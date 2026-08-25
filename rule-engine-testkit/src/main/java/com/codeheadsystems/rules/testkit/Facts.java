package com.codeheadsystems.rules.testkit;

import com.codeheadsystems.rules.dsl.FactFileException;
import com.codeheadsystems.rules.dsl.FactFiles;
import com.codeheadsystems.rules.dsl.FactSource;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds fact payloads.
 *
 * <p>Nothing here is engine machinery -- it exists so that a test reads as the rule it is about
 * rather than as Jackson boilerplate.
 */
public final class Facts {

  private Facts() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Parses a payload from JSON text.
   *
   * @param json the payload, typically a text block
   * @return the parsed object node
   * @throws IllegalArgumentException if the text is not a single JSON object
   */
  public static ObjectNode json(final String json) {
    return payload(FactSource.json("payload.json", json));
  }

  /**
   * Parses a payload from YAML text.
   *
   * <p>The same fact, in the serialization the rules beside it are written in. §6.1 settles that
   * question once for the whole project -- one object model, two serializations -- and a fixture is
   * where the difference is felt most: a nested payload written as YAML is the shape it describes,
   * where the same thing in JSON is a wall of quotes and braces inside a Java text block.
   *
   * @param yaml the payload, typically a text block
   * @return the parsed object node
   * @throws IllegalArgumentException if the text is not a single YAML mapping
   */
  public static ObjectNode yaml(final String yaml) {
    return payload(FactSource.yaml("payload.yaml", yaml));
  }

  /**
   * Parses one payload document.
   *
   * <p>Through {@code -dsl} rather than a mapper of this class's own, which is what it used to
   * hold. There is one place in this project where a Jackson mapper is configured, and a second one
   * here meant a fixture and a rule file disagreeing about a repeated key: {@code { total: 1,
   * total: 2 }} is rejected in a rule file and was silently the second one in a test fixture --
   * where it reads correctly in review and makes the rule under test look wrong.
   *
   * @param source the document
   * @return the payload
   * @throws IllegalArgumentException if the text is not a single object
   */
  private static ObjectNode payload(final FactSource source) {
    try {
      return FactFiles.payload(source);
    } catch (final FactFileException invalid) {
      /*
       * Re-thrown as an IllegalArgumentException, which is what this method threw before it had a
       * reader behind it and is what a fixture builder should throw: the caller is a test author
       * who passed a bad literal, not an application handling a document somebody else wrote. The
       * located message is kept, so nothing is lost but the type.
       */
      throw new IllegalArgumentException(invalid.getMessage(), invalid);
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
  private static tools.jackson.databind.JsonNode value(final Object raw) {
    return switch (raw) {
      case null -> JsonNodeFactory.instance.nullNode();
      case tools.jackson.databind.JsonNode node -> node;
      case String text -> JsonNodeFactory.instance.stringNode(text);
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
