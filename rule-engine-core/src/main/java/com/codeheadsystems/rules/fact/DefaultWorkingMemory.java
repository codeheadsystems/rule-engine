package com.codeheadsystems.rules.fact;

import com.codeheadsystems.rules.rule.TestedPaths;
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.SchemaViolationException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The single-writer working memory (spec §2.4), implementing §3.4.1's update algorithm.
 *
 * <p>Storage is two ordered maps: handle to fact, and fact type to the handles of that type. Both
 * are {@code LinkedHashMap}/{@code LinkedHashSet} rather than hash-ordered, because iteration order
 * reaches the agenda and §7.3 makes determinism a contract. Handle ids are allocated monotonically
 * and never reused, so per-type insertion order <em>is</em> ascending handle id.
 *
 * <p><strong>Node memories are never a bare array indexed by handle id.</strong> Dense ids tempt
 * you into {@code Fact[] byId} -- O(1), no hashing -- and that works exactly until the first
 * retract, after which the array is sparse and grows monotonically with total inserts ever made.
 * That is an unbounded leak in precisely the long-lived streaming session Phase 3 targets.
 */
public final class DefaultWorkingMemory implements WorkingMemory {

  private final TestedPaths testedPaths;
  private final FactSchemas schemas;
  private final WorkingMemoryObserver observer;
  private final boolean strict;

  private final Map<Long, Fact> byHandle = new LinkedHashMap<>();
  private final Map<String, LinkedHashSet<Long>> byType = new LinkedHashMap<>();
  private final Set<Long> reserved = new LinkedHashSet<>();
  private final Set<Long> retracting = new LinkedHashSet<>();

  private long nextHandleId;
  private long recencyCounter;
  private long skippedUpdates;
  private long propagatedUpdates;

  /**
   * Creates a working memory.
   *
   * @param testedPaths the compiled tested-path artifact, which the update diff walks (§3.4.1)
   * @param schemas the optional fact-payload schemas (§2.3); {@link FactSchemas#none()} to validate
   *     nothing, which is the default and needs no setup
   * @param observer the session's hook for everything keyed on a handle
   * @param strict whether strict-mode contract checks are enabled (§7.5). Never enable this in
   *     production: the checks here are O(payload) per operation
   */
  public DefaultWorkingMemory(final TestedPaths testedPaths, final FactSchemas schemas,
      final WorkingMemoryObserver observer,
      final boolean strict) {
    this.testedPaths = Objects.requireNonNull(testedPaths, "testedPaths");
    this.schemas = Objects.requireNonNull(schemas, "schemas");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.strict = strict;
  }

  @Override
  public FactHandle insert(final String type, final JsonNode payload) {
    return insertOwned(type, payload.deepCopy());
  }

