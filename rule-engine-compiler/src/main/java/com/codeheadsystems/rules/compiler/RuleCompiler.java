package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.access.JsonPointerAccessor;
import com.codeheadsystems.rules.access.Paths;
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
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RangeTest;
import com.codeheadsystems.rules.rule.RegexTest;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
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
    return new DefaultCompiledRuleSet(
        compiled,
        NetworkBuilder.build(compiled),
        new DefaultTestedPaths(pathsByType, pathsByRule, inverse),
        version(rules));
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
    final JsonPointer path = Paths.compile(constraint.field());
    record(rule.id(), pattern.factType(), path);
    final JsonPointerAccessor accessor = new JsonPointerAccessor(path);
    final String where = rule.id() + ": " + pattern.alias() + "." + constraint.field();

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
    final JsonPointer path = Paths.compile(constraint.field());
    record(rule.id(), pattern.factType(), path);
    final String where = rule.id() + ": " + pattern.alias() + "." + constraint.field();
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
    final JsonPointer path = Paths.compile(constraint.field());
    final JsonPointer otherPath = Paths.compile(constraint.otherField());
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
