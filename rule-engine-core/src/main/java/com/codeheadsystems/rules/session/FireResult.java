package com.codeheadsystems.rules.session;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The engine's output (spec §4.7). Every fire call returns one, and a limit breach carries a
 * partial one -- so no completed work is ever lost.
 *
 * @param fired what fired, in firing order
 * @param emitted the events produced, in firing order, from the collecting sink
 * @param why why firing stopped
 * @param residualAgendaSize how many activations remain <em>eligible</em>. Non-zero after a halt or
 *     a limit; zero after a drain. It counts what "the agenda is empty" means, so a session that
 *     genuinely drained never reports a residual
 * @param ruleSetVersion the content hash of the rules that produced this, stamped on every result
 *     rather than only on traces -- it is how "which rules produced this decision" is answerable
 *     months later
 * @param took how long the fire call took
 */
public record FireResult(
    List<FireRecord> fired,
    List<EmittedEvent> emitted,
    TerminationReason why,
    int residualAgendaSize,
    String ruleSetVersion,
    Duration took) {

  /**
   * Canonical constructor. Defensively copies the collection components.
   *
   * @param fired the firing records
   * @param emitted the emitted events
   * @param why the termination reason
   * @param residualAgendaSize the eligible activations remaining
   * @param ruleSetVersion the rule-set content hash
   * @param took the duration
   */
  public FireResult {
    Objects.requireNonNull(why, "why");
    Objects.requireNonNull(ruleSetVersion, "ruleSetVersion");
    Objects.requireNonNull(took, "took");
    fired = List.copyOf(fired);
    emitted = List.copyOf(emitted);
  }

  /**
   * How many activations fired.
   *
   * @return the firing count
   */
  public int firedCount() {
    return fired.size();
  }
}