  @Override
  public FactHandle insertOwned(final String type, final JsonNode payload) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    validate(type, payload);
    final FactHandle handle = new FactHandle(nextHandleId++);
    final Fact fact = new Fact(handle, type, payload, ++recencyCounter, Origin.ASSERTED);
    byHandle.put(handle.id(), fact);
    byType.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(handle.id());
    observer.factInserted(fact);
    return handle;
  }

  @Override
  public FactHandle reserveHandle() {
    final FactHandle handle = new FactHandle(nextHandleId++);
    reserved.add(handle.id());
    return handle;
  }

  @Override
  public void releaseHandle(final FactHandle handle) {
    reserved.remove(handle.id());
  }

  @Override
  public void insertReserved(final FactHandle handle, final String type, final JsonNode payload) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    /*
     * The reservation is consumed BEFORE validation, and the order is load-bearing. RhsExecutor
     * reserves a handle at stage time and explains at length why one must never escape unreleased:
     * "a rule that stages an insert and then fails to stage a later action would leak one handle id
     * per firing -- and under a skip-and-continue error policy that repeats for every match,
     * forever." A schema rejection is a new way for this call to throw, so it has to consume the
     * reservation on the way out too. It also keeps a data error from masking the invariant check
     * below, which is about the engine rather than about the fact.
     */
    if (!reserved.remove(handle.id())) {
      throw new IllegalArgumentException("handle " + handle.id() + " was not reserved");
    }
    validate(type, payload);
    // DERIVED, and this is the only place it is set. insertReserved is the only ENGINE path into
    // working memory that a right-hand side uses -- RhsExecutor touches working memory at several
    // sites and this is its only insert -- which is what makes one bit here enough to keep the §5.6
    // export honest. It is not the only path in existence: the method is public on WorkingMemory, so
    // a caller reserving a handle themselves gets a fact exportFacts() will drop. Documented on the
    // interface rather than defended against, because reserveHandle/insertReserved exists for the
    // engine's staging protocol and a caller using it has taken on the protocol.
    final Fact fact = new Fact(handle, type, payload, ++recencyCounter, Origin.DERIVED);
    byHandle.put(handle.id(), fact);
    byType.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).add(handle.id());
    observer.factInserted(fact);
  }

  @Override
  public void update(final FactHandle handle, final JsonNode newPayload) {
    Objects.requireNonNull(newPayload, "newPayload");
    if (strict) {
      rejectAliasing(require(handle).payload(), newPayload);
    }
    updateOwned(handle, newPayload.deepCopy());
  }

  /**
   * {@inheritDoc}
   *
   * <p>The six steps of §3.4.1, in the order that section specifies. The ordering of steps 3 and 4
   * -- retract against the old payload, <em>then</em> install the new one -- is the one thing here
   * that produces silently wrong output if reversed.
   */
  @Override
  public void updateOwned(final FactHandle handle, final JsonNode newPayload) {
    Objects.requireNonNull(newPayload, "newPayload");
    final Fact before = require(handle);
    validate(before.type(), newPayload);

    // Step 1, with §3.4.2's fast-path guard first: one structural walk that short-circuits on the
    // first difference. Producers re-sending unchanged records are extremely common in streaming
    // feeds, and this collapses the whole diff to a single comparison.
    final Set<JsonPointer> changed = before.payload().equals(newPayload)
        ? Set.of()
        : changedTestedPaths(before.type(), before.payload(), newPayload);

    // Step 2. Replace the stored payload at the SAME recency and return. The payload is always
    // replaced; only propagation is conditional.
    if (changed.isEmpty()) {
      final Fact unchanged = new Fact(handle, before.type(), newPayload, before.recency(),
          before.origin());
      byHandle.put(handle.id(), unchanged);
      skippedUpdates++;
      observer.updateSkipped(unchanged);
      return;
    }

    // Step 3. The ordinary retract path, against the OLD fact, which is still installed.
    observer.factRetracted(before);

    // Step 4. Install: new payload, new recency, SAME handle. This is a reassert of the same
    // identity, not a new fact -- which is what keeps refraction keyed on (ruleId, handles) working
    // without special casing, because a match destroyed in step 3 and recreated in step 6 arrives
    // at selection with the same key.
    final Fact after = new Fact(handle, before.type(), newPayload, ++recencyCounter,
        before.origin());
    byHandle.put(handle.id(), after);

    // Step 5. Deliberately un-refract the rules that DO test a changed path -- and only those.
    observer.refractionInvalidated(handle, rulesTesting(before.type(), changed));

    // Step 6. The ordinary insert path, with the same handle.
    propagatedUpdates++;
    observer.factInserted(after);
    observer.updatePropagated(before, after, changed);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Note the ordering: {@code factRetracted} is dispatched <strong>before</strong> the fact is
   * removed, so that working memory still holds it during the callback. That is not incidental
   * symmetry -- it is the same state §3.4.1 step 3 guarantees for the retract half of an update,
   * and making the two paths agree is what lets a Phase 1 index observer compute its removal keys
   * the same way in both. If retract removed first and update did not, an observer that
   * dereferenced the handle would get nothing in one path and the old fact in the other, which is
   * exactly the orphaned-index bug §3.4.1 exists to prevent, arriving through the observer instead.
   *
   * <p>Dispatching while the fact is still installed does mean a re-entrant retract -- a listener
   * whose {@code onRetract} retracts the same fact -- would recurse without end, because the guard
   * that used to stop it was the removal itself. The recursion is blocked explicitly rather than by
   * reordering, because the ordering is the property §3.4.1 requires and the re-entrancy is not.
   */
  @Override
  public void retract(final FactHandle handle) {
    final Fact fact = byHandle.get(handle.id());
    if (fact == null || !retracting.add(handle.id())) {
      return;
    }
    try {
      observer.factRetracted(fact);
    } finally {
      retracting.remove(handle.id());
    }
    byHandle.remove(handle.id());
    final LinkedHashSet<Long> ofType = byType.get(fact.type());
    if (ofType != null) {
      ofType.remove(handle.id());
      if (ofType.isEmpty()) {
        byType.remove(fact.type());
      }
    }
    observer.refractionInvalidatedAll(handle);
  }

  @Override
  public Optional<Fact> get(final FactHandle handle) {
    return Optional.ofNullable(byHandle.get(handle.id())).map(this::exposed);
  }

  @Override
  public Stream<Fact> factsOfType(final String type) {
    final Collection<Long> ids = byType.get(type);
    if (ids == null || ids.isEmpty()) {
      return Stream.empty();
    }
    // Materialise the handle list up front; dereference lazily. See WorkingMemory#factsOfType.
    final List<Long> snapshot = new ArrayList<>(ids);
    return snapshot.stream()
        .map(byHandle::get)
        .filter(Objects::nonNull)
        .map(this::exposed);
  }

  @Override
  public List<ExportedFact> exportFacts() {
    // Sorted explicitly rather than relying on byHandle's insertion order. LinkedHashMap keeps a
    // re-put key at its original position, so today the two agree -- but that is an implementation
    // detail of update(), and §7.3's ordering guarantee is too load-bearing to rest on it.
    return byHandle.values().stream()
        .filter(fact -> fact.origin() == Origin.ASSERTED)
        .sorted(Comparator.comparingLong(fact -> fact.handle().id()))
        .map(fact -> new ExportedFact(fact.type(), fact.payload().deepCopy(), fact.origin()))
        .toList();
  }

  @Override
  public int size() {
    return byHandle.size();
  }

  /**
   * How many updates changed no tested path and therefore propagated nothing.
   *
   * <p>§9's Phase 1 exit criterion is that this is a <em>measured</em> no-op, asserted on a counter
   * rather than inferred: "no propagation happened" is trivially satisfied by an implementation
   * that never propagates.
   *
   * @return the count since this working memory was created
   */
  public long skippedUpdateCount() {
    return skippedUpdates;
  }

  /**
   * How many handles are reserved but not yet used.
   *
   * <p>A diagnostic, and a growth surface worth being able to assert on: a right-hand side reserves
   * a handle whenever it stages an insert, and any path that abandons the insert without handing
   * the reservation back leaks one id per firing. In a one-shot session that is invisible; in the
   * long-lived streaming session Phase 3 targets it is a slow leak, and a leak nothing can measure
   * is a leak nobody finds.
   *
   * @return the number of outstanding reservations
   */
  public int reservedHandleCount() {
    return reserved.size();
  }

  /**
   * How many updates changed at least one tested path and therefore ran retract and reassert.
   *
   * @return the count since this working memory was created
   */
  public long propagatedUpdateCount() {
    return propagatedUpdates;
  }

  /**
   * Applies the strict-mode payload copy on the way out (§7.5).
   *
   * @param fact the stored fact
   * @return the fact itself, or in strict mode a copy whose payload the caller may safely mutate
   */
  private Fact exposed(final Fact fact) {
    return strict
        ? new Fact(fact.handle(), fact.type(), fact.payload().deepCopy(), fact.recency(),
            fact.origin())
        : fact;
  }

  /**
   * Looks up a fact, or fails.
   *
   * @param handle the fact to read
   * @return the fact
   * @throws NoSuchElementException if the handle is not in working memory
   */
  private Fact require(final FactHandle handle) {
    final Fact fact = byHandle.get(handle.id());
    if (fact == null) {
      throw new NoSuchElementException("no fact for handle " + handle.id());
    }
    return fact;
  }

  /**
   * Rejects a payload its registered schema does not accept (§2.3).
   *
   * <p>Runs before the fact enters working memory, which is the whole point: §2.3 wants a malformed
   * fact to fail loudly at the boundary rather than to sit in memory quietly not matching every
   * rule that expects a field it lacks.
   *
   * <p>Not gated on strict mode, unlike the aliasing check below. Strict mode is for contracts the
   * spec states but cannot enforce, run in test and forbidden in production (§7.5). This is a
   * feature a caller opted into by registering a schema, and switching it off in production would
   * remove the protection exactly where the malformed data actually arrives.
   *
   * @param type the fact type
   * @param payload the payload about to be stored
   * @throws SchemaViolationException if a registered schema rejects it
   */
  private void validate(final String type, final JsonNode payload) {
    final List<String> violations = schemas.violations(type, payload);
    if (!violations.isEmpty()) {
      throw new SchemaViolationException(type, violations);
    }
  }

  /**
   * Step 1 of §3.4.1: which of the type's tested paths differ between two payloads.
   *
   * <p>Delegated to the compiled tested-path artifact, which chooses how to answer it: a prefix
   * trie proportional to the size of the change (§3.4.2), or the straightforward probe loop that
   * remains the interface's default and the trie's oracle.
   *
   * @param type the fact type
   * @param oldPayload the payload as stored
   * @param newPayload the replacement payload
   * @return the tested paths whose values differ
   */
  private Set<JsonPointer> changedTestedPaths(
      final String type, final JsonNode oldPayload, final JsonNode newPayload) {
    return testedPaths.changedPaths(type, oldPayload, newPayload);
  }

  /**
   * The rules that test at least one of the changed paths.
   *
   * @param type the fact type
   * @param changed the paths that changed
   * @return the ids of the affected rules
   */
  private Set<String> rulesTesting(final String type, final Set<JsonPointer> changed) {
    final Set<String> ruleIds = new LinkedHashSet<>();
    for (final JsonPointer path : changed) {
      ruleIds.addAll(testedPaths.rulesTesting(type, path));
    }
    return ruleIds;
  }

  /**
   * Strict-mode check: reject an update whose payload aliases the stored one (§2.2, §7.5).
   *
   * <p>This is reachable entirely through supported API -- {@code get(h).payload()} returns the
   * live node, mutate it, pass it back -- and it breaks the update algorithm in two places, either
   * of which is fatal on its own. The diff compares an object against itself, finds nothing changed
   * and returns without touching the network. And even if the diff were bypassed, the retract half
   * would compute its index-removal keys from a payload that has already become the new one.
   *
   * <p>Checking subtree sharing, not just reference identity of the roots, is what catches the
   * common shape: a caller that builds a new root object but reuses the stored {@code customer}
   * node inside it.
   *
   * <p><strong>Only mutable containers count.</strong> Jackson interns its immutable scalar nodes --
   * {@code BooleanNode.TRUE}, {@code NullNode.instance}, and small {@code IntNode} values -- so two
   * payloads built entirely independently will routinely share those instances. Flagging them would
   * reject honest callers for a shape that cannot possibly cause the bug: an immutable node has no
   * mutation to observe. The hazard §2.2 describes is specifically that {@code ObjectNode} and
   * {@code ArrayNode} are mutable containers, so those are what this looks for.
   *
   * @param stored the payload currently in working memory
   * @param candidate the proposed replacement
   * @throws IllegalArgumentException if the two share a mutable container
   */
  private static void rejectAliasing(final JsonNode stored, final JsonNode candidate) {
    final Map<JsonNode, Boolean> storedNodes = new IdentityHashMap<>();
    collectNodes(stored, storedNodes);
    final Map<JsonNode, Boolean> candidateNodes = new IdentityHashMap<>();
    collectNodes(candidate, candidateNodes);
    for (final JsonNode node : candidateNodes.keySet()) {
      if (storedNodes.containsKey(node)) {
        throw new IllegalArgumentException(
            "strict mode: update payload shares a node with the stored payload. "
                + "Build a fresh tree, or use the RHS setField action, which copies for you.");
      }
    }
  }

  /**
   * Collects every mutable container in a tree into an identity set.
   *
   * @param node the tree root
   * @param into the identity set to fill
   */
  private static void collectNodes(final JsonNode node, final Map<JsonNode, Boolean> into) {
    if (!node.isContainerNode()) {
      return;
    }
    if (into.put(node, Boolean.TRUE) != null) {
      return;
    }
    for (final JsonNode child : node) {
      collectNodes(child, into);
    }
  }
}
