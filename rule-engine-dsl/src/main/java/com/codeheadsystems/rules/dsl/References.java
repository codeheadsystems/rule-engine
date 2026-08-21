package com.codeheadsystems.rules.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code $ref} syntax and its escape (spec §6.2.3).
 *
 * <p>§6.2.3 chose a structural reference -- {@code { $ref: o.customerId }} -- over a string sigil
 * such as {@code "$o.customerId"}, and was careful to say what that does and does not buy. It
 * removes the case a sigil cannot express, a value genuinely beginning with {@code $}. It does not
 * remove ambiguity altogether: §2.6.1 compares objects structurally, so a literal object that
 * happens to carry a {@code $ref} key becomes the unexpressible case instead. The escape is what
 * closes that, and the strictness is what keeps a typo from becoming a rule that never matches.
 *
 * <p>Three rules, applied everywhere a value may appear:
 *
 * <ul>
 *   <li>In <strong>operand position</strong>, an object carrying {@code $ref} is a reference.
 *   <li>A key beginning with {@code $$} is a literal key with one {@code $} stripped, so
 *       {@code $$ref} writes a literal {@code $ref}. §6.2.3 names only that one case; the rule here
 *       is the general form of it, which costs nothing and leaves no key unexpressible.
 *   <li>Any other {@code $}-prefixed key is <strong>rejected</strong>, at any depth. §6.2.3 asks
 *       for exactly this -- reject rather than pass through -- because {@code $}-prefixed keys are
 *       conventionally reserved and silently treating an unrecognised one as an ordinary field is
 *       how {@code $reff} becomes a rule nobody can explain.
 * </ul>
 */
final class References {

  /** The reference key. */
  static final String REF = "$ref";

  /** The expression key of §6.4, which sits in the same operand position as {@link #REF}. */
  static final String EXPR = "$expr";

  private References() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * One end of a cross-fact reference.
   *
   * @param alias the alias being referenced
   * @param field the dotted field path on that alias's fact
   */
  record Ref(String alias, String field) {}

  /**
   * Whether a value is in the shape of a reference.
   *
   * <p>Shape only: a malformed operand is still a reference, and is reported as a malformed one
   * rather than silently demoted to a literal object.
   *
   * @param operand the value
   * @return true when it is an object carrying a {@code $ref} key
   */
  static boolean isRef(final JsonNode operand) {
    return operand != null && operand.isObject() && operand.has(REF);
  }

  /**
   * Whether a value is in the shape of a §6.4 expression.
   *
   * @param operand the value
   * @return true when it is an object carrying an {@code $expr} key
   */
  static boolean isExpression(final JsonNode operand) {
    return operand != null && operand.isObject() && operand.has(EXPR);
  }

  /**
   * Reads an expression operand.
   *
   * @param operand the value, already known to be {@linkplain #isExpression expression-shaped}
   * @param pointer the operand's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the expression source, or empty when it was malformed
   */
  static Optional<String> readExpression(final JsonNode operand, final String pointer,
      final Diagnostics diagnostics) {
    if (operand.size() > 1) {
      diagnostics.error(DslError.MALFORMED_OPERAND, pointer,
          "an $expr is the whole operand; this one also carries " + otherKeys(operand, EXPR));
      return Optional.empty();
    }
    final JsonNode source = operand.get(EXPR);
    if (!source.isTextual()) {
      diagnostics.error(DslError.MALFORMED_OPERAND, pointer,
          "an $expr holds expression source as a string, got "
              + source.getNodeType().toString().toLowerCase(Locale.ROOT));
      return Optional.empty();
    }
    return Optional.of(source.textValue());
  }

  /**
   * Reads a reference operand.
   *
   * @param operand the value, already known to be {@linkplain #isRef reference-shaped}
   * @param pointer the operand's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the reference, or empty when it was malformed
   */
  static Optional<Ref> readRef(final JsonNode operand, final String pointer,
      final Diagnostics diagnostics) {
    if (operand.size() > 1) {
      diagnostics.error(DslError.MALFORMED_REFERENCE, pointer,
          "a $ref is the whole operand; this one also carries " + otherKeys(operand, REF)
              + ". §6.2.3 reserves that shape for later use, and this engine does not implement it");
      return Optional.empty();
    }
    final JsonNode target = operand.get(REF);
    if (!target.isTextual()) {
      diagnostics.error(DslError.MALFORMED_REFERENCE, pointer,
          "a $ref names 'alias.field' as a string, got "
              + target.getNodeType().toString().toLowerCase(Locale.ROOT));
      return Optional.empty();
    }
    final String text = target.textValue();
    final int dot = text.indexOf('.');
    if (dot < 1 || dot == text.length() - 1) {
      diagnostics.error(DslError.MALFORMED_REFERENCE, pointer,
          "a $ref must be 'alias.field', got '" + text + "'");
      return Optional.empty();
    }
    return Optional.of(new Ref(text.substring(0, dot), text.substring(dot + 1)));
  }

