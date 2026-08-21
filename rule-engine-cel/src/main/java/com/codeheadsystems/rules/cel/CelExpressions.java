package com.codeheadsystems.rules.cel;

import com.codeheadsystems.rules.expr.CompiledExpression;
import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.expr.ExpressionCompilationException;
import com.codeheadsystems.rules.expr.ExpressionCompiler;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import tools.jackson.databind.JsonNode;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import dev.cel.runtime.CelVariableResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * §6.4's escape hatch, compiled with CEL.
 *
 * <p>Every alias the rule binds is declared as a CEL variable of type {@code dyn} and bound to the
 * fact's payload, so {@code o.total} in an expression reads what {@code $ref: o.total} would.
 *
 * <p><strong>Determinism is a property of this environment, and §7.3 depends on it.</strong> The
 * contract -- same rule set, same facts, same insertion order, same firing sequence -- holds only
 * while a rule's outcome is a function of its inputs, and §7.3 warns that losing it "usually looks
 * stable in testing". Two things keep it: CEL's standard function set contains nothing that reads a
 * clock, a random source or an environment (time enters CEL as a bound variable, never as a
 * function), and this class binds <em>only</em> the tuple's aliases. Adding a binding here is
 * therefore a decision about §7.3, not a convenience -- inject time as a fact instead, exactly as
 * {@code HostFunction} already tells handlers to.
 *
 * <p><strong>On bounding cost.</strong> §6.4 says to set a static estimator and a runtime cost
 * limit, "both", and describes them as things {@code dev.cel} ships. As of 0.14.0 it ships neither.
 * What this class does instead:
 *
 * <ul>
 *   <li><strong>At compile time</strong>, {@link Program#estimatedCost()} is this module's own
 *       measure -- the number of type-checked AST nodes. It is a relative figure for comparing
 *       expressions and rejecting pathological ones, and it is honestly <em>not</em> a time bound.
 *   <li><strong>At run time</strong>, {@code comprehensionMaxIterations} bounds the case §6.4 is
 *       actually worried about. CEL guarantees termination, not linear time, and it is
 *       comprehensions -- {@code exists}, {@code all}, {@code map}, {@code filter} -- that make two
 *       nested loops O(n·m). Parse recursion depth and node count are bounded too, so a
 *       pathological expression fails to compile rather than consuming the compile.
 * </ul>
 *
 * <p>Immutable and thread-safe once built, as {@link CompiledExpression} requires: the compiler and
 * runtime are built in the constructor and every program is created at rule-compile time.
 */
public final class CelExpressions implements ExpressionCompiler {

  /** Bounds a comprehension, which is the one construct that can be superlinear (§6.4). */
  private static final int DEFAULT_MAX_COMPREHENSION_ITERATIONS = 10_000;

  /** Bounds the parser, so a pathological expression fails fast rather than during the compile. */
  private static final int DEFAULT_MAX_NODES = 1_000;

  /** Bounds nesting, for the same reason. */
  private static final int DEFAULT_MAX_RECURSION_DEPTH = 32;

  private final CelOptions options;
  private final CelRuntime runtime;

  private CelExpressions(final CelOptions options) {
    this.options = options;
    this.runtime = CelRuntimeFactory.standardCelRuntimeBuilder().setOptions(options).build();
  }

  /**
   * An expression compiler with the default limits.
   *
   * @return the compiler
   */
  public static CelExpressions create() {
    return builder().build();
  }

  /**
   * A fresh builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public CompiledExpression compileCondition(final String expression, final Set<String> aliases) {
    return compile(expression, aliases, SimpleType.BOOL);
  }

  @Override
  public CompiledExpression compileValue(final String expression, final Set<String> aliases) {
    return compile(expression, aliases, SimpleType.DYN);
  }

  /**
   * Compiles one expression against a rule's aliases.
   *
   * @param expression the source text
   * @param aliases the aliases the rule binds
   * @param resultType what the expression must produce
   * @return the compiled program
   */
  private CompiledExpression compile(final String expression, final Set<String> aliases,
      final CelType resultType) {
    CelCompilerBuilder compiler = CelCompilerFactory.standardCelCompilerBuilder()
        .setOptions(options)
        /*
         * The comprehension macros -- exists, all, map, filter -- are the reason §6.4 insists CEL
         * "guarantees termination, not linear time", and they are also most of what makes an
         * expression worth reaching for over an operator map. Enabled, and bounded by
         * comprehensionMaxIterations rather than withheld.
         */
        .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
        .setResultType(resultType);
    for (final String alias : aliases) {
      compiler = compiler.addVar(alias, SimpleType.DYN);
    }
    final CelValidationResult result = compiler.build().compile(expression);
    if (result.hasError()) {
      // getErrorString(), not getErrors().toString(): the latter prints an AutoValue dump
      // carrying its own line/column numbers, which sit beside the rule file's and mean something
      // completely different. ExpressionCompilationException's contract asks for the author's
      // message.
      throw new ExpressionCompilationException(result.getErrorString());
    }
    final CelAbstractSyntaxTree ast;
    try {
      ast = result.getAst();
    } catch (final CelValidationException invalid) {
      throw new ExpressionCompilationException(invalid.getMessage(), invalid);
    }
    try {
      return new Program(runtime.createProgram(ast), ast.getTypeMap().size(), expression);
    } catch (final CelEvaluationException unplannable) {
      throw new ExpressionCompilationException(
          "the expression could not be planned: " + unplannable.getMessage(), unplannable);
    }
  }

  /** One compiled CEL program. Immutable, and evaluated concurrently by every session. */
  private static final class Program implements CompiledExpression {

    private final CelRuntime.Program program;
    private final long estimatedCost;
    private final String source;

    private Program(final CelRuntime.Program program, final long estimatedCost,
        final String source) {
      this.program = program;
      this.estimatedCost = estimatedCost;
      this.source = source;
    }

    @Override
    public JsonNode evaluate(final ExpressionBindings bindings) {
      try {
        return JsonValues.toJson(program.eval(new Resolver(bindings)));
      } catch (final CelEvaluationException failed) {
        throw new ExpressionEvaluationException(
            "evaluating '" + source + "' failed: " + failed.getMessage(), failed);
      }
    }

    @Override
    public long estimatedCost() {
      return estimatedCost;
    }
  }

  /**
   * Presents the tuple's bindings to CEL, one alias at a time.
   *
   * <p>{@code CelVariableResolver} rather than the {@code eval(Map)} overload, because that
   * overload copies the map it is given -- which would convert every bound fact's whole payload to
   * evaluate an expression that reads one field of one of them. A rule joining five facts pays for
   * one here.
   *
   * <p>Conversion is remembered for the duration of an evaluation, since CEL may read the same
   * variable more than once and §7.3 wants the second read to see exactly what the first did.
   */
  private static final class Resolver implements CelVariableResolver {

    private final ExpressionBindings bindings;
    private final Map<String, Optional<Object>> converted = new LinkedHashMap<>();

    private Resolver(final ExpressionBindings bindings) {
      this.bindings = bindings;
    }

    @Override
    public Optional<Object> find(final String name) {
      return converted.computeIfAbsent(name, alias -> {
        final JsonNode payload = bindings.get(alias);
        /*
         * An alias the tuple does not bind is genuinely absent, and CEL's own answer for that --
         * an unknown -- is better than a null: it makes the expression fail loudly rather than
         * silently compare against nothing. The compiler already rejects an expression naming an
         * alias its rule does not bind, so this is a backstop, not a path.
         */
        return payload == null || payload.isMissingNode()
            ? Optional.empty()
            : Optional.ofNullable(JsonValues.toCel(payload));
      });
    }
  }

  /** Builds a {@link CelExpressions} with explicit limits. */
  public static final class Builder {

    private int maxComprehensionIterations = DEFAULT_MAX_COMPREHENSION_ITERATIONS;
    private int maxNodes = DEFAULT_MAX_NODES;
    private int maxRecursionDepth = DEFAULT_MAX_RECURSION_DEPTH;

    private Builder() {
      // Reached through builder().
    }

    /**
     * Caps how many iterations one comprehension may run.
     *
     * @param iterations the cap
     * @return this builder
     */
    public Builder maxComprehensionIterations(final int iterations) {
      this.maxComprehensionIterations = iterations;
      return this;
    }

    /**
     * Caps how many nodes an expression may parse to.
     *
     * @param nodes the cap
     * @return this builder
     */
    public Builder maxNodes(final int nodes) {
      this.maxNodes = nodes;
      return this;
    }

    /**
     * Caps how deeply an expression may nest.
     *
     * @param depth the cap
     * @return this builder
     */
    public Builder maxRecursionDepth(final int depth) {
      this.maxRecursionDepth = depth;
      return this;
    }

    /**
     * Builds the compiler.
     *
     * @return the compiler
     */
    public CelExpressions build() {
      return new CelExpressions(CelOptions.current()
          /*
           * Without this, `o.price > 100` throws whenever price is a decimal: CEL has no
           * int-versus-double comparison overload, and a JSON payload decides which of the two a
           * field is while the rule author writes a plain `100`. §2.6.2 treats 10000 and 10000.0 as
           * one value and the operator-map form honours that, so an expression that refused to
           * compare them would be the escape hatch disagreeing with the language around it -- on
           * the most ordinary rule anybody writes.
           */
          .enableHeterogeneousNumericComparisons(true)
          .comprehensionMaxIterations(maxComprehensionIterations)
          .maxParseExpressionNodeCount(maxNodes)
          .maxParseRecursionDepth(maxRecursionDepth)
          .build());
    }
  }
}
