package com.codeheadsystems.rules.dsl;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamLocation;

/**
 * Where every element of a rule file sits, keyed by JSON Pointer.
 *
 * <p>Built by a token walk separate from the one that binds the document, which costs a second pass
 * over a few kilobytes of configuration and buys a line number on every diagnostic. Jackson's
 * databind layer does not surface locations on a bound object -- by the time a record exists, the
 * parser that knew where it came from is finished -- so the choice is a second pass or no locations
 * at all.
 *
 * <p><strong>An object member is indexed at its key, not its value.</strong> An author told that an
 * operator is unknown wants the line the operator is written on; the value may be lines away, or on
 * the same line, and pointing at it reads as an off-by-one even when it is defensible.
 */
final class SourceIndex {

  private final String file;
  private final Map<String, SourceLocation> byPointer;

  private SourceIndex(final String file, final Map<String, SourceLocation> byPointer) {
    this.file = file;
    this.byPointer = byPointer;
  }

  /**
   * Indexes a rule file.
   *
   * <p>Failures are swallowed deliberately. This index is diagnostic sugar, and a document broken
   * enough to fail the walk will fail the bind too, where it produces a real error. Reporting the
   * same breakage twice, once as a missing line number, helps nobody.
   *
   * @param source the rule file
   * @return the index, empty if the walk could not complete
   */
  static SourceIndex of(final RuleSource source) {
    return of(source.name(), source.text(), source.format());
  }

  /**
   * Indexes a fact document.
   *
   * <p>The same walk, and it has to be the same one: a fact document is the identical two
   * serializations bound to a different target type, so the pointer-to-line mapping is not
   * fact-specific in any way. A second copy here would be a second place for the escaping rules to
   * be wrong.
   *
   * @param source the fact document
   * @return the index, empty if the walk could not complete
   */
  static SourceIndex of(final FactSource source) {
    return of(source.name(), source.text(), source.format());
  }

  /**
   * Indexes a document.
   *
   * @param name the file name to stamp on locations
   * @param text the document text
   * @param format which serialization the text is written in
   * @return the index, empty if the walk could not complete
   */
  private static SourceIndex of(final String name, final String text, final RuleFormat format) {
    final Map<String, SourceLocation> found = new HashMap<>();
    try (JsonParser parser = format.factory()
        .createParser(ObjectReadContext.empty(), text)) {
      if (parser.nextToken() != null) {
        walk(parser, name, "", found);
      }
    } catch (final RuntimeException ignored) {
      // See the note above: the bind pass reports what is actually wrong with this document.
    }
    return new SourceIndex(name, Map.copyOf(found));
  }

  /**
   * Walks one value and everything under it.
   *
   * @param parser positioned on the value's first token
   * @param file the file name to stamp on locations
   * @param pointer the JSON Pointer of the value being walked
   * @param found the index being built
   */
  private static void walk(final JsonParser parser, final String file, final String pointer,
      final Map<String, SourceLocation> found) {
    record(found, pointer, file, parser.currentTokenLocation());
    final JsonToken token = parser.currentToken();
    if (token == JsonToken.START_OBJECT) {
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        final String child = pointer + "/" + escape(parser.currentName());
        // The key's location, recorded before the value's walk can claim the same pointer.
        record(found, child, file, parser.currentTokenLocation());
        parser.nextToken();
        walk(parser, file, child, found);
      }
    } else if (token == JsonToken.START_ARRAY) {
      int index = 0;
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        walk(parser, file, pointer + "/" + index, found);
        index++;
      }
    }
  }

  /**
   * Records a location, keeping the first one seen for a pointer.
   *
   * @param found the index being built
   * @param pointer the element's pointer
   * @param file the file name
   * @param location the parser's location, which may be null
   */
  private static void record(final Map<String, SourceLocation> found, final String pointer,
      final String file, final TokenStreamLocation location) {
    if (location != null) {
      found.putIfAbsent(pointer,
          new SourceLocation(file, location.getLineNr(), location.getColumnNr(), pointer));
    }
  }

  /**
   * Applies RFC 6901 reference-token escaping to one pointer segment.
   *
   * <p>The same two substitutions {@code Paths} makes, in the same order and for the same reason --
   * {@code ~} before {@code /}, or the {@code ~1} produced by escaping a slash is re-escaped into
   * {@code ~01}. Not shared with it because that class compiles the DSL's dotted paths into
   * pointers, which is a different job that happens to need the same two lines.
   *
   * @param segment a raw field name
   * @return the segment, safe to splice into pointer text
   */
  private static String escape(final String segment) {
    return segment.replace("~", "~0").replace("/", "~1");
  }

  /**
   * Where an element is.
   *
   * @param pointer the element's JSON Pointer
   * @return its location, or empty if the walk did not reach it
   */
  Optional<SourceLocation> at(final String pointer) {
    return Optional.ofNullable(byPointer.get(pointer));
  }

  /**
   * Where an element is, falling back to the nearest ancestor and finally to the file itself.
   *
   * <p>The fallback is what makes locations usable rather than best-effort. A diagnostic about a
   * malformed operand under {@code /rules/3/when/0/where/total/between} should not lose its line
   * because nothing indexed that exact pointer; the enclosing pattern's line is close enough to
   * navigate by, and far better than nothing.
   *
   * @param pointer the element's JSON Pointer
   * @return a location, always
   */
  SourceLocation nearest(final String pointer) {
    String candidate = pointer;
    while (!candidate.isEmpty()) {
      final Optional<SourceLocation> hit = at(candidate);
      if (hit.isPresent()) {
        return hit.get();
      }
      candidate = candidate.substring(0, candidate.lastIndexOf('/') < 0
          ? 0
          : candidate.lastIndexOf('/'));
    }
    return at("").orElseGet(() -> new SourceLocation(file, 0, 0, pointer));
  }
}
