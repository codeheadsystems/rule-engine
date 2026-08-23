package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.value.Canonical;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.DecimalNode;

/**
 * A cross-fact comparison relating this pattern's field to an earlier-bound alias's field
 * (spec §2.5).
 *
 * <p>In the DSL this is the {@code { $ref: alias.field }} form, resolved at compile time against
 * the join graph. §6.5 requires every reference to resolve to an <em>earlier</em> alias, which is
 * what keeps the join graph a DAG with no forward references.
 *
 * @param field the dotted field path on the fact this pattern binds
 * @param otherAlias the earlier-bound alias to compare against
 * @param otherField the dotted field path on that alias's fact
 * @param op the comparison to apply
 * @param within the bound for a temporal relation, in the time field's own units; required for
 *     {@link Operator#AFTER} and {@link Operator#BEFORE} and refused for every other operator, both
 *     by the compiler. Normalised on the way in to a single decimal form, so that the same
 *     duration written as an int, a long or a trailing-zero decimal is one constraint and one
 *     rule-set version hash
 */
public record JoinConstraint(String field, String otherAlias, String otherField, Operator op,
    Optional<JsonNode> within) implements Constraint {

  /**
   * Canonical constructor.
   *
   * @param field the dotted field path on this pattern's fact
   * @param otherAlias the earlier-bound alias
   * @param otherField the dotted field path on that alias's fact
   * @param op the comparison
   * @param within the temporal bound, absent for every non-temporal operator
   */
  public JoinConstraint {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(otherAlias, "otherAlias");
    Objects.requireNonNull(otherField, "otherField");
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(within, "within");
    /*
     * Normalised to a decimal, not merely copied, and DslEquivalence is what found out why. A
     * window written as 86400000 reaches this record as an IntNode from a rule file and a LongNode
     * from the Java builder, and Jackson's node equality is type-sensitive -- so the same duration
     * written two ways produced two constraints and two rule-set version hashes, which §5.6's hot
     * reload, refraction and RuleSetFingerprint all key on. A duration is a magnitude rather than a
     * typed value, so collapsing the width is lossless.
     *
     * The same shape as RangeConstraint normalising its inclusivity flags, and found the same way:
     * this module's oracle exists because a difference invisible in the text is still a difference.
     */

    /*
     * Two layers, and they own different failures on purpose.
     *
     * A window that is a NUMBER this record cannot represent -- a NaN or an infinity -- fails here,
     * because normalising it is this constructor's job and it cannot do it. Through
     * Canonical.orderable rather than decimalValue() directly: isNumber() is true for both and
     * decimalValue() throws, which is how an infinity built from the testkit came to throw
     * JsonNodeException out of a public record constructor where every other failure is an NPE
     * naming a field.
     *
     * A window that is not a number at all passes through untouched, so that RuleCompiler reports
     * it as a located diagnostic against the author's own line rather than as an exception from a
     * record. Throwing here for that case made the compiler's own check unreachable and the two
     * layers silently disagree about who owns it.
     */
    within = within.map(window -> !window.isNumber()
        ? window.deepCopy()
        : Canonical.orderable(window)
            .map(value -> (JsonNode) DecimalNode.valueOf(magnitude(value)))
            .orElseThrow(() -> new IllegalArgumentException(
                "'within' is a finite number in the time field's own units, got " + window)));
  }

  /**
   * A duration's value with its width and scale collapsed.
   *
   * <p>Width alone is not enough. The rule-file schema types {@code within} as a number, so
   * {@code 86400000.0} is legal and is the same duration as {@code 86400000} -- and without
   * stripping it produces a different constraint and a different rule-set hash, which is the same
   * defect one step further in. {@code stripTrailingZeros} answers {@code 8.64E+7} for that input,
   * whose scale is negative and which renders unreadably in a diagnostic, so the scale is pulled
   * back to zero when it goes below.
   *
   * @param raw the window as written
   * @return the same magnitude, in one form whatever it was written as
   */
  private static BigDecimal magnitude(final BigDecimal raw) {
    final BigDecimal stripped = raw.stripTrailingZeros();
    return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
  }

  /**
   * Builds a non-temporal join.
   *
   * @param field the dotted field path on this pattern's fact
   * @param otherAlias the earlier-bound alias
   * @param otherField the dotted field path on that alias's fact
   * @param op the comparison
   */
  public JoinConstraint(final String field, final String otherAlias, final String otherField,
      final Operator op) {
    this(field, otherAlias, otherField, op, Optional.empty());
  }
}
