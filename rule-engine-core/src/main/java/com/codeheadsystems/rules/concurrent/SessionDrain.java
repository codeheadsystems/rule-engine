package com.codeheadsystems.rules.concurrent;

import com.codeheadsystems.rules.fact.ExportedFact;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.Objects;

/**
 * Moves a running session onto a new rule set by draining it and replaying (spec §5.6).
 *
 * <p>§5.6 is blunt about why there is no alternative -- "For long-lived sessions there is no safe
 * in-place swap" -- because a live session's pattern memories, refraction state and agenda are all
 * shaped by the old network's node ids. Drain-and-restart is called "correct, simple, the
 * right default" there, and this class is that sentence with the two easy mistakes closed off.
 *
 * <p><strong>The order of the replay is the contract</strong>, not an implementation detail. §7.3
 * guarantees a firing sequence for the same rule set, the same facts, and the same insertion order;
 * a restarted session that inserted its facts in a different order could fire differently from a
 * continuous one and nothing would look wrong. {@code exportFacts()} orders by handle id, and the
 * replay here preserves it.
 *
 * <p><strong>Derived facts are not replayed</strong>, which {@code exportFacts()} handles by
 * filtering to {@link com.codeheadsystems.rules.fact.Origin#ASSERTED}. The new session re-derives
 * them when it fires. This is also why the restarted session is handed back <em>unfired</em>: firing
 * is the caller's to schedule, and doing it here would hide the one step that rebuilds the
 * derivations.
 *
 * <p>What is deliberately not carried over is refraction state. §2.5 keys refraction on
 * {@code (ruleId, handles)} and the handles are new, so a rule that already fired for a tuple in the
 * old session fires again for the replayed one. That is inherent to restarting rather than a
 * shortcoming here -- the restarted session is a fresh session that happens to hold the same facts,
 * and treating it as a continuation of the old one is exactly the assumption that would bite.
 */
public final class SessionDrain {

  private SessionDrain() {
    throw new AssertionError("static helper");
  }

  /**
   * Exports a session's facts, closes it, and replays them into a session on the given rule set.
   *
   * <p>The old session is closed only after the new one is successfully created <em>and loaded</em>,
   * so a failure -- a schema rejection under a tightened rule set, most plausibly -- leaves the
   * caller with their original session still usable rather than with neither. The half-loaded new
   * session is closed on the way out, so the failure costs no memories either.
   *
   * @param session the session to drain; closed on success, left open on failure
   * @param rules the rule set to restart on, already compiled
   * @param options the options for the new session
   * @return a new session holding the same asserted facts, in the same order, not yet fired
   */
  public static RuleSession restart(final RuleSession session, final CompiledRuleSet rules,
      final SessionOptions options) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(options, "options");
    final List<ExportedFact> facts = session.exportFacts();
    final RuleSession restarted = rules.newSession(options);
    try {
      replay(restarted, facts);
    } catch (final RuntimeException failed) {
      try {
        restarted.close();
      } catch (final RuntimeException alsoFailed) {
        // Suppressed rather than thrown: the replay failure is why the caller is here, and a
        // close() that then throws would replace the diagnosis with the cleanup.
        failed.addSuppressed(alsoFailed);
      }
      throw failed;
    }
    session.close();
    return restarted;
  }

  /**
   * Inserts exported facts into a session, in order.
   *
   * <p>Uses {@code insertOwned}: the payloads were deep-copied on export and this method is the only
   * thing holding them, so a second copy would be pure cost. Splitting this out means a caller who
   * wants to replay into a session they already hold -- a shadow session for §5.6's
   * run-both-and-compare cutover, say -- does not have to give up their old one first.
   *
   * @param session the session to load
   * @param facts the facts, in the order they must be inserted
   */
  public static void replay(final RuleSession session, final List<ExportedFact> facts) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(facts, "facts");
    for (final ExportedFact fact : facts) {
      session.insertOwned(fact.type(), fact.payload());
    }
  }
}
