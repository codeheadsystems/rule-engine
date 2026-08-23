package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import com.codeheadsystems.rules.observability.Explanation;
import com.codeheadsystems.rules.observability.MatchExplainer;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the defects a senior review found in the §6.4 escape hatch.
 *
 * <p>What they have in common is that the suite passed over every one of them, because every
 * numeric fixture in it was an integer and none used an explicit null -- so the two most ordinary
 * values a rule author has were the two the tests never tried.
 */
class CelReviewRegressionTest {

  private static CompiledRuleSet compile(final com.codeheadsystems.rules.rule.RuleDefinition rule) {
    return RuleCompiler.compile(List.of(rule),
        CompilerOptions.builder().expressions(CelExpressions.create()).build());
  }

  @Nested
  @DisplayName("a decimal fact")
  class Decimals {

    /*
     * JSON integers became CEL int and decimals became CEL double, and CEL has no int-versus-double
     * comparison overload -- so `o.price > 100` threw whenever price happened to be a decimal, and
     * the throw escaped fireAllRules past every error policy. §2.6.2 treats 100 and 100.0 as one
     * value and the operator-map form honours that; the escape hatch has to as well.
     */

    @Test
    @DisplayName("compares against an integer literal, as the operator-map form does")
    void decimalComparesWithIntegerLiteral() {
      final CompiledRuleSet rules = compile(Rules.rule("priced")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.price > 100", Set.of("o"))))
          .then(actions -> actions.emit("dear")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"price\": 150.5}"));
        assertThat(session.fireAllRules().fired()).hasSize(1);
      }
    }

