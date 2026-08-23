package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.AggregateFunction;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The DSL against the constraint AST, as {@link MatcherEquivalence} runs the network against the
 * oracle.
 *
 * <p>Every case here writes one rule twice -- once as YAML, once against the AST -- and asserts the
 * two are indistinguishable in structure and in behaviour. The AST form is the definition, because
 * it is what every other test in this repository is written against and what the engine has been
 * correct on since Phase 0.
 *
 * <p>The corpus is chosen to cover the shapes where the two forms could plausibly diverge rather
 * than to re-walk §6.2.1's table, which {@code OperatorMapTest} does exhaustively one row at a
 * time. What is interesting here is composition: joins, ordering, several constraints per pattern,
 * and the literal forms whose canonicalisation is easy to get subtly wrong.
 */
class DslEquivalenceTest {

  private static final Consumer<RuleSession> ORDERS = session -> {
    session.insert("Customer", Facts.json("""
        {"id": 7, "riskTier": "HIGH", "floor": 1000, "ceiling": 50000}"""));
    session.insert("Customer", Facts.json("""
        {"id": 8, "riskTier": "LOW", "floor": 0, "ceiling": 100}"""));
    session.insert("Order", Facts.json("""
        {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7, "email": "a@example.com"}"""));
    session.insert("Order", Facts.json("""
        {"id": 2, "total": 50, "status": "PENDING", "customerId": 8, "email": "b@example.com"}"""));
    session.insert("Order", Facts.json("""
        {"id": 3, "total": 9000, "status": "CLOSED", "customerId": 7}"""));
  };

  /** Wraps rule bodies in a file header. */
  private static String file(final String rules) {
    return "apiVersion: rules.v1\nrules:\n" + rules;
  }

  @Nested
  @DisplayName("a single-fact rule")
  class SingleFact {

