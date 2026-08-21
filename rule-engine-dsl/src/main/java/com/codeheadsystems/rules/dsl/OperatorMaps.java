package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RangeConstraint;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * §6.2.1's operator table, which is the whole of what a {@code where} block means.
 *
 * <p>The table there gives the syntax; §2.6.1 gives the semantics and stays normative. Nothing in
 * this class decides what {@code eq} does to an absent field -- that lives once, in
 * {@code Comparisons}, and is reached by producing the same {@code Operator} a Java-authored rule
 * would have produced. <strong>A rule written in YAML and the same rule written against the
 * constraint AST must be indistinguishable downstream</strong>, which is why this class emits AST
 * nodes and never behaviour.
 *
 * <p>Two shapes of output, chosen by the operand rather than by the operator:
 *
 * <ul>
 *   <li>A literal operand yields a single-fact test -- {@link FieldConstraint} or, for the ordered
 *       operators, {@link RangeConstraint}.
 *   <li>A {@code $ref} operand yields a {@link JoinConstraint}, which §6.5 resolves against the
 *       join graph and which §3.3 indexes from both ends.
 * </ul>
 *
 * <p><strong>Several operators in one map are AND-ed</strong>, the same way several fields in one
 * {@code where} block are. §6.2's example writes one operator per field and the spec does not say
 * what more than one means; conjunction is the only reading consistent with the two AND-s it does
 * define. Note the consequence and prefer the shorter form where there is one:
 * {@code { gt: 100, lt: 500 }} is two one-sided {@link RangeConstraint}s where
 * {@code { between: { from: 100, to: 500 } }} is a single two-sided one.
 */
final class OperatorMaps {

