package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.Paths;
import com.fasterxml.jackson.core.JsonPointer;
import java.util.Objects;

/**
 * A reference to a field of a fact bound by the LHS, resolved at fire time (spec §2.5).
 *
 * <p>The path is precompiled, so nothing parses a path string while a rule is firing (§10).
 *
 * @param alias the LHS alias whose fact to read
 * @param path the precompiled path into that fact's payload
 */
public record FieldRef(String alias, JsonPointer path) implements ValueExpr {

  /**
   * Canonical constructor.
   *
   * @param alias the LHS alias
   * @param path the precompiled path
   */
  public FieldRef {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(path, "path");
  }

  /**
   * Builds a reference from the DSL's {@code alias.dotted.field} form.
   *
   * @param aliasDotField a reference such as {@code o.customer.id}
   * @return the compiled reference
   * @throws IllegalArgumentException if there is no alias separator
   */
  public static FieldRef of(final String aliasDotField) {
    final int dot = aliasDotField.indexOf('.');
    if (dot < 1 || dot == aliasDotField.length() - 1) {
      throw new IllegalArgumentException(
          "field reference must be 'alias.field', got '" + aliasDotField + "'");
    }
    return new FieldRef(
        aliasDotField.substring(0, dot), Paths.compile(aliasDotField.substring(dot + 1)));
  }
}
