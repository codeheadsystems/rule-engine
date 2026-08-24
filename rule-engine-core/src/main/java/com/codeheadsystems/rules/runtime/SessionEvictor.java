package com.codeheadsystems.rules.runtime;

import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.evict.EvictionView;
import com.codeheadsystems.rules.fact.DefaultWorkingMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs a session's {@link EvictionPolicy}, and keeps the order it selects over (spec §4.4).
 *
 * <p>One per session, created only when a policy is configured. A session without one holds a null
 * reference and pays a null check per insert, which is the cost §4.4's mechanism is allowed to
 * impose on the one-shot and batch sessions that will never need it.
 *
 * <h2>Why the recency order lives here and not in working memory</h2>
 *
 * <p>Eviction needs the facts ordered by {@code recency}, and working memory does not hold them
 * that way. Its {@code byHandle} map is insertion-ordered, and §3.4.1's update re-puts a key at its
 * original position -- so after any effective update the two orders disagree, which is precisely
 * when eviction would pick the wrong victim. {@code exportFacts} sorts explicitly for the same
 * reason.
 *
 * <p>Rather than re-order working memory, which would change an order §7.3 already guarantees to
 * callers, this keeps its own: a {@code LinkedHashSet} of handle ids, appended on insert and
 * removed on retract. The update path needs no case of its own, and that is the point -- §3.4.1
 * implements an effective update as a retract followed by an insert on the same handle, so the id
 * is removed and re-appended, arriving at the newest position exactly as its bumped recency says it
 * should. A no-op update propagates neither callback and correctly moves nothing.
 *
 * <h2>What it costs when nothing is over its cap</h2>
 *
 * <p>One counter increment per insert, and one policy call per fire cycle or caller insert that
 * followed one. The policy call is gated on an insert having happened since the last one, because
 * a cap can only be exceeded by an insert -- a retract cannot put a type over its bound, and
 * neither can an update, which is a retract and an insert of the same handle. Without that gate a
 * fire loop consults the policy once per firing, which is a scan per firing for an answer that
 * cannot have changed.
 */
final class SessionEvictor implements EvictionView {

  private final EvictionPolicy policy;
  private final DefaultWorkingMemory workingMemory;
  private final List<RuleEngineListener> listeners;
  private final boolean strict;

  /** Handle ids, oldest recency first. See the class documentation for why it is not shared. */
  private final Set<Long> byRecency = new LinkedHashSet<>();

  /**
   * The same order, split by fact type, and the split is not a convenience.
   *
   * <p>A per-type cap needs the oldest few facts <em>of one type</em>, and filtering the global
   * order for them costs everything older that is not of that type. That is not a rare shape, it is
   * the shape {@code perType} exists for: reference data is loaded first, so it sits at the front of
   * the global order permanently, and a session at its cap would walk all of it on every insert --
   * dereferencing each one, which under strict mode is a payload deep copy, twice over because
   * strict consults the policy twice. Keeping the split makes that walk O(what is taken) instead.
   *
   * <p>It also replaces the per-type counter this class used to keep separately: a type's size is
   * its set's size, which is one structure to maintain instead of two that could disagree.
   */
  private final Map<String, Set<Long>> byRecencyOfType = new LinkedHashMap<>();

  private long evicted;

  /** The same total, split by fact type, so a diagnostic can name the type that lost facts. */
  private final Map<String, Long> evictedByType = new LinkedHashMap<>();
  private int insertsSinceCheck;
  private boolean evicting;

