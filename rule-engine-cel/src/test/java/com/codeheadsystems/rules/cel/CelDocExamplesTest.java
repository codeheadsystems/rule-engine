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
 *
 * <p>That split reaches README as well as {@code docs/}: README prints a {@code $expr} example
 * because without one a reader concludes an action can only set a constant. {@code DocExamplesTest}
 * filters it out for the reason above, so this class is the only thing that compiles it.
 */
class CelDocExamplesTest {

  private static final Path DOCS = Path.of("..", "docs");

  /** The project root, because README prints an escape-hatch example too. */
  private static final Path ROOT = Path.of("..");

  private static List<DocExamples.Example> expressionExamples(final String fileName)
      throws IOException {
    return expressionExamplesIn(DOCS.resolve(fileName));
  }

  private static List<DocExamples.Example> expressionExamplesIn(final Path path)
      throws IOException {
    return DocExamples.in(path).stream()
        .filter(DocExamples.Example::needsExpressionCompiler)
        .toList();
  }

  private static void assertEveryExpressionExampleCompiles(
      final List<DocExamples.Example> examples) {
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

    assertEveryExpressionExampleCompiles(examples);
  }

  @Test
  @DisplayName("every escape-hatch example in the guide compiles too")
  void guideExpressionExamplesCompile() throws IOException {
    final List<DocExamples.Example> examples = expressionExamples("dsl-guide.md");

    assertThat(examples)
        .as("the guide documents the escape hatch; without this the loop below passes vacuously")
        .isNotEmpty();

    assertEveryExpressionExampleCompiles(examples);
  }

  @Test
  @DisplayName("the escape-hatch example README prints compiles, which nothing else checks")
  void readmeExpressionExamplesCompile() throws IOException {
    /*
     * README shows a `$expr` on the right-hand side because without one a reader concludes an
     * action can only set a constant -- which silently disqualifies the engine for anything that
     * has to compute. That example needs the same guard as every other rule file in the docs, and
     * DocExamplesTest cannot be it: -testkit does not depend on this module, so it filters the
     * example out as needing a compiler it does not have. Without this test README would print the
     * one rule file in the repository that nothing compiles.
     */
    final List<DocExamples.Example> examples = expressionExamplesIn(ROOT.resolve("README.md"));

    assertThat(examples)
        .as("README no longer prints an expression example; if that is deliberate, delete this "
            + "test rather than leaving it to pass vacuously")
        .isNotEmpty();

    assertEveryExpressionExampleCompiles(examples);
  }
}
