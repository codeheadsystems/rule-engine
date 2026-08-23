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
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code FOR_ALL}: the second of §1's deferred quantifiers (spec §2.5's amendment).
 *
 * <p><strong>The joins choose the scope; the pattern's own constraints are the requirement.</strong>
 * That reading is the whole feature, and the tests in {@link Scope} are the ones that pin it. Under
 * the literal alternative -- every fact of the type satisfies everything written -- a {@code FOR_ALL}
 * carrying a join asserts that every {@code LineItem} in working memory belongs to this order
 * <em>and</em> is in stock, which is false the moment a second order exists. An author would write
 * what looks right and get a rule that can never fire.
 *
 * <p>Evaluated in {@code RecomputingAgenda} for the reason negation is, so the three matchers cannot
 * disagree. Every behavioural case runs through {@code MatcherEquivalence} <strong>and also asserts
 * what fired</strong>: agreement is by construction here, so an equivalence assertion alone can
 * never fail for a {@code FOR_ALL} reason and a suite built only from those would pass with the
 * feature deleted. Both halves are needed, which is the lesson CLAUDE.md records from the §3.4.1
 * update-gate defect.
 */
class UniversalTest {

  /** Every line item of this order is in stock and has a positive quantity. */
  private static List<RuleDefinition> readyToShip() {
    return List.of(Rules.rule("ready")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .forAll("li", "LineItem", pattern -> pattern
            .ref("orderId", "o.id")
            .eq("inStock", true)
            .gt("qty", 0))
        .then(actions -> actions.emit("ready", "orderId", Rules.ref("o.id")))
        .build());
  }

  @Nested
  @DisplayName("a requirement is a question about a complete match")
  class Semantics {

