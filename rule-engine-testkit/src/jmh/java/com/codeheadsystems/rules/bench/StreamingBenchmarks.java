package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import java.util.Map;
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
import org.openjdk.jmh.infra.Blackhole;

/**
 * The streaming workload Phase 3 exists for, at a steady state (spec §11.1's option B).
 *
 * <p>{@code EngineBenchmarks} says plainly that it does not have this shape: it inserts a batch and
 * fires once, which is what §11.1 chose TREAT for and what the streaming matcher is worst at. This
 * class is insert-then-fire repeated against a working memory that stays the same size.
 *
 * <h2>Why this could not be written until eviction existed</h2>
 *
 * <p><strong>A streaming benchmark without a bound measures two things at once and can separate
 * neither.</strong> Insert continuously and working memory grows, so the join walk grows, so the
 * conflict set grows -- and a rising per-operation cost could be any of them. §4.4's eviction fixes
 * the working set, which is what makes the terms separable at all.
 *
 * <h2>The experiment</h2>
 *
 * <p>Two benchmarks over one steady-state session, differing in one statement:
 *
 * <ul>
 *   <li>{@link #insertOnly} inserts a fact and does not fire. That is the maintenance cost: the
 *       alpha network, the pattern memories and their indexes, the beta memory under the streaming
 *       shape, and the eviction the insert triggers.
 *   <li>{@link #insertAndFire} inserts the same fact and fires to quiescence.
 * </ul>
 *
 * <p><strong>The difference between them is the fire cycle -- but what is in that differs by
 * matcher, and reading it as one thing is a mistake this comment made in its first version.</strong>
 * Under Rete the join is already materialised, so the difference is the conflict-set rebuild. Under
 * TREAT the join happens <em>inside</em> the fire ({@code NetworkAgenda}: "Nothing pinned: TREAT
 * re-joins the whole working memory at fire time"), so the difference there is join walk plus
 * rebuild. §4.3's push-based agenda addresses the rebuild and is available to the Rete shape only --
 * §4.3 says so in as many words -- so a flat TREAT column after that commit is the expected result
 * rather than a failed optimisation.
 *
 * <p><strong>What this cannot show.</strong> The maintenance column is flat by construction, not by
 * discovery: the population being joined against is fixed at {@link #CUSTOMERS} and does not scale
 * with the cap, so an arriving fact probes the same few facts at every size. That makes it evidence
 * that maintenance is O(1) in the streamed type's working set, and <em>not</em> evidence for §9's
 * "amortizes join cost", which needs a shape where the counterpart population scales too.
 *
 * <p>Read the columns as ratios, never as absolutes: what is being asked is which term grows with
 * the working set, not how many nanoseconds this machine takes. {@code docs/benchmarks.md} carries
 * the numbers, two independent runs of them, and what they do not establish.
 *
 * <p>Run with {@code ./gradlew :rule-engine-testkit:jmh}; see {@code docs/benchmarks.md}.
 */
/*
 * Deliberately no @Fork/@Warmup/@Measurement here, and the reason is a trap worth leaving written
 * down. This class first carried them, to answer buildlogic.jmh-conventions.gradle.kts asking that
 * you "lengthen the iterations before hanging a decision on a small difference" -- and they did
 * nothing. The Gradle JMH plugin passes -f, -wi and -i on the command line from its own `jmh` block,
 * and command-line options beat annotations, so the run came back at the shared sizing with the
 * annotations sitting there implying otherwise. Three ways out, and this class took the third:
 * raise the shared sizing, which makes the whole suite slow enough that it stops being run; register
 * a JavaExec on org.openjdk.jmh.Main over the jmh runtime classpath with its own -f/-wi/-i, which is
 * about eight lines and gives per-class sizing without touching the shared block; or replicate.
 * docs/benchmarks.md quotes two independent runs of this class and the agreement between them, which
 * is what the wide within-run intervals actually needed.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class StreamingBenchmarks {

  /** Reference facts, loaded once and never evicted -- the population a per-type cap protects. */
  private static final int CUSTOMERS = 8;

  /**
   * How many facts the session holds. The independent variable of the whole experiment.
   *
   * <p>Three points rather than two, because the question is the shape of the curve and two points
   * fit any line.
   */
  @Param({"250", "1000", "4000"})
  private int workingSet;

  /** Which matcher. The naive oracle is excluded: it is quadratic here and measures nothing new. */
  @Param({"NETWORK", "RETE"})
  private String matcher;

  private CompiledRuleSet rules;
  private RuleSession session;
  private long nextId;

  /** Creates the benchmark state. */
  public StreamingBenchmarks() {
    // JMH instantiates this; fields are set up in the trial setup below.
  }

  /**
   * Compiles the rule set once per trial.
   *
   * <p>A two-pattern join, because a single-fact rule has no join to amortise and would measure the
   * alpha network twice over.
   */
  @Setup(Level.Trial)
  public void compile() {
    final List<RuleDefinition> rule = List.of(Rules.rule("review")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("review", "orderId", Rules.ref("o.id")))
        .build());
    this.rules = RuleCompiler.compile(rule);
  }

  /**
   * Opens a session and streams it up to its cap, so measurement starts at the steady state.
   *
   * <p>Per iteration rather than per trial: refraction and the beta memory are bounded by eviction
   * but a session still carries history, and an iteration that began where the last one ended would
   * measure a different point on the curve than the one it reports.
   *
   * <p>The warm-up fires as it goes. Filling silently and then firing once would leave the whole
   * conflict set to be built inside the first measured operation.
   */
  @Setup(Level.Iteration)
  public void fill() {
    this.session = rules.newSession(SessionOptions.builder()
        .matching(MatchingStrategy.valueOf(matcher))
        .eviction(EvictionPolicy.perType(Map.of("Order", workingSet)))
        .build());
    for (int customer = 0; customer < CUSTOMERS; customer++) {
      session.insert("Customer", Facts.obj("id", customer));
    }
    // One cap's worth plus a margin, so the first measured operation is evicting like every
    // operation after it rather than being the one that starts.
    for (int order = 0; order < workingSet + 64; order++) {
      insertOne();
      session.fireAllRules();
    }
  }

  /** Closes the session. */
  @TearDown(Level.Iteration)
  public void close() {
    session.close();
  }

  /**
   * Maintenance only: one insert, one eviction, no fire cycle.
   *
   * @param blackhole consumes the handle so the insert cannot be optimised away
   */
  @Benchmark
  public void insertOnly(final Blackhole blackhole) {
    blackhole.consume(insertOne());
  }

  /**
   * Maintenance plus one fire cycle to quiescence.
   *
   * <p>Exactly {@link #insertOnly} with a fire, so the difference between the two columns is the
   * fire cycle and nothing else.
   *
   * @param blackhole consumes the result so the fire cannot be optimised away
   */
  @Benchmark
  public void insertAndFire(final Blackhole blackhole) {
    blackhole.consume(insertOne());
    blackhole.consume(session.fireAllRules());
  }

  /**
   * Inserts one order against a rotating customer.
   *
   * @return the handle, so a caller can hand it to a blackhole
   */
  private Object insertOne() {
    final long id = nextId++;
    return session.insert("Order",
        Facts.obj("id", id, "total", 10, "customerId", id % CUSTOMERS));
  }
}
