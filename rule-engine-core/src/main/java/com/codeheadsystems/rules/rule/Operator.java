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
   * matches an absent field as readily as a present non-null one -- the same asymmetry as
   * {@link #NE}.
   */
  IS_NULL,

  /**
   * This fact's time field falls strictly after another's, and within a bound of it (§2.5's third
   * amendment).
   *
   * <p><strong>Join-only, and the bound is required</strong> -- both enforced by the compiler. An
   * unbounded "after" is {@link #GT} against a {@code $ref} and the language already has it, so an
   * {@code AFTER} that allowed the window to be omitted would add a second spelling for an existing
   * operator rather than any expressiveness. What it does add is the bounded form, which no pair of
   * comparisons can state: {@code b < a <= b + window} needs the other fact's value twice, and a
   * constraint reads it once.
   *
   * <p><strong>Time is a field, and the engine owns no clock.</strong> The window is a number in
   * whatever unit that field carries -- epoch millis, epoch seconds, a sequence number -- because
   * only the author knows which, and a unit this engine assumed would be a unit it got wrong
   * silently. §7.3 survives untouched as a result: replaying the same facts gives the same firings
   * on any host in any year, which a wall clock would end.
   */
  AFTER,

  /**
   * This fact's time field falls strictly before another's, and within a bound of it.
   *
   * <p>{@code b - window <= a < b}. Everything {@link #AFTER} documents applies, read the other
   * way.
   */
  BEFORE;

  /**
   * The operator that expresses the same relation read from the other side.
   *
   * <p>A join constraint {@code o.total > c.limit} is one relation, and which side of it a matcher
   * happens to bind first is a runtime decision -- §3.3 makes choosing the smaller side a per-fire
   * call. Reading the relation backwards is what lets the same constraint narrow either pattern:
   * "orders whose total exceeds this customer's limit" and "customers whose limit is under this
   * order's total" are the same edge, traversed in opposite directions.
   *
   * <p>Empty for the operators where the question does not apply. {@link #IN} and {@link #NOT_IN}
   * relate a scalar to an <em>array</em>, so they have no meaningful reverse; {@link #MATCHES}
   * relates a value to a pattern; {@link #HAS_FIELD} and {@link #IS_NULL} are single-fact tests
   * that never appear on a join in the first place; and {@link #AFTER} and {@link #BEFORE} are each
   * other's converse as a <em>relation</em> but carry a bound that a swapped operator would leave
   * behind, so they decline rather than risk widening the rule. That is what makes a temporal join
   * permanently unindexed, which is a documented contract rather than an accident. Those edges are
   * still evaluated -- they just cannot be used to probe an index from the far end.
   *
   * @return the reversed operator, or empty when the relation is not symmetric in that sense
   */
  public java.util.Optional<Operator> reversed() {
    return switch (this) {
      case EQ -> java.util.Optional.of(EQ);
      case NE -> java.util.Optional.of(NE);
      case GT -> java.util.Optional.of(LT);
      case GTE -> java.util.Optional.of(LTE);
      case LT -> java.util.Optional.of(GT);
      case LTE -> java.util.Optional.of(GTE);
      /*
       * AFTER and BEFORE are each other's converse as a relation -- "a within 24h after b" is "b
       * within 24h before a" -- and are still not reversed here. The window travels with the
       * constraint, and this method returns an operator alone, so a caller that built a probe from
       * the reversal would have dropped the bound.
       *
       * It is the second belt rather than the first: JoinEnumerator.probe declines AFTER and BEFORE
       * in its default branch, and NetworkBuilder never records an index for them, so a reversal
       * here could not actually reach a probe today -- returning the converse leaves the suite
       * green, which is how that was established rather than assumed. Declining anyway costs a
       * direction nothing can use and keeps the two gates saying the same thing.
       */
      case IN, NOT_IN, MATCHES, HAS_FIELD, IS_NULL, AFTER, BEFORE -> java.util.Optional.empty();
    };
  }
}
