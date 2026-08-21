package com.codeheadsystems.rules.report;

import java.util.Objects;
import java.util.Optional;

/**
 * One thing the compiler noticed (spec §7.4).
 *
 * @param ruleId the rule it belongs to
 * @param code a stable, machine-matchable code. Prose changes; a code is what CI pins to
 * @param message what was noticed, in prose
 * @param fieldPath the field it concerns, or empty when it concerns the whole rule
 */
public record Diagnostic(
    String ruleId, String code, String message, Optional<String> fieldPath) {

  /**
   * Canonical constructor.
   *
   * @param ruleId the owning rule
   * @param code the stable code
   * @param message the prose description
   * @param fieldPath the field concerned
   */
  public Diagnostic {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(fieldPath, "fieldPath");
  }
}
