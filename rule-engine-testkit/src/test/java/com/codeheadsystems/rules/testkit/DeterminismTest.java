package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec section 7.3's determinism contract: same rule set, same facts, same insertion order, same
 * firing sequence -- on every host and every run.
 *
 * <p>An engine that is 99.9% deterministic has none of the properties this buys. Rules are not
 * testable, because a golden-output test is meaningless; incidents are not reproducible, because a
 * replay gives a different answer; decisions are not auditable months later.
 *
 * <p>The threat section 7.3 says actually bites is hash iteration order reaching the agenda,
 * "because it usually <em>looks</em> stable in testing" -- a single-run test passes happily while a
 * {@code HashSet} sits on the path to the conflict set. The shuffle harness is what catches it.
 */
class DeterminismTest {

  private static final List<RuleDefinition> RULES = List.of(
      Rules.rule("flag-high-value").salience(10)
          .when("o", "Order", pattern -> pattern.gt("total", 10000))
          .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
          .build(),
      Rules.rule("greet-customer")
          .when("c", "Customer", pattern -> pattern.in("riskTier", "HIGH", "MEDIUM"))
          .then(actions -> actions.emit("greeted", "id", Rules.ref("c.id")))
          .build(),
      Rules.rule("review-risky-order")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
              .eq("riskTier", "HIGH"))
          .then(actions -> actions.emit("review",
              "orderId", Rules.ref("o.id"), "customerId", Rules.ref("c.id")))
          .build(),
      Rules.rule("audit-everything").salience(-5)
          .when("o", "Order")
          .then(actions -> actions.emit("audited", "id", Rules.ref("o.id")))
          .build());

  private static final Consumer<com.codeheadsystems.rules.session.RuleSession> SCENARIO =
      session -> {
        session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
        session.insert("Order",
            Facts.obj("id", 1, "total", 25000, "status", "PENDING", "customerId", 7));
        session.insert("Customer", Facts.obj("id", 8, "riskTier", "MEDIUM"));
        session.insert("Order",
            Facts.obj("id", 2, "total", 500, "status", "PENDING", "customerId", 8));
        session.insert("Order",
            Facts.obj("id", 3, "total", 90000, "status", "SHIPPED", "customerId", 7));
      };

  @Test
  @DisplayName("rule declaration order does not reach the firing sequence")
  void shuffleDeterminism() {
    final FiringSequence sequence = ShuffleHarness.assertDeterministic(RULES, SCENARIO);

    // Sanity: the scenario has to actually exercise something for the assertion to mean anything.
    assertThat(sequence.steps()).hasSizeGreaterThan(5);
  }

  @Test
  @DisplayName("two identical runs agree exactly, down to the effects")
  void repeatedRunsAgree() {
    final FiringSequence first = Engine.run(Engine.compile(RULES.toArray(RuleDefinition[]::new)),
        SessionOptions.defaults(), SCENARIO);
    final FiringSequence second = Engine.run(Engine.compile(RULES.toArray(RuleDefinition[]::new)),
        SessionOptions.defaults(), SCENARIO);

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("salience orders firing, and it is the first term, ahead of recency")
  void salienceDominates() {
    final FiringSequence sequence = Engine.run(Engine.compile(RULES.toArray(RuleDefinition[]::new)),
        SessionOptions.defaults(), SCENARIO);
    final List<String> rules = sequence.steps().stream().map(FiringSequence.Step::ruleId).toList();

    // salience 10 first, salience -5 last, everything else between.
    assertThat(rules.getFirst()).isEqualTo("flag-high-value");
    assertThat(rules.getLast()).isEqualTo("audit-everything");
    assertThat(rules.indexOf("greet-customer")).isGreaterThan(rules.indexOf("flag-high-value"));
  }

  @Test
  @DisplayName("determinism holds under a dry run too, which is what makes cutover diffs useful")
  void dryRunIsDeterministic() {
    // Running both rule sets over the same facts and comparing firing plans is only meaningful if
    // the plan is stable. This is the cheap version of a run-both-and-compare cutover.
    ShuffleHarness.assertDeterministic(RULES, SCENARIO, ShuffleHarness.DEFAULT_PERMUTATIONS,
        SessionOptions.builder().dryRun(true).build());
  }

  @Test
  @DisplayName("an untested-field update does not make a fact fresher for conflict resolution")
  void untestedChurnDoesNotReorderFiring() {
    // Section 2.1 states this as intended behaviour, not as an accident: recency advances only on
    // an update that changes a path the network tests, so a fact whose untested fields churn never
    // becomes "fresher". If it did, firing order would depend on traffic no rule can see.
    final RuleDefinition rule = Rules.rule("audit")
        .when("o", "Order")
        .then(actions -> actions.emit("audited", "id", Rules.ref("o.id")))
        .build();

    final FiringSequence untouched = Engine.run(session -> {
      session.insert("Order", Facts.obj("id", 1));
      session.insert("Order", Facts.obj("id", 2));
      session.insert("Order", Facts.obj("id", 3));
    }, rule);

    final FiringSequence churned = Engine.run(session -> {
      final var first = session.insert("Order", Facts.obj("id", 1));
      session.insert("Order", Facts.obj("id", 2));
      session.insert("Order", Facts.obj("id", 3));
      session.update(first, Facts.obj("id", 1, "touched", true));
    }, rule);

    // The rule constrains nothing, so it tests no paths and the update is a measured no-op.
    assertThat(churned).isEqualTo(untouched);
    // Newest first, because salience ties and recency defaults to LIFO.
    assertThat(churned.steps()).extracting(step -> step.handles().getFirst())
        .containsExactly(2L, 1L, 0L);
  }

  @Test
  @DisplayName("an update on a TESTED path does make a fact fresher, and reorders accordingly")
  void testedChurnDoesReorderFiring() {
    final RuleDefinition rule = Rules.rule("audit")
        .when("o", "Order", pattern -> pattern.hasField("id", true))
        .then(actions -> actions.emit("audited", "id", Rules.ref("o.id")))
        .build();

    final FiringSequence sequence = Engine.run(session -> {
      final var first = session.insert("Order", Facts.obj("id", 1));
      session.insert("Order", Facts.obj("id", 2));
      session.insert("Order", Facts.obj("id", 3));
      // /id is tested by this rule, so this bumps recency and the oldest fact becomes the newest.
      session.update(first, Facts.obj("id", 11));
    }, rule);

    assertThat(sequence.steps()).extracting(step -> step.handles().getFirst())
        .containsExactly(0L, 2L, 1L);
  }
}
