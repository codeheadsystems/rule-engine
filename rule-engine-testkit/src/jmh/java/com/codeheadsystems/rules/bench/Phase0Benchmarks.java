package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import com.codeheadsystems.rules.value.Comparisons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The Phase 0 baselines that spec section 10 asks for.
 *
 * <p>These exist to be <em>recorded</em>, not admired. Phase 1's entire claim is "faster than
 * this": single-fact rules match by index lookup rather than by scan, and an update to an untested
 * field is a measured no-op. Neither claim is falsifiable without a number from the naive engine
 * that preceded it, and running the benchmark after the optimisation is how a project ends up with
 * an optimisation nobody can prove helped.
 *
 * <p>Three of the four are microbenchmarks of the primitives section 10 names; the fourth is
 * end-to-end throughput, which is what actually moves.
 *
 * <p>Run with {@code ./gradlew :rule-engine-testkit:jmh}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class Phase0Benchmarks {

  /**
   * Comparison-operator evaluation: the innermost operation of every alpha test.
   *
   * <p>Worth watching because it is where numeric canonicalisation lands. Equality on a number
   * allocates a {@code BigDecimal} and strips trailing zeros on both sides, which is what makes
   * {@code 10000} and {@code 10000.0} agree -- and it is not free. Phase 1 hoists the literal side
   * to compile time; this is the number that says how much that is worth.
   */
  @State(Scope.Thread)
  public static class Comparison {

    /** The operator under test. */
    @Param({"EQ", "NE", "IN", "GT"})
    public String operator;

    private Operator op;
    private JsonNode textValue;
    private JsonNode numberValue;
    private JsonNode literal;

    /** Prepares the values. */
    @Setup(Level.Trial)
    public void setUp() {
      op = Operator.valueOf(operator);
      textValue = JsonNodeFactory.instance.textNode("PENDING");
      numberValue = JsonNodeFactory.instance.numberNode(25_000);
      literal = switch (op) {
        case IN -> Facts.array("HIGH", "MEDIUM", "PENDING");
        case GT -> JsonNodeFactory.instance.numberNode(10_000);
        default -> JsonNodeFactory.instance.textNode("PENDING");
      };
    }

    /**
     * The value this operator should be applied to.
     *
     * @return a text node for the string operators, a number node for the ordering one
     */
    public JsonNode value() {
      return op == Operator.GT ? numberValue : textValue;
    }
  }

  /**
   * Evaluates one comparison.
   *
   * @param state the prepared values
   * @return whether the comparison held
   */
  @Benchmark
  public boolean comparison(final Comparison state) {
    return Comparisons.test(state.op, state.value(), state.literal);
  }

  /** A batch of pre-built payloads, and a rule set to insert them into. */
  @State(Scope.Thread)
  public static class Insertion {

    /** How many facts one measured invocation inserts. Fixed, so nothing grows unboundedly. */
    public static final int BATCH = 100;

    /** How many fields each payload carries. A deep copy is O(payload), so this is the axis. */
    @Param({"5", "50"})
    public int payloadFields;

    private CompiledRuleSet ruleSet;
    private ObjectNode[] payloads;

    /** Compiles a rule set and builds the batch. */
    @Setup(Level.Trial)
    public void setUp() {
      ruleSet = RuleCompiler.compile(List.of(Rules.rule("bench")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("out", "id", Rules.ref("o.id")))
          .build()));
      payloads = new ObjectNode[BATCH];
      for (int item = 0; item < BATCH; item++) {
        final ObjectNode payload = JsonNodeFactory.instance.objectNode()
            .put("status", "PENDING")
            .put("id", item);
        for (int index = 0; index < payloadFields; index++) {
          payload.put("field" + index, "value" + index);
        }
        payloads[item] = payload;
      }
    }

    /**
     * The compiled rules.
     *
     * @return the rule set
     */
    public CompiledRuleSet ruleSet() {
      return ruleSet;
    }

    /**
     * The batch to insert.
     *
     * @return the payloads
     */
    public ObjectNode[] payloads() {
      return payloads;
    }
  }

  /**
   * A fresh session plus a fixed batch of inserts, with the engine copying each payload.
   *
   * <p>Section 2.2 is blunt that the copy is "frequently the largest single per-operation cost in
   * the engine -- larger than the alpha tests it protects", which is a claim worth having a number
   * for. The gap between this and {@link #insertBatchOwned} <em>is</em> that cost.
   *
   * <p>The batch is fixed and the session is discarded, so this measures inserts rather than an
   * ever-growing map -- and it includes session-creation cost, which section 5.5 calls the
   * concurrency throughput ceiling.
   *
   * @param state the prepared batch
   * @return the resulting working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int insertBatchCopying(final Insertion state) {
    try (RuleSession session = state.ruleSet().newSession()) {
      for (final ObjectNode payload : state.payloads()) {
        session.insert("Order", payload);
      }
      return session.workingMemory().size();
    }
  }

  /**
   * The same batch, handed over without a copy.
   *
   * <p><strong>This benchmark deliberately violates the ownership contract</strong>: it hands the
   * same trees to a new session on every invocation, where the contract says a transferred payload
   * belongs to one engine forever. That is safe here only because nothing in the benchmark mutates
   * them, and it is the only way to measure what the copy costs without paying for a copy inside
   * the harness. Do not read this as a usage example.
   *
   * @param state the prepared batch
   * @return the resulting working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int insertBatchOwned(final Insertion state) {
    try (RuleSession session = state.ruleSet().newSession()) {
      for (final ObjectNode payload : state.payloads()) {
        session.insertOwned("Order", payload);
      }
      return session.workingMemory().size();
    }
  }

  /** A refraction memory with a realistic number of fired matches. */
  @State(Scope.Thread)
  public static class Refraction {

    /** How many matches have already fired. */
    @Param({"1000", "100000"})
    public int fired;

    private RefractionMemory memory;
    private ActivationKey hit;
    private ActivationKey miss;

    /** Fills the memory. */
    @Setup(Level.Trial)
    public void setUp() {
      memory = new RefractionMemory();
      for (int index = 0; index < fired; index++) {
        memory.record(new ActivationKey("rule", new long[] {index, index + 1L}), index);
      }
      hit = new ActivationKey("rule", new long[] {fired / 2L, fired / 2L + 1});
      miss = new ActivationKey("rule", new long[] {-1L, -2L});
    }

    /**
     * The memory under test.
     *
     * @return the memory
     */
    public RefractionMemory memory() {
      return memory;
    }

    /**
     * A key that is present.
     *
     * @return the key
     */
    public ActivationKey hit() {
      return hit;
    }

    /**
     * A key that is absent.
     *
     * @return the key
     */
    public ActivationKey miss() {
      return miss;
    }
  }

  /**
   * The refraction probe, which runs once per selection.
   *
   * <p>Section 2.1 calls the refraction set one of the hottest maps in the engine, which is why the
   * activation key is cached rather than recomputed inside {@code hashCode()}.
   *
   * @param state the prepared memory
   * @param blackhole consumes both results
   */
  @Benchmark
  public void refractionProbe(final Refraction state, final Blackhole blackhole) {
    blackhole.consume(state.memory().shouldFire(state.hit()));
    blackhole.consume(state.memory().shouldFire(state.miss()));
  }

  /** A rule set and a fact population for end-to-end throughput. */
  @State(Scope.Thread)
  public static class Matching {

    /** How many facts of each type the session holds. */
    @Param({"10", "100"})
    public int facts;

    /** Which matcher to measure. NAIVE is the Phase 0 oracle; NETWORK is Phase 1. */
    @Param({"NETWORK", "NAIVE"})
    public String matcher;

    private CompiledRuleSet ruleSet;
    private com.codeheadsystems.rules.session.SessionOptions options;

    /** Compiles a small but representative rule set: one alpha rule and one join rule. */
    @Setup(Level.Trial)
    public void setUp() {
      final RuleDefinition alphaOnly = Rules.rule("flag-high-value")
          .when("o", "Order", pattern -> pattern.gt("total", 10_000).eq("status", "PENDING"))
          .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
          .build();
      final RuleDefinition withJoin = Rules.rule("review-risky-order")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
              .eq("riskTier", "HIGH"))
          .then(actions -> actions.emit("review", "id", Rules.ref("o.id")))
          .build();
      ruleSet = RuleCompiler.compile(List.of(alphaOnly, withJoin));
      options = com.codeheadsystems.rules.session.SessionOptions.builder()
          .matching(com.codeheadsystems.rules.session.MatchingStrategy.valueOf(matcher))
          .build();
    }

    /**
     * The session configuration under test.
     *
     * @return the options
     */
    public com.codeheadsystems.rules.session.SessionOptions options() {
      return options;
    }

    /**
     * The compiled rules.
     *
     * @return the rule set
     */
    public CompiledRuleSet ruleSet() {
      return ruleSet;
    }
  }

  /**
   * A whole session: create, insert, fire to completion, discard.
   *
   * <p>This is the shape v1 targets, so it is the number that matters most. It also measures
   * session-creation cost, which section 5.5 calls the concurrency throughput ceiling -- the
   * constant multiplying every task in the across-session model.
   *
   * <p>Parameterised by matcher so the phases are compared directly rather than across separate
   * runs on possibly different hardware. NAIVE is the Phase 0 oracle -- rescan and re-test every
   * fact of a type, every fire cycle. NETWORK is Phase 1: pattern memories hold only what matches,
   * and equality joins probe an index.
   *
   * @param state the prepared rule set
   * @return the fire result
   */
  @Benchmark
  public FireResult oneShotSession(final Matching state) {
    try (RuleSession session = state.ruleSet().newSession(state.options())) {
      for (int index = 0; index < state.facts; index++) {
        session.insert("Order", Facts.obj(
            "id", index, "total", 25_000, "status", "PENDING", "customerId", index));
        session.insert("Customer", Facts.obj("id", index, "riskTier", "HIGH"));
      }
      return session.fireAllRules();
    }
  }
}
