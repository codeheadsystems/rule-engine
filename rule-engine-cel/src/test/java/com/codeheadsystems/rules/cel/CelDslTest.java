package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFileException;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Engine;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.FiringSequence;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §6.4's escape hatch as an author actually writes it: in a rule file.
 *
 * <p>Both positions, both syntaxes -- {@code condition:} on a pattern and {@code $expr} in a value
 * -- driven through the same pipeline a rule file takes in production.
 */
class CelDslTest {

  private static final Consumer<RuleSession> ORDERS = session -> {
    session.insert("Order", Facts.json("""
        {"id": 1, "subtotal": 100, "tax": 7, "region": "XX", "priorityFlag": true}"""));
    session.insert("Order", Facts.json("""
        {"id": 2, "subtotal": 10, "tax": 1, "region": "US", "priorityFlag": false}"""));
  };

  private static CompiledRuleSet compile(final String yaml) {
    return RuleFiles.compile(List.of(RuleSource.yaml("rules.yaml", yaml)),
        CompilerOptions.builder().expressions(CelExpressions.create()).build());
  }

  @Nested
  @DisplayName("condition:")
  class Conditions {

    @Test
    @DisplayName("expresses the nested OR that operator maps deliberately cannot")
    void nestedOr() {
      final CompiledRuleSet rules = compile("""
          apiVersion: rules.v1
          rules:
            - id: interesting-order
              when:
                - fact: Order
                  as: o
                  condition: "o.subtotal > 50 && (o.region in ['US','EU'] || o.priorityFlag)"
              then:
                - action: emit
                  event: interesting
                  payload:
                    orderId: { $ref: o.id }
          """);

      final FiringSequence fired = Engine.run(rules, SessionOptions.defaults(), ORDERS);

      assertThat(fired.steps()).singleElement().satisfies(step ->
          assertThat(step.emitted().getFirst()).contains("\"orderId\":1"));
    }

