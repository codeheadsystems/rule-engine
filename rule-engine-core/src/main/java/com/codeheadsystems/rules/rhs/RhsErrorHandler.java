package com.codeheadsystems.rules.rhs;

import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.rule.ActionDefinition;

/**
 * Decides what a session does when a right-hand side throws (spec §4.6).
 */
@FunctionalInterface
public interface RhsErrorHandler {

  /**
   * What to do about a failed firing.
   */
  enum Decision {

    /**
     * Stop firing, mark the session failed, and <strong>return</strong> a fire result whose
     * termination reason is {@code RHS_ERROR}.
     *
     * <p>This differs from {@link #RETHROW} in one respect only: it does not propagate. Choose it
     * when the caller treats a rule failure as a decision outcome to inspect rather than an
     * exception to handle -- a batch driver that must report per-item status without a try/catch
     * around every item.
     *
     * <p>Note the termination reason is <em>not</em> a limit breach. No limit was breached, and a
     * caller switching on the reason to decide "retry with a higher maxCycles" would be told to do
     * exactly the wrong thing.
     */
    ABORT_SESSION,

    /**
     * Log it, leave the activation refracted so it cannot retry-loop, and keep firing.
     *
     * <p>Right for best-effort batch scoring, where one bad fact should not fail ten thousand good
     * ones. Choose it deliberately.
     */
    SKIP_ACTIVATION,

    /**
     * Propagate the original exception to the caller, with the partially-executed activation on the
     * trace and the session marked failed.
     *
     * <p>The default. Silent continuation after an unexpected RHS exception is how a rule engine
     * produces confidently wrong output.
     */
    RETHROW
  }

  /**
   * Decides how to handle one failure.
   *
   * @param activation the activation that was firing
   * @param failed the action that threw
   * @param cause the exception
   * @return what the session should do
   */
  Decision onRhsFailure(Activation activation, ActionDefinition failed, Throwable cause);

  /**
   * The default handler: rethrow.
   *
   * @return a handler that always returns {@link Decision#RETHROW}
   */
  static RhsErrorHandler rethrow() {
    return (activation, failed, cause) -> Decision.RETHROW;
  }
}
