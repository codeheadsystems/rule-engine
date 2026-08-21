package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.Paths;
import tools.jackson.core.JsonPointer;
import java.util.Objects;

/**
 * Mutates a field of a fact bound by the LHS (spec §2.5, §6.2.2).
 *
 * <p>This is the only supported way to change a fact's content, and it routes through
 * {@code update()} (§2.2). Several {@code setField}s on the same target merge into a single
 * update, applied in declaration order (§4.6) -- staging each as an independent update built from
 * the pre-RHS payload is the single most likely week-one bug in this design, because the second
 * silently overwrites the first.
 *
 * @param targetAlias an alias bound by the LHS
 * @param field the field to set, in DSL dotted form, kept for diagnostics
 * @param path the precompiled path, so nothing parses a path string at fire time
 * @param value the literal or fire-time reference supplying the new value
 */
public record SetField(String targetAlias, String field, JsonPointer path, ValueExpr value)
    implements ActionDefinition {

  /**
   * Canonical constructor.
   *
   * @param targetAlias the LHS alias to mutate
   * @param field the DSL field name
   * @param path the precompiled path
   * @param value the new value
   */
  public SetField {
    Objects.requireNonNull(targetAlias, "targetAlias");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(value, "value");
  }

  /**
   * Builds a {@code setField} action, compiling its path.
   *
   * @param targetAlias the LHS alias to mutate
   * @param field the field to set, in DSL dotted form
   * @param value the new value
   * @return the compiled action
   */
  public static SetField of(final String targetAlias, final String field, final ValueExpr value) {
    return new SetField(targetAlias, field, Paths.compile(field), value);
  }
}
