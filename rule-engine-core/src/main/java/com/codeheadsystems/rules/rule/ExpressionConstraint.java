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
 * rather than a hidden one. It is evaluated against a <em>complete</em> tuple, in the agenda both
 * matchers share, so that neither can apply it differently from the other.
 *
 * @param expression the CEL source text
 * @param referencedAliases the aliases the expression reads, held in a sorted immutable set so that
 *     iteration order is stable across JVM runs (see {@link RuleDefinition#tags()}).
 *     <p><strong>Optional, and advisory.</strong> §2.5 introduced it to decide where in the join
 *     order a condition could be evaluated; that question no longer arises, because a condition is
 *     evaluated once on a complete tuple. What remains is an extra compile-time check for a
 *     Java-authored rule: any alias named here must be one the rule binds. The expression language
 *     does the real check, against the variables it was given, and does it whether this set is
 *     populated or not -- so the DSL leaves it <strong>empty</strong>.
 *     <p>That has one consequence worth knowing: this record reaches §5.6's content hash, so a rule
 *     written in YAML and the same rule built in Java carry different rule-set versions unless the
 *     Java author also passes an empty set. Prefer empty
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
