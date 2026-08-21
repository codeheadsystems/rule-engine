package com.codeheadsystems.rules.rule;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which payload paths the rule set reads, per fact type and per rule (spec §2.4).
 *
 * <p>One compile-time artifact, two consumers, and the difference between them is the whole reason
 * this interface has three methods instead of one:
 *
 * <ol>
 *   <li><strong>The update diff</strong> (§3.4.1 step 1) walks {@link #forType} to decide whether
 *       an update needs to touch the network at all. A type-wide union is exactly right here.
 *   <li><strong>Refraction invalidation</strong> (§3.4.1 step 5, §4.4) clears a rule's fired-match
 *       memory only when a path <em>that rule</em> tests changes, which is what {@link #rulesTesting}
 *       is for. Clearing type-wide instead makes a rule re-fire on data it already handled because
 *       an unrelated rule's field moved -- a rule firing twice for a reason no author can predict
 *       from reading their rule.
 * </ol>
 *
 * <p>Dirty-rule tracking (§4.1) deliberately does <em>not</em> consume this. A rule is dirty when a
 * fact of a type it patterns changes at all, which needs only a fact-type to rule-id map.
 */
public interface TestedPaths {

  /**
   * The union of paths every rule reads on this fact type -- the diff set.
   *
   * @param factType the fact type
   * @return the paths, or an empty set for a type no rule patterns
   */
  Set<JsonPointer> forType(String factType);

  /**
   * The paths one rule reads on one fact type.
   *
   * @param ruleId the rule
   * @param factType the fact type
   * @return the paths, or an empty set if that rule does not pattern that type
   */
  Set<JsonPointer> forRule(String ruleId, String factType);

  /**
   * Which of a type's tested paths differ between two payloads -- step 1 of §3.4.1's update.
   *
   * <p><strong>This default implementation is the specification of the answer.</strong> It walks
   * every tested path and compares the two subtrees, which is {@code O(tested paths)} traversals
   * per update whether anything changed or not -- a 300-rule set touching 200 distinct paths on
   * {@code Order} pays 200 traversals on every update, including ones that change nothing. It grows
   * with the rule set rather than with the update, which is exactly the cost §3.4.2 sets out to
   * remove.
   *
   * <p>An implementation is free to override it with something proportional to the size of the
   * <em>change</em> -- §3.4.2 describes a prefix trie that walks both payloads together and stops
   * wherever two nodes are equal. §3.4.2 also says how to build one safely: "write the probe loop
   * first and use it as the oracle for the trie". This method is that probe loop, kept as the
   * default precisely so an override always has something to be differentially tested against.
   *
   * <p>Getting it wrong is quiet. Under-reporting a changed path no longer costs a missed
   * activation, because §3.4.1 step 6 re-asserts unconditionally -- but it costs a missed
   * <em>refraction clear</em> in step 5, which surfaces much later as a rule that should have
   * re-fired and did not.
   *
   * @param factType the fact's type
   * @param oldPayload the payload as stored
   * @param newPayload the replacement payload
   * @return the tested paths whose values differ
   */
  default Set<JsonPointer> changedPaths(final String factType, final JsonNode oldPayload,
      final JsonNode newPayload) {
    final Set<JsonPointer> changed = new LinkedHashSet<>();
    for (final JsonPointer path : forType(factType)) {
      if (!oldPayload.at(path).equals(newPayload.at(path))) {
        changed.add(path);
      }
    }
    return changed;
  }

  /**
   * The inverse index: which rules read a given path on a given type.
   *
   * <p>This is the per-rule scoping refraction invalidation needs (§4.4).
   *
   * <p><strong>The returned set is immutable on every path.</strong> An implementation lives inside
   * a shared {@code CompiledRuleSet}, so a live set handed out here is mutable state behind every
   * session -- and the damage is invisible, because this set decides which rules get un-refracted
   * after an update (§3.4.1 step 5) rather than which facts match. Empty it and the affected rules
   * simply stop re-firing, with no exception and no change to {@code version()}. Found in review of
   * Phase 4 in {@code DefaultTestedPaths}, where the inverse index was the one of three copiers
   * building its values shallowly.
   *
   * @param factType the fact type
   * @param changed the path that changed
   * @return the ids of the rules that read it, or an empty set; immutable either way
   */
  Set<String> rulesTesting(String factType, JsonPointer changed);
}
