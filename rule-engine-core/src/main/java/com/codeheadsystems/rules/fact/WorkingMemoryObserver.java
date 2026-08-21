package com.codeheadsystems.rules.fact;

import tools.jackson.core.JsonPointer;
import java.util.Set;

/**
 * The hook working memory calls as facts change, so that the session can maintain everything keyed
 * on a handle: node memories and indexes, the dirty-rule set, refraction, and listeners.
 *
 * <p>The method set mirrors §3.4.1's update algorithm rather than summarising it, and that is
 * deliberate. §3.4.1 specifies update as <em>retract, install, invalidate refraction, assert</em> --
 * four steps in that order, using the ordinary retract and insert paths -- and §10 audits the
 * ordering because getting it backwards produces orphaned index entries and permanent phantom
 * matches. An observer with a single {@code onUpdate} callback would let an implementation choose
 * its own order; this one cannot.
 *
 * <p>Every method defaults to doing nothing, so an implementation only overrides what it maintains.
 */
public interface WorkingMemoryObserver {

  /**
   * A fact entered working memory: from {@link WorkingMemory#insert}, or as step 6 of an update.
   *
   * @param fact the newly-asserted fact
   */
  default void factInserted(Fact fact) {
    // no-op by default
  }

  /**
   * A fact left working memory: from {@link WorkingMemory#retract}, or as step 3 of an update.
   *
   * <p><strong>Working memory still holds the fact when this is called</strong>, on both paths: a
   * plain retract dispatches before removing it, and an update dispatches before installing the new
   * payload. That is not incidental symmetry. The retract half must compute its index-removal keys
   * from the <em>old</em> payload, or the handle is never removed from its old bucket and the
   * orphaned entry produces phantom matches indefinitely -- and an observer that dereferenced the
   * handle would silently get different answers on the two paths if they disagreed.
   *
   * <p>Retract propagation must never re-evaluate a test. Remove by handle identity, and remove
   * index entries using keys computed from the payload the fact had when it was asserted.
   * Re-deriving "which entries should I remove" by running a test against current data is the
   * classic way incremental matching breaks.
   *
   * @param fact the fact being removed, carrying its pre-update payload
   */
  default void factRetracted(Fact fact) {
    // no-op by default
  }

  /**
   * Step 5 of an update: clear refraction for the rules that test a path which changed.
   *
   * <p>The scoping is essential and is why this callback carries rule ids rather than just a
   * handle. Clearing type-wide means an update to a field only rule B tests re-enables rule A's
   * already-fired match, so A fires twice for a reason no author can predict from reading their
   * rule (§4.4).
   *
   * @param handle the fact that changed
   * @param ruleIds the rules that test at least one changed path
   */
  default void refractionInvalidated(FactHandle handle, Set<String> ruleIds) {
    // no-op by default
  }

  /**
   * A fact was retracted outright, so <em>every</em> match binding it is eligible again.
   *
   * <p>Separate from {@link #refractionInvalidated}, and separate from {@link #factRetracted},
   * because the three fire in different combinations. A plain retract runs the network retract path
   * and clears all of the fact's refraction entries. An update runs the same network retract path
   * but clears refraction only for the rules that test a changed path -- clearing everything there
   * would make a rule re-fire on identical data every time any field of a fact it matched moved.
   *
   * @param handle the retracted fact's handle
   */
  default void refractionInvalidatedAll(FactHandle handle) {
    // no-op by default
  }

  /**
   * An update propagated: reported after the retract/install/assert sequence, for listeners.
   *
   * @param before the fact as it was
   * @param after the fact as it now is, with the same handle and a new recency
   * @param changedTestedPaths only the paths the rule set <em>tests</em> -- not every path that
   *     changed. A listener used as an audit log would under-report actual changes, so the
   *     parameter is named for what it is (§7.1)
   */
  default void updatePropagated(Fact before, Fact after, Set<JsonPointer> changedTestedPaths) {
    // no-op by default
  }

  /**
   * An update changed no tested path: the payload was replaced and nothing else happened.
   *
   * <p>No traversal, no index work, no refraction invalidation, no recency bump. §3.4 treats this
   * as a non-event, so it is reported separately rather than as a degenerate
   * {@link #updatePropagated}.
   *
   * @param fact the fact, carrying the new payload and its unchanged recency
   */
  default void updateSkipped(Fact fact) {
    // no-op by default
  }
}
