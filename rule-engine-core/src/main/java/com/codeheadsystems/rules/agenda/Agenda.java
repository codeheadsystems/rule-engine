package com.codeheadsystems.rules.agenda;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.match.Activation;
import java.util.Optional;

/**
 * The conflict set and the selection from it (spec §4.3).
 *
 * <p><strong>All four methods lazily recompute, and all four apply refraction filtering.</strong>
 * Neither is optional and neither is side-effect free, so both belong in every implementation's
 * contract rather than in one method's:
 *
 * <ul>
 *   <li><em>Lazy recomputation</em> is the TREAT bargain: nothing is materialised between fires, so
 *       the conflict set for a dirty rule is rebuilt on demand. Implementations must memoise the
 *       rebuild within a fire cycle -- the firing loop calls {@link #isEmpty()}, then perhaps
 *       {@link #peek()}, then {@link #nextToFire()}, and rebuilding three times over would make
 *       these methods quadratic in how many of them the loop happens to call.
 *   <li><em>Refraction filtering</em> is what makes the firing loop terminate. {@link #isEmpty()}
 *       returning {@code false} must mean something is <strong>genuinely eligible</strong>, not
 *       merely that the conflict set is non-empty: a set containing only refracted matches is empty
 *       for every purpose the firing loop cares about. Without that, the loop cannot distinguish
 *       "drained" from "not yet computed" from "computed but all refracted", and the last case
 *       would spin until the cycle limit.
 * </ul>
 *
 * <p><strong>Four methods, because the other three would have no caller -- and that is still true
 * now that the shape needing them exists.</strong> §4.3 names an
 * {@code activate}/{@code deactivate}/{@code deactivateAllInvolving} trio as the <em>Rete</em>
 * interface, for pushing activations in and pulling them out as tokens arrive. The streaming
 * matcher now does exactly that, and it does it through {@link #factInserted},
 * {@code RecomputingAgenda.onConsumed} and {@link #factRetracted} -- callbacks this interface
 * already had -- because nothing outside that class would ever call the trio. §4.3's own argument
 * applies to its own method names: specifying them would mean specifying, testing and maintaining a
 * public surface no code path invokes.
 *
 * <p>This interface is deliberately not exposed on a session (§5.1). {@link #nextToFire()}
 * consumes, so an outside call would silently delete a firing the running loop would otherwise have
 * performed, with no error anywhere.
 */
public interface Agenda {

  /**
   * The activation that would fire next, without consuming it.
   *
   * @return the most eligible non-refracted activation, or empty if none is eligible
   */
  Optional<Activation> peek();

  /**
   * Selects the next activation, records it as refracted, and removes it from the conflict set.
   *
   * <p>This <strong>consumes</strong>. Calling it and then declining to execute the result destroys
   * work the session can never recover.
   *
   * @return the activation to fire, or empty if none is eligible
   */
  Optional<Activation> nextToFire();

  /**
   * Whether anything is genuinely eligible to fire.
   *
   * @return {@code true} when no non-refracted activation exists
   */
  boolean isEmpty();

  /**
   * How many activations are genuinely eligible.
   *
   * <p>This counts what {@link #isEmpty()} means, so {@code size() == 0} and {@code isEmpty()}
   * always agree. A count of everything sitting in the conflict set would report a residual agenda
   * for a session that had genuinely drained.
   *
   * @return the eligible, non-refracted count
   */
  int size();

  /**
   * The most eligible activations, ranked, without consuming anything.
   *
   * <p>This is the diagnostic that backs a fire record's runners-up list, and it is a separate
   * method rather than a wider {@link #peek()} for a cost reason: an agenda <em>selects a
   * maximum</em>, it does not produce an ordering. Ranking means sorting everything eligible, which
   * if done unconditionally would make the trace the dominant cost of firing. So it is called only
   * when something will read the result, and it is bounded -- "why did B fire before A" is a
   * question about the activations that nearly won, never about the four-hundredth-ranked one.
   *
   * @param limit the maximum number to return
   * @return up to {@code limit} eligible activations, most eligible first
   */
  java.util.List<Activation> rankEligible(int limit);

