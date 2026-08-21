package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rhs.StagedEffect;
import com.codeheadsystems.rules.rule.ActionDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one firing actually did (spec §7.1). For anything regulated, this record <em>is</em> the
 * audit log.
 *
 * <p>Two components carry more weight than they look like they do.
 *
 * <p><strong>{@code effects}</strong> is the staged buffer as committed, so one record answers
 * "what did this firing do" without reconstructing it from a stream of mutations. It is also what
 * makes a partial commit discoverable: §4.6's atomicity guarantee is per-phase, and a commit-phase
 * failure leaves working-memory effects applied. A record naming only the failing action would
 * leave that partial state invisible.
 *
 * <p><strong>{@code runnersUp}</strong> answers "why did B fire before A", a top-three production
 * question that is unanswerable from a record naming only the winner. It is <em>bounded and empty
 * by default</em>, and that is not a detail: the agenda selects a maximum, not an ordering, so
 * producing the complete ranked list of losers would mean sorting everything eligible once per fire
 * cycle -- making the trace the dominant cost of firing. A bounded top-N answers the question
 * completely, because it is about the activations that nearly won, never about the four-hundredth.
 *
 * @param key the match that fired
 * @param recency the winning activation's recency -- a conflict-resolution input
 * @param salience the winning rule's salience -- the other conflict-resolution input
 * @param runnersUp who lost, most-eligible first. Bounded by the session's runners-up limit, and
 *     empty unless a listener is registered or the session is a dry run
 * @param effects what was committed, in the order it was applied
 * @param emitted the events this firing produced, in declaration order
 * @param failedAction the action that threw, if any. Effects for actions after it were never
 *     applied; working-memory effects staged before it were, if the failure happened at commit
 * @param notRun the actions that never executed. §4.6 requires this explicitly -- "which staged
 *     effects committed, which handler failed, and which handlers never ran" -- and it is not
 *     recoverable by re-reading the rule, because commit does not run actions in declaration order
 * @param took how long the firing took
 */
public record FireRecord(
    ActivationKey key,
    long recency,
    int salience,
    List<ActivationKey> runnersUp,
    List<StagedEffect> effects,
    List<EmittedEvent> emitted,
    Optional<ActionDefinition> failedAction,
    List<ActionDefinition> notRun,
    Duration took) {

  /**
   * Canonical constructor. Defensively copies the collection components.
   *
   * @param key the match that fired
   * @param recency the activation's recency
   * @param salience the rule's salience
   * @param runnersUp the bounded list of losers
   * @param effects the committed effects
   * @param emitted the emitted events
   * @param failedAction the action that threw, if any
   * @param notRun the actions that never executed
   * @param took the firing duration
   */
  public FireRecord {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(failedAction, "failedAction");
    Objects.requireNonNull(took, "took");
    runnersUp = List.copyOf(runnersUp);
    effects = List.copyOf(effects);
    emitted = List.copyOf(emitted);
    notRun = List.copyOf(notRun);
  }
}
