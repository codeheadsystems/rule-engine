package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.EmittedEvent;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.TerminationReason;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The DSL's front door, end to end (spec §9's Phase 5 exit criterion).
 *
 * <p>"An author writes YAML, never touches Java" is a claim about a whole pipeline, and the only
 * way to hold it is to run the whole pipeline: text in one end, a fired rule and an emitted event
 * out of the other, with no {@code RuleDefinition} built by hand anywhere in between.
 */
class RuleFilesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** §6.2's worked example, which is also README's, written as a rule file. */
  private static final String ORDERS = """
      apiVersion: rules.v1
      rules:
        - id: high-value-order-review
          salience: 10
          noLoop: true
          when:
            - fact: Order
              as: o
              where:
                total:  { gt: 10000 }
                status: { eq: "PENDING" }
            - fact: Customer
              as: c
              where:
                id:       { eq: { $ref: o.customerId } }
                riskTier: { in: ["HIGH", "MEDIUM"] }
          then:
            - action: setField
              target: o
              field: status
              value: "REVIEW"
            - action: emit
              event: "order.flagged"
              payload:
                orderId: { $ref: o.id }
                reason: "high value + risk tier"
      """;

  private static JsonNode json(final String text) {
    try {
      return JSON.readTree(text);
    } catch (final JacksonException broken) {
      throw new AssertionError("the test fixture is not valid JSON: " + text, broken);
    }
  }

  @Nested
  @DisplayName("an author who writes YAML and never touches Java")
  class EndToEnd {

    @Test
    @DisplayName("gets a rule that fires, mutates a fact, and emits the event it declared")
    void yamlRuleFires() {
      final CompiledRuleSet rules = RuleFiles.compile(RuleSource.yaml("orders.yaml", ORDERS));

      final FireResult result;
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", json("""
            {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
        session.insert("Customer", json("""
            {"id": 7, "riskTier": "HIGH"}"""));
        result = session.fireAllRules();
      }

      assertThat(result.fired()).hasSize(1);
      assertThat(result.fired().getFirst().key().ruleId()).isEqualTo("high-value-order-review");
      assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
      assertThat(result.emitted()).singleElement().satisfies(event -> {
        assertThat(event.eventType()).isEqualTo("order.flagged");
        assertThat(event.payload().get("orderId").intValue()).isEqualTo(1);
        assertThat(event.payload().get("reason").stringValue())
            .isEqualTo("high value + risk tier");
      });
    }

    @Test
    @DisplayName("gets the join enforced, so a mismatched customer does not fire the rule")
    void joinIsEnforced() {
      final CompiledRuleSet rules = RuleFiles.compile(RuleSource.yaml("orders.yaml", ORDERS));

      final FireResult result;
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", json("""
            {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
        session.insert("Customer", json("""
            {"id": 99, "riskTier": "HIGH"}"""));
        result = session.fireAllRules();
      }

      assertThat(result.fired()).isEmpty();
    }

    @Test
    @DisplayName("gets the same result whether the file was JSON or YAML")
    void formatDoesNotChangeBehaviour() {
      final String asJson = jsonFormOfOrders();

      final List<EmittedEvent> fromYaml =
          fire(RuleFiles.compile(RuleSource.yaml("orders.yaml", ORDERS)));
      final List<EmittedEvent> fromJson =
          fire(RuleFiles.compile(RuleSource.json("orders.json", asJson)));

      assertThat(fromYaml).hasSize(1);
      assertThat(fromYaml.getFirst().payload()).isEqualTo(fromJson.getFirst().payload());
    }

    private List<EmittedEvent> fire(final CompiledRuleSet rules) {
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", json("""
            {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
        session.insert("Customer", json("""
            {"id": 7, "riskTier": "HIGH"}"""));
        return session.fireAllRules().emitted();
      }
    }

    /** The identical rule set, serialized as JSON, so parity is checked on behaviour too. */
    private String jsonFormOfOrders() {
      try {
        return JSON.writeValueAsString(
            RuleFormat.YAML.mapper().readTree(ORDERS));
      } catch (final JacksonException impossible) {
        throw new AssertionError(impossible);
      }
    }
  }

  @Nested
  @DisplayName("many files")
  class ManyFiles {

    @Test
    @DisplayName("compile into one rule set, because §6.2.3 makes a rule set their union")
    void unionOfFiles() {
      final List<RuleDefinition> rules = RuleFiles.parse(
          RuleSource.yaml("a.yaml", minimal("first")),
          RuleSource.yaml("b.yaml", minimal("second")));

      assertThat(rules).extracting(RuleDefinition::id).containsExactly("first", "second");
    }

    @Test
    @DisplayName("share one id space, so a duplicate across two files is caught")
    void duplicateAcrossFiles() {
      assertThatThrownBy(() -> RuleFiles.compile(
          List.of(RuleSource.yaml("a.yaml", minimal("same")),
              RuleSource.yaml("b.yaml", minimal("same"))),
          com.codeheadsystems.rules.compiler.CompilerOptions.defaults()))
          .isInstanceOf(RuleFileException.class)
          .hasMessageContaining("duplicate rule id");
    }

    @Test
    @DisplayName("report every file's problems in one pass, not the first file's only")
    void everyFileReported() {
      assertThatThrownBy(() -> RuleFiles.parse(
          RuleSource.yaml("a.yaml", "apiVersion: rules.v0\nrules: []\n"),
          RuleSource.yaml("b.yaml", "apiVersion: rules.v0\nrules: []\n")))
          .isInstanceOf(RuleFileException.class)
          .satisfies(thrown -> assertThat(((RuleFileException) thrown).diagnostics()).hasSize(2));
    }

    private String minimal(final String id) {
      return """
          apiVersion: rules.v1
          rules:
            - id: %s
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: fired }]
          """.formatted(id);
    }
  }

  @Nested
  @DisplayName("a non-scalar apiVersion is a diagnostic, not a stack trace")
  class ApiVersionShape {

    /*
     * Found while migrating to Jackson 3. Jackson 2's asText() returned "" for a container node;
     * Jackson 3's asString() THROWS on one. The apiVersion check renders whatever it found into a
     * diagnostic message, and apiVersion comes from an untrusted rule file -- so a file writing
     * `apiVersion: {}` turned a located UNKNOWN_API_VERSION diagnostic into a raw JsonNodeException
     * escaping RuleFiles.compile.
     *
     * That is the failure this whole module exists to prevent, and no test had the shape to catch
     * it because every fixture writes a scalar. Parameterised over both container kinds and a
     * number, so the next accessor swap has to survive all three.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{ a: b }", "[1, 2]", "7", "true"})
    @DisplayName("every non-string apiVersion reports UNKNOWN_API_VERSION")
    void nonStringApiVersion(final String declared) {
      final RuleFileException thrown = catchThrowableOfType(RuleFileException.class,
          () -> RuleFiles.compile(RuleSource.yaml("v.yaml",
              "apiVersion: " + declared + "\nrules: []\n")));

      assertThat(thrown).isNotNull();
      assertThat(thrown.diagnostics())
          .extracting(DslDiagnostic::error)
          .contains(DslError.UNKNOWN_API_VERSION);
    }
  }

  @Nested
  @DisplayName("the rule-set version")
  class Versioning {

    private static final String ONE_ORDER = """
        apiVersion: rules.v1
        rules:
          - id: r
            when: [{ fact: Order, as: o, where: { total: { gt: 1 }, status: { eq: "P" } } }]
            then: [{ action: emit, event: e }]
        """;

    private static final String OTHER_ORDER = """
        apiVersion: rules.v1
        rules:
          - id: r
            when: [{ fact: Order, as: o, where: { status: { eq: "P" }, total: { gt: 1 } } }]
            then: [{ action: emit, event: e }]
        """;

    @Test
    @DisplayName("is stable across re-parses of the same text")
    void stableAcrossParses() {
      assertThat(RuleFiles.compile(RuleSource.yaml("a.yaml", ONE_ORDER)).version())
          .isEqualTo(RuleFiles.compile(RuleSource.yaml("a.yaml", ONE_ORDER)).version());
    }

    @Test
    @DisplayName("is stable across the two serializations of the same rule")
    void stableAcrossFormats() {
      final String asJson;
      try {
        asJson = JSON.writeValueAsString(RuleFormat.YAML.mapper().readTree(ONE_ORDER));
      } catch (final JacksonException impossible) {
        throw new AssertionError(impossible);
      }

      assertThat(RuleFiles.compile(RuleSource.json("a.json", asJson)).version())
          .isEqualTo(RuleFiles.compile(RuleSource.yaml("a.yaml", ONE_ORDER)).version());
    }

    @Test
    @DisplayName("changes when where-keys are reordered, which is documented rather than fixed")
    void sensitiveToConstraintOrder() {
      /*
       * Pinning a deliberate decision, not a bug. Constraint order is preserved into the AST
       * because it is mildly observable -- it is the evaluation order, and therefore which
       * constraint MatchExplainer blames for eliminating a fact -- so the content hash includes it.
       * Canonicalising the order away would make the version claim an equivalence the engine does
       * not provide. docs/dsl-reference.md says so; this is what would fail if somebody
       * "helpfully" sorted the keys.
       */
      assertThat(RuleFiles.compile(RuleSource.yaml("a.yaml", ONE_ORDER)).version())
          .isNotEqualTo(RuleFiles.compile(RuleSource.yaml("b.yaml", OTHER_ORDER)).version());
    }
  }

  @Nested
  @DisplayName("the rule definitions it produces")
  class Definitions {

    @Test
    @DisplayName("carry every §6.2.3 field, with the documented defaults for the absent ones")
    void fieldsAndDefaults() {
      final List<RuleDefinition> rules = RuleFiles.parse(RuleSource.yaml("r.yaml", """
          apiVersion: rules.v1
          rules:
            - id: fully-specified
              salience: 10
              noLoop: true
              agendaGroup: review
              tags: [fraud, manual-review]
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: e }]
            - id: all-defaults
              when: [{ fact: Order, as: o }]
              then: [{ action: emit, event: e }]
          """));

      final RuleDefinition specified = rules.getFirst();
      assertThat(specified.salience()).isEqualTo(10);
      assertThat(specified.noLoop()).isTrue();
      assertThat(specified.agendaGroup()).contains("review");
      assertThat(specified.tags()).containsExactly("fraud", "manual-review");

      final RuleDefinition defaults = rules.get(1);
      assertThat(defaults.salience()).isZero();
      assertThat(defaults.noLoop()).isFalse();
      assertThat(defaults.agendaGroup()).isEmpty();
      assertThat(defaults.tags()).isEmpty();
    }
  }
}
