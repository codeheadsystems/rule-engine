package com.codeheadsystems.rules.concurrent;

import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Runs a batch per session across virtual threads (spec §5.2).
 *
 * <pre>{@code
 * List<BatchOutcome<FireResult>> outcomes = RuleBatches.run(rules, orders, (session, order) -> {
 *   session.insert("Order", order);
 *   return session.fireAllRules();
 * });
 * }</pre>
 *
 * <p>§5.2 names this shape -- {@code ExecutorService} with a virtual-thread-per-task executor -- as
 * "the version to build and ship first", and deliberately not {@code StructuredTaskScope}, which is
 * still preview in JDK 25 on its fifth JEP with no finalization filed. Nothing here needs
 * {@code --enable-preview}.
 *
 * <p><strong>The whole thing is thirty lines because §5.1's split already did the work.</strong> A
 * {@code CompiledRuleSet} is immutable and shared, a {@code RuleSession} is single-writer and cheap,
 * so "one virtual thread per session" needs no locking, no pooling and no session reuse. §5.5 puts
 * the practical ceiling at cache-line sharing rather than contention. If this class ever grows a
 * lock, something upstream of it has gone wrong.
 *
 * <p><strong>Sessions are created inside the task, not handed in</strong>, and closed in a
 * try-with-resources whatever the batch does. A session that escaped to the caller could be used
 * from a second thread, which is precisely the thing the single-writer model forbids; and a batch
 * that threw would otherwise leak its session's memories.
 *
 * <p>This helper is a convenience over an executor, not a replacement for one. A caller who already
 * runs their own executor, needs backpressure, or wants results streamed as they finish should call
 * {@code newSession()} on their own threads -- there is nothing to inherit and nothing to register.
 */
public final class RuleBatches {

  private RuleBatches() {
    throw new AssertionError("static helper");
  }

  /**
   * Runs one batch per input on its own virtual thread and its own session, with default options.
   *
   * @param <I> the input type
   * @param <R> the result type
   * @param rules the shared, immutable rule set
   * @param inputs one element per batch
   * @param batch what to do with a session and an input
   * @return one outcome per input, in submission order
   */
  public static <I, R> List<BatchOutcome<R>> run(final CompiledRuleSet rules,
      final List<I> inputs, final SessionBatch<I, R> batch) {
    return run(rules, inputs, batch, SessionOptions.defaults());
  }

