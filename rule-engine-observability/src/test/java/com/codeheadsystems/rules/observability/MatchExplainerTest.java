package com.codeheadsystems.rules.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Engine;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Why did rule R <em>not</em> fire?" (spec §7.2).
 *
 * <p>Every case here is one a rule author actually hits. The last two are the ones they cannot work
 * out from staring at the rule file, which is what makes the diagnostic worth building rather than
 * telling people to read their constraints again.
 */
class MatchExplainerTest {

  private static final RuleDefinition REVIEW = Rules.rule("high-value-order-review")
      .when("o", "Order", pattern -> pattern.gt("total", 10_000).eq("status", "PENDING"))
      .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
          .in("riskTier", "HIGH", "MEDIUM"))
      .then(actions -> actions.emit("order.flagged", "orderId", Rules.ref("o.id")))
      .build();

  @Test
  @DisplayName("a fact type nothing has inserted is named as the reason")
  void noFactsOfAType() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict()).contains("no Customer fact exists");
    }
  }

  @Test
  @DisplayName("the constraint that eliminated the candidates is named, with the actual value")
  void theEliminatingConstraint() {
    // "status was SHIPPED, expected PENDING" is the sentence an author needs, and it needs the
    // actual value -- naming only the constraint tells them what they already wrote.
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      for (int order = 0; order < 3; order++) {
        session.insert("Order", Facts.obj(
            "id", order, "total", 25_000, "status", "SHIPPED", "customerId", 7));
      }

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("3 Order(s) considered").contains("SHIPPED"));
      assertThat(explanation.patterns().getFirst().firstFailure())
          .hasValueSatisfying(failure -> {
            assertThat(failure.constraint().field()).isEqualTo("status");
            assertThat(failure.eliminated()).isEqualTo(3);
            assertThat(failure.actualValue().textValue()).isEqualTo("SHIPPED");
          });
    }
  }

  @Test
  @DisplayName("the constraint eliminating the MOST candidates is reported, not the first written")
  void theMostSelectiveFailure() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      // One fails on total; four fail on status. The status constraint is the one to fix.
      session.insert("Order",
          Facts.obj("id", 0, "total", 5, "status", "PENDING", "customerId", 7));
      for (int order = 1; order < 5; order++) {
        session.insert("Order", Facts.obj(
            "id", order, "total", 25_000, "status", "SHIPPED", "customerId", 7));
      }

      assertThat(new MatchExplainer(rules, session).explain(REVIEW.id())
          .patterns().getFirst().firstFailure())
          .hasValueSatisfying(failure -> {
            assertThat(failure.constraint().field()).isEqualTo("status");
            assertThat(failure.eliminated()).isEqualTo(4);
          });
    }
  }

  @Test
  @DisplayName("everything matches individually but nothing joins, which is the invisible case")
  void nothingJoins() {
    // The failure an author is least likely to work out: every fact they look at is fine, and the
    // rule still does not fire, because no pairing satisfies the join.
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 999, "riskTier", "HIGH"));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("none joined to any o").contains("id"));
      assertThat(explanation.patterns()).anySatisfy(pattern ->
          assertThat(pattern.joinNote()).isPresent());
    }
  }

  @Test
  @DisplayName("a rule that already fired says so, with the recency — the verdict nobody guesses")
  void alreadyFired() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));

      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

      // It fired, so it will not fire again -- and everything the author can see still looks right.
      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("refracted").contains("already fired at recency"));
      assertThat(explanation.describe()).contains("refracted");
    }
  }

  @Test
  @DisplayName("a rule that matches and has not fired says that too")
  void eligibleButNotYetFired() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));

      assertThat(new MatchExplainer(rules, session).explain(REVIEW.id()).verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict).contains("eligible"));
    }
  }

  @Test
  @DisplayName("pinned bindings answer 'I expected THESE facts to match'")
  void pinnedBindings() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      final FactHandle order = session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "SHIPPED", "customerId", 7));
      final FactHandle customer = session.insert("Customer",
          Facts.obj("id", 7, "riskTier", "LOW"));
      // Noise the unpinned form would have to wade through.
      for (int extra = 0; extra < 50; extra++) {
        session.insert("Order", Facts.obj("id", 100 + extra, "total", 1, "status", "X"));
      }

      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(REVIEW.id(), Map.of("o", order, "c", customer));

      // Exactly one candidate per pattern, so each result is a single chain of evaluations.
      assertThat(explanation.patterns()).allSatisfy(pattern ->
          assertThat(pattern.considered()).isEqualTo(1));
      assertThat(explanation.patterns().getFirst().firstFailure())
          .hasValueSatisfying(failure ->
              assertThat(failure.constraint().field()).isEqualTo("status"));
      assertThat(explanation.patterns().get(1).firstFailure())
          .hasValueSatisfying(failure ->
              assertThat(failure.constraint().field()).isEqualTo("riskTier"));
    }
  }

  @Test
  @DisplayName("pinning only some aliases leaves the rest resolved normally")
  void partiallyPinned() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      final FactHandle order = session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 8, "riskTier", "HIGH"));
      session.insert("Customer", Facts.obj("id", 9, "riskTier", "HIGH"));

      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(REVIEW.id(), Map.of("o", order));

      assertThat(explanation.patterns().getFirst().considered()).isEqualTo(1);
      assertThat(explanation.patterns().get(1).considered()).isEqualTo(2);
      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("none joined"));
    }
  }

  @Test
  @DisplayName("pinning a fact of the wrong type says so rather than silently not matching")
  void pinnedWrongType() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      final FactHandle customer = session.insert("Customer", Facts.obj("id", 7));

      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(REVIEW.id(), Map.of("o", customer));

      assertThat(explanation.patterns().getFirst().joinNote())
          .hasValueSatisfying(note -> assertThat(note).contains("is a Customer"));
    }
  }

  @Test
  @DisplayName("pinning a retracted fact says so rather than reporting a constraint failure")
  void pinnedRetractedFact() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 25_000));
      session.retract(order);

      assertThat(new MatchExplainer(rules, session)
          .explain(REVIEW.id(), Map.of("o", order))
          .patterns().getFirst().joinNote())
          .hasValueSatisfying(note -> assertThat(note).contains("not in working memory"));
    }
  }

  @Test
  @DisplayName("an unknown rule id fails loudly")
  void unknownRule() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      assertThatThrownBy(() -> new MatchExplainer(rules, session).explain("nope"))
          .isInstanceOf(NoSuchElementException.class)
          .hasMessageContaining("no rule with id 'nope'");
    }
  }

  @Test
  @DisplayName("the explanation renders as something a person can read")
  void rendering() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "SHIPPED", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));

      final String rendered = new MatchExplainer(rules, session).explain(REVIEW.id()).describe();

      assertThat(rendered)
          .contains("rule high-value-order-review")
          .contains("o: Order")
          .contains("c: Customer");
    }
  }
}
