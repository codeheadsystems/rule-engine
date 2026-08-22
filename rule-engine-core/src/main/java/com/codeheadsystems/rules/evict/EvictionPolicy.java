package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.fact.FactHandle;
import java.util.List;
import java.util.Map;

/**
 * Which facts a long-lived session should let go of (spec §4.4).
 *
 * <p>A policy selects; it never removes. The session retracts what this returns, through the
 * ordinary retract path, so node memories and their indexes, the refraction memory and -- under the
 * streaming shape -- the beta memory are all cascaded by machinery that already exists. §4.4 makes
 * that a rule rather than an implementation note: eviction reaching into the memories by hand would
 * be a fifth place they are removed from, and the one nobody updates when a sixth is added.
 *
 * <h2>The contract, which is stronger than it looks</h2>
 *
 * <p><strong>An implementation must be a pure function of the view.</strong> Same facts in the same
 * order, same victims, on every host and every run. Eviction changes which facts exist, so it
 * changes which activations exist, so it reaches the firing sequence -- and §7.3 makes that
 * sequence a contract rather than a preference. Two things break it in practice and neither
 * announces itself: reading a clock, and iterating a {@code HashMap}. Strict mode (§7.5) calls a
 * policy twice on the same view and compares the answers, which catches the first reliably and the
 * second often.
 *
 * <p><strong>Order matters too.</strong> The returned list is retracted in the order given, and
 * that order is visible to listeners. Returning a {@code Set} with unspecified iteration would
 * satisfy this interface's types and violate its contract.
 *
 * <p>A policy may return handles it has no business returning -- one already retracted, one it
 * invented -- and the session ignores those rather than failing, except in strict mode where it
 * rejects them. Selecting nothing is expressed as an empty list, and is the answer on the
 * overwhelming majority of the calls a capped policy sees.
 *
 * <h2>Why no time-to-live policy ships here</h2>
 *
 * <p>§4.4 names TTL first, and it is deliberately absent. Wall-clock time is not an input the
 * determinism contract admits: two runs over identical input would evict different facts, and §7.3
 * is not a preference. The policies below key on {@code recency}, which is derived from the input
 * itself. A caller who genuinely wants a TTL writes one against this interface with a clock they
 * inject, and takes the trade knowingly -- which is a better place for that decision than a factory
 * method here that makes it look free.
 */
@FunctionalInterface
public interface EvictionPolicy {

  /**
   * Chooses the facts to evict.
   *
   * @param view read-only working memory, as described on {@link EvictionView}
   * @return the handles to retract, in the order to retract them; empty to evict nothing. Must be a
   *     deterministic function of the view -- see the contract on this interface
   */
  List<FactHandle> selectVictims(EvictionView view);

  /**
   * Caps working memory at a total fact count, evicting least-recently-used first.
   *
   * <p>The simple policy, and the wrong one for the shape §4.4 was written for. A streaming session
   * usually holds two populations -- reference data loaded once and a stream flowing past it -- and
   * the reference data is inserted first, so it is exactly what a global least-recently-used bound
   * evicts first. {@link #perType(Map)} is the answer there. This one is right when every fact is
   * the same kind of thing.
   *
   * @param maxFacts the most facts to hold; the excess over this is evicted
   * @return the policy
   * @throws IllegalArgumentException if {@code maxFacts} is not positive
   */
  static EvictionPolicy leastRecentlyUsed(final int maxFacts) {
    return new LeastRecentlyUsed(maxFacts);
  }

  /**
   * Caps named fact types individually, evicting least-recently-used first within each.
   *
   * <p>A type absent from the map is unbounded, which is the point: "keep the last ten thousand
   * orders and never evict the two hundred customers" is one map and no predicate. Types are
   * considered in name order, so the victims are the same list whatever order the map was built in.
   *
   * @param capsByType the most facts to hold per fact type; types not named are unbounded
   * @return the policy
   * @throws IllegalArgumentException if any cap is not positive
   * @throws NullPointerException if the map or any key is null
   */
  static EvictionPolicy perType(final Map<String, Integer> capsByType) {
    return new PerTypeCaps(capsByType);
  }
}
