package com.codeheadsystems.rules.rule;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The CEL escape hatch (spec §6.4). Reserved in v1; not evaluated by the Phase 0 engine.
 *
 * <p>It is opaque to the indexer -- an unindexed post-filter -- which is the point: §6.3 keeps the
 * fast, indexable path the default and makes free-form expression logic an explicit, visible cost
 * rather than a hidden one.
 *
 * @param expression the CEL source text
 * @param referencedAliases the aliases the expression reads, held in a sorted immutable set so that
 *     iteration order is stable across JVM runs (see {@link RuleDefinition#tags()}). Determines
 *     where in the join
 *     order it can be evaluated
 */
public record ExpressionConstraint(String expression, Set<String> referencedAliases)
    implements Constraint {

  /**
   * Canonical constructor. Defensively copies {@code referencedAliases}.
   *
   * @param expression the CEL source text
   * @param referencedAliases the aliases the expression reads
   */
  public ExpressionConstraint {
    Objects.requireNonNull(expression, "expression");
    referencedAliases = Collections.unmodifiableSortedSet(new TreeSet<>(referencedAliases));
  }

  /**
   * An expression constraint has no single field; it reads whatever its aliases expose.
   *
   * @return the empty string, meaning "not a single-field constraint"
   */
  @Override
  public String field() {
    return "";
  }
}
