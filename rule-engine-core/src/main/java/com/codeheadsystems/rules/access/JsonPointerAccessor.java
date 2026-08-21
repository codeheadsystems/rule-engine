package com.codeheadsystems.rules.access;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A {@link FieldAccessor} backed by a precompiled {@link JsonPointer} (spec §2.6).
 *
 * <p>Jackson documents {@code JsonPointer} as immutable and shareable, so an instance of this
 * record can be cached in the compiled rule set and used from every session with no
 * synchronisation.
 *
 * @param pointer the compiled path; see {@link Paths#compile(String)}
 */
public record JsonPointerAccessor(JsonPointer pointer) implements FieldAccessor {

  /**
   * Canonical constructor.
   *
   * @param pointer the compiled path
   */
  public JsonPointerAccessor {
    Objects.requireNonNull(pointer, "pointer");
  }

  @Override
  public JsonNode get(final JsonNode payload) {
    return payload.at(pointer);
  }
}
