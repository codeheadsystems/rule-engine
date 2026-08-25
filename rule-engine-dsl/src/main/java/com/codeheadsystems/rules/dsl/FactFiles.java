package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.fact.ExportedFact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.Origin;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fact documents into facts. The fact-side front door, beside {@link RuleFiles} (spec §6.1).
 *
 * <pre>{@code
 * CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
 *
 * try (RuleSession session = rules.newSession()) {
 *     FactFiles.insertInto(session, FactSource.of(Path.of("facts.yaml")));
 *     FireResult result = session.fireAllRules();
 * }
 * }</pre>
 *
 * <p>The document is a list of typed facts, in either serialization:
 *
 * <pre>{@code
 * - type: Customer
 *   payload: { id: "c1", riskTier: "HIGH" }
 * - type: Order
 *   payload:
 *     id: "o1"
 *     customerId: "c1"
 *     total: 12000
 * }</pre>
 *
 * <p><strong>The list order is the insertion order, and that makes it part of the input.</strong>
 * §7.3 states the determinism contract over the same facts in the same insertion order, so two
 * documents holding the same facts in different orders are two different inputs and may fire
 * differently. Reordering a fact document is editing it.
 *
 * <p><strong>Every problem is reported at once</strong>, as {@link RuleFiles} reports every
 * problem in a rule set, and nothing is inserted unless the whole document is good. See
 * {@link FactFileException}.
 *
 * <p><strong>A YAML alias does not survive, and nothing here can catch it.</strong>
 * {@code b: *x} reaches this reader as the <em>string</em> {@code "x"} rather than as the value the
 * anchor held: Jackson's YAML parser has already flattened it by the time the token stream is
 * readable, and the object id it would take to notice is null on every token. This is the failure
 * shape {@link RuleFormat} turns on strict duplicate detection to avoid -- it reads correctly and
 * is silently a different value -- and unlike a rule file there is no schema behind this to catch
 * the consequence. Write fact documents without anchors, or check the parsed payload.
 *
 * <p><strong>What this is not.</strong> It is not an ingestion path for a stream. A fact document
 * is a fixture, a seed, or a captured session -- read once, whole, from text somebody can edit.
 * An application whose facts arrive as events should do what {@code rule-engine-example}'s
 * {@code Ingest} does: decide fact identity, flatten collections (§2.4) and normalise absent fields
 * itself, none of which a generic reader can decide on its behalf.
 */
public final class FactFiles {

  /** The two keys an entry may hold, named here so the diagnostic and the check cannot disagree. */
  private static final String TYPE = "type";

  private static final String PAYLOAD = "payload";

  private FactFiles() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Reads a fact document.
   *
   * <p>Answers {@link ExportedFact} rather than a record of this module's own, and the reuse is
   * deliberate: that type is already "a fact outside a session" -- a type, a payload and a
   * provenance -- and it is what {@code SessionDrain.replay} consumes. A parallel record would have
   * meant a conversion on the one path this exists to serve. Every fact read from a document is
   * {@link Origin#ASSERTED}: a document states what is true, and what a rule set concludes from it
   * is the session's to derive (§4.4's truth maintenance withdraws conclusions, and replaying a
   * derived fact would double-count it).
   *
   * @param source the document
   * @return the facts, in document order, which is the order they must be inserted in
   * @throws FactFileException if the document cannot be read or does not hold facts
   */
  public static List<ExportedFact> read(final FactSource source) {
    Objects.requireNonNull(source, "source");
    final SourceIndex index = SourceIndex.of(source);
    final JsonNode tree = parse(source, index);
    if (!tree.isArray()) {
      throw new FactFileException(List.of(FactDiagnostic.at(index.nearest(""),
          "a fact document is a list of { " + TYPE + ", " + PAYLOAD + " } entries, and this one is "
              + shapeOf(tree) + ". A single fact is a list of one")));
    }
    final List<FactDiagnostic> problems = new ArrayList<>();
    final List<ExportedFact> facts = new ArrayList<>();
    int position = 0;
    for (final JsonNode entry : tree) {
      final String pointer = "/" + position;
      readEntry(entry, pointer, index, problems).ifPresent(facts::add);
      position++;
    }
    if (!problems.isEmpty()) {
      throw new FactFileException(problems);
    }
    return List.copyOf(facts);
  }

