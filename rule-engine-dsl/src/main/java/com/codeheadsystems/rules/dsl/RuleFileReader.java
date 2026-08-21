package com.codeheadsystems.rules.dsl;

import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;

/**
 * Rule-file text into a bound document (spec §6.5's first step).
 *
 * <p>The read produces three things and they are used by different gates: the raw tree, which the
 * rule-file schema validates; the bound {@link RuleFileDocument}, which {@link RuleFiles} walks; and
 * the {@link SourceIndex}, which puts a line number on everything either of them complains about.
 */
final class RuleFileReader {

  private RuleFileReader() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * What a successful read yields.
   *
   * @param source the file it came from
   * @param tree the raw document, for the schema gate
   * @param document the bound document
   * @param index where everything in the file is
   */
  record Parsed(RuleSource source, JsonNode tree, RuleFileDocument document, SourceIndex index) {}

  /**
   * Reads one rule file.
   *
   * @param source the file
   * @param sink collects diagnostics; appended to rather than thrown, so that a rule set spanning
   *     several files reports every file's problems in one go
   * @return the parsed file, or empty when it could not be read
   */
  static Optional<Parsed> read(final RuleSource source, final List<DslDiagnostic> sink) {
    final SourceIndex index = SourceIndex.of(source);
    final JsonNode tree;
    try {
      tree = source.format().mapper().readTree(source.text());
    } catch (final JacksonException malformed) {
      sink.add(malformed(source, malformed, "this file is not well-formed "
          + source.format() + ": "));
      return Optional.empty();
    }
    if (tree == null || tree.isNull() || tree.isMissingNode()) {
      sink.add(DslDiagnostic.at(DslError.SCHEMA_VIOLATION, index.nearest(""), null,
          "this file is empty; a rule file is an object with an 'apiVersion' and a 'rules' list"
              + " (§6.2.3)"));
      return Optional.empty();
    }
    if (!apiVersionIsKnown(tree, index, sink)) {
      return Optional.empty();
    }
    if (!RuleFileSchema.validate(tree, index, sink)) {
      return Optional.empty();
    }
    return bind(source, tree, index, sink);
  }

  /**
   * Checks §6.2.3's {@code apiVersion} before the schema does.
   *
   * <p>The schema states the same constraint and would catch this on its own. Checking first is
   * about the message: §6.2.3 makes a point of "a file naming an unknown version is a compile
   * error, not a best-effort parse", and a file written against a future rules.v2 deserves to be
   * told that rather than to be handed a list of every v2 key this schema does not recognise.
   *
   * @param tree the raw document
   * @param index where everything in the file is
   * @param sink collects diagnostics
   * @return true when the version is one this DSL implements
   */
  private static boolean apiVersionIsKnown(final JsonNode tree, final SourceIndex index,
      final List<DslDiagnostic> sink) {
    final JsonNode declared = tree.path("apiVersion");
    if (declared.isString() && RuleFileDocument.API_VERSION.equals(declared.stringValue())) {
      return true;
    }
    /*
     * asString(toString()), and both halves matter.
     *
     * Not a bare asString(): Jackson 3 throws on an object or array node where Jackson 2's asText()
     * returned an empty string, and `declared` comes straight out of an untrusted rule file -- so
     * `apiVersion: {}` turned this diagnostic into a raw JsonNodeException escaping
     * RuleFiles.compile.
     *
     * Not a bare toString() either, which was the first fix and was wrong on the case that actually
     * happens. A misspelled version is a STRING, and toString() renders a string with its JSON
     * quotes, so `apiVersion: rules.v2` reported `declares '"rules.v2"'` -- doubled quotes on the
     * single most likely spelling of this error. The default-argument form keeps asText()'s
     * rendering for every scalar and falls back to the JSON form only for the containers that would
     * otherwise throw.
     */
    final String found = declared.isMissingNode()
        ? "no 'apiVersion' key"
        : "'" + declared.asString(declared.toString()) + "'";
    sink.add(DslDiagnostic.at(DslError.UNKNOWN_API_VERSION,
        index.nearest(declared.isMissingNode() ? "" : "/apiVersion"), null,
        "this rule file declares " + found + "; this engine implements '"
            + RuleFileDocument.API_VERSION + "' (§6.2.3)"));
    return false;
  }

  /**
   * Binds a validated tree to the document POJOs.
   *
   * @param source the file
   * @param tree the raw document
   * @param index where everything is
   * @param sink collects diagnostics
   * @return the parsed file, or empty when binding failed
   */
  private static Optional<Parsed> bind(final RuleSource source, final JsonNode tree,
      final SourceIndex index, final List<DslDiagnostic> sink) {
    try {
      return Optional.of(new Parsed(
          source, tree, source.format().mapper().treeToValue(tree, RuleFileDocument.class), index));
    } catch (final JacksonException unbindable) {
      /*
       * Reachable in normal use only when the schema gate is off, since the schema rejects every
       * structural fault this can hit and rejects it with a message that names the key. Kept
       * anyway: "the gate ahead of me guarantees this cannot happen" is exactly the assumption that
       * turns into a raw Jackson stack trace in somebody's startup log.
       */
      sink.add(malformed(source, unbindable, "this file does not have the shape of a rule file: "));
      return Optional.empty();
    }
  }

  /**
   * Turns a Jackson failure into a located diagnostic.
   *
   * <p><strong>{@code JacksonException} is unchecked under Jackson 3</strong>, where the Jackson 2
   * exception it replaces here, {@code JsonProcessingException}, was checked. Nothing here
   * changed shape, but the
   * compiler no longer insists: deleting either catch above would now build clean and turn a
   * malformed rule file back into a raw stack trace in somebody's startup log, which is the exact
   * outcome the second catch's comment argues against. The catches are load-bearing on their own
   * merits now, not because javac says so.
   *
   * @param source the file
   * @param failure what Jackson threw
   * @param preamble what to say before Jackson's own explanation
   * @return the diagnostic
   */
  private static DslDiagnostic malformed(final RuleSource source,
      final JacksonException failure, final String preamble) {
    final String message = preamble + failure.getOriginalMessage();
    final TokenStreamLocation location = failure.getLocation();
    if (location == null) {
      return DslDiagnostic.of(DslError.MALFORMED_DOCUMENT, source.name() + ": " + message);
    }
    return DslDiagnostic.at(DslError.MALFORMED_DOCUMENT,
        new SourceLocation(source.name(), location.getLineNr(), location.getColumnNr(), ""),
        null, message);
  }
}
