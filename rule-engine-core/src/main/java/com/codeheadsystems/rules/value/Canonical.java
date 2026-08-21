package com.codeheadsystems.rules.value;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Canonicalises JSON values into the one Java type per compatibility class that the engine hashes,
 * orders and indexes on (spec §2.6.2).
 *
 * <p>There are two canonicalisations here, not one, and mixing them is a silent-wrong-answer bug:
 *
 * <ul>
 *   <li>{@link #hashKey(JsonNode)} is the <strong>equality and hashing</strong> path -- alpha
 *       tests, {@code EQ}/{@code IN}, and the hash index keys of §3.3. Numerics go through
 *       {@code stripTrailingZeros()}, because {@code equals} and {@code hashCode} move together
 *       and {@code new BigDecimal("10000").equals(new BigDecimal("10000.0"))} is {@code false}.
 *   <li>{@link #compare(JsonNode, JsonNode)} is the <strong>ordering</strong> path -- ranges and
 *       the sorted indexes. It uses {@code compareTo}, never {@code equals}, and deliberately does
 *       <em>not</em> strip trailing zeros: it is unnecessary there and obscures the intent.
 * </ul>
 *
 * <p>The second half of §2.6.2's rule matters as much as the scale rule: canonicalisation produces
 * <strong>one Java type per compatibility class</strong> -- {@code String}, {@code BigDecimal},
 * {@code Boolean}, never a {@code JsonNode}. An index keyed on {@code Object} will happily hold
 * both {@code StringNode("A")} and {@code String("A")}, which are not equal, and then a probe built
 * one way misses an entry stored the other.
 */
public final class Canonical {

  private Canonical() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Canonicalises a value for equality and hashing.
   *
   * @param node the value; may be missing, null or a container
   * @return a {@link String}, {@link BigDecimal} or {@link Boolean}, or empty when the value is
   *     absent, an explicit JSON null, a container, or a non-finite number. An empty result means
   *     "not a hash key" -- containers compare structurally rather than by key (§2.6.1), and
   *     absent and null are handled by the operators that are defined for them
   */
  public static Optional<Object> hashKey(final JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return Optional.empty();
    }
    if (node.isString()) {
      return Optional.of(node.stringValue());
    }
    if (node.isBoolean()) {
      return Optional.of(node.booleanValue());
    }
    if (node.isNumber()) {
      return decimal(node).map(d -> (Object) d.stripTrailingZeros());
    }
    return Optional.empty();
  }

  /**
   * Compares two values within a type-compatibility class.
   *
   * <p>Comparison is defined <em>within</em> a class only (§2.6.1), so this returns empty for a
   * cross-class comparison rather than imposing an arbitrary order. It is also empty for booleans
   * and containers, which have no meaningful ordering, and for absent or null values.
   *
   * <p>Ordering is defined for {@code number} and {@code string}. Only the numeric half is
   * index-eligible: §3.3's sorted index is a {@code TreeMap<BigDecimal, ...>}, so a range over a
   * string field is correct but unindexed, and Phase 1's compiler report names it as such.
   *
   * @param left the left value
   * @param right the right value
   * @return the sign of {@code left} relative to {@code right}, or empty when the two are not
   *     comparable
   */
  public static OptionalInt compare(final JsonNode left, final JsonNode right) {
    if (left == null || right == null
        || left.isMissingNode() || right.isMissingNode()
        || left.isNull() || right.isNull()) {
      return OptionalInt.empty();
    }
    if (left.isNumber() && right.isNumber()) {
      final Optional<BigDecimal> a = decimal(left);
      final Optional<BigDecimal> b = decimal(right);
      if (a.isEmpty() || b.isEmpty()) {
        return OptionalInt.empty();
      }
      return OptionalInt.of(a.get().compareTo(b.get()));
    }
    if (left.isString() && right.isString()) {
      return OptionalInt.of(Integer.signum(left.stringValue().compareTo(right.stringValue())));
    }
    return OptionalInt.empty();
  }

  /**
   * Extracts a value's decimal form, rejecting the values {@code BigDecimal} cannot represent.
   *
   * <p>JSON has no NaN or infinity, but a payload built in Java with {@code JsonNodeFactory} can
   * carry a non-finite double, and {@code BigDecimal.valueOf(Double.NaN)} throws. Treating those
   * as "not canonicalisable" makes them fail every comparison rather than fail the engine.
   *
   * @param node a numeric node
   * @return its decimal value, or empty if it is not finite
   */
  private static Optional<BigDecimal> decimal(final JsonNode node) {
    if (node.isDouble() || node.isFloat()) {
      final double raw = node.doubleValue();
      if (!Double.isFinite(raw)) {
        return Optional.empty();
      }
    }
    return Optional.of(node.decimalValue());
  }
}
