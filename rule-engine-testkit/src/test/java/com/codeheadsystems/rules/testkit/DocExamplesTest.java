package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every rule file printed in the documentation, compiled.
 *
 * <p>README already holds this contract for its Java example -- "This is {@code
 * SmokeTest.readmeExample}. If the two disagree, the README is wrong." The DSL documentation needs
 * it more, not less: its whole audience is people who will copy a block out of it, and a reference
 * page that has drifted from the parser is worse than no reference page, because it is believed.
 *
 * <p>A fenced {@code yaml} block is treated as a complete rule file when it begins with
 * {@code apiVersion:}. Anything else is a fragment illustrating one key, and is skipped -- with a
 * floor on the number of blocks actually checked, so that a change to the fence convention shows up
 * as a failure rather than as a test that quietly stopped testing anything.
 */
class DocExamplesTest {

  /** Where the docs live, relative to this module's directory, which Gradle uses as the test cwd. */
  private static final Path DOCS = Path.of("..", "docs");

  /** The project root, for README, which makes the same promise its Java example does. */
  private static final Path ROOT = Path.of("..");

  private static List<DocExamples.Example> examplesIn(final String fileName) throws IOException {
    final Path directory = fileName.equals("README.md") ? ROOT : DOCS;
    final Path path = directory.resolve(fileName);
    assertThat(path).as("the documentation moved; this test needs to move with it").exists();
    return DocExamples.in(path);
  }

