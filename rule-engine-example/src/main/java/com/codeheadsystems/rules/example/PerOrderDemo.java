package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.EmittedEvent;
import com.codeheadsystems.rules.session.FireRecord;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.List;

/**
 * A session per order: the shape most applications want, and the one to start from.
 *
 * <p>Create a session, put in the facts this decision is about, fire once, read the result, close
 * it. A session is cheap to allocate and single-writer by design, so "one per request" and "one per
 * message" are both correct; the expensive, shared, immutable thing is the {@link CompiledRuleSet},
 * built once in {@link OrderRules}.
 *
 * <p>What this shape gives up is visible in the output: rule 6, {@code repeat-unpaid-customer},
 * never fires here. It counts a customer's unpaid orders, and a session holding one order can only
 * ever count to one. Nothing is wrong with the rule -- the session cannot see the facts it needs.
 * That is the first thing to check when a rule does not fire, before reaching for
 * {@link DiagnosticsDemo}.
 */
public final class PerOrderDemo {

  private PerOrderDemo() {
    throw new AssertionError("static helper");
  }

  /**
   * Runs one session per order and prints what fired.
   *
   * @param rules the compiled rule set, shared by every session
   * @param pager the host function {@code alertOps} dispatches to
   * @return the result of each order's fire call, in feed order
   */
  public static List<FireResult> run(final CompiledRuleSet rules, final OpsPager pager) {
    final List<OrderEvent> feed = EventFeed.load();
    /*
     * NETWORK, the default. It recomputes joins per fire cycle from indexed pattern memories, which
     * is the right trade when a session fires once or twice and is then thrown away -- there is no
     * second cycle to amortise a maintained join across. StreamingDemo makes the other choice for
     * the opposite reason.
     */
    final var options = OrderRules.options(pager, MatchingStrategy.NETWORK);

    return orderIdsIn(feed).stream().map(orderId -> {
      try (RuleSession session = rules.newSession(options)) {
        final Ingest ingest = new Ingest(session);
        EventFeed.forOrder(feed, orderId).forEach(ingest::apply);
        /*
         * One fire call, after every fact is in. Firing per insert would also work and would give
         * the same final state -- but it fires rules against a half-built picture, so an order
         * whose payment has not been applied yet fires `unpaid-order` and then withdraws it. The
         * engine is happy either way; the log is not.
         */
        final FireResult result = session.fireAllRules();
        print(orderId, result);
        return result;
      }
    }).toList();
  }

  /**
   * The order ids the feed places, in feed order.
   *
   * @param feed the events
   * @return the order ids
   */
  static List<String> orderIdsIn(final List<OrderEvent> feed) {
    return feed.stream()
        .filter(event -> "order.placed".equals(event.type()))
        .map(event -> event.text("id"))
        .toList();
  }

  /**
   * Prints one order's outcome.
   *
   * @param orderId the order
   * @param result what firing produced
   */
  private static void print(final String orderId, final FireResult result) {
    System.out.printf("  %s: %d rule(s) fired, stopped because %s%n",
        orderId, result.firedCount(), result.why());
    for (final FireRecord record : result.fired()) {
      System.out.printf("      fired %s%n", record.key().ruleId());
    }
    for (final EmittedEvent event : result.emitted()) {
      /*
       * The events are the output. They come back on the FireResult rather than being pushed
       * anywhere, which is what makes a rule set testable with no mocking: assert on this list.
       * An EventSink that publishes inline would turn every fire call into a distributed
       * transaction, and would block the fire loop while it did.
       */
      System.out.printf("      emit  %-24s %s%n", event.eventType(), event.payload());
    }
  }
}
