package com.codeheadsystems.rules.compiler;

import java.io.Serial;
import java.util.List;

/**
 * Compilation failed, carrying every diagnostic rather than only the first.
 *
 * <p>Reporting all of them matters for the same reason a compiler reports all of them: a rule set
 * is edited as a batch, and one-error-at-a-time turns a five-minute fix into five round trips.
 */
public final class RuleCompilationException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * The diagnostics, in the order they were found.
   *
   * <p>Transient because {@code List} is not declared serializable and this exception is never
   * serialised in practice: it is thrown during compilation, in the same JVM that will handle it.
   * The message already carries every diagnostic, so nothing is lost if it ever crosses a
   * serialisation boundary.
   */
  private final transient List<String> diagnostics;

  /**
   * Creates the exception.
   *
   * @param diagnostics every problem found, in discovery order
   */
  public RuleCompilationException(final List<String> diagnostics) {
    super("rule compilation failed:" + System.lineSeparator() + "  - "
        + String.join(System.lineSeparator() + "  - ", diagnostics));
    this.diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Every problem found.
   *
   * @return the diagnostics, in discovery order
   */
  public List<String> diagnostics() {
    return diagnostics;
  }
}