  private OperatorMaps() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Compiles one field's operator map.
   *
   * @param field the dotted field path the map constrains
   * @param operatorMap the map, as written
   * @param pointer the map's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the constraints, in document order. Empty when every operator in the map was rejected
   */
  static List<Constraint> constraintsOf(final String field, final JsonNode operatorMap,
      final String pointer, final Diagnostics diagnostics) {
    if (!operatorMap.isObject()) {
      /*
       * `total: 5` instead of `total: { eq: 5 }`. Without this, JsonNode.properties() returns an
       * empty set for a non-object and the field compiles to NO constraints at all -- a rule
       * matching more than its author wrote, which is the one failure this module is organised to
       * prevent. The schema does state the same rule and does run first; this is here for the
       * reason every other check in this class is, which the switch below spells out.
       */
      diagnostics.error(DslError.MALFORMED_OPERAND, pointer,
          "'" + field + "' needs an operator map such as { eq: ... }, got "
              + operatorMap.getNodeType().toString().toLowerCase(Locale.ROOT));
      return List.of();
    }
    final List<Constraint> constraints = new ArrayList<>(operatorMap.size());
    for (final Map.Entry<String, JsonNode> entry : operatorMap.properties()) {
      final String key = entry.getKey();
      final JsonNode operand = entry.getValue();
      final String at = pointer + "/" + key;
      switch (key) {
        case "eq" -> comparison(field, Operator.EQ, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "ne" -> comparison(field, Operator.NE, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "gt" -> ordered(field, Operator.GT, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "gte" -> ordered(field, Operator.GTE, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "lt" -> ordered(field, Operator.LT, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "lte" -> ordered(field, Operator.LTE, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "between" -> constraints.addAll(between(field, operand, at, diagnostics));
        case "in" -> literalTest(field, Operator.IN, key, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "notIn" -> literalTest(field, Operator.NOT_IN, key, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "matches" -> literalTest(field, Operator.MATCHES, key, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "hasField" -> literalTest(field, Operator.HAS_FIELD, key, operand, at, diagnostics)
            .ifPresent(constraints::add);
        case "isNull" -> literalTest(field, Operator.IS_NULL, key, operand, at, diagnostics)
            .ifPresent(constraints::add);
        /*
         * Unreachable while the schema gate runs, which states the same closed set. Kept because
         * "the gate ahead of me guarantees this" is how a switch acquires a silent default, and a
         * silently dropped constraint is a rule that matches more than its author wrote.
         */
        default -> diagnostics.error(DslError.UNKNOWN_OPERATOR, at,
            "'" + key + "' is not an operator; §6.2.1 lists the complete set");
      }
    }
    return constraints;
  }

  /**
   * Compiles {@code eq} or {@code ne}, either of which may join.
   *
   * @param field the field path
   * @param operator the comparison
   * @param operand the value or reference
   * @param pointer the operand's JSON Pointer
   * @param diagnostics collects problems
   * @return the constraint, or empty when the operand was rejected
   */
  private static Optional<Constraint> comparison(final String field, final Operator operator,
      final JsonNode operand, final String pointer, final Diagnostics diagnostics) {
    if (References.isRef(operand)) {
      return References.readRef(operand, pointer, diagnostics)
          .map(ref -> new JoinConstraint(field, ref.alias(), ref.field(), operator));
    }
    return References.readLiteral(operand, pointer, diagnostics)
        .map(literal -> new FieldConstraint(field, operator, literal));
  }

  /**
   * Compiles one of the four ordered operators.
   *
   * <p>A literal bound becomes a one-sided {@link RangeConstraint} rather than a
   * {@link FieldConstraint}, so that §3.3's sorted index sees one structure however the author
   * wrote the bound -- {@code { gte: 100 }} and {@code { between: { from: 100 } }} compile to the
   * identical constraint, which is what §6.2.1 promises.
   *
   * @param field the field path
   * @param operator the comparison
   * @param operand the bound or reference
   * @param pointer the operand's JSON Pointer
   * @param diagnostics collects problems
   * @return the constraint, or empty when the operand was rejected
   */
  private static Optional<Constraint> ordered(final String field, final Operator operator,
      final JsonNode operand, final String pointer, final Diagnostics diagnostics) {
    if (References.isRef(operand)) {
      return References.readRef(operand, pointer, diagnostics)
          .map(ref -> new JoinConstraint(field, ref.alias(), ref.field(), operator));
    }
    return References.readLiteral(operand, pointer, diagnostics)
        .map(bound -> RangeConstraint.of(field, operator, bound));
  }

  /**
   * Compiles a {@code between} block.
   *
   * <p>Both inclusivity flags default to {@code true}, per §6.2.1.
   *
   * <p>Each bound may independently be a literal or a {@code $ref}, and the mix is the reason this
   * returns a list. "An order whose total is between this customer's floor and ceiling" is an
   * ordinary thing to want and falls straight out of §2.5's named-bound shape: the literal bounds
   * gather into one {@link RangeConstraint} the sorted index can use, and each referencing bound
   * becomes its own {@link JoinConstraint} carrying the inclusivity in its operator.
   *
   * @param field the field path
   * @param block the between block
   * @param pointer the block's JSON Pointer
   * @param diagnostics collects problems
   * @return the constraints, or empty when the block was rejected
   */
  private static List<Constraint> between(final String field, final JsonNode block,
      final String pointer, final Diagnostics diagnostics) {
    final JsonNode from = block.get("from");
    final JsonNode to = block.get("to");
    if (from == null && to == null) {
      diagnostics.error(DslError.EMPTY_RANGE, pointer,
          "a between needs a 'from', a 'to', or both; this one bounds nothing");
      return List.of();
    }
    final boolean fromInclusive = block.path("fromInclusive").asBoolean(true);
    final boolean toInclusive = block.path("toInclusive").asBoolean(true);

    final List<Constraint> constraints = new ArrayList<>(2);
    final Optional<JsonNode> lower =
        bound(from, field, fromInclusive ? Operator.GTE : Operator.GT,
            pointer + "/from", constraints, diagnostics);
    final Optional<JsonNode> upper =
        bound(to, field, toInclusive ? Operator.LTE : Operator.LT,
            pointer + "/to", constraints, diagnostics);

    if (lower.isPresent() || upper.isPresent()) {
      constraints.add(new RangeConstraint(
          field, lower, fromInclusive, upper, toInclusive));
    }
    return constraints;
  }

  /**
   * Reads one bound of a {@code between}, routing a reference into its own join constraint.
   *
   * @param operand the bound as written, or null when absent
   * @param field the field path
   * @param operator the comparison this bound expresses, inclusivity already folded in
   * @param pointer the bound's JSON Pointer
   * @param joins collects the join constraint, when the bound is a reference
   * @param diagnostics collects problems
   * @return the literal bound, or empty when the bound is absent, a reference, or rejected
   */
  private static Optional<JsonNode> bound(final JsonNode operand, final String field,
      final Operator operator, final String pointer, final List<Constraint> joins,
      final Diagnostics diagnostics) {
    if (operand == null) {
      return Optional.empty();
    }
    if (References.isRef(operand)) {
      References.readRef(operand, pointer, diagnostics)
          .map(ref -> new JoinConstraint(field, ref.alias(), ref.field(), operator))
          .ifPresent(joins::add);
      return Optional.empty();
    }
    return References.readLiteral(operand, pointer, diagnostics);
  }

  /**
   * Compiles an operator whose operand is always a literal.
   *
   * <p>{@code in}, {@code notIn}, {@code matches}, {@code hasField} and {@code isNull} take no
   * reference. §2.6.1 defines the first two against an array of candidates and the last two against
   * a polarity carried in the literal, and {@code Operator.reverse()} already records that none of
   * them describes a relation between two facts.
   *
   * @param field the field path
   * @param operator the test
   * @param key the DSL key the author wrote, so the diagnostic quotes their spelling rather than a
   *     lowercased enum constant -- {@code notIn}, not {@code notin}
   * @param operand the literal
   * @param pointer the operand's JSON Pointer
   * @param diagnostics collects problems
   * @return the constraint, or empty when the operand was rejected
   */
  private static Optional<Constraint> literalTest(final String field, final Operator operator,
      final String key, final JsonNode operand, final String pointer,
      final Diagnostics diagnostics) {
    if (References.isRef(operand)) {
      diagnostics.error(DslError.MALFORMED_OPERAND, pointer,
          key + " takes a literal, not a $ref: it is a single-fact test, so there is no other fact"
              + " for a reference to name");
      return Optional.empty();
    }
    return References.readLiteral(operand, pointer, diagnostics)
        .map(literal -> new FieldConstraint(field, operator, literal));
  }
}
