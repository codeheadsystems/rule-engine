package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Which modules go to Maven Central, and which deliberately do not.
 *
 * <p><strong>This is {@link ApiSurfaceTest}'s shape applied to a second irreversible list.</strong>
 * That test exists because a module missing from its table was never checked while the suite stayed
 * green; the publish set has exactly the same failure mode and, until this file, no guard. A new
 * module that forgets {@code buildlogic.publish-conventions} silently ships nothing -- consumers get
 * a {@code NoClassDefFoundError} rather than a resolution failure, because a sibling module's
 * {@code api} dependency still names it. One that adds the plugin without anybody deciding ships a
 * permanent artifact under this project's coordinates.
 *
 * <p>Read off the build files rather than off a list here, for the reason {@code ApiSurfaceTest}
 * gives about its own module list: a table checked against itself cannot fail. The build files are
 * declared as task inputs in {@code rule-engine-testkit/build.gradle.kts}, or Gradle would mark this
 * task {@code UP-TO-DATE} and the guard would check the previous build's answer.
 */
class PublishedModulesTest {

  /**
   * The modules that are published. Changing this list is the decision; the tests are the check.
   *
   * <p><strong>Its size is also written down in {@code .github/workflows/release.yml}</strong>, as
   * the {@code EXPECTED} of the pre-flight step that refuses to upload unless that many modules come
   * back signed. There is no way to derive one from the other -- the point of that check is to have
   * an independent count -- so adding a module here means editing that number too, or the release
   * job fails at the last gate with "expected 7, found 8" while every test stays green.
   */
  private static final Set<String> PUBLISHED = Set.of(
      "rule-engine-core",
      "rule-engine-compiler",
      "rule-engine-dsl",
      "rule-engine-schema",
      "rule-engine-cel",
      "rule-engine-observability",
      "rule-engine-testkit");

  /**
   * The modules deliberately not published, each with the reason it is not.
   *
   * <p>Listed rather than inferred as "everything else", so that a new module is unclassified until
   * somebody says which it is -- the same reason {@code ApiSurfaceTest} lists its internal packages
   * instead of deriving them.
   */
  private static final Set<String> NOT_PUBLISHED = Set.of(
      // A worked application, not a library. An artifact on Central is a promise to keep something
      // compiling for whoever depends on it, and nobody should be depending on the example.
      "rule-engine-example");

  /** Where the module list actually lives. */
  private static final Path SETTINGS = Path.of("..", "settings.gradle.kts");

  /**
   * Every module in the build, read off {@code settings.gradle.kts}.
   *
   * @return the module directory names
   */
  private static Set<String> modules() {
    // Anchored to the line start, so an `include` written inside that heavily commented file's
    // comments is not read as a module.
    final Matcher matcher = Pattern.compile("(?m)^include\\(\"(rule-engine-[\\w-]+)\"\\)")
        .matcher(read(SETTINGS));
    final Set<String> found = new TreeSet<>();
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }

  /**
   * The modules whose build file applies the publishing convention.
   *
   * @return the module directory names
   */
  private static Set<String> applyingThePlugin() {
    /*
     * Anchored to the line, not a substring search, for the reason ApiSurfaceTest anchors its own
     * `include(...)` regex: these build files are heavily commented, and a comment reading "we
     * deliberately do not apply buildlogic.publish-conventions" would otherwise read as applying it.
     * That is the exact failure this file exists to prevent, arriving through the file itself.
     */
    final Pattern applied = Pattern.compile(
        "(?m)^\\s*id\\(\"buildlogic\\.publish-conventions\"\\)");
    final Set<String> found = new TreeSet<>();
    for (final String module : modules()) {
      final Path build = Path.of("..", module, "build.gradle.kts");
      if (Files.isRegularFile(build) && applied.matcher(read(build)).find()) {
        found.add(module);
      }
    }
    return found;
  }

  /**
   * Reads a file.
   *
   * @param path the file
   * @return its text
   */
  private static String read(final Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read " + path, failed);
    }
  }

  @Nested
  @DisplayName("the publish set")
  class PublishSet {

    @Test
    @DisplayName("says something about every module in the build")
    void everyModuleIsClassified() {
      final Set<String> classified = new TreeSet<>(PUBLISHED);
      classified.addAll(NOT_PUBLISHED);

      assertThat(modules())
          .describedAs("a module this test does not classify is one nobody decided about."
              + " PUBLISHED if consumers should be able to depend on it -- which is a promise for"
              + " the life of the artifact -- and NOT_PUBLISHED, with the reason, if they should"
              + " not")
          .allSatisfy(module -> assertThat(classified)
              .describedAs("module '%s'", module)
              .contains(module));
    }

    @Test
    @DisplayName("says only one thing about each")
    void nothingIsBothPublishedAndNot() {
      assertThat(PUBLISHED)
          .describedAs("a module on both lists. Decide which it is")
          .doesNotContainAnyElementsOf(NOT_PUBLISHED);
    }

    @Test
    @DisplayName("matches what the build files actually do")
    void theBuildAgreesWithTheList() {
      /*
       * The half that makes the rest mean anything: PUBLISHED is a claim about the build, and this
       * is where it is checked against the build rather than against itself. A module can only
       * publish by applying the convention plugin -- that is the single switch -- so this comparison
       * is exhaustive in both directions at once.
       */
      assertThat(applyingThePlugin())
          .describedAs("the modules applying buildlogic.publish-conventions are not the modules this"
              + " test says are published. Either the build file changed without the decision, or"
              + " the decision changed without the build file")
          .isEqualTo(new TreeSet<>(PUBLISHED));
    }

    @Test
    @DisplayName("reads a module list that is not empty, so the comparison is not vacuous")
    void theScanFindsSomething() {
      // The failure this suite would otherwise have: a moved settings file, or a changed `include`
      // spelling, leaves every set empty and every assertion above passing.
      assertThat(modules())
          .describedAs("no modules found -- has settings.gradle.kts moved?")
          .hasSizeGreaterThanOrEqualTo(PUBLISHED.size() + NOT_PUBLISHED.size());
    }
  }
}
