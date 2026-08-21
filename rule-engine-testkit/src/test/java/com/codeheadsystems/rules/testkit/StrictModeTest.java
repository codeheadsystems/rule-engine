package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict mode (spec section 7.5): the checks that are too expensive for production but catch a
 * contract violation deterministically in test.
 *
 * <p>Every one of them detects a violation of a contract the spec states but cannot enforce at
 * compile time. The build runs the whole suite twice -- once normally, once with
 * {@code -Drules.strict=true} via the {@code strictTest} task -- which is what section 7.5 asks for
 * and what keeps these from being three tests nobody else benefits from.
 */
class StrictModeTest {

  private static final RuleDefinition RULE = Rules.rule("alert")
      .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
      .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
      .build();

  private static SessionOptions strict() {
    return SessionOptions.builder().strict(true).build();
  }

  @Test
  @DisplayName("payload() hands out a copy, so a caller cannot mutate engine state behind its back")
  void payloadIsCopied() {
    try (RuleSession session = Engine.compile(RULE).newSession(strict())) {
      final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

      ((ObjectNode) session.get(handle).orElseThrow().payload()).put("status", "MUTATED");

      assertThat(session.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("PENDING");
      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("the get-mutate-update sequence is rejected rather than silently propagating nothing")
  void aliasingUpdateRejected() {
    // Reachable entirely through supported API, and fatal in two independent ways: the diff
    // compares an object against itself and finds nothing changed, and the retract half would
    // compute its index-removal keys from a payload that has already become the new one.
    try (RuleSession lenient = Engine.compile(RULE).newSession()) {
      final var handle = lenient.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      final var live = (ObjectNode) lenient.get(handle).orElseThrow().payload();

      try (RuleSession session = Engine.compile(RULE).newSession(strict())) {
        final var owned = session.insertOwned("Order", live);
        assertThatThrownBy(() -> session.update(owned, live))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shares a node");
      }
    }
  }

  @Test
  @DisplayName("a conflict-resolution strategy that is not a total order is rejected")
  void nonTotalOrderRejected() {
    // Without this, the conflict set would order zero-comparing activations by internal accident,
    // and the determinism contract would be gone -- silently, and only on some inputs.
    final ConflictResolutionStrategy alwaysTied = (left, right) -> 0;

    try (RuleSession session = Engine.compile(RULE).newSession(
        SessionOptions.builder().strict(true).conflictResolution(alwaysTied).build())) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

      assertThatThrownBy(session::fireAllRules)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not consistent with equality");
    }
  }

  @Test
  @DisplayName("a strategy that is inconsistent with equality the other way is rejected too")
  void inconsistentWithEqualsRejected() {
    // The other half of the contract: non-zero for equal activations would make the conflict set
    // and any key-indexed structure disagree about how many entries exist.
    final ConflictResolutionStrategy neverTied = (left, right) -> 1;

    try (RuleSession session = Engine.compile(RULE).newSession(
        SessionOptions.builder().strict(true).conflictResolution(neverTied).build())) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

      // Distinct activations comparing non-zero is perfectly fine, so walking pairs drawn from
      // the conflict set can never catch this. Comparing an activation against itself is what
      // does, which is why the strict check asserts reflexivity separately.
      assertThatThrownBy(session::fireAllRules)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("compared against itself");
    }
  }

  @Test
  @DisplayName("the default strategy satisfies both halves of the contract")
  void defaultStrategyIsWellBehaved() {
    try (RuleSession session = Engine.compile(RULE).newSession(strict())) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 3, "status", "PENDING"));

      assertThat(session.fireAllRules().firedCount()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("strict mode does not change what fires, only what is checked")
  void strictModeIsBehaviourPreserving() {
    // If strict mode changed outcomes, running the suite under it would test a different engine
    // than the one that ships.
    final var scenario = (java.util.function.Consumer<RuleSession>) session -> {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "SHIPPED"));
      session.insert("Order", Facts.obj("id", 3, "status", "PENDING"));
    };

    assertThat(Engine.run(Engine.compile(RULE), strict(), scenario))
        .isEqualTo(Engine.run(Engine.compile(RULE), SessionOptions.defaults(), scenario));
  }

  @Test
  @DisplayName("the strict flag defaults from the rules.strict system property")
  void strictDefaultsFromTheProperty() {
    // This is what makes one CI task re-run the existing suite with the checks on, rather than
    // every test having to opt in and one forgetting to.
    assertThat(SessionOptions.defaults().strict())
        .isEqualTo(Boolean.getBoolean(SessionOptions.STRICT_PROPERTY));
  }
}
