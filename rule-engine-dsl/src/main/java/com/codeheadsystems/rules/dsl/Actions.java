package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * §6.2.2's five verbs, into the {@code ActionDefinition} of the same name.
 *
 * <p><strong>A {@code $ref} here is not the {@code $ref} of a {@code where} block, and the two must
 * not be unified.</strong> §6.2.3 is explicit: a {@code where} reference resolves at <em>compile</em>
 * time against the join graph and becomes a {@code JoinConstraint}; a {@code then} reference
 * resolves at <em>fire</em> time against the tuple and becomes a {@link FieldRef}. One syntax, two
 * mechanisms, deliberately -- which is why {@link OperatorMaps} and this class share
 * {@link References} for the <em>parsing</em> of the shape and share nothing for what it means.
 *
 * <p>The paths are compiled here, once, because §10 requires that nothing parse a path string while
 * a rule is firing.
 */
final class Actions {

  private Actions() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Compiles one {@code then} block.
   *
   * @param node the action, as written
   * @param pointer the action's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the action, or empty when it was rejected
   */
  static Optional<ActionDefinition> actionOf(final ThenNode node, final String pointer,
      final Diagnostics diagnostics) {
    return switch (node.action()) {
      case "setField" -> setField(node, pointer, diagnostics);
      case "insertFact" -> insertFact(node, pointer, diagnostics);
      case "retractFact" -> Optional.of(new RetractFact(node.target()));
      case "emit" -> payload(node.payload(), pointer + "/payload", diagnostics)
          .map(fields -> new Emit(node.event(), fields));
      case "callFunction" -> payload(node.args(), pointer + "/args", diagnostics)
          .map(args -> new CallFunction(node.name(), args));
      /*
       * Unreachable behind the schema gate, which enumerates the same five. Kept for the reason
       * OperatorMaps keeps its default: a dropped action is a rule that quietly does less than it
       * says, and that is worse than an error.
       */
      default -> {
        diagnostics.error(DslError.UNKNOWN_ACTION, pointer + "/action",
            "'" + node.action() + "' is not an action; §6.2.2 lists the complete set of five");
        yield Optional.empty();
      }
    };
  }

  /**
   * Compiles {@code setField}.
   *
   * @param node the action
   * @param pointer the action's JSON Pointer
   * @param diagnostics collects problems
   * @return the action, or empty when it was rejected
   */
  private static Optional<ActionDefinition> setField(final ThenNode node, final String pointer,
      final Diagnostics diagnostics) {
    final Optional<String> field =
        References.readKey(node.field(), pointer + "/field", "field name", diagnostics);
    if (field.isEmpty()) {
      return Optional.empty();
    }
    final Optional<JsonPointer> path = path(field.get(), pointer + "/field", diagnostics);
    final Optional<ValueExpr> value =
        valueOf(node.value(), pointer + "/value", diagnostics);
    return path.flatMap(compiled -> value.map(
        expression -> new SetField(node.target(), field.get(), compiled, expression)));
  }

  /**
   * Compiles {@code insertFact}.
   *
   * @param node the action
   * @param pointer the action's JSON Pointer
   * @param diagnostics collects problems
   * @return the action, or empty when it was rejected
   */
  private static Optional<ActionDefinition> insertFact(final ThenNode node, final String pointer,
      final Diagnostics diagnostics) {
    return payload(node.payload(), pointer + "/payload", diagnostics)
        .map(fields -> new InsertFact(
            node.fact(), Optional.ofNullable(node.as()), fields,
            // Absent means false, which is what every insertFact written before §4.4's amendment
            // meant and must go on meaning.
            Boolean.TRUE.equals(node.logical())));
  }

  /**
   * Compiles a payload or argument block.
   *
   * @param fields the block, field name to value
   * @param pointer the block's JSON Pointer
   * @param diagnostics collects problems
   * @return the compiled fields in document order, or empty when any field was rejected
   */
  private static Optional<List<PayloadField>> payload(final Map<String, JsonNode> fields,
      final String pointer, final Diagnostics diagnostics) {
    final int before = diagnostics.count();
    final List<PayloadField> compiled = new ArrayList<>(fields.size());
    for (final Map.Entry<String, JsonNode> field : fields.entrySet()) {
      final String at = pointer + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
      /*
       * The name gets the same $-treatment an operand does. This is where §6.2.3's typo is most
       * natural -- an author who forgets the wrapping key writes `payload: { $ref: o.id }` and
       * would otherwise get a field literally named '$ref' holding the string "o.id".
       */
      final Optional<String> name =
          References.readKey(field.getKey(), at, "payload field name", diagnostics);
      if (name.isEmpty()) {
        continue;
      }
      final Optional<JsonPointer> path = path(name.get(), at, diagnostics);
      final Optional<ValueExpr> value = valueOf(field.getValue(), at, diagnostics);
      path.flatMap(compiledPath -> value.map(
              expression -> new PayloadField(name.get(), compiledPath, expression)))
          .ifPresent(compiled::add);
    }
    return diagnostics.count() == before ? Optional.of(compiled) : Optional.empty();
  }

  /**
   * Reads a value that may be a literal or a fire-time reference.
   *
   * @param value the value as written
   * @param pointer the value's JSON Pointer
   * @param diagnostics collects problems
   * @return the expression, or empty when it was rejected
   */
  private static Optional<ValueExpr> valueOf(final JsonNode value, final String pointer,
      final Diagnostics diagnostics) {
    if (References.isExpression(value)) {
      /*
       * §6.4 on the right-hand side. The aliases are left empty here on purpose: this layer parses
       * syntax and the expression's variables are the compiler's business, which is where they are
       * checked against what the rule binds. Guessing them by scanning the source text would be a
       * second, worse parser for a language this module does not implement.
       */
      return References.readExpression(value, pointer, diagnostics)
          .map(source -> new ExpressionValue(source, java.util.Set.of()));
    }
    if (References.isRef(value)) {
      return References.readRef(value, pointer, diagnostics)
          .flatMap(ref -> path(ref.field(), pointer, diagnostics)
              .map(path -> new FieldRef(ref.alias(), path)));
    }
    return References.readLiteral(value, pointer, diagnostics).map(Literal::new);
  }

  /**
   * Compiles a dotted field path.
   *
   * @param dotted the path in DSL form
   * @param pointer the path's JSON Pointer
   * @param diagnostics collects problems
   * @return the compiled pointer, or empty when the path was malformed
   */
  private static Optional<JsonPointer> path(final String dotted, final String pointer,
      final Diagnostics diagnostics) {
    try {
      return Optional.of(Paths.compile(dotted));
    } catch (final IllegalArgumentException malformed) {
      diagnostics.error(DslError.MALFORMED_ACTION, pointer, malformed.getMessage());
      return Optional.empty();
    }
  }
}
