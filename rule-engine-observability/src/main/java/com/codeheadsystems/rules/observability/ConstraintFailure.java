package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.rule.Constraint;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The constraint that eliminated a pattern's candidates, with the value that failed it
 * (spec §7.2).
 *
 * <p>The example value is the load-bearing part. "Status was SHIPPED, expected PENDING" is the
 * sentence an author needs, and it needs the <em>actual</em> value — an explanation that names only
 * the constraint tells them what they already read in their own rule file.
 *
 * @param constraint the constraint that failed
 * @param exampleHandle a fact it eliminated, so the author can go and look at it
 * @param actualValue what that fact actually had at the constraint's path
 * @param eliminated how many candidates this constraint removed
 */
public record ConstraintFailure(Constraint constraint, long exampleHandle, JsonNode actualValue,
    int eliminated) {

  /**
   * Canonical constructor.
   *
   * @param constraint the failing constraint
   * @param exampleHandle an eliminated fact
   * @param actualValue its value at the constraint's path
   * @param eliminated how many were eliminated
   */
  public ConstraintFailure {
    Objects.requireNonNull(constraint, "constraint");
    Objects.requireNonNull(actualValue, "actualValue");
  }

  /**
   * A one-line rendering an author can act on.
   *
   * @return e.g. {@code status was "SHIPPED" (eliminated 3 of them)}
   */
  public String describe() {
    return constraint.field() + " was " + actualValue
        + " on fact #" + exampleHandle
        + ", failing " + constraint
        + (eliminated > 1 ? " (and " + (eliminated - 1) + " more)" : "");
  }
}
