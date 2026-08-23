package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule-file schema gate (spec §6.5).
 *
 * <p>Every case here is one this gate must catch <em>before</em> binding, because the alternative
 * to a schema violation reported against a line is a Jackson exception reported against a stack.
 *
 * <p>What is deliberately <strong>not</strong> tested here is meaning. That an operand is a
 * {@code $ref}, that a {@code between} has a bound, that an alias exists -- none of it is this
 * gate's business, and asserting it here would be the first step towards implementing it twice.
 */
class RuleFileSchemaTest {

  private final List<DslDiagnostic> diagnostics = new ArrayList<>();

  /** Wraps a rule body in the smallest valid file around it. */
  private static String file(final String ruleBody) {
    return "apiVersion: rules.v1\nrules:\n  - " + ruleBody.strip().replace("\n", "\n    ") + "\n";
  }

  private List<DslDiagnostic> reject(final String yaml) {
    assertThat(RuleFileReader.read(RuleSource.yaml("rules.yaml", yaml), diagnostics)).isEmpty();
    assertThat(diagnostics).isNotEmpty();
    return diagnostics;
  }

  private void accept(final String yaml) {
    assertThat(RuleFileReader.read(RuleSource.yaml("rules.yaml", yaml), diagnostics)).isPresent();
    assertThat(diagnostics).isEmpty();
  }

  @Nested
  @DisplayName("apiVersion")
  class ApiVersion {

    @Test
    @DisplayName("an unknown version is rejected as such, not as a pile of unknown keys")
    void unknownVersion() {
      final List<DslDiagnostic> found = reject("""
          apiVersion: rules.v2
          rules:
            - id: r
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: e }]
          """);

      assertThat(found).singleElement().satisfies(diagnostic -> {
        assertThat(diagnostic.error()).isEqualTo(DslError.UNKNOWN_API_VERSION);
        assertThat(diagnostic.message()).contains("rules.v2").contains("rules.v1");
        assertThat(diagnostic.location()).isPresent();
      });
    }

    @Test
    @DisplayName("a missing version is rejected, because a best-effort parse is what §6.2.3 forbids")
    void missingVersion() {
      final List<DslDiagnostic> found = reject("""
          rules:
            - id: r
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: e }]
          """);

      assertThat(found).singleElement()
          .extracting(DslDiagnostic::error).isEqualTo(DslError.UNKNOWN_API_VERSION);
    }
  }

  @Nested
  @DisplayName("required keys")
  class Required {

