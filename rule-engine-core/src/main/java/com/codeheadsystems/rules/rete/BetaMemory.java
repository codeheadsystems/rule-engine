package com.codeheadsystems.rules.rete;

import com.codeheadsystems.rules.match.Tuple;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One rule's materialised complete matches, maintained across inserts rather than rebuilt per fire
 * (spec §3.1, §11.1's option B).
 *
 * <p><strong>This is the state TREAT deliberately does not have</strong>, and §11.1 is blunt about
 * what it costs: "persistent beta memory is real mutable state you must get right (leaks if facts
 * are never retracted; incremental join maintenance bugs are notoriously easy to introduce)". Both
 * hazards are answered here rather than left to the caller -- {@link #removeInvolving} is what
 * keeps a retract from leaking, and the differential suite against the TREAT engine is what keeps
 * the maintenance honest.
 *
 * <p><strong>Complete matches, not per-node partial ones.</strong> Classic Rete materialises a
 * partial-match memory at every beta node, so a join of k patterns reuses the work of the first
 * k-1. This holds only the terminal memory. The saving is the one that matters for the workload
 * §11.1 names -- a streaming session re-evaluating a large working memory against a small delta
 * pays for joining the new fact rather than for re-joining everything -- and it buys it without a
 * second network shape to keep in agreement with the first. For a rule with many patterns and a
 * high insert rate, per-node memories would amortise further; that is a measurement away, and
 * `docs/benchmarks.md` says so rather than this class assuming it.
 *
 * <p>Insertion-ordered throughout. Selection sorts by a total order so iteration order cannot
 * change which activation fires, but §7.3 makes ordering on any path to the agenda load-bearing
 * anyway, and a hash-ordered set here would be a determinism bug waiting for a reason to appear.
 */
final class BetaMemory {

  private final Set<Tuple> tuples = new LinkedHashSet<>();
  private final Map<Long, Set<Tuple>> byHandle = new LinkedHashMap<>();

  /**
   * Records a complete match, if it is not already held.
   *
   * <p><strong>A complete match is derived exactly once as things stand</strong>, and the reason is
   * worth knowing before anyone optimises this: the pinned walk requires every <em>other</em>
   * position to be in its pattern memory already, so a tuple can only be produced when its last
   * member arrives. Even a self-join does not double-derive -- inserting the second Order yields
   * {@code [O2, O1]} from the walk pinned at position 0 and {@code [O1, O2]} from the one pinned at
   * position 1, which are two different matches rather than one match twice. Measured on that
   * scenario: zero duplicates rejected.
   *
   * <p>So the set semantics are a guard, not a deduplicator with a job today. They are kept because
   * the "exactly once" property is not local to this class -- it depends on {@code factInserted}
   * running after the alpha network has taken the fact, and on distinct aliases binding distinct
   * facts. Either changing would turn a silent double-fire into a no-op here, which is the right
   * failure for a property maintained somewhere else.
   *
   * @param tuple the match
   * @return whether it was new
   */
  boolean add(final Tuple tuple) {
    if (!tuples.add(tuple)) {
      return false;
    }
    for (final long handle : tuple.boundFacts()) {
      byHandle.computeIfAbsent(handle, ignored -> new LinkedHashSet<>()).add(tuple);
    }
    return true;
  }

  /**
   * Drops every match binding a handle.
   *
   * <p>The reverse index exists for this call alone, and it is what makes a retract proportional to
   * the matches the fact took part in rather than to the whole memory. §4.4's steady-state-heap
   * criterion is really a statement about this method: a session that inserts and retracts
   * indefinitely must not accumulate, so the index entry goes too, not merely the tuples.
   *
   * @param handle the retracted fact's handle id
   * @return the matches that were removed, which {@code ReteAgenda.factRetracted} uses as §4.3's
   *     {@code deactivateAllInvolving}: the matches leaving the join memory are exactly the ones
   *     that must leave the conflict set, and the reverse index has already found them
   */
  List<Tuple> removeInvolving(final long handle) {
    final Set<Tuple> affected = byHandle.remove(handle);
    if (affected == null) {
      return List.of();
    }
    final List<Tuple> removed = new ArrayList<>(affected);
    for (final Tuple tuple : removed) {
      tuples.remove(tuple);
      for (final long other : tuple.boundFacts()) {
        if (other == handle) {
          continue;
        }
        final Set<Tuple> siblings = byHandle.get(other);
        if (siblings != null) {
          siblings.remove(tuple);
          // The empty set goes too. Leaving it would make byHandle grow with every handle the
          // session has ever seen, which is exactly the leak §4.4's criterion looks for.
          if (siblings.isEmpty()) {
            byHandle.remove(other);
          }
        }
      }
    }
    return removed;
  }

  /**
   * The materialised matches, in the order they were derived.
   *
   * <p><strong>Nothing in main source calls this since §4.3.</strong> {@code matchesOf} reads the
   * pending set instead, so this is a test and diagnostic accessor now. It is kept unmodifiable for
   * the reason it always was -- a caller iterating it while something mutates the memory gets a
   * clear failure at the mutation rather than a confusing one at the next read -- though the wrapper
   * never made that safe, since it wraps a live set.
   *
   * @return the tuples, in derivation order
   */
  Set<Tuple> tuples() {
    return Collections.unmodifiableSet(tuples);
  }

  /**
   * How many matches are held. A diagnostic, and what the steady-state test asserts on.
   *
   * @return the match count
   */
  int size() {
    return tuples.size();
  }

  /**
   * How many handles the reverse index still tracks.
   *
   * <p>Exposed because "the memory reached a steady state" is not provable from {@link #size()}
   * alone: a session could hold no matches while the index quietly retained an entry per fact ever
   * inserted. §9's Phase 3 criterion asks for a steady-state heap, and this is the half of it that
   * a leak would hide in.
   *
   * @return the number of handles with at least one match
   */
  int indexedHandles() {
    return byHandle.size();
  }
}