  /**
   * Creates the evictor.
   *
   * @param policy the configured policy
   * @param workingMemory the session's working memory, retracted through and read from
   * @param listeners the session's listeners, in registration order
   * @param strict whether to run strict-mode contract checks (§7.5)
   */
  SessionEvictor(final EvictionPolicy policy, final DefaultWorkingMemory workingMemory,
      final List<RuleEngineListener> listeners, final boolean strict) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
    this.listeners = Objects.requireNonNull(listeners, "listeners");
    this.strict = strict;
  }

  /**
   * Records a fact's arrival.
   *
   * @param fact the fact that entered working memory
   */
  void factInserted(final Fact fact) {
    byRecency.add(fact.handle().id());
    byRecencyOfType.computeIfAbsent(fact.type(), ignored -> new LinkedHashSet<>())
        .add(fact.handle().id());
    insertsSinceCheck++;
  }

  /**
   * Records a fact's departure.
   *
   * @param fact the fact leaving working memory
   */
  void factRetracted(final Fact fact) {
    byRecency.remove(fact.handle().id());
    final Set<Long> ofType = byRecencyOfType.get(fact.type());
    if (ofType != null) {
      ofType.remove(fact.handle().id());
      if (ofType.isEmpty()) {
        byRecencyOfType.remove(fact.type());
      }
    }
  }

  /**
   * Consults the policy and retracts what it selects.
   *
   * <p>Called only at quiescence -- after a caller's insert, and at the top of a fire cycle -- and
   * never between a right-hand side's staging and its commit. That restriction is the difference
   * between eviction and a defect: §4.6 stages every effect and then applies it, so an eviction
   * landing in the middle could retract a fact the firing activation binds, and the firing record
   * would name a handle that no longer exists.
   */
  void evictIfNeeded() {
    if (insertsSinceCheck == 0 || evicting) {
      return;
    }
    insertsSinceCheck = 0;
    final List<FactHandle> victims = select();
    if (victims.isEmpty()) {
      return;
    }
    /*
     * Checked here, against the state the policy was shown, rather than inside the loop below.
     * Inside the loop the two faults are indistinguishable: a handle the policy invented looks
     * exactly like a handle a listener retracted while this same eviction was running, and blaming
     * the policy for the second is a strict-mode failure that sends a reader to the wrong file.
     * Checking before anything is retracted also means the throw leaves working memory untouched
     * rather than partially evicted.
     */
    if (strict) {
      for (final FactHandle victim : victims) {
        if (!byRecency.contains(victim.id())) {
          throw new IllegalStateException(
              "eviction policy " + policy + " selected handle " + victim.id()
                  + ", which is not in working memory");
        }
      }
    }
    evicting = true;
    try {
      for (final FactHandle victim : victims) {
        final Fact fact = workingMemory.get(victim).orElse(null);
        if (fact == null) {
          // Gone since it was selected, which the check above has already ruled out being the
          // policy's doing: a listener retracted it while this eviction was in progress.
          continue;
        }
        for (final RuleEngineListener listener : listeners) {
          listener.onEvicted(fact);
        }
        if (workingMemory.get(victim).isEmpty()) {
          // That listener retracted it itself, so the call below would do nothing and counting it
          // would have evictedCount() report work this session did not do.
          continue;
        }
        // The ordinary retract path, deliberately. §4.4's whole mechanism is that this one call
        // cascades to the node memories, their indexes, the refraction memory and the beta memory,
        // none of which this class knows about or should.
        workingMemory.retract(victim);
        evicted++;
        evictedByType.merge(fact.type(), 1L, Long::sum);
      }
    } finally {
      evicting = false;
    }
  }

  /**
   * How many facts this session has evicted.
   *
   * @return the count
   */
  long evictedCount() {
    return evicted;
  }

  /**
   * How many facts of each type this session has evicted.
   *
   * @return the counts, holding an entry only for a type something was evicted from
   */
  Map<String, Long> evictedByType() {
    return evictedByType;
  }

  @Override
  public int size() {
    return workingMemory.size();
  }

  @Override
  public int sizeOfType(final String type) {
    final Set<Long> ofType = byRecencyOfType.get(type);
    return ofType == null ? 0 : ofType.size();
  }

  @Override
  public Set<String> types() {
    return new LinkedHashSet<>(byRecencyOfType.keySet());
  }

  @Override
  public Stream<Fact> oldestFirst() {
    /*
     * Over the live order, and lazily, because the alternative is quadratic. Copying the id list
     * first is the safe-looking version and it costs O(facts) on every call -- which sounds like
     * nothing until you notice when this is called: a session at its cap evicts on every insert, so
     * the copy is paid per insert, and it grows with the cap. That is a scan per insert for a
     * policy that wanted to look at one fact, and it is worst for exactly the large caps a
     * streaming session runs with.
     *
     * Safe because of when selection happens rather than because of a defensive copy. §4.4's policy
     * is consulted at quiescence, over a view with no mutator on it, and {@link #select} forces the
     * policy's answer into a copy before {@link #evictIfNeeded} retracts anything -- so nothing can
     * mutate this set between the first element a policy pulls and the last. That copy is the only
     * thing standing between a policy that returns a lazily-backed list and a
     * ConcurrentModificationException in the retract loop, which is why it is named here: it reads
     * like removable defensive habit and is not. A policy that contrived to mutate working memory
     * anyway, through something it captured rather than through the view it was handed, gets a
     * ConcurrentModificationException: a clear failure at the mutation rather than a confusing one
     * later, which is the trade BetaMemory.tuples() makes for the same reason.
     */
    return dereference(byRecency);
  }

  /**
   * Turns an order of handle ids into the facts they name, lazily.
   *
   * @param ids the handle ids, in the order to produce them
   * @return the facts, skipping any id whose fact has gone
   */
  private Stream<Fact> dereference(final Set<Long> ids) {
    return ids.stream()
        .map(id -> workingMemory.get(new FactHandle(id)).orElse(null))
        .filter(Objects::nonNull);
  }

  @Override
  public Stream<Fact> oldestOfType(final String type) {
    // Its own order, not a filter of the global one. See byRecencyOfType for what the filter costs.
    final Set<Long> ofType = byRecencyOfType.get(Objects.requireNonNull(type, "type"));
    return ofType == null ? Stream.empty() : dereference(ofType);
  }

  /**
   * Asks the policy, under the strict-mode determinism check.
   *
   * @return the victims, defensively copied
   */
  private List<FactHandle> select() {
    final List<FactHandle> victims = List.copyOf(policy.selectVictims(this));
    /*
     * Copied before anything is retracted, and that is not defensive habit. A policy may return a
     * list backed by the stream this class handed it -- Stream.toList is materialised, but nothing
     * in the interface requires one -- and retracting mutates the order that stream reads from.
     */
    if (strict) {
      /*
       * §7.5's check for the contract EvictionPolicy states and cannot enforce: the selection must
       * be a deterministic function of the view. Nothing has changed between these two calls, so
       * a difference means the policy read something that is not in the view -- a clock, a counter,
       * a HashMap's iteration order -- and that difference reaches the firing sequence, where §7.3
       * says it must not. Too expensive for production, which is exactly what strict mode is for.
       */
      final List<FactHandle> again = List.copyOf(policy.selectVictims(this));
      if (!victims.equals(again)) {
        throw new IllegalStateException(
            "eviction policy " + policy + " is not deterministic: selected " + victims
                + " then " + again + " from the same working memory");
      }
    }
    return victims;
  }
}
