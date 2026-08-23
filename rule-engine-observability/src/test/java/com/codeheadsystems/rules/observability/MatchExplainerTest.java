package com.codeheadsystems.rules.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
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

  /**
   * §1's own example of "the first ten rules most people write": no {@code Payment} for this
   * {@code Order}. The negated pattern joins back to the bound one, which is what makes it a
   * question about <em>this</em> order rather than about payments in general.
   */
  private static final RuleDefinition UNPAID = Rules.rule("unpaid-shipped-order")
      .when("o", "Order", pattern -> pattern.eq("status", "SHIPPED"))
      .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
      .then(actions -> actions.emit("order.unpaid", "orderId", Rules.ref("o.id")))
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
  @DisplayName("a type whose facts were evicted says so, rather than reading as never-inserted")
  void evictedFactsAreNamed() {
    /*
     * The blind spot §4.4's eviction opens. "no Order fact exists" is true and complete and sends
     * an author to look at their rule, when the answer is their eviction cap -- and the two states
     * are indistinguishable from working memory alone, because the facts really are gone. §7.2's
     * whole claim is that this class answers "why did R not fire" better than a trace can, and
     * without this it answered a whole category of that question misleadingly.
     */
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession(SessionOptions.builder()
        .eviction(EvictionPolicy.perType(Map.of("Order", 2)))
        .build())) {
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      for (int id = 0; id < 5; id++) {
        // Below the threshold, so the surviving orders fail their own alpha test. Eviction is NOT
        // why this rule is silent here, and the clause is deliberately still shown: it is a neutral
        // count of what the session let go of, not a claim about causation. A clause that appeared
        // only when the explainer judged eviction to blame would be a judgement it cannot make --
        // the evicted facts are gone, so whether they would have matched is unknowable.
        session.insert("Order",
            Facts.obj("id", id, "total", 10, "status", "PENDING", "customerId", 7));
      }

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict().orElseThrow())
          .describedAs("the surviving candidates AND what the session let go of")
          .contains("none matched o")
          .contains("3 Order fact(s) evicted this session");
    }
  }

  @Test
  @DisplayName("a type evicted down to nothing is not reported as never inserted")
  void fullyEvictedTypeIsNamed() {
    final RuleDefinition ordersOnly = Rules.rule("orders-only")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
        .build();
    final CompiledRuleSet rules = Engine.compile(ordersOnly);
    try (RuleSession session = rules.newSession(SessionOptions.builder()
        .eviction(EvictionPolicy.perType(Map.of("Order", 1)))
        .build())) {
      for (int id = 0; id < 4; id++) {
        session.insert("Order", Facts.obj("id", id, "total", 100));
      }
      session.retract(session.workingMemory().factsOfType("Order").findFirst().orElseThrow()
          .handle());

      final Explanation explanation = new MatchExplainer(rules, session).explain("orders-only");

      assertThat(explanation.verdict().orElseThrow())
          .describedAs("empty for two different reasons, and only one of them is the author's")
          .contains("no Order fact exists")
          .contains("3 Order fact(s) evicted this session");
    }
  }

  @Test
  @DisplayName("the join verdict names an evicted type, which is where streaming actually lands")
  void evictionIsNamedOnTheJoinVerdict() {
    /*
     * The case a per-type cap produces in practice, and the one the first version of this note
     * missed. Cap the reference type, stream the other: an order whose customer has aged out still
     * passes its own alpha tests, so the pattern has survivors and the verdict is about the join --
     * which is correct, and which sends an author to inspect a join that has nothing wrong with it.
     */
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession(SessionOptions.builder()
        .eviction(EvictionPolicy.perType(Map.of("Customer", 1)))
        .build())) {
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      // Ages customer 7 out. The order still matches its own pattern; nothing joins it any more.
      session.insert("Customer", Facts.obj("id", 8, "riskTier", "HIGH"));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict().orElseThrow())
          .describedAs("the join is correct; what changed is that a fact was let go")
          .contains("1 Customer fact(s) evicted");
    }
  }

  @Test
  @DisplayName("a rule that fired is not told about eviction")
  void aWorkingRuleIsNotToldAboutEviction() {
    /*
     * The over-shoot the first version of this note had. "matched, but refracted -- all already
     * fired" is a rule working exactly as intended, and a capped session's evicted count is
     * permanently non-zero, so appending the clause there puts it on every explanation of every
     * rule for the rest of the session's life. That is the failure noPolicyMeansNoNote below is
     * named for, recreated in the sessions where the note is supposed to earn its place.
     */
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession(SessionOptions.builder()
        .eviction(EvictionPolicy.perType(Map.of("Order", 2)))
        .build())) {
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      for (int id = 0; id < 5; id++) {
        session.insert("Order",
            Facts.obj("id", id, "total", 25_000, "status", "PENDING", "customerId", 7));
      }
      session.fireAllRules();

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict().orElseThrow())
          .describedAs("three orders were evicted, and none of that explains a rule that fired")
          .contains("already fired")
          .doesNotContain("evicted");
    }
  }

  @Test
  @DisplayName("a session with no eviction policy says nothing about eviction")
  void noPolicyMeansNoNote() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.verdict().orElseThrow())
          .describedAs("a clause that is always there stops being read")
          .doesNotContain("evicted");
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
            assertThat(failure.actualValue().stringValue()).isEqualTo("SHIPPED");
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
  @DisplayName("an unfinished search never claims a match does not exist")
  void truncatedSearchDoesNotDenyAMatch() {
    // The sharpest failure this class can have: telling an author their rule cannot match when it
    // demonstrably does. Two unrelated 600-fact populations expand every prefix, and the single C
    // that joins sits behind the LAST A in handle order -- so the work budget runs out before the
    // walk reaches it.
    //
    // `complete` was already false here and describe() already carried the disclaimer, but the
    // headline verdict said "no combination of them satisfies c.x EQ a.k". It is the same shape as
    // a defect already fixed in this class once: an earlier branch in verdict() returning before
    // the honest sentence is reached.
    final RuleDefinition rule = Rules.rule("late-match")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.hasField("k", true))
        .when("c", "C", pattern -> pattern.ref("x", "a.k"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRuleSet rules = Engine.compile(rule);

    try (RuleSession session = rules.newSession()) {
      for (int index = 0; index < 600; index++) {
        session.insert("A", Facts.obj("k", index));
        session.insert("B", Facts.obj("k", index));
      }
      session.insert("C", Facts.obj("x", 599));

      // The rule really does match, 600 times.
      assertThat(session.fireAllRules().firedCount()).isEqualTo(600);
    }

    try (RuleSession session = rules.newSession()) {
      for (int index = 0; index < 600; index++) {
        session.insert("A", Facts.obj("k", index));
        session.insert("B", Facts.obj("k", index));
      }
      session.insert("C", Facts.obj("x", 599));

      final Explanation explanation = new MatchExplainer(rules, session).explain("late-match");

      assertThat(explanation.complete()).isFalse();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> {
        assertThat(verdict)
            .describedAs("a match provably exists, so no definite negative is permissible")
            .doesNotContain("no combination");
        assertThat(verdict).contains("budget");
      });
    }
  }

  @Test
  @DisplayName("a finished search still gets the definite 'nothing joined' answer")
  void completeSearchKeepsTheDefiniteAnswer() {
    // The suppression above must not cost the useful case: when the walk finishes, "no combination
    // satisfies this join" is exactly the sentence the author needs.
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order",
          Facts.obj("id", 1, "total", 25_000, "status", "PENDING", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 999, "riskTier", "HIGH"));

      final Explanation explanation = new MatchExplainer(rules, session).explain(REVIEW.id());

      assertThat(explanation.complete()).isTrue();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("no combination"));
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

  @Test
  @DisplayName("a present fact defeating an asserted absence is the verdict, and is named")
  void absenceDefeatedIsTheVerdict() {
    /*
     * The gap §1's amendment recorded rather than fixed. Everything an author can see is fine --
     * the Order matches, there is nothing else to look at -- and this class used to answer "1
     * match(es); all eligible, none has fired yet", which is not merely unhelpful but the opposite
     * of the truth, with the Payment responsible named nowhere.
     */
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
      final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));

      // What the engine actually does, so the explanation below is checked against a fact rather
      // than against an expectation: the absence does not hold, so nothing fires.
      assertThat(session.fireAllRules().firedCount()).isZero();

      final Explanation explanation = new MatchExplainer(rules, session).explain(UNPAID.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("1 combination(s) matched every pattern and join")
          .contains("no Payment matches 'p'")
          .describedAs("the part the author cannot derive: WHICH fact is in the way")
          .contains("fact #" + payment.id())
          .doesNotContain("eligible"));
      assertThat(explanation.negations()).singleElement().satisfies(negation -> {
        assertThat(negation.alias()).isEqualTo("p");
        assertThat(negation.factType()).isEqualTo("Payment");
        assertThat(negation.present()).isEqualTo(1);
        assertThat(negation.suppressed()).isEqualTo(1);
        assertThat(negation.exampleWitness()).contains(payment.id());
      });
    }
  }

  @Test
  @DisplayName("an absence that holds is reported and not blamed")
  void absenceThatHoldsIsNotBlamed() {
    // The other half, and the reason a negation is reported even when it suppressed nothing: an
    // author who has just been told a Payment is in the way needs to be able to see, next time,
    // that it no longer is -- rather than a list that goes silent and leaves them unsure it ran.
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
      // A payment for a different order: present, but it does not satisfy the negated pattern's
      // join, so the absence this rule asserts still holds.
      session.insert("Payment", Facts.obj("orderId", 999, "amount", 50));

      final Explanation explanation = new MatchExplainer(rules, session).explain(UNPAID.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict ->
          assertThat(verdict).contains("eligible"));
      assertThat(explanation.negations()).singleElement().satisfies(negation -> {
        assertThat(negation.present()).isEqualTo(1);
        assertThat(negation.suppressed()).isZero();
        assertThat(negation.exampleWitness()).isEmpty();
      });
      // The half a one-directional test misses. Sharing the predicate with the agenda is only worth
      // anything if the explainer is checked against the engine in BOTH directions: saying "eligible"
      // about a rule the engine will not fire is the same class of defect as the one this closed.
      assertThat(session.fireAllRules().firedCount())
          .describedAs("the explainer said eligible, so the engine must agree")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a negation must not be reported as a join failure")
  void negationDoesNotBlameTheJoins() {
    /*
     * The regression evaluating negations here opens. withJoinNotes infers "no combination
     * satisfies the joins" from an empty match set, and once negations can empty it, that inference
     * blames constraints that in fact held -- sending the author to inspect a join that is correct
     * while the Payment sits there unmentioned. Same lesson the §6.4 condition case taught.
     */
    final RuleDefinition joinedAndNegated = Rules.rule("unpaid-high-risk-order")
        .when("o", "Order", pattern -> pattern.eq("status", "SHIPPED"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
        .then(actions -> actions.emit("order.unpaid", "orderId", Rules.ref("o.id")))
        .build();
    final CompiledRuleSet rules = Engine.compile(joinedAndNegated);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED", "customerId", 7));
      session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
      session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));

      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(joinedAndNegated.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("no Payment matches 'p'")
          .describedAs("the join held; blaming it sends the author to correct code")
          .doesNotContain("no combination of them satisfies"));
      assertThat(explanation.patterns())
          .describedAs("no pattern carries a join note, because no join failed")
          .allSatisfy(pattern -> assertThat(pattern.joinNote()).isEmpty());
    }
  }

  @Test
  @DisplayName("a negated pattern of a bound type does not report the bound fact as its own witness")
  void negatedSameTypeKeepsTheImplicitInequality() {
    // §1's implicit inequality reaches negations too: "no OTHER order for this customer" is what
    // the author means. An explainer that missed it would announce every order as blocking itself,
    // which is a verdict that can never be acted on.
    final RuleDefinition onlyOrder = Rules.rule("customers-only-order")
        .when("o", "Order", pattern -> pattern.eq("status", "NEW"))
        .notExists("other", "Order", pattern -> pattern.ref("customerId", "o.customerId"))
        .then(actions -> actions.emit("order.only", "orderId", Rules.ref("o.id")))
        .build();
    final CompiledRuleSet rules = Engine.compile(onlyOrder);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "NEW", "customerId", 7));

      assertThat(new MatchExplainer(rules, session).explain(onlyOrder.id()).negations())
          .describedAs("the one order does not block itself")
          .singleElement()
          .satisfies(negation -> assertThat(negation.suppressed()).isZero());

      final FactHandle second = session.insert("Order",
          Facts.obj("id", 2, "status", "NEW", "customerId", 7));

      final Explanation explanation = new MatchExplainer(rules, session).explain(onlyOrder.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("2 combination(s)")
          .contains("no Order matches 'other'"));
      assertThat(explanation.negations()).singleElement().satisfies(negation -> {
        assertThat(negation.suppressed())
            .describedAs("each order is blocked by the other")
            .isEqualTo(2);
        assertThat(negation.exampleWitness()).contains(second.id());
      });
    }
  }

  @Test
  @DisplayName("a pinned binding answers the negation question too")
  void pinnedBindingHitsTheNegation() {
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      final FactHandle order = session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
      final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));
      // Noise: an order the author is not asking about, with its own blocking payment.
      session.insert("Order", Facts.obj("id", 2, "status", "SHIPPED"));
      session.insert("Payment", Facts.obj("orderId", 2, "amount", 10));

      final Explanation explanation = new MatchExplainer(rules, session)
          .explain(UNPAID.id(), Map.of("o", order));

      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("1 combination(s)")
          .describedAs("the payment blocking THIS order, not the other one")
          .contains("fact #" + payment.id()));
    }
  }

  @Test
  @DisplayName("negations render alongside the patterns, and say which way their numbers run")
  void negationRendering() {
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
      session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));

      final String rendered = new MatchExplainer(rules, session).explain(UNPAID.id()).describe();

      assertThat(rendered)
          .contains("o: Order")
          // "present" and "suppressed", never "considered"/"matched": a negated pattern has no
          // candidates and no survivors, and borrowing that vocabulary would read as success.
          .contains("not p: Payment")
          .contains("1 present")
          .contains("suppressed 1 match(es)");
    }
  }

  @Test
  @DisplayName("a rule asserting no absence has an empty negation list")
  void noNegationsIsAnEmptyList() {
    final CompiledRuleSet rules = Engine.compile(REVIEW);
    try (RuleSession session = rules.newSession()) {
      assertThat(new MatchExplainer(rules, session).explain(REVIEW.id()).negations()).isEmpty();
    }
  }

  @Test
  @DisplayName("a negation is charged for the scan it made, not for the population it might have")
  void negationIsChargedForWhatItExamined() {
    /*
     * A negation has to be charged against the work budget -- many complete tuples over a large
     * negated type multiply, which is the blow-up WORK_LIMIT exists to stop -- but charging the
     * population's size is the wrong number. A scan short-circuits on the first witness, so a rule
     * whose absences are defeated early would pay for a walk it never took, and the search would
     * stop to say "there may be a match" on exactly the case where it had the exact answer in hand.
     * 600 orders each blocked by one of 600 payments: the real cost is well inside the budget.
     */
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      for (int id = 0; id < 600; id++) {
        session.insert("Order", Facts.obj("id", id, "status", "SHIPPED"));
        session.insert("Payment", Facts.obj("orderId", id, "amount", 1));
      }

      final Explanation explanation = new MatchExplainer(rules, session).explain(UNPAID.id());

      assertThat(explanation.complete())
          .describedAs("the scans short-circuit, so the budget was never really spent")
          .isTrue();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("600 combination(s) matched every pattern and join")
          .contains("no Payment matches 'p'")
          .describedAs("the exact answer, where charging the population gave up instead")
          .doesNotContain("search budget"));
      assertThat(explanation.negations()).singleElement().satisfies(negation ->
          assertThat(negation.suppressed()).isEqualTo(600));
    }
  }

  @Test
  @DisplayName("a search the negations really did exhaust says what it saw, not just that it gave up")
  void truncatedByNegationStillPointsSomewhere() {
    /*
     * The other side of the charge. Here the scans are genuinely long -- 700 payments for orders
     * that do not exist sit ahead of every real witness -- so the budget runs out for real. A
     * truncated walk cannot claim the negation is THE answer, since removedEverything() requires a
     * finished search, but it can say what it saw. Without that clause the author gets a sentence
     * naming nothing they can act on, which is the failure this whole diagnostic exists to stop.
     *
     * The two numbers are chosen against WORK_LIMIT and want re-checking if it moves: each tuple
     * costs the 700-deep junk prefix plus its own witness, so the budget of 250 000 runs out around
     * tuple 330 of the 400. Enough margin either side that the walk is unambiguously truncated and
     * unambiguously got far enough to suppress something first.
     */
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      for (int id = 0; id < 400; id++) {
        session.insert("Order", Facts.obj("id", id, "status", "SHIPPED"));
      }
      // Scanned first and matching nothing, so every scan wades through all of them.
      for (int junk = 0; junk < 700; junk++) {
        session.insert("Payment", Facts.obj("orderId", 5_000 + junk, "amount", 1));
      }
      for (int id = 0; id < 400; id++) {
        session.insert("Payment", Facts.obj("orderId", id, "amount", 1));
      }

      final Explanation explanation = new MatchExplainer(rules, session).explain(UNPAID.id());

      assertThat(explanation.complete()).isFalse();
      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("search budget ran out")
          .contains("suppressed by an absence the rule asserts"));
      assertThat(explanation.negations()).singleElement().satisfies(negation -> {
        assertThat(negation.present()).isEqualTo(1_100);
        assertThat(negation.suppressed())
            .describedAs("a lower bound, which is what the complete flag above is for")
            .isPositive();
      });
    }
  }

  @Test
  @DisplayName("two negations split the suppressions without double-counting a tuple both defeat")
  void twoNegationsAttributeWithoutOverlap() {
    /*
     * NegationResult documents its counts as summing to the matches lost rather than double-counting
     * a tuple two negations each defeat, and negationVerdict's headline is the total while its named
     * example is the worst contributor. Both are claims about arithmetic, and neither was reachable
     * with one negation in the suite.
     */
    final RuleDefinition unsettled = Rules.rule("unsettled-order")
        .when("o", "Order", pattern -> pattern.eq("status", "SHIPPED"))
        .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
        .notExists("r", "Refund", pattern -> pattern.ref("orderId", "o.id"))
        .then(actions -> actions.emit("order.unsettled", "orderId", Rules.ref("o.id")))
        .build();
    final CompiledRuleSet rules = Engine.compile(unsettled);
    try (RuleSession session = rules.newSession()) {
      for (int id = 1; id <= 3; id++) {
        session.insert("Order", Facts.obj("id", id, "status", "SHIPPED"));
      }
      session.insert("Payment", Facts.obj("orderId", 1, "amount", 5));
      session.insert("Refund", Facts.obj("orderId", 2, "amount", 5));
      // Defeated by both. Attributed to the first in declaration order and counted once.
      session.insert("Payment", Facts.obj("orderId", 3, "amount", 5));
      session.insert("Refund", Facts.obj("orderId", 3, "amount", 5));

      assertThat(session.fireAllRules().firedCount()).isZero();

      final Explanation explanation = new MatchExplainer(rules, session).explain(unsettled.id());

      assertThat(explanation.negations()).satisfiesExactly(
          payment -> {
            assertThat(payment.alias()).isEqualTo("p");
            assertThat(payment.suppressed()).describedAs("orders 1 and 3").isEqualTo(2);
          },
          refund -> {
            assertThat(refund.alias()).isEqualTo("r");
            assertThat(refund.suppressed())
                .describedAs("order 2 only; order 3 was already spoken for")
                .isEqualTo(1);
          });
      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .describedAs("the total, so the number partitions the complete tuples")
          .contains("3 combination(s) matched every pattern and join")
          .describedAs("the example comes from the negation that suppressed the most")
          .contains("no Payment matches 'p'"));
    }
  }

  @Test
  @DisplayName("evicting a negated type warns that a match may be a false conclusion")
  void evictedNegatedTypeIsAWarning() {
    /*
     * §4.4's sharpest hazard, and the one case where the eviction clause belongs on a verdict that
     * says the rule matched. Everywhere else eviction costs a firing; over a negated type it
     * manufactures one, because an evicted fact and an absent fact are indistinguishable to a
     * negation. The explainer cannot detect it -- it re-asks the same question of the same working
     * memory and is fooled identically -- but the count is what changes what the reader does next.
     */
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession(SessionOptions.builder()
        .eviction(EvictionPolicy.perType(Map.of("Payment", 1)))
        .build())) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
      // The payment that really does settle order 1, followed by two that do not. The cap lets it
      // go, and the engine now believes a paid order is unpaid.
      session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));
      session.insert("Payment", Facts.obj("orderId", 998, "amount", 1));
      session.insert("Payment", Facts.obj("orderId", 999, "amount", 1));

      final Explanation explanation = new MatchExplainer(rules, session).explain(UNPAID.id());

      assertThat(explanation.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
          .contains("eligible")
          .contains("WARNING")
          .contains("2 Payment fact(s) evicted")
          .contains("may be a false conclusion"));
    }
  }

  @Test
  @DisplayName("a rule with no eviction gets no warning, so the one above stays readable")
  void noEvictionMeansNoWarning() {
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));

      assertThat(new MatchExplainer(rules, session).explain(UNPAID.id()).verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict).doesNotContain("WARNING"));
    }
  }

  @Test
  @DisplayName("pinning a negated alias is answered, not silently ignored")
  void pinningANegatedAliasIsAnswered() {
    // The output now prints "not p: Payment", which is an invitation to pin `p`. Before this the
    // answer was silence -- the alias is in no pattern, so the pin matched nothing and vanished.
    final CompiledRuleSet rules = Engine.compile(UNPAID);
    try (RuleSession session = rules.newSession()) {
      final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1, "amount", 50));
      session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));

      assertThat(new MatchExplainer(rules, session)
          .explain(UNPAID.id(), Map.of("p", payment)).verdict())
          .hasValueSatisfying(verdict -> assertThat(verdict)
              .contains("'p' is a NOT_EXISTS pattern")
              .contains("binds no fact"));
    }
  }
}