    @Test
    @DisplayName("and an integer fact compares against a decimal literal")
    void integerComparesWithDecimalLiteral() {
      final CompiledRuleSet rules = compile(Rules.rule("priced")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.price > 100.0", Set.of("o"))))
          .then(actions -> actions.emit("dear")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"price\": 150}"));
        assertThat(session.fireAllRules().fired()).hasSize(1);
      }
    }
  }

  @Nested
  @DisplayName("an explicit JSON null")
  class Nulls {

    @Test
    @DisplayName("reads as null, not as an unknown that then fails a boolean check")
    void nullReadsAsNull() {
      final CompiledRuleSet rules = compile(Rules.rule("open")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.closedAt == null", Set.of("o"))))
          .then(actions -> actions.emit("open")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"closedAt\": null}"));
        assertThat(session.fireAllRules().fired()).hasSize(1);
      }
    }

    @Test
    @DisplayName("survives a round trip through a value expression")
    void nullRoundTrips() {
      final CompiledRuleSet rules = compile(Rules.rule("copy")
          .when("o", "Order")
          .then(actions -> actions.emit("copied", "closedAt",
              new ExpressionValue("o.closedAt", Set.of("o")))).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"closedAt\": null}"));
        assertThat(session.fireAllRules().emitted().getFirst()
            .payload().get("closedAt").isNull()).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("a value with no JSON equivalent")
  class Unrepresentable {

    /*
     * The conversion's default arm turned anything it did not recognise into a STRING of its Java
     * toString(). A CEL null became "NULL_VALUE"; an unsigned became a quoted number. This is the
     * §4.6 commit path, so the fabricated value was inserted, indexed and audited as though an
     * author had written it -- the quietest possible corruption.
     */

    @Test
    @DisplayName("a CEL null becomes JSON null, not the string \"NULL_VALUE\"")
    void celNullIsNotAString() {
      final CompiledRuleSet rules = compile(Rules.rule("mark")
          .when("o", "Order")
          .then(actions -> actions.setField("o", "band",
              new ExpressionValue("null", Set.of("o")))).build());

      try (RuleSession session = rules.newSession()) {
        final var handle = session.insert("Order", Facts.json("{\"id\": 1}"));
        session.fireAllRules();
        assertThat(session.get(handle).orElseThrow().payload().get("band").isNull()).isTrue();
      }
    }

    @Test
    @DisplayName("an unsigned integer becomes a number, not a quoted one")
    void unsignedIsANumber() {
      final CompiledRuleSet rules = compile(Rules.rule("count")
          .when("o", "Order")
          .then(actions -> actions.emit("counted", "n",
              new ExpressionValue("uint(7)", Set.of("o")))).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"id\": 1}"));
        assertThat(session.fireAllRules().emitted().getFirst()
            .payload().get("n").isNumber()).isTrue();
      }
    }

    @Test
    @DisplayName("anything still unrepresentable is refused rather than stringified")
    void trulyUnrepresentableRefused() {
      final CompiledRuleSet rules = compile(Rules.rule("typed")
          .when("o", "Order")
          .then(actions -> actions.emit("t", "v",
              new ExpressionValue("type(o.id)", Set.of("o")))).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"id\": 1}"));
        assertThatThrownBy(session::fireAllRules)
            .isInstanceOf(ExpressionEvaluationException.class)
            .hasMessageContaining("no JSON equivalent");
      }
    }
  }

  @Nested
  @DisplayName("a condition that cannot be evaluated")
  class FailingCondition {

    @Test
    @DisplayName("names the rule, the alias and the expression, since nothing catches it")
    void failureIsActionable() {
      final CompiledRuleSet rules = compile(Rules.rule("compares-a-string")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.name > 100", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"name\": \"abc\"}"));
        assertThatThrownBy(session::fireAllRules)
            .isInstanceOf(ExpressionEvaluationException.class)
            .hasMessageContaining("compares-a-string")
            .hasMessageContaining("o.name > 100")
            .hasMessageContaining("stops the fire cycle");
      }
    }
  }

  @Nested
  @DisplayName("an expression naming an insertFact alias")
  class InsertAliases {

    @Test
    @DisplayName("is rejected when the insert comes later, as the $ref form already was")
    void forwardInsertAliasRejected() {
      assertThatThrownBy(() -> compile(Rules.rule("forward")
          .when("o", "Order")
          .then(actions -> actions
              .emit("early", "v", new ExpressionValue("sig.weight", Set.of()))
              .insertFactAs("RiskSignal", "sig", "weight", 3))
          .build()))
          .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class);
    }

    @Test
    @DisplayName("is accepted when the insert comes first, which §6.2.2 allows")
    void earlierInsertAliasAccepted() {
      assertThatCode(() -> compile(Rules.rule("backward")
          .when("o", "Order")
          .then(actions -> actions
              .insertFactAs("RiskSignal", "sig", "weight", 3)
              .emit("late", "v", new ExpressionValue("sig.weight", Set.of())))
          .build()))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("MatchExplainer")
  class Explaining {

    /*
     * The explainer knew nothing about conditions, so it reported matches the engine would never
     * fire. That is the inverse of the defect d973982 fixed, and worse: §7.2 exists to answer "why
     * did my rule not fire", and a phantom match sends the author looking somewhere else entirely.
     */

    @Test
    @DisplayName("does not report a match a condition rejects")
    void noPhantomMatch() {
      final CompiledRuleSet rules = compile(Rules.rule("expl")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"total\": 5}"));
        assertThat(session.fireAllRules().fired()).isEmpty();

        final Explanation why = new MatchExplainer(rules, session).explain("expl");
        assertThat(why.verdict()).isPresent();
        assertThat(why.describe())
            .as("the verdict must name the condition, not claim an eligible match")
            .contains("condition")
            .doesNotContain("all eligible");
      }
    }

    @Test
    @DisplayName("still reports a match a condition accepts")
    void realMatchStillReported() {
      final CompiledRuleSet rules = compile(Rules.rule("expl")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"total\": 5000}"));
        final Explanation why = new MatchExplainer(rules, session).explain("expl");
        assertThat(why.describe()).doesNotContain("condition' expression rejected");
      }
    }

    @Test
    @DisplayName("on a JOINED rule, blames the condition rather than a join that in fact holds")
    void joinedRuleBlamesTheCondition() {
      /*
       * Every explainer test above is single-pattern, and that is exactly how the first version of
       * this fix shipped a wrong answer: with the condition-rejected tuples removed from the match
       * set, the join annotator concluded the JOINS had failed and named a join that holds.
       */
      final CompiledRuleSet rules = compile(Rules.rule("joined")
          .when("c", "Customer")
          .when("o", "Order", pattern -> pattern
              .ref("customerId", "c.id")
              .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Customer", Facts.json("{\"id\": 7}"));
        session.insert("Order", Facts.json("{\"customerId\": 7, \"total\": 5}"));
        assertThat(session.fireAllRules().fired()).isEmpty();

        final String why = new MatchExplainer(rules, session).explain("joined").describe();
        assertThat(why)
            .as("the join 7 == 7 holds; saying otherwise sends the author to inspect correct code")
            .doesNotContain("no combination of them satisfies")
            .contains("condition");
      }
    }

    @Test
    @DisplayName("distinguishes a condition that said no from one that could not be evaluated")
    void rejectedIsNotTheSameAsFailed() {
      final CompiledRuleSet rules = compile(Rules.rule("boom")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.missingField > 1", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"total\": 5}"));

        final String why = new MatchExplainer(rules, session).explain("boom").describe();
        assertThat(why)
            .as("at run time this throws and stops the cycle; it does not filter anything")
            .contains("could not be evaluated")
            .contains("stops the fire cycle");
      }
    }

    @Test
    @DisplayName("a negation is applied before the condition, and the counts prove which ran first")
    void negationRunsBeforeTheCondition() {
      /*
       * §1's NOT_EXISTS and §6.4's conditions both remove tuples that satisfied every pattern and
       * every join, and RecomputingAgenda.postFilter applies absences() first -- so a tuple an
       * absence defeats is never offered to the condition at run time. The explainer has to apply
       * them in the same order or it reports a rejection the engine never made.
       *
       * The three orders below distinguish the two orderings by arithmetic. Order 3 fails BOTH: it
       * has a payment and its total is below the threshold. Under the engine's order it is one of
       * two suppressed by the negation; under the reverse it would be one of two rejected by the
       * condition. Only one of those readings can be right, and the counts say which.
       */
      final CompiledRuleSet rules = compile(Rules.rule("unpaid-and-big")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"id\": 1, \"total\": 5000}"));
        session.insert("Order", Facts.json("{\"id\": 2, \"total\": 5}"));
        session.insert("Order", Facts.json("{\"id\": 3, \"total\": 5}"));
        session.insert("Payment", Facts.json("{\"orderId\": 1}"));
        session.insert("Payment", Facts.json("{\"orderId\": 3}"));
        assertThat(session.fireAllRules().fired()).isEmpty();

        final Explanation why = new MatchExplainer(rules, session).explain("unpaid-and-big");

        assertThat(why.verdict()).hasValueSatisfying(verdict -> assertThat(verdict)
            .as("orders 1 and 3, the negation having claimed 3 before the condition saw it")
            .contains("2 combination(s) matched every pattern and join")
            .contains("no Payment matches 'p'")
            .as("order 2 alone; the clause is additional to the count above, not another view of it")
            .contains("a further 1 were rejected by a 'condition' expression"));
        assertThat(why.negations()).singleElement().satisfies(negation ->
            assertThat(negation.suppressed()).isEqualTo(2));
      }
    }

    @Test
    @DisplayName("a negation and an unevaluable condition are reported as the different problems they are")
    void negationAndAThrowingConditionAreBothNamed() {
      // The urgent half of §6.4's two outcomes survives being reported alongside a negation: at run
      // time an expression that cannot be evaluated does not filter a match, it throws and stops the
      // fire cycle, so telling the author their facts were "rejected" points them at their data.
      final CompiledRuleSet rules = compile(Rules.rule("boom-and-unpaid")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.missingField > 1", Set.of("o"))))
          .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"id\": 1, \"total\": 5}"));
        session.insert("Order", Facts.json("{\"id\": 2, \"total\": 5}"));
        session.insert("Payment", Facts.json("{\"orderId\": 1}"));

        final String why = new MatchExplainer(rules, session).explain("boom-and-unpaid").describe();

        assertThat(why)
            .contains("no Payment matches 'p'")
            .contains("could not be evaluated")
            .contains("stops the fire cycle")
            .as("the joins held and the patterns matched; blaming either is a wrong lead")
            .doesNotContain("no combination of them satisfies");
      }
    }

    @Test
    @DisplayName("does not claim exhaustiveness when the search ran out of budget")
    void truncatedSearchIsNotADefiniteNegative() {
      /*
       * The count for a truncated walk is the budget, not a count, and "rejected each of them"
       * asserts a completeness the walk never reached. Commit 10588b2 fixed exactly this shape by
       * hand once already: "stop it reporting a budget as a count".
       */
      final CompiledRuleSet rules = compile(Rules.rule("wide")
          .when("a", "Order")
          .when("b", "Order2", pattern -> pattern.constraint(
              new ExpressionConstraint("a.total > 1000000", Set.of("a"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        for (int index = 0; index < 600; index++) {
          session.insert("Order", Facts.json("{\"total\": 5}"));
          session.insert("Order2", Facts.json("{\"n\": 1}"));
        }

        final String why = new MatchExplainer(rules, session).explain("wide").describe();
        assertThat(why)
            .as("a truncated search must not report its budget as a count of rejections")
            .doesNotContain("rejected each of them")
            .contains("budget");
      }
    }

    @Test
    @DisplayName("does not throw while explaining a condition that cannot evaluate")
    void explainingDoesNotThrow() {
      final CompiledRuleSet rules = compile(Rules.rule("expl")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.name > 100", Set.of("o"))))
          .then(actions -> actions.emit("e")).build());

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.json("{\"name\": \"abc\"}"));
        // A diagnostic tool that throws while diagnosing is worse than one that says less.
        assertThatCode(() -> new MatchExplainer(rules, session).explain("expl"))
            .doesNotThrowAnyException();
      }
    }
  }
}
