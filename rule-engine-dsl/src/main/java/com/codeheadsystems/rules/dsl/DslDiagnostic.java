package com.codeheadsystems.rules.dsl;

import java.util.Objects;
import java.util.Optional;

/**
 * One problem with a rule file, and where it is.
 *
 * @param error what kind of problem it is
 * @param message what is wrong, in prose, naming the offending value wherever there is one to name
 * @param location where in the file, or empty when the problem belongs to no single element --
 *     a whole file that will not parse, most obviously
 * @param ruleId the rule the problem belongs to, or empty for a file-level problem
 */
public record DslDiagnostic(
    DslError error, String message, Optional<SourceLocation> location, Optional<String> ruleId) {

  /**
   * Canonical constructor.
   *
   * @param error the kind of problem
   * @param message the prose description
   * @param location where in the file
   * @param ruleId the owning rule
   */
  public DslDiagnostic {
    Objects.requireNonNull(error, "error");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(ruleId, "ruleId");
  }

  /**
   * A diagnostic located at a point in a file.
   *
   * @param error the kind of problem
   * @param location where in the file
   * @param ruleId the owning rule, or null for a file-level problem
   * @param message the prose description
   * @return the diagnostic
   */
  public static DslDiagnostic at(final DslError error, final SourceLocation location,
      final String ruleId, final String message) {
    return new DslDiagnostic(
        error, message, Optional.of(location), Optional.ofNullable(ruleId));
  }

  /**
   * A diagnostic with no more precise home than the file itself.
   *
   * @param error the kind of problem
   * @param message the prose description
   * @return the diagnostic
   */
  public static DslDiagnostic of(final DslError error, final String message) {
    return new DslDiagnostic(error, message, Optional.empty(), Optional.empty());
  }

  /**
   * Renders this diagnostic the way a compiler prints one.
   *
   * @return {@code file:line:column: [code] message}, degrading gracefully when there is no location
   */
  public String describe() {
    return location.map(SourceLocation::describe).map(where -> where + ": ").orElse("")
        + "[" + error.code() + "] " + message;
  }
}
