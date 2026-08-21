package com.codeheadsystems.rules.rule;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One rule, post-parse and pre-compile (spec §2.5). This is what the DSL compiles into.
 *
 * @param id the rule id, unique across every file in a rule set
 * @param salience author-assigned priority; higher fires first (§4.2)
 * @param when the LHS: an ordered list of patterns, implicitly AND-ed
 * @param then the RHS: an ordered list of declarative actions
 * @param noLoop suppresses re-activation of <em>this same match</em> caused by this rule's own
 *     RHS (§4.5). It is one level deep and is not a loop guard; {@code maxCycles} is
 * @param agendaGroup optional partitioning of the agenda; empty when ungrouped. §4.5 defers
 *     grouping to v2, so v1 records the value and ignores it
 * @param tags free-form labels, for filtering and reporting. Held in a <em>sorted</em> immutable
 *     set, not a {@code Set.copyOf} one: that factory randomises iteration order with a per-JVM
 *     salt, and anything derived from the iteration order of a rule definition -- the rule-set
 *     content hash, most obviously -- then differs between runs of identical rules. No same-JVM
 *     test can catch that, so the fix belongs at the field rather than at each consumer
 */
public record RuleDefinition(
    String id,
    int salience,
    List<PatternDefinition> when,
    List<ActionDefinition> then,
    boolean noLoop,
    Optional<String> agendaGroup,
    Set<String> tags) {

  /**
   * Canonical constructor. Defensively copies the collection components.
   *
   * @param id the rule id
   * @param salience the priority
   * @param when the LHS patterns
   * @param then the RHS actions
   * @param noLoop the self-retrigger suppression flag
   * @param agendaGroup the optional agenda group
   * @param tags the labels
   */
  public RuleDefinition {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(agendaGroup, "agendaGroup");
    when = List.copyOf(when);
    then = List.copyOf(then);
    tags = Collections.unmodifiableSortedSet(new TreeSet<>(tags));
  }
}
