package com.codeheadsystems.rules.dsl;

import tools.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One {@code then} action as written in a file (spec §6.2.2).
 *
 * <p>One flat record across all five verbs, rather than a polymorphic hierarchy keyed on
 * {@code action}. The division of labour is what decides this: the rule-file schema already states
 * which keys each verb accepts, with {@code additionalProperties: false} per branch, and structure
 * is precisely what a JSON Schema is good at. Duplicating that as a Jackson subtype registry would
 * put the same rule in two places and answer violations with Jackson's wording rather than a
 * message naming the verb. {@link Actions} reads the fields its verb uses and nothing else.
 *
 * @param action the verb: one of {@code setField}, {@code insertFact}, {@code retractFact},
 *     {@code emit} or {@code callFunction}
 * @param target the alias to mutate or retract; {@code setField} and {@code retractFact}
 * @param field the dotted field path to write; {@code setField}
 * @param value the value to write, a literal or a {@code $ref}; {@code setField}
 * @param fact the type of fact to insert; {@code insertFact}
 * @param as the optional alias bound to the inserted fact; {@code insertFact}
 * @param payload the fields of the inserted fact or emitted event; {@code insertFact} and
 *     {@code emit}
 * @param event the event type; {@code emit}
 * @param name the registered host-function name; {@code callFunction}
 * @param args the function arguments; {@code callFunction}
 */
record ThenNode(
    String action,
    String target,
    String field,
    JsonNode value,
    String fact,
    String as,
    Map<String, JsonNode> payload,
    String event,
    String name,
    Map<String, JsonNode> args) {

  /**
   * Canonical constructor. Copies both maps into insertion-ordered ones.
   *
   * <p>Ordered for the same reason {@link WhenNode}'s {@code where} is: payload field order reaches
   * the rule-set content hash, and §4.6 applies several {@code setField}s to one target in
   * declaration order, so "declaration order" has to survive parsing to mean anything.
   *
   * @param action the verb
   * @param target the alias to mutate or retract
   * @param field the dotted field path
   * @param value the value expression
   * @param fact the fact type to insert
   * @param as the optional binding alias
   * @param payload the fact or event fields
   * @param event the event type
   * @param name the host-function name
   * @param args the function arguments
   */
  ThenNode {
    payload = payload == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    args = args == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(args));
  }
}
