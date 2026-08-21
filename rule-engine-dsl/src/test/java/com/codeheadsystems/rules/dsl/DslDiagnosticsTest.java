package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every diagnostic this module can raise, and where it says the problem is.
 *
 * <p>Two obligations are tested here that no other test covers.
 *
 * <p><strong>Completeness.</strong> {@link DslError} is enumerable so that
 * {@code docs/dsl-reference.md}'s catalogue can be checked rather than trusted, and
 * {@link Catalogue} is that check: a constant nothing raises is either dead or an untested path,
 * and both are worth knowing about.
 *
 * <p><strong>Location.</strong> §6.5 hands semantic validation to {@code RuleCompiler}, whose
 * diagnostics are written for rules assembled in Java. Re-reporting them against a line of YAML is
 * the whole reason this module is more than a call to Jackson, and it is done by matching the
 * prefixes the compiler writes -- a coupling that only stays honest if something notices when the
 * compiler rewords itself. This is that something.
 */
class DslDiagnosticsTest {

  /** Parses, expecting failure, and returns the diagnostics. */
  private static List<DslDiagnostic> reject(final String yaml) {
    return reject(yaml, CompilerOptions.defaults());
  }

  private static List<DslDiagnostic> reject(final String yaml, final CompilerOptions options) {
    final RuleFileException thrown = catchThrowableOfType(RuleFileException.class,
        () -> RuleFiles.compile(List.of(RuleSource.yaml("rules.yaml", yaml)), options));
    assertThat(thrown).as("expected this file to be rejected").isNotNull();
    return thrown.diagnostics();
  }

  private static DslDiagnostic only(final String yaml) {
    final List<DslDiagnostic> found = reject(yaml);
    assertThat(found).hasSize(1);
    return found.getFirst();
  }

  @Nested
  @DisplayName("the diagnostic catalogue")
  class Catalogue {

    @Test
    @DisplayName("every unshielded DslError is reachable from a real rule file")
    void everyReachableErrorIsRaised() {
      final Set<DslError> raised = CORPUS.stream()
          .flatMap(yaml -> reject(yaml).stream())
          .map(DslDiagnostic::error)
          .collect(Collectors.toCollection(() -> EnumSet.noneOf(DslError.class)));

      final Set<DslError> expected = EnumSet.allOf(DslError.class);
      expected.removeIf(DslError::shieldedBySchema);

      assertThat(expected)
          .as("a code an author can reach, that no rule file in the corpus raises, is untested")
          .isSubsetOf(raised);
    }

    @Test
    @DisplayName("a shielded DslError really is unreachable through RuleFiles")
    void shieldedErrorsAreUnreachable() {
      final Set<DslError> raised = CORPUS.stream()
          .flatMap(yaml -> reject(yaml).stream())
          .map(DslDiagnostic::error)
          .collect(Collectors.toCollection(() -> EnumSet.noneOf(DslError.class)));

      assertThat(raised.stream().filter(DslError::shieldedBySchema).toList())
          .as("if this fails, the schema stopped stating something and the flag is now a lie")
          .isEmpty();
    }

    @Test
    @DisplayName("but is still raised by the component that would otherwise assume the gate ran")
    void shieldedErrorsAreStillRaisedByTheirComponent() {
      final List<DslDiagnostic> raised = new java.util.ArrayList<>();
      final Diagnostics diagnostics = new Diagnostics(
          SourceIndex.of(RuleSource.yaml("t.yaml", "{}")), raised);

      OperatorMaps.constraintsOf("total", operatorMap("{ greaterThan: 1 }"), "", diagnostics);
      OperatorMaps.constraintsOf("total", operatorMap("{ matches: { $ref: c.p } }"), "",
          diagnostics);
      Actions.actionOf(
          new ThenNode("sendEmail", null, null, null, null, null, null, null, null, null),
          "", diagnostics);

      assertThat(raised).extracting(DslDiagnostic::error)
          .containsExactlyInAnyOrder(DslError.UNKNOWN_OPERATOR, DslError.MALFORMED_OPERAND,
              DslError.UNKNOWN_ACTION);
    }

