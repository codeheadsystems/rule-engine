package com.codeheadsystems.rules.rule;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One pattern of an LHS, compiled (spec §6.5).
 *
 * <p>The alpha and join tests are separated because they are evaluated at different points and,
 * from Phase 1, indexed differently: alpha tests narrow a single type's candidates and are what
 * §3.2.4's {@code PatternNode} memory holds, while join tests relate a candidate to facts already
 * bound earlier in the pattern order.
 *
 * @param alias the binding name
 * @param factType the fact type this pattern matches
 * @param alphaTests the single-fact tests, in declaration order
 * @param joinTests the cross-fact tests, each referring to an earlier pattern position
 * @param expressionTests the compiled §6.4 conditions written on this pattern, evaluated as an
 *     unindexed post-filter against a complete tuple rather than narrowing anything
 * @param distinctFrom the earlier pattern positions whose fact this pattern's fact must differ
 *     from. This is §1's implicit inequality between same-type aliases in one rule: distinct
 *     aliases bind distinct facts, so {@code Order as o1, Order as o2} finds two <em>different</em>
 *     orders. That reading differs from OPS5 and the other one silently produces self-matches,
 *     which is why the compiler inserts this rather than leaving it to the author
 */
public record CompiledPattern(
    String alias,
    String factType,
    List<AlphaTest> alphaTests,
    List<JoinTest> joinTests,
    List<ExpressionTest> expressionTests,
    int[] distinctFrom) {

  /**
   * Canonical constructor. Defensively copies every component, including the array.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param alphaTests the single-fact tests
   * @param joinTests the cross-fact tests
   * @param distinctFrom the earlier positions this pattern's fact must differ from
   */
  public CompiledPattern {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    alphaTests = List.copyOf(alphaTests);
    joinTests = List.copyOf(joinTests);
    expressionTests = List.copyOf(expressionTests);
    distinctFrom = distinctFrom.clone();
  }

  /**
   * The earlier pattern positions this pattern's fact must differ from.
   *
   * @return a copy of the positions; the caller may not mutate the record's state through it
   */
  @Override
  public int[] distinctFrom() {
    return distinctFrom.clone();
  }

  /**
   * Whether binding a candidate fact here would violate the implicit inequality of §1.
   *
   * <p>Offered instead of iterating {@link #distinctFrom()} at the call site, which would clone the
   * array once per candidate fact considered.
   *
   * @param boundFacts the handles bound so far, in pattern order
   * @param candidateHandleId the handle this pattern is considering binding
   * @return true when the candidate is already bound to an alias this one must differ from
   */
  public boolean conflictsWith(final long[] boundFacts, final long candidateHandleId) {
    for (final int position : distinctFrom) {
      if (boundFacts[position] == candidateHandleId) {
        return true;
      }
    }
    return false;
  }
}
