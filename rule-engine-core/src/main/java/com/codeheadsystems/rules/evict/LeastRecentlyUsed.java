package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import java.util.List;

/**
 * A total cap on working memory, oldest by recency first (spec §4.4).
 *
 * @see EvictionPolicy#leastRecentlyUsed(int)
 */
final class LeastRecentlyUsed implements EvictionPolicy {

  private final int maxFacts;

  /**
   * Creates the policy.
   *
   * @param maxFacts the most facts to hold
   */
  LeastRecentlyUsed(final int maxFacts) {
    if (maxFacts <= 0) {
      throw new IllegalArgumentException("maxFacts must be positive, was " + maxFacts);
    }
    this.maxFacts = maxFacts;
  }

  @Override
  public List<FactHandle> selectVictims(final EvictionView view) {
    final int excess = view.size() - maxFacts;
    if (excess <= 0) {
      return List.of();
    }
    return view.oldestFirst().limit(excess).map(Fact::handle).toList();
  }

  @Override
  public String toString() {
    return "leastRecentlyUsed(" + maxFacts + ")";
  }
}
