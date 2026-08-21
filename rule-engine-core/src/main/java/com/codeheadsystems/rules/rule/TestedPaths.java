package com.codeheadsystems.rules.rule;

import com.fasterxml.jackson.core.JsonPointer;
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
   * The inverse index: which rules read a given path on a given type.
   *
   * <p>This is the per-rule scoping refraction invalidation needs (§4.4).
   *
   * @param factType the fact type
   * @param changed the path that changed
   * @return the ids of the rules that read it, or an empty set
   */
  Set<String> rulesTesting(String factType, JsonPointer changed);
}
