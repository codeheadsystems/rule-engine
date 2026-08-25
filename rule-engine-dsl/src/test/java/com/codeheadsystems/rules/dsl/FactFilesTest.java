package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.fact.ExportedFact;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.Origin;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.Presence;
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.schema.SchemaViolationException;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Fact documents, in both serializations (spec §6.1).
 *
 * <p>The claim this holds is the one §6.1 makes about rule files, applied to facts: JSON and YAML
 * are one language here, and the same document written two ways produces the same facts. That is
 * asserted directly -- {@link Equivalence} reads the pair and compares -- rather than trusted to
 * the fact that both go through Jackson, because "both go through Jackson" is exactly what stops
 * being true the first time one path grows a mapper feature the other does not have.
 */
class FactFilesTest {

  private static final String YAML = """
      - type: Customer
        payload: { id: "c1", riskTier: "HIGH" }
      - type: Order
        payload:
          id: "o1"
          customerId: "c1"
          total: 12000
      """;

  private static final String JSON = """
      [
        { "type": "Customer", "payload": { "id": "c1", "riskTier": "HIGH" } },
        { "type": "Order",
          "payload": { "id": "o1", "customerId": "c1", "total": 12000 } }
      ]
      """;

  private static List<ExportedFact> readYaml(final String text) {
    return FactFiles.read(FactSource.yaml("facts.yaml", text));
  }

  private static FactFileException rejects(final String text) {
    return catchThrowableOfType(FactFileException.class,
        () -> readYaml(text));
  }

  @Nested
  @DisplayName("what a document says")
  class Reading {

    @Test
    @DisplayName("a list of typed facts, in document order")
    void readsFacts() {
      assertThat(readYaml(YAML))
          .extracting(ExportedFact::type)
          .describedAs("document order is insertion order, which §7.3 makes part of the input")
          .containsExactly("Customer", "Order");
      assertThat(readYaml(YAML).get(1).payload().get("total").intValue()).isEqualTo(12000);
    }

    @Test
    @DisplayName("everything a document states is asserted, never derived")
    void everythingIsAsserted() {
      // A document says what is true; what a rule set concludes from it is the session's to derive.
      // Replaying a DERIVED fact would double-count it against the rule that concludes it -- which
      // is why exportFacts() filters them out and why nothing here can produce one.
      assertThat(readYaml(YAML)).allMatch(fact -> fact.origin() == Origin.ASSERTED);
    }

    @Test
    @DisplayName("an empty list is a document holding no facts, not a broken one")
    void emptyList() {
      assertThat(readYaml("[]")).isEmpty();
    }

    @Test
    @DisplayName("a payload may be empty, and says so explicitly")
    void emptyPayload() {
      assertThat(readYaml("- { type: Tick, payload: {} }"))
          .singleElement()
          .satisfies(fact -> assertThat(fact.payload().isEmpty()).isTrue());
    }

    @Test
    @DisplayName("a bare payload document, for a caller who already knows the type")
    void barePayload() {
      assertThat(FactFiles.payload(FactSource.yaml("order.yaml", """
          id: "o1"
          total: 12000
          """)).get("id").stringValue()).isEqualTo("o1");
    }

    @Test
    @DisplayName("a bare payload document that is not an object")
    void barePayloadMustBeAnObject() {
      assertThatThrownBy(() -> FactFiles.payload(FactSource.yaml("order.yaml", "- 1\n")))
          .isInstanceOf(FactFileException.class)
          .hasMessageContaining("a payload is an object of fields")
          .hasMessageContaining("a list");
    }

    @Test
    @DisplayName("a null payload is named, not printed as a value")
    void nullPayload() {
      assertThat(rejects("- { type: Order, payload: null }\n")).hasMessageContaining("got null");
    }

    @Test
    @DisplayName("a document may end on a separator, which is not a second document")
    void trailingSeparator() {
      // YAML lets a file end on `---`, and telling an author a second document "starts here" sends
      // them looking for content that is not there.
      assertThat(readYaml("- { type: Order, payload: {} }\n---\n")).hasSize(1);
      assertThat(readYaml("- { type: Order, payload: {} }\n...\n")).hasSize(1);
    }

