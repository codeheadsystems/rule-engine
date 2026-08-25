package com.codeheadsystems.rules.dsl;

import java.io.Serial;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A fact document could not be turned into facts, carrying every problem rather than only the
 * first.
 *
 * <p>The same contract {@link RuleFileException} keeps, for the same reason: a document is edited
 * as a batch, and one-error-at-a-time turns a five-minute fix into five round trips. It matters as
 * much here as there, because the file this is thrown for is usually a fixture somebody is holding
 * a rule set to -- so the question being asked is "why will my test data not load", and every
 * answer at once is the useful shape of that reply.
 *
 * <p><strong>Nothing is inserted when this is thrown.</strong> The whole document is read and
 * validated before a single fact reaches a session, so a rejected document leaves working memory
 * exactly as it was. Loading half a fixture and then failing would leave a session in a state no
 * rule set was written against, and §7.3's determinism guarantee is stated over the facts that were
 * inserted -- a partial load is a different input, not a failed one.
 */
public final class FactFileException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * The problems, in the order they were found.
   *
   * <p>Transient for the reason {@link RuleFileException}'s are: {@code List} is not declared
   * serializable, and this exception is handled in the JVM that threw it. The message already
   * carries every problem, so a serialisation boundary loses nothing that matters.
   */
  private final transient List<FactDiagnostic> diagnostics;

  /**
   * Creates the exception.
   *
   * @param diagnostics every problem found, in discovery order
   */
  public FactFileException(final List<FactDiagnostic> diagnostics) {
    super(render(diagnostics));
    this.diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Creates the exception, keeping the failure it was built from.
   *
   * @param diagnostics every problem found, in discovery order
   * @param cause the underlying failure, typically the Jackson exception that ended the parse.
   *     Retained rather than swallowed: a caller debugging an encoding or a truncated file wants
   *     the parser's own account of it
   */
  public FactFileException(final List<FactDiagnostic> diagnostics, final Throwable cause) {
    super(render(diagnostics), cause);
    this.diagnostics = List.copyOf(diagnostics);
  }

  /**
   * Every problem found.
   *
   * @return the diagnostics, in discovery order
   */
  public List<FactDiagnostic> diagnostics() {
    return diagnostics;
  }

  /**
   * Builds the message.
   *
   * @param diagnostics the problems
   * @return one line per problem, under a summary
   */
  private static String render(final List<FactDiagnostic> diagnostics) {
    return diagnostics.stream()
        .map(FactDiagnostic::describe)
        .collect(Collectors.joining(
            System.lineSeparator() + "  - ",
            "fact document is not valid:" + System.lineSeparator() + "  - ",
            ""));
  }
}
