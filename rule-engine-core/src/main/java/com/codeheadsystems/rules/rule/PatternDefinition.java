package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One fact-matching clause of an LHS, bound to an alias (spec §2.5).
 *
 * @param alias the binding name, e.g. {@code o}
 * @param factType the fact type this pattern matches, e.g. {@code Order}
 * @param quantifier {@link Quantifier#EXISTS_AT_LEAST_ONE}, {@link Quantifier#NOT_EXISTS} or
 *     {@link Quantifier#FOR_ALL}; or {@link Quantifier#ACCUMULATE}.
 *     {@code NOT_EXISTS} and {@code FOR_ALL} bind no fact, and {@code ACCUMULATE} binds a value
 *     rather than a fact -- see their own documentation for what that
 *     costs the rest of the rule
 * @param constraints the conditions, in declaration order. Implicitly AND-ed
 * @param accumulate what an {@link Quantifier#ACCUMULATE} pattern computes and requires of the
 *     answer; empty for every other quantifier, and the compiler refuses each mismatch by name
 */
public record PatternDefinition(
    String alias, String factType, Quantifier quantifier, List<Constraint> constraints,
    Optional<Accumulate> accumulate) {

  /**
   * Canonical constructor. Defensively copies {@code constraints}.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param quantifier the quantifier
   * @param constraints the conditions
   * @param accumulate what an accumulate computes, empty otherwise
   */
  public PatternDefinition {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(quantifier, "quantifier");
    Objects.requireNonNull(accumulate, "accumulate");
    constraints = List.copyOf(constraints);
  }

  /**
   * Builds a pattern with no accumulate, for every quantifier but {@code ACCUMULATE}.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param quantifier the quantifier
   * @param constraints the conditions
   */
  public PatternDefinition(final String alias, final String factType, final Quantifier quantifier,
      final List<Constraint> constraints) {
    this(alias, factType, quantifier, constraints, Optional.empty());
  }

  /**
   * Builds an ordinary positive pattern.
   *
   * <p>There is no matching factory for a negated one on purpose: {@link Quantifier#NOT_EXISTS}
   * carries obligations an author has to read (it binds nothing, and §4.4 eviction over its type
   * produces false conclusions), so it is written out in full at the one place that builds it.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param constraints the conditions
   * @return the pattern
   */
  public static PatternDefinition of(
      final String alias, final String factType, final List<Constraint> constraints) {
    return new PatternDefinition(alias, factType, Quantifier.EXISTS_AT_LEAST_ONE, constraints,
        Optional.empty());
  }
}
