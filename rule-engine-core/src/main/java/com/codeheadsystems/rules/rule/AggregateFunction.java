package com.codeheadsystems.rules.rule;

/**
 * What an {@code ACCUMULATE} pattern computes over its scope (spec §2.5's second amendment).
 *
 * <p>Five, and the omission is deliberate. {@code COLLECT} -- gather the matching facts into a list
 * -- is the one §1 names that is not here: it answers with a <em>collection</em> rather than a
 * scalar, so it has no meaningful comparison for a {@code having} test, and a rule that could bind a
 * list would need a way to take it apart that §2.5 does not have. §1's flattening advice is the
 * answer for now, and adding it later is a new constant here plus a value semantics decision, not a
 * reshape of anything.
 *
 * <p>Every function is a pure fold over the scope in working-memory order -- insertion order per
 * type -- so §7.3's determinism is structural rather than argued: the same facts inserted the same
 * way give the same answer. {@code SUM} and {@code AVERAGE} fold through {@code BigDecimal}, so
 * neither the walk order nor floating-point association can move them at all. The one place the
 * order is visible is {@code MIN}/{@code MAX} over a scope mixing strings and numbers, which
 * {@code Canonical} cannot order against each other -- see {@code Accumulators.pick}.
 */
public enum AggregateFunction {

  /**
   * How many facts are in scope.
   *
   * <p>The one function that reads no field, because it asks about the facts rather than about
   * anything in them. The compiler refuses a field on it rather than ignoring one.
   */
  COUNT,

  /**
   * The total of a numeric field over the scope.
   *
   * <p>Zero over an empty scope, which is the identity for addition and the answer every reader
   * expects. Facts whose field is absent or non-numeric are skipped rather than treated as zero --
   * see {@code Accumulators} for why that distinction matters and what it costs.
   */
  SUM,

  /** The smallest value of a field over the scope; empty over an empty scope. */
  MIN,

  /** The largest value of a field over the scope; empty over an empty scope. */
  MAX,

  /**
   * The mean of a numeric field over the scope.
   *
   * <p>Empty over an empty scope rather than zero: the mean of nothing is not a number, and
   * answering zero would make "average order value is below 10" true for a customer with no orders
   * -- the vacuous-truth trap {@code FOR_ALL} carries, in arithmetic form.
   */
  AVERAGE
}
