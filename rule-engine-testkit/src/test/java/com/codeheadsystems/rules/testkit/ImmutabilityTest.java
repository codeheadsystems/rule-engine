package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RangeTest;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Spec invariant 1: nothing in a {@code CompiledRuleSet} mutates after compile.
 *
 * <p>§5.5 rests the engine's scaling story on this — "thousands of concurrent virtual threads
 * reference the same {@code CompiledRuleSet} with zero contention, because nothing about it mutates
 * after compile" — and until Phase 4 nothing checked it.
 *
 * <p>It had a hole. Almost everything reachable is a record over strings, enums and primitives, but
 * the literals are {@code JsonNode}s: deep-copied on the way in, handed back live by the record
 * accessor. The first test below is that hole, and it is worth being precise about why it matters,
 * because "a caller could mutate it" understates the consequence twice over.
 */
class ImmutabilityTest {

  private static RuleDefinition tierRule() {
    return Rules.rule("tiers")
        .when("o", "Order", pattern -> pattern.in("tier", "GOLD"))
        .then(actions -> actions.emit("hit"))
        .build();
  }

  /** Reaches through the compiled rule set and extends an {@code IN} literal in place. */
  private static void mutateLiteral(final CompiledRuleSet rules, final String added) {
    final Constraint constraint =
        rules.rules().getFirst().source().when().getFirst().constraints().getFirst();
    ((ArrayNode) ((FieldConstraint) constraint).literal()).add(added);
  }

  private static int fireWith(final CompiledRuleSet rules, final String tier) {
    try (RuleSession session = rules.newSession(SessionOptions.builder().strict(false).build())) {
      session.insert("Order", Facts.obj("tier", tier));
      return session.fireAllRules().firedCount();
    }
  }

  @Nested
  @DisplayName("the hole this deliverable exists for")
  class TheHole {

    @Test
    @DisplayName("a mutated literal changes which facts match, and version() does not move")
    void mutationChangesMatchingSilently() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(tierRule()));
      final String before = rules.version();

      assertThat(fireWith(rules, "SILVER"))
          .as("SILVER is not in the literal, so nothing fires")
          .isZero();

      mutateLiteral(rules, "SILVER");

