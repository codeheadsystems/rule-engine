package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.expr.CompiledExpression;
import com.codeheadsystems.rules.expr.ExpressionCompilationException;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CallFunction;
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
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.DefaultCompiledRuleSet;
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
    /** Aliases a {@code NOT_EXISTS} pattern names. They bind nothing, so nothing may reference one. */
    final Set<String> negatedAliases = new LinkedHashSet<>();
    /*
     * Positions are assigned to POSITIVE patterns only, and that is the whole trick that keeps
     * negation from touching the rest of the engine. A NOT_EXISTS pattern binds no alias into the
     * tuple, so giving it a position would make every downstream consumer -- the join planner, the
     * join walk, the streaming matcher's pattern sites, the explainer -- have to know to skip it.
     * Instead they read `patterns`, which contains only the ones that produce bindings, and the
     * negated ones are compiled separately against these same positions.
     */
    int position = 0;
    for (final PatternDefinition pattern : rule.when()) {
      if (pattern.quantifier() != Quantifier.EXISTS_AT_LEAST_ONE
          && pattern.quantifier() != Quantifier.NOT_EXISTS) {
        diagnostics.add(rule.id() + ": quantifier " + pattern.quantifier()
            + " on alias '" + pattern.alias() + "' is not implemented. See spec section 1"
            + " for the interim answer");
      }
      if (pattern.quantifier() == Quantifier.NOT_EXISTS) {
        /*
         * A negated alias is still checked for duplication, because "bound twice" would be just as
         * confusing here -- but it is recorded in a separate set, since nothing may reference it.
         * The pattern itself is compiled after this loop, when every positive position is known: a
         * negation may join against any positive alias, including ones declared after it.
         */
        if (!negatedAliases.add(pattern.alias()) || aliasPositions.containsKey(pattern.alias())) {
          diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "' is bound twice");
        }
        /*
         * A §6.4 condition on a negated pattern is refused rather than ignored. It would compile --
         * ExpressionConstraint is a Constraint like any other -- and then never run, because the
         * post-filter that evaluates conditions walks the rule's POSITIVE patterns and the negation
         * is answered by its alpha and join tests alone. The negation would silently be broader than
         * written, which loses a firing quietly. Refusing costs little: such a condition cannot
         * reference the negated alias anyway, since only bound aliases are declared to the
         * expression compiler.
         */
        for (final Constraint constraint : pattern.constraints()) {
          if (constraint instanceof ExpressionConstraint) {
            diagnostics.add(rule.id() + ": alias '" + pattern.alias() + "': a condition on a"
                + " NOT_EXISTS pattern is not supported. Express it with the pattern's own"
                + " constraints, which are what decide whether the fact exists");
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
    int positive = 0;
    for (final PatternDefinition pattern : rule.when()) {
      if (pattern.quantifier() == Quantifier.NOT_EXISTS) {
        /*
         * Compiled at a notional position AFTER every positive one, which is not a trick: the third
         * argument only decides which earlier same-type positions this pattern's fact must differ
         * from, and a negated candidate must differ from all of them. "No OTHER order for this
         * customer" is what an author means by a negated pattern of a type the rule already binds,
         * and it is the same rule §1 states for two positive aliases.
         */
        negations.add(compilePattern(rule, pattern, aliasTypes.size(), aliasPositions, aliasTypes,
            negatedAliases));
      } else {
        patterns.add(compilePattern(rule, pattern, positive, aliasPositions, aliasTypes,
            negatedAliases));
        positive++;
      }
    }
    if (patterns.isEmpty()) {
      // A rule that is nothing but negations has no tuple to attach them to. It would "match" once,
      // against the empty binding, which is a semantics nobody asked for and §2.5 does not define.
      diagnostics.add(rule.id() + ": every pattern is NOT_EXISTS; a rule needs at least one pattern"
          + " that binds a fact");
    }

    validateActions(rule, aliasPositions.keySet(), negatedAliases);

    /*
     * Aliases an insertFact introduces count as bound for an expression, exactly as they do for a
     * $ref: §6.2.2 allocates the handle at stage time so a later action in the same right-hand side
     * can name it, and an expression is a later action's value like any other.
     */
    final Map<String, CompiledExpression> valueExpressions =
        compileValueExpressions(rule, aliasPositions.keySet(), negatedAliases);

    if (diagnostics.size() != before) {
      return Optional.empty();
    }
    return Optional.of(new CompiledRule(
        rule.id(), rule.salience(), rule.noLoop(), rule.agendaGroup(), patterns, negations,
        rule.then(),
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
   * @param negatedAliases the aliases naming a negated pattern, which bind nothing
   * @return the compiled pattern
   */
  private CompiledPattern compilePattern(final RuleDefinition rule,
      final PatternDefinition pattern, final int position,
      final Map<String, Integer> aliasPositions, final List<String> aliasTypes,
      final Set<String> negatedAliases) {
    final List<AlphaTest> alphaTests = new ArrayList<>();
    final List<JoinTest> joinTests = new ArrayList<>();
    final List<ExpressionTest> expressionTests = new ArrayList<>();

    for (final Constraint constraint : pattern.constraints()) {
      switch (constraint) {
        case JoinConstraint join ->
            compileJoin(rule, pattern, position, join, aliasPositions, aliasTypes, negatedAliases)
                .ifPresent(joinTests::add);
        case ExpressionConstraint expression ->
            compileCondition(rule, pattern, expression, aliasPositions, aliasTypes,
                negatedAliases).ifPresent(expressionTests::add);
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
      final Set<String> negatedAliases) {
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
      // Three cases, not two: a NOT_EXISTS alias exists but binds nothing, and saying it is not
      // bound by the rule sends an author looking for a typo that is not there. Same reasoning as
      // the "bound later" branch below.
      diagnostics.add(where + ": $ref names alias '" + constraint.otherAlias() + "', which "
          + (negatedAliases.contains(constraint.otherAlias())
              ? "is a NOT_EXISTS pattern. A negated pattern binds no fact, so nothing can reference"
                  + " it"
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
   * Validates that every action names something that exists.
   *
   * @param rule the rule
   * @param lhsAliases the aliases the left-hand side binds
   * @param negatedAliases the aliases a NOT_EXISTS pattern names, which bind nothing and so are
   *     neither bound nor absent -- a distinction the diagnostic has to make
   */
  private void validateActions(final RuleDefinition rule, final Set<String> lhsAliases,
      final Set<String> negatedAliases) {
    final Set<String> bound = new LinkedHashSet<>(lhsAliases);
    for (final ActionDefinition action : rule.then()) {
      switch (action) {
        case SetField setField -> {
          requireAlias(rule, bound, negatedAliases, setField.targetAlias(), "setField target");
          requireValue(rule, bound, negatedAliases, setField.value());
        }
        case InsertFact insert -> {
          insert.payload().forEach(
              field -> requireValue(rule, bound, negatedAliases, field.value()));
          insert.alias().ifPresent(alias -> {
            /*
             * A negated alias is checked BEFORE `bound`, because it is in neither set: it names no
             * fact, so it was never added to `bound`, and an unguarded add would therefore succeed
             * and quietly hand the name to the inserted fact. Every later action naming it would
             * then resolve -- to the opposite of what the rule says, a fact this rule created
             * standing in for one whose absence it asserted.
             */
            if (negatedAliases.contains(alias)) {
              diagnostics.add(rule.id() + ": insertFact binds alias '" + alias
                  + "', which names a NOT_EXISTS pattern. One name cannot mean both the fact whose"
                  + " absence this rule asserts and the fact this action creates");
            } else if (!bound.add(alias)) {
              diagnostics.add(rule.id() + ": insertFact binds alias '" + alias
                  + "', which is already bound");
            }
          });
        }
        case RetractFact retract ->
            requireAlias(rule, bound, negatedAliases, retract.targetAlias(), "retractFact target");
        case Emit emit -> {
          if (emit.eventType().isBlank()) {
            diagnostics.add(rule.id() + ": emit needs an event type");
          }
          emit.payload().forEach(
              field -> requireValue(rule, bound, negatedAliases, field.value()));
        }
        case CallFunction call -> {
          options.declaredFunctions().ifPresent(declared -> {
            if (!declared.contains(call.name())) {
              diagnostics.add(rule.id() + ": callFunction names '" + call.name()
                  + "', which is not registered. Known functions: " + new TreeSet<>(declared));
            }
          });
          call.args().forEach(field -> requireValue(rule, bound, negatedAliases, field.value()));
        }
      }
    }
  }

  /**
   * Records a diagnostic if an alias is not bound at this point in the action list.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param negatedAliases the aliases a NOT_EXISTS pattern names, which bind nothing
   * @param alias the alias to check
   * @param what a description of where the alias appeared
   */
  private void requireAlias(final RuleDefinition rule, final Set<String> bound,
      final Set<String> negatedAliases, final String alias, final String what) {
    if (bound.contains(alias)) {
      return;
    }
    // The same three cases the $ref resolver above distinguishes, for the same reason: an alias
    // the rule plainly writes, reported as one the rule does not have, sends an author looking for
    // a typo that is not there. A negated alias is written in 'when' and binds nothing.
    diagnostics.add(rule.id() + ": " + what + " names alias '" + alias + "', which "
        + (negatedAliases.contains(alias)
            ? "is a NOT_EXISTS pattern. A negated pattern binds no fact, so nothing can reference"
                + " it"
            : "is not bound by 'when' or by an earlier insertFact"));
  }

  /**
   * Records a diagnostic if a value expression references an unbound alias.
   *
   * @param rule the rule
   * @param bound the aliases bound so far
   * @param negatedAliases the aliases a NOT_EXISTS pattern names, which bind nothing
   * @param value the expression to check
   */
  private void requireValue(final RuleDefinition rule, final Set<String> bound,
      final Set<String> negatedAliases, final ValueExpr value) {
    switch (value) {
      case Literal ignored -> {
        // A constant references nothing.
      }
      case FieldRef ref -> requireAlias(rule, bound, negatedAliases, ref.alias(), "$ref");
      case ExpressionValue expression -> expression.referencedAliases()
          .forEach(alias -> requireAlias(rule, bound, negatedAliases, alias, "expression"));
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
      final Set<String> negatedAliases) {
    final Set<String> bound = aliasPositions.keySet();
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
    if (!checkAliases(where, constraint.referencedAliases(), bound, negatedAliases)) {
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
      final Set<String> whenAliases, final Set<String> negatedAliases) {
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
    for (final ActionDefinition action : rule.then()) {
      for (final ValueExpr value : valuesOf(action)) {
        if (!(value instanceof ExpressionValue expression)
            || programs.containsKey(expression.expression())) {
          continue;
        }
        // Keyed per operand, so the diagnostic lands on the line the expression is written on
        // rather than on the rule's id. The DSL registers the matching key at the same pointer.
        final String where = rule.id() + ": expression " + expression.expression();
        if (!checkAliases(where, expression.referencedAliases(), bound, negatedAliases)) {
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
   * @param negatedAliases the aliases a NOT_EXISTS pattern names, which bind nothing
   * @return true when every referenced alias is bound
   */
  private boolean checkAliases(final String where, final Set<String> referenced,
      final Set<String> bound, final Set<String> negatedAliases) {
    boolean valid = true;
    for (final String alias : referenced) {
      if (!bound.contains(alias)) {
        // The third case again (§1's negation), for the same reason the $ref resolver and the
        // action validator both make it: "this rule does not bind it" is true of a negated alias
        // and reads as "there is no such alias", which is the one thing an author can see is
        // false. An expression is where it matters most -- the alias is spelled inside free-form
        // text, so a typo really is the first thing to suspect.
        diagnostics.add(where + ": reads alias '" + alias + "', which "
            + (negatedAliases.contains(alias)
                ? "is a NOT_EXISTS pattern. A negated pattern binds no fact, so an expression has"
                    + " nothing to read from it"
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
