package com.codeheadsystems.rules.testkit;

import com.codeheadsystems.rules.fact.FactHandle;
import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.AggregateFunction;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@code ACCUMULATE}: the last of §1's deferred quantifiers (spec §2.5's second amendment).
 *
 * <p>Every behavioural case runs through {@code MatcherEquivalence} <strong>and</strong> asserts
 * what fired or what landed. An accumulate is evaluated in the shared agenda base, so the three
 * matchers agree by construction and an equivalence assertion alone could never fail for an
 * accumulate reason.
 */
class AccumulateTest {

  /** Bulk orders: the line items of this order total more than 100 units. */
  private static List<RuleDefinition> bulkOrder() {
    return List.of(Rules.rule("bulk")
        .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
        .accumulate("units", "LineItem",
            Rules.fold(AggregateFunction.SUM, "qty", Operator.GT, 100),
            pattern -> pattern.ref("orderId", "o.id"))
        .then(actions -> actions.emit("order.bulk", "units", Rules.ref("units")))
        .build());
  }

  @Nested
  @DisplayName("a fold over a scope")
  class Semantics {

    @Test
    @DisplayName("fires when the having holds")
    void firesWhenTheTotalPasses() {
      assertThat(MatcherEquivalence.assertEquivalent(bulkOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
      }).steps())
          .describedAs("120 units")
          .hasSize(1);
    }