  private static void assertEveryExampleCompiles(final String fileName, final int atLeast)
      throws IOException {
    final List<DocExamples.Example> all = examplesIn(fileName);
    /*
     * Examples using §6.4's escape hatch need a registered ExpressionCompiler, and -testkit does
     * not depend on -cel: that module brings protobuf, guava and antlr, which have no business on
     * the classpath of every consumer of this harness. CelDocExamplesTest compiles exactly those,
     * and the two counts are asserted against each other below so neither side can quietly stop
     * covering its half.
     */
    final List<DocExamples.Example> examples = all.stream()
        .filter(example -> !example.needsExpressionCompiler())
        .toList();

    /*
     * A floor rather than an exact count, so adding an example does not fail the build -- but a
     * floor is only a guard against the fence convention changing wholesale. It will not notice one
     * NEW block that quietly stops being collected, so the count is asserted against the number of
     * 'apiVersion:' lines the file actually contains: every one of them must have been picked up.
     */
    assertThat(examples)
        .as("no complete rule files found in %s -- has the fence convention changed?", fileName)
        .hasSizeGreaterThanOrEqualTo(atLeast);
    assertThat(all)
        .as("%s contains rule files this test did not collect", fileName)
        .hasSize(DocExamples.declaredIn(
            (fileName.equals("README.md") ? ROOT : DOCS).resolve(fileName)));

    for (final DocExamples.Example example : examples) {
      assertThatCode(() -> RuleFiles.compile(RuleSource.yaml(example.describe(), example.yaml())))
          .as("the rule file printed at %s does not compile:%n%s",
              example.describe(), example.yaml())
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("docs/dsl-reference.md")
  class Reference {

    @Test
    @DisplayName("every rule file it prints compiles")
    void examplesCompile() throws IOException {
      assertEveryExampleCompiles("dsl-reference.md", 7);
    }

    @Test
    @DisplayName("documents every diagnostic code the DSL can raise")
    void diagnosticCatalogueIsComplete() throws IOException {
      final String text =
          Files.readString(DOCS.resolve("dsl-reference.md"), StandardCharsets.UTF_8);

      for (final com.codeheadsystems.rules.dsl.DslError error
          : com.codeheadsystems.rules.dsl.DslError.values()) {
        assertThat(text)
            .as("the reference does not document the '%s' diagnostic", error.code())
            .contains("`" + error.code() + "`");
      }
    }

    @Test
    @DisplayName("documents every operator in §6.2.1's table")
    void operatorTableIsComplete() throws IOException {
      final String text =
          Files.readString(DOCS.resolve("dsl-reference.md"), StandardCharsets.UTF_8);

      assertThat(List.of("eq", "ne", "gt", "gte", "lt", "lte", "between", "in", "notIn",
              "matches", "hasField", "isNull"))
          .allSatisfy(operator -> assertThat(text)
              .as("the reference does not document the '%s' operator", operator)
              .contains("| `" + operator + "` |"));
    }
  }

  @Nested
  @DisplayName("README.md")
  class Readme {

    @Test
    @DisplayName("the rule file it prints compiles, as its Java example already promises")
    void examplesCompile() throws IOException {
      assertEveryExampleCompiles("README.md", 1);
    }

    @Test
    @DisplayName("and is the same rule as the Java example beside it, down to the version hash")
    void readmeYamlMatchesReadmeJava() throws IOException {
      final DocExamples.Example yaml = examplesIn("README.md").stream()
          .filter(example -> example.yaml().contains("id: high-value-order-review"))
          .findFirst()
          .orElseThrow(() -> new AssertionError("README no longer prints the worked rule file"));

      DslEquivalence.assertEquivalent(
          com.codeheadsystems.rules.dsl.RuleSource.yaml(yaml.describe(), yaml.yaml()),
          List.of(Rules.rule("high-value-order-review")
              .salience(10)
              .noLoop()
              .when("o", "Order", pattern -> pattern.gt("total", 10000).eq("status", "PENDING"))
              .when("c", "Customer", pattern -> pattern
                  .ref("id", "o.customerId").in("riskTier", "HIGH", "MEDIUM"))
              .then(actions -> actions
                  .setField("o", "status", "REVIEW")
                  .emit("order.flagged",
                      "orderId", Rules.ref("o.id"),
                      "reason", "high value + risk tier"))
              .build()),
          session -> {
            session.insert("Order", Facts.json("""
                {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
            session.insert("Customer", Facts.json("""
                {"id": 7, "riskTier": "HIGH"}"""));
          });
    }
  }

  @Nested
  @DisplayName("docs/dsl-guide.md")
  class Guide {

    @Test
    @DisplayName("every rule file it prints compiles")
    void examplesCompile() throws IOException {
      assertEveryExampleCompiles("dsl-guide.md", 4);
    }
  }

  @Nested
  @DisplayName("the guide's worked example")
  class WorkedExample {

    @Test
    @DisplayName("fires and emits exactly what the guide says it does")
    void guideExampleBehavesAsDocumented() throws IOException {
      final DocExamples.Example joined = examplesIn("dsl-guide.md").stream()
          .filter(example -> example.yaml().contains("id: high-value-order-review"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "the guide no longer contains the high-value-order-review example"));

      final FiringSequence fired = Engine.run(
          RuleFiles.compile(RuleSource.yaml(joined.describe(), joined.yaml())),
          com.codeheadsystems.rules.session.SessionOptions.defaults(),
          session -> {
            session.insert("Order", Facts.json("""
                {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
            session.insert("Customer", Facts.json("""
                {"id": 7, "riskTier": "HIGH"}"""));
          });

      assertThat(fired.steps()).singleElement().satisfies(step -> {
        assertThat(step.ruleId()).isEqualTo("high-value-order-review");
        assertThat(step.emitted()).singleElement().satisfies(event -> {
          assertThat(event).contains("order.flagged");
          assertThat(event).contains("high value + risk tier");
        });
      });
    }

    @Test
    @DisplayName("the flattening example finds the bulk line item and not the small one")
    void flatteningExampleBehavesAsDocumented() throws IOException {
      final DocExamples.Example bulk = examplesIn("dsl-guide.md").stream()
          .filter(example -> example.yaml().contains("id: bulk-line-item"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "the guide no longer contains the bulk-line-item example"));

      final FiringSequence fired = Engine.run(
          RuleFiles.compile(RuleSource.yaml(bulk.describe(), bulk.yaml())),
          com.codeheadsystems.rules.session.SessionOptions.defaults(),
          session -> {
            session.insert("Order", Facts.json("{\"id\": 1}"));
            session.insert("LineItem", Facts.json("""
                {"orderId": 1, "sku": "A", "qty": 20}"""));
            session.insert("LineItem", Facts.json("""
                {"orderId": 1, "sku": "B", "qty": 2}"""));
          });

      assertThat(fired.steps()).singleElement()
          .satisfies(step -> assertThat(step.emitted().getFirst()).contains("\"A\""));
    }
  }
}
