package com.codeheadsystems.rules.expr;

import java.util.Set;

/**
 * Turns expression source into something the engine can evaluate (spec §6.4).
 *
 * <p>The SPI {@code rule-engine-cel} implements. Registered through {@code CompilerOptions}, used
 * during §6.5's pipeline, and never consulted again: everything it produces is frozen into the
 * compiled rule set.
 *
 * <p>Two compile methods rather than one, because the two positions differ in the one way that can
 * be checked ahead of time. A {@code condition} must produce a boolean; a value may produce
 * anything a fact field can hold. Checking that when the expression is compiled is the difference
 * between a rule file that fails to build and a rule that behaves oddly in production.
 *
 * <p><strong>Implementations owe determinism.</strong> §7.3's contract -- same rule set, same
 * facts, same insertion order, same firing sequence -- holds only if an expression is a function of
 * its bindings. An environment that lets a rule read a clock, a random source or anything outside
 * {@link ExpressionBindings} breaks it, and §7.3 warns that this is the failure that "usually looks
 * stable in testing".
 */
public interface ExpressionCompiler {

  /**
   * Compiles an expression used as a pattern condition.
   *
   * @param expression the source text
   * @param aliases the aliases the enclosing rule binds, which the expression may read
   * @return the compiled expression
   * @throws ExpressionCompilationException if the source is invalid, reads an alias the rule does
   *     not bind, or does not produce a boolean
   */
  CompiledExpression compileCondition(String expression, Set<String> aliases);

  /**
   * Compiles an expression used as a value in a {@code then} block.
   *
   * @param expression the source text
   * @param aliases the aliases the enclosing rule binds, which the expression may read
   * @return the compiled expression
   * @throws ExpressionCompilationException if the source is invalid or reads an alias the rule does
   *     not bind
   */
  CompiledExpression compileValue(String expression, Set<String> aliases);

  /**
   * The compiler used when the caller registered none.
   *
   * <p>Rejects every expression with a message naming the module that would accept it. §6.4 makes
   * expressions an opt-in cost, so a rule set that uses one without the module present is a
   * configuration mistake, and the useful thing to do about it is say so at compile time rather
   * than fail with a {@code NoClassDefFoundError} at some later point.
   *
   * @return a compiler that accepts nothing
   */
  static ExpressionCompiler unavailable() {
    return Unavailable.INSTANCE;
  }

  /** Holds the rejecting instance, which is stateless and therefore shareable. */
  final class Unavailable implements ExpressionCompiler {

    private static final Unavailable INSTANCE = new Unavailable();

    private Unavailable() {
      // The one instance is enough; it holds no state.
    }

    @Override
    public CompiledExpression compileCondition(final String expression, final Set<String> aliases) {
      throw refuse();
    }

    @Override
    public CompiledExpression compileValue(final String expression, final Set<String> aliases) {
      throw refuse();
    }

    private static ExpressionCompilationException refuse() {
      return new ExpressionCompilationException(
          "expressions need an ExpressionCompiler, and none is registered. Add rule-engine-cel and"
              + " pass CompilerOptions.expressions(...), or express this with operator maps, which"
              + " §6.3 keeps the indexable default");
    }
  }
}
