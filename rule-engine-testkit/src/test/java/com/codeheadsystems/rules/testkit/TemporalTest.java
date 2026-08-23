package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Bounded temporal relations between two facts (spec §2.5's third amendment).
 *
 * <p><strong>Time is a field and the engine owns no clock</strong>, which is the decision every
 * other property here follows from. §7.3 promises that the same rule set over the same facts in the
 * same order fires the same way on every host and run; a wall clock would end that outright, since
 * a slower machine would see different intervals. Reading time from the facts means the guarantee
 * survives untouched -- these tests would pass identically if run in 1970 or 2170.
 *
 * <p>The cost is what §1's bullet actually asked for and this does not deliver: a sliding window,
 * or "nothing has happened for 24 hours", both of which need something to notice that time has
 * passed with no fact arriving. That stays deferred.
 */
class TemporalTest {

  /** A payment that lands within a day of the order being placed. Times are epoch millis. */
  private static final long DAY = 24L * 60 * 60 * 1000;

  private static List<RuleDefinition> paidWithinADay() {
    return List.of(Rules.rule("quick-payment")
        .when("o", "Order")
        .when("p", "Payment", pattern -> pattern
            .ref("orderId", "o.id")
            .after("paidAt", "o.placedAt", DAY))
        .then(actions -> actions.emit("order.paid.quickly", "orderId", Rules.ref("o.id")))
        .build());
  }

  @Nested
  @DisplayName("a bounded relation")
  class Semantics {

    @Test
    @DisplayName("holds inside the window")
    void insideTheWindow() {
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY / 2));
      }).steps())
          .describedAs("half a day later")
          .hasSize(1);
    }

    @Test
    @DisplayName("and not outside it")
    void outsideTheWindow() {
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY + 1));
      }).steps())
          .describedAs("one millisecond past the bound")
          .isEmpty();
    }

    @Test
    @DisplayName("includes the far edge exactly")
    void theFarEdgeIsInclusive() {
      // "within 24 hours" includes the twenty-fourth hour. An exclusive far edge would make the
      // operator mean "within a day, but not a day", which is not what anybody says out loud.
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY));
      }).steps())
          .hasSize(1);
    }

    @Test
    @DisplayName("excludes the near edge, so a shared timestamp is not 'after'")
    void theNearEdgeIsStrict() {
      // Strict, because a sequence is what the author means: two facts stamped the same instant
      // did not happen one after the other, and an inclusive near edge would say they did -- in
      // both directions at once, if the rule were written the other way round.
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L));
      }).steps())
          .isEmpty();
    }

    @Test
    @DisplayName("is directional: earlier is not 'after'")
    void directionMatters() {
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L - 1));
      }).steps())
          .describedAs("paid before it was placed")
          .isEmpty();
    }

    @Test
    @DisplayName("before is the mirror of after")
    void beforeIsTheMirror() {
      final List<RuleDefinition> rules = List.of(Rules.rule("late-cancel")
          .when("p", "Payment")
          .when("c", "Cancellation", pattern -> pattern
              .ref("orderId", "p.orderId")
              .before("at", "p.paidAt", DAY))
          .then(actions -> actions.emit("cancelled.before.payment"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L));
        session.insert("Cancellation", Facts.obj("orderId", 1, "at", 1_000_000L - DAY / 2));
      }).steps())
          .hasSize(1);
    }

    @Test
    @DisplayName("before has its own edges, and they mirror after's")
    void beforeHasItsOwnEdges() {
      /*
       * Comparisons.within branches on the operator, so nothing the AFTER cases pin constrains this
       * arm at all -- three separate mutations of it survived a suite that tested `before` once, in
       * the middle of its window. Near edge strict, far edge inclusive, read the other way round.
       */
      final List<RuleDefinition> rules = List.of(Rules.rule("late-cancel")
          .when("p", "Payment")
          .when("c", "Cancellation", pattern -> pattern
              .ref("orderId", "p.orderId")
              .before("at", "p.paidAt", DAY))
          .then(actions -> actions.emit("cancelled.before.payment"))
          .build());

      // paidAt is 1_000_000; the window is [paidAt - DAY, paidAt).
      final long paidAt = 1_000_000L + DAY;
      for (final long[] each : new long[][] {
          {paidAt - DAY, 1},        // far edge, inclusive
          {paidAt - DAY - 1, 0},    // one past it
          {paidAt - 1, 1},          // just inside the near edge
          {paidAt, 0},              // near edge, strict: same instant is not "before"
          {paidAt + 1, 0},          // after it entirely
      }) {
        assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
          session.insert("Payment", Facts.obj("orderId", 1, "paidAt", paidAt));
          session.insert("Cancellation", Facts.obj("orderId", 1, "at", each[0]));
        }).steps())
            .describedAs("cancelled at %d against a payment at %d", each[0], paidAt)
            .hasSize((int) each[1]);
      }
    }

    @Test
    @DisplayName("a same-type relation carries §1's implicit inequality")
    void twoFactsOfOneType() {
      /*
       * The flagship shape -- "two failed logins within five minutes" -- and the one place near-edge
       * strictness and the implicit inequality meet. One login cannot be its own predecessor by
       * either rule, and it matters that both say so: the inequality excludes the same fact, and the
       * strict near edge would exclude it anyway on a shared timestamp.
       */
      final long fiveMinutes = 5L * 60 * 1000;
      final List<RuleDefinition> rules = List.of(Rules.rule("repeated-failure")
          .when("first", "LoginFailure")
          .when("second", "LoginFailure", pattern -> pattern
              .ref("user", "first.user")
              .after("at", "first.at", fiveMinutes))
          .then(actions -> actions.emit("account.locked"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session ->
          session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L))).steps())
          .describedAs("one failure is not two, whatever the window")
          .isEmpty();

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L));
        session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L + 60_000));
      }).steps())
          .describedAs("a minute apart, so the second follows the first -- and only that way round")
          .hasSize(1);
    }

    @Test
    @DisplayName("an update to a time field re-propagates, so the rule reconsiders")
    void theTimeFieldIsATestedPath() {
      // compileJoin records both sides as tested paths; without that, §3.4.1's gate would swallow
      // the update and the rule would keep answering against the old timestamp.
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        final var payment =
            session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY * 9));
        session.fireAllRules();
        session.update(payment, Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY / 2));
      }).steps())
          .describedAs("moved into the window by an update")
          .hasSize(1);
    }

    @Test
    @DisplayName("a non-finite timestamp answers false rather than throwing")
    void nonFiniteDoesNotThrow() {
      // The same set Canonical rejects and Accumulators skips. Without the guard this is a
      // NumberFormatException on the matching path rather than a rule that does not fire.
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt",
            tools.jackson.databind.node.DoubleNode.valueOf(Double.NaN)));
      }).steps())
          .isEmpty();
    }

    @Test
    @DisplayName("an absent or non-numeric timestamp does not match, as every ordering does not")
    void absentDoesNotMatch() {
      assertThat(MatcherEquivalence.assertEquivalent(paidWithinADay(), session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1));
        session.insert("Order", Facts.obj("id", 2, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 2, "paidAt", "yesterday"));
      }).steps())
          .describedAs("§2.6.1 orders neither an absent value nor a string against a number")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("what the compiler refuses")
  class Validation {

    @Test
    @DisplayName("a temporal relation with no window, because that is just gt")
    void theWindowIsRequired() {
      final RuleDefinition unbounded = Rules.rule("unbounded")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.constraint(
              new com.codeheadsystems.rules.rule.JoinConstraint(
                  "paidAt", "o", "placedAt", Operator.AFTER)))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(unbounded)))
          .hasMessageContaining("needs a 'within' bound")
          .describedAs("and points at the operator that already does the unbounded case")
          .hasMessageContaining("gt or lt against the same $ref");
    }

    @Test
    @DisplayName("a window on an operator that is not temporal")
    void theWindowIsRefusedElsewhere() {
      final RuleDefinition odd = Rules.rule("odd")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.constraint(
              new com.codeheadsystems.rules.rule.JoinConstraint("orderId", "o", "id", Operator.EQ,
                  java.util.Optional.of(
                      tools.jackson.databind.node.LongNode.valueOf(DAY)))))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(odd)))
          .hasMessageContaining("'within' bounds a temporal relation, and EQ is not one");
    }

    @Test
    @DisplayName("a negative window, which would invert the relation its own name states")
    void theWindowMustBePositive() {
      final RuleDefinition backwards = Rules.rule("backwards")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.after("paidAt", "o.placedAt", -1))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(backwards)))
          .hasMessageContaining("positive number in the time field's own units");
    }

    @Test
    @DisplayName("a temporal operator against a literal, which has no second fact")
    void temporalNeedsTwoFacts() {
      final RuleDefinition literal = Rules.rule("literal")
          .when("p", "Payment", pattern -> pattern.constraint(
              new com.codeheadsystems.rules.rule.FieldConstraint("paidAt", Operator.AFTER,
                  tools.jackson.databind.node.LongNode.valueOf(1L))))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(literal)))
          .hasMessageContaining("relates two facts and needs a $ref")
          .hasMessageContaining("use gt or lt");
    }
  }

  @Nested
  @DisplayName("the window is a magnitude, not a typed value")
  class Normalisation {

    @Test
    @DisplayName("so the same duration written two ways is the same rule")
    void widthDoesNotChangeIdentity() {
      /*
       * DslEquivalence found this: a window of 86400000 arrives as an IntNode from a rule file and
       * a LongNode from the Java builder, and Jackson's node equality is type-sensitive -- so the
       * same duration written two ways produced two constraints and two rule-set version hashes.
       * §5.6's hot reload, refraction and RuleSetFingerprint all key on that identity, so it cannot
       * depend on which route an author took. Normalising to a decimal in JoinConstraint is the
       * same remedy RangeConstraint uses for its inclusivity flags, and was found the same way.
       */
      final RuleDefinition asInt = Rules.rule("same")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.after("paidAt", "o.placedAt", 86_400_000))
          .then(actions -> actions.emit("e"))
          .build();
      final RuleDefinition asLong = Rules.rule("same")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.after("paidAt", "o.placedAt", 86_400_000L))
          .then(actions -> actions.emit("e"))
          .build();

      assertThat(asInt)
          .describedAs("an int and a long of the same magnitude are one rule")
          .isEqualTo(asLong);
      assertThat(com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(asInt)).version())
          .isEqualTo(
              com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(asLong)).version());
    }
  }

  @Nested
  @DisplayName("with the other four Phase 6 slices")
  class Interactions {

    @Test
    @DisplayName("inside a negation: no payment within a day of the order")
    void insideANegation() {
      final List<RuleDefinition> rules = List.of(Rules.rule("slow-or-unpaid")
          .when("o", "Order")
          .notExists("p", "Payment", pattern -> pattern
              .ref("orderId", "o.id")
              .after("paidAt", "o.placedAt", DAY))
          .then(actions -> actions.emit("order.not.paid.promptly"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY * 9));
      }).steps())
          .describedAs("a payment exists, but not within the window, so the absence holds")
          .hasSize(1);
    }

    @Test
    @DisplayName("inside a forAll: every line item shipped within a day")
    void insideAUniversal() {
      final List<RuleDefinition> rules = List.of(Rules.rule("all-prompt")
          .when("o", "Order")
          .forAll("s", "Shipment", pattern -> pattern
              .ref("orderId", "o.id")
              .after("shippedAt", "o.placedAt", DAY))
          .then(actions -> actions.emit("order.all.prompt"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        session.insert("Shipment", Facts.obj("orderId", 1, "shippedAt", 1_000_000L + DAY / 2));
      }).steps())
          .describedAs("the one shipment is in scope AND meets the requirement")
          .hasSize(1);
    }

    @Test
    @DisplayName("inside an accumulate: how many logins in the window")
    void insideAnAccumulate() {
      final long fiveMinutes = 5L * 60 * 1000;
      final List<RuleDefinition> rules = List.of(Rules.rule("burst")
          .when("first", "LoginFailure")
          .accumulate("recent", "LoginFailure",
              Rules.count(Operator.GTE, 2),
              pattern -> pattern.ref("user", "first.user")
                  .after("at", "first.at", fiveMinutes))
          .then(actions -> actions.emit("account.burst"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(rules, session -> {
        session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L));
        session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L + 60_000));
        session.insert("LoginFailure", Facts.obj("user", "ana", "at", 1_000_000L + 120_000));
      }).steps())
          .describedAs("two follow the first inside five minutes, so its count passes")
          .hasSize(1);
    }

    @Test
    @DisplayName("and truth maintenance re-validates it, so a conclusion follows the window")
    void withTruthMaintenance() {
      // TupleMatch re-runs every join, temporal ones included. An update that moves a fact out of
      // the window has to withdraw whatever the match concluded, or a conclusion outlives the
      // relation that justified it.
      final CompiledRuleSet rules = Engine.compile(Rules.rule("quick")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern
              .ref("orderId", "o.id")
              .after("paidAt", "o.placedAt", DAY))
          .then(actions -> actions.insertLogical("PaidPromptly", "orderId", Rules.ref("o.id")))
          .build());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "placedAt", 1_000_000L));
        final var payment =
            session.insert("Payment", Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY / 2));
        session.fireAllRules();
        assertThat(session.workingMemory().factsOfType("PaidPromptly").count()).isEqualTo(1);

        session.update(payment, Facts.obj("orderId", 1, "paidAt", 1_000_000L + DAY * 9));
        session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("PaidPromptly").count())
            .describedAs("moved outside the window, so the conclusion goes with it")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("§3.3's index")
  class Indexing {

    @Test
    @DisplayName("never serves a temporal join, and the report says so")
    void temporalJoinsAreNeverIndexed() {
      /*
       * A contract stated in three documents and, until now, asserted nowhere. A bounded relation
       * IS a range probe in principle -- but the bound lives on the constraint rather than in the
       * probe value, so an index consulted with the timestamp alone returns everything on the right
       * side of it. That is slow rather than wrong, since every join is re-applied afterwards; the
       * engine declines because an unbounded probe narrows nothing worth the walk. §7.4's report
       * names it like any other unindexable constraint.
       */
      final var report = Engine.compile(paidWithinADay().getFirst()).report();

      assertThat(report.unindexed())
          .describedAs("named, not silently unindexed -- the report reaches an author who is"
              + " wondering why a join they expected to narrow did not")
          .anySatisfy(entry -> {
            assertThat(entry.alias()).isEqualTo("p");
            assertThat(entry.field()).isEqualTo("paidAt");
            assertThat(entry.reason())
                .describedAs("named for the right reason, not merely present for some other one")
                .isEqualTo(com.codeheadsystems.rules.report.UnindexedConstraint.Reason
                    .RESIDUAL_JOIN_CONDITION);
          });
    }
  }

  @Nested
  @DisplayName("the bound itself")
  class TheBound {

    @Test
    @DisplayName("of zero is refused, and the message says why nothing would match")
    void zeroIsRefused() {
      // Empty by construction, whatever the facts: the near edge is strict, so `after within 0` is
      // `other < mine <= other`. Unlike a range there is no reading under which zero was meant.
      final RuleDefinition empty = Rules.rule("empty")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.after("paidAt", "o.placedAt", 0))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(empty)))
          .hasMessageContaining("'within' is a positive number")
          .describedAs("and names the reason, rather than leaving the author to work it out")
          .hasMessageContaining("A window of zero excludes every value");
    }

    @Test
    @DisplayName("of the wrong type reaches the compiler, so the author gets a located diagnostic")
    void aNonNumberIsTheCompilersToReport() {
      /*
       * Two layers owning different failures. JoinConstraint normalises a numeric window and throws
       * for one it cannot represent; anything else passes through untouched so RuleCompiler reports
       * it against the author's own line. An earlier version threw here for both, which made the
       * compiler's own check unreachable and left the two layers silently disagreeing about who
       * owned it.
       */
      final RuleDefinition wrongType = Rules.rule("wrong-type")
          .when("o", "Order")
          .when("p", "Payment", pattern -> pattern.constraint(
              new com.codeheadsystems.rules.rule.JoinConstraint("paidAt", "o", "placedAt",
                  Operator.AFTER,
                  java.util.Optional.of(tools.jackson.databind.node.StringNode.valueOf("a day")))))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(wrongType)))
          .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class)
          .hasMessageContaining("'within' is a positive number in the time field's own units");
    }

    @Test
    @DisplayName("of a non-finite double is refused at the builder's own door")
    void nonFiniteIsRefused() {
      // isNumber() is true for an infinity and decimalValue() throws on it, so without the guard
      // this is a JsonNodeException out of a public record constructor -- the same trap Comparisons
      // hit one file over, met a second time at the other end of the same value.
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> Rules.rule("infinite")
              .when("o", "Order")
              .when("p", "Payment",
                  pattern -> pattern.after("paidAt", "o.placedAt", Double.POSITIVE_INFINITY))
              .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("'within' is a finite number");
    }
  }
}
