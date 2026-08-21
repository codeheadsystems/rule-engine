package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.FieldTest;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RangeTest;
import com.codeheadsystems.rules.rule.RegexTest;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.DefaultCompiledRuleSet;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiles rule definitions into an immutable, shareable rule set (spec section 6.5, Phase 0
 * subset).
 *
 * <p>What it does, in order:
 *
 * <ol>
 *   <li><strong>Semantic validation</strong> -- duplicate rule ids, empty {@code when}/{@code then},
 *       duplicate aliases, unimplemented quantifiers, and the rule section 6.5 states as
 *       "every {@code $ref} resolves to an <em>earlier</em> alias", which is what keeps the join
 *       graph a DAG with no forward references.
 *   <li><strong>The implicit inequality between same-type aliases.</strong> Distinct aliases in one
 *       rule bind distinct facts, so {@code Order as o1, Order as o2} finds two <em>different</em>
 *       orders. This differs from OPS5, and the other reading silently produces self-matches --
 *       which is exactly why the compiler inserts it rather than leaving it to the author to
 *       remember.
 *   <li><strong>Accessor, literal and pattern compilation.</strong> Paths become
 *       {@code JsonPointer}s and regular expressions become RE2 programs, once, here -- never per
 *       fact per cycle.
 *   <li><strong>The tested-path artifact</strong> and its inverse index.
 *   <li><strong>The rule-set version hash.</strong>
 * </ol>
 *
 * <p>What it deliberately does not do: node sharing, node id assignment, and the index plan. Those
 * are Phase 1, and there is no network in Phase 0 to hang them on.
 */
public final class RuleCompiler {

  /** Identifies the compiler in the version hash, so a compiler change invalidates it. */
  public static final String COMPILER_VERSION = "rule-engine-compiler/phase0";

  private final CompilerOptions options;
  private final List<String> diagnostics = new ArrayList<>();
  private final Map<String, Set<JsonPointer>> pathsByType = new LinkedHashMap<>();
  private final Map<String, Map<String, Set<JsonPointer>>> pathsByRule = new LinkedHashMap<>();
  private final Map<String, Map<JsonPointer, Set<String>>> inverse = new LinkedHashMap<>();

  private RuleCompiler(final CompilerOptions options) {
    this.options = options;
  }

  /**
   * Compiles a rule set with default options.
   *
   * @param rules the definitions, in the order they should be reported in
   * @return the compiled rule set
   * @throws RuleCompilationException if any rule is invalid; the exception carries every diagnostic
   */
  public static CompiledRuleSet compile(final List<RuleDefinition> rules) {
    return compile(rules, CompilerOptions.defaults());
  }

  /**
   * Compiles a rule set.
   *
   * @param rules the definitions, in the order they should be reported in
   * @param options how the compiler should behave
   * @return the compiled rule set
   * @throws RuleCompilationException if any rule is invalid; the exception carries every diagnostic
   */
  public static CompiledRuleSet compile(final List<RuleDefinition> rules,
      final CompilerOptions options) {
    return new RuleCompiler(options).run(rules);
  }

  /**
   * Runs the pipeline.
   *
   * @param rules the definitions
   * @return the compiled rule set
   */
  private CompiledRuleSet run(final List<RuleDefinition> rules) {
    final Set<String> seenIds = new LinkedHashSet<>();
    final List<CompiledRule> compiled = new ArrayList<>(rules.size());
    for (final RuleDefinition rule : rules) {
      if (!seenIds.add(rule.id())) {
        diagnostics.add("duplicate rule id '" + rule.id() + "'");
        continue;
      }
      compileRule(rule).ifPresent(compiled::add);
    }
    if (!diagnostics.isEmpty()) {
      throw new RuleCompilationException(diagnostics);
    }
    final Network network = NetworkBuilder.build(compiled);
    final String version = version(rules);
    return new DefaultCompiledRuleSet(
        compiled,
        network,
        new DefaultTestedPaths(pathsByType, pathsByRule, inverse),
        version,
        // §6.5's pipeline emits the report last, on the shared graph: sharing changes which nodes
        // exist, so anything counted before it counts nodes that are about to be merged away.
        ReportBuilder.build(compiled, network, version, options),
        options.factSchemas());
  }