  /**
   * Reads a key written by the author -- a {@code where} field name, a payload or argument name --
   * applying the same {@code $} rules the operand positions get.
   *
   * <p>Needed because those positions do not pass through {@link #readLiteral}, and the rule this
   * class states is "at any depth", not "in operand position". The gap it closes is the most
   * natural version of §6.2.3's typo, not an exotic one:
   *
   * <pre>{@code
   * payload:
   *   $ref: o.id        # the wrapping field name was forgotten
   * }</pre>
   *
   * <p>Without this, that binds an event field literally named {@code $ref} carrying the string
   * {@code "o.id"} -- a rule that quietly does something other than what it looks like, which is
   * the failure §6.2.3 asks to be rejected rather than passed through.
   *
   * @param key the key as written
   * @param pointer the key's JSON Pointer, for diagnostics
   * @param what the key's role, for the diagnostic's wording
   * @param diagnostics collects problems
   * @return the key with escapes resolved, or empty when it was rejected
   */
  static Optional<String> readKey(final String key, final String pointer, final String what,
      final Diagnostics diagnostics) {
    if (key.startsWith("$$")) {
      return Optional.of(key.substring(1));
    }
    if (REF.equals(key)) {
      diagnostics.error(DslError.MALFORMED_REFERENCE, pointer,
          "a $ref is an operand, not a " + what + ". Did you mean to wrap it, as"
              + " 'name: { $ref: alias.field }'? Write $$ref for a literal " + what
              + " named '$ref'");
      return Optional.empty();
    }
    if (key.startsWith("$")) {
      diagnostics.error(DslError.UNKNOWN_DOLLAR_KEY, pointer,
          "'" + key + "' is not a key this DSL recognises, and \u00a76.2.3 rejects unrecognised"
              + " $-prefixed keys rather than passing them through."
              + " Write '$" + key + "' if you meant a literal " + what + " named '" + key + "'");
      return Optional.empty();
    }
    return Optional.of(key);
  }

  /**
   * Reads a literal value, unescaping {@code $$}-prefixed keys and rejecting unrecognised
   * {@code $}-prefixed ones at any depth.
   *
   * @param literal the value
   * @param pointer the value's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the literal with escapes resolved, or empty when it carried a key that was rejected
   */
  static Optional<JsonNode> readLiteral(final JsonNode literal, final String pointer,
      final Diagnostics diagnostics) {
    final int before = diagnostics.count();
    final JsonNode resolved = unescape(literal, pointer, diagnostics);
    return diagnostics.count() == before ? Optional.of(resolved) : Optional.empty();
  }

  /**
   * Rewrites escapes and reports rejected keys, depth first.
   *
   * <p>Builds new nodes rather than editing the ones passed in. The document tree is also what
   * {@link SourceIndex} was walked against and what the schema validated, and quietly rewriting it
   * under them would make a diagnostic describe a document nobody wrote.
   *
   * @param value the value to rewrite
   * @param pointer the value's JSON Pointer
   * @param diagnostics collects problems
   * @return the rewritten value
   */
  private static JsonNode unescape(final JsonNode value, final String pointer,
      final Diagnostics diagnostics) {
    if (value.isArray()) {
      final ArrayNode rewritten = JsonNodeFactory.instance.arrayNode(value.size());
      for (int element = 0; element < value.size(); element++) {
        rewritten.add(unescape(value.get(element), pointer + "/" + element, diagnostics));
      }
      return rewritten;
    }
    if (!value.isObject()) {
      return value;
    }
    final ObjectNode rewritten = JsonNodeFactory.instance.objectNode();
    for (final Map.Entry<String, JsonNode> field : value.properties()) {
      final String key = field.getKey();
      final String childPointer = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
      if (key.startsWith("$$")) {
        rewritten.set(key.substring(1), unescape(field.getValue(), childPointer, diagnostics));
      } else if (EXPR.equals(key)) {
        diagnostics.error(DslError.MALFORMED_OPERAND, childPointer,
            "an $expr is only meaningful as a whole operand, not nested inside a literal."
                + " Write $$expr if you meant a literal field named '$expr'");
      } else if (REF.equals(key)) {
        diagnostics.error(DslError.MALFORMED_REFERENCE, childPointer,
            "a $ref is only meaningful as a whole operand, not nested inside a literal."
                + " Write $$ref if you meant a literal field named '$ref'");
      } else if (key.startsWith("$")) {
        diagnostics.error(DslError.UNKNOWN_DOLLAR_KEY, childPointer,
            "'" + key + "' is not a key this DSL recognises, and \u00a76.2.3 rejects unrecognised"
                + " $-prefixed keys rather than passing them through."
                + " Write '$" + key + "' if you meant a literal field named '" + key + "'");
      } else {
        rewritten.set(key, unescape(field.getValue(), childPointer, diagnostics));
      }
    }
    return rewritten;
  }

  /**
   * Names the keys accompanying a {@code $ref}, for the diagnostic that rejects them.
   *
   * @param operand the reference-shaped operand
   * @param own the key that belongs there, and so is not reported
   * @return the other keys, quoted and comma-separated
   */
  private static String otherKeys(final JsonNode operand, final String own) {
    return operand.properties().stream()
        .map(Map.Entry::getKey)
        .filter(key -> !own.equals(key))
        .map(key -> "'" + key + "'")
        .collect(Collectors.joining(", "));
  }
}
