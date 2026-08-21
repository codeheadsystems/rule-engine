package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.network.JoinPlan;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.RuleDefinition;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-fire join ordering (spec §3.3).
 *
 * <p>§3.3 makes choosing the smaller side "a per-fire decision under TREAT, since both memory sizes
 * are known". The consequence worth testing is that a rule's <em>written</em> order stops dictating
 * its cost: "for each pending order, find its customer" and "for each customer, find their pending
 * orders" are the same rule, and the matcher should pick whichever direction is cheaper right now.
 *
 * <p>Tested on the plan directly rather than through timing. What the plan decides is a discrete,
 * checkable fact; a timing test would prove less and be flaky besides. That the plan is <em>obeyed</em>
 * without changing results is what the differential suite covers.
 */
class JoinPlanTest {

  /** Written large-side-first, which is the natural way to phrase it and the wrong way to run it. */
  private static CompiledRule orderThenCustomer() {
    final RuleDefinition rule = Rules.rule("review")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("hit"))
        .build();
    return RuleCompiler.compile(List.of(rule)).rules().getFirst();
  }

  @Test
  @DisplayName("the smaller memory is bound first, whichever way the rule was written")
  void smallerSideFirst() {
    final CompiledRule rule = orderThenCustomer();

    // Ten thousand pending orders, four customers. Binding orders first means ten thousand index
    // probes; binding customers first means four.
    assertThat(JoinPlan.of(rule, new int[] {10_000, 4}).steps())
        .extracting(JoinPlan.Step::position)
        .containsExactly(1, 0);

    // Reverse the population and the plan reverses with it. Same rule, same compilation.
    assertThat(JoinPlan.of(rule, new int[] {4, 10_000}).steps())
        .extracting(JoinPlan.Step::position)
        .containsExactly(0, 1);
  }

  @Test
  @DisplayName("a join is evaluated at the step where its second endpoint is bound")
  void joinsAreAppliedWhenBothEndsAreKnown() {
    final CompiledRule rule = orderThenCustomer();

    // Whichever end goes first, the edge belongs to the second step -- that is the first moment
    // both payloads exist. Under a fixed left-to-right order this is always the constraint-bearing
    // pattern; under a chosen order it can be either end.
    final JoinPlan customersFirst = JoinPlan.of(rule, new int[] {10_000, 4});
    assertThat(customersFirst.steps().getFirst().edges()).isEmpty();
    assertThat(customersFirst.steps().get(1).edges()).hasSize(1);

    final JoinPlan ordersFirst = JoinPlan.of(rule, new int[] {4, 10_000});
    assertThat(ordersFirst.steps().getFirst().edges()).isEmpty();
    assertThat(ordersFirst.steps().get(1).edges()).hasSize(1);
  }

  @Test
  @DisplayName("a large disconnected pattern is deferred to last")
  void largeDisconnectedPatternsGoLast() {
    // A pattern with no join edge to anything bound cannot be narrowed, so it multiplies whatever
    // it is combined with. The total number of leaves is the same wherever it sits -- multiplication
    // commutes -- but placing it last means it is only expanded for prefixes that already survived
    // every join, which is far fewer.
    final RuleDefinition rule = Rules.rule("mixed")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .when("t", "Tariff")
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    // Tariff is by far the largest and joins to nothing: bind the connected pair first, then
    // expand tariffs only against the pairs that matched.
    assertThat(JoinPlan.of(compiled, new int[] {50, 200, 10_000}).steps())
        .extracting(JoinPlan.Step::position)
        .containsExactly(0, 1, 2);
  }

  @Test
  @DisplayName("a tiny disconnected pattern may go first, because it costs nothing wherever it sits")
  void tinyDisconnectedPatternsAreHarmless() {
    // The mirror of the case above, and the reason the rule is about size rather than a blanket
    // "connected first": a single-fact pattern is a multiplier of one. Deferring it would be
    // ceremony, and the first pick has nothing bound to be connected to anyway.
    final RuleDefinition rule = Rules.rule("mixed")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .when("t", "Tariff")
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    assertThat(JoinPlan.of(compiled, new int[] {50, 200, 1}).steps())
        .extracting(JoinPlan.Step::position)
        .containsExactly(2, 0, 1);
  }

  @Test
  @DisplayName("every pattern is bound exactly once, whatever the sizes")
  void thePlanIsATotalOrder() {
    final RuleDefinition rule = Rules.rule("chain")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.ref("k", "a.k"))
        .when("c", "C", pattern -> pattern.ref("k", "b.k"))
        .when("d", "D", pattern -> pattern.ref("k", "c.k"))
        .build();
    final RuleDefinition withThen = new RuleDefinition(rule.id(), rule.salience(), rule.when(),
        Rules.rule("x").when("a", "A").then(t -> t.emit("e")).build().then(),
        rule.noLoop(), rule.agendaGroup(), rule.tags());
    final CompiledRule compiled = RuleCompiler.compile(List.of(withThen)).rules().getFirst();

    for (final int[] sizes : List.of(
        new int[] {1, 2, 3, 4}, new int[] {4, 3, 2, 1},
        new int[] {9, 1, 9, 1}, new int[] {0, 0, 0, 0})) {
      final JoinPlan plan = JoinPlan.of(compiled, sizes);
      assertThat(plan.steps()).hasSize(4);
      assertThat(plan.positions()).containsExactlyInAnyOrder(0, 1, 2, 3);
    }
  }

  @Test
  @DisplayName("a chain is walked along its edges, not scattered by size alone")
  void chainsStayConnected() {
    final RuleDefinition rule = Rules.rule("chain")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.ref("k", "a.k"))
        .when("c", "C", pattern -> pattern.ref("k", "b.k"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    // C is smallest, so it goes first. B is C's only neighbour, so it must come second even though
    // A is smaller than B -- picking A there would be an unconstrained cross product with C.
    assertThat(JoinPlan.of(compiled, new int[] {5, 50, 1}).steps())
        .extracting(JoinPlan.Step::position)
        .containsExactly(2, 1, 0);
  }

  @Test
  @DisplayName("a single-pattern rule needs no plan beyond itself")
  void singlePattern() {
    final RuleDefinition rule = Rules.rule("solo")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    final JoinPlan plan = JoinPlan.of(compiled, new int[] {7});
    assertThat(plan.steps()).hasSize(1);
    assertThat(plan.steps().getFirst().edges()).isEmpty();
    assertThat(plan.steps().getFirst().distinctFrom()).isEmpty();
  }

  @Test
  @DisplayName("a same-type inequality is checked from whichever end is bound second")
  void selfJoinInequalityFollowsThePlan() {
    final RuleDefinition rule = Rules.rule("duplicate-orders")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("dupe"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    // The compiler records the inequality on the later pattern only, pointing at earlier positions.
    // It is symmetric, so whichever end is bound second is where it must be checked -- exactly once,
    // and never zero times.
    //
    // An earlier version of this test passed equal sizes, which meant the strict `<` tie-break
    // always bound position 0 first. It therefore only ever exercised the direction that already
    // worked, and missed a defect that let one fact bind both aliases. Both orderings now.
    final JoinPlan writtenOrder = JoinPlan.of(compiled, new int[] {1, 3});
    assertThat(writtenOrder.steps()).extracting(JoinPlan.Step::position).containsExactly(0, 1);
    assertThat(writtenOrder.steps().getFirst().distinctFrom()).isEmpty();
    assertThat(writtenOrder.steps().get(1).distinctFrom()).containsExactly(0);

    final JoinPlan reversed = JoinPlan.of(compiled, new int[] {3, 1});
    assertThat(reversed.steps()).extracting(JoinPlan.Step::position).containsExactly(1, 0);
    assertThat(reversed.steps().getFirst().distinctFrom()).isEmpty();
    assertThat(reversed.steps().get(1))
        .describedAs("bound second, so this is the only step that can enforce the inequality")
        .satisfies(step -> assertThat(step.distinctFrom()).containsExactly(1));
  }

  @Test
  @DisplayName("a pattern is never required to differ from itself")
  void noSelfInequality() {
    final RuleDefinition rule = Rules.rule("solo")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("hit"))
        .build();
    final CompiledRule compiled = RuleCompiler.compile(List.of(rule)).rules().getFirst();

    assertThat(JoinPlan.of(compiled, new int[] {3}).steps().getFirst().distinctFrom()).isEmpty();
  }
}
