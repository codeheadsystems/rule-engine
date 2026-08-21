package com.codeheadsystems.rules.rule;

import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A bounded range over one field: {@code between} and its one-sided forms (spec §2.5).
 *
 * <p>This is a separate constraint type rather than an {@link Operator}, because
 * {@link FieldConstraint} cannot express two bounds and their inclusivity without overloading its
 * literal into a positional array -- an encoding that then has to be documented, validated and
 * remembered. Half-open ranges are the common case in practice, so inclusivity has to be
 * expressible.
 *
 * <p>It also unifies the one-sided forms, so {@code gt}/{@code lt} on an indexed path compile into
 * the same structure the sorted index (§3.3) already understands.
 *
 * @param field the dotted field path this constraint reads
 * @param lower the lower bound, or empty for an unbounded low side
 * @param lowerInclusive whether the lower bound itself matches
 * @param upper the upper bound, or empty for an unbounded high side
 * @param upperInclusive whether the upper bound itself matches
 */
public record RangeConstraint(
    String field,
    Optional<JsonNode> lower, boolean lowerInclusive,
    Optional<JsonNode> upper, boolean upperInclusive) implements Constraint {

  /**
   * Canonical constructor.
   *
   * @param field the dotted field path
   * @param lower the lower bound, or empty
   * @param lowerInclusive whether the lower bound matches
   * @param upper the upper bound, or empty
   * @param upperInclusive whether the upper bound matches
   */
  public RangeConstraint {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(lower, "lower");
    Objects.requireNonNull(upper, "upper");
    // Deep-copied for the same reason as FieldConstraint's literal: bounds live in the shared,
    // immutable compiled rule set and must not be reachable for mutation by the caller.
    lower = lower.map(JsonNode::deepCopy);
    upper = upper.map(JsonNode::deepCopy);
    if (lower.isEmpty() && upper.isEmpty()) {
      throw new IllegalArgumentException("range constraint on '" + field + "' has no bounds");
    }
    /*
     * The inclusivity of a bound that is not there means nothing, and both readers -- Comparisons
     * and PatternMemory's sorted probe -- test isPresent() before they look at the flag. Left
     * un-normalised it is still part of this record's equality, and that leaks in two places that
     * matter: NetworkBuilder shares alpha nodes by constraint equality, so one constraint written
     * two ways would build two nodes; and the rule-set version hash of §5.6 is computed over these
     * records, so semantically identical rule sets would carry different versions.
     *
     * §6.2.1 states the case that surfaced it -- "{ between: { from: 100 } } and { gte: 100 }
     * compile to the identical RangeConstraint" -- which is true of behaviour either way and true
     * of equality only after this.
     */
    lowerInclusive = lower.isPresent() && lowerInclusive;
    upperInclusive = upper.isPresent() && upperInclusive;
  }

  /**
   * Builds a one-sided range for a simple comparison operator.
   *
   * @param field the dotted field path
   * @param op one of {@link Operator#GT}, {@link Operator#GTE}, {@link Operator#LT} or
   *     {@link Operator#LTE}
   * @param bound the bound value
   * @return the equivalent range constraint
   * @throws IllegalArgumentException if {@code op} is not a range operator
   */
  public static RangeConstraint of(final String field, final Operator op, final JsonNode bound) {
    return switch (op) {
      case GT -> new RangeConstraint(field, Optional.of(bound), false, Optional.empty(), false);
      case GTE -> new RangeConstraint(field, Optional.of(bound), true, Optional.empty(), false);
      case LT -> new RangeConstraint(field, Optional.empty(), false, Optional.of(bound), false);
      case LTE -> new RangeConstraint(field, Optional.empty(), false, Optional.of(bound), true);
      default -> throw new IllegalArgumentException(op + " is not a range operator");
    };
  }
}
