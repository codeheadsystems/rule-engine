package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireOptions;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.ArrayList;
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
import org.openjdk.jmh.annotations.TearDown;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * §11.2's honest test: does re-testing every constraint on a changed fact actually dominate?
 *
 * <p>§11.2 chose (A'), a gated retract-and-reassert, over (B), differential propagation, and is
 * explicit about what would reverse that: "If profiling on a real rule set shows that cost
 * dominating -- the honest test is a hot fact type with many mutually-disjoint rules under a high
 * update rate -- differential propagation goes back in." It is equally explicit about what (B)
 * costs: a {@code dependsOn()} superset obligation binding every future contributor, where
 * over-declaring loses performance and under-declaring loses an activation. So the decision is worth
 * a benchmark rather than a turn of the roadmap.
 *
 * <p>This is that workload. One hot fact type, {@code disjointRules} rules each reading a different
 * field of it, and an update that changes a tested path. Under (A') the whole fact is retracted and
 * re-asserted, so every distinct alpha constraint for the type is evaluated again -- all of them,
 * whichever one changed. If that is the dominant term it will show as a per-update cost growing with
 * {@code disjointRules}.
 *
 * <p><strong>Why this is worth measuring now and was not before.</strong> Until §4.3 landed, a fire
 * cycle over a working set of four thousand cost 554us and an update cost single-digit microseconds:
 * propagation was invisible next to firing, and any effort spent on it would have been effort spent
 * on the wrong term. The fire cycle is now near-constant, so an update-heavy streaming session is a
 * shape where propagation could plausibly be what is left.
 *
 * <p><strong>The update deliberately changes a tested path without changing which rules match.</strong>
 * Every {@code watched} field is flipped between two values no rule accepts, so the rule set's match
 * result is identical before and after. What that isolates is propagation: the diff finds a change,
 * §3.4.1 runs the full retract-and-reassert, every alpha constraint is re-evaluated, and no firing
 * follows to confuse the measurement. An update that changed the match result would fold the fire
 * cycle back in, which is what {@code StreamingBenchmarks} measures and this must not.
 *
 * <p>Following {@code UpdateBenchmarks}, which learned all three the hard way: {@code updateOwned}
 * rather than {@code update} so §2.2's payload copy is off the clock, a batch per invocation because
 * the interesting cost is hundreds of nanoseconds, and one session per trial.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PropagationBenchmarks {

  /** How many updates one invocation applies, to keep the measured region clear of setup. */
  private static final int BATCH = 50;

  /**
   * How many facts of the hot type the session holds, so memories and indexes are populated.
   *
   * <p>They really are populated: see the constraint built in {@code setUpTrial}, and the note there
   * about the version of this class where they were not.
   */
  private static final int FACTS = 200;

  /** How many customers the facts spread over, which sets a two-pattern rule's join fan-out. */
  private static final int CUSTOMERS = 50;

  /**
   * Every payload carries this many fields whatever the rule set reads.
   *
   * <p>Fixed, for the reason {@code UpdateBenchmarks} records: sizing the payload from the parameter
   * would give the wide arm both more rules <em>and</em> a bigger payload, and the growth could not
   * be attributed to either.
   *
   * <p><strong>It also caps {@code disjointRules} at this value.</strong> Rule <em>n</em> reads
   * {@code watched}<em>n</em>, so a wider parameter would produce rules reading fields no payload
   * carries -- constraints on absent paths, which is a different experiment wearing this one's
   * name. Raise both together or not at all.
   */
  private static final int PAYLOAD_WIDTH = 64;

  /** A session under a hot fact type, and the payloads to write over one of its facts. */
  @State(Scope.Thread)
  public static class Propagating {

    /** How many mutually-disjoint rules read the hot type: §11.2's "many". */
    @Param({"1", "8", "64"})
    public int disjointRules;

    /**
     * Patterns per rule: 1 for an alpha-only rule, 2 for one whose matches are joined tuples.
     *
     * <p>The arity decides how much state an update tears down and rebuilds. At 1 the fact leaves
     * and re-enters each pattern memory and its indexes; at 2 it also takes every tuple it
     * participated in out of the beta memory, and the pinned walk re-derives them. All of that is
     * work (B) would skip for the patterns whose constraints read nothing that changed, so a shape
     * without it measures only the cheapest part of what (A') repeats.
     */
    @Param({"1", "2"})
    public int patternsPerRule;

    /** Which matcher. The streaming shape also re-derives beta matches on an update. */
    @Param({"NETWORK", "RETE"})
    public String matcher;

    private RuleSession session;
    private FactHandle handle;
    private List<ObjectNode> oneChanging;
    private List<ObjectNode> allChanging;

    /** Creates the state. JMH instantiates this; setup does the work. */
    public Propagating() {
      // Fields are populated in setUpTrial.
    }

    /** Builds the rule set, the working set, and the payloads. */
    @Setup(Level.Trial)
    public void setUpTrial() {
      final List<RuleDefinition> rules = new ArrayList<>(disjointRules);
      for (int index = 0; index < disjointRules; index++) {
        final int field = index;
        /*
         * The constraint ACCEPTS both values the payloads carry, and that is the whole difference
         * between this benchmark and a useless one. The first version tested eq("watched", "SET")
         * against payloads that only ever held CLEAR or OFF, so Network.insert's
         * `pattern.accepts(evaluation)` gate rejected every fact: no pattern memory was ever
         * populated, no index key ever written, no tuple ever derived. Every form of state churn an
         * update performs -- and therefore every form (B) would avoid -- was zero by construction,
         * leaving only alpha evaluation and the diff on the clock. That measured the cheapest
         * component of what (A') repeats and would have understated (B)'s prize while calling itself
         * (B)'s best case.
         */
        rules.add(patternsPerRule == 1
            ? Rules.rule("watches-" + field)
                .when("o", "Order", pattern -> pattern.in("watched" + field, "CLEAR", "OFF"))
                .then(actions -> actions.emit("hit-" + field))
                .build()
            : Rules.rule("watches-" + field)
                .when("a", "Order", pattern -> pattern.in("watched" + field, "CLEAR", "OFF"))
                .when("b", "Order", pattern -> pattern.ref("customerId", "a.customerId"))
                .then(actions -> actions.emit("hit-" + field))
                .build());
      }
      final CompiledRuleSet ruleSet = RuleCompiler.compile(rules);
      session = ruleSet.newSession(SessionOptions.builder()
          .matching(MatchingStrategy.valueOf(matcher))
          .build());

      for (int fact = 0; fact < FACTS; fact++) {
        final FactHandle inserted = session.insertOwned("Order", payload(fact, "CLEAR"));
        if (fact == 0) {
          handle = inserted;
        }
      }
      /*
       * Fired to quiescence so the measured region is a steady state rather than a first fire, and
       * so refraction holds the matches -- which is what makes step 5's per-rule invalidation do
       * real work during the measurement rather than return at its null check.
       *
       * The limit is raised because the facts now MATCH: sixty four rules over two hundred facts is
       * 12 800 activations at arity one and about 38 000 at arity two, and §4.7's default of ten
       * thousand cycles stops the setup dead. That default is a guard against a runaway rule set,
       * which this is not -- every firing here is a distinct match firing once.
       */
      session.fireAllRules(FireOptions.builder().maxCycles(1_000_000).maxFacts(1_000_000).build());

      /*
       * Cycled, and each element a distinct object: updateOwned takes ownership, so handing the same
       * object back would have the diff compare a payload against itself and measure nothing. Each
       * payload flips every watched field between CLEAR and OFF -- both of which every rule rejects,
       * so the match result never changes and this measures propagation rather than firing.
       *
       * Like insertBatchOwned and UpdateBenchmarks, this DELIBERATELY VIOLATES the ownership
       * contract by handing the same objects over on every invocation. Safe only because nothing
       * mutates them afterwards. Not a usage example.
       */
      oneChanging = new ArrayList<>(BATCH);
      allChanging = new ArrayList<>(BATCH);
      for (int index = 0; index < BATCH; index++) {
        oneChanging.add(payloadWithOneFlipped(index));
        allChanging.add(payload(0, index % 2 == 0 ? "CLEAR" : "OFF"));
      }
    }

    /**
     * A payload identical to the last except for one watched field.
     *
     * <p>The point of the whole class: exactly one tested path differs, so §3.4.1's diff reports one
     * change and refraction is cleared for one rule -- and (A') still retracts and re-asserts the
     * whole fact, re-evaluating every constraint the type has.
     *
     * @param generation which value the single varying field takes
     * @return the payload
     */
    private ObjectNode payloadWithOneFlipped(final int generation) {
      final ObjectNode node = JsonNodeFactory.instance.objectNode()
          .put("id", 0)
          .put("customerId", 0);
      for (int index = 0; index < PAYLOAD_WIDTH; index++) {
        node.put("watched" + index, index == 0 && generation % 2 == 1 ? "OFF" : "CLEAR");
      }
      return node;
    }

    /**
     * Builds a payload for the hot fact type.
     *
     * @param id the fact id
     * @param watchedValue what every watched field is set to; no rule accepts either value used
     * @return the payload
     */
    private ObjectNode payload(final int id, final String watchedValue) {
      final ObjectNode node = JsonNodeFactory.instance.objectNode()
          .put("id", id)
          // Spread over customers so a two-pattern rule's join has fan-out rather than pairing a
          // fact with itself: each order joins the others sharing its customer.
          .put("customerId", id % CUSTOMERS);
      for (int index = 0; index < PAYLOAD_WIDTH; index++) {
        node.put("watched" + index, watchedValue);
      }
      return node;
    }

    /** Closes the session. */
    @TearDown(Level.Trial)
    public void tearDown() {
      if (session != null) {
        session.close();
        session = null;
      }
    }

    /**
     * The session under test.
     *
     * @return the session
     */
    public RuleSession session() {
      return session;
    }

    /**
     * The fact being updated.
     *
     * @return its handle
     */
    public FactHandle handle() {
      return handle;
    }

    /**
     * Payloads differing from one another in exactly one tested path.
     *
     * @return the payloads
     */
    public List<ObjectNode> oneChanging() {
      return oneChanging;
    }

    /**
     * Payloads differing from one another in every tested path.
     *
     * @return the payloads
     */
    public List<ObjectNode> allChanging() {
      return allChanging;
    }
  }

  /**
   * An update changing exactly one tested path out of many: §11.2's case.
   *
   * <p>The number the decision turns on. If it grows with {@code disjointRules} -- and tracks
   * {@link #everyTestedPathChanges}, which changes all of them -- then (A') is charging a
   * one-field update the price of a whole-fact one, and that gap is what differential propagation
   * would recover.
   *
   * <p><strong>The {@code disjointRules = 1} row is not a contrast and must not be read as one.</strong>
   * With one rule there is one tested path, so "one path changed" and "every path changed" are the
   * same experiment and the two arms measure the same thing. The contrast begins at 8.
   *
   * @param state the session and payloads
   * @return the working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int oneTestedPathChanges(final Propagating state) {
    for (final ObjectNode payload : state.oneChanging()) {
      state.session().updateOwned(state.handle(), payload);
    }
    return state.session().workingMemory().size();
  }

  /**
   * An update changing every tested path: the same machinery, doing necessary work.
   *
   * <p>The control for {@link #oneTestedPathChanges}. Re-testing every constraint is right here,
   * because every constraint's field changed, so this is what (A') costs when it is not wasting
   * anything -- and what (B) would still cost.
   *
   * @param state the session and payloads
   * @return the working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int everyTestedPathChanges(final Propagating state) {
    for (final ObjectNode payload : state.allChanging()) {
      state.session().updateOwned(state.handle(), payload);
    }
    return state.session().workingMemory().size();
  }

  /**
   * The same updates, each followed by a fire to quiescence.
   *
   * <p>The difference from {@link #oneTestedPathChanges} is what a fire cycle costs after §4.3 -- and
   * the comparison that says whether propagation is now the term worth attacking in an update-heavy
   * streaming session.
   *
   * @param state the session and payloads
   * @return the working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int oneTestedPathChangesAndFires(final Propagating state) {
    for (final ObjectNode payload : state.oneChanging()) {
      state.session().updateOwned(state.handle(), payload);
      state.session().fireAllRules();
    }
    return state.session().workingMemory().size();
  }
}
