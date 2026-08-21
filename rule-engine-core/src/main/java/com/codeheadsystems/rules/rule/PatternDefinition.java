package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;

/**
 * One fact-matching clause of an LHS, bound to an alias (spec §2.5).
 *
 * @param alias the binding name, e.g. {@code o}
 * @param factType the fact type this pattern matches, e.g. {@code Order}
 * @param quantifier v1 accepts only {@link Quantifier#EXISTS_AT_LEAST_ONE}
 * @param constraints the conditions, in declaration order. Implicitly AND-ed
 */
public record PatternDefinition(
    String alias, String factType, Quantifier quantifier, List<Constraint> constraints) {

  /**
   * Canonical constructor. Defensively copies {@code constraints}.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param quantifier the quantifier
   * @param constraints the conditions
   */
  public PatternDefinition {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(quantifier, "quantifier");
    constraints = List.copyOf(constraints);
  }

  /**
   * Builds an ordinary positive pattern.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param constraints the conditions
   * @return the pattern
   */
  public static PatternDefinition of(
      final String alias, final String factType, final List<Constraint> constraints) {
    return new PatternDefinition(alias, factType, Quantifier.EXISTS_AT_LEAST_ONE, constraints);
  }
}
