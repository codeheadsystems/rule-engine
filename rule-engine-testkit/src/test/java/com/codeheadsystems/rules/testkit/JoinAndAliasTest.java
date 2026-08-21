package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Multi-fact matching, and the one modelling question spec section 1 says v1 must answer
 * explicitly.
 *
 * <p>That question: when a rule has two patterns of the same fact type, may one fact bind both
 * aliases? <strong>No.</strong> Distinct aliases in one rule bind distinct facts, and the compiler
 * inserts an implicit inequality between same-type aliases. This matches what rule authors expect
 * ("find two <em>different</em> orders"), differs from OPS5, and must be pinned by a test because
 * the other reading is defensible and silently produces self-matches.
 */
class JoinAndAliasTest {

  @Test
  @DisplayName("a join matches only the facts that satisfy it")
  void joinsMatchOnTheJoinKey() {
    final RuleDefinition rule = Rules.rule("high-value-order-review")
        .when("o", "Order", pattern -> pattern.gt("total", 10000).eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId")
            .in("riskTier", "HIGH", "MEDIUM"))
        .then(actions -> actions.emit("order.flagged",
            "orderId", Rules.ref("o.id"), "tier", Rules.ref("c.riskTier")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          session.insert("Order",
              Facts.obj("id", 1, "total", 25000, "status", "PENDING", "customerId", 7));
          session.insert("Order",
              Facts.obj("id", 2, "total", 25000, "status", "PENDING", "customerId", 8));
          session.insert("Customer", Facts.obj("id", 7, "riskTier", "HIGH"));
          session.insert("Customer", Facts.obj("id", 8, "riskTier", "LOW"));
          session.insert("Customer", Facts.obj("id", 9, "riskTier", "HIGH"));
        });

    assertThat(result.emitted())
        .extracting(event -> event.payload().get("orderId").intValue())
        .containsExactly(1);
  }

  @Test
  @DisplayName("a join reads through the handle, so it sees the CURRENT payload")
  void joinsReadThroughTheHandle() {
    // Invariant 3: tuples bind handles, never Fact objects. If a tuple held a snapshot, an update
    // to a field the emit reads but no rule tests would send the old value -- and no test that
    // asserts only on matching would ever catch it.
    final RuleDefinition rule = Rules.rule("notify")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("notify", "email", Rules.ref("o.customerEmail")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          final var handle = session.insert("Order",
              Facts.obj("status", "PENDING", "customerEmail", "old@example.com"));
          // customerEmail is not a tested path, so this propagates nothing at all.
          session.update(handle, Facts.obj("status", "PENDING", "customerEmail",
              "new@example.com"));
        });

    assertThat(result.emitted()).singleElement()
        .extracting(event -> event.payload().get("email").stringValue())
        .isEqualTo("new@example.com");
  }

  @Test
  @DisplayName("two same-type aliases bind two DIFFERENT facts, never one fact twice")
  void sameTypeAliasesAreDistinct() {
    final RuleDefinition rule = Rules.rule("duplicate-orders")
        .when("o1", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("o2", "Order", pattern -> pattern.eq("status", "PENDING")
            .ref("customerId", "o1.customerId"))
        .then(actions -> actions.emit("duplicate",
            "first", Rules.ref("o1.id"), "second", Rules.ref("o2.id")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
          session.insert("Order", Facts.obj("id", 2, "status", "PENDING", "customerId", 7));
        });

    final List<String> pairs = result.emitted().stream()
        .map(event -> event.payload().get("first").intValue()
            + "-" + event.payload().get("second").intValue())
        .toList();

    // Both orderings of the two distinct orders, and no self-match. Without the implicit
    // inequality this would also report 1-1 and 2-2.
    assertThat(pairs).containsExactlyInAnyOrder("1-2", "2-1");
  }

  @Test
  @DisplayName("a single fact still matches a single-pattern rule, obviously")
  void oneAliasBindsOneFact() {
    final RuleDefinition rule = Rules.rule("single")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
        .build();

    assertThat(Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> session.insert("Order", Facts.obj("id", 1, "status", "PENDING")))
        .firedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a three-pattern join chains correctly")
  void threeWayJoin() {
    final RuleDefinition rule = Rules.rule("order-item-customer")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("i", "LineItem", pattern -> pattern.ref("orderId", "o.id").gt("qty", 10))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("bulk",
            "orderId", Rules.ref("o.id"), "sku", Rules.ref("i.sku")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING", "customerId", 7));
          // The flattening section 1 tells you to design your ingestion around: an Order with an
          // items[] array becomes one Order fact plus N LineItem facts carrying orderId.
          session.insert("LineItem", Facts.obj("orderId", 1, "sku", "A", "qty", 5));
          session.insert("LineItem", Facts.obj("orderId", 1, "sku", "B", "qty", 50));
          session.insert("LineItem", Facts.obj("orderId", 2, "sku", "C", "qty", 50));
          session.insert("Customer", Facts.obj("id", 7));
        });

    assertThat(result.emitted()).singleElement()
        .extracting(event -> event.payload().get("sku").stringValue())
        .isEqualTo("B");
  }

  @Test
  @DisplayName("a non-equality join operator works")
  void inequalityJoin() {
    final RuleDefinition rule = Rules.rule("cheaper-alternative")
        .when("a", "Quote", pattern -> pattern.eq("sku", "WIDGET"))
        .when("b", "Quote", pattern -> pattern.eq("sku", "WIDGET")
            .ref("price", "a.price", Operator.LT))
        .then(actions -> actions.emit("cheaper",
            "winner", Rules.ref("b.vendor"), "loser", Rules.ref("a.vendor")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          session.insert("Quote", Facts.obj("sku", "WIDGET", "vendor", "acme", "price", 100));
          session.insert("Quote", Facts.obj("sku", "WIDGET", "vendor", "globex", "price", 80));
        });

    assertThat(result.emitted()).singleElement()
        .extracting(event -> event.payload().get("winner").stringValue())
        .isEqualTo("globex");
  }
}
