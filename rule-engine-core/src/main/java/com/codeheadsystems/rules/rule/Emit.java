package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;

/**
 * Emits an event to the session's sink (spec §2.5, §6.2.2).
 *
 * <p>Emission is staged, so events from a failed RHS are never delivered, and delivery order is
 * rule-firing order. The default sink collects into the fire result rather than performing I/O,
 * which is what makes rules testable without mocking anything (§4.6).
 *
 * @param eventType the event name
 * @param payload the event fields, in declaration order
 */
public record Emit(String eventType, List<PayloadField> payload) implements ActionDefinition {

  /**
   * Canonical constructor. Defensively copies {@code payload}.
   *
   * @param eventType the event name
   * @param payload the event fields
   */
  public Emit {
    Objects.requireNonNull(eventType, "eventType");
    payload = List.copyOf(payload);
  }
}