  /**
   * Reads a fact document and inserts every fact into a session.
   *
   * <p>Inserts through {@code insertOwned}, exactly as {@code SessionDrain.replay} does and for the
   * same reason: the payloads were parsed a moment ago from text and no caller holds a reference to
   * them, so §2.2's defensive copy would copy a tree nothing else can reach.
   *
   * <p><strong>A document either loads or leaves nothing behind.</strong> The whole of it is read
   * and validated before the first insert, and an insert that throws part-way -- a §2.3 schema
   * violation on the fourth fact of ten -- is unwound by retracting what already landed. Half a
   * fixture is a different input rather than a failed one, and a session left holding one is a
   * session no rule set was written against. Three things the unwind does not claim: a fact
   * <em>eviction</em> removed while this was loading does not come back; listeners saw the inserts
   * and will see the retracts, because both went through the ordinary paths; and an insert that
   * throws <em>after</em> the fact reached working memory -- from network propagation, from a
   * listener, or from an eviction policy -- leaves that one fact, because its handle was never
   * handed back. A §2.3 schema violation, the ordinary case, is raised before the fact is stored
   * and unwinds completely.
   *
   * <p>Returned handles are the caller's to update or retract. Under a §4.4 eviction policy some
   * may already be dead -- a windowed policy takes a fact stamped before its far edge inside the
   * insert that added it -- which is true of any insert into such a session, not of this method.
   *
   * @param session the session to load; not closed by this method
   * @param source the document
   * @return the handles, in document order, which is the order they were inserted in
   * @throws FactFileException if the document cannot be read or does not hold facts
   */
  public static List<FactHandle> insertInto(final RuleSession session, final FactSource source) {
    Objects.requireNonNull(session, "session");
    final List<ExportedFact> facts = read(source);
    final List<FactHandle> handles = new ArrayList<>(facts.size());
    try {
      for (final ExportedFact fact : facts) {
        handles.add(session.insertOwned(fact.type(), fact.payload()));
      }
    } catch (final RuntimeException failed) {
      unwind(session, handles, failed);
      throw failed;
    }
    return List.copyOf(handles);
  }

  /**
   * Retracts what a failed load had already inserted.
   *
   * <p>Newest first, so that the session is unwound in the reverse of the order it was built up --
   * which matters to a listener reading the trace and to nothing else, since no rule has fired
   * between the inserts. A retract that itself throws is attached to the original failure rather
   * than replacing it: the caller is here because the load failed, and the reason it failed is the
   * more useful of the two.
   *
   * @param session the session being unwound
   * @param handles what was inserted, in insertion order
   * @param failure the failure being unwound from, which collects anything that goes wrong here
   */
  private static void unwind(final RuleSession session, final List<FactHandle> handles,
      final RuntimeException failure) {
    for (int index = handles.size() - 1; index >= 0; index--) {
      try {
        session.retract(handles.get(index));
      } catch (final RuntimeException alsoFailed) {
        failure.addSuppressed(alsoFailed);
      }
    }
  }

  /**
   * Reads a document holding one bare payload, with no type beside it.
   *
   * <p>For the caller who already knows the fact type -- a test naming it at the {@code insert}
   * call, an application reading one document per type. The document is the payload itself:
   *
   * <pre>{@code
   * id: "o1"
   * customerId: "c1"
   * total: 12000
   * }</pre>
   *
   * @param source the document
   * @return the payload
   * @throws FactFileException if the document cannot be read or is not a single JSON object
   */
  public static ObjectNode payload(final FactSource source) {
    Objects.requireNonNull(source, "source");
    final SourceIndex index = SourceIndex.of(source);
    final JsonNode tree = parse(source, index);
    if (!tree.isObject()) {
      throw new FactFileException(List.of(FactDiagnostic.at(index.nearest(""),
          "a payload is an object of fields, and this document is " + shapeOf(tree))));
    }
    return (ObjectNode) tree;
  }

