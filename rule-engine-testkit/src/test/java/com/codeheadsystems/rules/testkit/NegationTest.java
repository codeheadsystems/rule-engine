package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code NOT_EXISTS}: the first of §1's deferred quantifiers (spec §2.5).
 *
 * <p>§1 calls "no Payment exists for this Order" one of the first ten rules most people write, and
 * defers it because negation is genuinely harder than positive matching. What makes it tractable
 * here is that a negated pattern binds nothing and joins nothing into the tuple: it is a question
 * asked of a <em>complete</em> match, which is the same shape a §6.4 condition has. So it is
 * answered in {@code RecomputingAgenda}, the shared base, and the three matchers cannot disagree
 * about it.
 *
 * <p>Every behavioural case runs through {@code MatcherEquivalence}, so each is checked against the
 * naive oracle as well as the two networks -- <strong>and every one of them also asserts what
 * fired</strong>, which is the part that is easy to leave out and useless to leave out. Negation is
 * evaluated in the shared base, so all three matchers agree about it <em>by construction</em>: an
 * equivalence assertion alone can never fail for a negation reason, and a suite built only from
 * those would pass with the feature deleted. That is the vacuous-agreement trap CLAUDE.md records
 * from the §3.4.1 update-gate defect, where every matcher was identically wrong and the differential
 * test proved only that they agreed. Both halves are needed: agreement, and the count.
 */
class NegationTest {

  /** The rule §1 names: flag an order with no payment against it. */
  private static List<RuleDefinition> unpaidOrder() {
    return List.of(Rules.rule("unpaid")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
        .then(actions -> actions.emit("unpaid", "orderId", Rules.ref("o.id")))
        .build());
  }

  @Nested
  @DisplayName("an absence is a question about a complete match")
  class Semantics {

    @Test
    @DisplayName("the rule fires when nothing matches the negated pattern")
    void firesOnAnAbsence() {
      assertThat(MatcherEquivalence.assertEquivalent(unpaidOrder(), session ->
          session.insert("Order", Facts.obj("id", 1, "total", 100))).steps())
          .describedAs("an order with no payment against it")
          .hasSize(1);
    }

    @Test
    @DisplayName("and does not when something does")
    void silentOnAPresence() {
      assertThat(MatcherEquivalence.assertEquivalent(unpaidOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
      }).steps())
          .describedAs("the absence does not hold, so nothing may fire")
          .isEmpty();
    }

