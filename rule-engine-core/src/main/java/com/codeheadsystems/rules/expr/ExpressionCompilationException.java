package com.codeheadsystems.rules.expr;

import java.io.Serial;

/**
 * An expression could not be compiled (spec §6.4).
 *
 * <p>Caught by {@code RuleCompiler} and turned into an ordinary diagnostic, so that a bad
 * expression joins every other rule-set problem in one report rather than aborting the batch. The
 * message is the author's, so implementations should say what is wrong with <em>the expression</em>
 * -- the position and the rule are added around it.
 */
public final class ExpressionCompilationException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong with the expression
   */
  public ExpressionCompilationException(final String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what is wrong with the expression
   * @param cause the underlying failure
   */
  public ExpressionCompilationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
