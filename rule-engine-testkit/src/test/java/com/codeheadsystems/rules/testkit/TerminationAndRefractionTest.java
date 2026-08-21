package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.session.TerminationReason;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Phase 0 exit criterion that matters most: firing terminates, and refraction is scoped
 * correctly (spec sections 4.4 and 9).
 *
 * <p>Without refraction, firing all rules does not terminate on ordinary rule sets -- a rule whose
 * right-hand side does not invalidate its own left-hand side would re-fire on the same match until
 * the cycle limit. That is why refraction is a Phase 0 primitive rather than an optimisation.
 */
class TerminationAndRefractionTest {

  /** Mutates nothing at all. Under a broken refraction implementation this never terminates. */
  private static final RuleDefinition ALERT_ON_FLAGGED = Rules.rule("alert-on-flagged")
      .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
      .then(actions -> actions.emit("order.flagged", "orderId", Rules.ref("o.id")))
      .build();

  /** Reads only {@code status}. Its refraction must not be cleared by a change to {@code total}. */
  private static final RuleDefinition READS_STATUS = Rules.rule("reads-status")
      .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
      .then(actions -> actions.emit("status.seen", "orderId", Rules.ref("o.id")))
      .build();

  /** Reads only {@code total}. Exists so that changing it clears somebody's refraction. */
  private static final RuleDefinition READS_TOTAL = Rules.rule("reads-total")
      .when("o", "Order", pattern -> pattern.gt("total", 0))
      .then(actions -> actions.emit("total.seen", "orderId", Rules.ref("o.id")))
      .build();

  @Test
  @DisplayName("a rule whose RHS mutates nothing fires exactly once per match and terminates")
  void terminatesOnANonMutatingRule() {
    final FireResult result = Engine.result(
        Engine.compile(ALERT_ON_FLAGGED), SessionOptions.defaults(),
        session -> {
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
          session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
          session.insert("Order", Facts.obj("id", 3, "status", "SHIPPED"));
        });

    // The count is the assertion. "The test did not hang" is not the same claim.
    assertThat(result.firedCount()).isEqualTo(2);
    assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
    assertThat(result.residualAgendaSize()).isZero();
    // Order 2 fires FIRST. Salience ties, so recency breaks the tie, and the default is LIFO --
    // the freshest facts win, which is the classic engines' behaviour and section 4.2's default.
    // This is the "why did B fire before A" question in miniature, and it is answerable from the
    // record: both inputs to the decision are on it.
    assertThat(result.emitted()).extracting(event -> event.payload().get("orderId").intValue())
        .containsExactly(2, 1);
    assertThat(result.fired().getFirst().recency())
        .isGreaterThan(result.fired().getLast().recency());
  }

  @Test
  @DisplayName("firing again produces nothing: the same match does not re-fire")
  void refractionSurvivesAcrossFireCalls() {
    final CompiledRuleSet ruleSet = Engine.compile(ALERT_ON_FLAGGED);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
      assertThat(session.fireAllRules().firedCount()).isZero();
      assertThat(session.fireAllRules().why()).isEqualTo(TerminationReason.DRAINED);
    }
  }

  @Test
  @DisplayName("retracting a bound fact makes the match eligible again")
  void retractReenables() {
    final CompiledRuleSet ruleSet = Engine.compile(ALERT_ON_FLAGGED);
    try (RuleSession session = ruleSet.newSession()) {
      final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

      session.retract(handle);
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an update to a path THIS rule tests makes it eligible again")
  void updateOnATestedPathReenables() {
    final CompiledRuleSet ruleSet = Engine.compile(READS_STATUS, READS_TOTAL);
    try (RuleSession session = ruleSet.newSession()) {
      final FactHandle handle =
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "total", 100));
      assertThat(firedRules(session.fireAllRules()))
          .containsExactlyInAnyOrder("reads-status", "reads-total");

      // Leaves the rule matching, but changes a path it reads.
      session.update(handle, Facts.obj("id", 1, "status", "PENDING", "total", 100));
      assertThat(session.fireAllRules().firedCount()).isZero();

      session.update(handle, Facts.obj("id", 1, "status", "SHIPPED", "total", 100));
      session.update(handle, Facts.obj("id", 1, "status", "PENDING", "total", 100));
      assertThat(firedRules(session.fireAllRules())).contains("reads-status");
    }
  }

  @Test
  @DisplayName("an update to an UNTESTED path does not re-enable anything")
  void updateOnAnUntestedPathChangesNothing() {
    final CompiledRuleSet ruleSet = Engine.compile(READS_STATUS);
    try (RuleSession session = ruleSet.newSession()) {
      final FactHandle handle = session.insert("Order",
          Facts.obj("id", 1, "status", "PENDING", "customerEmail", "old@example.com"));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

      session.update(handle, Facts.obj(
          "id", 1, "status", "PENDING", "customerEmail", "new@example.com"));

      assertThat(session.fireAllRules().firedCount()).isZero();
      // ... and the new value is nonetheless what the engine holds. The payload is always
      // replaced; only propagation is conditional.
      assertThat(session.get(handle).orElseThrow().payload().get("customerEmail").textValue())
          .isEqualTo("new@example.com");
    }
  }

  @Test
  @DisplayName("an update to ANOTHER rule's path does not re-enable this one")
  void refractionScopingIsPerRuleEndToEnd() {
    // This is the assertion section 4.4 calls essential and section 10 audits. Type-wide clearing
    // would make reads-status fire a second time because reads-total's field moved -- a rule
    // firing twice for a reason no author can predict from reading their rule.
    final CompiledRuleSet ruleSet = Engine.compile(READS_STATUS, READS_TOTAL);
    try (RuleSession session = ruleSet.newSession()) {
      final FactHandle handle =
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "total", 100));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(2);

      session.update(handle, Facts.obj("id", 1, "status", "PENDING", "total", 999));

      assertThat(firedRules(session.fireAllRules())).containsExactly("reads-total");
    }
  }

  @Test
  @DisplayName("a fact that stops matching before firing does not fire")
  void matchesLostBeforeFiringDoNotFire() {
    final CompiledRuleSet ruleSet = Engine.compile(ALERT_ON_FLAGGED);
    try (RuleSession session = ruleSet.newSession()) {
      final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.update(handle, Facts.obj("id", 1, "status", "SHIPPED"));

      assertThat(session.fireAllRules().firedCount()).isZero();
    }
  }

  /**
   * The rule ids that fired, in firing order.
   *
   * @param result the fire result
   * @return the ids
   */
  private static List<String> firedRules(final FireResult result) {
    return result.fired().stream().map(record -> record.key().ruleId()).toList();
  }
}