  /**
   * Compiles one rule, or records why it could not be compiled.
   *
   * @param rule the definition
   * @return the compiled rule, or empty if it was invalid
   */
  private Optional<CompiledRule> compileRule(final RuleDefinition rule) {
    final int before = diagnostics.size();
    if (rule.when().isEmpty()) {
      diagnostics.add(rule.id() + ": a rule must have at least one pattern in 'when'");
    }
    if (rule.then().isEmpty()) {
      diagnostics.add(rule.id() + ": a rule must have at least one action in 'then'");
    }

    // Collect every alias BEFORE compiling any pattern. Doing it as we go would leave the join
    // resolver unable to tell "there is no such alias" from "that alias is bound later" -- the
    // second is a real and common authoring mistake, and it deserves the diagnostic that names it
    // rather than one that says the alias does not exist when it plainly does.
    final Map<String, Integer> aliasPositions = new LinkedHashMap<>();
    final List<String> aliasTypes = new ArrayList<>();
    for (int position = 0; position < rule.when().size(); position++) {
      final PatternDefinition pattern = rule.when().get(position);
      if (pattern.quantifier() != Quantifier.EXISTS_AT_LEAST_ONE) {
        diagnostics.add(rule.id() + ": quantifier " + pattern.quantifier()
            + " on alias '" + pattern.alias() + "' is not implemented in v1. See spec section 1"
            + " for the interim answer");
      }
      if (aliasPositions.putIfAbsent(pattern.alias(), position) != null) {
        diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "' is bound twice");
      }
      aliasTypes.add(pattern.factType());
    }

    final List<CompiledPattern> patterns = new ArrayList<>(rule.when().size());
    for (int position = 0; position < rule.when().size(); position++) {
      patterns.add(
          compilePattern(rule, rule.when().get(position), position, aliasPositions, aliasTypes));
    }

    validateActions(rule, aliasPositions.keySet());

