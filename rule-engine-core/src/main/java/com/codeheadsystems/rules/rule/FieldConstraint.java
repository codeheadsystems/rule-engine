package com.codeheadsystems.rules.rule;

import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A single-fact comparison of one field against a literal (spec §2.5).
 *
 * <p>The literal is a {@link JsonNode} rather than an {@code Object}, which is what lets a DSL
 * literal compare directly against a fact's field value with no coercion layer between: the DSL,
 * the constraint AST and the fact payload all speak one value model end to end.
 *
 * <p><strong>The literal is deep-copied on construction.</strong> A constraint ends up inside the
 * compiled rule set, which spec invariant 1 requires to be immutable and which thousands of
 * concurrent sessions read without synchronisation. Retaining the caller's node would let an array
 * literal be extended after compilation -- changing what every future session matches, through an
 * unsynchronised write racing every reader. This is §2.2's payload-ownership argument applied to
 * rule literals, where it gets none of §2.2's other defences.
 *
 * @param field the dotted field path this constraint reads
 * @param op the comparison to apply
 * @param literal the value to compare against. For {@link Operator#IN} and {@link Operator#NOT_IN}
 *     this is an array node whose elements are the candidates; for {@link Operator#HAS_FIELD} and
 *     {@link Operator#IS_NULL} it is a boolean node carrying the polarity
 */
public record FieldConstraint(String field, Operator op, JsonNode literal) implements Constraint {

  /**
   * Canonical constructor.
   *
   * @param field the dotted field path
   * @param op the comparison
   * @param literal the comparison value
   */
  public FieldConstraint {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(op, "op");
    Objects.requireNonNull(literal, "literal");
    literal = literal.deepCopy();
  }
}
