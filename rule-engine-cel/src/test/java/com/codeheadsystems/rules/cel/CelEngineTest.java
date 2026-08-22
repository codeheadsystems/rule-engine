package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
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
  @DisplayName("what a condition means for the streaming matcher's materialised memory")
  class StreamingMemoryShape {

    @Test
    @DisplayName("the beta memory holds matches a condition then rejects, and keeps holding them")
    void memoryHoldsPreFilterMatches() {
      /*
       * §6.4 conditions are applied in RecomputingAgenda.postFilter -- the shared base -- so every
       * matcher applies them identically and MatcherEquivalence covers that. What is specific to
       * the Rete shape, and what nothing else asserts, is the SHAPE of its state: the beta memory
       * holds matches the condition will reject. It has to, because a condition is evaluated
       * against a COMPLETE tuple and the memory is what completes it.
       *
       * The regression that would break is a future optimisation filtering at maintenance time to
       * keep the memory smaller. That is wrong twice over: a condition reads payloads, and payloads
       * change under §3.4.1's skipped update without the memory being touched, so a match filtered
       * out when it was derived could never come back.
       *
       * This test lives in -cel because only a real condition reaches the post-filter. An earlier
       * version of it used `hasField`, which compiles to an ALPHA test -- evaluated before pattern
       * membership, so the memory would have been post-filtered with respect to it, the exact
       * opposite of the claim. It would have passed against the regression it named.
       */
      final RuleDefinition rule = Rules.rule("bigger-than-partner")
          .when("a", "Order", pattern -> pattern.gt("total", 0))
          .when("b", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("b.total > a.total", Set.of("a", "b"))))
          .then(actions -> actions.emit("bigger", "id", Rules.ref("b.id")))
          .build();

      // Every ordered pair is a match the memory holds; only the ascending ones survive the
      // condition. Driven through the oracle so the count cannot drift from what a match means.
      MatcherEquivalence.assertEquivalent(List.of(rule),
          session -> {
            for (int id = 0; id < 4; id++) {
              session.insert("Order", Facts.obj("id", id, "total", id * 100));
            }
            session.fireAllRules();
            // A second insert after firing: the new fact must complete matches against everything
            // already held, and the condition must be applied to those too rather than to a
            // memory that was pruned when its members arrived.
            session.insert("Order", Facts.obj("id", 4, "total", 50));
          },
          SessionOptions.builder(), withCel());
    }

    @Test
    @DisplayName("a condition-rejected match leaves the conflict set and the join memory keeps it")
    void theConflictSetDropsWhatAConditionRejects() {
      /*
       * §4.3's shape holds the matches that have not fired; a condition rejects matches that will
       * never fire, so keeping them would drive the conflict set back to the size of the join
       * memory -- rebuilding and re-evaluating an activation per rejected match on every cycle,
       * which is the cost §4.3 removed for the ordinary case. It did exactly that until the
       * post-filter learned to report a rejection.
       *
       * Dropping is lossless, and the argument is two facts about the compiler rather than an
       * intuition. CelExpressions binds only the tuple's aliases and the environment has no clock,
       * so a condition is a pure function of the payloads it reads; and compileCondition records the
       * whole payload root of every type such an alias binds as a tested path. So anything that
       * could flip a condition is an effective update, which §3.4.1 performs as a retract and a
       * re-assert, which destroys the match and derives it again. aConditionThatBecomesTrueStillFires
       * below is that path end to end.
       *
       * Note what is NOT dropped: memoryHoldsPreFilterMatches above still holds, and must -- the
       * join memory is what completes a tuple, and a condition is evaluated against a complete one.
       */
      final RuleDefinition rule = Rules.rule("big-only")
          .when("o", "Order", pattern -> pattern.gt("total", 0)
              .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .then(actions -> actions.emit("big", "id", Rules.ref("o.id")))
          .build();

      final CompiledRuleSet rules = RuleCompiler.compile(List.of(rule), withCel());
      try (RuleSession session = rules.newSession(
          SessionOptions.builder().matching(MatchingStrategy.RETE).build())) {
        for (int id = 0; id < 20; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
          session.fireAllRules();
        }

        assertThat(session.stats().pendingMatchCount())
            .describedAs("nothing the condition rejected is still waiting to fire")
            .isZero();
        assertThat(session.stats().materialisedMatchCount())
            .describedAs("but the join memory holds them, because a condition needs a whole tuple")
            .isEqualTo(20);
      }
    }

    @Test
    @DisplayName("a match whose condition starts holding still fires")
    void aConditionThatBecomesTrueStillFires() {
      // The other half, and the reason the above is a cost rather than a defect: the post-filter is
      // re-run per cycle, so nothing about being rejected once is permanent.
      final RuleDefinition rule = Rules.rule("big-only")
          .when("o", "Order", pattern -> pattern.gt("total", 0)
              .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
          .then(actions -> actions.emit("big", "id", Rules.ref("o.id")))
          .build();

      MatcherEquivalence.assertEquivalent(List.of(rule), session -> {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 10));
        session.fireAllRules();
        session.update(order, Facts.obj("id", 1, "total", 5_000));
        session.fireAllRules();
      }, SessionOptions.builder(), withCel());

      // And the same path with the conflict set watched, since that is what the drop is about: the
      // rejected match is gone, the update re-derives it, and it is offered again.
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(rule), withCel());
      try (RuleSession session = rules.newSession(
          SessionOptions.builder().matching(MatchingStrategy.RETE).build())) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 10));
        session.fireAllRules();
        assertThat(session.stats().pendingMatchCount())
            .describedAs("rejected, so dropped").isZero();

        session.update(order, Facts.obj("id", 1, "total", 5_000));

        assertThat(session.stats().pendingMatchCount())
            .describedAs("re-derived by the update, and now the condition holds")
            .isEqualTo(1);
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
      }
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
