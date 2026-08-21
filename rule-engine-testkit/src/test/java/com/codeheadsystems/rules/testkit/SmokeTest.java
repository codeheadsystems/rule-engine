package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.TerminationReason;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end example from the README, plus the across-session concurrency model.
 *
 * <p>If this test drifts from the README, the README is wrong.
 */
class SmokeTest {

  /** The spec's own worked example, in section 6.2, expressed against the Java rule model. */
  private static RuleDefinition highValueOrderReview() {
    return Rules.rule("high-value-order-review")
        .salience(10)
        .noLoop()
        .when("o", "Order", pattern -> pattern
            .gt("total", 10000)
            .eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern
            .ref("id", "o.customerId")
            .in("riskTier", "HIGH", "MEDIUM"))
        .then(actions -> actions
            .setField("o", "status", "REVIEW")
            .emit("order.flagged",
                "orderId", Rules.ref("o.id"),
                "reason", "high value + risk tier"))
        .build();
  }

  @Test
  @DisplayName("the README example fires once, flags the order, and drains")
  void readmeExample() {
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(highValueOrderReview()));

    try (RuleSession session = rules.newSession()) {
      final var order = session.insert("Order", Facts.json("""
          {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
      session.insert("Customer", Facts.json("""
          {"id": 7, "riskTier": "HIGH"}"""));

      final FireResult result = session.fireAllRules();

      assertThat(result.firedCount()).isEqualTo(1);
      assertThat(result.fired().getFirst().key().ruleId()).isEqualTo("high-value-order-review");
      assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
      assertThat(result.residualAgendaSize()).isZero();
      assertThat(result.ruleSetVersion()).startsWith("sha256:");

      assertThat(result.emitted()).singleElement().satisfies(event -> {
        assertThat(event.eventType()).isEqualTo("order.flagged");
        assertThat(event.payload().get("orderId").intValue()).isEqualTo(1);
        assertThat(event.context().sessionId()).isEqualTo(session.sessionId());
        assertThat(event.context().ruleSetVersion()).isEqualTo(rules.version());
      });

      assertThat(session.get(order).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("REVIEW");

      // Firing again does nothing: refraction, and the rule no longer matches anyway.
      assertThat(session.fireAllRules().firedCount()).isZero();
    }
  }

  @Test
  @DisplayName("one compiled rule set serves many concurrent sessions with no shared mutable state")
  void acrossSessionConcurrency() throws Exception {
    // Section 5.2's primary concurrency primitive. The rule set is immutable and freely shareable,
    // and a session is cheap enough to create and discard per batch item -- a very different cost
    // model from pooling a small number of expensive stateful engine instances.
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(highValueOrderReview()));

    final List<FireResult> results;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      final List<Future<FireResult>> futures = IntStream.range(0, 200)
          .mapToObj(index -> executor.submit(() -> {
            try (RuleSession session = rules.newSession()) {
              session.insert("Order", Facts.obj(
                  "id", index, "total", 25000, "status", "PENDING", "customerId", index));
              session.insert("Customer", Facts.obj("id", index, "riskTier", "HIGH"));
              return session.fireAllRules();
            }
          }))
          .toList();
      results = futures.stream().map(SmokeTest::get).toList();
    }

    assertThat(results).hasSize(200).allSatisfy(result -> {
      assertThat(result.firedCount()).isEqualTo(1);
      assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
    });
    // Every session got a distinct id, which is half of what makes an emitted event globally
    // correlatable; the fact handle is the other half.
    assertThat(results.stream()
        .map(result -> result.emitted().getFirst().context().sessionId())
        .distinct()
        .count()).isEqualTo(200);
  }

  /**
   * Unwraps a future, turning its checked exceptions into unchecked ones.
   *
   * @param future the future
   * @return its value
   */
  private static FireResult get(final Future<FireResult> future) {
    try {
      return future.get();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (final java.util.concurrent.ExecutionException failed) {
      throw new IllegalStateException(failed.getCause());
    }
  }
}
