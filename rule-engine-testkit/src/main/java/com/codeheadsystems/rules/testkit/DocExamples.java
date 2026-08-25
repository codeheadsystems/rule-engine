package com.codeheadsystems.rules.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Lifts the rule files printed in a Markdown document out of it, so they can be compiled.
 *
 * <p>README already keeps this contract for its Java example -- "if the two disagree, the README is
 * wrong" -- and documentation for a rule DSL needs it more, not less: its whole audience is people
 * who will copy a block out of it, and a reference that has drifted from the parser is worse than no
 * reference, because it is believed.
 *
 * <p>In the testkit's main source set rather than a test, for the reason §8 gives about the testkit
 * generally: a team documenting their own rule set wants exactly this, and it is unusable from
 * outside if it lives in another module's {@code src/test}.
 */
public final class DocExamples {

  private DocExamples() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * One rule file lifted out of a document.
   *
   * @param file the document it came from
   * @param line the 1-based line the block starts on, so a failure is navigable
   * @param yaml the block's text
   */
  public record Example(String file, int line, String yaml) {

    /**
     * Where this example is, in a form a failure message can print.
     *
     * @return {@code file:line}
     */
    public String describe() {
      return file + ":" + line;
    }

    /**
     * Whether this example uses §6.4's escape hatch.
     *
     * <p>Which decides who can compile it: an expression needs a registered
     * {@code ExpressionCompiler}, and the module that provides one is not on every classpath.
     *
     * @return true when the example writes a {@code condition:} or an {@code $expr}
     */
    public boolean needsExpressionCompiler() {
      return yaml.contains("condition:") || yaml.contains("$expr");
    }
  }

  /**
   * Every complete rule file a document prints.
   *
   * <p>A fenced {@code yaml} block counts when its first line begins with {@code apiVersion:};
   * anything else is a fragment illustrating one key.
   *
   * @param path the document
   * @return the examples, in document order
   * @throws IOException if the document cannot be read
   */
  public static List<Example> in(final Path path) throws IOException {
    return blocks(path,
        body -> !body.isEmpty() && body.getFirst().strip().startsWith("apiVersion:"));
  }

  /**
   * Every fenced YAML block a document prints, complete or not.
   *
   * <p>For the documents whose YAML is <em>all</em> meant to be real -- a host-side manual printing
   * fact documents rather than fragments illustrating one key. Asserting over every block is a
   * stronger guard than collecting the ones that match a shape: a block that stops matching the
   * shape stops being checked and nothing says so, which is the hole {@link #in(Path)} closes with
   * {@link #declaredIn(Path)} and this closes by not having a filter at all.
   *
   * @param path the document
   * @return the blocks, in document order
   * @throws IOException if the document cannot be read
   */
  public static List<Example> yamlBlocksIn(final Path path) throws IOException {
    return blocks(path, body -> !body.isEmpty());
  }

  /**
   * Every fenced YAML block a document prints, filtered.
   *
   * @param path the document
   * @param keep decides whether a block's body is one the caller wants
   * @return the blocks that were kept, in document order
   * @throws IOException if the document cannot be read
   */
  private static List<Example> blocks(final Path path, final Predicate<List<String>> keep)
      throws IOException {
    final String fileName = path.getFileName().toString();
    final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    final List<Example> found = new ArrayList<>();
    int index = 0;
    while (index < lines.size()) {
      if (!lines.get(index).strip().equals("```yaml")) {
        index++;
        continue;
      }
      final int start = index + 1;
      int end = start;
      while (end < lines.size() && !lines.get(end).strip().equals("```")) {
        end++;
      }
      final List<String> body = lines.subList(start, Math.min(end, lines.size()));
      if (keep.test(body)) {
        found.add(new Example(fileName, start + 1, String.join("\n", body) + "\n"));
      }
      index = end + 1;
    }
    return found;
  }

  /**
   * How many complete rule files a document claims to contain.
   *
   * @param path the document
   * @return the number of lines starting an {@code apiVersion:} block
   * @throws IOException if the document cannot be read
   */
  public static int declaredIn(final Path path) throws IOException {
    return (int) Files.readAllLines(path, StandardCharsets.UTF_8).stream()
        .filter(line -> line.strip().startsWith("apiVersion:"))
        .count();
  }
}
