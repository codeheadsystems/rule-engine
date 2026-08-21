package com.codeheadsystems.rules.value;

import com.codeheadsystems.rules.rule.Operator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The normative implementation of §2.6.1's comparison table.
 *
 * <p>Logic is <strong>two-valued, not three-valued</strong>: a constraint against an absent or
 * wrong-typed value is {@code false} -- with the deliberate exception of {@link Operator#NE} and
 * {@link Operator#NOT_IN}, which are defined as {@code !EQ} and {@code !IN} and are therefore
 * <strong>true</strong> for an absent field. §2.6.1 considered three-valued logic and rejected it:
 * it doubles every truth table and forces authors to reason about UNKNOWN propagation through AND,
 * a larger cognitive cost than one documented asymmetry.
 *
 * <p>The one place this class interprets rather than transcribes the table is {@link Operator#IN}
 * against an explicit JSON null. §2.6.1's {@code in [...]} column is written alongside its
 * {@code eq: <non-null>} column and so describes an array of non-null elements. Rather than
 * special-case null out of membership, {@code IN} is defined here as "{@link Operator#EQ} against
 * any element", which reproduces every cell of the table for non-null element lists and makes
 * {@code { in: [null] }} behave exactly like {@code { eq: null }}. Defining it any other way would
 * make {@code IN} disagree with the {@code EQ} it is built from.
 */
public final class Comparisons {

  private Comparisons() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Evaluates one single-fact comparison.
   *
   * @param op the operator. {@link Operator#MATCHES} is not handled here -- it needs a pattern
   *     compiled at rule-compile time (§2.6.3), so it lives in the compiled test that owns one
   * @param actual the value read from the fact. Never {@code null}: an absent path reads as
   *     {@code MissingNode}
   * @param literal the constraint's literal
   * @return whether the constraint holds
   * @throws IllegalArgumentException if {@code op} is {@link Operator#MATCHES}
   */
  public static boolean test(final Operator op, final JsonNode actual, final JsonNode literal) {
    return switch (op) {
      case EQ -> equal(actual, literal);
      case NE -> !equal(actual, literal);
      case GT -> ordered(actual, literal, sign -> sign > 0);
      case GTE -> ordered(actual, literal, sign -> sign >= 0);
      case LT -> ordered(actual, literal, sign -> sign < 0);
      case LTE -> ordered(actual, literal, sign -> sign <= 0);
      case IN -> in(actual, literal);
      case NOT_IN -> !in(actual, literal);
      case HAS_FIELD -> literal.asBoolean() == !actual.isMissingNode();
      case IS_NULL -> literal.asBoolean() == actual.isNull();
      case MATCHES -> throw new IllegalArgumentException(
          "MATCHES needs a pattern compiled at rule-compile time; see RegexTest");
    };
  }

  /**
   * Equality within a type-compatibility class (§2.6.1).
   *
   * <p>The ordering of the checks below is the table, in order:
   *
   * <ol>
   *   <li>An absent value equals nothing, <em>including</em> an explicit null literal. This is the
   *       opposite of JavaScript-shaped intuition and it is the right choice: an engine that
   *       cannot distinguish "unknown" from "known to be nothing" cannot express half the rules
   *       people need. Use {@code hasField: false} for "the field isn't there".
   *   <li>A null literal matches an explicit null and nothing else.
   *   <li>Scalars compare through {@link Canonical#hashKey}, so this method and the hash indexes
   *       of §3.3 cannot disagree about what a key is.
   *   <li>Containers compare with Jackson's structural equality: object key order does not matter,
   *       array element order does.
   * </ol>
   *
   * @param actual the value read from the fact
   * @param literal the constraint's literal
   * @return whether they are equal
   */
  private static boolean equal(final JsonNode actual, final JsonNode literal) {
    if (actual.isMissingNode()) {
      return false;
    }
    if (literal.isNull()) {
      return actual.isNull();
    }
    if (actual.isNull()) {
      return false;
    }
    final Optional<Object> left = Canonical.hashKey(actual);
    final Optional<Object> right = Canonical.hashKey(literal);
    if (left.isPresent() && right.isPresent()) {
      return left.get().equals(right.get());
    }
    if (left.isPresent() || right.isPresent()) {
      // One side is a scalar and the other is a container: a cross-class comparison, hence false.
      return false;
    }
    return sameContainerKind(actual, literal) && actual.equals(literal);
  }

  /**
   * Whether two nodes are containers of the same kind.
   *
   * @param left the left value
   * @param right the right value
   * @return true when both are objects, or both are arrays
   */
  private static boolean sameContainerKind(final JsonNode left, final JsonNode right) {
    return (left.isObject() && right.isObject()) || (left.isArray() && right.isArray());
  }

  /**
   * Applies an ordering predicate, yielding false when the two values are not comparable.
   *
   * @param actual the value read from the fact
   * @param literal the bound
   * @param accept the predicate over the comparison sign
   * @return whether the ordering holds
   */
  private static boolean ordered(
      final JsonNode actual, final JsonNode literal, final SignPredicate accept) {
    final OptionalInt sign = Canonical.compare(actual, literal);
    return sign.isPresent() && accept.test(sign.getAsInt());
  }

  /**
   * Membership: {@link #equal} against any element of an array literal.
   *
   * @param actual the value read from the fact
   * @param literal the array of candidates
   * @return whether the value equals any element. False when the literal is not an array, which is
   *     the cross-type case
   */
  private static boolean in(final JsonNode actual, final JsonNode literal) {
    if (!literal.isArray()) {
      return false;
    }
    for (final JsonNode element : literal) {
      if (equal(actual, element)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Evaluates a two-sided range, honouring each bound's inclusivity (spec §2.5, §2.6.1).
   *
   * @param actual the value read from the fact
   * @param lower the lower bound, or empty for unbounded
   * @param lowerInclusive whether the lower bound itself matches
   * @param upper the upper bound, or empty for unbounded
   * @param upperInclusive whether the upper bound itself matches
   * @return whether the value falls in the range. False for absent, null, wrong-typed and
   *     non-orderable values
   */
  public static boolean inRange(
      final JsonNode actual,
      final Optional<JsonNode> lower, final boolean lowerInclusive,
      final Optional<JsonNode> upper, final boolean upperInclusive) {
    if (lower.isPresent()
        && !ordered(actual, lower.get(), lowerInclusive ? sign -> sign >= 0 : sign -> sign > 0)) {
      return false;
    }
    return upper.isEmpty()
        || ordered(actual, upper.get(), upperInclusive ? sign -> sign <= 0 : sign -> sign < 0);
  }

  /** A predicate over the sign of a comparison. */
  @FunctionalInterface
  private interface SignPredicate {

    /**
     * Tests a comparison sign.
     *
     * @param sign negative, zero or positive
     * @return whether the comparison is accepted
     */
    boolean test(int sign);
  }
}
