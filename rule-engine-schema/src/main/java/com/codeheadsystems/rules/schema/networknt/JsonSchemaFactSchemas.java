package com.codeheadsystems.rules.schema.networknt;

import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.Presence;
import com.codeheadsystems.rules.schema.SchemaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fact-payload schemas read from JSON Schema documents (spec §2.3).
 *
 * <p>Built once and frozen, per §2.3 and per {@link FactSchemas}'s contract: this object is
 * referenced by the compiled rule set and therefore by every running session, so it takes its
 * schemas at construction and never accepts another.
 *
 * <p><strong>Introspection reads the schema document directly, and stops where that stops being
 * simple.</strong> {@link #typeOf} and {@link #presence} walk {@code properties}, {@code required}
 * and {@code type} down the requested path. They do not resolve {@code $ref}, and they do not try
 * to reconcile {@code allOf}, {@code anyOf} or {@code oneOf} -- a path described only through a
 * composition answers empty and {@link Presence#UNKNOWN}.
 *
 * <p>That is a real limitation and it is the right one. §6.5's compile-time checks exist to catch a
 * literal that could never match; answering "I don't know" costs exactly one unmade check, which is
 * what a caller had before registering any schema at all. Guessing wrong would instead reject a
 * correct rule at compile time, and a false compile error in a rule file is far more damaging than
 * a missing warning. {@link #violations} has no such limit -- validation is the library's job and
 * it handles the full specification.
 */
public final class JsonSchemaFactSchemas implements FactSchemas {

  private final Map<String, Schema> byFactType;
  private final Map<String, JsonNode> documentsByFactType;

  private JsonSchemaFactSchemas(final Map<String, Schema> byFactType,
      final Map<String, JsonNode> documentsByFactType) {
    this.byFactType = Map.copyOf(byFactType);
    this.documentsByFactType = Map.copyOf(documentsByFactType);
  }

  /**
   * A fresh builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public List<String> violations(final String factType, final JsonNode payload) {
    final Schema schema = byFactType.get(factType);
    if (schema == null) {
      return List.of();
    }
    final List<Error> errors = schema.validate(payload);
    if (errors.isEmpty()) {
      return List.of();
    }
    final List<String> messages = new ArrayList<>(errors.size());
    errors.forEach(error -> messages.add(error.getMessage()));
    return List.copyOf(messages);
  }

  @Override
  public Optional<SchemaType> typeOf(final String factType, final String dottedPath) {
    return describe(factType, dottedPath)
        .map(node -> node.path("type"))
        .filter(JsonNode::isTextual)
        .map(node -> SchemaType.forKeyword(node.textValue()));
  }

  @Override
  public Presence presence(final String factType, final String dottedPath) {
    final JsonNode document = documentsByFactType.get(factType);
    if (document == null || dottedPath == null || dottedPath.isEmpty()) {
      return Presence.UNKNOWN;
    }
    final String[] segments = dottedPath.split("\\.", -1);
    JsonNode parent = document;
    for (int depth = 0; depth < segments.length; depth++) {
      if (composed(parent)) {
        // A node carrying allOf/anyOf/oneOf/$ref may require this property somewhere this class
        // does not read. Saying UNKNOWN is the documented contract; reading `required` here would
        // report a required field as optional and produce a false ne-on-optional-path warning.
        return Presence.UNKNOWN;
      }
      final JsonNode child = parent.path("properties").path(segments[depth]);
      if (!child.isObject()) {
        // The schema does not describe this path at all, which says nothing about whether it is
        // required -- and is not the same as saying it is optional.
        return Presence.UNKNOWN;
      }
      if (!pinnedToObjects(parent)) {
        /*
         * `required` and `properties` constrain object instances and nothing else, so they say
         * nothing unless the node is pinned to objects. Two shapes make this bite, and both are
         * ordinary hand-written schema: a nested object that simply omits "type", and the standard
         * nullable idiom "type": ["object", "null"]. In each case a payload can satisfy the schema
         * with the parent holding a non-object -- 5, or null -- and the leaf therefore absent.
         */
        return Presence.UNKNOWN;
      }
      if (!requires(parent, segments[depth])) {
        /*
         * The first ancestor that is not required settles it. A leaf listed in its own parent's
         * `required` is only reachable when that parent is present, so "customer.id is required"
         * is false whenever `customer` itself is optional: a payload with no customer at all
         * satisfies the schema and has no customer.id. Reporting REQUIRED there would be a
         * confident wrong answer, which is the one thing this class must not produce.
         */
        return Presence.OPTIONAL;
      }
      parent = child;
    }
    return Presence.REQUIRED;
  }

  /**
   * Whether a node's {@code required} list names a property.
   *
   * @param node the enclosing schema node
   * @param property the property name
   * @return true when the node requires it
   */
  private static boolean requires(final JsonNode node, final String property) {
    final JsonNode required = node.path("required");
    if (!required.isArray()) {
      // JSON Schema's default is that nothing is required, so a described property under a node
      // with no `required` list is optional. Sound only because composed(...) already returned.
      return false;
    }
    for (final JsonNode name : required) {
      if (name.isTextual() && name.textValue().equals(property)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a schema node constrains its instance to be an object.
   *
   * <p>Only then do {@code required} and {@code properties} say anything: JSON Schema applies both
   * to object instances only, so a node that permits a number or a null permits an instance with no
   * properties at all. {@code "type": ["object", "null"]} is deliberately <em>not</em> pinned --
   * that is the standard nullable idiom, and a null parent has no leaf beneath it.
   *
   * @param node the schema node
   * @return true when the node admits objects and nothing else
   */
  private static boolean pinnedToObjects(final JsonNode node) {
    final JsonNode type = node.path("type");
    return type.isTextual() && "object".equals(type.textValue());
  }

  /**
   * Whether a schema node defers part of its meaning to a construct this class does not read.
   *
   * @param node the schema node
   * @return true when it carries a composition or a reference
   */
  private static boolean composed(final JsonNode node) {
    return node.has("allOf") || node.has("anyOf") || node.has("oneOf")
        || node.has("$ref") || node.has("not") || node.has("if");
  }

  /**
   * The sub-schema describing a path, if it can be read directly.
   *
   * @param factType the fact type
   * @param dottedPath the path in DSL form
   * @return the sub-schema, or empty when it is absent or only described through composition
   */
  private Optional<JsonNode> describe(final String factType, final String dottedPath) {
    final JsonNode document = documentsByFactType.get(factType);
    if (document == null || dottedPath == null || dottedPath.isEmpty()) {
      return Optional.empty();
    }
    JsonNode node = document;
    for (final String segment : dottedPath.split("\\.", -1)) {
      if (composed(node)) {
        // Same reason as presence(): a composed node may declare this property's type somewhere
        // this class does not follow, and a wrong type here is a false compile error.
        return Optional.empty();
      }
      node = node.path("properties").path(segment);
      if (!node.isObject()) {
        return Optional.empty();
      }
    }
    return composed(node) ? Optional.empty() : Optional.of(node);
  }

  /**
   * Builds an immutable registry.
   *
   * <p>§2.3 is explicit that the built object must be frozen, and gives the reason: a mutable
   * {@code register} on an object every session reads would be a race with no barrier and no
   * defined ordering.
   */
  public static final class Builder {

    /**
     * A registry that resolves references from the document and from nowhere else.
     *
     * <p>The default registry carries a loader that fetches an unrecognised {@code $ref} over the
     * network, with no timeout. That is a poor thing to have on the path that builds a rule set:
     * rule compilation happens at service startup, and a schema naming a host that hangs would hang
     * the startup with it -- turning a configuration mistake into an outage, which is the shape of
     * problem §2.6.3 rejects RE2-less regexes for. Clearing the loaders makes registration
     * hermetic, so an unresolvable reference fails immediately and locally.
     *
     * <p>Consequence worth knowing: a schema whose {@code $ref} points at a URL is rejected at
     * {@link #register}, not fetched. Inline the referenced schema, or use an internal
     * {@code #/$defs/...} pointer, which resolves from the document itself.
     */
    private static final com.networknt.schema.SchemaRegistry HERMETIC =
        com.networknt.schema.SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            registry -> registry.resourceLoaders(loaders -> loaders.values(List::clear)));

    private final Map<String, Schema> compiled = new LinkedHashMap<>();
    private final Map<String, JsonNode> documents = new LinkedHashMap<>();

    private Builder() {
      // Reached through builder().
    }

    /**
     * Registers one fact type's schema.
     *
     * <p><strong>The schema is fully resolved here, not on first use.</strong> That is deliberate:
     * a networknt {@code Schema} otherwise resolves {@code $ref} targets lazily behind a plain
     * non-volatile flag, and this object is read by every session with no synchronisation, so the
     * lazy work would be a data race waiting for two concurrent first inserts. Resolving now also
     * moves a broken reference from a surprise at insert time to a failure at build time, where it
     * belongs.
     *
     * <p>Resolution is {@linkplain #HERMETIC hermetic}: references are read from the document, and
     * a {@code $ref} to a URL is rejected rather than fetched.
     *
     * @param factType the fact type
     * @param schemaDocument the JSON Schema document
     * @return this builder
     * @throws com.networknt.schema.SchemaException if the document is not a valid schema, or
     *     carries a {@code $ref} that cannot be resolved from within it
     */
    public Builder register(final String factType, final JsonNode schemaDocument) {
      Objects.requireNonNull(factType, "factType");
      Objects.requireNonNull(schemaDocument, "schemaDocument");
      final Schema schema = HERMETIC.getSchema(schemaDocument.toString());
      /*
       * Forced now, rather than left to the first validate(). This registry is frozen into the
       * compiled rule set and read by every session, one per virtual thread, with no
       * synchronisation -- so anything it holds has to be safe for concurrent use. A networknt
       * Schema resolves $ref targets lazily and guards that work with a plain non-volatile boolean,
       * which is a data race waiting for the first rule set whose schema uses a $ref and whose
       * first two inserts land on different threads. Building everything here puts it all behind
       * this object's final fields, where safe publication covers it.
       */
      schema.initializeValidators();
      compiled.put(factType, schema);
      documents.put(factType, schemaDocument.deepCopy());
      return this;
    }

    /**
     * Builds the registry.
     *
     * @return the immutable registry
     */
    public JsonSchemaFactSchemas build() {
      return new JsonSchemaFactSchemas(compiled, documents);
    }
  }
}
