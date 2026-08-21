package com.codeheadsystems.rules.testkit;

import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Builds rule definitions in Java.
 *
 * <p>There is no DSL until Phase 5, so rules are written against the constraint AST directly. This
 * builder exists so that a scenario reads like the YAML it will eventually be, and so that the
 * fixtures written now can be re-pointed at rule files later without rewriting the assertions.
 *
 * <pre>{@code
 * RuleDefinition rule = Rules.rule("high-value-order-review")
 *     .salience(10)
 *     .when("o", "Order", p -> p.gt("total", 10000).eq("status", "PENDING"))
 *     .when("c", "Customer", p -> p.ref("id", "o.customerId").in("riskTier", "HIGH", "MEDIUM"))
 *     .then(t -> t.setField("o", "status", "REVIEW")
 *                 .emit("order.flagged", "orderId", Rules.ref("o.id")))
 *     .build();
 * }</pre>
 */
public final class Rules {

  private Rules() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Starts a rule.
   *
   * @param id the rule id
   * @return a builder
   */
  public static RuleBuilder rule(final String id) {
    return new RuleBuilder(id);
  }

  /**
   * A fire-time reference to a bound fact's field.
   *
   * @param aliasDotField a reference such as {@code o.customer.id}
   * @return the value expression
   */
  public static ValueExpr ref(final String aliasDotField) {
    return FieldRef.of(aliasDotField);
  }

  /**
   * A constant value.
   *
   * @param raw a string, number, boolean, null or {@code JsonNode}
   * @return the value expression
   */
  public static ValueExpr value(final Object raw) {
    return new Literal(Facts.obj("v", raw).get("v"));
  }

  /** Builds one {@link RuleDefinition}. */
  public static final class RuleBuilder {

    private final String id;
    private final List<PatternDefinition> when = new ArrayList<>();
    private final List<ActionDefinition> then = new ArrayList<>();
    private final Set<String> tags = new LinkedHashSet<>();
    private int salience;
    private boolean noLoop;
    private Optional<String> agendaGroup = Optional.empty();

    private RuleBuilder(final String id) {
      this.id = id;
    }

    /**
     * Sets the priority.
     *
     * @param value the salience; higher fires first
     * @return this builder
     */
    public RuleBuilder salience(final int value) {
      this.salience = value;
      return this;
    }

    /**
     * Suppresses this rule's own right-hand side from re-enabling this same match.
     *
     * @return this builder
     */
    public RuleBuilder noLoop() {
      this.noLoop = true;
      return this;
    }

    /**
     * Assigns an agenda group. Recorded and ignored in v1.
     *
     * @param group the group name
     * @return this builder
     */
    public RuleBuilder agendaGroup(final String group) {
      this.agendaGroup = Optional.of(group);
      return this;
    }

    /**
     * Adds a tag.
     *
     * @param tag the label
     * @return this builder
     */
    public RuleBuilder tag(final String tag) {
      this.tags.add(tag);
      return this;
    }

    /**
     * Adds an unconstrained pattern.
     *
     * @param alias the binding name
     * @param factType the fact type
     * @return this builder
     */
    public RuleBuilder when(final String alias, final String factType) {
      return when(alias, factType, pattern -> { });
    }

    /**
     * Adds a pattern.
     *
     * @param alias the binding name
     * @param factType the fact type
     * @param constraints declares the pattern's conditions
     * @return this builder
     */
    public RuleBuilder when(final String alias, final String factType,
        final Consumer<PatternBuilder> constraints) {
      final PatternBuilder pattern = new PatternBuilder();
      constraints.accept(pattern);
      when.add(PatternDefinition.of(alias, factType, pattern.constraints));
      return this;
    }

    /**
     * Declares the right-hand side.
     *
     * @param actions declares the actions, in order
     * @return this builder
     */
    public RuleBuilder then(final Consumer<ActionBuilder> actions) {
      final ActionBuilder builder = new ActionBuilder();
      actions.accept(builder);
      then.addAll(builder.actions);
      return this;
    }

