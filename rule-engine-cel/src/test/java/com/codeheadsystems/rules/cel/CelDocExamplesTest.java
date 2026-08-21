package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.DocExamples;
import com.codeheadsystems.rules.testkit.Facts;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The half of the documentation {@code DocExamplesTest} cannot compile.
 *
 * <p>Every rule file printed in the docs is a fixture, but the ones using §6.4's escape hatch need a
 * registered {@code ExpressionCompiler} -- and {@code -testkit} does not depend on this module,
 * because CEL brings protobuf, guava and antlr and none of that belongs on the classpath of a
 * harness everyone uses. So the examples are split by who can compile them, and each side asserts
 * its own count so neither can quietly stop covering its half.
 */
class CelDocExamplesTest {

  private static final Path DOCS = Path.of("..", "docs");

  private static List<DocExamples.Example> expressionExamples(final String fileName)
      throws IOException {
    return DocExamples.in(DOCS.resolve(fileName)).stream()
        .filter(DocExamples.Example::needsExpressionCompiler)
        .toList();
  }

  @Test
  @DisplayName("the guide's worked example FIRES on decimal data, not merely compiles")
  void guideExampleRunsOnRealisticData() throws IOException {
    /*
     * Compiling proves nothing about the numeric model, and that is where this feature was most
     * wrong: an integer-only fixture passed while `o.subtotal > 50` threw on any decimal subtotal.
     * A subtotal is a decimal in every real system, so the doc example is run against one.
     */
    final DocExamples.Example example = expressionExamples("dsl-guide.md").stream()
        .filter(candidate -> candidate.yaml().contains("id: interesting-order"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the guide no longer prints interesting-order"));

    final CompiledRuleSet rules = RuleFiles.compile(
        List.of(RuleSource.yaml(example.describe(), example.yaml())),
        CompilerOptions.builder().expressions(CelExpressions.create()).build());

    try (RuleSession session = rules.newSession()) {
      session.insert("Order", Facts.json("""
          {"subtotal": 50.5, "region": "US", "tier": "A", "priorityFlag": false}"""));
      session.insert("Order", Facts.json("""
          {"subtotal": 50.5, "region": "US", "tier": "C", "priorityFlag": false}"""));
      // The first satisfies the condition (tier A); the second fails its OR. Both carry a decimal
      // subtotal, which is the point: before the numeric fix, both threw.
      assertThat(session.fireAllRules().fired()).hasSize(1);
    }
  }

  @Test
  @DisplayName("every escape-hatch example in the reference compiles with a CEL compiler")
  void referenceExpressionExamplesCompile() throws IOException {
    final List<DocExamples.Example> examples = expressionExamples("dsl-reference.md");

    assertThat(examples)
        .as("the reference documents the escape hatch; if it stops, this test should be removed")
        .isNotEmpty();

    for (final DocExamples.Example example : examples) {
      assertThatCode(() -> RuleFiles.compile(
          List.of(RuleSource.yaml(example.describe(), example.yaml())),
          CompilerOptions.builder().expressions(CelExpressions.create()).build()))
          .as("the rule file printed at %s does not compile:%n%s",
              example.describe(), example.yaml())
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("every escape-hatch example in the guide compiles too")
  void guideExpressionExamplesCompile() throws IOException {
    final List<DocExamples.Example> examples = expressionExamples("dsl-guide.md");

    assertThat(examples)
        .as("the guide documents the escape hatch; without this the loop below passes vacuously")
        .isNotEmpty();

    for (final DocExamples.Example example : examples) {
      assertThatCode(() -> RuleFiles.compile(
          List.of(RuleSource.yaml(example.describe(), example.yaml())),
          CompilerOptions.builder().expressions(CelExpressions.create()).build()))
          .as("the rule file printed at %s does not compile:%n%s",
              example.describe(), example.yaml())
          .doesNotThrowAnyException();
    }
  }
}
