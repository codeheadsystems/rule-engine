package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.SetField;
import tools.jackson.core.JacksonException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §6.2.2's five verbs, one at a time.
 *
 * <p>The assertion that matters most is the one about {@code $ref}: §6.2.3 says the {@code where}
 * form and the {@code then} form share a syntax and nothing else, so a reference here must come out
 * as a fire-time {@link FieldRef} and never as a compile-time join.
 */
class ActionMappingTest {

  private final List<DslDiagnostic> raised = new ArrayList<>();

  private Optional<ActionDefinition> compile(final String yaml) {
    final ThenNode node;
    try {
      node = RuleFormat.YAML.mapper().readValue(yaml, ThenNode.class);
    } catch (final JacksonException broken) {
      throw new AssertionError("the test fixture is not valid YAML: " + yaml, broken);
    }
    return Actions.actionOf(node,
        "", new Diagnostics(SourceIndex.of(RuleSource.yaml("t.yaml", yaml)), raised));
  }

  private ActionDefinition compileCleanly(final String yaml) {
    final Optional<ActionDefinition> action = compile(yaml);
    assertThat(raised).as("unexpected diagnostics").isEmpty();
    return action.orElseThrow();
  }

  @Nested
  @DisplayName("setField")
  class SetFieldVerb {

    @Test
    @DisplayName("compiles target, field and a literal value, with the path precompiled")
    void literalValue() {
      final SetField action = (SetField) compileCleanly(
          "{ action: setField, target: o, field: status, value: \"REVIEW\" }");

      assertThat(action.targetAlias()).isEqualTo("o");
      assertThat(action.field()).isEqualTo("status");
      assertThat(action.path().toString()).isEqualTo("/status");
      assertThat(action.value()).isInstanceOf(Literal.class);
      assertThat(((Literal) action.value()).value().stringValue()).isEqualTo("REVIEW");
    }

    @Test
    @DisplayName("a $ref value is a fire-time FieldRef, not a compile-time join")
    void refValue() {
      final SetField action = (SetField) compileCleanly(
          "{ action: setField, target: o, field: tier, value: { $ref: c.riskTier } }");

      assertThat(action.value()).isInstanceOf(FieldRef.class);
      final FieldRef ref = (FieldRef) action.value();
      assertThat(ref.alias()).isEqualTo("c");
      assertThat(ref.path().toString()).isEqualTo("/riskTier");
    }

    @Test
    @DisplayName("a dotted field compiles to a nested pointer, per §2.6")
    void dottedField() {
      final SetField action = (SetField) compileCleanly(
          "{ action: setField, target: o, field: customer.tier, value: 1 }");

      assertThat(action.path().toString()).isEqualTo("/customer/tier");
    }

    @Test
    @DisplayName("a malformed path is rejected here rather than at fire time")
    void malformedPath() {
      assertThat(compile("{ action: setField, target: o, field: \"a..b\", value: 1 }")).isEmpty();

      assertThat(raised).singleElement()
          .extracting(DslDiagnostic::error).isEqualTo(DslError.MALFORMED_ACTION);
    }
  }

  @Nested
  @DisplayName("insertFact")
  class InsertFactVerb {

    @Test
    @DisplayName("binds its optional alias so later actions in the same RHS can name the new fact")
    void withAlias() {
      final InsertFact action = (InsertFact) compileCleanly("""
          { action: insertFact, fact: RiskSignal, as: sig,
            payload: { orderId: { $ref: o.id }, severity: "HIGH" } }
          """);

      assertThat(action.factType()).isEqualTo("RiskSignal");
      assertThat(action.alias()).contains("sig");
      assertThat(action.payload()).extracting(PayloadField::name)
          .containsExactly("orderId", "severity");
      assertThat(action.payload().getFirst().value()).isInstanceOf(FieldRef.class);
      assertThat(action.payload().get(1).value()).isInstanceOf(Literal.class);
    }

    @Test
    @DisplayName("leaves the alias empty when the author did not bind one")
    void withoutAlias() {
      final InsertFact action = (InsertFact) compileCleanly(
          "{ action: insertFact, fact: RiskSignal, payload: { severity: \"LOW\" } }");

      assertThat(action.alias()).isEmpty();
    }

    @Test
    @DisplayName("keeps payload fields in document order")
    void payloadOrder() {
      final InsertFact action = (InsertFact) compileCleanly(
          "{ action: insertFact, fact: F, payload: { z: 1, a: 2, m: 3 } }");

      assertThat(action.payload()).extracting(PayloadField::name).containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("compiles a dotted payload name into a nested path")
    void dottedPayloadName() {
      final InsertFact action = (InsertFact) compileCleanly(
          "{ action: insertFact, fact: F, payload: { \"customer.id\": 7 } }");

      assertThat(action.payload().getFirst().path().toString()).isEqualTo("/customer/id");
    }
  }

  @Nested
  @DisplayName("retractFact")
  class RetractFactVerb {

    @Test
    @DisplayName("carries only the alias it retracts")
    void retract() {
      assertThat(compileCleanly("{ action: retractFact, target: sig }"))
          .isEqualTo(new RetractFact("sig"));
    }
  }

  @Nested
  @DisplayName("emit")
  class EmitVerb {

    @Test
    @DisplayName("compiles the event type and its payload")
    void emit() {
      final Emit action = (Emit) compileCleanly("""
          { action: emit, event: order.flagged,
            payload: { orderId: { $ref: o.id }, reason: "high value + risk tier" } }
          """);

      assertThat(action.eventType()).isEqualTo("order.flagged");
      assertThat(action.payload()).hasSize(2);
      assertThat(((FieldRef) action.payload().getFirst().value()).alias()).isEqualTo("o");
    }

    @Test
    @DisplayName("accepts an event with no payload at all")
    void emitWithoutPayload() {
      assertThat(((Emit) compileCleanly("{ action: emit, event: ping }")).payload()).isEmpty();
    }
  }

  @Nested
  @DisplayName("callFunction")
  class CallFunctionVerb {

    @Test
    @DisplayName("compiles the name and its arguments, the closed set's one escape")
    void call() {
      final CallFunction action = (CallFunction) compileCleanly("""
          { action: callFunction, name: notifySlack,
            args: { channel: "#risk-review", orderId: { $ref: o.id } } }
          """);

      assertThat(action.name()).isEqualTo("notifySlack");
      assertThat(action.args()).extracting(PayloadField::name)
          .containsExactly("channel", "orderId");
    }
  }

  @Nested
  @DisplayName("an unknown verb")
  class Unknown {

    @Test
    @DisplayName("is rejected here too, so the switch has no silent default")
    void unknownVerb() {
      assertThat(compile("{ action: sendEmail, target: o }")).isEmpty();

      assertThat(raised).singleElement().satisfies(diagnostic -> {
        assertThat(diagnostic.error()).isEqualTo(DslError.UNKNOWN_ACTION);
        assertThat(diagnostic.message()).contains("sendEmail");
      });
    }
  }
}
