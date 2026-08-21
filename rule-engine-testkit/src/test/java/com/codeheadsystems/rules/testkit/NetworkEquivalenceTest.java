package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 1's central claim: the network matcher and the naive oracle agree, always.
 *
 * <p>Spec §9's exit criterion for every phase after Phase 0 is results identical to the oracle, and
 * §11.5 explains why it is non-negotiable rather than nice to have. This suite is that criterion,
 * exercised across the shapes most likely to break an incrementally-maintained network: retraction,
 * update churn, joins whose keys move, shared constraints, and unindexable operators.
 */
class NetworkEquivalenceTest {

  /**
   * A rule set with heavy constraint sharing, so node sharing is genuinely exercised.
   *
   * <p>Four of these rules test {@code status == "PENDING"}. That must compile to one alpha node
   * evaluated once per order, and every one of the four must still fire.
   */
  private static List<RuleDefinition> corpus() {
    return List.of(
        Rules.rule("flag-high-value").salience(10)
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 10_000))
            .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
            .build(),
        Rules.rule("greet-risky")
            .when("c", "Customer", pattern -> pattern.in("riskTier", "HIGH", "MEDIUM"))
            .then(actions -> actions.emit("greeted", "id", Rules.ref("c.id")))
            .build(),
        Rules.rule("review-risky-order")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
                .eq("riskTier", "HIGH"))
            .then(actions -> actions.emit("review",
                "orderId", Rules.ref("o.id"), "customerId", Rules.ref("c.id")))
            .build(),
        Rules.rule("bulk-line-item")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("i", "LineItem", pattern -> pattern.ref("orderId", "o.id").gt("qty", 10))
            .then(actions -> actions.emit("bulk", "sku", Rules.ref("i.sku")))
            .build(),
        Rules.rule("unindexable-operators")
            .when("o", "Order", pattern -> pattern
                .eq("status", "PENDING")
                .ne("region", "XX")
                .matches("code", "^A"))
            .then(actions -> actions.emit("odd", "id", Rules.ref("o.id")))
            .build(),
        Rules.rule("duplicate-orders")
            .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
                .ref("customerId", "o1.customerId"))
            .then(actions -> actions.emit("dupe",
                "a", Rules.ref("o1.id"), "b", Rules.ref("o2.id")))
            .build(),
        Rules.rule("cheaper-quote")
            .when("a", "Quote", pattern -> pattern.eq("sku", "WIDGET"))
            .when("b", "Quote", pattern -> pattern.eq("sku", "WIDGET")
                .ref("price", "a.price", Operator.LT))
            .then(actions -> actions.emit("cheaper", "vendor", Rules.ref("b.vendor")))
            .build(),
        Rules.rule("audit-everything").salience(-5)
            .when("o", "Order")
            .then(actions -> actions.emit("audited", "id", Rules.ref("o.id")))
            .build());
  }

  @Test
  @DisplayName("plain inserts")
  void inserts() {
    assertThat(MatcherEquivalence.assertEquivalent(corpus(), populate()).steps())
        .describedAs("the corpus must actually fire something for the comparison to mean anything")
        .hasSizeGreaterThan(10);
  }

  @Test
  @DisplayName("retraction, which is where an incrementally-maintained memory goes stale")
  void retraction() {
    MatcherEquivalence.assertEquivalent(corpus(), session -> {
      populate().accept(session);
      session.fireAllRules();
      session.workingMemory().factsOfType("Order").toList()
          .forEach(fact -> {
            if (fact.payload().path("id").intValue() % 2 == 0) {
              session.retract(fact.handle());
            }
          });
    });
  }

  @Test
  @DisplayName("an update that moves a join key, which must move the index entry with it")
  void joinKeyChurn() {
    // The sharpest case for an index: /customerId is what review-risky-order joins on, so an
    // update that changes it must remove the handle from its old bucket. A network that recomputed
    // removal keys from the NEW payload would leave the old entry behind and produce a phantom
    // match against a customer the order no longer belongs to.
    MatcherEquivalence.assertEquivalent(corpus(), session -> {
      populate().accept(session);
      session.fireAllRules();
      session.workingMemory().factsOfType("Order").toList().forEach(fact -> {
        final var moved = Facts.obj(
            "id", fact.payload().path("id").intValue(),
            "status", "PENDING",
            "total", 25_000,
            "customerId", (fact.payload().path("customerId").intValue() + 1) % 4,
            "region", "US",
            "code", "A1");
        session.update(fact.handle(), moved);
      });
    });
  }

  @Test
  @DisplayName("an update that stops a fact matching, so it must leave the pattern memory")
  void factLeavesTheMemory() {
    MatcherEquivalence.assertEquivalent(corpus(), session -> {
      populate().accept(session);
      session.fireAllRules();
      session.workingMemory().factsOfType("Order").toList().forEach(fact ->
          session.update(fact.handle(), Facts.obj(
              "id", fact.payload().path("id").intValue(), "status", "SHIPPED")));
    });
  }

  @Test
  @DisplayName("interleaved insert, update and retract, driven by a seeded random walk")
  void randomWalk() {
    // A fixed seed: a differential test that is itself non-deterministic reports a different walk
    // every time it fails, which makes the failure much harder to act on.
    for (int seed = 0; seed < 8; seed++) {
      final int run = seed;
      MatcherEquivalence.assertEquivalent(corpus(), session -> walk(session, new Random(run)));
    }
  }

  @Test
  @DisplayName("a heavily asymmetric join, where the planner reverses the written order")
  void asymmetricJoinIsReordered() {
    // The case the join planner exists for: written "for each pending order, find its customer",
    // but with two thousand orders and three customers the cheap direction is the other one. The
    // planner reverses it, and the answer must not move.
    final List<RuleDefinition> rules = List.of(
        Rules.rule("review-risky-order")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
                .eq("riskTier", "HIGH"))
            .then(actions -> actions.emit("review",
                "orderId", Rules.ref("o.id"), "customerId", Rules.ref("c.id")))
            .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      for (int customer = 0; customer < 3; customer++) {
        session.insert("Customer", Facts.obj(
            "id", customer, "riskTier", customer == 0 ? "HIGH" : "LOW"));
      }
      for (int order = 0; order < 2_000; order++) {
        session.insert("Order", Facts.obj(
            "id", order, "status", "PENDING", "customerId", order % 3));
      }
    });
  }

  @Test
  @DisplayName("the asymmetry reversed, so the planner has to change its mind")
  void asymmetricJoinTheOtherWay() {
    final List<RuleDefinition> rules = List.of(
        Rules.rule("review-risky-order")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
            .then(actions -> actions.emit("review", "orderId", Rules.ref("o.id")))
            .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      for (int order = 0; order < 3; order++) {
        session.insert("Order", Facts.obj("id", order, "status", "PENDING", "customerId", order));
      }
      for (int customer = 0; customer < 2_000; customer++) {
        session.insert("Customer", Facts.obj("id", customer));
      }
    });
  }

  @Test
  @DisplayName("a self-join the planner reorders still forbids one fact binding both aliases")
  void selfJoinSurvivesReordering() {
    // §1's implicit inequality is recorded by the compiler on the LATER pattern only, pointing at
    // earlier positions, because that is what a left-to-right matcher needs. A plan that binds the
    // later pattern first would then enforce it zero times, and one order binds both aliases.
    //
    // No size asymmetry is needed to provoke it: o1 is disconnected and o2 is connected, so
    // connectivity alone reorders them.
    final List<RuleDefinition> rules = List.of(Rules.rule("pair")
        .when("c", "Customer", pattern -> pattern.hasField("id", true))
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "c.id"))
        .then(actions -> actions.emit("pair",
            "a", Rules.ref("o1.id"), "b", Rules.ref("o2.id")))
        .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      session.insert("Customer", Facts.obj("id", 7));
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING", "customerId", 7));
    });
  }

  @Test
  @DisplayName("a self-join reordered by size, which is the other way in")
  void selfJoinReorderedBySize() {
    final List<RuleDefinition> rules = List.of(Rules.rule("dupe")
        .when("o1", "Order", pattern -> pattern.gt("total", 0))
        .when("o2", "Order", pattern -> pattern.gt("total", 100)
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("dupe",
            "a", Rules.ref("o1.id"), "b", Rules.ref("o2.id")))
        .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      for (int order = 0; order < 5; order++) {
        session.insert("Order", Facts.obj(
            "id", order, "total", order == 4 ? 500 : 10, "customerId", 7));
      }
    });
  }

  @Test
  @DisplayName("a join key that is an explicit null still matches, because null equals null")
  void nullValuedJoinKey() {
    // A null has no canonical hash key, so a fact holding one was never filed in the hash index.
    // A probe that reported "index applied, zero candidates" rather than "no index usable" would
    // lose the match silently -- and §2.6.1 is explicit that an explicit null equals an explicit
    // null.
    final List<RuleDefinition> rules = List.of(Rules.rule("null-join")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.ref("k", "a.k"))
        .then(actions -> actions.emit("hit"))
        .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      session.insert("A", Facts.obj("k", (Object) null));
      session.insert("B", Facts.obj("k", (Object) null));
      session.insert("A", Facts.obj("k", "present"));
      session.insert("B", Facts.obj("k", "present"));
    });
  }

  @Test
  @DisplayName("a join key that is an object or an array still matches structurally")
  void containerValuedJoinKey() {
    final List<RuleDefinition> rules = List.of(Rules.rule("object-join")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.ref("k", "a.k"))
        .then(actions -> actions.emit("hit"))
        .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      session.insert("A", Facts.obj("k", Facts.obj("x", 1)));
      session.insert("B", Facts.obj("k", Facts.obj("x", 1)));
      session.insert("B", Facts.obj("k", Facts.obj("x", 2)));
      session.insert("A", Facts.obj("k", Facts.array(1, 2)));
      session.insert("B", Facts.obj("k", Facts.array(1, 2)));
    });
  }

  @Test
  @DisplayName("a range join probed from the reversed end")
  void reversedRangeProbe() {
    // The only path that exercises Operator.reversed() for the four range operators. It needs the
    // constraint-bearing pattern to be bound FIRST, so that the referenced side is the one probed
    // and its operator has to be read backwards. Equal memory sizes never produce that, which is
    // why the original corpus -- whose only range join has two patterns with identical alpha tests
    // -- left this entirely uncovered.
    final List<RuleDefinition> rules = List.of(Rules.rule("cheaper")
        .when("a", "Quote", pattern -> pattern.eq("sku", "A"))
        .when("b", "Quote", pattern -> pattern.eq("sku", "B")
            .ref("price", "a.price", Operator.LT))
        .then(actions -> actions.emit("cheaper",
            "a", Rules.ref("a.vendor"), "b", Rules.ref("b.vendor")))
        .build());

    MatcherEquivalence.assertEquivalent(rules, session -> {
      for (int price = 1; price <= 10; price++) {
        session.insert("Quote", Facts.obj("sku", "A", "vendor", "a" + price, "price", price));
      }
      session.insert("Quote", Facts.obj("sku", "B", "vendor", "b1", "price", 5));
    });
  }

  @Test
  @DisplayName("three same-type aliases: every pair distinct, under any binding order")
  void threeWaySelfJoin() {
    // The inequality is recorded on the later pattern only, so o3 names {o1, o2}, o2 names {o1},
    // and o1 names nothing. Symmetrising has to produce exactly one check per PAIR -- not zero when
    // the plan reverses a pair, and not two when it happens to bind them in written order.
    //
    // Three interchangeable orders should give 3! = 6 ordered matches. Four would be a missing
    // check; three would be a double-count eliminating good matches.
    final List<RuleDefinition> rules = List.of(Rules.rule("triple")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .when("o3", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("triple",
            "a", Rules.ref("o1.id"), "b", Rules.ref("o2.id"), "c", Rules.ref("o3.id")))
        .build());

    final FiringSequence sequence = MatcherEquivalence.assertEquivalent(rules, session -> {
      for (int order = 0; order < 3; order++) {
        session.insert("Order", Facts.obj("id", order, "status", "PENDING", "customerId", 7));
      }
    });
    assertThat(sequence.steps())
        .describedAs("three distinct orders in three ordered slots")
        .hasSize(6);
    assertThat(sequence.steps())
        .allSatisfy(step -> assertThat(step.handles()).doesNotHaveDuplicates());
  }

  @Test
  @DisplayName("same-type aliases with mixed connectivity, so the plan reorders them")
  void selfJoinWithMixedConnectivity() {
    // o2 is connected to the customer, o1 and o3 are not, so the plan is free to interleave them
    // in an order that bears no relation to how they were written.
    final List<RuleDefinition> rules = List.of(Rules.rule("mixed-triple")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.hasField("id", true))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "c.id"))
        .when("o3", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("mixed",
            "a", Rules.ref("o1.id"), "b", Rules.ref("o2.id"), "c", Rules.ref("o3.id")))
        .build());

    final FiringSequence sequence = MatcherEquivalence.assertEquivalent(rules, session -> {
      session.insert("Customer", Facts.obj("id", 7));
      for (int order = 0; order < 3; order++) {
        session.insert("Order", Facts.obj("id", order, "status", "PENDING", "customerId", 7));
      }
    });
    assertThat(sequence.steps()).hasSize(6);
    assertThat(sequence.steps()).allSatisfy(step -> {
      // The customer handle appears alongside three distinct order handles.
      assertThat(step.handles()).hasSize(4).doesNotHaveDuplicates();
    });
  }

  @Test
  @DisplayName("rules whose RHS mutates working memory, so the network churns mid-fire")
  void mutatingRules() {
    final List<RuleDefinition> mutating = List.of(
        Rules.rule("promote")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 100))
            .then(actions -> actions
                .setField("o", "status", "REVIEW")
                .insertFact("RiskSignal", "orderId", Rules.ref("o.id")))
            .build(),
        Rules.rule("observe-review")
            .when("o", "Order", pattern -> pattern.eq("status", "REVIEW"))
            .when("s", "RiskSignal", pattern -> pattern.ref("orderId", "o.id"))
            .then(actions -> actions.emit("reviewed", "id", Rules.ref("o.id")))
            .build(),
        Rules.rule("clean-up").salience(-10)
            .when("s", "RiskSignal", pattern -> pattern.hasField("orderId", true))
            .then(actions -> actions.retractFact("s"))
            .build());

    MatcherEquivalence.assertEquivalent(mutating, session -> {
      for (int order = 0; order < 6; order++) {
        session.insert("Order",
            Facts.obj("id", order, "status", "PENDING", "total", 500 + order));
      }
    });
  }

  @Test
  @DisplayName("equivalence holds under strict mode and under a dry run")
  void otherSessionShapes() {
    MatcherEquivalence.assertEquivalent(corpus(), populate(),
        SessionOptions.builder().strict(true));
    MatcherEquivalence.assertEquivalent(corpus(), populate(),
        SessionOptions.builder().dryRun(true));
  }

  /**
   * Inserts a small population across every fact type the corpus patterns.
   *
   * @return the scenario
   */
  private static Consumer<RuleSession> populate() {
    return session -> {
      for (int customer = 0; customer < 4; customer++) {
        session.insert("Customer", Facts.obj(
            "id", customer, "riskTier", customer % 2 == 0 ? "HIGH" : "LOW"));
      }
      for (int order = 0; order < 6; order++) {
        session.insert("Order", Facts.obj(
            "id", order,
            "status", order % 3 == 0 ? "SHIPPED" : "PENDING",
            "total", 1_000 * (order + 1) * 4,
            "customerId", order % 4,
            "region", order == 2 ? "XX" : "US",
            "code", order % 2 == 0 ? "A1" : "B2"));
      }
      for (int item = 0; item < 5; item++) {
        session.insert("LineItem", Facts.obj(
            "orderId", item % 3, "sku", "SKU-" + item, "qty", item * 6));
      }
      for (int quote = 0; quote < 4; quote++) {
        session.insert("Quote", Facts.obj(
            "sku", "WIDGET", "vendor", "v" + quote, "price", 100 - quote * 7));
      }
    };
  }

  /**
   * A seeded interleaving of inserts, updates, retracts and fire calls.
   *
   * @param session the session to drive
   * @param random the seeded source
   */
  private static void walk(final RuleSession session, final Random random) {
    populate().accept(session);
    for (int step = 0; step < 40; step++) {
      final List<com.codeheadsystems.rules.fact.Fact> orders =
          session.workingMemory().factsOfType("Order").toList();
      switch (random.nextInt(4)) {
        case 0 -> session.insert("Order", Facts.obj(
            "id", 100 + step, "status", "PENDING", "total", random.nextInt(30_000),
            "customerId", random.nextInt(4), "region", "US", "code", "A" + step));
        case 1 -> {
          if (!orders.isEmpty()) {
            final var target = orders.get(random.nextInt(orders.size()));
            session.update(target.handle(), Facts.obj(
                "id", target.payload().path("id").intValue(),
                "status", random.nextBoolean() ? "PENDING" : "SHIPPED",
                "total", random.nextInt(30_000),
                "customerId", random.nextInt(4),
                "region", "US", "code", "A" + step));
          }
        }
        case 2 -> {
          if (!orders.isEmpty()) {
            session.retract(orders.get(random.nextInt(orders.size())).handle());
          }
        }
        default -> session.fireAllRules();
      }
    }
  }
}
