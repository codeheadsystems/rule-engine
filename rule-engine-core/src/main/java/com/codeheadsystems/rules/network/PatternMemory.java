package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.value.Canonical;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One pattern's alpha memory and its indexes: per-session mutable state (spec §3.2.3, §3.3).
 *
 * <p>The membership set holds exactly the facts satisfying the conjunction of the pattern's alpha
 * tests. It is maintained incrementally, so a fire cycle enumerates matching facts rather than
 * scanning every fact of the type.
 *
 * <p><strong>Ordering: ascending handle id, via {@code TreeSet}.</strong> §3.3 suggests
 * {@code LinkedHashSet} buckets for deterministic iteration, and insertion order would indeed be
 * ascending id -- until the first update. §3.4.1 makes an update a retract followed by a re-assert,
 * which removes a handle and re-adds it at the <em>end</em> of an insertion-ordered set, so
 * iteration order would start depending on unrelated update traffic. That is the same reasoning
 * §2.4 gives for ordering {@code factsOfType} by handle id rather than by recency.
 *
 * <p>Firing order does not depend on this -- conflict resolution is a total order on the match
 * itself, precisely so that enumeration order cannot reach the agenda. What ascending order buys is
 * that <em>this</em> matcher enumerates reproducibly across runs and hosts, which is what makes a
 * failure reproducible and a listener trace stable.
 *
 * <p>It does <strong>not</strong> mean the two matchers enumerate alike. An earlier version of this
 * comment claimed that, and it stopped being true when the join planner started choosing the
 * binding order per fire cycle: the network and the oracle now create activations in different
 * orders and only the <em>firing</em> sequence is guaranteed to agree. The {@code O(log n)}
 * membership cost is therefore bought for reproducibility alone, and is a profiling target.
 */
public final class PatternMemory implements NodeMemory {

  /** Shared empty result, so an index miss allocates nothing. */
  private static final SortedSet<Long> EMPTY =
      java.util.Collections.unmodifiableSortedSet(new TreeSet<>());

  private final SortedSet<Long> members = new TreeSet<>();
  private final Map<JsonPointer, Map<Object, SortedSet<Long>>> hashIndexes = new LinkedHashMap<>();
  private final Map<JsonPointer, NavigableMap<BigDecimal, SortedSet<Long>>> sortedIndexes =
      new LinkedHashMap<>();

  /**
   * Creates a memory laid out for one pattern's index plan.
   *
   * @param plan which paths to index, from the compiled rule set
   */
  public PatternMemory(final IndexPlan plan) {
    plan.hashed().forEach(path -> hashIndexes.put(path, new LinkedHashMap<>()));
    plan.sorted().forEach(path -> sortedIndexes.put(path, new TreeMap<>()));
  }

  /**
   * Adds a fact that satisfies the pattern.
   *
   * @param handleId the fact's handle id
   * @param payload the fact's payload, read to compute index keys
   */
  public void add(final long handleId, final JsonNode payload) {
    members.add(handleId);
    hashIndexes.forEach((path, index) ->
        hashKey(path, payload).ifPresent(key ->
            index.computeIfAbsent(key, ignored -> new TreeSet<>()).add(handleId)));
    sortedIndexes.forEach((path, index) ->
        orderKey(path, payload).ifPresent(key ->
            index.computeIfAbsent(key, ignored -> new TreeSet<>()).add(handleId)));
  }

  /**
   * Removes a fact.
   *
   * <p><strong>The payload must be the one the fact had when it was added.</strong> Index keys are
   * recomputed here to find the buckets to remove from, so a caller that passes the <em>new</em>
   * payload during an update leaves the handle in its old bucket forever, and that orphaned entry
   * produces phantom matches indefinitely. §3.4.1 orders the update's steps -- retract against the
   * old fact, and only then install the new one -- specifically so this cannot happen, and §10
   * audits it.
   *
   * @param handleId the fact's handle id
   * @param payload the payload the fact had when it was added
   */
  public void remove(final long handleId, final JsonNode payload) {
    members.remove(handleId);
    hashIndexes.forEach((path, index) ->
        hashKey(path, payload).ifPresent(key -> discard(index, key, handleId)));
    sortedIndexes.forEach((path, index) ->
        orderKey(path, payload).ifPresent(key -> discard(index, key, handleId)));
  }

  /**
   * Every fact matching the pattern.
   *
   * @return the handle ids, ascending
   */
  public SortedSet<Long> members() {
    return members;
  }

  /**
   * How many facts match the pattern. Used to choose the smaller side of a join.
   *
   * @return the membership count
   */
  public int size() {
    return members.size();
  }