    if (diagnostics.size() != before) {
      return Optional.empty();
    }
    return Optional.of(new CompiledRule(
        rule.id(), rule.salience(), rule.noLoop(), rule.agendaGroup(), patterns, rule.then(),
        pathsByRule.getOrDefault(rule.id(), Map.of()), rule));
  }

  /**
   * Compiles one pattern's constraints and derives its implicit inequalities.
   *
   * @param rule the enclosing rule
   * @param pattern the pattern
   * @param position the pattern's position in the rule
   * @param aliasPositions every alias the rule binds, mapped to its position
   * @param aliasTypes every fact type the rule binds, indexed by position
   * @return the compiled pattern
   */
  private CompiledPattern compilePattern(final RuleDefinition rule,
      final PatternDefinition pattern, final int position,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes) {
    final List<AlphaTest> alphaTests = new ArrayList<>();
    final List<JoinTest> joinTests = new ArrayList<>();

    for (final Constraint constraint : pattern.constraints()) {
      switch (constraint) {
        case JoinConstraint join ->
            compileJoin(rule, pattern, join, aliasPositions, aliasTypes).ifPresent(joinTests::add);
        case ExpressionConstraint expression -> diagnostics.add(rule.id()
            + ": the CEL 'condition' escape hatch is not implemented in v1 (expression: '"
            + expression.expression() + "'). It arrives with the DSL front-end in Phase 5");
        case FieldConstraint field ->
            compileField(rule, pattern, field).ifPresent(alphaTests::add);
        case RangeConstraint range ->
            compileRange(rule, pattern, range).ifPresent(alphaTests::add);
      }
    }

    // The implicit inequality: this pattern's fact must differ from every earlier fact of the same
    // type. Two same-type aliases naming one fact is a self-match nobody asked for.
    final List<Integer> distinct = new ArrayList<>();
    for (int earlier = 0; earlier < position; earlier++) {
      if (aliasTypes.get(earlier).equals(pattern.factType())) {
        distinct.add(earlier);
      }
    }
    return new CompiledPattern(pattern.alias(), pattern.factType(), alphaTests, joinTests,
        distinct.stream().mapToInt(Integer::intValue).toArray());
  }

  /**
   * Compiles a single-fact constraint, validating its literal against its operator.
   *
   * @param rule the enclosing rule
   * @param pattern the enclosing pattern
   * @param constraint the constraint
   * @return the compiled test, or empty if the constraint was invalid
   */
  private Optional<AlphaTest> compileField(final RuleDefinition rule,
      final PatternDefinition pattern, final FieldConstraint constraint) {
    final String where = rule.id() + ": " + pattern.alias() + "." + constraint.field();
    /*
     * Returning here means a constraint with both a bad path and a bad literal reports only the
     * path. That narrowing is deliberate: there is no accessor to validate a literal against until
     * the path compiles, and a second diagnostic derived from a path that does not exist would be
     * noise. Every OTHER constraint in the rule is still reported -- compilePattern keeps going.
     */
    final Optional<JsonPointer> compiled = compilePath(where, constraint.field());
    if (compiled.isEmpty()) {
      return Optional.empty();
    }
    final JsonPointer path = compiled.get();
    record(rule.id(), pattern.factType(), path);
    final JsonPointerAccessor accessor = new JsonPointerAccessor(path);

    // The four ordering operators are not checked here: the switch below hands them to
    // compileRange, which checks each bound in its own right. Checking in both places reported
    // every such constraint twice, with byte-identical text.
    if (!isOrdering(constraint.op())) {
      checkLiteralType(where, pattern.factType(), constraint.field(), constraint.op(),
          constraint.literal());
    }
    return switch (constraint.op()) {
      case MATCHES -> compileRegex(where, constraint, accessor);
      case IN, NOT_IN -> {
        if (!constraint.literal().isArray()) {
          diagnostics.add(where + ": " + constraint.op() + " expects an array literal, got "
              + constraint.literal().getNodeType());
          yield Optional.empty();
        }
        yield Optional.of(new FieldTest(constraint, accessor));
      }
      case HAS_FIELD, IS_NULL -> {
        if (!constraint.literal().isBoolean()) {
          diagnostics.add(where + ": " + constraint.op()
              + " carries its polarity in a boolean literal, got "
              + constraint.literal().getNodeType());
          yield Optional.empty();
        }
        yield Optional.of(new FieldTest(constraint, accessor));
      }
      // gt/gte/lt/lte compile into the same RangeConstraint the two-sided form produces, so there
      // is one ordering code path rather than two that can disagree.
      case GT, GTE, LT, LTE -> compileRange(rule, pattern,
          RangeConstraint.of(constraint.field(), constraint.op(), constraint.literal()));
      case EQ, NE -> Optional.of(new FieldTest(constraint, accessor));
    };
  }

  /**
   * Compiles a regular-expression constraint into an RE2 program.
   *
   * @param where a diagnostic prefix naming the rule and field
   * @param constraint the constraint
   * @param accessor the compiled accessor
   * @return the compiled test, or empty if the pattern was invalid
   */
  private Optional<AlphaTest> compileRegex(final String where, final FieldConstraint constraint,
      final JsonPointerAccessor accessor) {
    if (!constraint.literal().isTextual()) {
      diagnostics.add(where + ": matches expects a string pattern, got "
          + constraint.literal().getNodeType());
      return Optional.empty();
    }
    try {
      return Optional.of(
          new RegexTest(constraint, accessor, Pattern.compile(constraint.literal().textValue())));
    } catch (final PatternSyntaxException invalid) {
      diagnostics.add(where + ": invalid regular expression -- " + invalid.getMessage()
          + ". Patterns are RE2 (spec section 2.6.3), which has no backreferences and no"
          + " lookaround. That is the price of a guaranteed linear-time match");
      return Optional.empty();
    }
  }

  /**
   * Compiles a range constraint, validating that its bounds are orderable.
   *
   * @param rule the enclosing rule
   * @param pattern the enclosing pattern
   * @param constraint the constraint
   * @return the compiled test, or empty if a bound was invalid
   */
  private Optional<AlphaTest> compileRange(final RuleDefinition rule,
      final PatternDefinition pattern, final RangeConstraint constraint) {
    final String where = rule.id() + ": " + pattern.alias() + "." + constraint.field();
    final Optional<JsonPointer> compiled = compilePath(where, constraint.field());
    if (compiled.isEmpty()) {
      return Optional.empty();
    }
    final JsonPointer path = compiled.get();
    record(rule.id(), pattern.factType(), path);
    // Both bounds are literals of the field's own type, so both get §6.5's check. The operator
    // reported is the one the bound expresses, so the message names what the author wrote.
    constraint.lower().ifPresent(bound -> checkLiteralType(where, pattern.factType(),
        constraint.field(), constraint.lowerInclusive() ? Operator.GTE : Operator.GT, bound));
    constraint.upper().ifPresent(bound -> checkLiteralType(where, pattern.factType(),
        constraint.field(), constraint.upperInclusive() ? Operator.LTE : Operator.LT, bound));
    boolean valid = true;
    for (final Optional<JsonNode> maybeBound : List.of(constraint.lower(), constraint.upper())) {
      if (maybeBound.isPresent()) {
        final JsonNode bound = maybeBound.get();
        if (!bound.isNumber() && !bound.isTextual()) {
          diagnostics.add(where + ": range bounds must be numbers or strings, got "
              + bound.getNodeType() + ". Ordering is defined within a type-compatibility class"
              + " only (spec section 2.6.1)");
          valid = false;
        }
      }
    }
    return valid
        ? Optional.of(new RangeTest(constraint, new JsonPointerAccessor(path)))
        : Optional.empty();
  }

  /**
   * Resolves a cross-fact reference against the aliases bound so far.
   *
   * @param rule the enclosing rule
   * @param pattern the enclosing pattern
   * @param constraint the constraint
   * @param aliasPositions every alias the rule binds, mapped to its position
   * @param aliasTypes every fact type the rule binds, indexed by position
   * @return the compiled join, or empty if the reference did not resolve
   */
  private Optional<JoinTest> compileJoin(final RuleDefinition rule,
      final PatternDefinition pattern, final JoinConstraint constraint,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes) {
    final String where = rule.id() + ": " + pattern.alias() + "." + constraint.field();
    switch (constraint.op()) {
      case MATCHES -> {
        // A join's "literal" is the other fact's value, and MATCHES needs a pattern compiled at
        // rule-compile time. Left to run, it throws from inside the matcher at fire time -- a
        // compile-time-detectable authoring error escaping to production.
        diagnostics.add(where + ": matches cannot be used as a join operator, because the pattern"
            + " would have to be compiled from another fact's value at fire time");
        return Optional.empty();
      }
      case HAS_FIELD, IS_NULL -> {
        // Both read their polarity from a boolean literal. Against another fact's value that is
        // not wrong so much as meaningless, and it fails silently rather than loudly.
        diagnostics.add(where + ": " + constraint.op() + " is a single-fact test and cannot be used"
            + " as a join operator; it carries its polarity in a boolean literal, which a $ref"
            + " cannot supply");
        return Optional.empty();
      }
      default -> {
        // Comparisons and membership are all meaningful across two facts.
      }
    }
    final Integer other = aliasPositions.get(constraint.otherAlias());
    if (other == null) {
      diagnostics.add(where + ": $ref names alias '" + constraint.otherAlias()
          + "', which is not bound by this rule");
      return Optional.empty();
    }
    if (other >= aliasPositions.get(pattern.alias())) {
      diagnostics.add(where + ": $ref names alias '" + constraint.otherAlias()
          + "', which is bound later. Every reference must resolve to an earlier alias, which is"
          + " what keeps the join graph acyclic (spec section 6.5)");
      return Optional.empty();
    }
    /*
     * Both sides are attempted before either is checked, so a join with a bad path at each end
     * reports both.
     *
     * The far side keeps the NEAR side's prefix and names itself in the text. The prefix is not
     * decoration: the DSL locates a compiler diagnostic by matching it against
     * "<ruleId>: <alias>.<field>", and the only key it holds for this edge is the one the author
     * wrote the $ref on. Re-prefixing with the far alias produced a better sentence attached to no
     * line, which is the worse trade -- the $ref IS written on the near side's line.
     */
    final Optional<JsonPointer> compiled = compilePath(where, constraint.field());
    final Optional<JsonPointer> compiledOther = compilePath(
        where + ": $ref target '" + constraint.otherAlias() + "." + constraint.otherField() + "'",
        constraint.otherField());
    if (compiled.isEmpty() || compiledOther.isEmpty()) {
      return Optional.empty();
    }
    final JsonPointer path = compiled.get();
    final JsonPointer otherPath = compiledOther.get();
    // Both sides are read by the network, so both are tested paths, on their own types. Recording
    // only this side would make an update to the other side of a join look like a no-op.
    record(rule.id(), pattern.factType(), path);
    record(rule.id(), aliasTypes.get(other), otherPath);
    return Optional.of(new JoinTest(constraint, new JsonPointerAccessor(path), other,
        new JsonPointerAccessor(otherPath)));
  }

  /**
   * Validates that every action names something that exists.
   *
   * @param rule the rule
   * @param lhsAliases the aliases the left-hand side binds
   */
  private void validateActions(final RuleDefinition rule, final Set<String> lhsAliases) {
    final Set<String> bound = new LinkedHashSet<>(lhsAliases);
    for (final ActionDefinition action : rule.then()) {
      switch (action) {
        case SetField setField -> {
          requireAlias(rule, bound, setField.targetAlias(), "setField target");
          requireValue(rule, bound, setField.value());
        }
        case InsertFact insert -> {
          insert.payload().forEach(field -> requireValue(rule, bound, field.value()));
          insert.alias().ifPresent(alias -> {
            if (!bound.add(alias)) {
              diagnostics.add(rule.id() + ": insertFact binds alias '" + alias
                  + "', which is already bound");
            }
          });
        }
        case RetractFact retract ->
            requireAlias(rule, bound, retract.targetAlias(), "retractFact target");
        case Emit emit -> {
          if (emit.eventType().isBlank()) {
            diagnostics.add(rule.id() + ": emit needs an event type");
          }
          emit.payload().forEach(field -> requireValue(rule, bound, field.value()));
        }
        case CallFunction call -> {
          options.declaredFunctions().ifPresent(declared -> {
            if (!declared.contains(call.name())) {
              diagnostics.add(rule.id() + ": callFunction names '" + call.name()
                  + "', which is not registered. Known functions: " + new TreeSet<>(declared));
            }
          });
          call.args().forEach(field -> requireValue(rule, bound, field.value()));
        }
      }
    }
  }

  /**
   * Records a diagnostic if an alias is not bound at this point in the action list.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param alias the alias to check
   * @param what a description of where the alias appeared
   */
  private void requireAlias(final RuleDefinition rule, final Set<String> bound, final String alias,
      final String what) {
    if (!bound.contains(alias)) {
      diagnostics.add(rule.id() + ": " + what + " names alias '" + alias
          + "', which is not bound by 'when' or by an earlier insertFact");
    }
  }

  /**
   * Records a diagnostic if a value expression references an unbound alias.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param value the expression to check
   */
  private void requireValue(final RuleDefinition rule, final Set<String> bound,
      final ValueExpr value) {
    switch (value) {
      case Literal ignored -> {
        // A constant references nothing.
      }
      case FieldRef ref -> requireAlias(rule, bound, ref.alias(), "$ref");
    }
  }

  /**
   * Records one tested path against a rule and a fact type, and into the inverse index.
   *
   * @param ruleId the rule that reads it
   * @param factType the type it is read on
   * @param path the path
   */
  private void record(final String ruleId, final String factType, final JsonPointer path) {
    pathsByType.computeIfAbsent(factType, ignored -> new LinkedHashSet<>()).add(path);
    pathsByRule.computeIfAbsent(ruleId, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(factType, ignored -> new LinkedHashSet<>()).add(path);
    inverse.computeIfAbsent(factType, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(ruleId);
  }

  /**
   * Rejects a comparison a registered schema proves can never be true (§6.5, §2.3, §2.6.1).
   *
   * <p>§2.6.1 sanctions exactly this: "cross-type comparison is {@code false} at runtime, but a
   * compile error wherever a schema can prove it." The proof obligation is the whole of the design
   * here, and it is narrower than "the literal is not of the declared type".
   *
   * <p><strong>Only where wrong type means false.</strong> §2.6.1's {@code present, wrong type} row
   * gives {@code NE} and {@code NOT_IN} the value <strong>true</strong>, because they are
   * {@code !EQ} and {@code !IN}. A wrong-typed literal there does not make the rule unmatchable --
   * it makes the constraint vacuously satisfied, which is a different mistake, reported as a
   * warning by {@code ReportBuilder} rather than failing the build. Erroring on it would reject a
   * rule that matches everything, with a message saying it matches nothing.
   *
   * <p><strong>Class, not JSON Schema type.</strong> See {@link SchemaType#comparableWith}: a field
   * declared {@code integer} compared against {@code 99.5} is a legitimate comparison that
   * {@code 100} satisfies.
   *
   * <p><strong>{@code IN} needs every element to fail.</strong> §2.6.1 defines it as {@code EQ}
   * against each element, so one incompatible entry in {@code ["OPEN", 1]} is dead weight, not a
   * defect -- the compatible entries still match.
   *
   * @param where the diagnostic prefix identifying the constraint
   * @param factType the type the pattern matches
   * @param field the dotted field path
   * @param operator the comparison
   * @param literal the literal, or an array of them for {@code IN}
   */
  private void checkLiteralType(final String where, final String factType, final String field,
      final Operator operator, final JsonNode literal) {
    final Optional<SchemaType> declared = options.factSchemas().typeOf(factType, field);
    if (declared.isEmpty()) {
      return;
    }
    final SchemaType type = declared.get();
    switch (operator) {
      case EQ, GT, GTE, LT, LTE -> {
        if (!type.comparableWith(literal)) {
          reportIncomparable(where, factType, field, type, operator.name(), literal);
        }
      }
      case MATCHES -> {
        if (type != SchemaType.STRING) {
          diagnostics.add(where + ": " + factType + "." + field + " is declared "
              + type.name().toLowerCase(Locale.ROOT)
              + ", and matches compares a string against a pattern. This rule would compile and"
              + " never match (§2.6.1)");
        }
      }
      case IN -> {
        if (literal.isArray() && !literal.isEmpty() && !anyComparable(type, literal)) {
          reportIncomparable(where, factType, field, type, "IN", literal);
        }
      }
      /*
       * NE and NOT_IN are !EQ and !IN, so §2.6.1 makes them TRUE against a wrong-typed value.
       * HAS_FIELD and IS_NULL carry a boolean polarity rather than a value of the field's type.
       * None of the four can be proved unmatchable by a type, which is all this check may act on.
       */
      case NE, NOT_IN, HAS_FIELD, IS_NULL -> { }
    }
  }

  /**
   * Whether any candidate in an {@code IN} list could compare equal.
   *
   * @param type the declared type
   * @param candidates the array literal
   * @return true when at least one element shares the field's compatibility class
   */
  private static boolean anyComparable(final SchemaType type, final JsonNode candidates) {
    for (final JsonNode candidate : candidates) {
      if (type.comparableWith(candidate)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Records a comparison the schema proves can never be true.
   *
   * @param where the diagnostic prefix
   * @param factType the fact type
   * @param field the field path
   * @param type the declared type
   * @param operator the operator, as the author would recognise it
   * @param literal the offending literal
   */
  private void reportIncomparable(final String where, final String factType, final String field,
      final SchemaType type, final String operator, final JsonNode literal) {
    diagnostics.add(where + ": " + factType + "." + field + " is declared "
        + type.name().toLowerCase(Locale.ROOT) + ", which §2.6.1 compares only within "
        + type.compatibilityClass() + ", so it can never " + operator.toLowerCase(Locale.ROOT)
        + " " + literal + ". This rule would compile and never match (§2.3)");
  }

  /**
   * Whether an operator is one of the four §2.6.1 orders.
   *
   * @param operator the operator
   * @return true for {@code GT}, {@code GTE}, {@code LT} and {@code LTE}
   */
  private static boolean isOrdering(final Operator operator) {
    return operator == Operator.GT || operator == Operator.GTE
        || operator == Operator.LT || operator == Operator.LTE;
  }

  /**
   * Compiles a dotted field path into a pointer, or records why it could not be.
   *
   * <p>{@code Paths.compile} throws on a malformed path -- an empty segment, as in {@code a..b} --
   * and every caller here used to let that propagate. It is the one validation failure in this
   * compiler that escaped as a raw {@link IllegalArgumentException} rather than joining the
   * diagnostics, which broke two contracts at once: the caller sees an exception type the API does
   * not document, and compilation dies on the first bad path instead of reporting every problem in
   * the batch.
   *
   * <p>It surfaced through the Phase 5 DSL, where a mistyped {@code where} key defeated "every
   * diagnostic names a file, line and column". The guard belongs here rather than in that front
   * end, because a rule built in Java reached the same throw.
   *
   * @param where the diagnostic prefix identifying the constraint
   * @param dotted the field path in DSL form
   * @return the compiled pointer, or empty when the path was malformed
   */
  private Optional<JsonPointer> compilePath(final String where, final String dotted) {
    try {
      return Optional.of(Paths.compile(dotted));
    } catch (final IllegalArgumentException malformed) {
      diagnostics.add(where + ": " + malformed.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Renders one rule in a form that is stable across JVM runs.
   *
   * <p><strong>Do not derive a content hash from {@code Record::toString}.</strong> That was the
   * first implementation and it was wrong in a way that no same-JVM test can catch: a record's
   * generated {@code toString} renders its components as they iterate, and
   * {@link RuleDefinition#tags()} holds a {@code Set.copyOf} result, whose iteration order Java
   * randomises per JVM with a per-process salt. Any rule with two or more tags therefore hashed
   * differently on every run of the same rules -- and that string is stamped into every fire result
   * and every emitted event, so §5.6's "which rules produced this decision, months later" and the
   * run-both-and-compare cutover both quietly stopped working.
   *
   * <p>So every unordered collection is sorted here, and everything else is rendered in a fixed
   * order. Anything added to {@link RuleDefinition} must be added here too.
   *
   * @param rule the definition
   * @return a stable rendering
   */
  private static String canonicalise(final RuleDefinition rule) {
    return new StringBuilder()
        .append("id=").append(rule.id())
        .append(" salience=").append(rule.salience())
        .append(" noLoop=").append(rule.noLoop())
        .append(" agendaGroup=").append(rule.agendaGroup().orElse(""))
        .append(" tags=").append(new TreeSet<>(rule.tags()))
        .append(" when=").append(rule.when())
        .append(" then=").append(rule.then())
        .toString();
  }

  /**
   * The rule set's identity: a content hash of the definitions plus the compiler version.
   *
   * <p>Stamped into every fire result and every emitted event, so that "which rules produced this
   * decision" is answerable months later. Including the compiler version is what keeps a change in
   * compilation semantics from producing the same hash for different behaviour.
   *
   * @param rules the definitions, in compilation order
   * @return a short, stable version string
   */
  private static String version(final List<RuleDefinition> rules) {
    final StringBuilder canonical = new StringBuilder(COMPILER_VERSION);
    for (final RuleDefinition rule : rules) {
      canonical.append('\n').append(canonicalise(rule));
    }
    try {
      final byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest, 0, 8);
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
    }
  }
}
