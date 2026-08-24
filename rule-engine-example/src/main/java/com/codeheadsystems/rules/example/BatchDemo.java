package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.concurrent.BatchOutcome;
import com.codeheadsystems.rules.concurrent.RuleBatches;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;

/**
 * The same work as {@link PerOrderDemo}, one virtual thread and one session per order.
 *
 * <p>This is the concurrency story in full, and it is short because the two-tier split did the work
 * (§5.2): the {@link CompiledRuleSet} is immutable and shared, a session is single-writer and never
 * escapes its task, and {@code RuleBatches} closes each one in a try-with-resources so it cannot.
 * There is no lock anywhere and no pool to size.
 *
 * <p>Two things are worth reading closely.
 *
 * <p><strong>A batch that fails does not stop the others.</strong> Every input comes back as a
 * {@link BatchOutcome} holding either a value or a throwable, because §5.2 refuses to decide for you
 * what a partially-successful batch means -- retry the failures, fail the lot, or ship what worked
 * are all correct answers to different problems, and only the caller knows which.
 *
 * <p><strong>One {@link SessionOptions} fans out across every session</strong>, so anything mutable
 * it holds becomes shared state. Here that is exactly one thing, the {@link OpsPager} behind
 * {@code alertOps}, and it is written to be safe for it. A listener that accumulates per-session
 * state would need an options object per session instead.
 */
public final class BatchDemo {

  private BatchDemo() {
    throw new AssertionError("static helper");
  }

  /**
   * Runs every order concurrently and prints one line each.
   *
   * @param rules the compiled rule set, shared by every session
   * @return one outcome per order, in submission order
   */
  public static List<BatchOutcome<String>> run(final CompiledRuleSet rules) {
    final List<OrderEvent> feed = EventFeed.load();
    final List<String> orderIds = PerOrderDemo.orderIdsIn(feed);
    final OpsPager pager = new OpsPager();
    final SessionOptions options = OrderRules.options(pager, MatchingStrategy.NETWORK);

    final List<BatchOutcome<String>> outcomes = RuleBatches.run(rules, orderIds, (session, id) -> {
      final Ingest ingest = new Ingest(session);
      EventFeed.forOrder(feed, id).forEach(ingest::apply);
      final FireResult result = session.fireAllRules();
      /*
       * The batch returns a value rather than the FireResult itself. Nothing stops you returning
       * the result -- it is an immutable record and the session it came from is already closed --
       * but summarising inside the task is what keeps the interesting work parallel.
       */
      return id + ": " + result.emitted().stream().map(event -> event.eventType()).toList();
    }, options);

    for (final BatchOutcome<String> outcome : outcomes) {
      /*
       * `succeeded()` rather than a try/catch, because the failure is a value here. An exception
       * thrown inside a batch arrives unwrapped -- the ExecutionException the executor wrapped it in
       * is an artefact of how it ran rather than anything about what went wrong.
       */
      System.out.printf("  [%d] %s%n", outcome.index(),
          outcome.value().orElseGet(() -> "FAILED: " + outcome.failure().orElseThrow()));
    }
    System.out.printf("  alertOps was called %d time(s) across all sessions%n",
        pager.paged().size());
    return outcomes;
  }
}