    @Test
    @DisplayName("the negation joins, so an unrelated fact of that type does not suppress it")
    void theJoinNarrowsTheAbsence() {
      // The whole point of joining a negation: "no payment FOR THIS ORDER", not "no payment at all".
      assertThat(MatcherEquivalence.assertEquivalent(unpaidOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.insert("Payment", Facts.obj("orderId", 999, "amount", 5));
      }).steps())
          .describedAs("someone else's payment is not this order's")
          .hasSize(1);
    }

    @Test
    @DisplayName("the negated pattern's own constraints narrow it further")
    void theNegatedPatternsConstraintsApply() {
      final List<RuleDefinition> rules = List.of(Rules.rule("unsettled")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id")
              .eq("status", "SETTLED"))
          .then(actions -> actions.emit("unsettled", "orderId", Rules.ref("o.id")))
          .build());

      // A pending payment is not a settled one, so the absence still holds and the rule fires.
      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.insert("Payment", Facts.obj("orderId", 1, "status", "PENDING"));
      }).steps()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("the count crossing one and zero, in both directions")
  class Transitions {

    @Test
    @DisplayName("a fact arriving makes a firing match ineligible")
    void oneToZeroSuppresses() {
      /*
       * §1 names this as the hard half of negation: "correct behavior when the count crosses 1 to 0
       * in both directions". Going to one is what CompiledRule.factTypes exists for -- the negated
       * type is in it, so a Payment arriving marks the rule dirty and the absence is asked again.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(unpaidOrder());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("the absence does not hold, so there is nothing to fire")
            .isZero();
      }
    }

    @Test
    @DisplayName("a fact leaving makes a suppressed match eligible")
    void zeroToOneRestores() {
      // The other direction, and the one a counter-based NotNode gets wrong by decrementing to zero
      // without re-propagating. Here it falls out of the rule being dirty for the negated type.
      assertThat(MatcherEquivalence.assertEquivalent(unpaidOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
        session.fireAllRules();
        session.retract(payment);
      }).steps())
          .describedAs("suppressed while the payment existed, eligible once it went")
          .hasSize(1);
    }

    @Test
    @DisplayName("a match already fired on an absence is not undone when the absence ends")
    void thereIsNoTruthMaintenance() {
      /*
       * §1's documented boundary, asserted rather than left implicit: "a match justified by an
       * absence must be retracted the moment the absence ends" is what truth maintenance would do,
       * and there is none. Refraction is keyed on the facts the match BINDS, and the Payment is not
       * one of them -- the rule bound only the Order -- so the firing stands and the match simply
       * becomes ineligible from here on.
       *
       * This is the strongest argument in §1 for landing negation and truth maintenance together,
       * and it is why this test is here rather than in a list of things nobody checked.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(unpaidOrder());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        assertThat(session.fireAllRules().firedCount())
            .describedAs("nothing was owed, so it fired")
            .isEqualTo(1);

        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("the absence has ended, and what it justified is not withdrawn")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("a negated pattern of a type the rule already binds")
  class SameType {

    @Test
    @DisplayName("asks about some other fact, as §1's implicit inequality says it should")
    void theImplicitInequalityApplies() {
      /*
       * "No other order for this customer with a higher total" is the shape people write, and it is
       * unusable if the order already bound counts as its own counterexample. §1 states the rule for
       * two positive aliases; a negated pattern of a bound type is the same question.
       */
      final List<RuleDefinition> rules = List.of(Rules.rule("largest")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("bigger", "Order", pattern -> pattern.ref("customerId", "o.customerId")
              .gt("total", 0))
          .then(actions -> actions.emit("only", "id", Rules.ref("o.id")))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session ->
          // One order for this customer: it must not be the "other" order that suppresses it.
          session.insert("Order", Facts.obj("id", 1, "total", 100, "customerId", 7))).steps())
          .describedAs("a fact is not its own counterexample")
          .hasSize(1);
    }

    @Test
    @DisplayName("and a second fact of that type does suppress it")
    void asecondFactSuppresses() {
      final List<RuleDefinition> rules = List.of(Rules.rule("largest")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("bigger", "Order", pattern -> pattern.ref("customerId", "o.customerId")
              .gt("total", 0))
          .then(actions -> actions.emit("only", "id", Rules.ref("o.id")))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Order", Facts.obj("id", 1, "total", 100, "customerId", 7));
        session.insert("Order", Facts.obj("id", 2, "total", 50, "customerId", 7));
      }).steps())
          .describedAs("each is the other's counterexample, so neither fires")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("the boundaries this ships with")
  class Boundaries {

    @Test
    @DisplayName("evicting a negated type makes the engine assert an absence that is false")
    void evictionManufacturesAnAbsence() {
      /*
       * Sharper than the no-truth-maintenance boundary above, and not covered by §1's licence for
       * it. "No truth maintenance" means a conclusion already drawn is not withdrawn. This is the
       * engine NEWLY asserting an absence that is not true: an evicted fact and an absent fact are
       * the same thing to a negation, so a cap on the negated type turns a paid order into an unpaid
       * one. Before negation, §4.4 eviction could only ever cost a firing.
       *
       * Asserted rather than described so that anyone who later makes eviction and negation
       * cooperate has a test that changes colour.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(unpaidOrder());
      try (RuleSession session = rules.newSession(SessionOptions.builder()
          .eviction(EvictionPolicy.perType(java.util.Map.of("Payment", 1)))
          .build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
        assertThat(session.fireAllRules().firedCount())
            .describedAs("the order is paid, so nothing fires")
            .isZero();

        // An unrelated payment arrives and the cap evicts the one that mattered.
        session.insert("Payment", Facts.obj("orderId", 999, "amount", 1));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("and now the engine says a paid order is unpaid")
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("what the compiler refuses")
  class Validation {

    @Test
    @DisplayName("a rule that is nothing but negations")
    void allNegationsIsRejected() {
      final RuleDefinition rule = Rules.rule("nothing")
          .notExists("p", "Payment", pattern -> pattern.gt("amount", 0))
          .then(actions -> actions.emit("none"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("at least one pattern that binds a fact");
    }

    @Test
    @DisplayName("a $ref to the negated alias, which names a fact no tuple holds")
    void aReferenceToANegatedAliasIsRejected() {
      final RuleDefinition rule = Rules.rule("dangling")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.gt("amount", 0))
          .when("c", "Customer", pattern -> pattern.ref("id", "p.customerId"))
          .then(actions -> actions.emit("x"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("is a NOT_EXISTS pattern")
          .describedAs("not 'no such alias' -- the alias is written right there, and an author"
              + " sent looking for a typo will not find one");
    }

    @Test
    @DisplayName("a right-hand side naming the negated alias, told apart from an unbound one")
    void anActionOnANegatedAliasIsRejected() {
      /*
       * The same three-way distinction the $ref resolver makes, on the other side of the rule. A
       * negated alias reaches an action only through this path, and until negation had a rule-file
       * surface nobody could write it by accident; now they can.
       */
      final RuleDefinition rule = Rules.rule("mutate-the-absent")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.gt("amount", 0))
          .then(actions -> actions.setField("p", "status", "VOID"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("setField target names alias 'p', which is a NOT_EXISTS pattern");
    }

    @Test
    @DisplayName("an insertFact that reuses the negated alias, which would shadow the negation")
    void anInsertFactCannotStealANegatedAlias() {
      /*
       * The sharpest of the three, because it fails silently rather than loudly: the negated alias
       * is in neither the bound set nor the taken set, so an unguarded insertFact alias would take
       * it and every later action naming it would resolve -- to the fact this rule just created,
       * standing in for the one whose absence it asserted.
       */
      final RuleDefinition rule = Rules.rule("shadow")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.gt("amount", 0))
          .then(actions -> actions
              .insertFactAs("Payment", "p", "orderId", 1)
              .setField("p", "status", "NEW"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("which names a NOT_EXISTS pattern");
    }

    @Test
    @DisplayName("an expression that reads the negated alias, which has nothing to read")
    void anExpressionCannotReadANegatedAlias() {
      // The §6.4 half of the same rule. Rejected before any expression compiler is consulted --
      // the alias check runs first -- so this holds whether or not -cel is on the classpath.
      final RuleDefinition rule = Rules.rule("read-the-absent")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("p.amount > 0", java.util.Set.of("p"))))
          .notExists("p", "Payment", pattern -> pattern.gt("amount", 0))
          .then(actions -> actions.emit("x"))
          .build();

      // The tail, not the shared "is a NOT_EXISTS pattern" prefix: all three branches carry that,
      // so asserting it would pass with this branch wired to the action wording -- and the wording
      // is what this branch exists to add.
      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("so an expression has nothing to read from it");
    }

    @Test
    @DisplayName("an alias used twice, whichever side it is on")
    void aDuplicateAliasIsRejected() {
      final RuleDefinition rule = Rules.rule("clash")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("o", "Payment", pattern -> pattern.gt("amount", 0))
          .then(actions -> actions.emit("x"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("bound twice");
    }
  }
}
