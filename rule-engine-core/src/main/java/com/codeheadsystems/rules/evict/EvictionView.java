package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.fact.Fact;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only working memory, as an {@link EvictionPolicy} sees it (spec §4.4).
 *
 * <p><strong>Deliberately not {@code WorkingMemory}.</strong> That interface carries insert, update
 * and retract, and handing a selection function the ability to mutate the thing it is selecting
 * from is the kind of thing nobody does on purpose. A policy answers a question; the session acts
 * on the answer.
 *
 * <p>Every method here is a snapshot taken at the moment the policy is consulted, which is a point
 * at which nothing else is running: §4.4's eviction is performed between operations, never inside
 * one. A policy may therefore read as much of this as it needs without the answers moving
 * underneath it.
 */
public interface EvictionView {

  /**
   * How many facts working memory holds.
   *
   * @return the total count
   */
  int size();

  /**
   * How many facts of one type working memory holds.
   *
   * <p>Constant-time, which is what lets a capped policy decline in one comparison on the inserts
   * where nothing is over its bound -- the overwhelming majority of them.
   *
   * @param type the fact type
   * @return the count, or zero if no fact of that type is present
   */
  int sizeOfType(String type);

  /**
   * The fact types currently present.
   *
   * <p>Ordered by when each type's oldest surviving fact arrived, near enough: a type is added when
   * its first fact does and dropped when its last one goes, so a type that empties and refills
   * reappears at the end. The guarantee that matters is that the order is <em>stable</em> for a
   * given sequence of inputs, because iteration order here reaches the agenda through the victims a
   * policy returns and §7.3 makes that a contract.
   *
   * @return the types, in a deterministic order
   */
  Set<String> types();

  /**
   * Every fact, least recently used first.
   *
   * <p>Ordered by {@code recency} rather than by handle id, and the two differ: an effective update
   * bumps a fact's recency (§3.4.1 step 4), so a fact inserted first and updated since is not the
   * oldest. Recency is the ordering §4.4 names for eviction, and it is derived from the input
   * rather than from a clock, which is what keeps a policy built on it deterministic.
   *
   * <p>Lazy. A policy taking the two oldest facts dereferences two, not all of them.
   *
   * @return the facts, ascending by recency
   */
  Stream<Fact> oldestFirst();

  /**
   * Every fact of one type, least recently used first.
   *
   * <p>Lazy like {@link #oldestFirst()}, and its own order rather than that one filtered -- taking
   * the oldest few of a type costs what it takes, not what it takes plus everything older of other
   * types. The difference is the whole workload this exists for: reference data is loaded first and
   * therefore sits at the front of the global order permanently, so a filter would walk all of it
   * on every insert once the capped type is at its bound.
   *
   * @param type the fact type
   * @return the facts of that type, ascending by recency
   */
  Stream<Fact> oldestOfType(String type);
}
