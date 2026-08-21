package com.codeheadsystems.rules.schema.networknt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.schema.SchemaViolationException;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.StringNode;

/**
 * What registering a schema buys, end to end (spec §2.3, §6.5, §2.6.1).
 *
 * <p>§2.3 makes a specific promise -- that a schema turns a class of authoring mistake from "a rule
 * that silently never matches" into a compile error, and a class of data mistake from "a fact that
 * quietly matches nothing" into a failure at the boundary. These are the tests that make it true.
 */
class SchemaBindingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(final String text) {
    try {
      return JSON.readTree(text);
    } catch (final JacksonException broken) {
      throw new AssertionError("the test fixture is not valid JSON: " + text, broken);
    }
  }

  private static final JsonSchemaFactSchemas SCHEMAS = JsonSchemaFactSchemas.builder()
      .register("Order", json("""
          {
            "type": "object",
            "required": ["id", "total"],
            "properties": {
              "id":     { "type": "integer" },
              "total":  { "type": "number" },
              "status": { "type": "string" }
            }
          }"""))
      .build();

  private static CompilerOptions withSchemas() {
    return CompilerOptions.builder().factSchemas(SCHEMAS).build();
  }

  @Nested
  @DisplayName("at rule-compile time")
  class CompileTime {

    @Test
    @DisplayName("a literal the field could never hold is an error, not a rule that never matches")
    void incompatibleLiteral() {
      final RuleDefinition rule = Rules.rule("wrong-type")
          .when("o", "Order", pattern -> pattern.gt("total", "expensive"))
          .then(actions -> actions.emit("e")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("total")
          .hasMessageContaining("number")
          .hasMessageContaining("never match");
    }

    @Test
    @DisplayName("the same rule compiles happily with no schema registered")
    void unregisteredIsUnchecked() {
      final RuleDefinition rule = Rules.rule("wrong-type")
          .when("o", "Order", pattern -> pattern.gt("total", "expensive"))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an in list is rejected only when EVERY element is incompatible")
    void inNeedsEveryElementToFail() {
      // §2.6.1 defines IN as EQ against each element, so one dead entry is dead weight, not a
      // defect -- {"status": "OPEN"} still matches this.
      final RuleDefinition mixed = Rules.rule("mixed-list")
          .when("o", "Order", pattern -> pattern.op("status", Operator.IN,
              Facts.array("PENDING", 7)))
          .then(actions -> actions.emit("e")).build();
      assertThatCode(() -> RuleCompiler.compile(List.of(mixed), withSchemas()))
          .doesNotThrowAnyException();

      final RuleDefinition hopeless = Rules.rule("all-wrong")
          .when("o", "Order", pattern -> pattern.op("status", Operator.IN, Facts.array(7, 8)))
          .then(actions -> actions.emit("e")).build();
      assertThatThrownBy(() -> RuleCompiler.compile(List.of(hopeless), withSchemas()))
          .isInstanceOf(RuleCompilationException.class);
    }

    @Test
    @DisplayName("an integer field compared against a fractional bound is fine: §2.6.1 has one number class")
    void integerIsNotItsOwnCompatibilityClass() {
      // {"qty": 100} matches this. §2.6.1's classes are {number}, {string}, {boolean}, {array},
      // {object} -- JSON Schema's integer/number split is not one of them.
      final RuleDefinition rule = Rules.rule("fractional-bound")
          .when("o", "Order", pattern -> pattern.gt("id", 99.5))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a wrong-typed ne is NOT an error, because §2.6.1 makes it true, not false")
    void antiMatchIsNotAnError() {
      final RuleDefinition rule = Rules.rule("wrong-typed-ne")
          .when("o", "Order", pattern -> pattern.ne("status", 5))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("but it is reported as vacuous, since it constrains nothing")
    void antiMatchIsWarnedAbout() {
      final CompilerReport report = RuleCompiler.compile(List.of(Rules.rule("wrong-typed-ne")
          .when("o", "Order", pattern -> pattern.ne("status", 5))
          .then(actions -> actions.emit("e")).build()), withSchemas()).report();

      // Exactly one: the ne-on-optional-path check stands down here, because a constraint that is
      // always true already matches an absent field and saying so twice helps nobody.
      assertThat(report.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(CompilerReport.VACUOUS_ANTI_MATCH);
        assertThat(warning.message()).contains("always").contains("{string}");
      });
    }

    @Test
    @DisplayName("a notIn is vacuous only when no element is comparable")
    void notInVacuousOnlyWhenHopeless() {
      final CompilerReport mixed = RuleCompiler.compile(List.of(Rules.rule("mixed")
          .when("o", "Order", pattern -> pattern.op("status", Operator.NOT_IN,
              Facts.array("X", 7)))
          .then(actions -> actions.emit("e")).build()), withSchemas()).report();
      assertThat(mixed.warnings())
          .noneSatisfy(warning ->
              assertThat(warning.code()).isEqualTo(CompilerReport.VACUOUS_ANTI_MATCH));

      final CompilerReport hopeless = RuleCompiler.compile(List.of(Rules.rule("hopeless")
          .when("o", "Order", pattern -> pattern.op("status", Operator.NOT_IN, Facts.array(7, 8)))
          .then(actions -> actions.emit("e")).build()), withSchemas()).report();
      assertThat(hopeless.warnings()).anySatisfy(warning ->
          assertThat(warning.code()).isEqualTo(CompilerReport.VACUOUS_ANTI_MATCH));
    }

    @Test
    @DisplayName("matches on a non-string field is an error, naming what matches actually compares")
    void matchesNeedsAString() {
      final RuleDefinition rule = Rules.rule("regex-on-number")
          .when("o", "Order", pattern -> pattern.matches("total", "^1"))
          .then(actions -> actions.emit("e")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("matches compares a string");
    }

    @Test
    @DisplayName("a non-finite double is a diagnostic, not a NumberFormatException")
    void nonFiniteBound() {
      // Canonical.decimal guards this hazard and says why: a payload or literal built in Java can
      // carry a non-finite double, and BigDecimal.valueOf(NaN) throws.
      final RuleDefinition rule = Rules.rule("not-a-number")
          .when("o", "Order", pattern -> pattern.op("id", Operator.GT,
              DoubleNode.valueOf(Double.NaN)))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a cross-type error is reported once, not once per compilation stage")
    void reportedOnce() {
      final RuleDefinition rule = Rules.rule("one-error")
          .when("o", "Order", pattern -> pattern.op("total", Operator.GT,
              StringNode.valueOf("expensive")))
          .then(actions -> actions.emit("e")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .isInstanceOf(RuleCompilationException.class)
          .satisfies(thrown -> assertThat(
              ((RuleCompilationException) thrown).diagnostics()).hasSize(1));
    }

    @Test
    @DisplayName("an explicit null passes against any declared type, since §2.6.1 allows eq: null")
    void nullIsAlwaysAcceptable() {
      final RuleDefinition rule = Rules.rule("null-check")
          .when("o", "Order", pattern -> pattern.eq("status", (Object) null))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a presence test is not type-checked, since its literal is a polarity")
    void presenceTestsAreNotTypeChecked() {
      final RuleDefinition rule = Rules.rule("presence")
          .when("o", "Order", pattern -> pattern.hasField("total", true).isNull("status", false))
          .then(actions -> actions.emit("e")).build();

      assertThatCode(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a range is checked on both ends, not only one")
    void rangeBoundsChecked() {
      // Two bad bounds and two diagnostics: with only one bound wrong this would pass even if a
      // whole end of the check were missing.
      final RuleDefinition rule = Rules.rule("bad-range")
          .when("o", "Order", pattern -> pattern.between("total", "cheap", "lots"))
          .then(actions -> actions.emit("e")).build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule), withSchemas()))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("cheap")
          .hasMessageContaining("lots")
          .satisfies(thrown -> assertThat(
              ((RuleCompilationException) thrown).diagnostics()).hasSize(2));
    }
  }

  @Nested
  @DisplayName("at insert time")
  class RunTime {

    private CompiledRuleSet ruleSet() {
      return RuleCompiler.compile(List.of(Rules.rule("any")
          .when("o", "Order", pattern -> pattern.gt("total", 1))
          .then(actions -> actions.emit("e")).build()), withSchemas());
    }

    @Test
    @DisplayName("a malformed fact is rejected before it can enter the network")
    void malformedFactRejected() {
      try (RuleSession session = ruleSet().newSession()) {
        assertThatThrownBy(() -> session.insert("Order", json("{\"id\": 1}")))
            .isInstanceOf(SchemaViolationException.class)
            .satisfies(thrown -> assertThat(
                ((SchemaViolationException) thrown).factType()).isEqualTo("Order"));
      }
    }

    @Test
    @DisplayName("a well-formed fact passes straight through")
    void wellFormedFactAccepted() {
      try (RuleSession session = ruleSet().newSession()) {
        assertThatCode(() -> session.insert("Order", json("""
            {"id": 1, "total": 25000}"""))).doesNotThrowAnyException();
        assertThat(session.fireAllRules().fired()).hasSize(1);
      }
    }

    @Test
    @DisplayName("an update is validated too, not only the original insert")
    void updateValidated() {
      try (RuleSession session = ruleSet().newSession()) {
        final var handle = session.insert("Order", json("""
            {"id": 1, "total": 25000}"""));

        assertThatThrownBy(() -> session.update(handle, json("{\"id\": 1}")))
            .isInstanceOf(SchemaViolationException.class);
      }
    }

    @Test
    @DisplayName("a derived fact is validated too, and a rejection does not leak its handle")
    void derivedFactValidatedWithoutLeaking() {
      /*
       * insertFact reserves a handle at stage time and fills it at commit, so a schema rejection
       * throws from inside the commit phase. RhsExecutor explains why a reservation must never
       * escape unreleased: it would leak one id per firing, forever, under a skip-and-continue
       * policy.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("derives-bad-fact")
          .when("t", "Trigger")
          .then(actions -> actions.insertFactAs("Order", "o", "id", 1))
          .build()), withSchemas());

      for (int attempt = 0; attempt < 2; attempt++) {
        try (RuleSession session = rules.newSession()) {
          session.insert("Trigger", json("{}"));
          assertThatThrownBy(session::fireAllRules)
              .as("the derived Order lacks the required total")
              .isInstanceOf(SchemaViolationException.class);
        }
      }
    }

    @Test
    @DisplayName("a rejected update leaves working memory exactly as it was")
    void rejectedUpdateChangesNothing() {
      try (RuleSession session = ruleSet().newSession()) {
        final var handle = session.insert("Order", json(
            "{\"id\": 1, \"total\": 25000}"));

        assertThatThrownBy(() -> session.update(handle, json("{\"id\": 1}")))
            .isInstanceOf(SchemaViolationException.class);

        assertThat(session.get(handle).orElseThrow().payload().get("total").intValue())
            .as("validation runs before the \u00a73.4.1 diff and before any mutation")
            .isEqualTo(25000);
      }
    }

    @Test
    @DisplayName("a type nobody registered is untouched, which is the zero-setup default")
    void unregisteredTypeUnvalidated() {
      try (RuleSession session = ruleSet().newSession()) {
        assertThatCode(() -> session.insert("Anything", json("{\"shape\": \"arbitrary\"}")))
            .doesNotThrowAnyException();
      }
    }
  }

  @Nested
  @DisplayName("§2.6.1's ne trap, as a warning")
  class AntiMatchWarning {

    private CompilerReport reportOf(final RuleDefinition rule, final CompilerOptions options) {
      return RuleCompiler.compile(List.of(rule), options).report();
    }

    @Test
    @DisplayName("warns on ne against an optional path, and names the fix")
    void warnsOnOptionalPath() {
      final CompilerReport report = reportOf(Rules.rule("anti-match")
          .when("o", "Order", pattern -> pattern.ne("status", "CLOSED"))
          .then(actions -> actions.emit("e")).build(), withSchemas());

      assertThat(report.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(CompilerReport.NE_ON_OPTIONAL_PATH);
        assertThat(warning.fieldPath()).contains("status");
        assertThat(warning.message()).contains("hasField: true");
      });
    }

    @Test
    @DisplayName("stays quiet when hasField: true already guards it")
    void quietWhenGuarded() {
      final CompilerReport report = reportOf(Rules.rule("guarded")
          .when("o", "Order", pattern -> pattern.hasField("status", true).ne("status", "CLOSED"))
          .then(actions -> actions.emit("e")).build(), withSchemas());

      assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("stays quiet on a required path, where the trap cannot spring")
    void quietOnRequiredPath() {
      final CompilerReport report = reportOf(Rules.rule("required-path")
          .when("o", "Order", pattern -> pattern.ne("total", 0))
          .then(actions -> actions.emit("e")).build(), withSchemas());

      assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("stays quiet with no schema, rather than firing on every ne in the codebase")
    void quietWithoutSchema() {
      final CompilerReport report = reportOf(Rules.rule("anti-match")
          .when("o", "Order", pattern -> pattern.ne("status", "CLOSED"))
          .then(actions -> actions.emit("e")).build(), CompilerOptions.defaults());

      assertThat(report.warnings()).isEmpty();
    }
  }
}
