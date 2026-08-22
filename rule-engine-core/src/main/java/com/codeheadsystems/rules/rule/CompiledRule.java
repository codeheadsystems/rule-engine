package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.expr.CompiledExpression;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JsonPointer;

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
 * @param patterns the compiled positive LHS, in declaration order. A {@code NOT_EXISTS} pattern is
 *     <em>not</em> here: it binds no alias, contributes no position to a tuple, and joins nothing.
 *     Keeping it out is what lets the join planner, the join walk, the streaming matcher's pattern
 *     sites and the explainer go on reading this list as "the patterns that produce bindings"
 *     without any of them having to know negation exists
 * @param negations the compiled {@code NOT_EXISTS} patterns, in declaration order. Their join tests
 *     point at positions in {@code patterns}, and they are evaluated against a complete tuple
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
    java.util.List<CompiledPattern> negations,
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
   * @param patterns the compiled positive LHS
   * @param negations the compiled negated patterns
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
    negations = java.util.List.copyOf(negations);
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
    /*
     * Negated types belong here, and leaving them out is the defect that would be hardest to find.
     * This set is what §4.1's dirty predicate is built from -- a rule is dirty when a fact of a type
     * it patterns changes -- so a rule that negates Payment and does not name Payment here would not
     * be recomputed when a Payment arrived. It would go on firing on an absence that had ended,
     * silently, until something else made it dirty.
     */
    for (final CompiledPattern negation : negations) {
      types.add(negation.factType());
    }
    return types;
  }

  /**
   * The fact types this rule <em>binds</em>, which is not the same question as {@link #factTypes()}.
   *
   * <p>A negated type is one this rule reads and must be dirtied by, but not one it needs anything
   * of to match -- it needs the opposite. So the two answers must not be confused, and confusing
   * them inverts a reachability analysis: a rule that negates {@code Payment} is at its <em>most</em>
   * reachable when no {@code Payment} is ever inserted, while a walk over {@link #factTypes()} would
   * call it dead for exactly that reason. §7.4's report asks this question; §4.1's dirty predicate
   * asks the other one.
   *
   * @return the types bound by positive patterns, in declaration order
   */
  public Set<String> boundFactTypes() {
    final Set<String> types = new LinkedHashSet<>();
    for (final CompiledPattern pattern : patterns) {
      types.add(pattern.factType());
    }
    return types;
  }

  /**
   * Whether this rule asserts the absence of anything.
   *
   * <p>A fast path for the agenda, which evaluates negations against every complete tuple: the
   * overwhelming majority of rules have none, and checking a boolean is cheaper than entering a
   * loop over an empty list per match per fire cycle.
   *
   * @return whether any {@code NOT_EXISTS} pattern was compiled
   */
  public boolean hasNegations() {
    return !negations.isEmpty();
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
