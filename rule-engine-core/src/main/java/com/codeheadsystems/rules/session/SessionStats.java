package com.codeheadsystems.rules.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
 * @param pendingMatchCount matches held and waiting to fire, and zero under the recomputing
 *     matchers, which hold none between fire cycles. Under the streaming matcher this is what a
 *     fire cycle costs, so a session whose beta memory is large and whose pending count is small is
 *     the steady state §4.3 exists to produce. A rule with a §6.4 {@code condition} is the
 *     exception: a match the condition rejects is never fired, so it is never pulled back out and
 *     this count rises toward the held-match count -- see {@code ReteAgenda.pendingMatchCount}
 * @param concludedFactCount facts currently held up by a justification (§4.4's amendment) -- what
 *     a {@code logical} insert has concluded and truth maintenance has not yet withdrawn. Reported
 *     beside {@link #refractedMatchCount} because it is the same kind of thing: a per-session
 *     structure that grows with what the rules conclude and is bounded only by them withdrawing it.
 *     A long-lived session whose fact count is flat and whose conclusion count climbs has rules
 *     concluding faster than their reasons expire
 * @param evictedCount facts this session has evicted (§4.4); zero when no policy is configured
 * @param evictedByType the same count split by fact type, holding an entry only for a type that has
 *     had something evicted. Split because the total cannot answer the question it is needed for:
 *     a rule that stops matching because its facts were let go looks exactly like a rule that never
 *     matched, and telling those apart means knowing about <em>that</em> type
 */
public record SessionStats(
    int factCount,
    int refractedMatchCount,
    int materialisedMatchCount,
    int materialisedHandleCount,
    int pendingMatchCount,
    int concludedFactCount,
    long evictedCount,
    Map<String, Long> evictedByType) {

  /** A session holding nothing. */
  private static final SessionStats EMPTY = new SessionStats(0, 0, 0, 0, 0, 0, 0L, Map.of());

  /**
   * Copies the per-type counts, keeping their order.
   *
   * <p>A {@code LinkedHashMap} rather than {@code Map.copyOf}, whose iteration order is salted per
   * JVM. Nothing here reaches the agenda, so this is not a §7.3 obligation -- but it does reach an
   * explanation an author reads and a test asserts on, and a diagnostic that lists fact types in a
   * different order on a different host is a diagnostic somebody eventually distrusts.
   *
   * <p><strong>A copy, not a wrapper, and the difference is the whole point of taking one.</strong>
   * The session hands over its live counter map; wrapping it unmodifiable would make this record a
   * live view of a session that keeps evicting, so a caller holding two of these to compare would
   * find both agreeing and a record's {@code equals} changing under it. {@code SessionStatsTest}
   * pins the snapshot, because removing the {@code new LinkedHashMap<>} leaves every other test in
   * the suite green.
   */
  public SessionStats {
    Objects.requireNonNull(evictedByType, "evictedByType");
    evictedByType = Collections.unmodifiableMap(new LinkedHashMap<>(evictedByType));
  }

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
