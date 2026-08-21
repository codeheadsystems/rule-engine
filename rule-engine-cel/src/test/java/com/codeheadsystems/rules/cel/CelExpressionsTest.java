package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.expr.CompiledExpression;
import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.expr.ExpressionCompilationException;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The CEL implementation of §6.4's escape hatch.
 *
 * <p>Two obligations get the most attention here, because they are the two §6.4 and §7.3 would be
 * broken by silently: that an expression cannot reach anything outside its bindings, and that its
 * cost is bounded at both ends.
 */
class CelExpressionsTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final CelExpressions compiler = CelExpressions.create();

  private static JsonNode json(final String text) {
    try {
      return JSON.readTree(text);
    } catch (final JsonProcessingException broken) {
      throw new AssertionError("the test fixture is not valid JSON: " + text, broken);
    }
  }

  /** Bindings over a fixed set of payloads. */
  private static ExpressionBindings bind(final Map<String, JsonNode> facts) {
    return alias -> facts.getOrDefault(alias, MissingNode.getInstance());
  }

  @Nested
  @DisplayName("conditions")
  class Conditions {

    @Test
    @DisplayName("read a bound fact's fields the way a $ref would")
    void readsFields() {
      final CompiledExpression program =
          compiler.compileCondition("o.total > 10000", Set.of("o"));

      assertThat(program.test(bind(Map.of("o", json("{\"total\": 25000}"))))).isTrue();
      assertThat(program.test(bind(Map.of("o", json("{\"total\": 50}"))))).isFalse();
    }

    @Test
    @DisplayName("express the nested boolean logic operator maps cannot")
    void nestedLogic() {
      final CompiledExpression program = compiler.compileCondition(
          "o.total > 10000 && (o.region in ['US','EU'] || o.priorityFlag)", Set.of("o"));

      assertThat(program.test(bind(Map.of("o", json("""
          {"total": 25000, "region": "XX", "priorityFlag": true}"""))))).isTrue();
      assertThat(program.test(bind(Map.of("o", json("""
          {"total": 25000, "region": "XX", "priorityFlag": false}"""))))).isFalse();
    }

    @Test
    @DisplayName("span two facts, which is what makes them a post-filter and not an alpha test")
    void spansTwoFacts() {
      final CompiledExpression program =
          compiler.compileCondition("o.total > c.creditLimit * 2", Set.of("o", "c"));

      assertThat(program.test(bind(Map.of(
          "o", json("{\"total\": 300}"), "c", json("{\"creditLimit\": 100}"))))).isTrue();
      assertThat(program.test(bind(Map.of(
          "o", json("{\"total\": 150}"), "c", json("{\"creditLimit\": 100}"))))).isFalse();
    }

    @Test
    @DisplayName("must produce a boolean, checked when compiled rather than when fired")
    void resultTypeChecked() {
      assertThatThrownBy(() -> compiler.compileCondition("o.total + 1", Set.of("o")))
          .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    @DisplayName("cannot name a variable the rule does not declare")
    void unknownVariableRejected() {
      assertThatThrownBy(() -> compiler.compileCondition("nope.total > 1", Set.of("o")))
          .isInstanceOf(ExpressionCompilationException.class);
    }

    @Test
    @DisplayName("are rejected when they will not parse")
    void syntaxErrorRejected() {
      assertThatThrownBy(() -> compiler.compileCondition("o.total >", Set.of("o")))
          .isInstanceOf(ExpressionCompilationException.class);
    }
  }

  @Nested
  @DisplayName("determinism (§7.3)")
  class Determinism {

    @Test
    @DisplayName("no clock is reachable, so a rule's outcome cannot depend on when it ran")
    void noClock() {
      // CEL's standard function set contains no clock read at all -- time enters as a bound
      // variable -- and this compiler binds only the rule's aliases. Both halves matter, and this
      // asserts the pair rather than trusting either.
      for (final String clock : new String[] {"now()", "now", "timestamp()", "currentTime()"}) {
        assertThatThrownBy(() -> compiler.compileCondition(clock + " != null", Set.of("o")))
            .as("'%s' must not be reachable from a rule", clock)
            .isInstanceOf(ExpressionCompilationException.class);
      }
    }

    @Test
    @DisplayName("the same bindings give the same answer, every time")
    void repeatable() {
      final CompiledExpression program =
          compiler.compileCondition("o.total > 10000", Set.of("o"));
      final ExpressionBindings bindings = bind(Map.of("o", json("{\"total\": 25000}")));

      for (int attempt = 0; attempt < 100; attempt++) {
        assertThat(program.test(bindings)).isTrue();
      }
    }
  }

  @Nested
  @DisplayName("bounded cost (§6.4)")
  class Cost {

    @Test
    @DisplayName("a bigger expression estimates higher, so a budget can discriminate")
    void estimateGrows() {
      final long simple = compiler.compileCondition("o.a > 1", Set.of("o")).estimatedCost();
      final long complex = compiler.compileCondition(
          "o.a > 1 && o.b > 2 && o.c > 3 && (o.d < 4 || o.e < 5)", Set.of("o")).estimatedCost();

      assertThat(simple).isPositive();
      assertThat(complex).isGreaterThan(simple);
    }

    @Test
    @DisplayName("a comprehension that runs away is stopped rather than allowed to finish")
    void comprehensionBounded() {
      // §6.4: CEL guarantees termination, NOT linear time. Nested comprehensions over two lists
      // are O(n*m), which is the case this limit exists for.
      final CelExpressions tight =
          CelExpressions.builder().maxComprehensionIterations(10).build();
      // `all` rather than `exists`: exists short-circuits on the first hit, so it would never
      // reach the limit and the test would pass without the limit existing at all.
      final CompiledExpression program =
          tight.compileCondition("o.items.all(i, i > 0)", Set.of("o"));

      assertThat(program.test(bind(Map.of("o", json("{\"items\": [1, 2, 3]}"))))).isTrue();
      assertThatThrownBy(() -> program.test(bind(Map.of("o", json("""
          {"items": [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20]}""")))))
          .as("§6.4: CEL guarantees termination, not linear time -- this is the bound that matters")
          .isInstanceOf(ExpressionEvaluationException.class);
    }

    @Test
    @DisplayName("an expression too large to parse is refused rather than parsed anyway")
    void nodeCountBounded() {
      final CelExpressions tight = CelExpressions.builder().maxNodes(5).build();

      assertThatThrownBy(() -> tight.compileCondition(
          "o.a > 1 && o.b > 2 && o.c > 3 && o.d > 4 && o.e > 5", Set.of("o")))
          .isInstanceOf(ExpressionCompilationException.class);
    }
  }

  @Nested
  @DisplayName("values")
  class Values {

    @Test
    @DisplayName("compute arithmetic across fields, which is what callFunction was needed for")
    void arithmetic() {
      final CompiledExpression program =
          compiler.compileValue("o.subtotal + o.tax", Set.of("o"));

      assertThat(program.evaluate(bind(Map.of("o", json("""
          {"subtotal": 100, "tax": 7}""")))).intValue()).isEqualTo(107);
    }

    @Test
    @DisplayName("produce strings, booleans, lists and objects, not only numbers")
    void richResults() {
      assertThat(compiler.compileValue("'tier-' + c.tier", Set.of("c"))
          .evaluate(bind(Map.of("c", json("{\"tier\": \"HIGH\"}")))).textValue())
          .isEqualTo("tier-HIGH");
      assertThat(compiler.compileValue("o.total > 10", Set.of("o"))
          .evaluate(bind(Map.of("o", json("{\"total\": 50}")))).booleanValue()).isTrue();
      assertThat(compiler.compileValue("[o.a, o.b]", Set.of("o"))
          .evaluate(bind(Map.of("o", json("{\"a\": 1, \"b\": 2}"))))).hasSize(2);
    }

    @Test
    @DisplayName("fail on an absent field, which is CEL's rule and NOT §2.6.1's")
    void absentFieldIsAnError() {
      /*
       * Worth pinning because it is the sharpest difference between the two halves of the DSL. An
       * operator map treats an absent field as a value: §2.6.1 makes `eq` false and `ne` true
       * against it, and nothing fails. CEL treats a missing map key as an error.
       *
       * Not reconciled, deliberately. Making absence read as null here would mean rewriting every
       * payload into something CEL does not have, and would leave expressions quietly disagreeing
       * with the operator maps beside them. Adopting CEL's own semantics keeps the escape hatch
       * honest about being CEL, and `has()` is the guard its authors already know.
       */
      assertThatThrownBy(() -> compiler.compileValue("o.missing", Set.of("o"))
          .evaluate(bind(Map.of("o", json("{}")))))
          .isInstanceOf(ExpressionEvaluationException.class)
          .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("guard an optional field with has(), which is how CEL says hasField")
    void hasGuardsAbsence() {
      final CompiledExpression program =
          compiler.compileCondition("has(o.coupon) && o.coupon != ''", Set.of("o"));

      assertThat(program.test(bind(Map.of("o", json("{}"))))).isFalse();
      assertThat(program.test(bind(Map.of("o", json("{\"coupon\": \"X\"}"))))).isTrue();
    }
  }

  @Nested
  @DisplayName("the value conversion")
  class Conversion {

    @Test
    @DisplayName("keeps an integral number exact")
    void integralExact() {
      assertThat(compiler.compileValue("o.n", Set.of("o"))
          .evaluate(bind(Map.of("o", json("{\"n\": 9007199254740993}")))).longValue())
          .isEqualTo(9_007_199_254_740_993L);
    }

    @Test
    @DisplayName("carries nested objects and arrays through both directions")
    void nested() {
      assertThat(compiler.compileValue("o.customer", Set.of("o"))
          .evaluate(bind(Map.of("o", json("""
              {"customer": {"id": 7, "tags": ["a", "b"]}}"""))))
          .get("tags")).hasSize(2);
    }
  }
}
