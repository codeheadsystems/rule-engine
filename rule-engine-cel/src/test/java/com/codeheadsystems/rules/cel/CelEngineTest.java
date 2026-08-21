package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Engine;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.FiringSequence;
import com.codeheadsystems.rules.testkit.MatcherEquivalence;
import com.codeheadsystems.rules.testkit.Rules;
import com.codeheadsystems.rules.testkit.ShuffleHarness;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §6.4's escape hatch, driven through the whole engine.
 *
 * <p>The obligation that matters most here is {@link MatcherEquivalence}. §9 gives every phase the
 * same exit criterion -- results identical to the naive oracle -- and §11.5 states the stake: if two
 * matching strategies can diverge, "the choice of session type silently changes business outcomes".
 * An expression is evaluated as a post-filter in the agenda both matchers share, so equivalence
 * should hold by construction; these tests are what turn "should" into "does".
 */
class CelEngineTest {

  private static CompilerOptions withCel() {
    return CompilerOptions.builder().expressions(CelExpressions.create()).build();
  }

  private static final Consumer<RuleSession> ORDERS = session -> {
    session.insert("Customer", Facts.json("""
        {"id": 7, "creditLimit": 1000, "tier": "GOLD"}"""));
    session.insert("Order", Facts.json("""
        {"id": 1, "customerId": 7, "total": 5000, "region": "XX", "priorityFlag": true}"""));
    session.insert("Order", Facts.json("""
        {"id": 2, "customerId": 7, "total": 500, "region": "US", "priorityFlag": false}"""));
    session.insert("Order", Facts.json("""
        {"id": 3, "customerId": 7, "total": 100, "region": "XX", "priorityFlag": false}"""));
  };

  @Nested
  @DisplayName("a condition on the left")
  class Conditions {

    @Test
    @DisplayName("filters matches, and both matchers agree on which")
    void bothMatchersAgree() {
      final RuleDefinition rule = Rules.rule("nested-logic")
          .when("o", "Order", pattern -> pattern.constraint(new ExpressionConstraint(
              "o.total > 1000 && (o.region in ['US','EU'] || o.priorityFlag)", Set.of("o"))))
          .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
          .build();

      final FiringSequence fired = MatcherEquivalence.assertEquivalent(
          List.of(rule), ORDERS, SessionOptions.builder(), withCel());

      // Order 1 alone: over 1000 and priority. Order 2 is US but only 500; order 3 is neither.
      assertThat(fired.steps()).hasSize(1);
      assertThat(fired.steps().getFirst().emitted().getFirst()).contains("\"id\":1");
    }

    @Test
    @DisplayName("spans two facts, and still agrees across matchers")
    void crossFactCondition() {
      final RuleDefinition rule = Rules.rule("over-limit")
          .when("c", "Customer")
          .when("o", "Order", pattern -> pattern
              .ref("customerId", "c.id")
              .constraint(new ExpressionConstraint(
                  "o.total > c.creditLimit * 2", Set.of("o", "c"))))
          .then(actions -> actions.emit("over", "id", Rules.ref("o.id")))
          .build();

      final FiringSequence fired = MatcherEquivalence.assertEquivalent(
          List.of(rule), ORDERS, SessionOptions.builder(), withCel());

      assertThat(fired.steps()).hasSize(1);
    }

    @Test
    @DisplayName("does not disturb the determinism contract of §7.3")
    void determinismHolds() {
      ShuffleHarness.assertDeterministic(List.of(
          Rules.rule("expr-a")
              .when("o", "Order", pattern -> pattern.constraint(
                  new ExpressionConstraint("o.total > 400", Set.of("o"))))
              .then(actions -> actions.emit("a", "id", Rules.ref("o.id"))).build(),
          Rules.rule("expr-b").salience(5)
              .when("o", "Order", pattern -> pattern.constraint(
                  new ExpressionConstraint("o.total > 400", Set.of("o"))))
              .then(actions -> actions.emit("b", "id", Rules.ref("o.id"))).build()),
          ORDERS, withCel());
    }
  }