    /**
     * Builds the definition.
     *
     * @return the rule
     */
    public RuleDefinition build() {
      return new RuleDefinition(id, salience, when, then, noLoop, agendaGroup, tags);
    }
  }

  /** Declares one pattern's constraints. */
  public static final class PatternBuilder {

    private final List<Constraint> constraints = new ArrayList<>();

    private PatternBuilder() {
      // Constructed by RuleBuilder.
    }

    /**
     * Adds an arbitrary constraint, for cases the shorthands do not cover.
     *
     * @param constraint the constraint
     * @return this builder
     */
    public PatternBuilder constraint(final Constraint constraint) {
      constraints.add(constraint);
      return this;
    }

    /**
     * Adds a comparison against a literal.
     *
     * @param field the dotted field path
     * @param op the operator
     * @param literal the literal, as a raw Java value or a {@code JsonNode}
     * @return this builder
     */
    public PatternBuilder op(final String field, final Operator op, final Object literal) {
      return constraint(new FieldConstraint(field, op, node(literal)));
    }

    /**
     * Field equals a literal.
     *
     * @param field the dotted field path
     * @param literal the literal
     * @return this builder
     */
    public PatternBuilder eq(final String field, final Object literal) {
      return op(field, Operator.EQ, literal);
    }

    /**
     * Field does not equal a literal. True for an absent field; see {@link Operator#NE}.
     *
     * @param field the dotted field path
     * @param literal the literal
     * @return this builder
     */
    public PatternBuilder ne(final String field, final Object literal) {
      return op(field, Operator.NE, literal);
    }

    /**
     * Field is strictly greater than a bound.
     *
     * @param field the dotted field path
     * @param bound the bound
     * @return this builder
     */
    public PatternBuilder gt(final String field, final Object bound) {
      return op(field, Operator.GT, bound);
    }

    /**
     * Field is strictly less than a bound.
     *
     * @param field the dotted field path
     * @param bound the bound
     * @return this builder
     */
    public PatternBuilder lt(final String field, final Object bound) {
      return op(field, Operator.LT, bound);
    }

    /**
     * Field falls in a closed range.
     *
     * @param field the dotted field path
     * @param from the inclusive lower bound
     * @param to the inclusive upper bound
     * @return this builder
     */
    public PatternBuilder between(final String field, final Object from, final Object to) {
      return constraint(new RangeConstraint(field,
          Optional.of(node(from)), true, Optional.of(node(to)), true));
    }

    /**
     * Field equals any of the given values.
     *
     * @param field the dotted field path
     * @param values the candidates
     * @return this builder
     */
    public PatternBuilder in(final String field, final Object... values) {
      return op(field, Operator.IN, Facts.array(values));
    }

    /**
     * Field matches an RE2 regular expression.
     *
     * @param field the dotted field path
     * @param regex the pattern
     * @return this builder
     */
    public PatternBuilder matches(final String field, final String regex) {
      return op(field, Operator.MATCHES, regex);
    }

    /**
     * Field presence.
     *
     * @param field the dotted field path
     * @param present the polarity
     * @return this builder
     */
    public PatternBuilder hasField(final String field, final boolean present) {
      return op(field, Operator.HAS_FIELD, present);
    }

    /**
     * Field is an explicit JSON null, as distinct from absent.
     *
     * @param field the dotted field path
     * @param isNull the polarity
     * @return this builder
     */
    public PatternBuilder isNull(final String field, final boolean isNull) {
      return op(field, Operator.IS_NULL, isNull);
    }

    /**
     * A join: this pattern's field equals an earlier alias's field.
     *
     * @param field the dotted field path on this pattern's fact
     * @param otherAliasDotField the earlier reference, such as {@code o.customerId}
     * @return this builder
     */
    public PatternBuilder ref(final String field, final String otherAliasDotField) {
      return ref(field, otherAliasDotField, Operator.EQ);
    }

