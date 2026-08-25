package com.codeheadsystems.rules.dsl;

import java.util.Objects;
import java.util.Optional;

/**
 * One problem with a fact document, and where it is.
 *
 * <p>The fact-side twin of {@link DslDiagnostic}, minus the {@link DslError} code, and the missing
 * code is a decision rather than an omission. A code exists on the rule side because tooling has to
 * match on a problem without matching on prose, and because {@code docs/dsl-reference.md} carries a
 * catalogue that {@code DocExamplesTest} holds to the enum -- a guard that only works while every
 * code belongs in that one document. A fact document is data rather than authored configuration:
 * it has four ways to be wrong, all of them structural, none of them belonging in the rule-file
 * reference. Adding them to {@code DslError} would have put ingestion codes in the DSL reference or
 * loosened the test that keeps it complete, and neither is worth a code nobody switches on.
 *
 * @param message what is wrong, in prose, naming the offending value wherever there is one to name
 * @param location where in the document, or empty when the problem belongs to no single element --
 *     a whole file that will not parse, most obviously
 */
public record FactDiagnostic(String message, Optional<SourceLocation> location) {

  /**
   * Canonical constructor.
   *
   * @param message the prose description
   * @param location where in the document
   */
  public FactDiagnostic {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(location, "location");
  }

  /**
   * A diagnostic located at a point in a document.
   *
   * @param location where in the document
   * @param message the prose description
   * @return the diagnostic
   */
  public static FactDiagnostic at(final SourceLocation location, final String message) {
    return new FactDiagnostic(message, Optional.of(location));
  }

  /**
   * A diagnostic with no more precise home than the document itself.
   *
   * @param message the prose description
   * @return the diagnostic
   */
  public static FactDiagnostic of(final String message) {
    return new FactDiagnostic(message, Optional.empty());
  }

  /**
   * Renders this diagnostic the way a compiler prints one.
   *
   * @return {@code file:line:column: message}, degrading gracefully when there is no location
   */
  public String describe() {
    return location.map(SourceLocation::describe).map(where -> where + ": ").orElse("") + message;
  }
}