    private tools.jackson.databind.JsonNode operatorMap(final String yaml) {
      try {
        return RuleFormat.YAML.mapper().readTree(yaml);
      } catch (final tools.jackson.core.JacksonException broken) {
        throw new AssertionError(broken);
      }
    }

    @Test
    @DisplayName("codes round-trip, so tooling can match on them without matching on prose")
    void codesRoundTrip() {
      for (final DslError error : DslError.values()) {
        assertThat(DslError.forCode(error.code())).isEqualTo(error);
      }
    }
  }

  @Nested
  @DisplayName("a located diagnostic")
  class Located {

    @Test
    @DisplayName("names the line the offending operator is written on")
    void operatorLine() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: r
              when:
                - fact: Order
                  as: o
                  where:
                    total: { eq: { $reff: o.id } }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.error()).isEqualTo(DslError.UNKNOWN_DOLLAR_KEY);
      assertThat(diagnostic.location()).isPresent();
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(8);
      assertThat(diagnostic.describe()).startsWith("rules.yaml:8:");
    }

    @Test
    @DisplayName("names the rule it belongs to, so a multi-rule file is navigable")
    void namesTheRule() {
      assertThat(only("""
          apiVersion: rules.v1
          rules:
            - id: first
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: e }]
            - id: second
              when: [{ fact: Order, as: o, where: { t: { between: {} } } }]
              then: [{ action: emit, event: e }]
          """).ruleId()).contains("second");
    }
  }

  @Nested
  @DisplayName("semantic diagnostics from the compiler")
  class Semantic {

    @Test
    @DisplayName("a forward $ref is located at the constraint that made it")
    void forwardReference() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: forward
              when:
                - fact: Order
                  as: o
                  where:
                    customerId: { eq: { $ref: c.id } }
                - fact: Customer
                  as: c
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.error()).isEqualTo(DslError.SEMANTIC);
      assertThat(diagnostic.message()).contains("bound later");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(8);
    }

    @Test
    @DisplayName("a $ref to an alias nothing binds is located at the constraint")
    void unknownAlias() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: unknown-alias
              when:
                - fact: Order
                  as: o
                  where:
                    customerId: { eq: { $ref: nope.id } }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.message()).contains("not bound by this rule");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(8);
    }

    @Test
    @DisplayName("a rule-scoped problem is located at the rule's id")
    void duplicateAlias() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: twice
              when:
                - { fact: Order, as: o }
                - { fact: Order, as: o }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.message()).contains("bound twice");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(3);
      assertThat(diagnostic.ruleId()).contains("twice");
    }

    @Test
    @DisplayName("an unregistered callFunction name is located, since §11.3 wants it caught early")
    void unregisteredFunction() {
      final DslDiagnostic diagnostic = reject("""
          apiVersion: rules.v1
          rules:
            - id: calls-out
              when: [{ fact: Order, as: o }]
              then: [{ action: callFunction, name: notifySlack, args: { a: 1 } }]
          """, CompilerOptions.builder().declaredFunctions(Set.of("notifyEmail")).build())
          .getFirst();

      assertThat(diagnostic.error()).isEqualTo(DslError.SEMANTIC);
      assertThat(diagnostic.message()).contains("notifySlack");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(3);
    }

    @Test
    @DisplayName("a malformed where-key path is a located diagnostic, not an IllegalArgumentException")
    void malformedFieldPathInWhere() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: bad-path
              when:
                - fact: Order
                  as: o
                  where:
                    a..b: { eq: 1 }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.error()).isEqualTo(DslError.SEMANTIC);
      assertThat(diagnostic.message()).contains("empty path segment");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(8);
    }

    @Test
    @DisplayName("a malformed $ref target path is located too")
    void malformedFieldPathInRef() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: bad-ref-path
              when:
                - { fact: Order, as: o }
                - fact: Customer
                  as: c
                  where:
                    id: { eq: { $ref: "o.a..b" } }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.message())
          .contains("empty path segment")
          .as("the far side of the edge names itself, so the near alias does not look malformed")
          .contains("$ref target 'o.a..b'");
      assertThat(diagnostic.location().orElseThrow().line())
          .as("located on the line the $ref is written on, which is the near side's")
          .isEqualTo(9);
    }

    @Test
    @DisplayName("an invalid regex is located at the constraint carrying it")
    void invalidRegex() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: bad-regex
              when:
                - fact: Order
                  as: o
                  where:
                    email: { matches: "([a-z" }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.message()).containsIgnoringCase("regular expression");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(8);
    }

    @Test
    @DisplayName("attributes a diagnostic to the rule that caused it, not to one merely named 'o'")
    void attributionIsNotByCoincidence() {
      // 'o' is a legal rule id and also the alias the OTHER rule binds. A fallback that matched
      // any message containing 'o' in quotes would hand the second rule's problem to the first.
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: o
              when: [{ fact: Order, as: x }]
              then: [{ action: emit, event: e }]
            - id: really-broken
              when:
                - { fact: Order, as: o }
                - { fact: Order, as: o }
              then: [{ action: emit, event: e }]
          """);

      assertThat(diagnostic.ruleId()).contains("really-broken");
      assertThat(diagnostic.location().orElseThrow().line()).isEqualTo(6);
    }

    @Test
    @DisplayName("keeps the compilation failure as its cause rather than swallowing it")
    void keepsTheCause() {
      final RuleFileException thrown = catchThrowableOfType(RuleFileException.class,
          () -> RuleFiles.compile(RuleSource.yaml("rules.yaml", """
              apiVersion: rules.v1
              rules:
                - id: twice
                  when:
                    - { fact: Order, as: o }
                    - { fact: Order, as: o }
                  then: [{ action: emit, event: e }]
              """)));

      assertThat(thrown).isNotNull();
      assertThat(thrown.getCause())
          .isInstanceOf(com.codeheadsystems.rules.compiler.RuleCompilationException.class);
    }
  }

  @Nested
  @DisplayName("$-prefixed keys in the positions the author writes names")
  class DollarKeysInNames {

    @Test
    @DisplayName("a forgotten wrapping key in a payload is rejected, not bound as a literal field")
    void bareRefAsPayloadName() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: forgot-the-wrapper
              when: [{ fact: Order, as: o }]
              then:
                - action: emit
                  event: e
                  payload:
                    $ref: o.id
          """);

      assertThat(diagnostic.error()).isEqualTo(DslError.MALFORMED_REFERENCE);
      assertThat(diagnostic.message()).contains("$$ref");
    }

    @Test
    @DisplayName("an unrecognised $-key is rejected as a where field name")
    void dollarKeyAsFieldName() {
      assertThat(only("""
          apiVersion: rules.v1
          rules:
            - id: dollar-field
              when: [{ fact: Order, as: o, where: { $foo: { eq: 1 } } }]
              then: [{ action: emit, event: e }]
          """).error()).isEqualTo(DslError.UNKNOWN_DOLLAR_KEY);
    }

    @Test
    @DisplayName("but $$ still escapes, so a payload field really named $ref stays expressible")
    void escapedNameIsAccepted() {
      final List<com.codeheadsystems.rules.rule.RuleDefinition> rules =
          RuleFiles.parse(RuleSource.yaml("ok.yaml", """
              apiVersion: rules.v1
              rules:
                - id: escaped-name
                  when: [{ fact: Order, as: o }]
                  then:
                    - action: emit
                      event: e
                      payload:
                        $$ref: "a literal field named $ref"
              """));

      final com.codeheadsystems.rules.rule.Emit emit =
          (com.codeheadsystems.rules.rule.Emit) rules.getFirst().then().getFirst();
      assertThat(emit.payload()).singleElement()
          .extracting(com.codeheadsystems.rules.rule.PayloadField::name).isEqualTo("$ref");
    }
  }

  @Nested
  @DisplayName("the CEL escape hatch")
  class Condition {

    @Test
    @DisplayName("without a registered compiler, says so against the line that used it")
    void conditionNeedsACompiler() {
      final DslDiagnostic diagnostic = only("""
          apiVersion: rules.v1
          rules:
            - id: uses-cel
              when:
                - fact: Order
                  as: o
                  condition: "o.total > 10000"
              then: [{ action: emit, event: e }]
          """);

      // §6.4 makes the escape hatch an opt-in cost of a dependency as well as of evaluation, so
      // its absence is a compile error naming the module -- located, like every other diagnostic.
      assertThat(diagnostic.error()).isEqualTo(DslError.SEMANTIC);
      assertThat(diagnostic.message()).contains("rule-engine-cel");
      assertThat(diagnostic.location().orElseThrow().line())
          .as("the condition's own line, not the rule's id -- expression text most needs a line")
          .isEqualTo(7);
    }
  }

  @Nested
  @DisplayName("reporting")
  class Reporting {

    @Test
    @DisplayName("collects every problem in one pass, the way the compiler does")
    void everyProblemAtOnce() {
      assertThat(reject("""
          apiVersion: rules.v1
          rules:
            - id: several
              when:
                - fact: Order
                  as: o
                  where:
                    a: { eq: { $one: 1 } }
                    b: { eq: { $two: 2 } }
                    c: { between: {} }
              then: [{ action: emit, event: e }]
          """)).hasSize(3);
    }

    @Test
    @DisplayName("renders as file:line:column: [code] message")
    void rendering() {
      assertThat(only("""
          apiVersion: rules.v1
          rules:
            - id: r
              when: [{ fact: Order, as: o, where: { t: { between: {} } } }]
              then: [{ action: emit, event: e }]
          """).describe())
          .matches("rules\\.yaml:\\d+:\\d+: \\[empty-range] .*");
    }
  }

  /**
   * One rule file per {@link DslError}, so {@link Catalogue} can prove the set is reachable.
   *
   * <p>Kept as a list rather than folded into the individual tests above: the completeness check
   * needs every code raised in one place, and a check that quietly stops covering a code as tests
   * are refactored around it is worse than no check.
   */
  private static final List<String> CORPUS = List.of(
      // MALFORMED_DOCUMENT
      "apiVersion: rules.v1\nrules:\n  - id: [unclosed\n",
      // UNKNOWN_API_VERSION
      "apiVersion: rules.v99\nrules: []\n",
      // SCHEMA_VIOLATION
      "apiVersion: rules.v1\nrules:\n  - saliance: 3\n",
      /*
       * The three shielded codes each need a fixture that ATTEMPTS them, or
       * shieldedErrorsAreUnreachable asserts only that a corpus which never tries something did not
       * achieve it -- and a schema that quietly lost `additionalProperties: false` or its verb enum
       * would sail through. These three are what make that test load-bearing.
       */
      // UNKNOWN_OPERATOR, if the schema's closed operator set ever stopped closing it.
      """
      apiVersion: rules.v1
      rules:
        - id: unknown-operator
          when: [{ fact: Order, as: o, where: { t: { greaterThan: 10 } } }]
          then: [{ action: emit, event: e }]
      """,
      // UNKNOWN_ACTION, if the schema's five-verb enum ever stopped enumerating.
      """
      apiVersion: rules.v1
      rules:
        - id: unknown-action
          when: [{ fact: Order, as: o }]
          then: [{ action: sendEmail, to: "ops@example.com" }]
      """,
      """
      apiVersion: rules.v1
      rules:
        - id: dollar
          when: [{ fact: Order, as: o, where: { t: { eq: { $nope: 1 } } } }]
          then: [{ action: emit, event: e }]
      """,
      """
      apiVersion: rules.v1
      rules:
        - id: bad-ref
          when: [{ fact: Order, as: o, where: { t: { eq: { $ref: nodot } } } }]
          then: [{ action: emit, event: e }]
      """,
      """
      apiVersion: rules.v1
      rules:
        - id: empty-range
          when: [{ fact: Order, as: o, where: { t: { between: {} } } }]
          then: [{ action: emit, event: e }]
      """,
      // MALFORMED_OPERAND, if the schema ever stopped typing `matches` as a string.
      """
      apiVersion: rules.v1
      rules:
        - id: ref-on-single-fact-test
          when: [{ fact: Order, as: o, where: { t: { matches: { $ref: c.p } } } }]
          then: [{ action: emit, event: e }]
      """,
      """
      apiVersion: rules.v1
      rules:
        - id: bad-path
          when: [{ fact: Order, as: o }]
          then: [{ action: setField, target: o, field: "a..b", value: 1 }]
      """,

      """
      apiVersion: rules.v1
      rules:
        - id: twice
          when:
            - { fact: Order, as: o }
            - { fact: Order, as: o }
          then: [{ action: emit, event: e }]
      """);
}
