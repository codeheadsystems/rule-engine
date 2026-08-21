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
          assertThat(verdict).contains("no combination").contains("c.id EQ o.customerId"));
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
          assertThat(verdict).contains("refracted").contains("already fired").contains("recency"));
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
          assertThat(verdict).contains("no combination"));
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

      assertThat(explanation.patterns().getFirst().note())
          .describedAs("a wrong-typed pinned fact is not a join outcome")
          .hasValueSatisfying(note -> assertThat(note).contains("is a Customer"));
      assertThat(explanation.patterns().getFirst().joinNote()).isEmpty();
    }
  }

  @Test
  @DisplayName("pinning a retracted fact says so rather than reporting a constraint failure")
  void pinnedRetractedFact() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 25_000));
      session.retract(order);

      // Fifty other Orders are in working memory, so a verdict of "no Order fact exists" would be
      // flatly untrue -- which is what reporting `considered = 0` here used to produce.
      for (int extra = 0; extra < 50; extra++) {
        session.insert("Order", Facts.obj("id", 100 + extra, "total", 25_000));
      }
      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(REVIEW.id(), Map.of("o", order));

      assertThat(explanation.patterns().getFirst().note())
          .hasValueSatisfying(note -> assertThat(note).contains("not in working memory"));
      assertThat(explanation.verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict).contains("not in working memory"));
    }
  }

  @Test
  @DisplayName("several matches that ALL fired are all reported, not one representative")
  void everyMatchFired() {
    // The verdict used to be built from the first survivor of each pattern -- an arbitrary
    // combination, usually not a match at all. With two orders and two customers inserted in an
    // order that makes the first-of-each pairing a non-match, it announced "eligible and has not
    // fired yet" about a rule that had fired twice.
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 1));
      session.insert("Order",
          Facts.obj("id", 2, "total", 25_000, "status", "PENDING", "customerId", 2));
      session.insert("Customer", Facts.obj("id", 2, "riskTier", "HIGH"));
      session.insert("Customer", Facts.obj("id", 1, "riskTier", "HIGH"));

      assertThat(session.fireAllRules().firedCount()).isEqualTo(2);

      assertThat(new MatchExplainer(rules, session).explain(REVIEW.id()).verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("refracted")
              .doesNotContain("has not fired yet"));
    }
  }

  @Test
  @DisplayName("a mix of fired and eligible matches says which is which")
  void someFiredSomeEligible() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 1));
      session.insert("Customer", Facts.obj("id", 1, "riskTier", "HIGH"));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

      // A second match arrives after the first has fired.
      session.insert("Order",
          Facts.obj("id", 2, "total", 25_000, "status", "PENDING", "customerId", 1));

      assertThat(new MatchExplainer(rules, session).explain(REVIEW.id()).verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("2 match(es)").contains("1 already fired").contains("1 still eligible"));
    }
  }

  @Test
  @DisplayName("a self-join that fired is reported as fired, not as eligible")
  void selfJoinThatFired() {
    // The old verdict paired the first survivor of o1 with the first survivor of o2 -- the same
    // handle -- producing a key that can never fire, so a self-join always looked eligible.
    final RuleDefinition duplicates = Rules.rule("duplicate-orders")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("dupe"))
        .build();
    final CompiledRuleSet rules = Engine.compile(duplicates);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING", "customerId", 7));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(2);

      assertThat(new MatchExplainer(rules, session).explain("duplicate-orders").verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("refracted").doesNotContain("has not fired yet"));
    }
  }

  @Test
  @DisplayName("a single fact does not join to itself on a self-join")
  void oneFactDoesNotSelfPair() {
    final RuleDefinition duplicates = Rules.rule("duplicate-orders")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("dupe"))
        .build();
    final CompiledRuleSet rules = Engine.compile(duplicates);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));

      assertThat(new MatchExplainer(rules, session).explain("duplicate-orders").verdict())
          .describedAs("one order cannot be both o1 and o2")
          .hasValueSatisfying(verdict -> assertThat(verdict).contains("no combination"));
    }
  }

  @Test
  @DisplayName("two joins each satisfiable alone but never together is reported, not missed")
  void joinsSatisfiableOnlySeparately() {
    // The old join note returned on the first join that had no satisfying pair. With each join
    // individually satisfiable it emitted nothing and the verdict claimed eligibility for a rule
    // with zero matches.
    final RuleDefinition twoJoins = Rules.rule("two-joins")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.hasField("k", true))
        .when("c", "C", pattern -> pattern.ref("x", "a.k").ref("y", "b.k"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRuleSet rules = Engine.compile(twoJoins);
    try (RuleSession session = rules.newSession()) {
      session.insert("A", Facts.obj("k", 1));
      session.insert("B", Facts.obj("k", 2));
      session.insert("C", Facts.obj("x", 1, "y", 99));
      session.insert("C", Facts.obj("x", 99, "y", 2));

      assertThat(new MatchExplainer(rules, session).explain("two-joins").verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("no combination").doesNotContain("has not fired yet"));
    }
  }

  @Test
  @DisplayName("a truncated search says so instead of reporting the budget as the count")
  void truncationIsReported() {
    // Two forty-fact populations with no join is 1600 real matches. Reporting "1000 match(es)" is a
    // confidently stated wrong number, and an author counting matches from it gets the limit back
    // rather than the truth.
    final RuleDefinition crossProduct = Rules.rule("cross")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.hasField("k", true))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRuleSet rules = Engine.compile(crossProduct);
    try (RuleSession session = rules.newSession()) {
      for (int index = 0; index < 40; index++) {
        session.insert("A", Facts.obj("k", index));
        session.insert("B", Facts.obj("k", index));
      }

      final Explanation explanation = new MatchExplainer(rules, session).explain("cross");

      assertThat(explanation.complete())
          .describedAs("1600 matches exceed the reporting bound")
          .isFalse();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("at least"));
      assertThat(explanation.describe()).contains("lower bounds");
    }
  }

  @Test
  @DisplayName("a complete search does not claim to be truncated")
  void completeSearchSaysSo() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.complete()).isTrue();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).doesNotContain("at least"));
      assertThat(explanation.describe()).doesNotContain("lower bounds");
    }
  }

  @Test
  @DisplayName("a rule with NO matches still terminates promptly, which is the whole point")
  void zeroMatchesDoesNotHang() {
    // The hazard the match bound did not cover: it stops once enough matches are FOUND, so a rule
    // with none never trips it and runs the whole cross product. "Why did my rule not fire" is
    // exactly that case. Measured at roughly cubic before the work budget: 400 facts per type took
    // four seconds, extrapolating to minutes at a couple of thousand.
    final RuleDefinition rule = Rules.rule("no-matches")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.hasField("k", true))
        .when("c", "C", pattern -> pattern.ref("x", "a.k"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRuleSet rules = Engine.compile(rule);
    try (RuleSession session = rules.newSession()) {
      for (int index = 0; index < 400; index++) {
        session.insert("A", Facts.obj("k", index));
        session.insert("B", Facts.obj("k", index));
        // No C matches any A, so the walk finds nothing and never hits a match bound.
        session.insert("C", Facts.obj("x", -index - 1));
      }

      final long startedAt = System.nanoTime();
      final Explanation explanation = new MatchExplainer(rules, session).explain("no-matches");
      final java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

      assertThat(took)
          .describedAs("a diagnostic that hangs on the question it exists to answer is worse "
              + "than not having one")
          .isLessThan(java.time.Duration.ofSeconds(2));
      assertThat(explanation.verdict()).isPresent();
    }
  }

  @Test
  @DisplayName("a self-join names the inequality, not just the join that would have held")
  void selfJoinBlamesTheRightThing() {
    // With one order the named join WOULD hold -- it is §1's implicit inequality that rejects the
    // pairing. Attributing it to the join alone sends the author to look at a constraint that is
    // satisfied.
    final RuleDefinition duplicates = Rules.rule("duplicate-orders")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("dupe"))
        .build();
    final CompiledRuleSet rules = Engine.compile(duplicates);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));

      assertThat(new MatchExplainer(rules, session).explain("duplicate-orders").verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("must bind a different fact from o1"));
    }
  }

  @Test
  @DisplayName("many fired matches are summarised, not listed to twenty thousand characters")
  void firedMatchesAreSummarised() {
    final RuleDefinition rule = Rules.rule("pairs")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.hasField("k", true))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRuleSet rules = Engine.compile(rule);
    try (RuleSession session = rules.newSession()) {
      for (int index = 0; index < 20; index++) {
        session.insert("A", Facts.obj("k", index));
        session.insert("B", Facts.obj("k", index));
      }
      session.fireAllRules();

      final Explanation explanation = new MatchExplainer(rules, session).explain("pairs");

      // §7.2 asks for a one-sentence answer. A count and a few examples carry the same information
      // as four hundred bracketed tuples.
      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> {
        assertThat(verdict).contains("refracted").contains("more");
        assertThat(verdict.length()).isLessThan(300);
      });
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
