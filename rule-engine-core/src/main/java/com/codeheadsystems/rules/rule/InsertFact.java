package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inserts a derived fact (spec §2.5, §6.2.2).
 *
 * <p>Its handle is allocated at <em>stage</em> time, not at commit, so a later action in the same
 * RHS can reference the new fact through {@link #alias()} -- a common shape is "insert a derived
 * fact, then emit an event naming it". Only the propagation is deferred: the fact becomes visible
 * to matching at commit.
 *
 * @param factType the type of the fact to insert
 * @param alias an optional binding name for the new handle, so later actions in this RHS can
 *     reference it; empty when the rule never refers back to it
 * @param payload the fields of the new fact, in declaration order
 */
public record InsertFact(String factType, Optional<String> alias, List<PayloadField> payload)
    implements ActionDefinition {

  /**
   * Canonical constructor. Defensively copies {@code payload}.
   *
   * @param factType the type of the fact to insert
   * @param alias the optional binding name
   * @param payload the fields of the new fact
   */
  public InsertFact {
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(alias, "alias");
    payload = List.copyOf(payload);
  }

  /**
   * Builds an unaliased insert.
   *
   * @param factType the type of the fact to insert
   * @param payload the fields of the new fact
   * @return the action
   */
  public static InsertFact of(final String factType, final List<PayloadField> payload) {
    return new InsertFact(factType, Optional.empty(), payload);
  }
}
