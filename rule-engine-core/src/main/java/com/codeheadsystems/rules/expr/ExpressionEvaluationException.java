package com.codeheadsystems.rules.expr;

import java.io.Serial;

/**
 * An expression could not be evaluated (spec §6.4).
 *
 * <p>Thrown rather than swallowed into a non-match. §6.4's whole argument for CEL over a scripting
 * language is that the cost of an expression is bounded and visible, and an evaluation that hit its
 * limit is exactly the event that claim is about -- turning it into "this rule did not match" would
 * hide the thing an author most needs told.
 */
public final class ExpressionEvaluationException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   */
  public ExpressionEvaluationException(final String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public ExpressionEvaluationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
