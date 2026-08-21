package com.codeheadsystems.rules.session;

/**
 * Why firing stopped (spec §4.7).
 */
public enum TerminationReason {

  /** The agenda emptied: everything eligible fired. The residual agenda size is zero. */
  DRAINED,

  /** {@code halt()} was called from another thread. Activations may remain eligible. */
  HALTED,

  /**
   * A work limit was breached.
   *
   * <p>This appears only on the partial result carried by a {@link RuleEngineLimitExceeded}, never
   * on a normally-returned result.
   */
  LIMIT_EXCEEDED,

  /**
   * A right-hand side threw and the error handler chose to abort.
   *
   * <p>Deliberately distinct from {@link #LIMIT_EXCEEDED}: no limit was breached, and a caller
   * switching on the reason to decide "retry with a higher maxCycles" would be told to do exactly
   * the wrong thing.
   */
  RHS_ERROR
}
