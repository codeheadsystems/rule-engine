package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Phase 4's exit criterion: do N concurrent sessions on M cores scale near-linearly (spec §9)?
 *
 * <p><strong>What is inside the measured region, stated before any number is read.</strong> This
 * repository has shipped six benchmarks that measured something other than their name, so the
 * boundary is written down first and the write-up is held to it.
 *
 * <p>{@link #concurrentBatches} measures: submitting {@link Work#SESSIONS} tasks to an
 * already-running pool, each task creating a session, inserting {@link Work#FACTS} order/customer
 * pairs, firing to completion, and closing; then waiting for all of them. It does <em>not</em>
 * measure rule compilation, executor construction, or thread creation -- all three happen in trial
 * setup, because they are per-process costs and including them would flatter the single-threaded
 * column at high thread counts.
 *
 * <p><strong>Total work is fixed and thread count varies.</strong> That is the only arrangement in
 * which "near-linear" is a claim about the engine: {@code SESSIONS} batches are run no matter what
 * {@code threads} is, so perfect scaling is {@code time(N) == time(1) / N} and any departure is
 * contention, allocation pressure or cache-line sharing rather than a change in the workload. A
 * benchmark that ran {@code SESSIONS} batches <em>per thread</em> would show flat per-invocation
 * time and prove nothing at all.
 *
 * <p>The per-session workload is deliberately identical to {@code EngineBenchmarks.oneShotSession},
 * so the {@code threads=1} column here and that benchmark's number are directly comparable and any
 * gap is this harness's own overhead.
 *
 * <p><strong>Two controls, because a raw scaling curve on a laptop measures the laptop.</strong>
 * On an SMT part under a frequency governor, a one-thread run boosts to a clock an all-core run
 * cannot hold, so some sub-linearity is guaranteed before the engine does anything at all.
 * Attributing that to the engine would be this repository's seventh benchmark that measured
 * something other than its name.
 *
 * <ul>
 *   <li>{@link #sharedNothingBaseline} runs the identical harness -- same pool, same task count,
 *       same submit-and-await -- over arithmetic that touches no shared state whatsoever. Whatever
 *       curve it shows is the machine's ceiling, and only the gap between the two curves is the
 *       engine's.
 *   <li>The {@code sharing} parameter compiles either one rule set shared by every task or one per
 *       thread. §5.5's claim is that sharing an immutable {@code CompiledRuleSet} costs nothing;
 *       identical columns are what that claim looks like when it is true, and the private column is
 *       what it must be compared against rather than against an ideal.
 * </ul>
 *
 * <p><strong>Three forks, set on the class rather than left to the runner.</strong> The shared
 * {@code buildlogic.jmh-conventions} sizing is one fork, which is right for the rest of the suite
 * and wrong here, and an advisory sentence in a comment is not a setting. JMH's reported error is
 * the spread across
 * iterations <em>within</em> one fork, and every parameter combination here is its own fork, so at
 * {@code -f 1} the ± says nothing about how much of a difference between two columns is just JIT and
 * layout luck. That is not a theoretical worry: at {@code threads=1} the {@code sharing} parameter
 * is a null experiment -- one thread means one thread-local rule set, so PRIVATE and SHARED are the
 * same configuration -- and a single-fork run put them 3.8% apart with error bars that did not
 * overlap. Read that row first on any run: whatever separation it shows is the floor below which no
 * difference elsewhere in the table means anything.
 *
 * <p>§5.5 predicts the ceiling is "cache-line sharing, not locking", since nothing in a
 * {@code CompiledRuleSet} mutates and sessions share nothing. {@link #sessionCreation} isolates the
 * constant §10 calls the concurrency throughput ceiling -- the per-task cost that no amount of
 * parallelism amortises away.
 *
 * <p>Run with {@code ./gradlew :rule-engine-testkit:jmh}; see {@code docs/benchmarks.md}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
public class ConcurrencyBenchmarks {

  /** A rule set, a thread pool, and a fixed amount of work to push through them. */
  @State(Scope.Benchmark)
  public static class Work {

    /**
     * How many sessions one measured invocation runs, whatever the thread count.
     *
     * <p>Fixed on purpose: this is what makes the thread-count columns a scaling curve rather than
     * four unrelated numbers.
     */
    public static final int SESSIONS = 256;

    /** Order/customer pairs per session. Matches {@code EngineBenchmarks.oneShotSession}. */
    public static final int FACTS = 50;

    /**
     * How many threads share the fixed workload.
     *
     * <p>Platform threads, not virtual ones, and the reason matters: this measures whether the
     * engine scales across cores, and a virtual-thread executor's carrier pool would silently cap
     * at the core count anyway while adding scheduling noise on top. §5.2's virtual-thread model is
     * about task count, not about parallelism -- {@code RuleBatches} exists for that and inherits
     * whatever this curve says.
     */
    @Param({"1", "2", "4", "8", "16"})
    public int threads;

    /**
     * Whether every task uses one shared rule set, or each thread gets its own.
     *
     * <p>The direct test of §5.5's "zero contention, because nothing about it mutates after
     * compile". PRIVATE removes all sharing; if SHARED is slower, the sharing costs something.
     */
    @Param({"SHARED", "PRIVATE"})
    public String sharing;

    private CompiledRuleSet ruleSet;
    private ThreadLocal<CompiledRuleSet> perThread;
    private SessionOptions options;
    private ExecutorService executor;

    /** Compiles the rules and starts the pool, both outside every measured region. */
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
      /*
       * One rule set per THREAD, keyed by the thread rather than by the batch index. Indexing by
       * `batch % threads` was the obvious thing and is subtly wrong: the in-flight batches are
       * usually consecutive, so usually distinct, but one slow task widens the window and lets two
       * concurrent batches land on the same index. "Usually private" is not a control.
       *
       * Compiled separately rather than copied, so the arrays, maps and literal nodes are genuinely
       * distinct objects on distinct cache lines. Reusing one instance would make PRIVATE a rename
       * of SHARED and the control worthless.
       */
      perThread = ThreadLocal.withInitial(() -> RuleCompiler.compile(List.of(alphaOnly, withJoin)));
      options = SessionOptions.defaults();
      executor = Executors.newFixedThreadPool(threads);
      // Start every thread before measuring. A pool creates threads lazily on submit, so without
      // this the eight-thread column would pay for eight thread creations inside its first
      // invocation and the warmup would hide it unevenly across the columns.
      warmPool();
    }

    /** Forces every pool thread into existence. */
    private void warmPool() {
      final List<Future<Object>> started = new ArrayList<>(threads);
      for (int index = 0; index < threads; index++) {
        started.add(executor.submit(() -> {
          Thread.sleep(20);
          return null;
        }));
      }
      await(started);
    }

    /** Shuts the pool down. */
    @TearDown(Level.Trial)
    public void tearDown() {
      executor.shutdownNow();
    }

    /**
     * The pool under test.
     *
     * @return the executor
     */
    public ExecutorService executor() {
      return executor;
    }

    /**
     * The rule set the calling thread should use.
     *
     * <p>Called from inside the task, not from the submitting loop, because under PRIVATE the answer
     * depends on which thread is asking. That is what makes "no two concurrent tasks share a rule
     * set" true by construction rather than true of the schedule the pool happened to choose.
     *
     * <p>Compilation happens on first use per thread, which is why the pool is warmed and why JMH's
     * warmup iterations matter: by the time anything is measured every thread has its own.
     *
     * @return the rule set for this thread
     */
    public CompiledRuleSet ruleSetForThisThread() {
      return "SHARED".equals(sharing) ? ruleSet : perThread.get();
    }

    /**
     * The options every session is created with.
     *
     * @return the options
     */
    public SessionOptions options() {
      return options;
    }
  }

  /**
   * A fixed batch of sessions, spread across a fixed thread pool.
   *
   * <p>Returns the total firing count so nothing can be optimised away. It is not a cross-column
   * check -- JMH discards the value and nothing compares it between thread counts -- so a column
   * that scaled beautifully by doing less work would still have to be caught by reading the code.
   *
   * @param state the rule set, pool and sizing
   * @return the total number of rule firings across the batch
   */
  @Benchmark
  public int concurrentBatches(final Work state) {
    final List<Future<Integer>> futures = new ArrayList<>(Work.SESSIONS);
    for (int batch = 0; batch < Work.SESSIONS; batch++) {
      futures.add(state.executor().submit(() -> {
        try (RuleSession session =
            state.ruleSetForThisThread().newSession(state.options())) {
          for (int index = 0; index < Work.FACTS; index++) {
            session.insert("Order", Facts.obj(
                "id", index, "total", 25_000, "status", "PENDING", "customerId", index));
            session.insert("Customer", Facts.obj("id", index, "riskTier", "HIGH"));
          }
          return session.fireAllRules().firedCount();
        }
      }));
    }
    int fired = 0;
    for (final Integer count : await(futures)) {
      fired += count;
    }
    return fired;
  }

  /**
   * The same harness over work that shares nothing: the machine's own scaling ceiling.
   *
   * <p>This is the experimental control, and it is the number {@link #concurrentBatches} must be
   * read against rather than against a straight line. It submits the same {@link Work#SESSIONS}
   * tasks to the same pool and awaits them the same way; the only difference is that the task body
   * is arithmetic on locals. No allocation, no shared reads, no memory traffic beyond the stack --
   * so any sub-linearity here is frequency scaling, SMT, or the harness, and none of it is the
   * engine's.
   *
   * <p>The iteration count is tuned so a single task costs roughly what an engine batch costs,
   * which keeps the submit-and-await overhead the same fraction of both curves. It does not need to
   * match exactly; what matters is that it is the same order of magnitude rather than a thousand
   * times shorter, where the harness would dominate and the control would flatter itself.
   *
   * @param state the pool and sizing
   * @return the accumulated total, so nothing can be folded away
   */
  @Benchmark
  public long sharedNothingBaseline(final Work state) {
    final List<Future<Long>> futures = new ArrayList<>(Work.SESSIONS);
    for (int batch = 0; batch < Work.SESSIONS; batch++) {
      futures.add(state.executor().submit(() -> {
        long accumulator = 0;
        for (int index = 0; index < 300_000; index++) {
          accumulator += index ^ (accumulator >>> 3);
        }
        return accumulator;
      }));
    }
    long total = 0;
    for (final Long value : await(futures)) {
      total += value;
    }
    return total;
  }

  /** Just a rule set, for the session-creation constant. */
  @State(Scope.Benchmark)
  public static class Creation {

    private CompiledRuleSet ruleSet;

    /** Compiles the rules. */
    @Setup(Level.Trial)
    public void setUp() {
      ruleSet = RuleCompiler.compile(List.of(Rules.rule("flag-high-value")
          .when("o", "Order", pattern -> pattern.gt("total", 10_000).eq("status", "PENDING"))
          .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
          .build()));
    }

    /**
     * The rule set.
     *
     * @return the rule set
     */
    public CompiledRuleSet ruleSet() {
      return ruleSet;
    }
  }

  /**
   * Session creation and close, with no facts and no firing.
   *
   * <p>§10 calls this "your concurrency throughput ceiling", and it is the one number in this class
   * that is a constant rather than a curve: it multiplies every task in the across-session model,
   * so a microsecond here is a microsecond off every batch regardless of how many cores are thrown
   * at the problem.
   *
   * <p>Overrides the class-level time unit to nanoseconds, which is not cosmetic: JMH's score
   * formatter drops to {@code ~= 10^-4} notation below a thousandth of the chosen unit, so in
   * milliseconds this row prints no digits at all and the figure cannot be quoted from the run that
   * produced it.
   *
   * @param state the rule set
   * @return the session's id, so the session cannot be optimised away
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public Object sessionCreation(final Creation state) {
    try (RuleSession session = state.ruleSet().newSession()) {
      return session.sessionId();
    }
  }

  /**
   * Waits for every future, turning failures into errors rather than losing them.
   *
   * @param <T> the result type
   * @param futures the submitted tasks
   * @return their results, in submission order
   */
  private static <T> List<T> await(final List<Future<T>> futures) {
    final List<T> results = new ArrayList<>(futures.size());
    for (final Future<T> future : futures) {
      try {
        results.add(future.get());
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted", interrupted);
      } catch (final ExecutionException failed) {
        throw new IllegalStateException("benchmark task failed", failed.getCause());
      }
    }
    return results;
  }
}
