package com.codeheadsystems.rules.session;

/**
 * What a session is currently holding (spec §4.4's growth surfaces).
 *
 * <p>§4.4 lists the structures a session accumulates and makes "a streaming session under sustained
 * insert-without-retract load reaches a steady-state heap" an exit criterion for Phase 3. That
 * claim is not assertable from outside without a window onto the structures it is about: working
 * memory's size was already public, the other two were not, and a session that held no facts while
 * quietly retaining a match or an index entry per fact ever inserted would satisfy every assertion
 * available before this record existed.
 *
 * <p><strong>Counts, not bytes.</strong> Heap measurement is a flaky assertion -- it fails on
 * collector timing rather than on a defect -- and these are what actually grow. A test asserts that
 * they stop growing; a heap that then still climbs is a different bug in a different place.
 *
 * @param factCount facts in working memory, the bound {@code maxFacts} (§4.7) is checked against
 * @param refractedMatchCount matches the refraction memory remembers as fired (§4.4). Bounded only
 *     by retract and per-rule invalidation, which is why eviction is what bounds it in a long-lived
 *     session
 * @param materialisedMatchCount complete matches held by the streaming matcher's beta memory, and
 *     zero under the recomputing matchers, which hold none between fire cycles. A self-join over N
 *     facts holds O(N²) of these while working memory holds N
 * @param materialisedHandleCount handles the beta memory's reverse index still tracks, and zero
 *     under the recomputing matchers. Reported separately from the match count because a leak hides
 *     here rather than there: a session can hold no matches at all while retaining an index entry
 *     for every fact it has ever seen
 * @param evictedCount facts this session has evicted (§4.4); zero when no policy is configured
 */
public record SessionStats(
    int factCount,
    int refractedMatchCount,
    int materialisedMatchCount,
    int materialisedHandleCount,
    long evictedCount) {

  /** A session holding nothing. */
  private static final SessionStats EMPTY = new SessionStats(0, 0, 0, 0, 0L);

  /**
   * Counts for a session holding nothing.
   *
   * <p>What {@link RuleSession#stats()} answers when an implementation does not override it. Named
   * rather than constructed at the call site so that the reading "this session reports nothing" is
   * distinguishable from a session that genuinely measured zero.
   *
   * @return the all-zero counts
   */
  public static SessionStats empty() {
    return EMPTY;
  }
}