  /**
   * Probes an equality index.
   *
   * @param path the indexed path
   * @param value the value to match, taken from the other side of a join
   * @return the matching handle ids ascending, or empty when this probe cannot be served -- either
   *     the path is not hash-indexed, or the value has no canonical hash key and so could never
   *     have been indexed in the first place
   */
  public Optional<SortedSet<Long>> probeEqual(final JsonPointer path, final JsonNode value) {
    final Map<Object, SortedSet<Long>> index = hashIndexes.get(path);
    if (index == null) {
      return Optional.empty();
    }
    // A value with no canonical hash key -- absent, an explicit null, or a container -- cannot be
    // looked up, because facts holding such values were never filed under a key either. Reporting
    // "index applied, zero candidates" would be a silent lost match: §2.6.1 says null equals null
    // and that two objects compare structurally, so those matches are real. Report "no index
    // usable" instead and let the caller scan, which is the same discipline probeRange already
    // follows for a non-numeric bound.
    return Canonical.hashKey(value).map(key -> index.getOrDefault(key, EMPTY));
  }

  /**
   * Probes a range index.
   *
   * @param path the indexed path
   * @param lower the lower bound, or empty for unbounded
   * @param lowerInclusive whether the lower bound itself matches
   * @param upper the upper bound, or empty for unbounded
   * @param upperInclusive whether the upper bound itself matches
   * @return the matching handle ids ascending, or empty when the path is not range-indexed
   */
  public Optional<SortedSet<Long>> probeRange(final JsonPointer path,
      final Optional<JsonNode> lower, final boolean lowerInclusive,
      final Optional<JsonNode> upper, final boolean upperInclusive) {
    final NavigableMap<BigDecimal, SortedSet<Long>> index = sortedIndexes.get(path);
    if (index == null) {
      return Optional.empty();
    }
    final Optional<BigDecimal> from = lower.flatMap(PatternMemory::decimal);
    final Optional<BigDecimal> to = upper.flatMap(PatternMemory::decimal);
    if (lower.isPresent() && from.isEmpty() || upper.isPresent() && to.isEmpty()) {
      // A bound that is not a number cannot be located in a numeric index. Report "no index" so the
      // caller falls back to a scan rather than silently matching nothing.
      return Optional.empty();
    }
    NavigableMap<BigDecimal, SortedSet<Long>> view = index;
    if (from.isPresent()) {
      view = view.tailMap(from.get(), lowerInclusive);
    }
    if (to.isPresent()) {
      view = view.headMap(to.get(), upperInclusive);
    }
    final SortedSet<Long> matching = new TreeSet<>();
    view.values().forEach(matching::addAll);
    return Optional.of(matching);
  }

  /**
   * Removes one handle from one bucket, dropping the bucket when it empties.
   *
   * @param index the index to update
   * @param key the bucket key
   * @param handleId the handle to remove
   * @param <K> the key type
   */
  private static <K> void discard(final Map<K, SortedSet<Long>> index, final K key,
      final long handleId) {
    final SortedSet<Long> bucket = index.get(key);
    if (bucket != null) {
      bucket.remove(handleId);
      if (bucket.isEmpty()) {
        index.remove(key);
      }
    }
  }

  /**
   * The canonical hash key for a path, or empty when the value cannot be one.
   *
   * @param path the path
   * @param payload the payload to read
   * @return the key
   */
  private static Optional<Object> hashKey(final JsonPointer path, final JsonNode payload) {
    return Canonical.hashKey(new JsonPointerAccessor(path).get(payload));
  }

  /**
   * The ordering key for a path, or empty when the value is not numeric.
   *
   * @param path the path
   * @param payload the payload to read
   * @return the key
   */
  private static Optional<BigDecimal> orderKey(final JsonPointer path, final JsonNode payload) {
    return decimal(new JsonPointerAccessor(path).get(payload));
  }

  /**
   * A value's numeric form, if it has one.
   *
   * <p>Deliberately does <em>not</em> strip trailing zeros: this is the ordering path, and §2.6.2 is
   * explicit that stripping belongs to the hashing path only, where it exists to make
   * {@code equals} and {@code hashCode} agree. A {@code TreeMap} uses {@code compareTo} and is
   * therefore already correct without it.
   *
   * @param value the value
   * @return its decimal form, or empty if it is not a finite number
   */
  private static Optional<BigDecimal> decimal(final JsonNode value) {
    if (!value.isNumber()) {
      return Optional.empty();
    }
    if ((value.isDouble() || value.isFloat()) && !Double.isFinite(value.doubleValue())) {
      return Optional.empty();
    }
    return Optional.of(value.decimalValue());
  }

  /**
   * A diagnostic rendering.
   *
   * @return the membership count and how many indexes are maintained
   */
  @Override
  public String toString() {
    return "PatternMemory[" + members.size() + " facts, "
        + (hashIndexes.size() + sortedIndexes.size()) + " indexes]";
  }

  /**
   * Every indexed bucket count, for tests that assert an index is actually being maintained.
   *
   * @return the number of distinct keys across every index
   */
  public int indexedKeyCount() {
    final Collection<Map<Object, SortedSet<Long>>> hashed = hashIndexes.values();
    int keys = 0;
    for (final Map<Object, SortedSet<Long>> index : hashed) {
      keys += index.size();
    }
    for (final NavigableMap<BigDecimal, SortedSet<Long>> index : sortedIndexes.values()) {
      keys += index.size();
    }
    return keys;
  }

  /**
   * The paths this memory maintains a hash index for.
   *
   * @return the hash-indexed paths
   */
  public Set<JsonPointer> hashedPaths() {
    return hashIndexes.keySet();
  }
}
