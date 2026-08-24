package com.codeheadsystems.rules.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.codeheadsystems.rules.cel.CelExpressions;
import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.testkit.DocExamples;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every rule file printed in this module's README, compiled.
 *
 * <p>The same contract {@code DocExamplesTest} holds the three root documents to, applied here
 * because this page's whole audience is people who will copy a block out of it. A reference that has
 * drifted from the parser is worse than none, because it is believed.
 *
 * <p>The README is declared as a task input in this module's {@code build.gradle.kts}. Without that
 * Gradle cannot see the dependency -- the file is read at runtime rather than through the classpath
 * -- so the task goes {@code UP-TO-DATE} and the guard silently checks the previous build's answer.
 */
class ReadmeExamplesTest {

  /** Relative to this module's directory, which Gradle uses as the test working directory. */
  private static final Path README = Path.of("README.md");

  @Test
  @DisplayName("every rule file it prints compiles")
  void examplesCompile() throws IOException {
    final List<DocExamples.Example> examples = DocExamples.in(README);

    /*
     * Two assertions before the loop, and both exist because a collector that quietly stops
     * collecting passes every test after it. The floor catches the fence convention changing
     * wholesale; the equality against the file's own `apiVersion:` count catches one new block
     * going unnoticed.
     */
    assertThat(examples)
        .as("no complete rule files found in README.md -- has the fence convention changed?")
        .hasSizeGreaterThanOrEqualTo(2);
    assertThat(examples)
        .as("README.md contains rule files this test did not collect")
        .hasSize(DocExamples.declaredIn(README));

    /*
     * With the expression compiler registered, unlike DocExamplesTest -- this module already
     * depends on -cel, so there is no reason to split the fixtures into two suites the way the root
     * documents have to.
     */
    final CompilerOptions options = CompilerOptions.builder()
        .expressions(CelExpressions.create())
        .build();
    for (final DocExamples.Example example : examples) {
      assertThatCode(() -> RuleFiles.compile(
          List.of(RuleSource.yaml(example.describe(), example.yaml())), options))
          .as("the rule file printed at %s does not compile:%n%s",
              example.describe(), example.yaml())
          .doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("the rules it names are the rules the rule file actually has")
  void namedRulesExist() throws IOException {
    /*
     * The README's table lists seven rules by id. A rename in orders.yaml that leaves the table
     * behind is exactly the drift the fixture check above cannot see -- those blocks compile
     * whatever the real file is called.
     */
    final String readme = Files.readString(README, StandardCharsets.UTF_8);
    final List<String> ids = OrderRules.compile().rules().stream()
        .map(rule -> rule.source().id())
        .toList();

    assertThat(ids).hasSize(7).allSatisfy(id ->
        assertThat(readme).as("README.md does not mention rule '%s'", id).contains("`" + id + "`"));
  }
}
