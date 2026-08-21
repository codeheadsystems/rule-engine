package com.codeheadsystems.rules.access;

import tools.jackson.databind.JsonNode;

/**
 * Reads one value out of a fact payload.
 *
 * <p>Implementations are pure, immutable and thread-safe: they live in the shared compiled rule
 * set (spec §3.2.3) and are read concurrently by every session.
 */
@FunctionalInterface
public interface FieldAccessor {

  /**
   * Reads the value this accessor addresses.
   *
   * @param payload the fact payload to read from; never {@code null}
   * @return the value at this accessor's path, or {@link tools.jackson.databind.node.MissingNode}
   *     when the path is not present. Never {@code null} -- §2.6.1 distinguishes an absent value
   *     from an explicit JSON null, and both are represented as nodes rather than as {@code null}
   */
  JsonNode get(JsonNode payload);
}