      assertThat(fireWith(rules, "SILVER"))
          .as("the same rule set now matches a fact it did not, with no recompile")
          .isEqualTo(1);
      assertThat(rules.version())
          .as("§5.6 stamps this into every trace and event to answer 'which rules produced this "
              + "decision'. It answers wrongly for every decision after the mutation.")
          .isEqualTo(before);
    }
  }

  @Nested
  @DisplayName("the strict-mode detector")
  class Detector {

    @Test
    @DisplayName("catches the mutation when the next session starts")
    void caught() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(tierRule()));
      mutateLiteral(rules, "SILVER");

      assertThatThrownBy(() -> rules.newSession(SessionOptions.builder().strict(true).build()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mutated since it was compiled")
          .hasMessageContaining(rules.version());
    }

    @Test
    @DisplayName("stays out of the way when nothing was mutated")
    void quietWhenClean() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(tierRule()));

      for (int attempt = 0; attempt < 50; attempt++) {
        assertThatCode(() -> rules.newSession(SessionOptions.builder().strict(true).build()).close())
            .doesNotThrowAnyException();
      }
    }

    @Test
    @DisplayName("does not run in production, where §7.5 forbids strict mode")
    void notWithoutStrictMode() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(tierRule()));
      mutateLiteral(rules, "SILVER");

      // A detector that fired in production would turn a caller's mistake into an outage. §7.5's
      // whole table is checks that are too expensive for production and are run in test instead.
      assertThatCode(() -> rules.newSession(SessionOptions.builder().strict(false).build()).close())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("notices a mutated object literal, which §2.6.1 compares structurally")
    void objectLiteral() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("structural")
          .when("o", "Order", pattern -> pattern.op("customer",
              com.codeheadsystems.rules.rule.Operator.EQ, Facts.json("{\"id\": 7}")))
          .then(actions -> actions.emit("hit"))
          .build()));

      final FieldConstraint constraint = (FieldConstraint) rules.rules().getFirst()
          .source().when().getFirst().constraints().getFirst();
      ((tools.jackson.databind.node.ObjectNode) constraint.literal()).put("tier", "GOLD");

      assertThatThrownBy(() -> rules.newSession(SessionOptions.builder().strict(true).build()))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("has nothing to catch on a scalar bound, which cannot be edited in place")
    void scalarBoundsAreSafeByType() {
      // Worth stating rather than assuming: a numeric or string literal is an immutable JsonNode
      // subclass, so the hole is specific to the container-valued ones -- arrays and objects.
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("range")
          .when("o", "Order", pattern -> pattern.between("total", 100, 500))
          .then(actions -> actions.emit("hit"))
          .build()));

      assertThatCode(() -> rules.newSession(SessionOptions.builder().strict(true).build()).close())
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("notices a mutated right-hand-side literal")
    void rhsLiteral() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("emits")
          .when("o", "Order")
          .then(actions -> actions.emit("out", "tags", Facts.array("a")))
          .build()));

      final com.codeheadsystems.rules.rule.Emit emit =
          (com.codeheadsystems.rules.rule.Emit) rules.rules().getFirst().source().then().getFirst();
      ((ArrayNode) ((com.codeheadsystems.rules.rule.Literal)
          emit.payload().getFirst().value()).value()).add("b");

      assertThatThrownBy(() -> rules.newSession(SessionOptions.builder().strict(true).build()))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the network tests the same node the fingerprint hashes, even across the rewrite")
    void networkSharesTheHashedNode() {
      /*
       * The fingerprint walks `rule.source().when()`. The matcher tests against whatever the
       * compiled pattern holds. Those are the same objects today, and the detector is worthless the
       * moment they stop being -- a caller could mutate the node the matcher actually reads while
       * the hash, computed over the source, never moved.
       *
       * It is not obvious that they are the same, because RuleCompiler rewrites an ordered
       * FieldConstraint into a RangeConstraint (§6.2.1), and RangeConstraint's compact constructor
       * deep-copies its bounds. The copy is a no-op only because Jackson's scalar nodes are
       * immutable and return `this` from deepCopy(), and because the compiler rejects a non-scalar
       * bound before it gets here. Two facts about other people's code, propping up an invariant
       * this file is the enforcement of -- so they are pinned rather than trusted.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("ordered")
          .when("o", "Order", pattern -> pattern.constraint(new FieldConstraint(
              "total", Operator.GT, JsonNodeFactory.instance.numberNode(100))))
          .then(actions -> actions.emit("hit"))
          .build()));

      final CompiledRule rule = rules.rules().getFirst();
      final JsonNode hashed = ((FieldConstraint) rule.source().when().getFirst()
          .constraints().getFirst()).literal();
      final JsonNode tested = ((RangeTest) rule.patterns().getFirst().alphaTests().getFirst())
          .source().lower().orElseThrow();

      assertThat(tested).isSameAs(hashed);
    }

    @Test
    @DisplayName("a non-scalar bound is rejected at compile, which is what makes the above hold")
    void orderedOperatorsRejectNonScalarBounds() {
      // The other half. If this ever starts compiling, RangeConstraint's deepCopy stops being a
      // no-op, the network gets a bound the fingerprint cannot see, and networkSharesTheHashedNode
      // above is the test that says so.
      assertThatThrownBy(() -> RuleCompiler.compile(List.of(Rules.rule("bad")
          .when("o", "Order", pattern -> pattern.constraint(new FieldConstraint(
              "total", Operator.GT, Facts.array(1, 2))))
          .then(actions -> actions.emit("hit"))
          .build())))
          .isInstanceOf(RuleCompilationException.class);
    }
  }
}
