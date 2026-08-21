package com.codeheadsystems.rules.rule;

/**
 * The single-fact comparison operators (spec §2.5).
 *
 * <p>Semantics against absent, null and wrong-typed values are §2.6.1's table, implemented once in
 * {@link com.codeheadsystems.rules.value.Comparisons}. That table is normative; this enum is only
 * the vocabulary.
 *
 * <p>Note two operators that carry their polarity in the constraint literal rather than in a
 * second enum constant, so that a single operator covers both directions:
 * {@link #HAS_FIELD} and {@link #IS_NULL} both expect a boolean literal.
 */
public enum Operator {

  /** Equality within a type-compatibility class. {@code eq: null} matches an explicit JSON null only. */
  EQ,

  /**
   * Defined as {@code !EQ}, and therefore <strong>true</strong> for an absent field.
   *
   * <p>This is a genuine trap -- {@code status: { ne: "CLOSED" }} matches an order with no
   * {@code status} at all -- and §2.6.1 accepts it deliberately rather than moving to
   * three-valued logic. Pair it with {@code hasField: true} when you mean "present and not X".
   */
  NE,

  /** Strictly greater than. False for absent, null and wrong-typed values. */
  GT,

  /** Greater than or equal to. False for absent, null and wrong-typed values. */
  GTE,

  /** Strictly less than. False for absent, null and wrong-typed values. */
  LT,

  /** Less than or equal to. False for absent, null and wrong-typed values. */
  LTE,

  /** Membership: true when the value is {@link #EQ} to any element of an array literal. */
  IN,

  /** Defined as {@code !IN}, and therefore <strong>true</strong> for an absent field. See {@link #NE}. */
  NOT_IN,

  /**
   * Regular-expression match against a string value.
   *
   * <p>Patterns are compiled once, at rule-compile time, using RE2 rather than
   * {@code java.util.regex} -- §2.6.3. A backtracking engine turns a reviewed-as-config rule file
   * into a denial-of-service vector.
   */
  MATCHES,

  /**
   * Field presence on <em>one</em> fact. The literal is a boolean giving polarity.
   *
   * <p>This has nothing to do with existential quantification over a <em>pattern</em> ("does any
   * Payment exist for this Order"), which is {@link Quantifier}'s job and a v1 non-goal. Two very
   * different features; §2.5 declines to give them one word.
   */
  HAS_FIELD,

  /**
   * Explicit JSON null, as distinct from absent (§2.6.1). The literal is a boolean giving polarity.
   *
   * <p>{@code isNull: false} is the negation of the predicate, not "present and not null", so it
   * matches an absent field as readily as a present non-null one -- the same asymmetry as {@link #NE}.
   */
  IS_NULL
}