  @Nested
  @DisplayName("an expression on the right")
  class Values {

    @Test
    @DisplayName("computes a field without needing callFunction's commit-time semantics")
    void computesAValue() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("compute")
          .when("o", "Order", pattern -> pattern.eq("id", 1))
          .then(actions -> actions.emit("priced", "withTax",
              new ExpressionValue("o.total + o.total / 10", Set.of("o"))))
          .build()), withCel());

      final FiringSequence fired = Engine.run(rules, SessionOptions.defaults(), ORDERS);

      assertThat(fired.steps()).singleElement().satisfies(step ->
          assertThat(step.emitted().getFirst()).contains("5500"));
    }

    @Test
    @DisplayName("can read a fact this same right-hand side inserted")
    void readsAStagedInsert() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("derive-then-read")
          .when("o", "Order", pattern -> pattern.eq("id", 1))
          .then(actions -> actions
              .insertFactAs("RiskSignal", "sig", "weight", 3)
              .emit("scored", "score", new ExpressionValue("sig.weight * 2", Set.of("sig"))))
          .build()), withCel());

      final FiringSequence fired = Engine.run(rules, SessionOptions.defaults(), ORDERS);

      assertThat(fired.steps()).singleElement().satisfies(step ->
          assertThat(step.emitted().getFirst()).contains("6"));
    }

    @Test
    @DisplayName("sets a field on a matched fact")
    void setsAField() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("mark")
          .when("o", "Order", pattern -> pattern.eq("id", 1))
          .then(actions -> actions.setField("o", "band",
              new ExpressionValue("o.total > 1000 ? 'HIGH' : 'LOW'", Set.of("o"))))
          .build()), withCel());

      try (RuleSession session = rules.newSession()) {
        ORDERS.accept(session);
        session.fireAllRules();
        assertThat(session.workingMemory().factsOfType("Order")
            .filter(fact -> fact.payload().get("id").intValue() == 1)
            .findFirst().orElseThrow().payload().get("band").stringValue())
            .isEqualTo("HIGH");
      }
    }
  }

  @Nested
  @DisplayName("the compiler")
  class Compilation {

    @Test
    @DisplayName("reports an expression as unindexed, which is §6.4's visible cost")
    void reportedAsUnindexed() {
      final CompilerReport report = RuleCompiler.compile(List.of(Rules.rule("expr")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total > 1", Set.of("o"))))
          .then(actions -> actions.emit("e")).build()), withCel()).report();

      assertThat(report.unindexed()).singleElement().satisfies(constraint ->
          assertThat(constraint.reason()).isEqualTo(
              com.codeheadsystems.rules.report.UnindexedConstraint.Reason.CEL_EXPRESSION));
    }

    @Test
    @DisplayName("rejects an expression over the configured budget (§6.4)")
    void budgetEnforced() {
      final RuleDefinition rule = Rules.rule("expensive")
          .when("o", "Order", pattern -> pattern.constraint(new ExpressionConstraint(
              "o.a > 1 && o.b > 2 && o.c > 3 && o.d > 4 && o.e > 5", Set.of("o"))))
          .then(actions -> actions.emit("e")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule),
          CompilerOptions.builder()
              .expressions(CelExpressions.create())
              .expressionBudget(3)
              .build()))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("exceeds the configured budget");
    }

    @Test
    @DisplayName("turns a bad expression into a diagnostic rather than aborting the batch")
    void badExpressionIsADiagnostic() {
      final RuleDefinition broken = Rules.rule("broken")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total >", Set.of("o"))))
          .then(actions -> actions.emit("e")).build();
      final RuleDefinition alsoBroken = Rules.rule("also-broken")
          .when("o", "Order", pattern -> pattern.eq("id", 1))
          .then(actions -> actions.emit("")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(broken, alsoBroken), withCel()))
          .isInstanceOf(RuleCompilationException.class)
          .satisfies(thrown -> assertThat(
              ((RuleCompilationException) thrown).diagnostics())
              .as("one bad expression must not hide every other problem in the file")
              .hasSize(2));
    }
  }
}
