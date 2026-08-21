package com.codeheadsystems.rules.fact;

import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A fact lifted out of a session so it can be replayed into another one (spec §5.6).
 *
 * <p><strong>No handle.</strong> That is the important part of this type. A replayed fact is a new
 * fact in a new session and gets a new handle; carrying the old one over would suggest the two are
 * the same fact across a boundary where nothing else -- refraction state, memories, node ids -- is
 * carried over at all. What must survive is the type, the payload, and the <em>order</em>, because
 * §7.3 guarantees a firing sequence only for the same facts in the same insertion order.
 *
 * @param type the fact type, as given at insert
 * @param payload the payload, deep-copied on export so the exporting session's memory cannot be
 *     reached through it
 * @param origin where the fact came from, carried for diagnostics; {@code exportFacts()} already
 *     filters to {@link Origin#ASSERTED}
 */
public record ExportedFact(String type, JsonNode payload, Origin origin) {

  /**
   * Creates an exported fact.
   *
   * @param type the fact type
   * @param payload the payload
   * @param origin the provenance
   */
  public ExportedFact {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(origin, "origin");
  }
}
