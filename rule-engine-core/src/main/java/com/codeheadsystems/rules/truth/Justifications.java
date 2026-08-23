package com.codeheadsystems.rules.truth;

import com.codeheadsystems.rules.match.ActivationKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which firing concluded which fact, and what still holds each conclusion up (spec §4.4's
 * amendment).
 *
 * <p>Session-scoped and single-writer, like {@code RefractionMemory} beside it. Two maps rather than
 * one, because both directions are asked on the hot path of a revalidation: "what did this
 * justification conclude" when a justification dies, and "what else supports this fact" before
 * retracting it.
 *
 * <p><strong>Exactly one justification per conclusion, and that follows from the fact model rather
 * than from a choice made here.</strong> A firing that inserts logically allocates a fresh handle
 * (§6.2.2), so two matches concluding "the same" thing produce two <em>facts</em>, each supported by
 * its own match and each withdrawn with its own reason. A multi-support graph -- retract only when
 * the <em>last</em> reason goes -- was written first and then removed as unreachable: nothing can
 * make two activations produce one handle.
 *
 * <p>The consequence is worth stating because classical truth maintenance does the opposite. Two
 * unpaid orders for one customer yield two {@code CustomerAtRisk} facts, not one held up twice, and
 * a rule that counts them counts two. Deduplicating conclusions by content is a separate feature
 * from withdrawing them -- it needs an equality index over payloads and it changes what fact
 * identity means (§2.1) -- so it is not smuggled in here. Flatten or aggregate at ingestion, which
 * is §1's answer to the same shape of question.
 *
 * <p>Insertion-ordered throughout. A retraction ordering that varied per JVM would reach the agenda
 * through the rules those retracts dirty, and §7.3 covers every path to it.
 */
public final class Justifications {

  /** What each firing concluded. Keyed by the same {@link ActivationKey} refraction uses. */
  private final Map<ActivationKey, Set<Long>> concludedBy = new LinkedHashMap<>();

  /** What holds each concluded fact up. Exactly one entry; see the class note for why. */
  private final Map<Long, ActivationKey> supportedBy = new LinkedHashMap<>();

  /**
   * Conclusions replaced by a re-firing of the same justification, awaiting retraction.
   *
   * <p>A rule re-fires on the same {@link ActivationKey} when an update clears its refraction while
   * leaving the match valid -- the handles are unchanged, so the key is. The conclusion it drew the
   * first time was drawn from the old payload and is superseded, not confirmed. Retracting it there
   * and then is not available: a firing is mid-commit (§4.6), and that is precisely where nothing
   * may be retracted. So it waits here for the pass, which runs at the next quiescence point.
   */
  private final List<Long> superseded = new ArrayList<>();

  /** Creates an empty graph. */
  public Justifications() {
  }

  /**
   * Marks whatever this justification concluded before as replaced by what it is about to conclude.
   *
   * <p>Called once per firing, before its effects are recorded. Without it a rule re-firing on an
   * updated binding leaves both conclusions alive -- the same belief twice, differing only in the
   * payload it was drawn from, and a growth surface in exactly the streaming-with-updates workload
   * this feature exists for.
   *
   * @param key the justification about to conclude again
   */
  public void supersede(final ActivationKey key) {
    final Set<Long> previous = concludedBy.remove(key);
    if (previous == null) {
      return;
    }
    for (final long handleId : previous) {
      supportedBy.remove(handleId);
      superseded.add(handleId);
    }
  }

  /**
   * Takes the superseded conclusions, leaving none behind.
   *
   * @return the handles to retract, in the order they were superseded
   */
  public List<Long> drainSuperseded() {
    if (superseded.isEmpty()) {
      return List.of();
    }
    final List<Long> drained = List.copyOf(superseded);
    superseded.clear();
    return drained;
  }

  /**
   * Whether anything is waiting to be retracted as superseded.
   *
   * @return true when a re-firing replaced a conclusion the pass has not yet cleared up
   */
  public boolean hasSuperseded() {
    return !superseded.isEmpty();
  }

  /**
   * Records that one firing concluded one fact.
   *
   * @param key the firing that concluded it
   * @param handleId the derived fact's handle
   */
  public void record(final ActivationKey key, final long handleId) {
    concludedBy.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(handleId);
    supportedBy.put(handleId, key);
  }

  /**
   * Every justification currently held, in the order it was recorded.
   *
   * @return a snapshot, safe to iterate while {@link #invalidate} mutates the graph
   */
  public List<ActivationKey> keys() {
    return new ArrayList<>(concludedBy.keySet());
  }

  /**
   * Whether anything is recorded at all.
   *
   * @return true when no firing has concluded anything logically
   */
  public boolean isEmpty() {
    return concludedBy.isEmpty();
  }

  /**
   * Drops one justification and reports what it was holding up.
   *
   * @param key the justification that no longer holds
   * @return the handles now supported by nothing, in insertion order
   */
  public List<Long> invalidate(final ActivationKey key) {
    final Set<Long> concluded = concludedBy.remove(key);
    if (concluded == null) {
      return List.of();
    }
    final List<Long> unsupported = new ArrayList<>();
    for (final long handleId : concluded) {
      if (supportedBy.remove(handleId) != null) {
        unsupported.add(handleId);
      }
    }
    return unsupported;
  }

  /**
   * Forgets a fact entirely, whatever still claimed to support it.
   *
   * <p>For a derived fact that left by some other door -- a caller's {@code retract}, an eviction,
   * another rule's {@code retractFact}. Dropping the support is what bounds this graph in a
   * long-lived session: an entry per conclusion ever drawn, kept after the fact has gone, is a leak
   * the fact count would never show. It also stops the pass revalidating a justification for
   * something already retracted and calling {@code retract} on it a second time when its reason
   * finally expires.
   *
   * @param handleId the fact that has gone
   */
  public void forget(final long handleId) {
    final ActivationKey key = supportedBy.remove(handleId);
    if (key == null) {
      return;
    }
    final Set<Long> concluded = concludedBy.get(key);
    if (concluded != null && concluded.remove(handleId) && concluded.isEmpty()) {
      concludedBy.remove(key);
    }
  }

  /**
   * How many facts are currently held up by a justification.
   *
   * @return the count, reported by {@code SessionStats.concludedFactCount} so a long-lived session
   *     can be watched for rules concluding faster than their reasons expire
   */
  public int size() {
    return supportedBy.size();
  }
}