    /**
     * A join with an explicit operator.
     *
     * @param field the dotted field path on this pattern's fact
     * @param otherAliasDotField the earlier reference, such as {@code o.customerId}
     * @param op the comparison
     * @return this builder
     */
    public PatternBuilder ref(final String field, final String otherAliasDotField,
        final Operator op) {
      final int dot = otherAliasDotField.indexOf('.');
      if (dot < 1) {
        throw new IllegalArgumentException(
            "join reference must be 'alias.field', got '" + otherAliasDotField + "'");
      }
      return constraint(new JoinConstraint(field, otherAliasDotField.substring(0, dot),
          otherAliasDotField.substring(dot + 1), op));
    }
  }

  /** Declares one right-hand side's actions, in order. */
  public static final class ActionBuilder {

    private final List<ActionDefinition> actions = new ArrayList<>();

    private ActionBuilder() {
      // Constructed by RuleBuilder.
    }

    /**
     * Sets a field of a bound fact.
     *
     * @param alias the target alias
     * @param field the dotted field path
     * @param literalOrRef a raw value, a {@code JsonNode}, or a {@link Rules#ref(String)}
     * @return this builder
     */
    public ActionBuilder setField(final String alias, final String field,
        final Object literalOrRef) {
      actions.add(SetField.of(alias, field, expr(literalOrRef)));
      return this;
    }

    /**
     * Inserts a derived fact.
     *
     * @param factType the new fact's type
     * @param keysAndValues alternating field names and values or references
     * @return this builder
     */
    public ActionBuilder insertFact(final String factType, final Object... keysAndValues) {
      actions.add(InsertFact.of(factType, fields(keysAndValues)));
      return this;
    }

    /**
     * Inserts a derived fact and binds it, so later actions can name it.
     *
     * @param factType the new fact's type
     * @param alias the binding name
     * @param keysAndValues alternating field names and values or references
     * @return this builder
     */
    public ActionBuilder insertFactAs(final String factType, final String alias,
        final Object... keysAndValues) {
      actions.add(new InsertFact(factType, Optional.of(alias), fields(keysAndValues)));
      return this;
    }

    /**
     * Retracts a bound fact.
     *
     * @param alias the target alias
     * @return this builder
     */
    public ActionBuilder retractFact(final String alias) {
      actions.add(new RetractFact(alias));
      return this;
    }

    /**
     * Emits an event.
     *
     * @param eventType the event name
     * @param keysAndValues alternating field names and values or references
     * @return this builder
     */
    public ActionBuilder emit(final String eventType, final Object... keysAndValues) {
      actions.add(new Emit(eventType, fields(keysAndValues)));
      return this;
    }

    /**
     * Calls a registered host function.
     *
     * @param name the function name
     * @param keysAndValues alternating argument names and values or references
     * @return this builder
     */
    public ActionBuilder callFunction(final String name, final Object... keysAndValues) {
      actions.add(new CallFunction(name, fields(keysAndValues)));
      return this;
    }

    /**
     * Builds an ordered payload field list from alternating names and values.
     *
     * @param keysAndValues alternating names and values
     * @return the fields, in declaration order
     */
    private static List<PayloadField> fields(final Object... keysAndValues) {
      if (keysAndValues.length % 2 != 0) {
        throw new IllegalArgumentException("payloads take alternating names and values");
      }
      final List<PayloadField> fields = new ArrayList<>(keysAndValues.length / 2);
      for (int index = 0; index < keysAndValues.length; index += 2) {
        fields.add(PayloadField.of(
            String.valueOf(keysAndValues[index]), expr(keysAndValues[index + 1])));
      }
      return fields;
    }
  }

  /**
   * Coerces a raw value into a value expression, passing references through.
   *
   * @param raw a value expression, a {@code JsonNode}, or a raw Java value
   * @return the expression
   */
  private static ValueExpr expr(final Object raw) {
    return raw instanceof ValueExpr expression ? expression : value(raw);
  }

  /**
   * Coerces a raw value into a JSON node.
   *
   * @param raw a {@code JsonNode} or a raw Java value
   * @return the node
   */
  private static JsonNode node(final Object raw) {
    return raw instanceof JsonNode already ? already : Facts.obj("v", raw).get("v");
  }
}
