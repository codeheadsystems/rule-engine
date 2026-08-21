package com.codeheadsystems.rules.session;

import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * An emission captured rather than delivered: the collecting sink's element type, and what a fire
 * result returns (spec §4.6).
 *
 * @param eventType the event name
 * @param payload the event payload
 * @param context the correlation context
 */
public record EmittedEvent(String eventType, JsonNode payload, EmitContext context) {

  /**
   * Canonical constructor.
   *
   * @param eventType the event name
   * @param payload the payload
   * @param context the correlation context
   */
  public EmittedEvent {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(context, "context");
  }
}
