package com.codeheadsystems.rules.rhs;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes a value into a mutable JSON tree at a precompiled path, creating intermediate objects.
 *
 * <p>Used by {@code setField} and by the payload construction of {@code insertFact},
 * {@code emit} and {@code callFunction}. Every path here was compiled at rule-compile time
 * (spec §10: no path-string parsing in the hot path).
 */
final class JsonWriter {

  private JsonWriter() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Sets one value.
   *
   * @param root the tree to write into. Must be engine-owned and safe to mutate -- either a fresh
   *     node, or a deep copy of a stored payload
   * @param path where to write it
   * @param value the value to write
   * @throws IllegalArgumentException if the path addresses the payload root, or names an array
   *     index, which is not writable through this mechanism
   */
  static void set(final ObjectNode root, final JsonPointer path, final JsonNode value) {
    if (path.matches()) {
      throw new IllegalArgumentException("cannot set the payload root; name a field");
    }
    final JsonPointer parent = path.head();
    final String property = path.last().getMatchingProperty();
    if (property == null) {
      throw new IllegalArgumentException("cannot write through an array index: " + path);
    }
    final ObjectNode target = parent.matches() ? root : root.withObject(parent);
    target.set(property, value);
  }
}
