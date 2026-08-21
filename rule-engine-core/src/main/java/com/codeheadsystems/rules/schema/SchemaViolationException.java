package com.codeheadsystems.rules.schema;

import java.io.Serial;
import java.util.List;

/**
 * A fact was rejected by its registered schema (spec §2.3).
 *
 * <p>Thrown from {@code insert} and {@code update} rather than recorded, because there is no
 * meaningful way to carry on: the alternative to failing here is letting a malformed fact into
 * working memory, where §2.3 observes it becomes "a fact that silently does not match every rule
 * expecting a field it lacks, which is a much harder bug to spot".
 *
 * <p>Its own type rather than {@code IllegalArgumentException}, so that an ingestion boundary can
 * catch exactly this -- routing a bad record to a dead-letter queue is a normal thing to want, and
 * catching a generic runtime exception there would swallow real defects with it.
 */
public final class SchemaViolationException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The fact type that was rejected. */
  private final String factType;

  /**
   * Every violation the schema reported.
   *
   * <p>Transient for the reason the compiler's diagnostics are: {@code List} is not declared
   * serializable, and the message already carries every violation.
   */
  private final transient List<String> violations;

  /**
   * Creates the exception.
   *
   * @param factType the fact type
   * @param violations every violation found, in the order the validator reported them
   */
  public SchemaViolationException(final String factType, final List<String> violations) {
    super(factType + " does not match its registered schema:" + System.lineSeparator() + "  - "
        + String.join(System.lineSeparator() + "  - ", violations));
    this.factType = factType;
    this.violations = List.copyOf(violations);
  }

  /**
   * The rejected fact's type.
   *
   * @return the type
   */
  public String factType() {
    return factType;
  }

  /**
   * Every violation found.
   *
   * @return the violations, in report order -- or null if this exception has crossed a
   *     serialisation boundary, since the list is transient. The message keeps all of them either
   *     way, and this exception is meant to be handled in the JVM that threw it
   */
  public List<String> violations() {
    return violations;
  }
}
