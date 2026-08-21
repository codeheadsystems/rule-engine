package com.codeheadsystems.rules.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.NodePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The rule-file schema gate (spec §6.5: "fail fast, before touching the network").
 *
 * <p>Structure only. Which keys exist, what each holds, which keys a verb accepts -- all of it
 * stated once, in {@code rules.v1.json}, and enforced here before anything is bound. What an
 * operand <em>means</em> belongs to {@link OperatorMaps} and {@link Actions}, because §6.2.3 puts
 * the {@code $ref}/{@code $$ref} judgement in one place and a schema cannot make it.
 *
 * <p>The schema earns its keep twice. Once here, and once as a published artifact: it is what an
 * editor or a CI linter validates a rule file against without running this engine, which is what
 * makes §6.2.3's promise -- that {@code apiVersion} lets the schema evolve without silently
 * reinterpreting existing files -- something more than an intention.
 */
final class RuleFileSchema {

  /** Where the schema resource sits, beside this class. */
  private static final String RESOURCE = "rules.v1.json";

  private RuleFileSchema() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Holds the compiled schema, initialised on first use.
   *
   * <p>Compiled once and shared: a {@link Schema} is immutable and thread-safe, and recompiling it
   * per file would put a parse of this document in front of every parse of a rule file.
   */
  private static final class Holder {
    private static final Schema SCHEMA = load();

    private Holder() {
      throw new UnsupportedOperationException("no instances");
    }

    private static Schema load() {
      try (InputStream resource = RuleFileSchema.class.getResourceAsStream(RESOURCE)) {
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Objects.requireNonNull(resource, RESOURCE + " is missing from the jar"));
      } catch (final IOException unreadable) {
        // The schema ships inside this module's jar. Unreadable means a broken build artifact,
        // which no caller can do anything about and none should have to catch.
        throw new UncheckedIOException("cannot read " + RESOURCE, unreadable);
      }
    }
  }

  /**
   * Validates a rule file against {@code rules.v1}.
   *
   * @param tree the raw document
   * @param index where everything in the file is
   * @param sink collects diagnostics
   * @return true when the document is structurally valid
   */
  static boolean validate(final JsonNode tree, final SourceIndex index,
      final List<DslDiagnostic> sink) {
    final List<Error> errors = Holder.SCHEMA.validate(tree);
    if (errors.isEmpty()) {
      return true;
    }
    /*
     * Sorted by where they are in the FILE, not by the order the validator happened to visit them
     * and not by the pointer text. A list of problems in a rule file is read top to bottom against
     * the file, and any other order makes the reader do the sorting. Sorting on the pointer string
     * would look right until a file grew past ten rules, at which point /rules/10 sorts between
     * /rules/1 and /rules/2 -- so the line is what to sort on, with the pointer only as a
     * tiebreaker for elements the index could not place.
     */
    errors.stream()
        .map(error -> {
          final String pointer = pointerOf(error.getInstanceLocation());
          return DslDiagnostic.at(DslError.SCHEMA_VIOLATION, index.nearest(pointer),
              ruleIdAt(tree, pointer), error.getMessage());
        })
        .sorted(Comparator
            .<DslDiagnostic>comparingInt(d -> d.location().map(SourceLocation::line).orElse(0))
            .thenComparingInt(d -> d.location().map(SourceLocation::column).orElse(0))
            .thenComparing(d -> d.location().map(SourceLocation::pointer).orElse(""))
            .thenComparing(DslDiagnostic::message))
        .forEach(sink::add);
    return false;
  }

  /**
   * Converts the validator's path into an RFC 6901 pointer.
   *
   * <p>Built from the path's elements rather than from its {@code toString()}, which renders in
   * whichever {@code PathType} the registry was configured with. Depending on that would make this
   * class's correctness a function of a setting made somewhere else.
   *
   * @param path the validator's instance location
   * @return the equivalent JSON Pointer
   */
  private static String pointerOf(final NodePath path) {
    final StringBuilder pointer = new StringBuilder();
    for (int element = 0; element < path.getNameCount(); element++) {
      final Object segment = path.getElement(element);
      pointer.append('/').append(segment instanceof Integer
          ? segment.toString()
          : segment.toString().replace("~", "~0").replace("/", "~1"));
    }
    return pointer.toString();
  }

  /**
   * Which rule a pointer falls inside.
   *
   * <p>Read off the document rather than off the bound POJOs, because binding is what has not
   * happened yet when this runs. A rule whose own {@code id} is the malformed part has no id to
   * report, which is why the result is nullable rather than a placeholder.
   *
   * @param tree the raw document
   * @param pointer the offending element's pointer
   * @return the enclosing rule's id, or null if there is not one to name
   */
  private static String ruleIdAt(final JsonNode tree, final String pointer) {
    if (!pointer.startsWith("/rules/")) {
      return null;
    }
    final int slash = pointer.indexOf('/', "/rules/".length());
    final String index = slash < 0
        ? pointer.substring("/rules/".length())
        : pointer.substring("/rules/".length(), slash);
    try {
      final JsonNode id = tree.path("rules").path(Integer.parseInt(index)).path("id");
      return id.isTextual() ? id.textValue() : null;
    } catch (final NumberFormatException notAnIndex) {
      return null;
    }
  }
}