    @Test
    @DisplayName("a rule needs an id")
    void ruleNeedsId() {
      assertThat(reject(file("""
          when: [{ fact: Order, as: o }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic -> {
            assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION);
            assertThat(diagnostic.message()).contains("id");
          });
    }

    @Test
    @DisplayName("a rule needs a when and a then")
    void ruleNeedsWhenAndThen() {
      assertThat(reject(file("id: r\n"))).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a pattern needs a fact and an alias")
    void patternNeedsFactAndAlias() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).contains("as"));
    }

    @Test
    @DisplayName("a file needs at least one rule, rather than silently loading none")
    void fileNeedsARule() {
      assertThat(reject("apiVersion: rules.v1\nrules: []\n")).anySatisfy(diagnostic ->
          assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION));
    }
  }

  @Nested
  @DisplayName("unknown keys")
  class Unknown {

    @Test
    @DisplayName("a misspelled rule key is rejected, not ignored")
    void unknownRuleKey() {
      assertThat(reject(file("""
          id: r
          saliance: 10
          when: [{ fact: Order, as: o }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic -> {
            assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION);
            assertThat(diagnostic.message()).contains("saliance");
          });
    }

    @Test
    @DisplayName("a misspelled operator is rejected by the schema's closed operator set")
    void unknownOperatorKey() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o, where: { total: { greaterThan: 10 } } }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).contains("greaterThan"));
    }

    @Test
    @DisplayName("a quantifier outside the implemented pair is rejected, forAll included")
    void unknownQuantifier() {
      /*
       * §2.5's enum reserves FOR_ALL and ACCUMULATE and §1 defers them, and the schema deliberately
       * does not spell either: rules.v1 is published, and a spelling for a feature that does not
       * exist promises a shape the version implementing it may not want.
       *
       * An enum violation names the permitted values rather than the offending one -- the same
       * shape the verb enum above produces -- so what makes it navigable is the location, which is
       * the key's own line and not the rule's.
       */
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o, quantifier: forAll }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic -> {
            assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION);
            assertThat(diagnostic.message()).contains("notExists");
            assertThat(diagnostic.location().orElseThrow().pointer())
                .isEqualTo("/rules/0/when/0/quantifier");
          });
    }

    @Test
    @DisplayName("an unknown top-level file key is rejected")
    void unknownFileKey() {
      assertThat(reject("""
          apiVersion: rules.v1
          ruels:
            - id: r
          """)).anySatisfy(diagnostic ->
              assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION));
    }
  }

  @Nested
  @DisplayName("wrong types")
  class Types {

    @Test
    @DisplayName("salience must be an integer")
    void salienceType() {
      assertThat(reject(file("""
          id: r
          salience: "high"
          when: [{ fact: Order, as: o }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).containsIgnoringCase("integer"));
    }

    @Test
    @DisplayName("in takes a list, because §2.6.1 defines it as eq against each element")
    void inTakesAList() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o, where: { tier: { in: "HIGH" } } }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).containsIgnoringCase("array"));
    }

    @Test
    @DisplayName("hasField takes a boolean, since the literal carries the polarity")
    void hasFieldTakesABoolean() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o, where: { coupon: { hasField: "no" } } }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).containsIgnoringCase("boolean"));
    }

    @Test
    @DisplayName("a range bound cannot be a boolean, which §2.6.1 cannot order")
    void rangeBoundType() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o, where: { total: { gt: true } } }]
          then: [{ action: emit, event: e }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION));
    }
  }

  @Nested
  @DisplayName("the five action verbs")
  class Actions {

    @Test
    @DisplayName("an unknown verb is rejected against the closed set of §6.2.2")
    void unknownVerb() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o }]
          then: [{ action: sendEmail, to: "ops@example.com" }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION));
    }

    @Test
    @DisplayName("setField names the key IT is missing, which a oneOf could not")
    void setFieldMissingKey() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o }]
          then: [{ action: setField, target: o, field: status }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).contains("value"));
    }

    @Test
    @DisplayName("a key belonging to a different verb is rejected on this one")
    void keyFromAnotherVerb() {
      assertThat(reject(file("""
          id: r
          when: [{ fact: Order, as: o }]
          then: [{ action: retractFact, target: o, event: "nope" }]
          """))).anySatisfy(diagnostic ->
              assertThat(diagnostic.message()).contains("event"));
    }

    @Test
    @DisplayName("each of the five verbs is accepted in its documented shape")
    void allFiveVerbsAccepted() {
      accept("""
          apiVersion: rules.v1
          rules:
            - id: every-verb
              when: [{ fact: Order, as: o }]
              then:
                - { action: setField, target: o, field: status, value: "REVIEW" }
                - { action: insertFact, fact: RiskSignal, as: sig, payload: { orderId: 1 } }
                - { action: retractFact, target: sig }
                - { action: emit, event: order.flagged, payload: { reason: "why" } }
                - { action: callFunction, name: notifySlack, args: { channel: "#risk" } }
          """);
    }
  }

  @Nested
  @DisplayName("diagnostic order")
  class Ordering {

    @Test
    @DisplayName("follows the file top to bottom, past ten rules where pointer text would not")
    void reportedInFileOrder() {
      final StringBuilder yaml = new StringBuilder("apiVersion: rules.v1\nrules:\n");
      // Twelve rules, every one of them missing 'then'. Sorting on the pointer string would put
      // /rules/10 and /rules/11 between /rules/1 and /rules/2.
      for (int rule = 0; rule < 12; rule++) {
        yaml.append("  - id: r").append(rule)
            .append("\n    when: [{ fact: Order, as: o }]\n");
      }

      final List<DslDiagnostic> found = reject(yaml.toString());

      assertThat(found).hasSize(12);
      assertThat(found).extracting(diagnostic ->
              diagnostic.location().orElseThrow().line())
          .isSorted();
      assertThat(found).extracting(diagnostic -> diagnostic.ruleId().orElseThrow())
          .containsExactly("r0", "r1", "r2", "r3", "r4", "r5",
              "r6", "r7", "r8", "r9", "r10", "r11");
    }
  }

  @Nested
  @DisplayName("what the schema deliberately lets through")
  class OwnedByTheCompiler {

    @Test
    @DisplayName("a $ref operand, whose interpretation §6.2.3 puts in one place downstream")
    void refOperandPasses() {
      accept(file("""
          id: r
          when:
            - { fact: Order, as: o }
            - { fact: Customer, as: c, where: { id: { eq: { $ref: o.customerId } } } }
          then: [{ action: emit, event: e }]
          """));
    }

    @Test
    @DisplayName("both spellings of the quantifier key, which Quantifiers maps and §2.5 defines")
    void quantifierPasses() {
      accept(file("""
          id: r
          when:
            - { fact: Order, as: o, quantifier: exists }
            - { fact: Payment, as: p, quantifier: notExists,
                where: { orderId: { eq: { $ref: o.id } } } }
          then: [{ action: emit, event: e }]
          """));
    }

    @Test
    @DisplayName("a condition block, so the DSL can say where CEL arrives rather than 'unknown key'")
    void conditionPasses() {
      accept(file("""
          id: r
          when: [{ fact: Order, as: o, condition: "o.total > 10000" }]
          then: [{ action: emit, event: e }]
          """));
    }
  }
}
