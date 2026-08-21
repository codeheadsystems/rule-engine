package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.expr.CompiledExpression;
import com.fasterxml.jackson.core.JsonPointer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The compile-time companion to {@link RuleDefinition} (spec §4.2): the definition plus everything
 * the compiler precomputed for it.
 *
 * <p>Immutable, lives in the compiled rule set, shared by every session. Activations hold one of
 * these; nothing at runtime should be re-deriving a compile-time fact from a definition.
 *
 * <p><strong>One deviation from §4.2's record, and why.</strong> §4.2 lists a
 * {@code terminalNodeId} indexing into the session's node memories. Phase 0 has no network and
 * therefore no node ids, so carrying the field would mean carrying a component whose only possible
 * value is a placeholder -- the same "dead configuration a reader reasonably assumes has two
 * positions" that §7.5 rejects for a {@code joinStrategy} selector. It arrives in Phase 1 with the
 * alpha network that gives it a meaning. In its place, {@link #patterns()} carries the compiled
 * patterns the naive matcher walks directly.
 *
 * @param id the rule id
 * @param salience the author-assigned priority
 * @param noLoop whether this rule's own RHS may re-enable this same match (§4.5)
 * @param agendaGroup the optional agenda group; §4.5 defers grouping to v2
 * @param patterns the compiled LHS, in declaration order
 * @param actions the RHS, in declaration order
 * @param testedPaths per fact type, the paths <em>this rule</em> reads. This is
 *     {@link TestedPaths#forRule}'s backing, and its per-rule scoping is what keeps refraction
 *     invalidation from clearing a rule because an unrelated rule's field changed (§4.4)
 * @param valueExpressions the compiled §6.4 expressions this rule's actions use, by source text
 * @param source the definition, kept for diagnostics and §7.2's explanations
 */
public record CompiledRule(
    String id,
    int salience,
    boolean noLoop,
    Optional<String> agendaGroup,
    java.util.List<CompiledPattern> patterns,
    java.util.List<ActionDefinition> actions,
    Map<String, Set<JsonPointer>> testedPaths,
    Map<String, CompiledExpression> valueExpressions,
    RuleDefinition source) {

  /**
   * Canonical constructor. Defensively copies every collection component.
   *
   * @param id the rule id
   * @param salience the priority
   * @param noLoop the self-retrigger suppression flag
   * @param agendaGroup the optional agenda group
   * @param patterns the compiled LHS
   * @param actions the RHS
   * @param testedPaths the per-type paths this rule reads
   * @param valueExpressions the compiled §6.4 expressions its actions use, by source text
   * @param source the originating definition
   */
  public CompiledRule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(agendaGroup, "agendaGroup");
    Objects.requireNonNull(source, "source");
    patterns = java.util.List.copyOf(patterns);
    actions = java.util.List.copyOf(actions);
    // Map.copyOf is shallow: without this the values would remain the compiler's live, mutable
    // sets, leaving state inside the shared rule set that an outside caller could clear.
    final Map<String, Set<JsonPointer>> frozen = new LinkedHashMap<>();
    testedPaths.forEach((factType, paths) -> frozen.put(factType, Set.copyOf(paths)));
    testedPaths = Map.copyOf(frozen);
    /*
     * Insertion-ordered, not Map.copyOf: that factory salts its iteration order per JVM, and
     * ReportBuilder walks these values to build §7.4's CelCost list. Firing determinism was never
     * at risk -- only get() is on the agenda path -- but a report a build asserts on has to be the
     * same report twice.
     */
    valueExpressions = Collections.unmodifiableMap(new LinkedHashMap<>(valueExpressions));
  }

  /**
   * The fact types this rule patterns.
   *
   * <p>This is the whole input to dirty-rule tracking (§4.1): a rule is dirty when a fact of a type
   * it patterns is inserted, retracted or effectively updated. Note what it deliberately is
   * <em>not</em> -- "dirty only when this rule's alpha memories changed" is wrong in a way that
   * silently serves stale matches, because an update to a join key leaves alpha membership
   * unchanged while making the rule's join result stale.
   *
   * @return the distinct fact types, in pattern order
   */
  public Set<String> factTypes() {
    final Set<String> types = new LinkedHashSet<>();
    for (final CompiledPattern pattern : patterns) {
      types.add(pattern.factType());
    }
    return types;
  }

  /**
   * Whether any of this rule's patterns carries a §6.4 condition.
   *
   * <p>Asked once per recomputation so that a rule set using no expressions -- which is every rule
   * set that has not opted into §6.4's cost -- pays nothing for the post-filter beyond this check.
   *
   * @return true when at least one pattern has a compiled condition
   */
  public boolean hasExpressionTests() {
    for (final CompiledPattern pattern : patterns) {
      if (!pattern.expressionTests().isEmpty()) {
        return true;
      }
    }
    return false;
  }
}
