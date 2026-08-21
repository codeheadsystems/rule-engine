package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.Paths;
import tools.jackson.core.JsonPointer;
import java.util.Objects;

/**
 * One named field of a payload an action builds -- an {@code insertFact} payload, an {@code emit}
 * payload, or a {@code callFunction} argument.
 *
 * <p><strong>Why an ordered list of these rather than §2.5's {@code Map<String, ValueExpr>}.</strong>
 * A {@code Map} does not guarantee iteration order, and these fields are materialised while a rule
 * is firing -- into an emitted event, or into the arguments a host function receives. §7.3 makes
 * determinism a contract, not a nice-to-have, so anything whose order is observable from outside
 * the engine gets a type that fixes the order. It also gives the path somewhere to be precompiled.
 *
 * @param name the field name in DSL dotted form, kept for diagnostics and the compiler report
 * @param path the precompiled path, so payload construction parses nothing at fire time
 * @param value the constant or reference that supplies the value
 */
public record PayloadField(String name, JsonPointer path, ValueExpr value) {

  /**
   * Canonical constructor.
   *
   * @param name the DSL field name
   * @param path the precompiled path
   * @param value the value expression
   */
  public PayloadField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(value, "value");
  }

  /**
   * Builds a payload field, compiling its path.
   *
   * @param name the DSL field name, e.g. {@code orderId} or {@code customer.id}
   * @param value the value expression
   * @return the compiled payload field
   */
  public static PayloadField of(final String name, final ValueExpr value) {
    return new PayloadField(name, Paths.compile(name), value);
  }
}
