package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.expr.CompiledExpression;
import com.codeheadsystems.rules.expr.ExpressionCompilationException;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.rule.Accumulate;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.AggregateFunction;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.CompiledAccumulate;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionTest;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.FieldTest;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RangeTest;
import com.codeheadsystems.rules.rule.RegexTest;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import com.codeheadsystems.rules.runtime.DefaultCompiledRuleSet;
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.session.CompiledRuleSet;
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
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

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

  /**
   * Which {@code (ruleId, factType)} pairs acquired a payload-root tested path from a §6.4
   * condition rather than from a constraint the author wrote.
   *
   * <p>Passed to {@link ReportBuilder} so §7.4's shallow-tested-path warning can tell the two
   * apart: a root the author wrote is actionable advice, a root this compiler inserted is a warning
   * nobody can clear. {@code TestedPaths} deliberately does not carry this -- it is frozen into the
   * compiled rule set and read on the update hot path, and provenance has no runtime consumer.
   *
   * <p>Threaded rather than reconstructed in the report builder. Reconstructing it there means the
   * same predicate written twice in two files with nothing tying them together, and CLAUDE.md names
   * that failure directly: duplicating one gate in another is how they drift apart. Concretely --
   * if the §6.4 amendment's door to real read paths is ever taken, a reconstruction keyed on "does
   * this rule have a condition" would keep suppressing, and the *authored* root warning would
   * vanish for any rule that also carries one.
   */
  private final Set<String> conditionRoots = new LinkedHashSet<>();

  /**
   * Whether an author wrote a whole-payload constraint, as opposed to the compiler inserting one.
   *
   * <p>What makes the suppression exact rather than a trade: subtracted from
   * {@link #conditionRoots} by {@link #suppressibleRoots()}, so a rule that both writes a root and
   * carries a condition on the same type keeps its warning.
   */
  private final Set<String> authoredRoots = new LinkedHashSet<>();

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
        ReportBuilder.build(compiled, network, version, options, suppressibleRoots()),
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
    /** Aliases of quantified patterns, none of which takes a tuple position. */
    final Map<String, Quantifier> nonBindingAliases = new LinkedHashMap<>();
    /*
     * Positions are assigned to POSITIVE patterns only, and that is the whole trick that keeps the
     * quantifiers from touching the rest of the engine. A NOT_EXISTS or FOR_ALL pattern binds no
     * alias into the tuple, so giving it a position would make every downstream consumer -- the
     * join planner, the join walk, the streaming matcher's pattern sites, the explainer -- have to
     * know to skip it. Instead they read `patterns`, which contains only the ones that produce
     * bindings, and the quantified ones are compiled separately against these same positions.
     */
    int position = 0;
    for (final PatternDefinition pattern : rule.when()) {
      /*
       * Exhaustive and without a default, so adding a Quantifier constant fails THIS compile rather
       * than shipping. An `== ACCUMULATE` test would let a new constant through as an ordinary
       * binding pattern with no diagnostic at all, which is the failure mode the compiler exists to
       * prevent -- a quantifier nobody implemented, silently matching.
       */
      switch (pattern.quantifier()) {
        case EXISTS_AT_LEAST_ONE, NOT_EXISTS, FOR_ALL, ACCUMULATE -> {
          // All implemented; the partition below decides where each one lands. Exhaustive and
          // without a default, so adding a constant fails this compile rather than shipping.
        }
      }
      /*
       * The accumulate spec and the quantifier have to agree, in both directions. A pattern
       * carrying one without the quantifier would compile and never fold; a quantifier without one
       * has nothing to compute. Refusing both by name costs nothing and saves an author staring at
       * a rule that looks like it aggregates.
       */
      if (pattern.accumulate().isPresent() != (pattern.quantifier() == Quantifier.ACCUMULATE)) {
        diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "': "
            + (pattern.accumulate().isPresent()
                ? "an 'accumulate' block needs quantifier: accumulate"
                : "quantifier: accumulate needs an 'accumulate' block saying what to compute"));
      }
      if (takesNoPosition(pattern.quantifier())) {
        /*
         * A quantified alias is still checked for duplication, because "bound twice" would be just
         * as confusing here -- but it is recorded in a separate map, since nothing may reference it.
         * The pattern itself is compiled after this loop, when every positive position is known: a
         * quantified pattern may join against any positive alias, including ones declared after it.
         */
        if (nonBindingAliases.put(pattern.alias(), pattern.quantifier()) != null
            || aliasPositions.containsKey(pattern.alias())) {
          diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "' is bound twice");
        }
        /*
         * A §6.4 condition on a quantified pattern is refused rather than ignored. It would compile
         * -- ExpressionConstraint is a Constraint like any other -- and then never run, because the
         * post-filter that evaluates conditions walks the rule's POSITIVE patterns and a quantified
         * pattern is answered by its alpha and join tests alone. It would silently be broader than
         * written, which loses a firing quietly. Refusing costs little: such a condition cannot
         * reference the quantified alias anyway, since only bound aliases are declared to the
         * expression compiler.
         *
         * It costs a FOR_ALL more than a NOT_EXISTS, and that is worth knowing: an expression is
         * exactly how an author would want to write a requirement the operator set cannot express.
         * §2.5's amendment records it as the quantifier's sharpest limit rather than leaving it to
         * be discovered through this diagnostic.
         */
        for (final Constraint constraint : pattern.constraints()) {
          if (constraint instanceof ExpressionConstraint) {
            diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "': a condition on a "
                + pattern.quantifier() + " pattern is not supported. Express it with the pattern's"
                + " own constraints, which are what the quantifier is asked of");
          }
        }
        continue;
      }
      if (aliasPositions.putIfAbsent(pattern.alias(), position) != null) {
        diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "' is bound twice");
      }
      aliasTypes.add(pattern.factType());
      position++;
    }

    final List<CompiledPattern> patterns = new ArrayList<>(aliasTypes.size());
    final List<CompiledPattern> negations = new ArrayList<>();
    final List<CompiledPattern> universals = new ArrayList<>();
    final List<CompiledAccumulate> accumulates = new ArrayList<>();
    int positive = 0;
    for (final PatternDefinition pattern : rule.when()) {
      if (takesNoPosition(pattern.quantifier())) {
        /*
         * Compiled at a notional position AFTER every positive one, which is not a trick: the third
         * argument only decides which earlier same-type positions this pattern's fact must differ
         * from, and a quantified candidate must differ from all of them. "No OTHER order for this
         * customer", and "every OTHER order is shipped", are what an author means by a quantified
         * pattern of a type the rule already binds, and it is the same rule §1 states for two
         * positive aliases.
         */
        final CompiledPattern compiled = compilePattern(rule, pattern, aliasTypes.size(),
            aliasPositions, aliasTypes, nonBindingAliases);
        switch (pattern.quantifier()) {
          case NOT_EXISTS -> negations.add(compiled);
          case FOR_ALL -> universals.add(compiled);
          case ACCUMULATE -> compileAccumulate(rule, pattern, compiled)
              .ifPresent(accumulates::add);
          case EXISTS_AT_LEAST_ONE -> throw new IllegalStateException("positive pattern here");
        }
      } else {
        patterns.add(compilePattern(rule, pattern, positive, aliasPositions, aliasTypes,
            nonBindingAliases));
        positive++;
      }
    }
    if (patterns.isEmpty()) {
      /*
       * A rule of nothing but quantified patterns has no tuple to attach them to. It would "match"
       * once, against the empty binding, which is a semantics nobody asked for and §2.5 does not
       * define. For FOR_ALL it would be worse than undefined: the quantifier is vacuously true over
       * an empty scope, so such a rule would fire on an empty working memory.
       */
      diagnostics.add(rule.id() + ": no pattern binds a fact; a rule needs at least one pattern"
          + " that is not NOT_EXISTS, FOR_ALL or ACCUMULATE");
    }

    validateActions(rule, aliasPositions.keySet(), nonBindingAliases);

    /*
     * Aliases an insertFact introduces count as bound for an expression, exactly as they do for a
     * $ref: §6.2.2 allocates the handle at stage time so a later action in the same right-hand side
     * can name it, and an expression is a later action's value like any other.
     */
    final Map<String, CompiledExpression> valueExpressions =
        compileValueExpressions(rule, aliasPositions.keySet(), nonBindingAliases);

    if (diagnostics.size() != before) {
      return Optional.empty();
    }
    return Optional.of(new CompiledRule(
        rule.id(), rule.salience(), rule.noLoop(), rule.agendaGroup(), patterns, negations,
        universals, accumulates, rule.then(),
        pathsByRule.getOrDefault(rule.id(), Map.of()),
        valueExpressions, rule));
  }

  /**
   * Compiles one pattern's constraints and derives its implicit inequalities.
   *
   * @param rule the enclosing rule
   * @param pattern the pattern
   * @param position the pattern's position in the rule
   * @param aliasPositions every alias the rule binds, mapped to its position
   * @param aliasTypes every fact type the rule binds, indexed by position
   * @param nonBindingAliases the aliases of quantified patterns that bind nothing, by quantifier
   * @return the compiled pattern
   */
  private CompiledPattern compilePattern(final RuleDefinition rule,
      final PatternDefinition pattern, final int position,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes,
      final Map<String, Quantifier> nonBindingAliases) {
    final List<AlphaTest> alphaTests = new ArrayList<>();
    final List<JoinTest> joinTests = new ArrayList<>();
    final List<ExpressionTest> expressionTests = new ArrayList<>();

    for (final Constraint constraint : pattern.constraints()) {
      switch (constraint) {
        case JoinConstraint join ->
            compileJoin(rule, pattern, position, join, aliasPositions, aliasTypes, nonBindingAliases)
                .ifPresent(joinTests::add);
        case ExpressionConstraint expression ->
            compileCondition(rule, pattern, expression, aliasPositions, aliasTypes,
                nonBindingAliases).ifPresent(expressionTests::add);
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
        expressionTests,
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
      /*
       * Join-only, and refused here rather than quietly ignored. A temporal relation is between two
       * FACTS -- "this payment within 24 hours after that order" -- and a literal on one side has
       * no window to be within. Comparing a timestamp to a constant is what gt and lt are for, and
       * saying so points the author at an operator that exists rather than at a shape that does not.
       */
      case AFTER, BEFORE -> {
        diagnostics.add(where + ": " + constraint.op() + " relates two facts and needs a $ref on"
            + " the other side; to compare a time against a fixed value, use gt or lt");
        yield Optional.empty();
      }
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
    if (!constraint.literal().isString()) {
      diagnostics.add(where + ": matches expects a string pattern, got "
          + constraint.literal().getNodeType());
      return Optional.empty();
    }
    try {
      return Optional.of(
          new RegexTest(constraint, accessor, Pattern.compile(constraint.literal().stringValue())));
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
        if (!bound.isNumber() && !bound.isString()) {
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
      final PatternDefinition pattern, final int position, final JoinConstraint constraint,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes,
      final Map<String, Quantifier> nonBindingAliases) {
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
    if (!temporalWindowIsWellFormed(where, constraint)) {
      return Optional.empty();
    }
    final Integer other = aliasPositions.get(constraint.otherAlias());
    if (other == null) {
      // Three cases, not two: a NOT_EXISTS alias exists but binds nothing, and saying it is not
      // bound by the rule sends an author looking for a typo that is not there. Same reasoning as
      // the "bound later" branch below.
      diagnostics.add(where + ": $ref names alias '" + constraint.otherAlias() + "', which "
          + (nonBindingAliases.containsKey(constraint.otherAlias())
              ? bindsNothing(nonBindingAliases.get(constraint.otherAlias()))
              : "is not bound by this rule"));
      return Optional.empty();
    }
    if (other >= position) {
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
   * Compiles what an accumulate computes, on top of the pattern that selects its scope.
   *
   * @param rule the rule being compiled
   * @param pattern the source pattern
   * @param scope its compiled scope
   * @return the compiled accumulate, or empty when it was rejected
   */
  private Optional<CompiledAccumulate> compileAccumulate(final RuleDefinition rule,
      final PatternDefinition pattern, final CompiledPattern scope) {
    final Optional<Accumulate> source = pattern.accumulate();
    if (source.isEmpty()) {
      // Already reported by the quantifier/spec agreement check; nothing to add.
      return Optional.empty();
    }
    final Accumulate accumulate = source.get();
    final String where = rule.id() + ": " + pattern.alias();
    if (!havingIsUsable(accumulate, where)) {
      return Optional.empty();
    }
    /*
     * COUNT asks about the facts, not about anything in them, so a field on it is refused rather
     * than ignored -- an author who writes count(total) means something, and it is not what COUNT
     * does. Every other function needs one for the same reason in reverse.
     */
    if (accumulate.function() == AggregateFunction.COUNT) {
      if (accumulate.field().isPresent()) {
        diagnostics.add(where + ": count takes no field; it counts the facts in scope, and a field"
            + " would suggest it skips the ones without that field");
        return Optional.empty();
      }
      return Optional.of(new CompiledAccumulate(pattern.alias(), scope, accumulate.function(),
          Optional.empty(), accumulate.having()));
    }
    if (accumulate.field().isEmpty()) {
      diagnostics.add(where + ": " + accumulate.function().name().toLowerCase(Locale.ROOT)
          + " needs a field to fold over");
      return Optional.empty();
    }
    final Optional<JsonPointer> path = compilePath(where, accumulate.field().get());
    if (path.isEmpty()) {
      return Optional.empty();
    }
    /*
     * Recorded as a tested path of the accumulated type, and it has to be. §3.4.1's update gate is
     * upstream of every matcher, so a change to a field only an accumulate reads would propagate
     * nothing and the rule would go on firing with a stale total -- the same defect CLAUDE.md
     * records from the §6.4 condition case, where no differential test could catch it because every
     * matcher was identically wrong.
     */
    record(rule.id(), scope.factType(), path.get());
    return Optional.of(new CompiledAccumulate(pattern.alias(), scope, accumulate.function(),
        Optional.of(new JsonPointerAccessor(path.get())), accumulate.having()));
  }

  /**
   * Renders a compiled pointer back as the dotted path an author wrote.
   *
   * <p>A diagnostic that echoes {@code units/value} at somebody who typed {@code units.value} makes
   * them look for a path they do not have. Everything else in this compiler quotes the author's own
   * spelling back at them; this is what lets the one reference diagnostic built from a compiled
   * pointer do the same.
   *
   * @param path the compiled pointer, which must address a field rather than the root -- the one
   *     caller checks that before asking, and the {@code substring} below would throw on an empty
   *     one
   * @return the dotted form, without a leading separator
   */
  private static String dotted(final JsonPointer path) {
    return path.toString().replace('/', '.').substring(1);
  }

  /**
   * Whether a join's temporal window agrees with its operator.
   *
   * <p>Required for {@link Operator#AFTER} and {@link Operator#BEFORE}, refused for everything
   * else, and both directions matter. An unbounded {@code after} is {@code gt} against a
   * {@code $ref} and the language already has it, so allowing the window to be omitted would add a
   * second spelling for an existing operator instead of the one thing no pair of comparisons can
   * state. A window on a non-temporal operator would be a value the matcher silently never reads.
   *
   * <p>The bound must be a <em>positive</em> number. Negative would invert the relation into one
   * the operator's own name contradicts, and there is a spelling for that already: the other
   * operator. Zero is empty by construction -- the near edge is strict -- so it compiles a rule
   * that can never fire and says nothing.
   *
   * @param where the diagnostic prefix
   * @param constraint the join being compiled
   * @return whether the pairing is legal
   */
  private boolean temporalWindowIsWellFormed(final String where, final JoinConstraint constraint) {
    final boolean temporal =
        constraint.op() == Operator.AFTER || constraint.op() == Operator.BEFORE;
    if (!temporal) {
      if (constraint.within().isPresent()) {
        diagnostics.add(where + ": 'within' bounds a temporal relation, and " + constraint.op()
            + " is not one; only after and before take it");
        return false;
      }
      return true;
    }
    if (constraint.within().isEmpty()) {
      diagnostics.add(where + ": " + constraint.op() + " needs a 'within' bound. An unbounded"
          + " ordering is gt or lt against the same $ref, which this language already has");
      return false;
    }
    final JsonNode window = constraint.within().orElseThrow();
    if (!window.isNumber() || window.decimalValue().signum() <= 0) {
      /*
       * Positive, not merely non-negative. A window of zero is `other < mine <= other` for AFTER
       * and `other <= mine < other` for BEFORE -- empty by construction, whatever the facts -- so
       * accepting it compiles a rule that can never fire and says nothing. §7.4's report has
       * IMPOSSIBLE_RANGE for exactly this shape on a range; refusing outright is the cheaper answer
       * here, because unlike a range there is no reading under which zero was meant.
       */
      /*
       * Reachable for a non-number, and only for a non-number: JoinConstraint normalises a numeric
       * window itself and throws for one it cannot represent, but passes anything else through
       * precisely so this reports it against the author's own line. The two layers own different
       * failures rather than one shadowing the other.
       */
      diagnostics.add(where + ": 'within' is a positive number in the time field's own units, got "
          // A MissingNode renders as the empty string, so the message would name nothing at all --
          // the same shape as the reference diagnostic that shipped reading "also carries .". The
          // node type is what is left to say when the value has no rendering of its own.
          + (window.toString().isEmpty() ? window.getNodeType() : window)
          + (window.isNumber() && window.decimalValue().signum() == 0
              ? ". A window of zero excludes every value: the near edge is strict"
              : ""));
      return false;
    }
    return true;
  }

  /**
   * Whether an accumulate's {@code having} names an operator that can test a bare value.
   *
   * <p><strong>The compiler checks this even though the DSL's operator table already narrows
   * it.</strong> CLAUDE.md's rule is that a gate ahead never excuses a gate behind, and here the
   * consequence is not a loosened constraint but a throw on the matching path: {@code Comparisons}
   * calls {@code asBoolean()} for {@code HAS_FIELD} and {@code IS_NULL} and reaches for a
   * precompiled pattern for {@code MATCHES}, and Jackson 3's accessors throw on a type mismatch
   * rather than answering false. An {@code AggregateTest} is the third position in the engine that
   * reaches {@code Comparisons.test}, and the other two are guarded here for exactly this reason.
   *
   * <p>{@code IN} and {@code NOT_IN} do not throw but are refused anyway: membership of a set is a
   * question about a field's value against a list, and a fold answers one number.
   *
   * @param accumulate the source spec
   * @param where the diagnostic prefix
   * @return whether the test is safe to evaluate, true when there is no test
   */
  private boolean havingIsUsable(final Accumulate accumulate, final String where) {
    if (accumulate.having().isEmpty()) {
      return true;
    }
    final Operator op = accumulate.having().get().op();
    return switch (op) {
      case EQ, NE, GT, GTE, LT, LTE -> true;
      default -> {
        diagnostics.add(where + ": 'having' cannot use " + op + "; a fold answers one value, so"
            + " only EQ, NE, GT, GTE, LT and LTE apply");
        yield false;
      }
    };
  }

  /**
   * Whether a quantifier makes its pattern contribute no binding to the tuple.
   *
   * <p>The single question the two compilation loops branch on, asked in one place so they cannot
   * disagree about it -- the first decides which aliases go in {@code nonBindingAliases} and the
   * second decides which list a compiled pattern lands in, and a quantifier that was non-binding to
   * one and binding to the other would produce an alias nothing can reference occupying a tuple
   * position.
   *
   * @param quantifier the pattern's quantifier
   * @return whether the pattern binds no fact
   */
  private static boolean bindsNoFact(final Quantifier quantifier) {
    return quantifier == Quantifier.NOT_EXISTS || quantifier == Quantifier.FOR_ALL;
  }

  /**
   * Whether a quantifier makes its pattern take no tuple position.
   *
   * <p>Wider than {@link #bindsNoFact}, and the gap between them is {@code ACCUMULATE}. All three
   * are absent from {@code patterns} and compile against the positive positions rather than into
   * them -- but a negation and a universal bind <em>nothing</em>, where an accumulate binds a
   * value. So they share a compilation path and differ in what may reference them, which is why the
   * compiler keeps two questions rather than one.
   *
   * @param quantifier the pattern's quantifier
   * @return whether the pattern contributes no tuple position
   */
  private static boolean takesNoPosition(final Quantifier quantifier) {
    return bindsNoFact(quantifier) || quantifier == Quantifier.ACCUMULATE;
  }

  /**
   * How to say that an alias names a pattern binding no fact.
   *
   * <p>Three places ask this and a fourth phrases it inline, and all of them are answering the same
   * authoring mistake: a name the author can see written in {@code when}, reported as one the rule
   * does not have, sends them hunting a typo that is not there. The quantifier is named because the
   * two reasons differ -- a negation has no fact <em>because</em> it asserts there is none, while a
   * universal has none because it speaks about a whole scope rather than a member of it -- and an
   * author who reaches for the alias is reasoning from one of those two pictures.
   *
   * @param quantifier the quantifier the alias's pattern carries
   * @return the clause, ready to follow "which "
   */
  private static String bindsNothing(final Quantifier quantifier) {
    return switch (quantifier) {
      case NOT_EXISTS ->
          "is a NOT_EXISTS pattern. A negated pattern binds no fact, so nothing can reference it";
      case FOR_ALL -> "is a FOR_ALL pattern. A universal pattern asserts something about every fact"
          + " in its scope rather than binding one, so nothing can reference it";
      /*
       * The one of the three that says "not HERE" rather than "not at all", and it reaches three
       * positions that each fail for the same reason: a join has no fact on this side, a setField
       * has nothing to write to, a retractFact has nothing to remove. Reporting it as
       * unreferenceable would send an author to delete a binding they are right to want, so the
       * message names what does work instead.
       */
      case ACCUMULATE -> "is an ACCUMULATE pattern, which binds a value rather than a fact -- so"
          + " there is nothing here to write to, retract, or join against. Reading the answer is"
          + " fine: name it in an action's value, in a §6.4 expression, or test it with the"
          + " accumulate's own 'having'";
      case EXISTS_AT_LEAST_ONE -> "is not bound by this rule";
    };
  }

  /**
   * Validates that every action names something that exists.
   *
   * @param rule the rule
   * @param lhsAliases the aliases the left-hand side binds
   * @param nonBindingAliases the aliases of the quantified patterns that bind nothing, by
   *     quantifier. They are neither bound nor absent -- a distinction the diagnostic has to make,
   *     and the quantifier is carried because the two say why differently
   */
  private void validateActions(final RuleDefinition rule, final Set<String> lhsAliases,
      final Map<String, Quantifier> nonBindingAliases) {
    final Set<String> bound = new LinkedHashSet<>(lhsAliases);
    for (final ActionDefinition action : rule.then()) {
      switch (action) {
        case SetField setField -> {
          requireAlias(rule, bound, nonBindingAliases, setField.targetAlias(), "setField target");
          requireValue(rule, bound, nonBindingAliases, setField.value());
        }
        case InsertFact insert -> {
          insert.payload().forEach(
              field -> requireValue(rule, bound, nonBindingAliases, field.value()));
          insert.alias().ifPresent(alias -> {
            /*
             * A negated alias is checked BEFORE `bound`, because it is in neither set: it names no
             * fact, so it was never added to `bound`, and an unguarded add would therefore succeed
             * and quietly hand the name to the inserted fact. Every later action naming it would
             * then resolve -- to the opposite of what the rule says, a fact this rule created
             * standing in for one whose absence it asserted.
             */
            if (nonBindingAliases.containsKey(alias)) {
              diagnostics.add(rule.id() + ": insertFact binds alias '" + alias
                  + "', which names a " + nonBindingAliases.get(alias) + " pattern. One name cannot"
                  + " mean both what this rule quantifies over and the fact this action creates");
            } else if (!bound.add(alias)) {
              diagnostics.add(rule.id() + ": insertFact binds alias '" + alias
                  + "', which is already bound");
            }
          });
        }
        case RetractFact retract ->
            requireAlias(rule, bound, nonBindingAliases, retract.targetAlias(), "retractFact target");
        case Emit emit -> {
          if (emit.eventType().isBlank()) {
            diagnostics.add(rule.id() + ": emit needs an event type");
          }
          emit.payload().forEach(
              field -> requireValue(rule, bound, nonBindingAliases, field.value()));
        }
        case CallFunction call -> {
          options.declaredFunctions().ifPresent(declared -> {
            if (!declared.contains(call.name())) {
              diagnostics.add(rule.id() + ": callFunction names '" + call.name()
                  + "', which is not registered. Known functions: " + new TreeSet<>(declared));
            }
          });
          call.args().forEach(field -> requireValue(rule, bound, nonBindingAliases, field.value()));
        }
      }
    }
  }

  /**
   * Records a diagnostic if an alias is not bound at this point in the action list.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param nonBindingAliases the aliases of quantified patterns that bind nothing, by quantifier
   * @param alias the alias to check
   * @param what a description of where the alias appeared
   */
  private void requireAlias(final RuleDefinition rule, final Set<String> bound,
      final Map<String, Quantifier> nonBindingAliases, final String alias, final String what) {
    if (bound.contains(alias)) {
      return;
    }
    // The same three cases the $ref resolver above distinguishes, for the same reason: an alias
    // the rule plainly writes, reported as one the rule does not have, sends an author looking for
    // a typo that is not there. A negated alias is written in 'when' and binds nothing.
    diagnostics.add(rule.id() + ": " + what + " names alias '" + alias + "', which "
        + (nonBindingAliases.containsKey(alias)
            ? bindsNothing(nonBindingAliases.get(alias))
            : "is not bound by 'when' or by an earlier insertFact"));
  }

  /**
   * Records a diagnostic if a value expression references an unbound alias.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param nonBindingAliases the aliases of quantified patterns that bind nothing, by quantifier
   * @param value the expression to check
   */
  private void requireValue(final RuleDefinition rule, final Set<String> bound,
      final Map<String, Quantifier> nonBindingAliases, final ValueExpr value) {
    switch (value) {
      case Literal ignored -> {
        // A constant references nothing.
      }
      case FieldRef ref -> requireValueAlias(rule, bound, nonBindingAliases, ref, "$ref");
      case ExpressionValue expression -> expression.referencedAliases()
          .forEach(alias -> {
            if (nonBindingAliases.get(alias) != Quantifier.ACCUMULATE) {
              requireAlias(rule, bound, nonBindingAliases, alias, "expression");
            }
          });
    }
  }

  /**
   * Records a diagnostic if a {@code $ref} in a <em>value</em> position names something unreadable.
   *
   * <p><strong>Separate from {@link #requireAlias} because the two positions differ, and an earlier
   * version conflated them.</strong> An accumulate alias may be read -- its answer is a value the
   * right-hand side can use -- but it may not be <em>written to</em> or retracted, because there is
   * no fact behind it. Putting the escape in {@code requireAlias} let a {@code setField} whose
   * target was an accumulate compile clean and then throw at fire time, which is the shape of defect
   * the compiler exists to prevent.
   *
   * <p>A dotted path into an accumulate is refused here too. {@code $ref: units.value} resolves to a
   * missing node and lands in the payload as JSON null, silently -- and it is the natural thing to
   * write, because every other alias in the language needs a field.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param nonBindingAliases the aliases of quantified patterns, by quantifier
   * @param ref the reference to check
   * @param what a description of where it appeared
   */
  private void requireValueAlias(final RuleDefinition rule, final Set<String> bound,
      final Map<String, Quantifier> nonBindingAliases, final FieldRef ref, final String what) {
    if (nonBindingAliases.get(ref.alias()) != Quantifier.ACCUMULATE) {
      requireAlias(rule, bound, nonBindingAliases, ref.alias(), what);
      return;
    }
    // matches() on an empty pointer is Jackson's spelling of "addresses the root", which for a
    // FieldRef means "no field was written". A non-empty path is a dotted reference into a value.
    if (!ref.path().matches()) {
      diagnostics.add(rule.id() + ": " + what + " names '" + ref.alias() + "."
          + dotted(ref.path()) + "', but '" + ref.alias() + "' is an ACCUMULATE pattern: it binds a"
          + " value, not a fact, so it has no fields. Write '" + ref.alias() + "' on its own");
    }
  }

  /**
   * A rule-and-type key for the provenance sets.
   *
   * @param ruleId the rule
   * @param factType the fact type
   * @return a key neither component can collide across, neither being allowed a NUL
   */
  private static String key(final String ruleId, final String factType) {
    return ruleId + '\u0000' + factType;
  }

  /**
   * The {@code (rule, type)} pairs whose payload root came <em>only</em> from a condition.
   *
   * <p>A rule that also wrote {@code field: ""} keeps its shallow-tested-path warning, because that
   * root really is actionable -- the author can constrain a deeper path. Only a root this compiler
   * inserted, which they cannot remove without deleting their condition, is suppressed.
   *
   * @return the suppressible keys
   */
  private Set<String> suppressibleRoots() {
    final Set<String> suppressible = new LinkedHashSet<>(conditionRoots);
    suppressible.removeAll(authoredRoots);
    return suppressible;
  }

  /**
   * Records the payload root for a type a §6.4 condition reads, tagged as compiler-inserted.
   *
   * <p>Separate from {@link #record} rather than a flag on it, so neither has to infer provenance
   * from the path it was handed: reaching {@code record} with a root means the author wrote
   * {@code field: ""}, and reaching here means this compiler inserted one.
   *
   * @param ruleId the rule
   * @param factType the type an alias the condition references binds
   */
  private void recordConditionRoot(final String ruleId, final String factType) {
    // index(), not record(): record() tags any root it sees as authored, and routing through it
    // would make the tag depend on whether the author happened to write their root constraint
    // before or after the condition in the same pattern. Same three insertions, no mis-tagging.
    index(ruleId, factType, JsonPointer.empty());
    conditionRoots.add(key(ruleId, factType));
  }

  /**
   * Records one tested path against a rule and a fact type, and into the inverse index.
   *
   * @param ruleId the rule that reads it
   * @param factType the type it is read on
   * @param path the path
   */
  private void record(final String ruleId, final String factType, final JsonPointer path) {
    if (path.matches()) {
      // Reached with a root only from compileField or compileRange, i.e. the author wrote
      // `field: ""`. Conditions go through recordConditionRoot and never through here.
      authoredRoots.add(key(ruleId, factType));
    }
    index(ruleId, factType, path);
  }

  /**
   * Puts a path into the three tested-path structures, without deciding where it came from.
   *
   * @param ruleId the rule that reads it
   * @param factType the type it is read on
   * @param path the path
   */
  private void index(final String ruleId, final String factType, final JsonPointer path) {
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
      /*
       * Join-only, and never reach a literal. compileField rejects them before a literal can be
       * meaningful, so there is nothing here to type-check -- named rather than left to a default
       * because this switch is a complete enumeration and a constant falling silently out of it is
       * how the next one gets forgotten.
       */
      case AFTER, BEFORE -> { }
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
   * Compiles one pattern condition (§6.4).
   *
   * <p>Three things are checked here rather than left to the expression compiler, because the
   * expression compiler does not know about rules: that a compiler is registered at all, that the
   * expression reads only aliases this rule binds, and that its estimated cost is within
   * {@code CompilerOptions.expressionBudget}.
   *
   * <p>A failure is a diagnostic, never a thrown exception. §6.5 reports every problem in a rule
   * set in one pass, and an expression is the one construct whose compiler is somebody else's code
   * -- letting it abort the batch would make one bad expression hide every other problem in the
   * file.
   *
   * @param rule the enclosing rule
   * @param pattern the enclosing pattern
   * @param constraint the condition as written
   * @param bound every alias the rule binds
   * @return the compiled test, or empty if it was rejected
   */
  private Optional<ExpressionTest> compileCondition(final RuleDefinition rule,
      final PatternDefinition pattern, final ExpressionConstraint constraint,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes,
      final Map<String, Quantifier> nonBindingAliases) {
    /*
     * The tuple's aliases plus any accumulate the rule folds. Declaring the accumulate is what
     * makes a condition able to compare a total against something other than a literal -- `having`
     * takes a literal, and nothing may join to an accumulate alias, so an expression is the only
     * route. RecomputingAgenda.bindingsFor is the other half, and the two have to agree about which
     * names exist or the compiler accepts an expression the matcher cannot resolve.
     */
    final Set<String> bound = new LinkedHashSet<>(aliasPositions.keySet());
    nonBindingAliases.forEach((alias, quantifier) -> {
      if (quantifier == Quantifier.ACCUMULATE) {
        bound.add(alias);
      }
    });
    /*
     * The prefix follows "<rule>: <alias>.<field>", which is what the DSL matches on to put a line
     * number on a compiler diagnostic. Written any other way the message still arrives, but located
     * at the rule rather than at the condition -- and free-form expression text is the construct
     * most in need of a line.
     */
    /*
     * "<rule>: <alias>: condition", with a colon rather than a dot before the last word. The DSL
     * matches this prefix to put a line number on the diagnostic, and a dot would make the key
     * indistinguishable from a field constraint on a field actually named `condition` -- which is
     * not an exotic name, and whose registration would win.
     */
    final String where = rule.id() + ": " + pattern.alias() + ": condition";
    if (!checkAliases(where, constraint.referencedAliases(), bound, nonBindingAliases)) {
      return Optional.empty();
    }
    try {
      final CompiledExpression program =
          options.expressions().compileCondition(constraint.expression(), bound);
      if (!withinBudget(where, constraint.expression(), program)) {
        return Optional.empty();
      }
      /*
       * Every alias the condition reads makes that alias's WHOLE PAYLOAD a tested path, and both
       * halves of that are deliberate.
       *
       * Recording at all: §3.4.1 propagates an update only when it changed a path some rule tests,
       * and a condition reading o.total means the rule tests o.total as surely as `{ gt: 1000 }`
       * would. Until Phase 3 this recorded nothing, so an update that made a condition newly true
       * fired nothing while the equivalent retract-plus-insert fired -- a §9 Phase 1 exit criterion
       * missed for exactly the rules that reached for §6.4's escape hatch. No differential test
       * could find it: the gate runs in DefaultWorkingMemory, upstream of the matcher, so every
       * shape was identically wrong.
       *
       * The root rather than the paths: extracting read paths from the compiled expression is the
       * precise answer and dev.cel exposes the AST for it, but it puts a permanent obligation on
       * this compiler to be a superset of what an arbitrary expression reads -- under-declare by
       * one path and the engine loses a firing silently. That is §11.2's rejected `dependsOn()`
       * trap in miniature, and §11.2's own escape from it was to let a node declare the root and be
       * "instantly correct-but-conservative". The cost lands on the rules that opted in: every
       * update to a fact type carrying a condition propagates. §3.4.2's fast path is untouched,
       * because an identical payload short-circuits before the trie is consulted at all.
       *
       * EVERY alias the RULE BINDS, not merely the ones the constraint declares. That is broader
       * than it was, and the narrower version was silently wrong for every rule written in the DSL.
       *
       * ExpressionConstraint.referencedAliases is documented as "optional, and advisory" -- the
       * expression language does the real check against the variables it was handed, and the DSL
       * deliberately passes an EMPTY set, because populating it would change §5.6's content hash and
       * make the same rule authored in YAML and in Java carry different versions. Recording roots
       * from that set therefore recorded nothing at all for a rule file: a YAML condition reading
       * o.total, on a pattern whose `where` block happened not to constrain /total, produced tested
       * paths that did not include it, so an update taking total from 100 to 5000 changed no tested
       * path, propagated nothing, and never fired. That is precisely the Phase 3 defect the
       * paragraph above describes as fixed -- fixed for hand-built definitions only, which is all
       * ConditionTestedPathsTest exercised. It hid because an alpha constraint on the same path
       * covers for it, which is the common shape.
       *
       * So the advisory set is not consulted here. A field whose contract says it may be empty
       * cannot be what a correctness property rests on, and the fail-safe reading of "we do not know
       * which aliases this expression reads" is "assume all of them". This is the same
       * correct-but-conservative move as recording the root rather than the read paths, one level
       * up, and it costs what that costs: an update to any fact a conditioned rule binds propagates.
       */
      for (final String factType : aliasTypes) {
        recordConditionRoot(rule.id(), factType);
      }
      return Optional.of(new ExpressionTest(constraint, program));
    } catch (final ExpressionCompilationException rejected) {
      diagnostics.add(where + ": " + rejected.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Compiles every expression a rule's actions use (§6.4).
   *
   * <p>Keyed by source text, which deduplicates two identical expressions into one program -- the
   * same sharing §6.5 does for alpha nodes, available here for free because an expression's meaning
   * is a function of its text and the aliases in scope.
   *
   * @param rule the rule
   * @param whenAliases the aliases the {@code when} block binds; insert aliases are added as the
   *     walk reaches the action that introduces them
   * @return the compiled programs by source text
   */
  private Map<String, CompiledExpression> compileValueExpressions(final RuleDefinition rule,
      final Set<String> whenAliases, final Map<String, Quantifier> nonBindingAliases) {
    final Map<String, CompiledExpression> programs = new LinkedHashMap<>();
    /*
     * Grown as the actions are walked, not gathered up front. §6.2.2 allocates an insertFact's
     * handle at stage time so a LATER action can name it, and validateActions already holds $ref to
     * exactly that: an alias must be bound by `when` or by an EARLIER insert. Folding every insert
     * alias in before the walk let an expression name a fact that does not exist yet -- accepted at
     * compile time, and an unresolved variable at fire time -- while the same rule written with
     * $ref was correctly rejected.
     */
    final Set<String> bound = new LinkedHashSet<>(whenAliases);
    /*
     * Accumulate aliases are declared up front, unlike insert aliases below, and the asymmetry is
     * the reason above read backwards: an insert alias names a fact that does not exist until a
     * later action creates it, while an accumulate names a fold over facts that already do. There
     * is no ordering to respect, so an expression in the first action may read it.
     */
    nonBindingAliases.forEach((alias, quantifier) -> {
      if (quantifier == Quantifier.ACCUMULATE) {
        bound.add(alias);
      }
    });
    for (final ActionDefinition action : rule.then()) {
      for (final ValueExpr value : valuesOf(action)) {
        if (!(value instanceof ExpressionValue expression)
            || programs.containsKey(expression.expression())) {
          continue;
        }
        // Keyed per operand, so the diagnostic lands on the line the expression is written on
        // rather than on the rule's id. The DSL registers the matching key at the same pointer.
        final String where = rule.id() + ": expression " + expression.expression();
        if (!checkAliases(where, expression.referencedAliases(), bound, nonBindingAliases)) {
          continue;
        }
        try {
          final CompiledExpression program =
              options.expressions().compileValue(expression.expression(), bound);
          if (withinBudget(where, expression.expression(), program)) {
            programs.put(expression.expression(), program);
          }
        } catch (final ExpressionCompilationException rejected) {
          diagnostics.add(where + ": " + rejected.getMessage());
        }
      }
      if (action instanceof InsertFact insert) {
        // Unguarded against a negated alias on purpose, unlike validateActions above: that check
        // already rejects the rule, and repeating it here would report one defect twice.
        insert.alias().ifPresent(bound::add);
      }
    }
    return programs;
  }

  /**
   * Every value expression one action carries.
   *
   * @param action the action
   * @return its values, in declaration order
   */
  private static List<ValueExpr> valuesOf(final ActionDefinition action) {
    return switch (action) {
      case SetField set -> List.of(set.value());
      case InsertFact insert -> insert.payload().stream().map(PayloadField::value).toList();
      case Emit emit -> emit.payload().stream().map(PayloadField::value).toList();
      case CallFunction call -> call.args().stream().map(PayloadField::value).toList();
      case RetractFact ignored -> List.of();
    };
  }

  /**
   * Checks that an expression reads only aliases the rule binds.
   *
   * @param where the diagnostic prefix
   * @param referenced the aliases the expression reads
   * @param bound every alias the rule binds
   * @param nonBindingAliases the aliases of quantified patterns that bind nothing, by quantifier
   * @return true when every referenced alias is bound
   */
  private boolean checkAliases(final String where, final Set<String> referenced,
      final Set<String> bound, final Map<String, Quantifier> nonBindingAliases) {
    boolean valid = true;
    for (final String alias : referenced) {
      if (!bound.contains(alias)) {
        // The third case again (§1's negation), for the same reason the $ref resolver and the
        // action validator both make it: "this rule does not bind it" is true of a negated alias
        // and reads as "there is no such alias", which is the one thing an author can see is
        // false. An expression is where it matters most -- the alias is spelled inside free-form
        // text, so a typo really is the first thing to suspect.
        if (nonBindingAliases.get(alias) == Quantifier.ACCUMULATE) {
          // Legal: §6.4's bindings resolve it to the folded value, as an action's $ref does.
          continue;
        }
        diagnostics.add(where + ": reads alias '" + alias + "', which "
            + (nonBindingAliases.containsKey(alias)
                ? "is a " + nonBindingAliases.get(alias) + " pattern, which binds no fact, so an"
                    + " expression has nothing to read from it"
                : "this rule does not bind"));
        valid = false;
      }
    }
    return valid;
  }

  /**
   * Checks an expression's estimated cost against the configured budget (§6.4).
   *
   * @param where the diagnostic prefix
   * @param expression the source text, for the message
   * @param program the compiled expression
   * @return true when the estimate is within budget
   */
  private boolean withinBudget(final String where, final String expression,
      final CompiledExpression program) {
    if (program.estimatedCost() <= options.expressionBudget()) {
      return true;
    }
    diagnostics.add(where + ": estimated cost " + program.estimatedCost()
        + " exceeds the configured budget of " + options.expressionBudget()
        + " (expression: '" + expression + "'). Note that a budget bounds ONE evaluation, and an"
        + " unindexed condition is evaluated once per candidate (§6.4)");
    return false;
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
