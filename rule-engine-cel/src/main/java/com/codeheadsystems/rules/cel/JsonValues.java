package com.codeheadsystems.rules.cel;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import tools.jackson.databind.node.ObjectNode;
import com.google.common.primitives.UnsignedLong;
import com.google.protobuf.NullValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves values between the engine's JSON model and CEL's.
 *
 * <p><strong>Numbers are the interesting part, and the lossy one.</strong> §2.6.2 canonicalises
 * numerics through {@code BigDecimal} precisely because JSON number encoding is source-dependent
 * and {@code 10000}, {@code 10000.0} and {@code 1e4} must mean one value. CEL has no such type: it
 * offers {@code int} (64-bit) and {@code double}, so a value handed to an expression becomes one of
 * those.
 *
 * <p>The practical consequence, stated rather than hidden: an integral number within {@code long}
 * range is exact, and anything else goes through {@code double}, so an expression comparing very
 * large or high-precision decimals can disagree with the same comparison written as an operator
 * map. That is one more reason §6.3 keeps operator maps the default and §6.4 calls this an escape
 * hatch -- and it is why arithmetic on money is better done before the fact reaches the engine.
 */
final class JsonValues {

  private JsonValues() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Converts a fact payload into the value model CEL evaluates over.
   *
   * @param node the payload or field value
   * @return the equivalent CEL value. An <em>absent</em> value becomes Java null, which the
   *     evaluator reads as an unknown -- correct, since the alias genuinely is not there. An
   *     <em>explicit</em> null becomes CEL's own null, because §2.6.1 makes it a value: passing
   *     Java null for it made {@code o.closedAt == null} produce an unknown rather than true
   */
  static Object toCel(final JsonNode node) {
    if (node == null || node.isMissingNode()) {
      return null;
    }
    if (node.isNull()) {
      /*
       * CEL's own null, not Java's. A Java null inside the bindings is read by the evaluator as an
       * UNKNOWN, which is a different thing entirely: an unknown propagates through the expression
       * and comes out the far end as a CelUnknownSet, so `o.total > 100` against a fact whose total
       * is an explicit null produced neither true nor false but a value that then failed a boolean
       * check. §2.6.1 makes null a first-class value -- {eq: null} matches it -- so it has to
       * arrive here as one.
       */
      return NullValue.NULL_VALUE;
    }
    if (node.isString()) {
      return node.stringValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isIntegralNumber() && node.canConvertToLong()) {
      return node.longValue();
    }
    if (node.isNumber()) {
      return node.doubleValue();
    }
    if (node.isArray()) {
      final List<Object> values = new ArrayList<>(node.size());
      node.forEach(element -> values.add(toCel(element)));
      return values;
    }
    if (node.isObject()) {
      // Insertion-ordered so that anything derived from iteration order stays stable run to run,
      // which is §7.3's concern applied at the boundary rather than only inside the engine.
      final Map<String, Object> fields = new LinkedHashMap<>();
      node.properties().forEach(field -> fields.put(field.getKey(), toCel(field.getValue())));
      return fields;
    }
    return node.asString();
  }

  /**
   * Converts a value CEL produced back into the engine's model.
   *
   * @param value the result of an evaluation
   * @return the equivalent JSON value; an explicit null for a CEL null, since §2.6.1 keeps that
   *     distinct from absent and an expression that computed null did compute something
   */
  static JsonNode toJson(final Object value) {
    final JsonNodeFactory nodes = JsonNodeFactory.instance;
    return switch (value) {
      case null -> nodes.nullNode();
      /*
       * Two null representations, both real. A null that came in through toCel comes back as
       * protobuf's; one written literally in an expression comes back as CEL's own. Matching only
       * the first is how `$expr: "null"` used to write the string "NULL_VALUE" into a fact.
       */
      case com.google.protobuf.NullValue ignored -> nodes.nullNode();
      case dev.cel.common.values.NullValue ignored -> nodes.nullNode();
      case UnsignedLong unsigned -> nodes.numberNode(unsigned.bigIntegerValue());
      case JsonNode already -> already;
      case String text -> nodes.stringNode(text);
      case Boolean flag -> nodes.booleanNode(flag);
      case Long number -> nodes.numberNode(number);
      case Integer number -> nodes.numberNode(number.longValue());
      case Double number -> nodes.numberNode(number);
      case Float number -> nodes.numberNode(number.doubleValue());
      case BigDecimal number -> nodes.numberNode(number);
      case Iterable<?> values -> {
        final ArrayNode array = nodes.arrayNode();
        values.forEach(element -> array.add(toJson(element)));
        yield array;
      }
      case Map<?, ?> fields -> {
        final ObjectNode object = nodes.objectNode();
        fields.forEach((key, element) -> {
          /*
           * A JSON object is keyed by string and a CEL map is not, so two distinct keys can render
           * the same -- {1: 'a', '1': 'b'} would silently become one field. Every other way a value
           * could vanish in this method now throws; this was the last one that did not.
           */
          final String name = String.valueOf(key);
          if (object.has(name)) {
            throw new ExpressionEvaluationException(
                "an expression produced a map whose keys collide once written as JSON: '" + name
                    + "' appears twice. JSON objects are keyed by string; convert the keys in the"
                    + " expression so that each is distinct");
          }
          object.set(name, toJson(element));
        });
        yield object;
      }
      /*
       * Anything else is refused rather than stringified. The previous default -- textNode of
       * toString() -- turned every unhandled CEL value into plausible-looking data: a CEL null
       * became the string "NULL_VALUE", a uint became a quoted number, and an unknown became
       * "CelUnknownSet{...}". This is the §4.6 commit path, so that value would be inserted,
       * indexed and audited as if somebody had written it. §6.4's whole argument is that an
       * expression's failures are visible; a corrupted fact is the opposite of visible.
       */
      default -> throw new ExpressionEvaluationException(
          "an expression produced a " + value.getClass().getSimpleName()
              + " (" + value + "), which has no JSON equivalent. Convert it in the expression --"
              + " string(...), int(...), double(...) -- so that what reaches the fact is a value"
              + " this engine can store");
    };
  }
}