    @Test
    @DisplayName("and not when it does not")
    void silentWhenTheTotalFails() {
      assertThat(MatcherEquivalence.assertEquivalent(bulkOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
      }).steps())
          .describedAs("60 units")
          .isEmpty();
    }

    @Test
    @DisplayName("the scope is per tuple, so another order's items do not count")
    void scopeIsPerTuple() {
      assertThat(MatcherEquivalence.assertEquivalent(bulkOrder(), session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
        session.insert("Order", Facts.obj("id", 2, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 2, "qty", 60));
      }).steps())
          .describedAs("60 each, so neither is bulk -- summing across orders would fire twice")
          .isEmpty();
    }

    @Test
    @DisplayName("every constraint filters the scope, literals included")
    void literalsFilterTheScopeToo() {
      // Where FOR_ALL cannot: a literal there states a requirement, so it makes a non-matching
      // fact a counterexample rather than excluding it. An accumulate has no requirement half.
      final List<RuleDefinition> physicalOnly = List.of(Rules.rule("bulk")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("units", "LineItem",
              Rules.fold(AggregateFunction.SUM, "qty", Operator.GT, 100),
              pattern -> pattern.ref("orderId", "o.id").eq("kind", "PHYSICAL"))
          .then(actions -> actions.emit("order.bulk"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(physicalOnly, session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60, "kind", "PHYSICAL"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60, "kind", "DIGITAL"));
      }).steps())
          .describedAs("only the physical 60 counts, so the total is under the threshold")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("the answer binds")
  class Binding {

    @Test
    @DisplayName("and the right-hand side reads it")
    void theRhsReadsTheTotal() {
      final CompiledRuleSet rules = Engine.compile(bulkOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 70));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 80));

        final var result = session.fireAllRules();

        assertThat(result.firedCount()).isEqualTo(1);
        assertThat(result.fired().getFirst().emitted().getFirst().payload().get("units").asInt())
            .describedAs("the value the fold produced, read at fire time from working memory")
            .isEqualTo(150);
      }
    }

    @Test
    @DisplayName("and it is re-folded on each read, never carried stale in the tuple")
    void theValueIsFresh() {
      /*
       * §3.2.2's invariant in its sharpest form. An aggregate is the most stale-able value in the
       * engine -- any fact in its scope moving makes it wrong -- so it is folded at read time
       * rather than bound into the tuple. Under the streaming matcher a tuple is materialised and
       * held across cycles, which is where a stored total would go wrong first.
       */
      // `v` is a tested path, so bumping it clears refraction and the rule fires again on the
      // same order. Adding an UNtested field would propagate nothing (§3.4.1) and there would be
      // no second firing to observe -- the trap that has caught three tests in this suite already.
      final CompiledRuleSet rules = Engine.compile(Rules.rule("total")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN").gt("v", 0))
          .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("total", "units", Rules.ref("units")))
          .build());
      try (RuleSession session = rules.newSession()) {
        final var order = session.insert("Order", Facts.obj("id", 1, "status", "OPEN", "v", 1));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 10));
        assertThat(session.fireAllRules().fired().getFirst().emitted().getFirst()
            .payload().get("units").asInt()).isEqualTo(10);

        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 5));
        session.update(order, Facts.obj("id", 1, "status", "OPEN", "v", 2));

        assertThat(session.fireAllRules().fired().getFirst().emitted().getFirst()
            .payload().get("units").asInt())
            .describedAs("re-folded, not remembered")
            .isEqualTo(15);
      }
    }
  }

  @Nested
  @DisplayName("each function")
  class Functions {

    /** Emits every fold over the same three line items, so one firing pins all five. */
    private CompiledRuleSet allFive() {
      return Engine.compile(Rules.rule("stats")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("total", "LineItem", Rules.fold(AggregateFunction.SUM, "price"),
              pattern -> pattern.ref("orderId", "o.id"))
          .accumulate("lines", "LineItem", Rules.count(null, null),
              pattern -> pattern.ref("orderId", "o.id"))
          .accumulate("cheapest", "LineItem", Rules.fold(AggregateFunction.MIN, "price"),
              pattern -> pattern.ref("orderId", "o.id"))
          .accumulate("dearest", "LineItem", Rules.fold(AggregateFunction.MAX, "price"),
              pattern -> pattern.ref("orderId", "o.id"))
          .accumulate("mean", "LineItem", Rules.fold(AggregateFunction.AVERAGE, "price"),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("stats",
              "total", Rules.ref("total"), "lines", Rules.ref("lines"),
              "cheapest", Rules.ref("cheapest"), "dearest", Rules.ref("dearest"),
              "mean", Rules.ref("mean")))
          .build());
    }

    @Test
    @DisplayName("computes what its name says")
    void everyFunction() {
      try (RuleSession session = allFive().newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 30));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 10));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 20));

        final var payload = session.fireAllRules().fired().getFirst().emitted().getFirst().payload();

        assertThat(payload.get("total").asInt()).isEqualTo(60);
        assertThat(payload.get("lines").asInt()).isEqualTo(3);
        assertThat(payload.get("cheapest").asInt())
            .describedAs("the smallest, not the last seen")
            .isEqualTo(10);
        assertThat(payload.get("dearest").asInt())
            .describedAs("the largest, not the last seen")
            .isEqualTo(30);
        assertThat(payload.get("mean").asInt()).isEqualTo(20);
      }
    }

    @Test
    @DisplayName("skips a fact whose field is absent rather than counting it as zero")
    void absentIsNotZero() {
      /*
       * §2.6.1 is emphatic that absent is not zero, and the arithmetic has to agree with the
       * matching semantics or the same rule set means two things. The consequence is the part worth
       * pinning: an average over a scope where one fact lacks the field is the average of the ones
       * that have it, NOT the sum divided by everything in scope.
       */
      try (RuleSession session = allFive().newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 10));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 30));
        session.insert("LineItem", Facts.obj("orderId", 1, "sku", "no-price"));

        final var payload = session.fireAllRules().fired().getFirst().emitted().getFirst().payload();

        assertThat(payload.get("total").asInt()).isEqualTo(40);
        assertThat(payload.get("lines").asInt())
            .describedAs("count asks about the facts, so it counts all three")
            .isEqualTo(3);
        assertThat(payload.get("mean").asInt())
            .describedAs("40 over the TWO that have a price, not over three")
            .isEqualTo(20);
      }
    }

    @Test
    @DisplayName("skips a non-finite double, and still folds a decimal too large for one")
    void nonFiniteIsSkippedButABigDecimalIsNot() {
      /*
       * Two cases and they pull opposite ways, which is why the first attempt at this guard got it
       * wrong. A NaN or an infinity must be skipped: Canonical will not order it, so folding it
       * would make the arithmetic disagree with the comparison semantics. A BigDecimal outside
       * double range must NOT be skipped -- it is an ordinary number that BigDecimal holds fine --
       * and the guard that reached for doubleValue() to test it threw on the matching path instead.
       */
      try (RuleSession session = allFive().newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "price", 10));
        session.insert("LineItem", Facts.obj("orderId", 1, "price",
            tools.jackson.databind.node.DoubleNode.valueOf(Double.NaN)));
        session.insert("LineItem", Facts.obj("orderId", 1, "price",
            tools.jackson.databind.node.DoubleNode.valueOf(Double.POSITIVE_INFINITY)));

        assertThat(session.fireAllRules().fired().getFirst().emitted().getFirst()
            .payload().get("total").asInt())
            .describedAs("neither non-finite value contributes")
            .isEqualTo(10);
      }

      try (RuleSession session = allFive().newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "price",
            tools.jackson.databind.node.DecimalNode.valueOf(new java.math.BigDecimal("1E+400"))));

        assertThat(session.fireAllRules().fired().getFirst().emitted().getFirst()
            .payload().get("total").decimalValue())
            .describedAs("far outside double range, and perfectly foldable")
            .isEqualByComparingTo(new java.math.BigDecimal("1E+400"));
      }
    }

    @Test
    @DisplayName("sums decimals exactly, without floating-point drift")
    void decimalsAreExact() {
      // Through BigDecimal, so 0.1 + 0.2 is 0.3 rather than 0.30000000000000004 -- and so two
      // hosts agree (§7.3), which a double fold cannot promise once the walk order is free.
      try (RuleSession session = allFive().newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.json("""
            {"orderId": 1, "price": 0.1}"""));
        session.insert("LineItem", Facts.json("""
            {"orderId": 1, "price": 0.2}"""));

        assertThat(session.fireAllRules().fired().getFirst().emitted().getFirst()
            .payload().get("total").decimalValue())
            .isEqualByComparingTo(new java.math.BigDecimal("0.3"));
      }
    }
  }

  @Nested
  @DisplayName("an empty scope")
  class EmptyScope {

    @Test
    @DisplayName("sums to zero and counts zero, which are the identities")
    void sumAndCountHaveIdentities() {
      final CompiledRuleSet rules = Engine.compile(Rules.rule("empty")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
              pattern -> pattern.ref("orderId", "o.id"))
          .accumulate("lines", "LineItem", Rules.count(null, null),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("empty",
              "units", Rules.ref("units"), "lines", Rules.ref("lines")))
          .build());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));

        final var emitted = session.fireAllRules().fired().getFirst().emitted().getFirst();

        assertThat(emitted.payload().get("units").asInt()).isZero();
        assertThat(emitted.payload().get("lines").asInt()).isZero();
      }
    }

    @Test
    @DisplayName("averages to absent, so a having on it does not hold")
    void averageOfNothingIsNotZero() {
      // The vacuous-truth trap FOR_ALL carries, in arithmetic form: answering zero would make
      // "average below 10" true for an order with no line items at all.
      final List<RuleDefinition> cheap = List.of(Rules.rule("cheap")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("mean", "LineItem",
              Rules.fold(AggregateFunction.AVERAGE, "price", Operator.LT, 10),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("order.cheap"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(cheap, session ->
          session.insert("Order", Facts.obj("id", 1, "status", "OPEN"))).steps())
          .describedAs("no line items, so there is no mean and nothing is below 10")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("an accumulate over a type the rule already binds")
  class SameType {

    @Test
    @DisplayName("excludes the bound fact, as §1's implicit inequality says")
    void theImplicitInequalityApplies() {
      // "The total of this customer's OTHER orders" is what a same-type accumulate means. Without
      // the exclusion the order in hand counts toward the threshold it is being tested against,
      // which is a rule that means something different from what it says.
      final List<RuleDefinition> otherOrders = List.of(Rules.rule("big-spender")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("others", "Order",
              Rules.fold(AggregateFunction.SUM, "total", Operator.GT, 100),
              pattern -> pattern.ref("customerId", "o.customerId"))
          .then(actions -> actions.emit("customer.big"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(otherOrders, session ->
          session.insert("Order",
              Facts.obj("id", 1, "status", "OPEN", "customerId", 7, "total", 500))).steps())
          .describedAs("the only order is the bound one, so the others total nothing")
          .isEmpty();
    }

    @Test
    @DisplayName("and another fact of that type does count")
    void anotherFactCounts() {
      final List<RuleDefinition> otherOrders = List.of(Rules.rule("big-spender")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("others", "Order",
              Rules.fold(AggregateFunction.SUM, "total", Operator.GT, 100),
              pattern -> pattern.ref("customerId", "o.customerId"))
          .then(actions -> actions.emit("customer.big"))
          .build());

      assertThat(MatcherEquivalence.assertEquivalent(otherOrders, session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN", "customerId", 7, "total", 10));
        session.insert("Order", Facts.obj("id", 2, "status", "DONE", "customerId", 7, "total", 500));
      }).steps())
          .describedAs("order 2 is in scope for order 1 -- the scope filters on customerId only")
          .hasSize(1);
    }
  }

  @Nested
  @DisplayName("what the compiler refuses")
  class Validation {

    @Test
    @DisplayName("a join to an accumulate alias, which names a value and not a fact")
    void noJoinToAnAccumulate() {
      final RuleDefinition joined = Rules.rule("joined")
          .when("o", "Order")
          .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
              pattern -> pattern.ref("orderId", "o.id"))
          .when("c", "Cap", pattern -> pattern.ref("limit", "units.value"))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(joined)))
          .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class)
          .describedAs("names what it IS and what may read it, rather than calling it unbound")
          .hasMessageContaining("is an ACCUMULATE pattern")
          .hasMessageContaining("nothing here to write to, retract, or join against")
          .describedAs("and the message names the routes that DO work, so an author does not"
              + " delete a binding they are right to want")
          .hasMessageContaining("Reading the answer is fine");
    }

    @Test
    @DisplayName("a setField or retractFact targeting an accumulate alias")
    void nothingMayWriteToAValue() {
      // Reading the answer is legal; writing to it is not, and the two live in different checks
      // for that reason. An earlier version put the escape in the shared alias check, so this
      // compiled clean and threw at fire time -- the shape of defect the compiler exists to stop.
      for (final RuleDefinition rule : List.of(
          Rules.rule("write")
              .when("o", "Order")
              .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
                  pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.setField("units", "x", 1))
              .build(),
          Rules.rule("drop")
              .when("o", "Order")
              .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
                  pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.retractFact("units"))
              .build())) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(rule)))
            .describedAs("%s", rule.id())
            .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class)
            .hasMessageContaining("is an ACCUMULATE pattern");
      }
    }

    @Test
    @DisplayName("a dotted $ref into an accumulate alias, which would resolve to null")
    void anAccumulateHasNoFields() {
      // The natural thing to write, because every other alias in the language needs a field --
      // and it used to compile and put a JSON null in the payload.
      final RuleDefinition dotted = Rules.rule("dotted")
          .when("o", "Order")
          .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty"),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e", "n", Rules.ref("units.value")))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(dotted)))
          .hasMessageContaining("it binds a value, not a fact")
          .hasMessageContaining("Write 'units' on its own");
    }

    @Test
    @DisplayName("a having whose operator would throw on the matching path")
    void theHavingOperatorIsChecked() {
      /*
       * CLAUDE.md's standing warning: Comparisons calls asBoolean() for HAS_FIELD and IS_NULL and
       * reaches for a precompiled pattern for MATCHES, and Jackson 3's accessors throw on a type
       * mismatch rather than answering false. An AggregateTest is the third position that reaches
       * Comparisons.test; the other two are guarded at compile time and so is this one now.
       */
      for (final Operator op : List.of(Operator.HAS_FIELD, Operator.IS_NULL, Operator.MATCHES,
          Operator.IN, Operator.NOT_IN)) {
        final RuleDefinition bad = Rules.rule("bad-having")
            .when("o", "Order")
            .accumulate("units", "LineItem", Rules.fold(AggregateFunction.SUM, "qty", op, "x"),
                pattern -> pattern.ref("orderId", "o.id"))
            .then(actions -> actions.emit("e"))
            .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(bad)))
            .describedAs("%s", op)
            .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class)
            .hasMessageContaining("'having' cannot use " + op);
      }
    }

    @Test
    @DisplayName("a count with a field, and a sum without one")
    void theFunctionAndTheFieldMustAgree() {
      final RuleDefinition counted = Rules.rule("counted")
          .when("o", "Order")
          .accumulate("n", "LineItem",
              new com.codeheadsystems.rules.rule.Accumulate(AggregateFunction.COUNT,
                  java.util.Optional.of("qty"), java.util.Optional.empty()),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e"))
          .build();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(counted)))
          .hasMessageContaining("count takes no field");

      final RuleDefinition summed = Rules.rule("summed")
          .when("o", "Order")
          .accumulate("n", "LineItem",
              new com.codeheadsystems.rules.rule.Accumulate(AggregateFunction.SUM,
                  java.util.Optional.empty(), java.util.Optional.empty()),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.emit("e"))
          .build();
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(summed)))
          .hasMessageContaining("sum needs a field to fold over");
    }

    @Test
    @DisplayName("a rule of nothing but an accumulate, which has no tuple to fold against")
    void anAccumulateCannotStandAlone() {
      final RuleDefinition alone = Rules.rule("alone")
          .accumulate("n", "LineItem", Rules.count(null, null), pattern -> pattern.gt("qty", 0))
          .then(actions -> actions.emit("e"))
          .build();

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> com.codeheadsystems.rules.compiler.RuleCompiler.compile(List.of(alone)))
          .hasMessageContaining("no pattern binds a fact");
    }
  }

  @Nested
  @DisplayName("§3.4.1's update gate")
  class TestedPaths {

    @Test
    @DisplayName("an update to a field only the fold reads still propagates")
    void theFoldedFieldIsATestedPath() {
      /*
       * The mutation that left the whole suite green: delete the `record(...)` in compileAccumulate
       * and nothing noticed. §3.4.1's gate is upstream of every matcher, so a change to a field
       * only an accumulate reads would propagate nothing and the rule would keep its old answer --
       * and CLAUDE.md records exactly this shape from the §6.4 condition case, where "no
       * differential test could catch that" because every matcher was identically wrong.
       *
       * An UPDATE is what tests it. An insert marks the rule dirty whatever the tested paths are,
       * which is why the fresh-value test beside this one misses it.
       */
      final List<RuleDefinition> bulk = bulkOrder();

      assertThat(MatcherEquivalence.assertEquivalent(bulk, session -> {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        final FactHandle item = session.insert("LineItem", Facts.obj("orderId", 1, "qty", 10));
        session.fireAllRules();
        // Crosses `sum gt 100`, and `qty` is read by nothing but the fold.
        session.update(item, Facts.obj("orderId", 1, "qty", 500));
      }).steps())
          .describedAs("the update has to reach the matcher, or the total stays at 10")
          .hasSize(1);
    }

    @Test
    @DisplayName("and so does an update that takes the total back below the threshold")
    void theGateWorksInBothDirections() {
      final CompiledRuleSet rules = Engine.compile(Rules.rule("bulk")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("units", "LineItem",
              Rules.fold(AggregateFunction.SUM, "qty", Operator.GT, 100),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertLogical("BulkOrder", "orderId", Rules.ref("o.id")))
          .build());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        final FactHandle item = session.insert("LineItem", Facts.obj("orderId", 1, "qty", 500));
        session.fireAllRules();
        assertThat(session.workingMemory().factsOfType("BulkOrder").count()).isEqualTo(1);

        session.update(item, Facts.obj("orderId", 1, "qty", 10));
        session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("BulkOrder").count())
            .describedAs("the conclusion goes with the total")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("§7.3's determinism contract")
  class Determinism {

    @Test
    @DisplayName("the same facts fold to the same answer whatever order the rules are declared in")
    void foldingIsStable() {
      // SUM and AVERAGE fold through BigDecimal for this reason: a double fold would depend on
      // the association order. The scope is walked in working-memory order, which is deterministic
      // per session; ShuffleHarness permutes rule declaration order, not fact order.
      ShuffleHarness.assertDeterministic(
          List.of(bulkOrder().getFirst(),
              Rules.rule("counted")
                  .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
                  .accumulate("lines", "LineItem", Rules.count(Operator.GTE, 2),
                      pattern -> pattern.ref("orderId", "o.id"))
                  .then(actions -> actions.emit("order.multiline"))
                  .build()),
          session -> {
            session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
            for (int qty = 1; qty <= 5; qty++) {
              session.insert("LineItem", Facts.obj("orderId", 1, "qty", qty * 11));
            }
          });
    }
  }

  @Nested
  @DisplayName("truth maintenance over a fold")
  class WithTruthMaintenance {

    @Test
    @DisplayName("withdraws a conclusion when the total stops passing its having")
    void theTotalMoving() {
      /*
       * The interaction that would have been silently missing: TupleMatch re-asks a justification
       * by re-running every gate, and an accumulate's `having` is one. Leave it out and truth
       * maintenance is correct for two of §2.5's three post-filters and wrong for the third --
       * a conclusion drawn from "these line items total over 100" outliving the line item that
       * made it true.
       */
      final CompiledRuleSet rules = Engine.compile(Rules.rule("bulk")
          .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
          .accumulate("units", "LineItem",
              Rules.fold(AggregateFunction.SUM, "qty", Operator.GT, 100),
              pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertLogical("BulkOrder", "orderId", Rules.ref("o.id")))
          .build());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "OPEN"));
        session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
        final var second = session.insert("LineItem", Facts.obj("orderId", 1, "qty", 60));
        session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("BulkOrder").count()).isEqualTo(1);

        session.retract(second);
        session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("BulkOrder").count())
            .describedAs("60 units left, so it is not a bulk order any more")
            .isZero();
      }
    }
  }
}