    @Test
    @DisplayName("the rule fires when every fact in scope meets the requirement")
    void firesWhenAllComply() {
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 2));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 5));
      }).steps())
          .describedAs("two line items, both compliant")
          .hasSize(1);
    }

    @Test
    @DisplayName("and does not when one of them fails it")
    void silentOnACounterexample() {
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 2));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 5));
      }).steps())
          .describedAs("one line item out of stock is enough to defeat the assertion")
          .isEmpty();
    }

    @Test
    @DisplayName("every constraint is part of the requirement, not just the first")
    void allConstraintsAreRequired() {
      /*
       * The expressiveness FOR_ALL adds over a negation, and the reason it is not redundant. A
       * single-constraint requirement is already writable as notExists of its complement -- "every
       * Order is shipped" is "no Order is not shipped". A multi-constraint one is not: the
       * complement of "in stock AND qty > 0" is a disjunction, and no pattern here expresses one.
       */
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 0));
      }).steps())
          .describedAs("in stock, but a zero quantity fails the second half")
          .isEmpty();
    }

    @Test
    @DisplayName("with no joins it is the global assertion the enum originally described")
    void withoutJoinsItIsGlobal() {
      final List<RuleDefinition> rules = List.of(Rules.rule("all-shipped")
          .when("c", "Customer", pattern -> pattern.eq("tier", "GOLD"))
          .forAll("x", "Order", pattern -> pattern.eq("status", "SHIPPED"))
          .then(actions -> actions.emit("all-shipped"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Customer", Facts.obj("id", 1, "tier", "GOLD"));
        session.insert("Order", Facts.obj("id", 1, "status", "SHIPPED"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
      }).steps())
          .describedAs("scope is every Order, and one of them is not shipped")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("the joins choose the scope, which is the whole reading")
  class Scope {

    @Test
    @DisplayName("a fact of the type that fails the requirement but is out of scope does not suppress")
    void outOfScopeCannotFail() {
      /*
       * THE test for the semantics chosen. Order 2's line item is out of stock, so under the
       * literal reading -- every LineItem satisfies every constraint written, the join included --
       * order 1's rule would be suppressed by a fact that has nothing to do with it, and in a
       * session with more than one order the rule could never fire at all.
       */
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 2));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 2, "inStock", false, "qty", 9));
      }).steps())
          .describedAs("order 1 is ready; order 2 is not; neither answer is about the other")
          .hasSize(1);
    }

    @Test
    @DisplayName("each order is answered against its own scope")
    void scopeIsPerTuple() {
      // Both orders compliant, then one counterexample added for order 2 only. Asserting merely
      // that two compliant orders both fire proves nothing -- that holds with the quantifier never
      // evaluated. The asymmetry is what shows the question is asked per tuple.
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));
        session.insert("LineItem", Facts.obj("orderId", 2, "inStock", true, "qty", 1));
        session.insert("LineItem", Facts.obj("orderId", 2, "inStock", false, "qty", 1));
      }).steps())
          .describedAs("order 1 is still ready; order 2 alone is defeated")
          .hasSize(1);
    }
  }

  @Nested
  @DisplayName("the requirement crossing true and false, in both directions")
  class Transitions {

    @Test
    @DisplayName("a counterexample arriving suppresses the same order re-asserted")
    void aCounterexampleSuppressesAReassertedMatch() {
      /*
       * Un-refracted by retract-and-reinsert rather than by an update, and the first attempt at this
       * test is why. Touching an untested field to clear refraction does not: §3.4.1's gate
       * propagates nothing when no tested path changed, which is exactly what the spec promises, so
       * the second fire returned zero because the match was still refracted and the quantifier was
       * never consulted. The test passed with the feature deleted.
       *
       * A fresh handle gives a fresh ActivationKey, so refraction cannot account for the result and
       * the requirement is the only thing left that can.
       */
      final CompiledRuleSet rules = Engine.compile(readyToShip().getFirst());
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("the requirement holds, so it fires")
            .isEqualTo(1);

        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));
        session.retract(order);
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("nothing is refracted now, and the counterexample defeats the requirement")
            .isZero();
      }
    }

    @Test
    @DisplayName("updating an in-scope fact into compliance restores the match")
    void updatingIntoComplianceRestores() {
      /*
       * The way a requirement actually changes in production: you do not retract a line item, you
       * restock it. That path runs through §3.4.1's tested-path gate, which is upstream of every
       * matcher -- and CLAUDE.md records what that cost last time. Until Phase 3 a condition's paths
       * were not recorded at all, so an update that made a condition newly true fired nothing, and
       * "no differential test could catch that" because every matcher was identically wrong. A
       * FOR_ALL's constraints have to be tested paths for the same reason; this is the test that
       * says so.
       */
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FactHandle item =
            session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));
        session.fireAllRules();
        session.update(item, Facts.obj("orderId", 1, "inStock", true, "qty", 1));
      }).steps())
          .describedAs("restocked, so the requirement now holds over the same line item")
          .hasSize(1);
    }

    @Test
    @DisplayName("updating an in-scope fact out of compliance suppresses a match not yet fired")
    void updatingOutOfComplianceSuppresses() {
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FactHandle item =
            session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));
        // No fire in between: the match is eligible and then stops being so, with nothing refracted.
        session.update(item, Facts.obj("orderId", 1, "inStock", false, "qty", 1));
      }).steps())
          .describedAs("the requirement stopped holding before anything fired")
          .isEmpty();
    }

    @Test
    @DisplayName("updating a fact out of the scope entirely leaves the requirement holding")
    void updatingOutOfScopeRestores() {
      // The join is the scope, so changing the join key moves the fact out of the assertion's reach
      // rather than fixing it. A counterexample that leaves by re-parenting is still gone.
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FactHandle item =
            session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));
        session.fireAllRules();
        session.update(item, Facts.obj("orderId", 2, "inStock", false, "qty", 1));
      }).steps())
          .describedAs("no longer this order's problem")
          .hasSize(1);
    }

    @Test
    @DisplayName("a match already fired is not undone when a counterexample arrives")
    void thereIsNoTruthMaintenance() {
      // §1's boundary, inherited unchanged. Refraction keys on the facts the match binds, and the
      // counterexample is not one of them, so the firing stands. The requirement ending makes the
      // match ineligible from then on, which is not the same as undoing what it did.
      final CompiledRuleSet rules = Engine.compile(readyToShip().getFirst());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));

        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("not re-fired, and equally not retracted -- this insert is not logical,"
                + " so §4.4's truth maintenance has nothing to withdraw")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("the boundaries this ships with")
  class Boundaries {

    @Test
    @DisplayName("an empty scope makes it vacuously true, and the rule fires")
    void vacuousTruth() {
      /*
       * Classical, and the trap to know about. "Every line item of this order is in stock" holds
       * for an order with no line items at all, so the rule announces that an empty order is ready
       * to ship. Pairing the FOR_ALL with a positive pattern of the same type -- which a rule
       * wanting this usually has anyway -- is what turns "all of them" into "there are some, and
       * all of them".
       */
      assertThat(MatcherEquivalence.assertEquivalent(readyToShip(), session ->
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING"))).steps())
          .describedAs("no line items at all, and the assertion holds over nothing")
          .hasSize(1);
    }

    @Test
    @DisplayName("pairing it with a positive pattern is what excludes the empty scope")
    void aPositivePatternDefeatsVacuousTruth() {
      final List<RuleDefinition> rules = List.of(Rules.rule("ready")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .when("some", "LineItem", pattern -> pattern.ref("orderId", "o.id"))
          .forAll("li", "LineItem", pattern -> pattern
              .ref("orderId", "o.id")
              .eq("inStock", true))
          .then(actions -> actions.emit("ready"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session ->
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING"))).steps())
          .describedAs("nothing in scope, and now nothing to bind either")
          .isEmpty();
    }

    @Test
    @DisplayName("evicting the quantified type deletes the requirement rather than weakening it")
    void evictionManufacturesCompliance() {
      /*
       * §4.4's hazard, sharper here than for a negation. Evicting facts can only remove
       * counterexamples, so a cap does not weaken the requirement -- it strengthens it, and a cap
       * that empties the scope makes the assertion vacuously true. The engine announces that an
       * order whose line items are out of stock is ready to ship.
       */
      final CompiledRuleSet rules = Engine.compile(readyToShip().getFirst());
      // The control first: with no cap, the same three facts leave the counterexample in place and
      // the rule is correctly silent. Without this the test asserts only that something fires,
      // which it would do with the requirement never evaluated at all -- proving nothing about
      // eviction.
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("uncapped, the out-of-stock item is still there and still defeats it")
            .isZero();
      }
      try (RuleSession session = rules.newSession(SessionOptions.builder()
          .eviction(EvictionPolicy.perType(Map.of("LineItem", 1)))
          .build())) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false, "qty", 1));
        // Pushes the counterexample out. The requirement now holds over what is left.
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("a false conclusion, recorded here so the hazard is not a claim in prose")
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("a universal pattern of a type the rule already binds")
  class SameType {

    /** Every other order for this customer is shipped. */
    private List<RuleDefinition> everyOtherShipped() {
      return List.of(Rules.rule("last-open")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .forAll("other", "Order", pattern -> pattern
              .ref("customerId", "o.customerId")
              .eq("status", "SHIPPED"))
          .then(actions -> actions.emit("last-open"))
          .build());
    }

    @Test
    @DisplayName("excludes the bound fact from the scope, as §1's implicit inequality says")
    void theImplicitInequalityApplies() {
      // Without it the rule is unsatisfiable by construction: o is PENDING, so o would be its own
      // counterexample, and "every other order is shipped" could never hold.
      assertThat(MatcherEquivalence.assertEquivalent(everyOtherShipped(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
        session.insert("Order", Facts.obj("id", 2, "status", "SHIPPED", "customerId", 7));
      }).steps())
          .describedAs("the bound order is not one the assertion is about")
          .hasSize(1);
    }

    @Test
    @DisplayName("and another fact of that type is in scope and can fail it")
    void anotherFactIsInScope() {
      assertThat(MatcherEquivalence.assertEquivalent(everyOtherShipped(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
        session.insert("Order", Facts.obj("id", 2, "status", "SHIPPED", "customerId", 7));
        session.insert("Order", Facts.obj("id", 3, "status", "CANCELLED", "customerId", 7));
      }).steps())
          .describedAs("order 3 is in scope for order 1 and fails the requirement")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("what the compiler refuses")
  class Validation {

    @Test
    @DisplayName("a rule that binds nothing, because a quantifier needs a tuple to be asked of")
    void allQuantifiedIsRejected() {
      final RuleDefinition rule = Rules.rule("nothing")
          .forAll("x", "Order", pattern -> pattern.eq("status", "SHIPPED"))
          .then(actions -> actions.emit("e"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("no pattern binds a fact");
    }

    @Test
    @DisplayName("a $ref to the universal alias, which names a fact no tuple holds")
    void refToAUniversalAlias() {
      final RuleDefinition rule = Rules.rule("ref")
          .when("o", "Order")
          .forAll("li", "LineItem", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e", "q", Rules.ref("li.qty")))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .describedAs("naming the quantifier, not reporting the alias as unknown: an author who"
              + " can see 'li' written in when would go hunting a typo that is not there")
          .hasMessageContaining("is a FOR_ALL pattern");
    }

    @Test
    @DisplayName("an action naming the universal alias")
    void actionOnAUniversalAlias() {
      final RuleDefinition rule = Rules.rule("act")
          .when("o", "Order")
          .forAll("li", "LineItem", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.setField("li", "flagged", true))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("is a FOR_ALL pattern");
    }

    @Test
    @DisplayName("a §6.4 condition on the universal pattern, which would compile and never run")
    void conditionOnAUniversal() {
      // Refused rather than ignored, for the reason a condition on a negation is: the post-filter
      // that evaluates conditions walks the POSITIVE patterns, so it would silently never run and
      // the quantifier would be broader than written.
      final RuleDefinition rule = Rules.rule("cond")
          .when("o", "Order")
          .forAll("li", "LineItem", pattern -> pattern.constraint(
              new ExpressionConstraint("li.qty > 0", Set.of("li"))))
          .then(actions -> actions.emit("e"))
          .build();

      Assertions.assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("a condition on a FOR_ALL pattern is not supported");
    }
  }

  @Nested
  @DisplayName("§7.3's determinism contract")
  class Determinism {

    @Test
    @DisplayName("the firing sequence does not depend on rule order")
    void shuffledRuleOrderAgrees() {
      /*
       * Two rules, because ShuffleHarness permutes rule DECLARATION order and nothing else -- a
       * one-element list compiles identically twelve times and guards nothing. Both quantify over
       * LineItem and both are eligible on the facts below, so conflict resolution has a real choice
       * to make and §7.3's contract has something to be true of.
       */
      final List<RuleDefinition> rules = List.of(
          readyToShip().getFirst(),
          Rules.rule("has-stock")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .forAll("li", "LineItem", pattern -> pattern
                  .ref("orderId", "o.id")
                  .gt("qty", 0))
              .then(actions -> actions.emit("has-stock", "orderId", Rules.ref("o.id")))
              .build());

      ShuffleHarness.assertDeterministic(rules, session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 1));
        session.insert("LineItem", Facts.obj("orderId", 2, "inStock", false, "qty", 1));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true, "qty", 3));
      });
    }
  }
}
