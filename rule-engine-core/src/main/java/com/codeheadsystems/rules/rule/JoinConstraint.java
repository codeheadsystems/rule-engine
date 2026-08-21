package com.codeheadsystems.rules.rule;

import java.util.Objects;

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
 */
public record JoinConstraint(String field, String otherAlias, String otherField, Operator op)
    implements Constraint {

  /**
   * Canonical constructor.
   *
   * @param field the dotted field path on this pattern's fact
   * @param otherAlias the earlier-bound alias
   * @param otherField the dotted field path on that alias's fact
   * @param op the comparison
   */
  public JoinConstraint {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(otherAlias, "otherAlias");
    Objects.requireNonNull(otherField, "otherField");
    Objects.requireNonNull(op, "op");
  }
}
