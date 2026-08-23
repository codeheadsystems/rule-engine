package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Rule files into rules. This is the DSL's front door (spec §6).
 *
 * <pre>{@code
 * CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
 *
 * try (RuleSession session = rules.newSession()) {
 *     session.insert("Order", Facts.json("..."));
 *     FireResult result = session.fireAllRules();
 * }
 * }</pre>
 *
 * <p>{@link #parse} stops at {@code RuleDefinition}; {@link #compile} carries on into
 * {@code RuleCompiler}. Both accept many files, because §6.2.3 makes a rule set the union of them
 * and {@code id} unique across all of them -- a duplicate id is only detectable if the files are
 * compiled together.
 *
 * <p><strong>Every problem in every file is reported at once.</strong> A rule set is edited as a
 * batch, and this module's audience is somebody who has just written four hundred lines of YAML;
 * one error at a time would be a poor way to meet them.
 */
public final class RuleFiles {

  private RuleFiles() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Parses rule files into rule definitions.
   *
   * @param sources the files
   * @return the definitions, in the order the files declare them
   * @throws RuleFileException if any file is invalid, carrying every problem found
   */
  public static List<RuleDefinition> parse(final RuleSource... sources) {
    return parse(List.of(sources));
  }

  /**
   * Parses rule files into rule definitions.
   *
   * @param sources the files
   * @return the definitions, in the order the files declare them
   * @throws RuleFileException if any file is invalid, carrying every problem found
   */
  public static List<RuleDefinition> parse(final List<RuleSource> sources) {
    // Copied on the way out. Every other collection boundary in this engine does, and this is the
    // one an outside caller actually holds.
    return List.copyOf(assemble(sources).rules());
  }

  /**
   * Parses and compiles rule files with default compiler options.
   *
   * @param sources the files
   * @return the compiled, immutable rule set
   * @throws RuleFileException if any file is invalid, or if compilation rejects the rules
   */
  public static CompiledRuleSet compile(final RuleSource... sources) {
    return compile(List.of(sources), CompilerOptions.defaults());
  }

  /**
   * Parses and compiles rule files.
   *
   * @param sources the files
   * @param options how the compiler should behave
   * @return the compiled, immutable rule set
   * @throws RuleFileException if any file is invalid, or if compilation rejects the rules
   */
  public static CompiledRuleSet compile(final List<RuleSource> sources,
      final CompilerOptions options) {
    final Assembly assembly = assemble(sources);
    try {
      return RuleCompiler.compile(assembly.rules(), options);
    } catch (final RuleCompilationException rejected) {
      /*
       * §6.5 hands semantic validation to the compiler, and this module does not reimplement it.
       * What it does add is the one thing the compiler cannot know: which line of which file each
       * of its diagnostics came from.
       */
      throw new RuleFileException(assembly.locate(rejected.diagnostics()), rejected);
    }
  }

  /**
   * Reads and interprets every file.
   *
   * @param sources the files
   * @return the assembled rules and the locations they came from
   * @throws RuleFileException if any file is invalid
   */
  private static Assembly assemble(final List<RuleSource> sources) {
    final Assembly assembly = new Assembly();
    for (final RuleSource source : sources) {
      RuleFileReader.read(source, assembly.diagnostics)
          .ifPresent(parsed -> interpret(parsed, assembly));
    }
    if (!assembly.diagnostics.isEmpty()) {
      throw new RuleFileException(assembly.diagnostics);
    }
    return assembly;
  }

  /**
   * Turns one bound document into rule definitions.
   *
   * @param parsed the document
   * @param assembly what to add to
   */
  private static void interpret(final RuleFileReader.Parsed parsed, final Assembly assembly) {
    final Diagnostics diagnostics = new Diagnostics(parsed.index(), assembly.diagnostics);
    final List<RuleNode> rules = parsed.document().rules();
    for (int index = 0; index < rules.size(); index++) {
      final RuleNode rule = rules.get(index);
      final String pointer = "/rules/" + index;
      diagnostics.inRule(rule.id());
      assembly.byRule.put(rule.id(), parsed.index().nearest(pointer + "/id"));
      recordExpressionSites(rule, pointer, parsed, assembly);
      assembly.rules.add(new RuleDefinition(
          rule.id(),
          rule.salience(),
          patternsOf(rule, pointer, parsed, assembly, diagnostics),
          actionsOf(rule, pointer, diagnostics),
          rule.noLoop(),
          Optional.ofNullable(rule.agendaGroup()),
          new TreeSet<>(rule.tags())));
    }
    diagnostics.inRule(null);
  }

  /**
   * Compiles one rule's {@code when} block.
   *
   * @param rule the rule
   * @param rulePointer the rule's JSON Pointer
   * @param parsed the document
   * @param assembly what to record locations into
   * @param diagnostics collects problems
   * @return the patterns, in document order
   */
  private static List<PatternDefinition> patternsOf(final RuleNode rule, final String rulePointer,
      final RuleFileReader.Parsed parsed, final Assembly assembly,
      final Diagnostics diagnostics) {
    final List<PatternDefinition> patterns = new ArrayList<>(rule.when().size());
    for (int index = 0; index < rule.when().size(); index++) {
      final WhenNode pattern = rule.when().get(index);
      final String pointer = rulePointer + "/when/" + index;
      /*
       * Read first and acted on last. An unspellable quantifier costs this pattern -- there is no
       * honest default to fall back to, since guessing `exists` would compile a rule that asks the
       * opposite of what it says -- but the constraints below are still walked, so their problems
       * are reported in the same pass rather than one edit at a time.
       */
      final Optional<Quantifier> quantifier =
          Quantifiers.of(pattern.quantifier(), pointer + "/quantifier", diagnostics);
      final List<Constraint> constraints = new ArrayList<>();
      if (pattern.condition() != null) {
        /*
         * §6.4's escape hatch. The referenced aliases are left empty deliberately: the expression
         * language owns variable resolution, and the compiler declares exactly the rule's aliases
         * to it, so an expression naming something else is rejected there -- by a parser that knows
         * the syntax, rather than by a second one guessing at it here.
         */
        constraints.add(new ExpressionConstraint(pattern.condition(), Set.of()));
        // So a compiler diagnostic about the expression lands on the condition's own line rather
        // than on the rule's id. The key matches the prefix RuleCompiler writes.
        assembly.byConstraint.put(rule.id() + ": " + pattern.as() + ": condition",
            new Assembly.ConstraintSite(
                parsed.index().nearest(pointer + "/condition"), rule.id()));
        /*
         * The compiler refuses a condition on a quantified pattern outright, and writes that one
         * under a different prefix -- "<rule>: alias '<alias>': ..." rather than the "<rule>:
         * <alias>: condition: ..." an ordinary condition diagnostic carries. Registered here so it
         * lands on the condition rather than on the rule's id, which is the whole point of this
         * module.
         */
        if (quantifier.filter(kind -> kind == Quantifier.NOT_EXISTS
            || kind == Quantifier.FOR_ALL).isPresent()) {
          assembly.byConstraint.put(rule.id() + ": alias '" + pattern.as() + "'",
              new Assembly.ConstraintSite(
                  parsed.index().nearest(pointer + "/condition"), rule.id()));
        }
      }
      for (final Map.Entry<String, JsonNode> entry : pattern.where().entrySet()) {
        final String at =
            pointer + "/where/" + entry.getKey().replace("~", "~0").replace("/", "~1");
        // A field NAME gets the same $-treatment an operand does; see References.readKey.
        final Optional<String> field =
            References.readKey(entry.getKey(), at, "field name", diagnostics);
        if (field.isEmpty()) {
          continue;
        }
        assembly.byConstraint.put(rule.id() + ": " + pattern.as() + "." + field.get(),
            new Assembly.ConstraintSite(parsed.index().nearest(at), rule.id()));
        constraints.addAll(
            OperatorMaps.constraintsOf(field.get(), entry.getValue(), at, diagnostics));
      }
      quantifier.ifPresent(quantified -> patterns.add(
          new PatternDefinition(pattern.as(), pattern.fact(), quantified, constraints)));
    }
    return patterns;
  }

  /**
   * Compiles one rule's {@code then} block.
   *
   * @param rule the rule
   * @param rulePointer the rule's JSON Pointer
   * @param diagnostics collects problems
   * @return the actions, in document order
   */
  private static List<ActionDefinition> actionsOf(final RuleNode rule, final String rulePointer,
      final Diagnostics diagnostics) {
    final List<ActionDefinition> actions = new ArrayList<>(rule.then().size());
    for (int index = 0; index < rule.then().size(); index++) {
      Actions.actionOf(rule.then().get(index), rulePointer + "/then/" + index, diagnostics)
          .ifPresent(actions::add);
    }
    return actions;
  }

  /**
   * Records where each §6.4 expression is written, so a compiler diagnostic can be located.
   *
   * <p>The compiler knows an expression only by its source text -- it never sees the document -- so
   * the key is that text, and the DSL supplies the line. Without this an expression diagnostic
   * falls back to the rule's {@code id:}, which for free-form text is the construct least able to
   * afford it.
   *
   * @param rule the rule as written
   * @param rulePointer the rule's JSON Pointer
   * @param parsed the document
   * @param assembly what to record into
   */
  private static void recordExpressionSites(final RuleNode rule, final String rulePointer,
      final RuleFileReader.Parsed parsed, final Assembly assembly) {
    for (int index = 0; index < rule.then().size(); index++) {
      final ThenNode action = rule.then().get(index);
      final String at = rulePointer + "/then/" + index;
      record(action.value(), at + "/value", rule, parsed, assembly);
      action.payload().forEach((name, value) -> record(value,
          at + "/payload/" + name.replace("~", "~0").replace("/", "~1"), rule, parsed, assembly));
      action.args().forEach((name, value) -> record(value,
          at + "/args/" + name.replace("~", "~0").replace("/", "~1"), rule, parsed, assembly));
    }
  }

  /**
   * Records one value's location when it is an expression.
   *
   * @param value the value as written
   * @param pointer its JSON Pointer
   * @param rule the enclosing rule
   * @param parsed the document
   * @param assembly what to record into
   */
  private static void record(final JsonNode value, final String pointer, final RuleNode rule,
      final RuleFileReader.Parsed parsed, final Assembly assembly) {
    if (References.isExpression(value) && value.get(References.EXPR).isString()) {
      /*
       * putIfAbsent, so the FIRST occurrence wins -- matching RuleCompiler, which dedups identical
       * expression text and therefore compiles and reports the first one. With aliases bound
       * progressively the two occurrences need not be equally valid: an expression naming an
       * insertFact alias is invalid before that insert and fine after it, so last-wins pointed the
       * author at the working copy and told them it was broken.
       */
      assembly.byConstraint.putIfAbsent(
          rule.id() + ": expression " + value.get(References.EXPR).stringValue(),
          new Assembly.ConstraintSite(parsed.index().nearest(pointer), rule.id()));
    }
  }

  /**
   * What a run of {@link #assemble} produced.
   *
   * <p>The two location maps are keyed on the exact prefixes {@code RuleCompiler} writes its
   * diagnostics with. Matching on a message prefix is a coupling worth naming rather than hiding:
   * the alternative is a location parameter threaded through every diagnostic in the compiler, for
   * the benefit of one caller that may or may not have a file to point at. This keeps the cost on
   * the side that wants the feature, and {@code DslDiagnosticsTest} is what notices if the compiler
   * ever changes its wording.
   */
  private static final class Assembly {

    private final List<RuleDefinition> rules = new ArrayList<>();
    private final List<DslDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, SourceLocation> byRule = new LinkedHashMap<>();
    private final Map<String, ConstraintSite> byConstraint = new LinkedHashMap<>();

    /**
     * Where one constraint was written, and which rule wrote it.
     *
     * <p>The rule id is carried rather than parsed back out of the map key. The key is
     * {@code "<ruleId>: <alias>.<field>"} and a rule id may legally contain a colon, so splitting
     * on the first one would silently truncate the id it reported.
     *
     * <p>Note the key space also holds {@code "<ruleId>: expression <source>"}, which embeds
     * arbitrary expression text. A rule id containing {@code ": expression "} can therefore collide
     * with another rule's expression key and take its location. Adversarial rather than accidental,
     * and recorded here rather than defended against: the cost of the collision is a diagnostic
     * pointing at the wrong line, and the cost of a defence would be a key nobody can read. The
     * negation key {@code "<ruleId>: alias '<alias>'"} has one of its own on the same terms -- an
     * alias literally named {@code alias 'p'}, on a pattern carrying a condition, renders the same
     * text as the key for a negated alias {@code p}.
     *
     * @param location where the constraint is in the file
     * @param ruleId the rule that wrote it
     */
    private record ConstraintSite(SourceLocation location, String ruleId) {}

    private List<RuleDefinition> rules() {
      return rules;
    }

    /**
     * Re-reports compiler diagnostics against the lines that caused them.
     *
     * @param compilerDiagnostics what the compiler said
     * @return the same problems, located
     */
    private List<DslDiagnostic> locate(final List<String> compilerDiagnostics) {
      return compilerDiagnostics.stream().map(this::locate).toList();
    }

    /**
     * Locates one compiler diagnostic.
     *
     * @param message the compiler's text
     * @return the located diagnostic
     */
    private DslDiagnostic locate(final String message) {
      // "<ruleId>: <alias>.<field>: ..." -- the most specific key, so it is tried first.
      for (final Map.Entry<String, ConstraintSite> entry : byConstraint.entrySet()) {
        if (message.startsWith(entry.getKey() + ":")) {
          return DslDiagnostic.at(DslError.SEMANTIC, entry.getValue().location(),
              entry.getValue().ruleId(), message);
        }
      }
      for (final Map.Entry<String, SourceLocation> entry : byRule.entrySet()) {
        if (message.startsWith(entry.getKey() + ":")) {
          return DslDiagnostic.at(DslError.SEMANTIC, entry.getValue(), entry.getKey(), message);
        }
      }
      /*
       * A second pass for the file-level shape, "duplicate rule id 'x'", which names its rule in
       * quotes rather than as a prefix. Kept as its own pass, and matched against that exact
       * wording rather than a bare contains: a rule legitimately called 'o' would otherwise claim
       * every diagnostic that mentions an alias 'o' -- in any other rule.
       */
      for (final Map.Entry<String, SourceLocation> entry : byRule.entrySet()) {
        if (message.contains("rule id '" + entry.getKey() + "'")) {
          return DslDiagnostic.at(DslError.SEMANTIC, entry.getValue(), entry.getKey(), message);
        }
      }
      return DslDiagnostic.of(DslError.SEMANTIC, message);
    }
  }
}
