package com.codeheadsystems.rules.access;

import com.fasterxml.jackson.core.JsonPointer;

/**
 * Compiles the DSL's dotted field paths into RFC 6901 JSON Pointers (spec §2.6).
 *
 * <p>{@code customer.id} becomes {@code /customer/id}. This runs at rule-compile time, once per
 * {@code (factType, field)} pair, never at match time.
 *
 * <p><strong>Escaping.</strong> RFC 6901 gives {@code ~} and {@code /} special meaning inside a
 * pointer, so a field name containing either must be escaped ({@code ~} as {@code ~0},
 * {@code /} as {@code ~1}) before it is spliced into the pointer text. Skipping this turns a
 * field literally named {@code a/b} into a two-segment path, which is a rule that silently never
 * matches -- exactly the failure mode §2.6.1 exists to eliminate.
 *
 * <p><strong>Array indices are addressable, wildcards are not.</strong> {@code items.0.qty}
 * compiles to {@code /items/0/qty} and works. There is no wildcard: RFC 6901 has none, which is
 * why §1 defers collection matching and tells you to flatten collections into separate facts at
 * ingestion.
 */
public final class Paths {

  private Paths() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Compiles a dotted field path.
   *
   * @param dottedPath a field path in DSL form, e.g. {@code customer.id}. An empty string
   *     addresses the payload root
   * @return the compiled, immutable pointer
   * @throws IllegalArgumentException if the path has an empty segment, e.g. {@code a..b}
   */
  public static JsonPointer compile(final String dottedPath) {
    if (dottedPath == null || dottedPath.isEmpty()) {
      return JsonPointer.empty();
    }
    final StringBuilder pointer = new StringBuilder(dottedPath.length() + 8);
    for (final String segment : dottedPath.split("\\.", -1)) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException("empty path segment in '" + dottedPath + "'");
      }
      pointer.append('/').append(escape(segment));
    }
    return JsonPointer.compile(pointer.toString());
  }

  /**
   * Applies RFC 6901 reference-token escaping to one path segment.
   *
   * <p>The order matters: {@code ~} must be escaped before {@code /}, or the {@code ~1} produced
   * by escaping a slash would itself be re-escaped into {@code ~01}.
   *
   * @param segment one dot-separated segment of a field path
   * @return the segment, safe to splice into pointer text
   */
  private static String escape(final String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }
}
