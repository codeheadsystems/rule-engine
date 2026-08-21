package com.codeheadsystems.rules.dsl;

import java.util.List;

/**
 * One rule as written in a file (spec §6.2.3).
 *
 * <p>{@code salience} and {@code noLoop} are primitives rather than boxed, because §6.2.3's
 * defaults -- {@code 0} and {@code false} -- are exactly what Jackson leaves a primitive at when the
 * key is absent. Boxing them to tell "absent" from "explicitly the default" would buy a distinction
 * the spec does not draw.
 *
 * @param id the rule id, unique across every file in a rule set
 * @param salience the priority; higher fires first (§4.2). Defaults to 0
 * @param noLoop whether this rule's own RHS may re-activate this same match (§4.5). Defaults false
 * @param agendaGroup the optional agenda group, or null. §4.5 defers grouping to v2, so v1 records
 *     the value and ignores it
 * @param tags free-form labels, or null when absent
 * @param when the LHS patterns, in document order
 * @param then the RHS actions, in document order
 */
record RuleNode(
    String id,
    int salience,
    boolean noLoop,
    String agendaGroup,
    List<String> tags,
    List<WhenNode> when,
    List<ThenNode> then) {

  /**
   * Canonical constructor. Normalises absent lists to empty ones.
   *
   * @param id the rule id
   * @param salience the priority
   * @param noLoop the self-retrigger suppression flag
   * @param agendaGroup the optional agenda group
   * @param tags the labels
   * @param when the LHS patterns
   * @param then the RHS actions
   */
  RuleNode {
    tags = tags == null ? List.of() : List.copyOf(tags);
    when = when == null ? List.of() : List.copyOf(when);
    then = then == null ? List.of() : List.copyOf(then);
  }
}