    @Test
    @DisplayName("with an equality and a range matches the hand-built form exactly")
    void equalityAndRange() {
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: high-value
                  when:
                    - fact: Order
                      as: o
                      where:
                        total:  { gt: 10000 }
                        status: { eq: "PENDING" }
                  then:
                    - action: emit
                      event: flagged
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("high-value")
              .when("o", "Order", pattern -> pattern.gt("total", 10000).eq("status", "PENDING"))
              .then(actions -> actions.emit("flagged", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS);

      assertThat(fired.steps()).hasSize(1);
    }

    @Test
    @DisplayName("with membership, presence and a regex matches too")
    void membershipPresenceRegex() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: mixed
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { in: ["PENDING", "REVIEW"] }
                        # Two operators on one field go in ONE map. Writing 'email' twice is a
                        # duplicate key, which the reader rejects rather than silently keeping the
                        # last -- see RuleFormat.strict.
                        email:  { hasField: true, matches: "^[a-z]@example\\\\.com$" }
                  then:
                    - action: emit
                      event: matched
              """),
          List.of(Rules.rule("mixed")
              .when("o", "Order", pattern -> pattern
                  .in("status", "PENDING", "REVIEW")
                  .hasField("email", true)
                  .matches("email", "^[a-z]@example\\.com$"))
              .then(actions -> actions.emit("matched"))
              .build()),
          ORDERS);
    }

    @Test
    @DisplayName("with a two-sided between matches the builder's inclusive range")
    void between() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: mid-value
                  when:
                    - fact: Order
                      as: o
                      where:
                        total: { between: { from: 100, to: 30000 } }
                  then:
                    - action: emit
                      event: mid
              """),
          List.of(Rules.rule("mid-value")
              .when("o", "Order", pattern -> pattern.between("total", 100, 30000))
              .then(actions -> actions.emit("mid"))
              .build()),
          ORDERS);
    }
  }

  @Nested
  @DisplayName("a join")
  class Joins {

    @Test
    @DisplayName("on equality matches the hand-built $ref form")
    void equalityJoin() {
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: high-value-order-review
                  salience: 10
                  noLoop: true
                  when:
                    - fact: Order
                      as: o
                      where:
                        total:  { gt: 10000 }
                        status: { eq: "PENDING" }
                    - fact: Customer
                      as: c
                      where:
                        id:       { eq: { $ref: o.customerId } }
                        riskTier: { in: ["HIGH", "MEDIUM"] }
                  then:
                    - action: setField
                      target: o
                      field: status
                      value: "REVIEW"
                    - action: emit
                      event: "order.flagged"
                      payload:
                        orderId: { $ref: o.id }
                        reason: "high value + risk tier"
              """),
          List.of(Rules.rule("high-value-order-review")
              .salience(10)
              .noLoop()
              .when("o", "Order", pattern -> pattern.gt("total", 10000).eq("status", "PENDING"))
              .when("c", "Customer", pattern -> pattern
                  .ref("id", "o.customerId").in("riskTier", "HIGH", "MEDIUM"))
              .then(actions -> actions
                  .setField("o", "status", "REVIEW")
                  .emit("order.flagged",
                      "orderId", Rules.ref("o.id"),
                      "reason", "high value + risk tier"))
              .build()),
          ORDERS);

      assertThat(fired.steps()).hasSize(1);
    }

    @Test
    @DisplayName("on an ordered operator matches, since either end may be bound first")
    void orderedJoin() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: over-ceiling
                  when:
                    - fact: Customer
                      as: c
                    - fact: Order
                      as: o
                      where:
                        total: { gt: { $ref: c.ceiling } }
                  then:
                    - action: emit
                      event: over
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("over-ceiling")
              .when("c", "Customer")
              .when("o", "Order", pattern -> pattern.ref("total", "c.ceiling",
                  com.codeheadsystems.rules.rule.Operator.GT))
              .then(actions -> actions.emit("over", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS);
    }
  }

  @Nested
  @DisplayName("the right-hand side")
  class RightHandSide {

    @Test
    @DisplayName("insert, retract and a derived fact match the hand-built actions")
    void everyVerb() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: derive
                  when:
                    - fact: Order
                      as: o
                      where:
                        total: { gt: 10000 }
                  then:
                    - action: insertFact
                      fact: RiskSignal
                      as: sig
                      payload:
                        orderId:  { $ref: o.id }
                        severity: "HIGH"
                    - action: emit
                      event: derived
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("derive")
              .when("o", "Order", pattern -> pattern.gt("total", 10000))
              .then(actions -> actions
                  .insertFactAs("RiskSignal", "sig",
                      "orderId", Rules.ref("o.id"),
                      "severity", "HIGH")
                  .emit("derived", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS);
    }
  }

  @Nested
  @DisplayName("several rules in one file")
  class ManyRules {

    @Test
    @DisplayName("keep their declaration order, which conflict resolution must not depend on")
    void severalRules() {
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: low-priority
                  salience: 1
                  when: [{ fact: Order, as: o, where: { status: { eq: "PENDING" } } }]
                  then: [{ action: emit, event: low, payload: { id: { $ref: o.id } } }]
                - id: high-priority
                  salience: 100
                  when: [{ fact: Order, as: o, where: { status: { eq: "PENDING" } } }]
                  then: [{ action: emit, event: high, payload: { id: { $ref: o.id } } }]
              """),
          List.of(
              Rules.rule("low-priority").salience(1)
                  .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
                  .then(actions -> actions.emit("low", "id", Rules.ref("o.id")))
                  .build(),
              Rules.rule("high-priority").salience(100)
                  .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
                  .then(actions -> actions.emit("high", "id", Rules.ref("o.id")))
                  .build()),
          ORDERS);

      assertThat(fired.steps()).extracting(FiringSequence.Step::ruleId)
          .startsWith("high-priority");
    }
  }

  @Nested
  @DisplayName("literal canonicalisation")
  class Literals {

    @Test
    @DisplayName("a decimal from YAML matches the builder given the same JSON number type")
    void numericLiteral() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: exact-total
                  when: [{ fact: Order, as: o, where: { total: { eq: 25000.0 } } }]
                  then: [{ action: emit, event: exact }]
              """),
          List.of(Rules.rule("exact-total")
              .when("o", "Order", pattern -> pattern.eq("total", 25_000.0d))
              .then(actions -> actions.emit("exact"))
              .build()),
          ORDERS);
    }

    @Test
    @DisplayName("but two spellings of one number are NOT one constraint, which §2.6.2 arguably wants")
    void numericNodeTypeIsNotCanonicalisedInConstraints() {
      /*
       * A known limitation, pinned rather than hidden. §2.6.2 canonicalises numerics for hashing,
       * ordering and indexing -- and Comparisons does exactly that, so MATCHING is correct: a rule
       * written `eq: 25000` matches a fact holding 25000.0. What is not canonicalised is the
       * literal stored in the constraint, so the two spellings are unequal records and
       * NetworkBuilder builds two alpha nodes where one would do.
       *
       * That is a sharing inefficiency, not a wrong answer, which is why it is recorded here rather
       * than fixed under a DSL change: canonicalising FieldConstraint's literal would alter every
       * existing rule set's §5.6 version and change what literal() hands back. It is also why
       * DslEquivalence compares definitions and not only the version hash -- these two constraints
       * digest identically, because canonicalise() renders with toString().
       */
      final var fromDsl = com.codeheadsystems.rules.dsl.RuleFiles.parse(
          com.codeheadsystems.rules.dsl.RuleSource.yaml("n.yaml", file("""
                - id: n
                  when: [{ fact: Order, as: o, where: { total: { eq: 25000.0 } } }]
                  then: [{ action: emit, event: e }]
              """)));
      final var fromJava = List.of(Rules.rule("n")
          .when("o", "Order", pattern ->
              pattern.eq("total", new java.math.BigDecimal("25000.0")))
          .then(actions -> actions.emit("e"))
          .build());

      assertThat(fromDsl).isNotEqualTo(fromJava);
      assertThat(com.codeheadsystems.rules.compiler.RuleCompiler.compile(fromDsl).version())
          .as("the version hash cannot tell them apart, which is why it is the weaker check")
          .isEqualTo(com.codeheadsystems.rules.compiler.RuleCompiler.compile(fromJava).version());
    }

    @Test
    @DisplayName("an explicit null equals the builder's, which §2.6.1 keeps distinct from absent")
    void explicitNull() {
      final List<RuleDefinition> handBuilt = List.of(Rules.rule("closed-at-null")
          .when("o", "Order", pattern -> pattern.isNull("closedAt", true))
          .then(actions -> actions.emit("open"))
          .build());

      DslEquivalence.assertEquivalent(
          file("""
                - id: closed-at-null
                  when: [{ fact: Order, as: o, where: { closedAt: { isNull: true } } }]
                  then: [{ action: emit, event: open }]
              """),
          handBuilt, ORDERS);
    }
  }

  @Nested
  @DisplayName("a negated pattern")
  class Negation {

    /** Order 1 has no payment; order 2 has one. */
    private static final Consumer<RuleSession> ORDERS_AND_PAYMENTS = session -> {
      session.insert("Order", Facts.json("""
          {"id": 1, "status": "PENDING", "customerId": 7}"""));
      session.insert("Order", Facts.json("""
          {"id": 2, "status": "PENDING", "customerId": 8}"""));
      session.insert("Payment", Facts.json("""
          {"orderId": 2, "amount": 50}"""));
    };

    @Test
    @DisplayName("joined to a bound alias matches the builder's notExists")
    void unpaidOrder() {
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: unpaid-order
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "PENDING" }
                    - fact: Payment
                      as: p
                      quantifier: notExists
                      where:
                        orderId: { eq: { $ref: o.id } }
                  then:
                    - action: emit
                      event: unpaid
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("unpaid-order")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.emit("unpaid", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS_AND_PAYMENTS);

      assertThat(fired.steps())
          .as("the unpaid order fires and the paid one does not")
          .hasSize(1);
    }

    @Test
    @DisplayName("a temporal window survives the round trip, carried on §6.2.3's $ref extension")
    void temporalWindow() {
      /*
       * `within` is the first thing §6.2.3's reserved `{ $ref: …, extension: … }` shape actually
       * carries. The hash half is what catches a front end that read the $ref and dropped the
       * bound: JoinConstraint renders the window through its record toString, so an unbounded
       * "after" and a 24-hour one cannot hash alike. The firing half catches the direction.
       */
      final long day = 24L * 60 * 60 * 1000;
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: quick-payment
                  when:
                    - fact: Order
                      as: o
                    - fact: Payment
                      as: p
                      where:
                        orderId: { eq: { $ref: o.id } }
                        paidAt: { after: { $ref: o.placedAt, within: 86400000 } }
                  then:
                    - action: emit
                      event: order.paid.quickly
              """),
          List.of(Rules.rule("quick-payment")
              .when("o", "Order")
              .when("p", "Payment", pattern -> pattern
                  .ref("orderId", "o.id")
                  .after("paidAt", "o.placedAt", day))
              .then(actions -> actions.emit("order.paid.quickly"))
              .build()),
          session -> {
            session.insert("Order", Facts.json("""
                {"id": 1, "placedAt": 1000000}"""));
            session.insert("Payment", Facts.json("""
                {"orderId": 1, "paidAt": 1500000}"""));
            session.insert("Order", Facts.json("""
                {"id": 2, "placedAt": 1000000}"""));
            session.insert("Payment", Facts.json("""
                {"orderId": 2, "paidAt": 99000000}"""));
          });

      assertThat(fired.steps())
          .as("order 1 was paid inside the day; order 2 far outside it")
          .hasSize(1);
    }

    @Test
    @DisplayName("and so does before, which is a separate switch arm and a separate mistake")
    void temporalWindowBefore() {
      /*
       * `after` and `before` are two lines of one switch in OperatorMaps, and swapping one for the
       * other is the classic copy-paste in that shape. It survived the entire suite: nothing else
       * in the tree writes `before:` in a rule file, so the DSL half of that arm was unreached.
       */
      final long day = 24L * 60 * 60 * 1000;
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: cancelled-late
                  when:
                    - fact: Payment
                      as: p
                    - fact: Cancellation
                      as: c
                      where:
                        orderId: { eq: { $ref: p.orderId } }
                        at: { before: { $ref: p.paidAt, within: 86400000 } }
                  then:
                    - action: emit
                      event: cancelled.before.payment
              """),
          List.of(Rules.rule("cancelled-late")
              .when("p", "Payment")
              .when("c", "Cancellation", pattern -> pattern
                  .ref("orderId", "p.orderId")
                  .before("at", "p.paidAt", day))
              .then(actions -> actions.emit("cancelled.before.payment"))
              .build()),
          session -> {
            session.insert("Payment", Facts.json("""
                {"orderId": 1, "paidAt": 90000000}"""));
            session.insert("Cancellation", Facts.json("""
                {"orderId": 1, "at": 89000000}"""));
          });

      assertThat(fired.steps())
          .as("cancelled shortly before the payment -- which `after` would not match")
          .hasSize(1);
    }

    @Test
    @DisplayName("an accumulate's function, field, scope and having all survive the round trip")
    void accumulate() {
      /*
       * Four things could be dropped independently here -- the function, the field it folds, the
       * scope's constraints and the having -- and the version hash catches every one, because all
       * four reach it through PatternDefinition's record toString. The firing half catches the one
       * the hash cannot: that the bound VALUE is the same number on both sides, which is a
       * statement about Accumulators rather than about the front end.
       */
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: bulk-order
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "OPEN" }
                    - fact: LineItem
                      as: units
                      quantifier: accumulate
                      accumulate:
                        sum: "qty"
                        having: { gt: 100 }
                      where:
                        orderId: { eq: { $ref: o.id } }
                  then:
                    - action: emit
                      event: order.bulk
                      payload: { units: { $ref: units } }
              """),
          List.of(Rules.rule("bulk-order")
              .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
              .accumulate("units", "LineItem",
                  Rules.fold(AggregateFunction.SUM, "qty", Operator.GT, 100),
                  pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.emit("order.bulk", "units", Rules.ref("units")))
              .build()),
          session -> {
            session.insert("Order", Facts.json("""
                {"id": 1, "status": "OPEN"}"""));
            session.insert("LineItem", Facts.json("""
                {"orderId": 1, "qty": 70}"""));
            session.insert("LineItem", Facts.json("""
                {"orderId": 1, "qty": 80}"""));
          });

      assertThat(fired.steps()).as("150 units, over the threshold").hasSize(1);
      assertThat(fired.steps().getFirst().emitted().getFirst())
          .as("and the bound value is the fold, not a placeholder -- identical on both sides,"
              + " which the version hash cannot check because it is about Accumulators")
          .contains("\"units\":150");
    }

    @Test
    @DisplayName("a logical insert survives the round trip and is still withdrawn")
    void logicalInsert() {
      /*
       * The hash half of this oracle is the one doing the work here, and it is worth saying why the
       * sequence half cannot. `logical` changes what happens AFTER a firing, not what fires, so two
       * rules differing only in that flag produce identical firing sequences -- Engine.run fires
       * once at the end of the scenario, so there is no second cycle in which a withdrawal could
       * show up. What the hash catches is a front end that dropped the key: InsertFact is a record,
       * so `logical` reaches the version hash through its toString, and a dropped key would make a
       * stated conclusion and a revocable one hash identically. The withdrawal itself is
       * TruthMaintenanceTest's job, including from a rule file.
       */
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: unpaid-order
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "PENDING" }
                    - fact: Payment
                      as: p
                      quantifier: notExists
                      where:
                        orderId: { eq: { $ref: o.id } }
                  then:
                    - action: insertFact
                      fact: OrderUnpaid
                      logical: true
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("unpaid-order")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.insertLogical("OrderUnpaid", "orderId", Rules.ref("o.id")))
              .build()),
          session -> session.insert("Order", Facts.json("""
              {"id": 1, "status": "PENDING"}""")));

      assertThat(fired.steps())
          .as("both sides conclude, once")
          .hasSize(1);
    }

    @Test
    @DisplayName("a forAll pattern's scope and requirement survive the round trip")
    void readyToShip() {
      /*
       * The hash half of this oracle is the strong one, and for FOR_ALL it is checking something
       * specific: that the rule file's joins and constraints land in the same halves the builder
       * puts them in. A front end that conjoined them would produce a rule asserting every LineItem
       * anywhere belongs to this order -- a different rule, with the same text.
       */
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: ready-to-ship
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "PENDING" }
                    - fact: LineItem
                      as: li
                      quantifier: forAll
                      where:
                        orderId: { eq: { $ref: o.id } }
                        inStock: { eq: true }
                  then:
                    - action: emit
                      event: ready
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("ready-to-ship")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .forAll("li", "LineItem", pattern -> pattern
                  .ref("orderId", "o.id")
                  .eq("inStock", true))
              .then(actions -> actions.emit("ready", "orderId", Rules.ref("o.id")))
              .build()),
          session -> {
            session.insert("Order", Facts.json("""
                {"id": 1, "status": "PENDING"}"""));
            session.insert("LineItem", Facts.json("""
                {"orderId": 1, "inStock": true}"""));
            session.insert("Order", Facts.json("""
                {"id": 2, "status": "PENDING"}"""));
            session.insert("LineItem", Facts.json("""
                {"orderId": 2, "inStock": false}"""));
          });

      assertThat(fired.steps())
          .as("order 1 is ready; order 2's out-of-stock item is out of scope for it")
          .hasSize(1);
    }

    @Test
    @DisplayName("of a type the rule already binds carries §1's implicit inequality across too")
    void sameTypeNegation() {
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: only-order-for-customer
                  when:
                    - fact: Order
                      as: o
                    - fact: Order
                      as: other
                      quantifier: notExists
                      where:
                        customerId: { eq: { $ref: o.customerId } }
                  then:
                    - action: emit
                      event: sole
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("only-order-for-customer")
              .when("o", "Order")
              .notExists("other", "Order", pattern -> pattern.ref("customerId", "o.customerId"))
              .then(actions -> actions.emit("sole", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS_AND_PAYMENTS);

      assertThat(fired.steps())
          .as("both orders are the only one for their own customer; neither counts as its own"
              + " counterexample")
          .hasSize(2);
    }

    @Test
    @DisplayName("declared before the alias it joins to, which the reference says is legal")
    void negationDeclaredFirst() {
      /*
       * The reference claims a negated pattern "could equally be written before the Order it
       * references", which is true because the compiler assigns positions to positive patterns
       * only and compiles negations afterwards, against all of them. A claim in a doc that no
       * fixture exercises is a claim waiting to stop being true.
       */
      final FiringSequence fired = DslEquivalence.assertEquivalent(
          file("""
                - id: unpaid-order-negation-first
                  when:
                    - fact: Payment
                      as: p
                      quantifier: notExists
                      where:
                        orderId: { eq: { $ref: o.id } }
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "PENDING" }
                  then:
                    - action: emit
                      event: unpaid
                      payload: { orderId: { $ref: o.id } }
              """),
          List.of(Rules.rule("unpaid-order-negation-first")
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .then(actions -> actions.emit("unpaid", "orderId", Rules.ref("o.id")))
              .build()),
          ORDERS_AND_PAYMENTS);

      assertThat(fired.steps())
          .as("the same one firing the same rule produces with the patterns the other way round")
          .hasSize(1);
    }

    @Test
    @DisplayName("written as an explicit 'exists' is the default, and the default is unchanged")
    void explicitExistsIsTheDefault() {
      DslEquivalence.assertEquivalent(
          file("""
                - id: pending
                  when:
                    - fact: Order
                      as: o
                      quantifier: exists
                      where:
                        status: { eq: "PENDING" }
                  then:
                    - action: emit
                      event: pending
              """),
          List.of(Rules.rule("pending")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .then(actions -> actions.emit("pending"))
              .build()),
          ORDERS_AND_PAYMENTS);
    }
  }
}
