package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.Paths;
import java.util.Objects;
import tools.jackson.core.JsonPointer;

/**
 * A reference to a field of a fact bound by the LHS, resolved at fire time (spec §2.5).
 *
 * <p>The path is precompiled, so nothing parses a path string while a rule is firing (§10).
 *
 * <p><strong>A bare alias with an empty path is legal, and means one thing only:</strong> the answer
 * of an {@code ACCUMULATE} pattern (§2.5's second amendment), which binds a value rather than a
 * fact and so has no field to name. Every other alias binds a payload, and an empty path there
 * would mean "the whole payload" -- a thing no verb in §11.3's closed set can use, since a
 * {@code setField} needs somewhere to write and an {@code insertFact} payload field needs a scalar.
 * The compiler is where that distinction is enforced; this record only carries it.
 *
 * @param alias the LHS alias whose fact to read, or the accumulate whose answer to fold
 * @param path the precompiled path into that fact's payload; empty for an accumulate answer
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
   * <p>A name with no dot is taken as a whole-alias reference, which only an {@code ACCUMULATE}
   * alias can satisfy. Rejecting it here instead would put the check in the wrong place: this
   * method cannot see the rule, so it cannot tell an accumulate alias from a typo, and the
   * compiler -- which can -- reports it by name.
   *
   * @param aliasDotField a reference such as {@code o.customer.id}, or a bare accumulate alias
   * @return the compiled reference
   * @throws IllegalArgumentException if the name is empty, or ends in a trailing dot
   */
  public static FieldRef of(final String aliasDotField) {
    final int dot = aliasDotField.indexOf('.');
    if (dot < 0) {
      if (aliasDotField.isEmpty()) {
        throw new IllegalArgumentException("field reference must name an alias");
      }
      return new FieldRef(aliasDotField, JsonPointer.empty());
    }
    if (dot < 1 || dot == aliasDotField.length() - 1) {
      throw new IllegalArgumentException(
          "field reference must be 'alias.field', got '" + aliasDotField + "'");
    }
    return new FieldRef(
        aliasDotField.substring(0, dot), Paths.compile(aliasDotField.substring(dot + 1)));
  }
}
