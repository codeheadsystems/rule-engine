package com.codeheadsystems.rules.value;

import com.codeheadsystems.rules.rule.Operator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import tools.jackson.databind.JsonNode;

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
 * <p>{@link Operator#IN} is {@link Operator#EQ} against each element, which is what §2.6.1's
 * "per element" cell on the null row means: {@code { in: [null] }} matches an explicit null exactly
 * as {@code { eq: null }} does. Carving null out of membership would make {@code IN} disagree with
 * the {@code EQ} it is built from, and would stop {@link Operator#NOT_IN} being {@code !IN}.
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
      /*
       * asBoolean() THROWS on a string or object node under Jackson 3, where Jackson 2 coerced and
       * returned false. Two independent compile-time gates keep a non-boolean from reaching here,
       * and both got sharper in this migration rather than merely staying correct:
       *
       *   - RuleCompiler rejects a HAS_FIELD/IS_NULL literal that is not boolean (§6.2.1: they
       *     carry a polarity, not a value of the field's type);
       *   - RuleCompiler rejects HAS_FIELD/IS_NULL as JOIN operators outright, which matters more
       *     than it looks. JoinTest.test passes the other fact's field VALUE as this `literal`
       *     argument -- arbitrary runtime data, not a validated literal -- so without that gate
       *     ordinary facts would reach asBoolean() and a string-valued field would throw from the
       *     matching hot path.
       *
       * Under Jackson 2 a hole in either gate produced a quiet wrong answer; now it produces an
       * exception mid-fire. Do not relax either without revisiting this line.
       */
      case HAS_FIELD -> literal.asBoolean() == !actual.isMissingNode();
      case IS_NULL -> literal.asBoolean() == actual.isNull();
      case MATCHES -> throw new IllegalArgumentException(
          "MATCHES needs a pattern compiled at rule-compile time; see RegexTest");
      /*
       * Two values are not enough for a temporal operator: it needs the window as well, which no
       * two-argument comparison can carry. JoinTest dispatches to `within` below rather than here,
       * and the compiler refuses AFTER/BEFORE anywhere a single fact is tested -- so reaching this
       * branch means one of those gates was removed, and saying so is more use than a wrong answer.
       */
      case AFTER, BEFORE -> throw new IllegalArgumentException(
          op + " is a bounded cross-fact relation and needs its window; see Comparisons.within");
    };
  }

  /**
   * Whether a time field falls within a bounded window of another fact's (§2.5's third amendment).
   *
   * <p><strong>Strict on the near side, inclusive on the far one:</strong> {@code AFTER} holds when
   * {@code other < value <= other + window}, and {@code BEFORE} when
   * {@code other - window <= value < other}. The strict end is what stops a fact being "after"
   * itself when two share a timestamp, which is the reading an author means by a sequence; the
   * inclusive end is what makes "within 24 hours" include the twenty-fourth hour exactly.
   *
   * <p><strong>The window is in the field's own units</strong>, because the engine has no idea what
   * they are. A field holding epoch millis takes a window in millis and one holding epoch seconds
   * takes seconds; assuming either would be assuming wrong for half of all rule sets, silently. The
   * DSL documents this beside the operator, and §2.5's amendment records it as the cost of owning no
   * clock.
   *
   * <p><strong>Numbers only, which is narrower than ordering and worth knowing.</strong> §2.6.1
   * orders two strings, so {@code gt} on a pair of ISO-8601 timestamps works today -- and this does
   * not, because a window is arithmetic and there is nothing to add to a string. A textual timestamp
   * therefore matches nothing here rather than being rejected, which is the one sharp edge in this
   * operator: convert to an epoch number at ingestion, as §1's flattening advice says to do with
   * collections and for the same reason.
   *
   * <p>Absent, null and non-finite values answer false, as every ordering comparison in §2.6.1 does
   * -- a value the engine cannot order is not one it should guess at.
   *
   * @param op {@link Operator#AFTER} or {@link Operator#BEFORE}
   * @param value this fact's time field
   * @param other the already-bound fact's time field
   * @param window the bound, in the field's own units; the compiler requires it to be positive
   * @return whether the relation holds
   */
  public static boolean within(final Operator op, final JsonNode value, final JsonNode other,
      final JsonNode window) {
    // Canonical's predicate, not a copy of it: which values this engine will order is one decision,
    // and the comparisons, these windows, the bound one carries and Accumulators' folds all have to
    // make it the same way.
    final Optional<BigDecimal> here = Canonical.orderable(value);
    final Optional<BigDecimal> there = Canonical.orderable(other);
    final Optional<BigDecimal> bound = Canonical.orderable(window);
    if (here.isEmpty() || there.isEmpty() || bound.isEmpty()) {
      return false;
    }
    final BigDecimal from = op == Operator.AFTER ? there.get() : there.get().subtract(bound.get());
    final BigDecimal to = op == Operator.AFTER ? there.get().add(bound.get()) : there.get();
    return op == Operator.AFTER
        ? here.get().compareTo(from) > 0 && here.get().compareTo(to) <= 0
        : here.get().compareTo(from) >= 0 && here.get().compareTo(to) < 0;
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
    return sameContainerKind(actual, literal) && structurallyEqual(actual, literal);
  }

  /**
   * Structural equality over two containers, with numbers compared the way §2.6.1 compares them
   * everywhere else.
   *
   * <p><strong>Not {@code JsonNode.equals}, and a wider fix than the bug that prompted it.</strong>
   * Jackson's node equality is <em>representation</em> equality: it separates {@code IntNode} from
   * {@code DoubleNode} from {@code DecimalNode}, and (from Jackson 3) one {@code DecimalNode} scale
   * from another. §2.6.1 puts all of them in one {@code {number}} class, and {@link Canonical}
   * implements that for scalars. Delegating containers to Jackson therefore made the engine answer
   * differently about the same two numbers depending on whether they sat inside an object.
   *
   * <p>The scale half of that split arrived with Jackson 3 -- its {@code DecimalNode.equals} uses
   * {@code BigDecimal.equals} where Jackson 2 used {@code compareTo} -- and money written at two
   * scales is the everyday way to meet it. <strong>The cross-representation half is older and was
   * never a Jackson 3 regression at all</strong>: {@code {a: 1}} and {@code {a: 1.0}} straight out
   * of {@code readTree} were already unequal here while {@code 1} and {@code 1.0} were equal as
   * scalars. Both are fixed together, because both are the same disagreement.
   *
   * <p>Note the direction: this <em>widens</em> what matches, and §2.6.1's design is largely about
   * not doing that quietly. It is stated in the amendment there, and pinned by
   * {@code ReviewRegressionTest.ContainerNumericEquality} across scale, representation and NaN.
   * Non-finite doubles go the other way -- Jackson called two NaNs equal, this does not -- which
   * again is what the scalar path already did.
   *
   * <p>§2.6.1 originally read "{@code EQ} on two object/array values is Jackson's structural
   * equals". That sentence was true of Jackson 2 and stayed literally true of Jackson 3 while
   * meaning something else, so the spec is amended rather than the code bent to it. The rest of the
   * container contract is unchanged -- object key order does not matter, array element order does.
   *
   * <p>Cost: no extra traversal, since {@code JsonNode.equals} was already a deep walk, and nothing
   * here is quadratic -- {@code properties()} is the live entry set and {@code get(key)} the same
   * hash lookup Jackson already did. Not free at the leaf, though: a numeric comparison allocates
   * {@code BigDecimal}s and {@code Optional}s where {@code IntNode.equals} was a primitive compare.
   * That runs per alpha test per insert, not per fire cycle.
   *
   * @param left one container
   * @param right the other, already known to be the same kind
   * @return whether they are equal under §2.6.1
   */
  private static boolean structurallyEqual(final JsonNode left, final JsonNode right) {
    if (left.size() != right.size()) {
      return false;
    }
    if (left.isArray()) {
      for (int index = 0; index < left.size(); index++) {
        if (!nodesEqual(left.get(index), right.get(index))) {
          return false;
        }
      }
      return true;
    }
    for (final Map.Entry<String, JsonNode> field : left.properties()) {
      final JsonNode other = right.get(field.getKey());
      if (other == null || !nodesEqual(field.getValue(), other)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Equality for one pair of nodes at any depth: canonical for numbers, recursive for containers.
   *
   * @param left one value
   * @param right the other
   * @return whether they are equal
   */
  private static boolean nodesEqual(final JsonNode left, final JsonNode right) {
    /*
     * Deliberately no `left == right` fast path. It looks free and is not: two NaNs are unequal
     * here, so a reference check would make a node equal to ITSELF while unequal to a structurally
     * identical twin -- an identity-dependent answer, which is worse than the cost it saves. The
     * consequence is that comparing a node with itself walks; a self-referential node, which
     * Jackson lets you build, overflows the stack rather than short-circuiting. JsonNode.equals had
     * the same exposure for two distinct cyclic nodes, and a payload that IS its own constraint
     * literal is already a contract violation.
     */
    if (left.isNumber() && right.isNumber()) {
      /*
       * Canonical.compare, where the scalar path uses Canonical.hashKey -- compareTo() == 0 against
       * stripTrailingZeros().equals(). Two spellings of one predicate, and the whole fix rests on
       * them agreeing. They do, over every scale, signed zero and exponent form checked; if you
       * change either, check the other, because no test compares them directly.
       *
       * Empty means a value BigDecimal cannot represent -- a non-finite double built in Java. Those
       * compare unequal rather than throwing, matching what the scalar path does with them.
       */
      final OptionalInt sign = Canonical.compare(left, right);
      return sign.isPresent() && sign.getAsInt() == 0;
    }
    if (sameContainerKind(left, right)) {
      return structurallyEqual(left, right);
    }
    return !left.isContainer() && !right.isContainer() && left.equals(right);
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