  /**
   * Runs one batch per input on its own virtual thread and its own session.
   *
   * <p>Blocks until every batch has finished or failed. A batch that throws does not stop the
   * others; its exception comes back in its own {@link BatchOutcome}, unwrapped from the
   * {@code ExecutionException} the executor wraps it in, because the wrapper is an artefact of how
   * this ran rather than anything about what went wrong.
   *
   * <p><strong>Every task is already complete before any result is read.</strong> The executor is
   * closed by the try-with-resources, and {@code ExecutorService.close()} blocks until termination,
   * so the {@code get()} calls that follow never wait. That ordering is deliberate rather than
   * incidental: reading results inside the block would leave the executor open on any exception
   * thrown while collecting.
   *
   * <p><strong>On interrupt this returns normally, with the interrupt flag set.</strong>
   * {@code close()} responds to an interrupt by calling {@code shutdownNow()} and re-interrupting
   * the caller. A virtual-thread-per-task executor starts a thread at submit, so there are no
   * queued-but-unstarted tasks for it to cancel: every unfinished batch is a running thread that
   * gets interrupted, completes exceptionally, and arrives here as an ordinary failure carrying an
   * {@code InterruptedException}. Batches that had already finished carry their results.
   *
   * <p>So an interrupt looks like "some batches failed", which is exactly what happened, and the
   * caller finds out it was <em>their</em> interrupt from {@code Thread.currentThread()
   * .isInterrupted()} rather than from the shape of the result. Two earlier versions of this
   * paragraph described behaviour this code does not have -- one claimed the failures would be a
   * lie about the batches, the next that a {@code CancellationException} escapes. Neither happens;
   * the second was measured and refuted in review.
   *
   * @param <I> the input type
   * @param <R> the result type
   * @param rules the shared, immutable rule set
   * @param inputs one element per batch
   * @param batch what to do with a session and an input
   * <p><strong>This is the one place in the engine that fans a single {@code SessionOptions} across
   * threads</strong>, so it is where anything held in that options object becomes shared mutable
   * state. Listeners and host functions are the two that matter:
   * {@link com.codeheadsystems.rules.listener.RuleEngineListener} and
   * {@link com.codeheadsystems.rules.rhs.HostFunction} both state the resulting obligation, and
   * {@code TracingListener} meets it. Sharing one listener across a batch run is a supported thing
   * to want -- it is how you get an aggregate trace -- but a per-session view needs a per-session
   * options object.
   *
   * @param options the options every session is created with; anything mutable it holds is shared by
   *     all of them
   * @return one outcome per input, in submission order
   */
  public static <I, R> List<BatchOutcome<R>> run(final CompiledRuleSet rules,
      final List<I> inputs, final SessionBatch<I, R> batch, final SessionOptions options) {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(inputs, "inputs");
    Objects.requireNonNull(batch, "batch");
    Objects.requireNonNull(options, "options");
    final List<Future<R>> futures = new ArrayList<>(inputs.size());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (final I input : inputs) {
        futures.add(executor.submit(() -> {
          try (RuleSession session = rules.newSession(options)) {
            return batch.apply(session, input);
          }
        }));
      }
    }
    return collect(futures);
  }

  /**
   * Runs a function on its own virtual thread and session per input, for batches that need no input
   * beyond the session itself.
   *
   * @param <R> the result type
   * @param rules the shared, immutable rule set
   * @param count how many sessions to run
   * @param batch what to do with each session
   * @return one outcome per session, in submission order
   */
  public static <R> List<BatchOutcome<R>> run(final CompiledRuleSet rules, final int count,
      final Function<RuleSession, R> batch) {
    Objects.requireNonNull(batch, "batch");
    final List<Integer> indices = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      indices.add(index);
    }
    return run(rules, indices, (session, ignored) -> batch.apply(session));
  }

  /**
   * Turns finished futures into outcomes.
   *
   * @param <R> the result type
   * @param futures the futures, all complete
   * @return one outcome each, in the same order
   */
  private static <R> List<BatchOutcome<R>> collect(final List<Future<R>> futures) {
    final List<BatchOutcome<R>> outcomes = new ArrayList<>(futures.size());
    for (int index = 0; index < futures.size(); index++) {
      try {
        outcomes.add(new BatchOutcome<>(index, Optional.of(futures.get(index).get()),
            Optional.empty()));
      } catch (final ExecutionException failed) {
        outcomes.add(new BatchOutcome<>(index, Optional.empty(),
            Optional.of(failed.getCause())));
      } catch (final InterruptedException interrupted) {
        // Unreachable as written -- close() has already terminated the executor, so no get() here
        // blocks. Kept because it is checked, and because "unreachable" is a property of the call
        // above rather than of this method: anyone who moves the collection inside the executor
        // block needs this to already be right.
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while collecting batch results", interrupted);
      }
    }
    return outcomes;
  }

  /**
   * What one batch does with its session.
   *
   * <p>Not {@code BiFunction} so that a batch may throw; a checked exception from rule loading or
   * from the caller's own code is exactly what {@link BatchOutcome#failure()} is for, and forcing it
   * to be wrapped at the call site would defeat that.
   *
   * @param <I> the input type
   * @param <R> the result type
   */
  @FunctionalInterface
  public interface SessionBatch<I, R> {

    /**
     * Runs the batch.
     *
     * @param session the session, owned by this call and closed after it returns
     * @param input this batch's input
     * @return the batch's result
     * @throws Exception whatever the batch throws, reported in this batch's outcome
     */
    R apply(RuleSession session, I input) throws Exception;
  }
}
