package com.codeheadsystems.rules.dsl;

import java.util.Objects;

/**
 * Where in a rule file -- or a fact document -- something is (spec §6.5's pipeline, made usable to
 * the author).
 *
 * <p>This type is the reason the DSL layer is more than a call to Jackson. The compiler's
 * diagnostics are written for a rule set assembled in Java -- {@code high-value-order-review: alias
 * 'c' is bound twice} -- and that sentence is correct, complete, and nearly useless to somebody
 * looking at four hundred lines of YAML. Carrying a location lets the same diagnostic be reported
 * as {@code orders.yaml:47:7}, which is the difference between a rule file being editable and being
 * merely valid.
 *
 * @param file the source's name, as the caller supplied it
 * @param line the 1-based line, or 0 when the parser could not report one
 * @param column the 1-based column, or 0 when the parser could not report one
 * @param pointer the JSON Pointer into the document, e.g. {@code /rules/0/when/1/where/total}.
 *     Kept alongside the line because a pointer survives reformatting and a line number does not,
 *     and because it is what identifies the same element across the two serializations
 */
public record SourceLocation(String file, int line, int column, String pointer) {

  /**
   * Canonical constructor.
   *
   * @param file the source's name
   * @param line the 1-based line, or 0 if unknown
   * @param column the 1-based column, or 0 if unknown
   * @param pointer the JSON Pointer into the document
   */
  public SourceLocation {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(pointer, "pointer");
  }

  /**
   * Renders this location the way a compiler prints one.
   *
   * @return {@code file:line:column}, degrading to {@code file:line} and then to
   *     {@code file (pointer)} as the parser's information runs out
   */
  public String describe() {
    if (line <= 0) {
      return pointer.isEmpty() ? file : file + " (" + pointer + ")";
    }
    return column <= 0 ? file + ":" + line : file + ":" + line + ":" + column;
  }
}