  /**
   * Parses the document.
   *
   * @param source the document
   * @param index where everything in it is
   * @return the tree
   * @throws FactFileException if the text is not one well-formed document
   */
  private static JsonNode parse(final FactSource source, final SourceIndex index) {
    final Tail tail = tailOf(source);
    if (tail.kind() == TailKind.ANOTHER) {
      /*
       * Checked here rather than left to Jackson, which does catch it: readTree stops after the
       * first value and FAIL_ON_TRAILING_TOKENS turns the rest into "Trailing token
       * (`JsonToken.START_OBJECT`) found after value", which is an accurate description of a
       * problem the author does not have. They wrote two YAML documents in one file, which is an
       * ordinary thing to write, and the useful reply names that and says where the second one
       * starts. The alternative -- reading every document and concatenating -- was rejected because
       * the source index is built per document, so every location after the first would be wrong.
       */
      throw new FactFileException(List.of(FactDiagnostic.at(
          new SourceLocation(source.name(), tail.location().getLineNr(),
              tail.location().getColumnNr(), ""),
          "a fact document holds one document, and a second one starts here. Put every fact in the"
              + " one list, or read the documents as separate sources")));
    }
    final JsonNode tree;
    try {
      /*
       * FAIL_ON_TRAILING_TOKENS is turned off for exactly one case and left on for the others, and
       * the distinction is the whole of why this is a three-way answer rather than a boolean.
       *
       * Off when the token walk reached the end cleanly: whatever follows the first value is an
       * empty document -- a file ending on `---`, which costs nothing and which authors write --
       * and Jackson would refuse it as a "trailing token" the author cannot act on.
       *
       * ON when the walk could not finish. Trailing junk that does not tokenise (`[…] xyz`, a
       * truncated concatenation) is invisible to the walk, so turning the feature off there would
       * silently accept a malformed file -- which is what the first version of this fix did, and
       * what the table in the review found. The bind pass is the only thing that can report it, so
       * it has to be allowed to look.
       *
       * Through reader() rather than a second mapper: strict duplicate detection is configured on
       * the mapper and is NOT carried by a bare parser off its token factory, which is how an
       * earlier attempt at this quietly stopped rejecting `{ total: 1, total: 2 }`.
       */
      final ObjectReader reader = source.format().mapper().reader();
      tree = (tail.kind() == TailKind.NONE
          ? reader.without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          : reader).readTree(source.text());
    } catch (final JacksonException malformed) {
      throw new FactFileException(List.of(malformed(source, malformed)), malformed);
    }
    if (tree == null || tree.isNull() || tree.isMissingNode()) {
      throw new FactFileException(List.of(FactDiagnostic.at(index.nearest(""),
          "this document is empty; a fact document is a list of { " + TYPE + ", " + PAYLOAD
              + " } entries, and an empty list is written []")));
    }
    return tree;
  }

  /**
   * What follows the document's first value.
   *
   * @param kind whether anything follows it, and whether the walk could tell
   * @param location where the second root value starts, set only for {@link TailKind#ANOTHER}
   */
  private record Tail(TailKind kind, TokenStreamLocation location) {
  }

  /** The three answers {@link #tailOf} gives; the bind in {@link #parse} says what each buys. */
  private enum TailKind {

    /** Nothing follows the first value, or only empty documents do. */
    NONE,

    /** A second document with content in it. */
    ANOTHER,

    /** The walk could not finish, so what follows is unknown and the bind pass must decide. */
    UNREADABLE
  }

  /**
   * Walks past the document's first value to see what follows it.
   *
   * <p>A token walk rather than a parse, so it costs the tokens and not the tree. It is the third
   * walk over a few kilobytes of fixture, after {@link SourceIndex}'s and the bind's, which is the
   * same trade §6.5 already makes for line numbers.
   *
   * <p>A trailing {@code ---} is a second document holding nothing, and telling an author that a
   * second document "starts here" sends them looking for content that is not there. YAML lets a
   * file end on a separator, so an empty document is skipped rather than reported. One that holds
   * something still is.
   *
   * @param source the document
   * @return what follows the first value
   */
  private static Tail tailOf(final FactSource source) {
    try (JsonParser parser = source.format().factory()
        .createParser(ObjectReadContext.empty(), source.text())) {
      if (parser.nextToken() == null) {
        return new Tail(TailKind.NONE, null);
      }
      parser.skipChildren();
      JsonToken next = parser.nextToken();
      while (next == JsonToken.VALUE_NULL) {
        next = parser.nextToken();
      }
      return next == null
          ? new Tail(TailKind.NONE, null)
          : new Tail(TailKind.ANOTHER, parser.currentTokenLocation());
    } catch (final JacksonException unreadable) {
      /*
       * UNREADABLE rather than NONE, and the difference is a defect this reader shipped for one
       * revision. Text that does not tokenise -- `[…] xyz`, a truncated concatenation -- looks
       * exactly like "nothing follows" from here, so answering NONE would tell the bind pass to
       * stop checking for trailing content and the file would load, silently, minus whatever came
       * after the junk. The bind pass is the only thing that can say what is actually wrong with
       * it; this answer is what lets it.
       */
      return new Tail(TailKind.UNREADABLE, null);
    }
  }

