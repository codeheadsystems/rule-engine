package com.codeheadsystems.rules.rule;

/**
 * One condition inside a pattern (spec §2.5).
 *
 * <p>Sealed over records, which are implicitly final, so this hierarchy is complete as written.
 */
public sealed interface Constraint
    permits FieldConstraint, RangeConstraint, JoinConstraint, ExpressionConstraint {

  /**
   * The field this constraint reads, in DSL dotted form.
   *
   * @return the dotted path, e.g. {@code customer.id}
   */
  String field();
}
