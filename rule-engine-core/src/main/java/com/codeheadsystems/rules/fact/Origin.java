package com.codeheadsystems.rules.fact;

/**
 * Where a fact came from: the caller, or a rule that fired (spec §5.6).
 *
 * <p>This exists for one reason, and it is worth stating so nobody widens it casually. §5.6's
 * drain-and-restart says a session whose rule set has changed is rebuilt by replaying its facts into
 * a fresh session. Replaying a fact that a right-hand side inserted would double-count it, because
 * the replayed session re-derives it the moment it fires. So the export has to distinguish the two,
 * and provenance is how.
 *
 * <p>It is <em>not</em> a general truth-maintenance system. There is no dependency graph here, no
 * record of which activation produced which fact, and retracting the fact a rule derived from does
 * not retract the derivation -- §4.4 defers all of that. This is one bit, recorded at the boundary
 * that already existed.
 */
public enum Origin {

  /**
   * Inserted from outside the engine, through {@code insert} or {@code insertOwned}.
   *
   * <p>These are the session's inputs, and they are what
   * {@code RuleSession.exportFacts()} returns.
   */
  ASSERTED,

  /**
   * Inserted by a rule's right-hand side while firing.
   *
   * <p>Deliberately excluded from the export: a replayed session produces these itself.
   */
  DERIVED
}
