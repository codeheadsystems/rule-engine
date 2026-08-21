package com.codeheadsystems.rules.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds fact payloads.
 *
 * <p>Nothing here is engine machinery -- it exists so that a test reads as the rule it is about
 * rather than as Jackson boilerplate.
 */
public final class Facts {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Facts() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Parses a payload from JSON text.
   *
   * @param json the payload, typically a text block
   * @return the parsed object node
   * @throws IllegalArgumentException if the text is not a JSON object
   */
  public static ObjectNode json(final String json) {
    try {
      return (ObjectNode) MAPPER.readTree(json);
    } catch (final JsonProcessingException | ClassCastException invalid) {
      throw new IllegalArgumentException("not a JSON object: " + json, invalid);
    }
  }

  /**
   * Builds a payload from alternating keys and values.
   *
   * <p>Values may be {@code String}, {@code Integer}, {@code Long}, {@code Double},
   * {@code BigDecimal}, {@code Boolean}, {@code null} (which becomes an explicit JSON null, not an
   * absent field -- the distinction §2.6.1 is built around), or an existing {@code JsonNode}.
   *
   * @param keysAndValues alternating keys and values
   * @return the payload
   * @throws IllegalArgumentException if the argument count is odd or a value type is unsupported
   */
  public static ObjectNode obj(final Object... keysAndValues) {
    if (keysAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("obj() takes alternating keys and values");
    }
    final ObjectNode node = JsonNodeFactory.instance.objectNode();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      node.set(String.valueOf(keysAndValues[index]), value(keysAndValues[index + 1]));
    }
    return node;
  }

  /**
   * Builds an array node from values.
   *
   * @param values the elements
   * @return the array node
   */
  public static ArrayNode array(final Object... values) {
    final ArrayNode node = JsonNodeFactory.instance.arrayNode();
    for (final Object element : values) {
      node.add(value(element));
    }
    return node;
  }

  /**
   * Converts one Java value into a JSON node.
   *
   * @param raw the value
   * @return the node
   * @throws IllegalArgumentException if the type is unsupported
   */
  private static com.fasterxml.jackson.databind.JsonNode value(final Object raw) {
    return switch (raw) {
      case null -> JsonNodeFactory.instance.nullNode();
      case com.fasterxml.jackson.databind.JsonNode node -> node;
      case String text -> JsonNodeFactory.instance.textNode(text);
      case Integer number -> JsonNodeFactory.instance.numberNode(number);
      case Long number -> JsonNodeFactory.instance.numberNode(number);
      case Double number -> JsonNodeFactory.instance.numberNode(number);
      case java.math.BigDecimal number -> JsonNodeFactory.instance.numberNode(number);
      case Boolean flag -> JsonNodeFactory.instance.booleanNode(flag);
      default -> throw new IllegalArgumentException(
          "unsupported value type " + raw.getClass().getName()
              + "; pass a JsonNode if you need something exotic");
    };
  }
}
