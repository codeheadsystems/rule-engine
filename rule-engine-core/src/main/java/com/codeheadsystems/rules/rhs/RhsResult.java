package com.codeheadsystems.rules.rhs;

import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.session.EmittedEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What executing one right-hand side produced.
 *
 * <p>The failure component is what makes §4.6's honest atomicity boundary reportable. The guarantee
 * is per-phase, and the two phases differ: a staging-phase failure applies nothing, while a
 * commit-phase failure leaves working-memory effects applied and earlier handlers' side effects in
 * place. So {@link #effects()} is not "what the rule declared" -- it is what actually landed.
 *
 * @param effects the effects that were applied, in the order they were applied
 * @param emitted the events delivered
 * @param failure the action that threw, if any, together with its cause
 * @param notRun the actions that never executed because commit stopped at the failure. §4.6
 *     requires a firing's record to say "which staged effects committed, which handler failed, and
 *     <em>which handlers never ran</em>" -- a record naming only the failing action leaves the
 *     partial state undiscoverable, and the reader cannot recover the answer by re-reading the rule
 *     because commit does not run actions in declaration order (working-memory effects go first,
 *     then handlers, then emissions)
 */
public record RhsResult(
    List<StagedEffect> effects,
    List<EmittedEvent> emitted,
    Optional<Failure> failure,
    List<ActionDefinition> notRun) {

  /**
   * Canonical constructor. Defensively copies the collection components.
   *
   * @param effects the applied effects
   * @param emitted the delivered events
   * @param failure the failure, if any
   * @param notRun the actions that never executed
   */
  public RhsResult {
    Objects.requireNonNull(failure, "failure");
    effects = List.copyOf(effects);
    emitted = List.copyOf(emitted);
    notRun = List.copyOf(notRun);
  }

  /**
   * A right-hand side that completed with everything applied.
   *
   * @param effects the applied effects
   * @param emitted the delivered events
   * @return a successful result
   */
  public static RhsResult succeeded(final List<StagedEffect> effects,
      final List<EmittedEvent> emitted) {
    return new RhsResult(effects, emitted, Optional.empty(), List.of());
  }

  /**
   * A failed action and why.
   *
   * @param action the action that threw
   * @param cause the exception
   * @param duringCommit whether the failure happened at commit, in which case working-memory
   *     effects were <em>not</em> rolled back and cannot be
   */
  public record Failure(ActionDefinition action, Throwable cause, boolean duringCommit) {

    /**
     * Canonical constructor.
     *
     * @param action the failing action
     * @param cause the exception
     * @param duringCommit whether it failed at commit
     */
    public Failure {
      Objects.requireNonNull(action, "action");
      Objects.requireNonNull(cause, "cause");
    }
  }
}