    @Test
    @DisplayName("combines with operator maps on the same pattern")
    void alongsideOperatorMaps() {
      final CompiledRuleSet rules = compile("""
          apiVersion: rules.v1
          rules:
            - id: both
              when:
                - fact: Order
                  as: o
                  where:
                    region: { eq: "US" }
                  condition: "o.subtotal + o.tax < 50"
              then: [{ action: emit, event: cheap-us }]
          """);

      assertThat(Engine.run(rules, SessionOptions.defaults(), ORDERS).steps()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("$expr")
  class Values {

    @Test
    @DisplayName("computes an emitted value, which previously needed callFunction")
    void computesEmittedValue() {
      final CompiledRuleSet rules = compile("""
          apiVersion: rules.v1
          rules:
            - id: total
              when: [{ fact: Order, as: o, where: { id: { eq: 1 } } }]
              then:
                - action: emit
                  event: priced
                  payload:
                    total: { $expr: "o.subtotal + o.tax" }
          """);

      assertThat(Engine.run(rules, SessionOptions.defaults(), ORDERS).steps())
          .singleElement()
          .satisfies(step -> assertThat(step.emitted().getFirst()).contains("107"));
    }

    @Test
    @DisplayName("sets a field on a matched fact")
    void setsField() {
      final CompiledRuleSet rules = compile("""
          apiVersion: rules.v1
          rules:
            - id: band
              when: [{ fact: Order, as: o, where: { id: { eq: 1 } } }]
              then:
                - action: setField
                  target: o
                  field: band
                  value: { $expr: "o.subtotal > 50 ? 'HIGH' : 'LOW'" }
          """);

      try (RuleSession session = rules.newSession()) {
        ORDERS.accept(session);
        session.fireAllRules();
        assertThat(session.workingMemory().factsOfType("Order")
            .filter(fact -> fact.payload().get("id").intValue() == 1)
            .findFirst().orElseThrow().payload().get("band").stringValue())
            .isEqualTo("HIGH");
      }
    }

    @Test
    @DisplayName("is rejected when nested inside a literal, like $ref is")
    void nestedExprRejected() {
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: nested
              when: [{ fact: Order, as: o }]
              then:
                - action: emit
                  event: e
                  payload:
                    wrapper: { inner: { $expr: "1 + 1" } }
          """))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("$$expr");
    }

    @Test
    @DisplayName("escapes as $$expr for a literal field genuinely named $expr")
    void escaped() {
      final CompiledRuleSet rules = compile("""
          apiVersion: rules.v1
          rules:
            - id: escaped
              when: [{ fact: Order, as: o, where: { id: { eq: 1 } } }]
              then:
                - action: emit
                  event: e
                  payload:
                    meta: { $$expr: "not an expression" }
          """);

      assertThat(Engine.run(rules, SessionOptions.defaults(), ORDERS).steps())
          .singleElement()
          .satisfies(step ->
              assertThat(step.emitted().getFirst()).contains("not an expression"));
    }
  }

  @Nested
  @DisplayName("a bad expression in a rule file")
  class Diagnostics {

    @Test
    @DisplayName("is reported against the line it is written on")
    void locatedDiagnostic() {
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: broken
              when:
                - fact: Order
                  as: o
                  condition: "o.subtotal >"
              then: [{ action: emit, event: e }]
          """))
          .isInstanceOf(RuleFileException.class)
          // The condition is on line 7; a diagnostic located at the rule id would be
          // the DSL's headline feature quietly not applying to expressions.
          .hasMessageContaining("rules.yaml:7")
          .hasMessageContaining("mismatched input");
    }

    @Test
    @DisplayName("an $expr value is located at its own line, not at the rule's id")
    void expressionValueLocated() {
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: broken-value
              when: [{ fact: Order, as: o }]
              then:
                - action: emit
                  event: e
                  payload:
                    total: { $expr: "o.subtotal +" }
          """))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("rules.yaml:9");
    }

    @Test
    @DisplayName("a repeated $expr is located at its FIRST occurrence, which is the one reported")
    void duplicateExpressionUsesFirstOccurrence() {
      /*
       * The compiler dedups identical expression text and compiles the first occurrence, so the
       * location has to agree. With aliases bound progressively the two are not equally valid: this
       * expression is a forward reference on line 9 and perfectly fine on line 16, and pointing at
       * line 16 would tell the author their working code references an undeclared alias.
       */
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: dup
              when: [{ fact: Order, as: o }]
              then:
                - action: emit
                  event: early
                  payload:
                    v: { $expr: "sig.weight" }
                - action: insertFact
                  fact: RiskSignal
                  as: sig
                  payload:
                    weight: 3
                - action: emit
                  event: late
                  payload:
                    v: { $expr: "sig.weight" }
          """))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("rules.yaml:9")
          .satisfies(thrown -> assertThat(thrown.getMessage())
              .as("line 16 is the occurrence that is valid; blaming it inverts the diagnostic")
              .doesNotContain("rules.yaml:16"));
    }

    @Test
    @DisplayName("a where field named 'condition' does not steal the condition's location")
    void fieldNamedConditionDoesNotCollide() {
      // The location key must not be confusable with a field path, and `condition` is not an
      // exotic field name.
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: collide
              when:
                - fact: Order
                  as: o
                  where:
                    condition: { eq: true }
                  condition: "o.subtotal >"
              then: [{ action: emit, event: e }]
          """))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("rules.yaml:9");
    }

    @Test
    @DisplayName("naming an alias the rule does not bind is rejected by the expression language")
    void unknownAlias() {
      assertThatThrownBy(() -> compile("""
          apiVersion: rules.v1
          rules:
            - id: unbound
              when: [{ fact: Order, as: o, condition: "c.tier == 'HIGH'" }]
              then: [{ action: emit, event: e }]
          """))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("undeclared reference");
    }
  }
}