    @Test
    @DisplayName("the format comes off the file name")
    void fromAFile(@TempDir final Path directory) throws IOException {
      final Path file = directory.resolve("facts.yml");
      Files.writeString(file, YAML);
      assertThat(FactFiles.read(FactSource.of(file))).hasSize(2);
      assertThatThrownBy(() -> FactSource.of(directory.resolve("facts.txt")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("extension");
    }
  }

  @Nested
  @DisplayName("§6.1: one language, two serializations")
  class Equivalence {

    @Test
    @DisplayName("the same document written twice reads as the same facts")
    void jsonAndYamlAgree() {
      assertThat(FactFiles.read(FactSource.json("facts.json", JSON)))
          .describedAs("if these ever diverge, one serialization has grown a feature the other"
              + " has not, which is the failure §6.1's single factory choice exists to prevent")
          .isEqualTo(readYaml(YAML));
    }
  }

  @Nested
  @DisplayName("what it refuses")
  class Validation {

    @Test
    @DisplayName("a document that is not a list")
    void notAList() {
      assertThat(rejects("type: Order\npayload: {}\n"))
          .hasMessageContaining("a fact document is a list")
          .hasMessageContaining("facts.yaml:1:1");
    }

    @Test
    @DisplayName("an empty document, which is a truncation rather than an empty list")
    void empty() {
      assertThat(rejects("")).hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a missing or non-string type")
    void badType() {
      assertThat(rejects("- payload: {}\n")).hasMessageContaining("it is missing");
      assertThat(rejects("- { type: 7, payload: {} }\n"))
          .describedAs("named, never echoed: a `type` holding a large object is untrusted input")
          .hasMessageContaining("got the value 7");
      assertThat(rejects("- { type: \"  \", payload: {} }\n"))
          .hasMessageContaining("non-empty string");
    }

    @Test
    @DisplayName("a payload that is missing, or is not an object")
    void badPayload() {
      assertThat(rejects("- type: Order\n"))
          .hasMessageContaining("A fact with no fields is written payload: {}");
      assertThat(rejects("- { type: Order, payload: 7 }\n")).hasMessageContaining("the value 7");
      assertThat(rejects("- { type: Order, payload: [1] }\n")).hasMessageContaining("a list");
    }

    @Test
    @DisplayName("an unknown key, because the ones people write are typos for the two real ones")
    void unknownKey() {
      assertThat(rejects("- { type: Order, payloads: {} }\n"))
          .hasMessageContaining("unknown key 'payloads'")
          .describedAs("and the payload it does not have is reported too, in the same throw")
          .hasMessageContaining("payload: {}");
    }

    @Test
    @DisplayName("every problem at once, each on its own line")
    void everyProblem() {
      final FactFileException rejected = rejects("""
          - { type: 7, payload: {} }
          - { type: Order }
          - { type: Order, payload: {}, extra: 1 }
          """);
      assertThat(rejected.diagnostics()).hasSize(3);
      assertThat(rejected.diagnostics())
          .describedAs("a fixture is edited as a batch, so a reader gets every line to fix")
          .extracting(diagnostic -> diagnostic.location().orElseThrow().line())
          .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("text that is not well-formed at all")
    void malformed() {
      assertThat(rejects("- type: [Order\n"))
          .hasMessageContaining("not well-formed YAML")
          .hasCauseInstanceOf(tools.jackson.core.JacksonException.class);
    }

    @Test
    @DisplayName("trailing text that does not even tokenise")
    void trailingGarbage() {
      /*
       * The regression this file exists to prevent a second time. Tolerating a trailing `---` means
       * turning off Jackson's trailing-token check, and the first version turned it off
       * unconditionally -- so `[…] xyz`, which the token walk cannot see past, loaded as one fact
       * and said nothing. A truncated or concatenated file has exactly that shape.
       */
      assertThat(rejects("- { type: Order, payload: {} }\nxyz\n"))
          .hasMessageContaining("not well-formed");
      assertThatThrownBy(() -> FactFiles.read(FactSource.json("facts.json",
          "[{\"type\":\"A\",\"payload\":{}}] xyz")))
          .isInstanceOf(FactFileException.class)
          .hasMessageContaining("not well-formed");
      assertThatThrownBy(() -> FactFiles.payload(FactSource.json("p.json", "{\"a\":1} garbage")))
          .describedAs("and the bare-payload path shares the parse, so Facts.json is covered too")
          .isInstanceOf(FactFileException.class);
    }

    @Test
    @DisplayName("a second document, which Jackson catches as a trailing token and cannot explain")
    void twoDocuments() {
      assertThat(rejects("""
          - { type: Order, payload: {} }
          ---
          - { type: Order, payload: {} }
          """))
          .describedAs("naming the second document is the useful reply; 'trailing token' is not")
          .hasMessageContaining("a second one starts here")
          .hasMessageContaining("facts.yaml:3");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "- { type: Order, payload: { total: 1, total: 2 } }",
        "- { type: Order, payload: {}, type: Payment }",
    })
    @DisplayName("a repeated key, which both serializations otherwise accept and last-wins")
    void duplicateKeys(final String document) {
      // The same call RuleFormat makes for rule files, and it matters as much for data: a fixture
      // whose payload names `total` twice is a fact with a value nobody wrote on purpose, and it
      // reads correctly in review.
      assertThat(rejects(document)).hasMessageContaining("Duplicate");
    }
  }

  @Nested
  @DisplayName("a problem that belongs to no single element")
  class Unlocated {

    @Test
    @DisplayName("degrades to the message alone rather than to a wrong line")
    void describesItselfWithoutALocation() {
      // Reached when the parser gives up without a location -- a nesting-depth breach, say. Rare
      // enough to be worth asserting directly: a diagnostic that renders as ":0:0: …" is worse
      // than one that renders as prose.
      final FactDiagnostic unlocated = FactDiagnostic.of("something is wrong with this file");
      assertThat(unlocated.location()).isEmpty();
      assertThat(unlocated.describe()).isEqualTo("something is wrong with this file");
      assertThat(FactDiagnostic.at(new SourceLocation("f.yaml", 3, 5, ""), "and this one").describe())
          .isEqualTo("f.yaml:3:5: and this one");
    }
  }

  @Nested
  @DisplayName("loading a session")
  class Loading {

    private static final String RULES = """
        apiVersion: rules.v1
        rules:
          - id: high-value
            when:
              - fact: Order
                as: o
                where:
                  total: { gt: 10000 }
            then:
              - action: emit
                event: "order.flagged"
                payload:
                  orderId: { $ref: o.id }
        """;

    /**
     * Compiler options carrying a §2.3 schema that rejects any payload holding one key.
     *
     * <p>Which is how a load is made to fail at <em>insert</em> rather than at read: everything
     * this reader checks happens before the first insert, so a document good enough to reach the
     * session is the only way to exercise the unwind.
     *
     * @param forbidden the key whose presence is a violation
     * @return the options
     */
    private static CompilerOptions rejecting(final String forbidden) {
      return CompilerOptions.builder().factSchemas(new FactSchemas() {
        @Override
        public List<String> violations(final String factType, final JsonNode payload) {
          return payload.has(forbidden) ? List.of("'" + forbidden + "' is not allowed") : List.of();
        }

        @Override
        public Optional<SchemaType> typeOf(final String factType, final String dottedPath) {
          return Optional.empty();
        }

        @Override
        public Presence presence(final String factType, final String dottedPath) {
          return Presence.UNKNOWN;
        }
      }).build();
    }

    @Test
    @DisplayName("facts go in in document order, and the rules fire")
    void insertsAndFires() {
      final CompiledRuleSet rules = RuleFiles.compile(RuleSource.yaml("rules.yaml", RULES));
      try (RuleSession session = rules.newSession()) {
        assertThat(FactFiles.insertInto(session, FactSource.yaml("facts.yaml", YAML)))
            .describedAs("a handle per fact, in document order, so a caller can retract what it"
                + " loaded")
            .hasSize(2);
        final FireResult result = session.fireAllRules();
        assertThat(result.emitted()).singleElement()
            .satisfies(event -> assertThat(event.payload().get("orderId").stringValue())
                .isEqualTo("o1"));
      }
    }

    @Test
    @DisplayName("document order is the insertion order, which §7.3 makes part of the input")
    void documentOrderIsInsertionOrder() {
      final CompiledRuleSet rules = RuleFiles.compile(RuleSource.yaml("rules.yaml", RULES));
      try (RuleSession session = rules.newSession()) {
        FactFiles.insertInto(session, FactSource.yaml("facts.yaml", YAML));
        assertThat(session.exportFacts())
            .describedAs("exportFacts orders by handle id, so this is the order they went in")
            .extracting(fact -> fact.type())
            .containsExactly("Customer", "Order");
      }
    }

    @Test
    @DisplayName("an insert that throws part-way unwinds what already landed")
    void unwindsAPartialLoad() {
      /*
       * The document is good and the fourth fact is not: a §2.3 schema rejects it at insert, which
       * is downstream of everything this reader checks. Half a fixture is a different input rather
       * than a failed one, so the load takes back what it put in.
       */
      final CompiledRuleSet rules = RuleFiles.compile(
          List.of(RuleSource.yaml("rules.yaml", RULES)), rejecting("bad"));
      try (RuleSession session = rules.newSession()) {
        assertThatThrownBy(() -> FactFiles.insertInto(session, FactSource.yaml("facts.yaml", """
            - { type: Order, payload: { id: "o1" } }
            - { type: Order, payload: { id: "o2" } }
            - { type: Order, payload: { bad: true } }
            """)))
            .isInstanceOf(SchemaViolationException.class);
        assertThat(session.workingMemory().size())
            .describedAs("the two that landed before the failure were taken back")
            .isZero();
      }
    }

    @Test
    @DisplayName("the unwind retracts in reverse, which is what a trace shows")
    void unwindRunsBackwards() {
      final List<String> trace = new ArrayList<>();
      final CompiledRuleSet rules = RuleFiles.compile(
          List.of(RuleSource.yaml("rules.yaml", RULES)), rejecting("bad"));
      final SessionOptions options = SessionOptions.builder()
          .listener(new RuleEngineListener() {
            @Override
            public void onInsert(final Fact fact) {
              trace.add("+" + fact.payload().path("id").asString("?"));
            }

            @Override
            public void onRetract(final Fact fact) {
              trace.add("-" + fact.payload().path("id").asString("?"));
            }
          })
          .build();
      try (RuleSession session = rules.newSession(options)) {
        assertThatThrownBy(() -> FactFiles.insertInto(session, FactSource.yaml("facts.yaml", """
            - { type: Order, payload: { id: "o1" } }
            - { type: Order, payload: { id: "o2" } }
            - { type: Order, payload: { bad: true } }
            """)))
            .isInstanceOf(SchemaViolationException.class);
      }
      assertThat(trace)
          .describedAs("built up in order and taken down in the reverse of it")
          .containsExactly("+o1", "+o2", "-o2", "-o1");
    }

    @Test
    @DisplayName("a rejected document inserts nothing")
    void allOrNothing() {
      final CompiledRuleSet rules = RuleFiles.compile(RuleSource.yaml("rules.yaml", RULES));
      try (RuleSession session = rules.newSession()) {
        assertThatThrownBy(() -> FactFiles.insertInto(session, FactSource.yaml("facts.yaml", """
            - { type: Order, payload: { id: "o1", total: 12000 } }
            - { type: Order, payload: 7 }
            """)))
            .isInstanceOf(FactFileException.class);
        assertThat(session.workingMemory().size())
            .describedAs("half a fixture is a different input, not a failed one")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("what YAML does to a scalar, pinned")
  class YamlScalars {

    /**
     * Every one of these is a value an author will write meaning something else, and the answers
     * are Jackson's rather than YAML 1.1's -- {@code no} is the string "no" here, where a YAML 1.1
     * parser would hand back {@code false} and turn a country code into a boolean. Pinned because
     * it is a property of the parser this project depends on, not of YAML, so a Jackson upgrade
     * that changed it would change what every fact document means, silently.
     *
     * @param document a one-fact document whose payload holds the scalar under test
     * @param rendered what the field must read as
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @org.junit.jupiter.params.provider.CsvSource({
        "'- {type: T, payload: {v: no}}',      '\"no\"'",
        "'- {type: T, payload: {v: yes}}',     '\"yes\"'",
        "'- {type: T, payload: {v: on}}',      '\"on\"'",
        "'- {type: T, payload: {v: \"~\"}}',   '\"~\"'",
        "'- {type: T, payload: {v: 007}}',     '\"007\"'",
        "'- {type: T, payload: {v: 1.10}}',    '1.1'",
        "'- {type: T, payload: {v: true}}',    'true'",
    })
    void scalars(final String document, final String rendered) {
      assertThat(readYaml(document).getFirst().payload().get("v").toString())
          .isEqualTo(rendered);
    }

    @Test
    @DisplayName("a YAML alias does not survive, which nothing here can catch")
    void aliasesBecomeStrings() {
      /*
       * `b: *x` reads back as the string "x", not as 5. Jackson's YAML parser has flattened the
       * alias before the token stream is readable -- getObjectId() is null on every token -- so
       * this cannot be rejected, only known about. Pinned here because it is the one YAML behaviour
       * in this list that produces a wrong VALUE rather than a differently-typed literal the author
       * actually typed, and because a Jackson upgrade that fixed it should tell us.
       */
      assertThat(readYaml("""
          - type: A
            payload:
              a: &x 5
              b: *x
          """).getFirst().payload().toString())
          .isEqualTo("{\"a\":5,\"b\":\"x\"}");
    }

    @Test
    @DisplayName("a field written with no value is an explicit null, which is not an absent field")
    void emptyIsNull() {
      // §2.6.1's distinction, and the one place a YAML author trips on it: `v:` with nothing after
      // it is `{ "v": null }`, which `eq: null` matches and `hasField: false` does not. Leaving the
      // field out entirely is what "absent" means.
      final var payload = readYaml("- {type: T, payload: {v: }}").getFirst().payload();
      assertThat(payload.get("v").isNull()).isTrue();
      assertThat(payload.has("v")).isTrue();
    }
  }
}
