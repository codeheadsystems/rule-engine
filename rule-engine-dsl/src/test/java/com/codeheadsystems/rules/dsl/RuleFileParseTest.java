package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reading a rule file into the intermediate document (spec §6.1, §6.5).
 *
 * <p>The headline assertion is parity. §6.1's whole argument for one parser is that the two
 * serializations differ by a factory choice and by nothing else, and an argument of that shape is
 * either tested or merely believed.
 */
class RuleFileParseTest {

  /** §6.2's worked example, in YAML. */
  private static final String YAML = """
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

  /** The identical rule, in JSON. */
  private static final String JSON = """
      {
        "apiVersion": "rules.v1",
        "rules": [
          {
            "id": "high-value-order-review",
            "salience": 10,
            "noLoop": true,
            "when": [
              {
                "fact": "Order",
                "as": "o",
                "where": {
                  "total":  { "gt": 10000 },
                  "status": { "eq": "PENDING" }
                }
              },
              {
                "fact": "Customer",
                "as": "c",
                "where": {
                  "id":       { "eq": { "$ref": "o.customerId" } },
                  "riskTier": { "in": ["HIGH", "MEDIUM"] }
                }
              }
            ],
            "then": [
              { "action": "setField", "target": "o", "field": "status", "value": "REVIEW" },
              {
                "action": "emit",
                "event": "order.flagged",
                "payload": {
                  "orderId": { "$ref": "o.id" },
                  "reason": "high value + risk tier"
                }
              }
            ]
          }
        ]
      }
      """;

  private final List<DslDiagnostic> diagnostics = new ArrayList<>();

  private RuleFileReader.Parsed read(final RuleSource source) {
    final Optional<RuleFileReader.Parsed> parsed = RuleFileReader.read(source, diagnostics);
    assertThat(diagnostics).isEmpty();
    assertThat(parsed).isPresent();
    return parsed.orElseThrow();
  }

  @Nested
  @DisplayName("the two serializations")
  class Parity {

    @Test
    @DisplayName("bind to the same document, which is the whole of §6.1's claim")
    void jsonAndYamlAgree() {
      final RuleFileDocument fromYaml = read(RuleSource.yaml("rules.yaml", YAML)).document();
      final RuleFileDocument fromJson = read(RuleSource.json("rules.json", JSON)).document();

      assertThat(fromYaml).isEqualTo(fromJson);
    }

    @Test
    @DisplayName("bind every field of the worked example, not merely an equal subset")
    void documentIsFullyBound() {
      final RuleFileDocument document = read(RuleSource.yaml("rules.yaml", YAML)).document();

      assertThat(document.apiVersion()).isEqualTo("rules.v1");
      assertThat(document.rules()).hasSize(1);

      final RuleNode rule = document.rules().getFirst();
      assertThat(rule.id()).isEqualTo("high-value-order-review");
      assertThat(rule.salience()).isEqualTo(10);
      assertThat(rule.noLoop()).isTrue();
      assertThat(rule.agendaGroup()).isNull();
      assertThat(rule.tags()).isEmpty();

      assertThat(rule.when()).hasSize(2);
      final WhenNode order = rule.when().getFirst();
      assertThat(order.fact()).isEqualTo("Order");
      assertThat(order.as()).isEqualTo("o");
      assertThat(order.condition()).isNull();
      assertThat(order.where()).containsOnlyKeys("total", "status");
      assertThat(order.where().get("total").get("gt").intValue()).isEqualTo(10_000);

      assertThat(rule.then()).hasSize(2);
      final ThenNode emit = rule.then().get(1);
      assertThat(emit.action()).isEqualTo("emit");
      assertThat(emit.event()).isEqualTo("order.flagged");
      assertThat(emit.payload()).containsOnlyKeys("orderId", "reason");
      assertThat(emit.payload().get("orderId").get("$ref").textValue()).isEqualTo("o.id");
    }
  }

  @Nested
  @DisplayName("document order")
  class Ordering {

    @Test
    @DisplayName("survives into the where map, because constraint order reaches the content hash")
    void whereKeysKeepDocumentOrder() {
      final RuleFileDocument document = read(RuleSource.yaml("rules.yaml", YAML)).document();

      assertThat(document.rules().getFirst().when().getFirst().where().keySet())
          .containsExactly("total", "status");
      assertThat(document.rules().getFirst().when().get(1).where().keySet())
          .containsExactly("id", "riskTier");
    }

    @Test
    @DisplayName("survives into then payloads, which §4.6 applies in declaration order")
    void payloadKeysKeepDocumentOrder() {
      final RuleFileDocument document = read(RuleSource.yaml("rules.yaml", YAML)).document();

      assertThat(document.rules().getFirst().then().get(1).payload().keySet())
          .containsExactly("orderId", "reason");
    }

    @Test
    @DisplayName("is not disturbed by the map being unmodifiable")
    void whereMapIsUnmodifiable() {
      final RuleFileDocument document = read(RuleSource.yaml("rules.yaml", YAML)).document();

      assertThat(document.rules().getFirst().when().getFirst().where())
          .isUnmodifiable();
    }
  }

  @Nested
  @DisplayName("the source index")
  class Locations {

    @Test
    @DisplayName("puts a YAML operator map on the line it is written on")
    void yamlLineNumbers() {
      final SourceIndex index = read(RuleSource.yaml("rules.yaml", YAML)).index();

      // 'total: { gt: 10000 }' is line 10 of the block above, 1-based.
      assertThat(index.nearest("/rules/0/when/0/where/total").line()).isEqualTo(10);
      assertThat(index.nearest("/rules/0/when/0/where/status").line()).isEqualTo(11);
      assertThat(index.nearest("/rules/0/when/0/where/total").file()).isEqualTo("rules.yaml");
    }

    @Test
    @DisplayName("puts a JSON operator map on the line it is written on")
    void jsonLineNumbers() {
      final SourceIndex index = read(RuleSource.json("rules.json", JSON)).index();

      // '"total":  { "gt": 10000 },' is line 13 of the block above, 1-based.
      assertThat(index.nearest("/rules/0/when/0/where/total").line()).isEqualTo(13);
      assertThat(index.nearest("/rules/0/when/0/where/status").line()).isEqualTo(14);
    }

    @Test
    @DisplayName("falls back to the nearest indexed ancestor rather than losing the line")
    void nearestAncestorWins() {
      final SourceIndex index = read(RuleSource.yaml("rules.yaml", YAML)).index();

      // Nothing indexes this pointer; the enclosing operator map is close enough to navigate by.
      final SourceLocation location =
          index.nearest("/rules/0/when/0/where/total/gt/nonexistent/deeper");

      assertThat(location.line()).isEqualTo(10);
    }

    @Test
    @DisplayName("degrades to the file itself for a pointer with no indexed ancestor at all")
    void unknownPointerStillNamesTheFile() {
      final SourceIndex index = SourceIndex.of(RuleSource.yaml("empty.yaml", ""));

      assertThat(index.nearest("/rules/0").file()).isEqualTo("empty.yaml");
    }
  }

  @Nested
  @DisplayName("a file that will not read")
  class Failures {

    @Test
    @DisplayName("reports malformed YAML with a location instead of a Jackson stack trace")
    void malformedYaml() {
      final Optional<RuleFileReader.Parsed> parsed = RuleFileReader.read(
          RuleSource.yaml("broken.yaml", "apiVersion: rules.v1\nrules:\n  - id: [unclosed\n"),
          diagnostics);

      assertThat(parsed).isEmpty();
      assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
        assertThat(diagnostic.error()).isEqualTo(DslError.MALFORMED_DOCUMENT);
        assertThat(diagnostic.describe()).contains("broken.yaml");
      });
    }

    @Test
    @DisplayName("reports malformed JSON with a location")
    void malformedJson() {
      final Optional<RuleFileReader.Parsed> parsed = RuleFileReader.read(
          RuleSource.json("broken.json", "{\"apiVersion\": \"rules.v1\","), diagnostics);

      assertThat(parsed).isEmpty();
      assertThat(diagnostics).singleElement()
          .extracting(DslDiagnostic::error).isEqualTo(DslError.MALFORMED_DOCUMENT);
    }

    @Test
    @DisplayName("rejects a repeated key rather than silently keeping the last one")
    void duplicateKeyInYaml() {
      final Optional<RuleFileReader.Parsed> parsed = RuleFileReader.read(
          RuleSource.yaml("dup.yaml", """
              apiVersion: rules.v1
              rules:
                - id: dup
                  when:
                    - fact: Order
                      as: o
                      where:
                        status: { eq: "PENDING" }
                        status: { ne: "CLOSED" }
                  then: [{ action: emit, event: e }]
              """), diagnostics);

      assertThat(parsed).isEmpty();
      assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
        assertThat(diagnostic.error()).isEqualTo(DslError.MALFORMED_DOCUMENT);
        assertThat(diagnostic.message()).contains("status");
      });
    }

    @Test
    @DisplayName("rejects a repeated key in JSON too, where the default is equally silent")
    void duplicateKeyInJson() {
      final Optional<RuleFileReader.Parsed> parsed = RuleFileReader.read(
          RuleSource.json("dup.json",
              "{\"apiVersion\":\"rules.v1\",\"apiVersion\":\"rules.v1\",\"rules\":[]}"),
          diagnostics);

      assertThat(parsed).isEmpty();
      assertThat(diagnostics).singleElement()
          .extracting(DslDiagnostic::error).isEqualTo(DslError.MALFORMED_DOCUMENT);
    }

    @Test
    @DisplayName("says an empty file is empty, rather than binding a document with no rules")
    void emptyFile() {
      final Optional<RuleFileReader.Parsed> parsed =
          RuleFileReader.read(RuleSource.yaml("empty.yaml", ""), diagnostics);

      assertThat(parsed).isEmpty();
      assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
        assertThat(diagnostic.error()).isEqualTo(DslError.SCHEMA_VIOLATION);
        assertThat(diagnostic.message()).contains("empty");
      });
    }
  }

  @Nested
  @DisplayName("reading a file from disk")
  class FromDisk {

    @org.junit.jupiter.api.Test
    @DisplayName("takes its format from the extension and its name from the file")
    void readsAndNamesTheFile(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
        throws java.io.IOException {
      final java.nio.file.Path file = dir.resolve("orders.yaml");
      java.nio.file.Files.writeString(file, YAML);

      final RuleSource source = RuleSource.of(file);

      assertThat(source.format()).isEqualTo(RuleFormat.YAML);
      assertThat(source.name()).isEqualTo("orders.yaml");
      assertThat(read(source).document().rules()).hasSize(1);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("refuses an extension it cannot map, rather than guessing a parser")
    void unknownExtensionRefused(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
        throws java.io.IOException {
      final java.nio.file.Path file = dir.resolve("orders.txt");
      java.nio.file.Files.writeString(file, YAML);

      org.assertj.core.api.Assertions.assertThatThrownBy(() -> RuleSource.of(file))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("orders.txt");
    }
  }

  @Nested
  @DisplayName("format detection")
  class Formats {

    @Test
    @DisplayName("reads an extension it knows")
    void knownExtensions() {
      assertThat(RuleFormat.forFileName("rules.yaml")).contains(RuleFormat.YAML);
      assertThat(RuleFormat.forFileName("rules.yml")).contains(RuleFormat.YAML);
      assertThat(RuleFormat.forFileName("/etc/rules/orders.JSON")).contains(RuleFormat.JSON);
    }

    @Test
    @DisplayName("declines one it does not, rather than guessing")
    void unknownExtension() {
      assertThat(RuleFormat.forFileName("rules.txt")).isEmpty();
    }
  }
}
