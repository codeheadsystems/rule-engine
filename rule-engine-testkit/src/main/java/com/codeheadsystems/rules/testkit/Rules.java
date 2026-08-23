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
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

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
     * Adds a {@code NOT_EXISTS} pattern: the rule matches only when no such fact exists (§1).
     *
     * <p>A negated pattern <strong>binds nothing</strong>. Its alias names the fact being looked
     * for so its own constraints can be written, and nothing else in the rule may reference it --
     * neither a {@code ref} in another pattern nor a right-hand side. What it contributes is a
     * question asked of each complete match: "and is there no {@code Payment} whose orderId is this
     * order's?"
     *
     * <p>It may join against the aliases the rule does bind, in either direction of declaration.
     * Where its fact type is one the rule already binds, §1's implicit inequality applies as it does
     * between two positive aliases: the question is about some <em>other</em> fact of that type.
     *
     * <p><strong>There is no truth maintenance behind this</strong> (§1). A rule that fires because
     * something was absent is not retracted when that thing arrives; refraction is keyed on the
     * facts the match binds, and the fact whose absence was asserted is not one of them. The absence
     * ending makes the match ineligible from then on, which is not the same as undoing what it did.
     *
     * <p><strong>Do not negate a type a session evicts</strong> (§4.4), and this one is sharper than
     * the paragraph above. An evicted fact and an absent fact are indistinguishable to a negation,
     * so a cap on {@code Payment} makes this rule announce that a paid order is unpaid -- not a
     * firing missed, but a conclusion asserted that is false. Before negation existed, eviction
     * could only ever cost a firing; a negated type turns it into wrong output, and §1's licence to
     * ship without truth maintenance does not extend to that. Cap the types you bind, not the ones
     * whose absence you assert.
     *
     * @param alias a name for the fact being looked for; binds nothing
     * @param factType the fact type whose absence is asserted
     * @param constraints the conditions that fact would have to satisfy
     * @return this builder
     */
    public RuleBuilder notExists(final String alias, final String factType,
        final Consumer<PatternBuilder> constraints) {
      final PatternBuilder pattern = new PatternBuilder();
      constraints.accept(pattern);
      when.add(new PatternDefinition(alias, factType, Quantifier.NOT_EXISTS, pattern.constraints));
      return this;
    }

    /**
     * Declares a pattern asserting that every fact in some scope meets a requirement (§2.5's
     * {@code FOR_ALL}).
     *
     * <p><strong>The joins choose the scope; the constraints are the requirement.</strong> That is
     * the whole reading, and it is what makes the quantifier useful rather than a trap. Written
     * beside a bound {@code o}, {@code forAll("li", "LineItem", p -> p.ref("orderId", "o.id")
     * .eq("inStock", true))} says "every {@code LineItem} <em>of this order</em> is in stock". Under
     * the other reading -- every fact of the type satisfies everything written -- the same pattern
     * would assert that every {@code LineItem} anywhere belongs to this order, which is false the
     * moment a second order exists, and the rule could never fire.
     *
     * <p>With no joins the two readings coincide: every fact of the type must satisfy the
     * constraints.
     *
     * <p>Like a negated pattern it <strong>binds nothing</strong>, and nothing in the rule may
     * reference its alias. Where its fact type is one the rule already binds, §1's implicit
     * inequality applies to the <em>scope</em>: the fact the tuple binds is not one the assertion is
     * about, so "every other {@code Order} is shipped" is what a same-type universal means.
     *
     * <p><strong>It is vacuously true over an empty scope</strong>, which is classical and is the
     * trap to know about. "Every line item of this order is in stock" fires for an order with no
     * line items at all. Pair it with a positive pattern of the same type -- which a rule wanting
     * this usually has anyway -- to say "there are some, and all of them".
     *
     * <p><strong>There is no truth maintenance behind this</strong> (§1), exactly as for
     * {@link #notExists}: a rule that fired because everything in scope met the requirement is not
     * undone when a counterexample arrives.
     *
     * <p><strong>Do not quantify over a type a session evicts</strong> (§4.4), and this is sharper
     * than the negation case. Evicting facts can only remove counterexamples, so a cap does not
     * weaken the requirement but strengthens it -- and a cap that empties the scope makes the
     * assertion vacuously true, which deletes it. Cap the types you bind.
     *
     * @param alias a name for the facts being quantified over; binds nothing
     * @param factType the fact type the assertion ranges over
     * @param constraints the joins that choose the scope, and the requirement asserted of it
     * @return this builder
     */
    public RuleBuilder forAll(final String alias, final String factType,
        final Consumer<PatternBuilder> constraints) {
      final PatternBuilder pattern = new PatternBuilder();
      constraints.accept(pattern);
      when.add(new PatternDefinition(alias, factType, Quantifier.FOR_ALL, pattern.constraints));
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
      return range(field, Operator.GT, bound);
    }

    /**
     * Field is greater than or equal to a bound.
     *
     * @param field the dotted field path
     * @param bound the bound
     * @return this builder
     */
    public PatternBuilder gte(final String field, final Object bound) {
      return range(field, Operator.GTE, bound);
    }

    /**
     * Field is strictly less than a bound.
     *
     * @param field the dotted field path
     * @param bound the bound
     * @return this builder
     */
    public PatternBuilder lt(final String field, final Object bound) {
      return range(field, Operator.LT, bound);
    }

    /**
     * Field is less than or equal to a bound.
     *
     * @param field the dotted field path
     * @param bound the bound
     * @return this builder
     */
    public PatternBuilder lte(final String field, final Object bound) {
      return range(field, Operator.LTE, bound);
    }

    /**
     * Adds a one-sided range.
     *
     * <p>A {@link RangeConstraint} rather than a {@link FieldConstraint} carrying the same
     * operator, because §6.2.1's table compiles the DSL's {@code gt}/{@code gte}/{@code lt}/
     * {@code lte} that way and {@link RangeConstraint} exists to "unify the one-sided forms".
     * <p>The consequence is narrower than it first looks, and worth stating exactly. Node sharing
     * is <em>not</em> affected: {@code RuleCompiler.compileField} already rewrites an ordered
     * {@code FieldConstraint} into a {@code RangeConstraint} before building the {@code AlphaTest},
     * so {@code NetworkBuilder} dedups on the same key whichever form was written, and both
     * spellings really do collapse to one node.
     *
     * <p>What differs is §5.6's version hash, which is computed over the <em>source</em>
     * {@code RuleDefinition} rather than the compiled form -- so the same rule authored in YAML and
     * in Java carried two different rule-set versions. {@code DslEquivalenceTest} is what found it,
     * and a hash that changes with the authoring front end makes hot reload swap on nothing.
     *
     * @param field the dotted field path
     * @param op one of the four ordered operators
     * @param bound the bound
     * @return this builder
     */
    private PatternBuilder range(final String field, final Operator op, final Object bound) {
      return constraint(RangeConstraint.of(field, op, node(bound)));
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
