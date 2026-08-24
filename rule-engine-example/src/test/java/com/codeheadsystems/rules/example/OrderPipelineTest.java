package com.codeheadsystems.rules.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.EmittedEvent;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.TerminationReason;
import com.codeheadsystems.rules.testkit.Facts;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The other half of testing a rule set: fire it, and assert on what came out.
 *
 * <p>No mocking anywhere. Nothing performs I/O by default -- the default {@code EventSink} discards,
 * and {@link FireResult#emitted()} is sourced from the firing records -- so a rule is testable as a
 * pure function of the facts you put in. That property is worth protecting: the moment a sink
 * publishes inline, firing becomes a distributed transaction and this file needs a broker.
 */
class OrderPipelineTest {

  private static CompiledRuleSet rules;
  private static List<OrderEvent> feed;

  @BeforeAll
  static void compileOnce() {
    rules = OrderRules.compile();
    feed = EventFeed.load();
  }

  /**
   * Fires one order in its own session and returns the event names it emitted.
   *
   * @param orderId the order
   * @return the emitted event types, in firing order
   */
  private static List<String> emittedFor(final String orderId) {
    try (RuleSession session =
             rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.NETWORK))) {
      final Ingest ingest = new Ingest(session);
      EventFeed.forOrder(feed, orderId).forEach(ingest::apply);
      return session.fireAllRules().emitted().stream().map(EmittedEvent::eventType).toList();
    }
  }

  @Nested
  @DisplayName("a session per order")
  class PerOrder {

    @Test
    @DisplayName("a small, paid, in-stock order ships and was paid quickly")
    void o1() {
      assertThat(emittedFor("O-1"))
          .containsExactly("order.paidWithinTheHour", "order.readyToShip");
    }

    @Test
    @DisplayName("a high-value order from a high-risk customer is flagged first")
    void o2() {
      /*
       * containsExactly, not contains: the ORDER is the assertion. `order.flagged` comes first
       * because rule 1 carries salience 20, and §7.3 promises that is reproducible on any host.
       * An assertion that only checks membership would not notice conflict resolution breaking.
       */
      assertThat(emittedFor("O-2"))
          .containsExactly("order.flagged", "order.expedited", "order.readyToShip");
    }

    @Test
    @DisplayName("an unpaid order does not ship, and says nothing -- it concludes a fact instead")
    void o3() {
      assertThat(emittedFor("O-3")).containsExactly("order.expedited");
    }

    @Test
    @DisplayName("a hundred and ten units earns the bulk discount")
    void o4() {
      assertThat(emittedFor("O-4"))
          .containsExactly("order.readyToShip", "order.bulkDiscount", "order.expedited");
    }

    @Test
    @DisplayName("ready-to-ship fires once per order, not once per line item")
    void readyToShipDoesNotMultiply() {
      /*
       * The regression test for the first defect this example found in itself. O-1 and O-4 both
       * have two line items; with a plain positive companion pattern instead of the accumulate,
       * both emitted order.readyToShip twice.
       */
      assertThat(emittedFor("O-1")).filteredOn("order.readyToShip"::equals).hasSize(1);
      assertThat(emittedFor("O-4")).filteredOn("order.readyToShip"::equals).hasSize(1);
    }

    @Test
    @DisplayName("the cross-order rule cannot fire, because the session cannot see across orders")
    void scopeIsWhatDecides() {
      /*
       * Asserted rather than merely described in the README. `repeat-unpaid-customer` needs two
       * OrderUnpaid facts for one customer, and C-2's two unpaid orders are in two different
       * sessions. The rule is correct; the scope is what stops it.
       */
      assertThat(emittedFor("O-2")).doesNotContain("customer.atRisk");
      assertThat(emittedFor("O-3")).doesNotContain("customer.atRisk");
    }
  }

  @Nested
  @DisplayName("the ingestion path")
  class Ingestion {

    /** An order with no `priority` field at all, on the channel that makes rule 7 need it. */
    private static final String NO_PRIORITY = """
        {"id":"O-9","customerId":"C-1","placedAtEpochMs":1700000000000,"totalCents":120000,
         "channel":"MOBILE",
         "items":[{"sku":"WINCH-2T","qty":1,"unitPriceCents":120000,"inStock":true}]}""";

    @Test
    @DisplayName("defaults an absent `priority`, because CEL cannot read one")
    void absentFieldsAreNormalised() {
      try (RuleSession session =
               rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.NETWORK))) {
        new Ingest(session).apply(new OrderEvent("order.placed", Facts.json(NO_PRIORITY)));

        assertThat(session.workingMemory().factsOfType("Order").findFirst().orElseThrow()
            .payload().get("priority").booleanValue())
            .describedAs("Ingest fills it in; the event did not carry it")
            .isFalse();
        assertThatCode(session::fireAllRules).doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("and without that default the whole fire cycle fails, which is why it is there")
    void theSameOrderInsertedRawBlowsUp() {
      /*
       * The other half, and the reason this pair exists: an assertion that a default was applied
       * proves nothing about whether it MATTERED. Inserting the identical payload straight into the
       * session, bypassing Ingest, is what shows the cost -- rule 7's condition reads `o.priority`,
       * every operator map in the engine treats an absent field as a value, and CEL treats it as an
       * error. §6.4 has no per-match error policy, so one unreadable field stops the cycle for every
       * rule, not just for this one.
       */
      try (RuleSession session =
               rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.NETWORK))) {
        final var raw = Facts.json(NO_PRIORITY);
        raw.remove("items");
        raw.put("status", "PENDING");
        session.insert("Order", raw);

        assertThatThrownBy(session::fireAllRules)
            .describedAs("an absent field is an error to CEL, and it takes the cycle with it")
            .isInstanceOf(ExpressionEvaluationException.class);
      }
    }
  }

  @Nested
  @DisplayName("one long-lived session")
  class Streaming {

    @Test
    @DisplayName("sees across orders, so the cross-order rule fires")
    void crossOrderRuleFires() {
      final StreamingDemo.Outcome outcome = StreamingDemo.run(rules);

      assertThat(outcome.emitted()).extracting(EmittedEvent::eventType)
          .describedAs("the same rules, the same facts, a wider session")
          .contains("customer.atRisk");
    }

    @RepeatedTest(20)
    @DisplayName("the actor's fire cycles stay paired with its commands")
    void everySubmitConsumesExactlyOneFirePermit() {
      /*
       * Repeated, because this is a race and a single run passed while the defect was present. Two
       * of StreamingDemo's submits did not consume the permit their fire cycle released, which left
       * the semaphore one ahead: the stats read after each retract could then return before its own
       * fire cycle, and truth-maintenance withdrawal happens inside fireAllRules rather than inside
       * retract. It reported a conclusion still held about one run in forty.
       *
       * Asserted on the returned counts rather than on stdout, which is why run() returns them.
       */
      final StreamingDemo.Outcome outcome = StreamingDemo.run(rules);

      assertThat(outcome.finalStats().concludedFactCount())
          .describedAs("O-3 was the last unpaid order and it has been retracted, so nothing is"
              + " still concluded -- seeing 1 here means a stats read overtook its fire cycle")
          .isZero();
    }

    @Test
    @DisplayName("withdraws its conclusion when the payment arrives")
    void conclusionsAreWithdrawn() {
      try (RuleSession session =
               rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.RETE))) {
        final Ingest ingest = new Ingest(session);
        feed.stream()
            .filter(event -> !"payment.received".equals(event.type()))
            .forEach(ingest::apply);
        session.fireAllRules();
        assertThat(session.stats().concludedFactCount())
            .describedAs("four orders, none of them paid for")
            .isEqualTo(4);

        feed.stream()
            .filter(event -> "payment.received".equals(event.type()))
            .forEach(ingest::apply);
        final FireResult afterPayment = session.fireAllRules();

        assertThat(session.stats().concludedFactCount())
            .describedAs("three paid; only O-3's conclusion still has a reason to hold."
                + " Nothing retracted those facts -- their justifications stopped holding")
            .isEqualTo(1);
        assertThat(afterPayment.why()).isEqualTo(TerminationReason.DRAINED);
      }
    }

    @Test
    @DisplayName("retracting a finished order takes its conclusion with it")
    void retractingCascades() {
      try (RuleSession session =
               rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.RETE))) {
        final Ingest ingest = new Ingest(session);
        feed.forEach(ingest::apply);
        session.fireAllRules();
        assertThat(session.stats().concludedFactCount()).isEqualTo(1);

        ingest.retractOrder("O-3");
        session.fireAllRules();
        assertThat(session.stats().concludedFactCount())
            .describedAs("O-3 was the unpaid one; letting go of it withdrew what it justified")
            .isZero();
      }
    }
  }
}
