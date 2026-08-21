package com.codeheadsystems.rules.rule;

/**
 * How many facts a pattern requires (spec §2.5).
 *
 * <p>v1 implements only {@link #EXISTS_AT_LEAST_ONE}. The rest are reserved so that adding them
 * later is a new enum constant plus a new node type, rather than a reshape of
 * {@link PatternDefinition}. §1 states what each costs and gives an interim answer for each.
 */
public enum Quantifier {

  /** At least one fact matches the pattern. The only quantifier v1 implements. */
  EXISTS_AT_LEAST_ONE,

  /**
   * No fact matches the pattern. Deferred (§1).
   *
   * <p>Negation needs per-tuple match counters, correct behaviour when the count crosses 1 to 0
   * in both directions, and correct interaction with truth maintenance. Interim answer: compute
   * the absence at ingestion and insert an explicit marker fact.
   */
  NOT_EXISTS,

  /** Every fact of the type matches the pattern. Deferred (§1). */
  FOR_ALL,

  /** Aggregation over matching facts. Deferred (§1); aggregate at ingestion instead. */
  ACCUMULATE
}
