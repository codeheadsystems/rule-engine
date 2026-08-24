package com.codeheadsystems.rules.example;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * One line of the feed: what happened, and the data it carried.
 *
 * <p>This is the application's own envelope, not the engine's. Nothing in the engine knows what an
 * event is -- {@link Ingest} is where an event becomes some number of facts, and the shape of that
 * translation is the most consequential modelling decision in the whole example.
 *
 * @param type the event name, as the feed spells it
 * @param payload the event body
 */
public record OrderEvent(String type, JsonNode payload) {

  /**
   * Canonical constructor.
   *
   * @param type the event name
   * @param payload the event body
   */
  public OrderEvent {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
  }

  /**
   * Reads a string field from the payload.
   *
   * @param field the field name
   * @return its value
   * @throws IllegalArgumentException if the field is absent or is not a string
   */
  public String text(final String field) {
    return requiredText(payload, field, type + " event");
  }

  /**
   * Reads a string field from any JSON object, or says what was wrong with it.
   *
   * <p><strong>Jackson 3's typed accessors are strict.</strong> {@code stringValue()} throws on a
   * type mismatch where Jackson 2's {@code textValue()} returned null -- and so does the coercing
   * {@code asString()}, which is the part that catches people out, because coercion sounds total.
   * So the guard below is doing real work rather than being defensive noise, and this method exists
   * so that every read of untrusted JSON in this module goes through one. That is the rule the
   * engine's own source keeps; see CLAUDE.md.
   *
   * @param owner the object to read from
   * @param field the field name
   * @param context what to call the object in the failure message
   * @return the field's value
   * @throws IllegalArgumentException if the field is absent or is not a string
   */
  public static String requiredText(final JsonNode owner, final String field,
      final String context) {
    final JsonNode value = owner == null ? null : owner.get(field);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException(context + " has no string '" + field + "': " + owner);
    }
    return value.stringValue();
  }
}