  /**
   * Reads one entry of the list.
   *
   * @param entry the entry
   * @param pointer the entry's JSON Pointer, for diagnostics
   * @param index where everything in the document is
   * @param problems collects problems
   * @return the fact, or empty when the entry was rejected
   */
  private static Optional<ExportedFact> readEntry(final JsonNode entry,
      final String pointer, final SourceIndex index, final List<FactDiagnostic> problems) {
    if (!entry.isObject()) {
      problems.add(FactDiagnostic.at(index.nearest(pointer),
          "a fact is { " + TYPE + ": <fact type>, " + PAYLOAD + ": { … } }, and this entry is "
              + shapeOf(entry)));
      return Optional.empty();
    }
    boolean usable = true;
    for (final Map.Entry<String, JsonNode> property : entry.properties()) {
      final String key = property.getKey();
      if (!TYPE.equals(key) && !PAYLOAD.equals(key)) {
        /*
         * Rejected rather than ignored, which is the same call rules.v1.json makes about an unknown
         * key: a document whose extra key is a typo for a real one -- `payloads:`, `Type:` -- is
         * silently a fact with no payload, and a rule set tried against it fails for a reason
         * nothing in the output names.
         */
        problems.add(FactDiagnostic.at(index.nearest(pointer + "/" + key),
            "unknown key '" + key + "'; a fact holds '" + TYPE + "' and '" + PAYLOAD + "'"));
        usable = false;
      }
    }
    final JsonNode type = entry.path(TYPE);
    if (!type.isString() || type.stringValue().isBlank()) {
      problems.add(FactDiagnostic.at(index.nearest(pointer + "/" + TYPE),
          "'" + TYPE + "' is the fact type, a non-empty string" + (type.isMissingNode()
              ? ", and it is missing"
              // Through shapeOf, which names a node rather than printing it. A `type` holding a
              // two-hundred-key object is untrusted input, and a diagnostic that echoes it whole is
              // the defect CLAUDE.md records against RuleFileReader's apiVersion message.
              : ", got " + shapeOf(type))));
      usable = false;
    }
    final JsonNode payload = entry.path(PAYLOAD);
    if (!payload.isObject()) {
      /*
       * An object, never a scalar or a list. §2.2 makes a payload a JSON object whose fields are
       * what a path addresses, and a fact whose payload is a bare value has no field for any
       * constraint to name -- so it would load and then never match anything.
       */
      problems.add(FactDiagnostic.at(index.nearest(pointer + "/" + PAYLOAD),
          "'" + PAYLOAD + "' is an object of fields" + (payload.isMissingNode()
              ? ", and it is missing. A fact with no fields is written " + PAYLOAD + ": {}"
              : ", got " + shapeOf(payload))));
      usable = false;
    }
    return usable
        ? Optional.of(new ExportedFact(type.stringValue(), payload, Origin.ASSERTED))
        : Optional.empty();
  }

  /**
   * Names a node's shape for a diagnostic.
   *
   * @param node the node
   * @return a phrase naming what it is, never its contents
   */
  private static String shapeOf(final JsonNode node) {
    if (node.isObject()) {
      return "an object";
    }
    if (node.isArray()) {
      return "a list";
    }
    return node.isNull() ? "null" : "the value " + node;
  }

  /**
   * Turns a parse failure into a located diagnostic.
   *
   * @param source the document
   * @param failure what Jackson threw
   * @return the diagnostic, located where the parser gave up
   */
  private static FactDiagnostic malformed(final FactSource source, final JacksonException failure) {
    final String message = "this document is not well-formed " + source.format() + ": "
        + failure.getOriginalMessage();
    final TokenStreamLocation location = failure.getLocation();
    return location == null
        ? FactDiagnostic.of(source.name() + ": " + message)
        : FactDiagnostic.at(
            new SourceLocation(source.name(), location.getLineNr(), location.getColumnNr(), ""),
            message);
  }
}
