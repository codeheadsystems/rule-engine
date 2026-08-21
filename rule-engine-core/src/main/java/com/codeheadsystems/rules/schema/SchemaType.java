package com.codeheadsystems.rules.schema;

import tools.jackson.databind.JsonNode;

/**
 * A declared JSON type, in the vocabulary JSON Schema uses (spec §2.3).
 *
 * <p>The whole point of knowing this at compile time is §2.3's strongest argument for registering a
 * schema at all: if {@code Order.total} is a number, then {@code { gt: "expensive" }} is a mistake
 * the compiler can catch, rather than a rule that silently never matches -- which is a far harder
 * bug to find, because nothing about it looks wrong.
 */
public enum SchemaType {

  /** A JSON string. */
  STRING,

  /** Any JSON number. */
  NUMBER,

  /** A JSON number with no fractional part. */
  INTEGER,

  /** A JSON boolean. */
  BOOLEAN,

  /** A JSON object. */
  OBJECT,

  /** A JSON array. */
  ARRAY,

  /** An explicit JSON null, which §2.6.1 keeps distinct from an absent field. */
  NULL;

  /**
   * Whether a literal is in the same §2.6.1 type-compatibility class as this declared type.
   *
   * <p><strong>This is a question about §2.6.1's classes, not about JSON Schema's types</strong>,
   * and conflating the two produces false compile errors. §2.6.1 lists exactly five classes --
   * {@code {number}}, {@code {string}}, {@code {boolean}}, {@code {array}}, {@code {object}} --
   * and says "comparison is defined <em>within</em> a class only". There is no integer/number split
   * in that relation. A field declared {@code "type": "integer"} compared against {@code 99.5} is
   * an ordinary within-class comparison that a value of {@code 100} satisfies; rejecting it would
   * break a working rule for a distinction the engine's semantics do not draw.
   *
   * <p>An explicit null is in every class. §2.6.1 keeps null distinct from absent and defines
   * {@code eq: null} as a meaningful test, so rejecting it against a declared string would reject a
   * rule that is both correct and useful.
   *
   * @param literal the literal a rule compares against
   * @return true when the two could ever be compared to {@code true}
   */
  public boolean comparableWith(final JsonNode literal) {
    if (literal == null || literal.isNull()) {
      return true;
    }
    return switch (this) {
      case STRING -> literal.isString();
      case NUMBER, INTEGER -> literal.isNumber();
      case BOOLEAN -> literal.isBoolean();
      case OBJECT -> literal.isObject();
      case ARRAY -> literal.isArray();
      // A declared null has no class to share: §2.6.1's classes are the five above, and a field
      // that can only ever hold null is compared with isNull, not with a literal.
      case NULL -> false;
    };
  }

  /**
   * How to name this type's comparison class in a diagnostic.
   *
   * <p>§2.6.1 lists exactly five classes -- {@code {number}}, {@code {string}}, {@code {boolean}},
   * {@code {array}}, {@code {object}} -- and {@link #NULL} is not among them, so it does not get an
   * invented sixth. A field declared {@code "type": "null"} holds no comparable value at all, and
   * the diagnostic says that rather than naming a class the spec does not define.
   *
   * @return the class as §2.6.1 writes it, or a description for the one type that has none
   */
  public String compatibilityClass() {
    return switch (this) {
      case STRING -> "{string}";
      case NUMBER, INTEGER -> "{number}";
      case BOOLEAN -> "{boolean}";
      case OBJECT -> "{object}";
      case ARRAY -> "{array}";
      case NULL -> "no comparison class at all -- §2.6.1 defines comparison over"
          + " {number}, {string}, {boolean}, {array} and {object}";
    };
  }

  /**
   * The type a JSON Schema {@code "type"} keyword names.
   *
   * @param keyword the keyword's value, e.g. {@code "integer"}
   * @return the matching type, or null when it is not one this engine models
   */
  public static SchemaType forKeyword(final String keyword) {
    return switch (keyword) {
      case "string" -> STRING;
      case "number" -> NUMBER;
      case "integer" -> INTEGER;
      case "boolean" -> BOOLEAN;
      case "object" -> OBJECT;
      case "array" -> ARRAY;
      case "null" -> NULL;
      default -> null;
    };
  }
}