  /**
   * Marks every rule that patterns a fact type as needing recomputation (§4.1).
   *
   * <p>The predicate is one line: a rule is dirty when a fact of a type it patterns is inserted,
   * retracted or effectively updated -- including by its own RHS.
   *
   * <p>Note what it deliberately is <em>not</em>. "Dirty only the rules whose alpha memories
   * actually changed" is wrong, and wrong in a way that silently serves stale matches: an update to
   * a join key leaves alpha membership unchanged while making the rule's join result stale.
   *
   * @param factType the type whose facts changed
   */
  void markDirty(String factType);

  /**
   * Tells the agenda a fact has entered working memory.
   *
   * <p>Default no-op, and the default is the honest one for the shapes that recompute. §4.3
   * explains why: under TREAT "nothing pushes and nothing pulls" -- a dirty rule's conflict set is
   * replaced wholesale at the next recomputation, so knowing <em>which</em> fact arrived buys
   * nothing that {@link #markDirty} has not already bought. Only the Rete shape overrides it, to
   * extend its beta memory with the matches the fact completes.
   *
   * <p><strong>This is where §4.3's {@code activate} happens</strong>, though it reports a fact
   * rather than an activation. An earlier version of this note drew a distinction between the two --
   * the conflict set was still replaced wholesale then, so reporting a fact really did buy nothing
   * for the agenda. The streaming matcher now derives the new matches here and pushes the ones that
   * can fire, so the fact callback is the trigger and the distinction has collapsed.
   *
   * <p>Called after the alpha network has taken the fact, so an implementation may read pattern
   * memberships that already include it.
   *
   * @param fact the fact that has entered working memory
   */
  default void factInserted(Fact fact) {
    // Nothing to do for a recomputing shape; see the contract above.
  }

  /**
   * Tells the agenda a fact is leaving working memory.
   *
   * <p>Default no-op, for the same reason as {@link #factInserted}: a retracted fact's matches
   * disappear under TREAT because the next recomputation does not produce them, which needs no
   * agenda surgery. The Rete shape overrides it to drop the matches the fact took part in, which is
   * what a streaming session's steady-state heap depends on -- and, like {@link #factInserted},
   * is a report about a fact rather than §4.3's {@code deactivateAllInvolving} over activations.
   *
   * @param fact the fact leaving working memory, carrying the payload it had when asserted
   */
  default void factRetracted(Fact fact) {
    // Nothing to do for a recomputing shape; see the contract above.
  }

  /**
   * How many complete matches this agenda is holding between fire cycles.
   *
   * <p>Zero for the recomputing shapes, and the default is the honest answer rather than an
   * unimplemented one: under TREAT nothing is materialised between fires, so there is nothing to
   * count. Only the Rete shape holds matches, and §9's steady-state criterion for Phase 3 is a
   * claim about exactly that number.
   *
   * @return the held match count; zero when nothing is materialised
   */
  default int materialisedMatchCount() {
    return 0;
  }

  /**
   * How many held matches are waiting to fire.
   *
   * <p>Zero for the recomputing shapes, which hold nothing between fires. For a shape that pushes
   * activations in and pulls them out (§4.3) this is the structure that decides its cost: a fire
   * cycle ranks what is waiting, so this number is what "the fire cycle" is proportional to, and a
   * shape that failed to pull fired matches back out would show it here and nowhere else.
   *
   * @return the count of matches held and not yet fired; zero when nothing is held
   */
  default int pendingMatchCount() {
    return 0;
  }

  /**
   * How many handles this agenda's reverse index still tracks.
   *
   * <p>Reported separately from {@link #materialisedMatchCount()} because a leak hides here rather
   * than there: an index retaining an entry per fact ever inserted grows without bound while the
   * match count looks healthy. Zero for the recomputing shapes, which keep no such index -- §4.3
   * declines to maintain one for pending activations, and the beta memory's exists for a different
   * purpose.
   *
   * @return the indexed handle count; zero when no reverse index is held
   */
  default int materialisedHandleCount() {
    return 0;
  }
}
