package com.codeheadsystems.rules.dsl;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A rule file could not be turned into rules, carrying every diagnostic rather than only the first.
 *
 * <p>The same contract {@code RuleCompilationException} keeps, and for the same reason it gives: a
 * rule set is edited as a batch, and one-error-at-a-time turns a five-minute fix into five round
 * trips. It matters more here than there, because the audience for this exception is the author
 * §9's Phase 5 criterion describes -- somebody writing YAML who never opens a Java file.
 */
public final class RuleFileException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * The diagnostics, in the order they were found.
   *
   * <p>Transient for the reason {@code RuleCompilationException}'s are: {@code List} is not
   * declared serializable, and this exception is handled in the JVM that threw it. The message
   * already carries every diagnostic, so a serialisation boundary loses nothing that matters.
   */
  private final transient List<DslDiagnostic> diagnostics;

  /**
   * Creates the exception.
   *
   * @param diagnostics every problem found, in discovery order
   */
  public RuleFileException(final List<DslDiagnostic> diagnostics) {
    super(render(diagnostics));
    this.diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Creates the exception, keeping the failure it was built from.
   *
   * @param diagnostics every problem found, in discovery order
   * @param cause the underlying failure, typically a {@code RuleCompilationException} whose
   *     diagnostics these are. Retained rather than swallowed: a caller that already handles
   *     compilation failures should still be able to see one
   */
  public RuleFileException(final List<DslDiagnostic> diagnostics, final Throwable cause) {
    super(render(diagnostics), cause);
    this.diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Every problem found.
   *
   * @return the diagnostics, in discovery order
   */
  public List<DslDiagnostic> diagnostics() {
    return diagnostics;
  }

  /**
   * Builds the message.
   *
   * @param diagnostics the problems
   * @return one line per diagnostic, under a summary
   */
  private static String render(final List<DslDiagnostic> diagnostics) {
    return diagnostics.stream()
        .map(DslDiagnostic::describe)
        .collect(Collectors.joining(
            System.lineSeparator() + "  - ",
            "rule file is not valid:" + System.lineSeparator() + "  - ",
            ""));
  }
}
