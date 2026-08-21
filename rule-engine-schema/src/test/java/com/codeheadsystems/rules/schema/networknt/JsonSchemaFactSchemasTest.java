package com.codeheadsystems.rules.schema.networknt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.schema.Presence;
import com.codeheadsystems.rules.schema.SchemaType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * JSON Schema behind §2.3's optional fact-payload binding.
 *
 * <p>Two halves that behave differently on purpose. Validation is the library's job and handles the
 * full specification. Introspection is this class's job, reads the document directly, and answers
 * "unknown" wherever that stops being simple -- so the tests below assert the limit as firmly as
 * they assert the capability.
 */
class JsonSchemaFactSchemasTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode json(final String text) {
    try {
      return JSON.readTree(text);
    } catch (final JacksonException broken) {
      throw new AssertionError("the test fixture is not valid JSON: " + text, broken);
    }
  }

  private static final JsonSchemaFactSchemas ORDERS = JsonSchemaFactSchemas.builder()
      .register("Order", json("""
          {
            "type": "object",
            "required": ["id", "total"],
            "properties": {
              "id":       { "type": "integer" },
              "total":    { "type": "number" },
              "status":   { "type": "string" },
              "customer": {
                "type": "object",
                "required": ["id"],
                "properties": {
                  "id":   { "type": "integer" },
                  "tier": { "type": "string" }
                }
              }
            }
          }"""))
      .build();

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("accepts a payload the schema describes")
    void valid() {
      assertThat(ORDERS.violations("Order", json("""
          {"id": 1, "total": 25000, "status": "PENDING"}"""))).isEmpty();
    }

    @Test
    @DisplayName("rejects a wrong-typed field, naming it")
    void wrongType() {
      assertThat(ORDERS.violations("Order", json("""
          {"id": 1, "total": "lots"}"""))).isNotEmpty();
    }

    @Test
    @DisplayName("rejects a missing required field")
    void missingRequired() {
      assertThat(ORDERS.violations("Order", json("{\"id\": 1}"))).isNotEmpty();
    }

    @Test
    @DisplayName("says nothing about a type nobody registered, which is the zero-setup default")
    void unregisteredType() {
      assertThat(ORDERS.violations("Customer", json("{\"anything\": true}"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("type introspection")
  class Types {

    @Test
    @DisplayName("reads a declared scalar type")
    void scalar() {
      assertThat(ORDERS.typeOf("Order", "total")).contains(SchemaType.NUMBER);
      assertThat(ORDERS.typeOf("Order", "id")).contains(SchemaType.INTEGER);
      assertThat(ORDERS.typeOf("Order", "status")).contains(SchemaType.STRING);
    }

    @Test
    @DisplayName("follows a dotted path into a nested object")
    void nested() {
      assertThat(ORDERS.typeOf("Order", "customer.tier")).contains(SchemaType.STRING);
      assertThat(ORDERS.typeOf("Order", "customer")).contains(SchemaType.OBJECT);
    }

    @Test
    @DisplayName("knows nothing about an undescribed path, rather than guessing")
    void undescribed() {
      assertThat(ORDERS.typeOf("Order", "nope")).isEmpty();
      assertThat(ORDERS.typeOf("Order", "customer.nope")).isEmpty();
      assertThat(ORDERS.typeOf("Unregistered", "total")).isEmpty();
    }
  }

  @Nested
  @DisplayName("presence")
  class Required {

    @Test
    @DisplayName("reports a listed field as required")
    void required() {
      assertThat(ORDERS.presence("Order", "total")).isEqualTo(Presence.REQUIRED);
    }

    @Test
    @DisplayName("a required leaf under an OPTIONAL parent is optional, not required")
    void requiredLeafUnderOptionalParent() {
      /*
       * `customer` is not in the root's required list, and `customer.required` is ["id"]. A payload
       * with no customer at all satisfies the schema and has no customer.id -- so reporting
       * REQUIRED here would be a confident wrong answer, and would suppress a ne-on-optional-path
       * warning that should fire.
       */
      assertThat(ORDERS.presence("Order", "customer.id")).isEqualTo(Presence.OPTIONAL);
    }

    @Test
    @DisplayName("a required leaf under a required parent really is required")
    void requiredLeafUnderRequiredParent() {
      final JsonSchemaFactSchemas schemas = JsonSchemaFactSchemas.builder()
          .register("Order", json("""
              {
                "type": "object",
                "required": ["customer"],
                "properties": {
                  "customer": {
                    "type": "object",
                    "required": ["id"],
                    "properties": { "id": { "type": "integer" } }
                  }
                }
              }"""))
          .build();

      assertThat(schemas.presence("Order", "customer.id")).isEqualTo(Presence.REQUIRED);
    }

    @Test
    @DisplayName("reports a described but unlisted field as optional")
    void optional() {
      assertThat(ORDERS.presence("Order", "status")).isEqualTo(Presence.OPTIONAL);
      assertThat(ORDERS.presence("Order", "customer.tier")).isEqualTo(Presence.OPTIONAL);
    }

    @Test
    @DisplayName("a parent not pinned to objects cannot make its leaf required")
    void unpinnedParentIsUnknown() {
      // `customer` omits "type", so {"customer": 5} satisfies this schema and has no customer.id.
      final JsonSchemaFactSchemas untyped = JsonSchemaFactSchemas.builder()
          .register("T", json("""
              {
                "type": "object",
                "required": ["customer"],
                "properties": {
                  "customer": { "required": ["id"], "properties": { "id": {"type":"integer"} } }
                }
              }"""))
          .build();

      assertThat(untyped.violations("T", json("{\"customer\": 5}")))
          .as("the schema really does accept a non-object here")
          .isEmpty();
      assertThat(untyped.presence("T", "customer.id")).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    @DisplayName("the nullable idiom is not pinned either, since a null parent has no leaf")
    void nullableParentIsUnknown() {
      final JsonSchemaFactSchemas nullable = JsonSchemaFactSchemas.builder()
          .register("T", json("""
              {
                "type": "object",
                "required": ["customer"],
                "properties": {
                  "customer": {
                    "type": ["object", "null"],
                    "required": ["id"],
                    "properties": { "id": {"type":"integer"} }
                  }
                }
              }"""))
          .build();

      assertThat(nullable.violations("T", json("{\"customer\": null}"))).isEmpty();
      assertThat(nullable.presence("T", "customer.id")).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    @DisplayName("an optional ancestor settles it without needing to read further")
    void optionalAncestorSettlesIt() {
      // Not a limitation but a shortcut that is always sound: if `customer` may be absent, so may
      // anything beneath it, whatever the sub-schema says.
      assertThat(ORDERS.presence("Order", "customer.tier")).isEqualTo(Presence.OPTIONAL);
    }

    @Test
    @DisplayName("reports an undescribed field as unknown, which is not the same as optional")
    void undescribed() {
      assertThat(ORDERS.presence("Order", "nope")).isEqualTo(Presence.UNKNOWN);
      assertThat(ORDERS.presence("Unregistered", "total")).isEqualTo(Presence.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("the documented limit")
  class Composition {

    private final JsonSchemaFactSchemas composed = JsonSchemaFactSchemas.builder()
        .register("Thing", json("""
            {
              "type": "object",
              "allOf": [
                { "properties": { "hidden": { "type": "string" } } }
              ],
              "properties": { "plain": { "type": "string" } }
            }"""))
        .build();

    @Test
    @DisplayName("still validates through a composition, because that is the library's job")
    void validationHandlesComposition() {
      assertThat(composed.violations("Thing", json("{\"hidden\": 5}"))).isNotEmpty();
    }

    @Test
    @DisplayName("but declines to introspect through one, rather than reporting a wrong answer")
    void introspectionDeclines() {
      assertThat(composed.typeOf("Thing", "plain"))
          .as("a node carrying allOf defers part of its meaning, so nothing under it is read")
          .isEmpty();
      assertThat(composed.typeOf("Thing", "hidden")).isEmpty();
      assertThat(composed.presence("Thing", "hidden")).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    @DisplayName("declines rather than calling a composed-required field optional")
    void requiredThroughCompositionIsUnknown() {
      /*
       * The dangerous direction. `status` IS required, through allOf. Reading only the local
       * `required` list would answer OPTIONAL and produce a false ne-on-optional-path warning on a
       * field the schema guarantees is present.
       */
      final JsonSchemaFactSchemas viaAllOf = JsonSchemaFactSchemas.builder()
          .register("Thing", json("""
              {
                "type": "object",
                "properties": { "status": { "type": "string" } },
                "allOf": [ { "required": ["status"] } ]
              }"""))
          .build();

      assertThat(viaAllOf.presence("Thing", "status")).isEqualTo(Presence.UNKNOWN);
    }

    @Test
    @DisplayName("declines through a $ref, which it also does not follow")
    void refIsUnknown() {
      // `inner` is required, so the walk actually reaches the $ref rather than settling early on
      // an optional ancestor -- which is what makes this a test of the $ref and not of the parent.
      final JsonSchemaFactSchemas viaRef = JsonSchemaFactSchemas.builder()
          .register("Thing", json("""
              {
                "type": "object",
                "required": ["inner"],
                "properties": { "inner": { "$ref": "#/$defs/leaf" } },
                "$defs": { "leaf": { "type": "object",
                                     "required": ["id"],
                                     "properties": { "id": { "type": "integer" } } } }
              }"""))
          .build();

      assertThat(viaRef.typeOf("Thing", "inner.id")).isEmpty();
      assertThat(viaRef.presence("Thing", "inner.id")).isEqualTo(Presence.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("registration is hermetic")
  class Hermetic {

    @Test
    @DisplayName("an internal $defs reference resolves from the document")
    void internalRefResolves() {
      final JsonSchemaFactSchemas schemas = JsonSchemaFactSchemas.builder()
          .register("T", json("""
              {
                "type": "object",
                "properties": { "inner": { "$ref": "#/$defs/leaf" } },
                "$defs": { "leaf": { "type": "integer" } }
              }"""))
          .build();

      assertThat(schemas.violations("T", json("{\"inner\": \"not-a-number\"}"))).isNotEmpty();
      assertThat(schemas.violations("T", json("{\"inner\": 5}"))).isEmpty();
    }

    @Test
    @DisplayName("a $ref to a URL is rejected at registration rather than fetched")
    void remoteRefRejected() {
      /*
       * The point is not that this URL is unreachable -- it is that registration must not depend on
       * reaching it. Rule sets are compiled at service startup, and a schema naming a host that
       * hangs would hang the startup with it.
       */
      assertThatThrownBy(() -> JsonSchemaFactSchemas.builder()
          .register("T", json("""
              {
                "type": "object",
                "properties": { "inner": { "$ref": "https://example.invalid/leaf.json" } }
              }""")))
          .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("an unresolvable internal reference fails at registration, not at first insert")
    void danglingRefFailsEarly() {
      assertThatThrownBy(() -> JsonSchemaFactSchemas.builder()
          .register("T", json("""
              {"type": "object", "properties": { "a": { "$ref": "#/$defs/missing" } }}""")))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Nested
  @DisplayName("immutability")
  class Frozen {

    @Test
    @DisplayName("the document is copied on registration, so a later edit cannot reach inside")
    void registrationCopies() {
      final JsonNode document = json("""
          {"type": "object", "properties": {"a": {"type": "string"}}}""");
      final JsonSchemaFactSchemas schemas =
          JsonSchemaFactSchemas.builder().register("T", document).build();

      ((tools.jackson.databind.node.ObjectNode) document.get("properties"))
          .set("b", json("{\"type\": \"integer\"}"));

      assertThat(schemas.typeOf("T", "b"))
          .as("§2.3 freezes the registry; a caller keeping the document must not reach inside")
          .isEmpty();
    }
  }
}
