/**
 * Field access over JSON payloads.
 *
 * <p>Because a fact's payload is canonically a {@link tools.jackson.databind.JsonNode}
 * (spec §2.2), field access is JSON Pointer traversal rather than reflection. The concern this
 * package exists to address is stated in §2.6: <em>do not re-parse a path string on every fact,
 * every cycle</em>. Paths are compiled once, at rule-compile time, and the resulting
 * {@link tools.jackson.core.JsonPointer} is immutable and shared across every session.
 */
package com.codeheadsystems.rules.access;
