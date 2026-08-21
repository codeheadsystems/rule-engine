package com.codeheadsystems.rules.dsl;

import java.util.List;

/**
 * A whole rule file, bound but not yet interpreted (spec §6.2.3).
 *
 * <p>This is the "intermediate POJO tree" of §6.1 and §6.5: the shape of the <em>document</em>,
 * carrying no engine meaning at all. Nothing here knows what an operator is, which is the point --
 * the document shape and the constraint AST evolve for different reasons and on different
 * schedules, and a parser bound straight to {@code RuleDefinition} would couple them.
 *
 * @param apiVersion the rule-file schema version (§6.2.3), or null when the file omitted it
 * @param rules the file's rules, in document order
 */
record RuleFileDocument(String apiVersion, List<RuleNode> rules) {

  /** The one {@code apiVersion} this DSL understands. */
  static final String API_VERSION = "rules.v1";

  /**
   * Canonical constructor. Normalises an absent rule list to an empty one.
   *
   * @param apiVersion the schema version
   * @param rules the rules
   */
  RuleFileDocument {
    rules = rules == null ? List.of() : List.copyOf(rules);
  }
}
