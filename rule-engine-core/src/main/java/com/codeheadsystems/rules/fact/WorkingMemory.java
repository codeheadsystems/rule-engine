package com.codeheadsystems.rules.fact;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The per-session set of currently-asserted facts (spec §2.4).
 *
 * <p>Single-writer: a session is never shared across threads (§5.1), so nothing here synchronises
 * and the handle and recency counters are plain {@code long}s rather than atomics.
 *
 * <p><strong>Payload ownership is the contract to get right.</strong> {@code ObjectNode} and
 * {@code ArrayNode} are mutable containers and Jackson has no immutable tree type, so if code
 * outside the engine mutates a payload after insert, index entries point at values the fact no
 * longer has -- silent wrong matches, not a crash. Hence: {@link #insert} and {@link #update} both
 * deep-copy, and the {@code Owned} variants skip the copy under an explicit transfer of ownership.
 *
 * <p>Be honest about the cost: a deep copy is {@code O(payload size)} on every insert and every
 * update, and for large payloads at high rates it is frequently the largest single per-operation
 * cost in the engine -- larger than the alpha tests it protects. That is what the {@code Owned}
 * variants are for.
 */
public interface WorkingMemory {

  /**
   * Inserts a fact, deep-copying the payload (§2.2).
   *
   * @param type the fact type. Given explicitly rather than inferred from the payload's shape or a
   *     magic {@code $type} field: inferring from structure is a footgun at scale, and a magic
   *     field couples the DSL to the payload's contents
   * @param payload the payload; copied, so the caller may keep and mutate its own reference
   * @return the new fact's handle
   */
  FactHandle insert(String type, JsonNode payload);

  /**
   * Inserts a fact without copying the payload.
   *
   * <p>Ownership transfers to the engine: <strong>the caller must never touch {@code payload}
   * again.</strong> Correct at an ingestion boundary where the tree was just parsed from bytes and
   * nobody else holds a reference -- a common case where the copy is pure waste.
   *
   * @param type the fact type
   * @param payload the payload; retained, not copied
   * @return the new fact's handle
   */
  FactHandle insertOwned(String type, JsonNode payload);

  /**
   * Replaces a fact's payload, deep-copying it (§2.2, §3.4.1).
   *
   * <p>The handle stays valid and identical. Recency advances, and the change propagates,
   * <em>iff</em> at least one path the rule set tests actually changed; an update touching nothing
   * tested replaces the stored payload and returns without touching the network.
   *
   * <p>Note that the stored payload is <strong>always</strong> replaced, whether or not anything
   * propagates. Only propagation is conditional; storage never is. Getting this backwards is the
   * most tempting way to reintroduce the stale-payload bug the design exists to prevent.
   *
   * @param handle the fact to update
   * @param newPayload the replacement payload; copied
   * @throws java.util.NoSuchElementException if the handle is not in working memory
   */
  void update(FactHandle handle, JsonNode newPayload);

  /**
   * Replaces a fact's payload without copying it. Ownership transfers; see {@link #insertOwned}.
   *
   * @param handle the fact to update
   * @param newPayload the replacement payload; retained, not copied
   * @throws java.util.NoSuchElementException if the handle is not in working memory
   */
  void updateOwned(FactHandle handle, JsonNode newPayload);

  /**
   * Reserves the next handle without inserting anything (spec §4.6).
   *
   * <p>This exists for one reason: an {@code insertFact} action allocates its handle at
   * <strong>stage</strong> time, not at commit, so a later action in the same right-hand side can
   * reference the fact it is about to create -- a common shape is "insert a derived fact, then emit
   * an event naming it". Only the propagation is deferred; the fact becomes visible to matching at
   * commit.
   *
   * <p>A reserved handle that is never used is simply a gap in the id sequence, which nothing
   * depends on being dense.
   *
   * @return a handle no fact holds yet
   */
  FactHandle reserveHandle();

  /**
   * Returns a reserved handle unused, so the reservation does not accumulate.
   *
   * <p>A right-hand side reserves a handle when it stages an insert, and that insert may never
   * happen: a later action in the same firing can retract it, cancelling both effects, and a dry
   * run stages every insert and applies none. Without this, those reservations would accumulate for
   * the life of the session -- harmless in a one-shot batch, a slow leak in the long-lived
   * streaming session Phase 3 targets.
   *
   * <p>Releasing a handle does not make its id available again. Ids are never reused: refraction is
   * keyed on {@code (ruleId, handles)}, so handing an old id to a new fact would let a rule that had
   * already fired be silently suppressed against different data.
   *
   * @param handle a handle from {@link #reserveHandle()} that will not be used
   */
  void releaseHandle(FactHandle handle);

  /**
   * Inserts a fact under a handle obtained from {@link #reserveHandle()}, without copying.
   *
   * <p>Ownership transfers, as with {@link #insertOwned}.
   *
   * <p><strong>Records {@link Origin#DERIVED}</strong>, because this is the door a firing rule
   * inserts through, and that is what keeps {@link #exportFacts()} from double-counting on a §5.6
   * restart. It follows that a caller who drives {@link #reserveHandle()} and this method
   * themselves, rather than letting a right-hand side do it, produces facts the export deliberately
   * drops. Use {@link #insert} or {@link #insertOwned} for facts that are the session's input.
   *
   * @param handle a previously reserved handle
   * @param type the fact type
   * @param payload the payload; retained, not copied
   * @throws IllegalArgumentException if the handle was not reserved, or is already in use
   */
  void insertReserved(FactHandle handle, String type, JsonNode payload);

  /**
   * Removes a fact.
   *
   * <p>Retracting an unknown handle is a no-op, not an error: an RHS may legitimately retract a
   * fact another action in the same firing already removed.
   *
   * @param handle the fact to remove
   */
  void retract(FactHandle handle);

  /**
   * Looks up a fact.
   *
   * @param handle the fact to read
   * @return the fact, or empty if it is not in working memory
   */
  Optional<Fact> get(FactHandle handle);

  /**
   * All currently-asserted facts of one type.
   *
   * <p><strong>This is a snapshot, not a live view.</strong> Streaming straight off the backing
   * collection throws {@code ConcurrentModificationException} the moment an RHS inserts or
   * retracts while iterating -- not an exotic case but the normal one, since {@code then} blocks
   * routinely insert derived facts while a {@code callFunction} walks the same type. The handle
   * list is materialised up front and dereferenced lazily, so facts retracted mid-iteration
   * disappear from the stream and facts inserted mid-iteration are invisible to an already-started
   * stream.
   *
   * <p><strong>Iteration is by ascending handle id, that is, insertion order</strong> -- not by
   * recency. Handle ids are allocated in insertion order and never change, so this is stable.
   * Recency would not be: an update moves a fact to the end of a recency ordering, so iteration
   * order would silently depend on unrelated update traffic. This ordering is part of the
   * determinism contract (§7.3).
   *
   * @param type the fact type
   * @return a stream over the snapshot, in ascending handle id
   */
  Stream<Fact> factsOfType(String type);

  /**
   * The facts the caller inserted, in insertion order, ready to replay into another session (§5.6).
   *
   * <p>Three properties, each of which the drain-and-restart story would be wrong without.
   *
   * <p><strong>Asserted facts only.</strong> A fact a right-hand side derived is excluded, because
   * the session it is replayed into re-derives it as soon as it fires. Including them would
   * double-count every derivation, and the duplicates would look like an engine bug rather than an
   * export bug. See {@link Origin}.
   *
   * <p><strong>Ascending handle id, which is insertion order.</strong> §7.3 promises the same firing
   * sequence for the same facts <em>in the same insertion order</em>, so a drained-and-replayed
   * session that reordered its inputs could fire differently from a continuous one while looking
   * entirely healthy. Handle ids are monotonic and never reused, so this is insertion order by
   * construction rather than by bookkeeping.
   *
   * <p><strong>Current payloads, not original ones.</strong> A fact updated five times exports once,
   * at its latest value. Replaying the update history would be a different feature (and would need
   * a history the engine does not keep); replaying the current state is what rebuilds an equivalent
   * session.
   *
   * <p>Payloads are deep-copied, so the returned list shares nothing with this working memory.
   *
   * @return the externally-inserted facts, ascending by handle id
   */
  List<ExportedFact> exportFacts();

  /**
   * The number of facts currently asserted.
   *
   * @return the count, which the {@code maxFacts} bound (§4.7) is checked against
   */
  int size();
}
