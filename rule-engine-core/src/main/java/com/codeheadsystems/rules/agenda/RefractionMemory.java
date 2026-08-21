package com.codeheadsystems.rules.agenda;

import com.codeheadsystems.rules.match.ActivationKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Per-session memory of "this rule has already fired on this exact set of facts" (spec §4.4).
 *
 * <p><strong>Without refraction, firing all rules does not terminate on ordinary rule sets.</strong>
 * A rule whose RHS does not invalidate its own LHS -- "for every flagged order, emit an alert",
 * which mutates nothing -- would re-fire on the same match until the cycle limit. This is a
 * primitive, not an optimisation, which is why §9 puts it in Phase 0 alongside the naive matcher
 * where it is ten lines and trivially testable.
 *
 * <p><strong>It keeps its own handle index.</strong> A fired activation has been removed from the
 * conflict set at selection, so there is nothing left to scan it out of; without the index,
 * invalidating on retract would be an O(fired) scan, which is quadratic in a long-lived session.
 *
 * <p><strong>This is a growth surface.</strong> {@code fired} grows with every match ever fired and
 * is bounded only by retract and per-rule invalidation. For the one-shot/batch sessions v1 targets
 * that is a non-issue -- the session is discarded before anything accumulates -- but it is a real
 * problem for a streaming session that inserts continuously and retracts nothing. The mechanism
 * that bounds it is fact eviction running the full retract path, which is Phase 3 work.
 */
public final class RefractionMemory {

  private final Set<ActivationKey> fired = new LinkedHashSet<>();
  private final Map<Long, Set<ActivationKey>> byHandle = new LinkedHashMap<>();
  private final Map<ActivationKey, Long> firedAtRecency = new LinkedHashMap<>();

  private ActivationKey noLoopGuard;

  /** Creates an empty refraction memory. One per session; never shared. */
  public RefractionMemory() {
    // Fields are initialised inline; this constructor exists to carry the documentation above.
  }

  /**
   * Whether a match is still eligible to fire.
   *
   * @param key the match's identity
   * @return {@code true} if this rule has not already fired on these exact facts
   */
  public boolean shouldFire(final ActivationKey key) {
    return !fired.contains(key);
  }

  /**
   * Records a match as fired.
   *
   * <p>Called when the activation is <strong>consumed</strong> -- immediately after selection and
   * before the RHS runs -- not when it completes. Recording on success only would let a rule whose
   * RHS throws under a skip-and-continue error policy be re-selected on the next cycle and throw
   * again, forever: a retry loop that looks exactly like the non-termination refraction exists to
   * prevent.
   *
   * @param key the match's identity
   * @param recency the activation's recency, kept so that §7.2's explanation can say
   *     <em>when</em> a rule already fired rather than merely that it did
   */
  public void record(final ActivationKey key, final long recency) {
    Objects.requireNonNull(key, "key");
    if (fired.add(key)) {
      firedAtRecency.put(key, recency);
      for (final long handle : key.handles()) {
        byHandle.computeIfAbsent(handle, ignored -> new LinkedHashSet<>()).add(key);
      }
    }
  }

  /**
   * The recency at which a match fired.
   *
   * <p>Refraction is the verdict nobody guesses: a rule that "stopped working" has usually already
   * fired on those exact facts. §7.2 wants to say so explicitly, with the recency.
   *
   * @param key the match's identity
   * @return the recency it fired at, or empty if it has not fired
   */
  public Optional<Long> firedAt(final ActivationKey key) {
    return Optional.ofNullable(firedAtRecency.get(key));
  }

  /**
   * A fact was retracted, so every match binding it is eligible again.
   *
   * @param handleId the retracted fact's handle id
   */
  public void invalidateAll(final long handleId) {
    final Set<ActivationKey> keys = byHandle.remove(handleId);
    if (keys == null) {
      return;
    }
    for (final ActivationKey key : keys) {
      forget(key);
    }
  }

  /**
   * A fact was effectively updated, so matches of the rules that test a changed path are eligible
   * again -- and only those.
   *
   * <p>The scoping is essential (§4.4). A type-wide clear means an update to a field only rule B
   * tests re-enables rule A's already-fired match, so A fires twice because an unrelated rule's
   * field changed.
   *
   * <p>A key protected by {@link #guardNoLoop} is skipped, which is how {@code noLoop} is
   * implemented: see that method.
   *
   * @param handleId the updated fact's handle id
   * @param ruleIds the rules that test at least one changed path
   * @return the matches that would have become eligible but were held back by the {@code noLoop}
   *     guard. Returned rather than swallowed so the session can report them: {@code noLoop}
   *     suppression is observable in this agenda shape, and §7.1 gives it a listener callback,
   *     which would be dead code if nothing here said it had happened
   */
  public Set<ActivationKey> invalidateFor(final long handleId, final Set<String> ruleIds) {
    final Set<ActivationKey> keys = byHandle.get(handleId);
    if (keys == null || ruleIds.isEmpty()) {
      return Set.of();
    }
    final Set<ActivationKey> affected = new LinkedHashSet<>();
    final Set<ActivationKey> suppressed = new LinkedHashSet<>();
    for (final ActivationKey key : keys) {
      if (!ruleIds.contains(key.ruleId())) {
        continue;
      }
      if (key.equals(noLoopGuard)) {
        suppressed.add(key);
      } else {
        affected.add(key);
      }
    }
    for (final ActivationKey key : affected) {
      forget(key);
    }
    return suppressed;
  }

  /**
   * Protects one match's refraction entry for the duration of its own RHS -- this is {@code noLoop}
   * (spec §4.5).
   *
   * <p>The propagation-time formulation of {@code noLoop} ("suppress activations produced by this
   * activation's mutations") is not implementable in the TREAT shape, which rebuilds the conflict
   * set wholesale from current state with no record of which mutation produced which match. The
   * equivalent formulation, which works in both shapes and is what this method provides: while
   * executing activation A of a rule with {@code noLoop} set, effective updates performed by A's
   * own RHS do not clear refraction for A's key. Other rules' refraction, and the same rule's
   * refraction for other bindings, are unaffected.
   *
   * <p>Be explicit about what this does not do: it is one level deep. A rule that triggers a second
   * rule which re-triggers the first sails through it, as does a rule mutating a fact that
   * re-activates it on a different binding. Every engine offering this flag has the same
   * limitation. The cycle limit is the actual loop defence.
   *
   * @param key the match whose refraction entry to protect, or {@code null} to clear the guard
   */
  public void guardNoLoop(final ActivationKey key) {
    this.noLoopGuard = key;
  }

  /**
   * How many matches have fired. A diagnostic, and the growth surface described above.
   *
   * @return the number of distinct matches recorded
   */
  public int size() {
    return fired.size();
  }

  /**
   * Removes one key from every structure.
   *
   * @param key the match to forget
   */
  private void forget(final ActivationKey key) {
    fired.remove(key);
    firedAtRecency.remove(key);
    for (final long handle : key.handles()) {
      final Set<ActivationKey> keys = byHandle.get(handle);
      if (keys != null) {
        keys.remove(key);
        if (keys.isEmpty()) {
          byHandle.remove(handle);
        }
      }
    }
  }
}
