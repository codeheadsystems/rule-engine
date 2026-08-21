package com.codeheadsystems.rules.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One {@code when} clause as written in a file (spec §6.2).
 *
 * <p><strong>{@code where} is a map of raw nodes, not a typed operator record, and that is a
 * decision rather than laziness.</strong> §6.2.3 puts three judgements in the operator map -- is
 * this operand a {@code $ref}, is it the {@code $$ref} escape, is this an unrecognised
 * {@code $}-prefixed key to reject -- and they have to be made in one place or they drift. Binding
 * to a typed shape would scatter them across Jackson creators and answer them with Jackson's error
 * messages instead of ones that name the operator. {@link OperatorMaps} owns all of it.
 *
 * @param fact the fact type this pattern matches
 * @param as the alias it binds
 * @param where the operator maps, field name to operator map, in document order
 * @param condition the CEL escape hatch of §6.4, or null. Parsed so that it can be rejected with a
 *     diagnostic that says where it arrives, rather than falling out of the schema as an unknown key
 */
record WhenNode(String fact, String as, Map<String, JsonNode> where, String condition) {

  /**
   * Canonical constructor. Copies {@code where} into an insertion-ordered map.
   *
   * <p>The ordering is load-bearing. Constraint order within a pattern is preserved into
   * {@code PatternDefinition.constraints} and reaches the rule-set content hash, so letting hash
   * iteration order decide it would be a §7.3 determinism defect -- the same one
   * {@code RuleDefinition.tags} documents at its own field, arriving by a different route.
   *
   * @param fact the fact type
   * @param as the alias
   * @param where the operator maps
   * @param condition the CEL expression, or null
   */
  WhenNode {
    where = where == null
        ? Map.of()
        : Collections.unmodifiableMap(new LinkedHashMap<>(where));
  }
}
