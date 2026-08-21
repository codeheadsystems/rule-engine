package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Rules;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * §3.4.1's gated update: whether changing an untested field really does cost nothing.
 *
 * <p>Phase 1's exit criterion has two halves and §9 is careful that they test different things. The
 * correctness half is asserted on a counter in the test suite -- "assert it, don't infer it", since
 * "no propagation happened" is trivially satisfied by an implementation that never propagates. This
 * is the other half, which a counter cannot answer: whether the no-op path is <em>cheap</em>.
 *
 * <p><strong>Three things are deliberately outside the measured region, and the first version of
 * this benchmark got all three wrong.</strong> That version measured 228ns against 326ns and grew
 * 3.6x with payload size while reading identically on both matchers -- which is the signature of
 * measuring {@code update()}'s deep copy rather than the diff. §2.2's copy is already measured by
 * {@code EngineBenchmarks.insertBatch*}; measuring it again here would drown the thing this class
 * is named after. So:
 *
 * <ul>
 *   <li>{@code updateOwned}, not {@code update}, so the payload copy is not on the clock. Every
 *       payload is built in trial setup and used once, which is what that method's ownership
 *       contract requires.
 *   <li>A batch of updates per invocation. The interesting cost is a few hundred nanoseconds, and
 *       JMH's own guidance is that per-invocation setup cannot be trusted at that scale.
 *   <li>One session for the trial. Session construction is measured by
 *       {@code EngineBenchmarks.oneShotSession}, and nothing here accumulates across updates
 *       because the rules are never fired.
 * </ul>
 *
 * <p>The parameter that matters is {@code testedPaths}, not payload size. §3.4.2's argument for the
 * prefix trie is that a diff should cost the size of the <em>change</em> rather than the size of the
 * rule set, and the previous benchmark had six tested paths -- far too few for that to show. A
 * no-op update whose cost grows with {@code testedPaths} is a trie that is not doing its job.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class UpdateBenchmarks {

  /** How many updates one invocation applies, to keep the measured region well clear of setup. */
  private static final int BATCH = 100;

  /**
   * How many {@code watched} fields every payload carries, whatever the rule set reads.
   *
   * <p>Fixed on purpose. The first version of this benchmark sized the payload from
   * {@code testedPaths}, so the wide arm had both twenty times the tested paths <em>and</em> ten
   * times the payload -- and the growth it measured could not be attributed to either. Holding the
   * payload constant is what lets {@code testedPaths} mean what its name says.
   */
  private static final int PAYLOAD_WIDTH = 40;

  /** A session holding one fact, and the payloads to write over it. */
  @State(Scope.Thread)
  public static class Updating {

    /** How many distinct paths the rule set reads: what §3.4.2's diff has to walk. */
    @Param({"2", "40"})
    public int testedPaths;

    private RuleSession session;
    private FactHandle handle;
    private List<ObjectNode> untested;
    private List<ObjectNode> tested;

    @Setup(Level.Trial)
    public void setUpTrial() {
      /*
       * One rule per tested path, each reading a different field, so the rule set's tested-path set
       * grows with the parameter. Rules that share a constraint would be the node-sharing question,
       * which is a different benchmark.
       */
      final List<RuleDefinition> rules = new ArrayList<>(testedPaths);
      for (int index = 0; index < testedPaths; index++) {
        final int path = index;
        rules.add(Rules.rule("watches-" + path)
            .when("o", "Order", pattern -> pattern.eq("watched" + path, "SET"))
            .then(actions -> actions.emit("hit-" + path))
            .build());
      }
      final CompiledRuleSet ruleSet = RuleCompiler.compile(rules);
      /*
       * No matcher parameter, deliberately. DefaultRuleSession installs its Observer unconditionally
       * and that observer maintains the network's memories on every fact change whatever the
       * MatchingStrategy is; the strategy only selects matchesOf, which runs at fire time -- and
       * this benchmark never fires. Parameterising on it would have produced two identical columns
       * and invited someone to read the equality as evidence about memory maintenance costs. It is
       * not evidence; it is the same code run twice.
       */
      session = ruleSet.newSession();
      // Seeded with a generation no batch element uses, so the first update of a trial is a real
      // diff rather than an equals fast-path hit against an identical payload.
      handle = session.insertOwned("Order", payload(-1, "seed"));

      /*
       * Built once and cycled. Cycling matters: updateOwned takes ownership, so handing it the same
       * object twice in a row would have the diff compare a payload against itself, report no
       * change, and measure nothing. Across a batch the stored payload is always the previous
       * element and the incoming one is always a different object, including at the wrap.
       *
       * This benchmark DELIBERATELY VIOLATES the ownership contract, as insertBatchOwned does and
       * says: each payload is handed over on every invocation, millions of times per trial, and the
       * engine retains each one. It is safe only because nothing here ever mutates them. Do not
       * read it as a usage example.
       */
      untested = new ArrayList<>(BATCH);
      tested = new ArrayList<>(BATCH);
      for (int index = 0; index < BATCH; index++) {
        untested.add(payload(0, "spin-" + index));
        tested.add(payload(index, "ignored"));
      }
    }

    /**
     * Builds a payload.
     *
     * @param watchedGeneration varies a path the rules read, so the diff finds a change
     * @param unwatched varies a path no rule reads, so the diff finds nothing
     * @return the payload
     */
    private ObjectNode payload(final int watchedGeneration, final String unwatched) {
      final ObjectNode node = JsonNodeFactory.instance.objectNode()
          .put("id", 1)
          .put("unwatched", unwatched);
      // Every payload is the same width; only how many of these fields a rule reads varies.
      for (int index = 0; index < PAYLOAD_WIDTH; index++) {
        // Rotating which field is "SET" changes exactly one tested path per update.
        node.put("watched" + index, index == watchedGeneration % Math.max(testedPaths, 1)
            ? "SET" : "CLEAR");
      }
      return node;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (session != null) {
        session.close();
        session = null;
      }
    }

    public RuleSession session() {
      return session;
    }

    public FactHandle handle() {
      return handle;
    }

    public List<ObjectNode> untested() {
      return untested;
    }

    public List<ObjectNode> tested() {
      return tested;
    }
  }

  /**
   * Changing only a path no rule reads: §3.4.1's no-op.
   *
   * @param state the session and payloads
   * @return the working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int untestedPathBatch(final Updating state) {
    for (final ObjectNode payload : state.untested()) {
      state.session().updateOwned(state.handle(), payload);
    }
    return state.session().workingMemory().size();
  }

  /**
   * Changing a path a rule reads: retract and reassert on the same handle.
   *
   * @param state the session and payloads
   * @return the working-memory size, so nothing is optimised away
   */
  @Benchmark
  public int testedPathBatch(final Updating state) {
    for (final ObjectNode payload : state.tested()) {
      state.session().updateOwned(state.handle(), payload);
    }
    return state.session().workingMemory().size();
  }
}
