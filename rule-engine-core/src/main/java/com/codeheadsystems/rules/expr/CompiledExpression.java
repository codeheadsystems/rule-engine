package com.codeheadsystems.rules.expr;

import tools.jackson.databind.JsonNode;

/**
 * One expression, compiled once and evaluated many times (spec §6.4).
 *
 * <p>§6.4 states the obligation this interface exists to make possible: "compile once, evaluate
 * many. Never compile at match time." A compiled expression is immutable and shareable, lives in
 * the {@code CompiledRuleSet}, and is read concurrently by every session -- so an implementation
 * must be thread-safe, and must not carry per-evaluation state.
 */
public interface CompiledExpression {

  /**
   * Evaluates the expression against a tuple.
   *
   * @param bindings the facts bound to the rule's aliases
   * @return the result, as a JSON value
   * @throws ExpressionEvaluationException if evaluation fails or exceeds its limits
   */
  JsonNode evaluate(ExpressionBindings bindings);

  /**
   * What the compiler estimated this expression would cost to evaluate once.
   *
   * <p>Reported per expression by §7.4, and checked against a budget at compile time. It is a
   * <em>relative</em> figure for comparing expressions, not a unit of time.
   *
   * @return the estimate; 0 when the implementation does not estimate
   */
  long estimatedCost();

  /**
   * Evaluates a condition.
   *
   * <p>Separate from {@link #evaluate} because a condition's result type is checked when it is
   * compiled, so a non-boolean here is a broken {@link ExpressionCompiler} rather than a rule that
   * does not match -- and quietly returning false would hide that.
   *
   * @param bindings the facts bound to the rule's aliases
   * @return whether the condition holds
   * @throws ExpressionEvaluationException if evaluation fails, exceeds its limits, or does not
   *     produce a boolean
   */
  default boolean test(final ExpressionBindings bindings) {
    final JsonNode result = evaluate(bindings);
    if (result == null || !result.isBoolean()) {
      throw new ExpressionEvaluationException(
          "a condition evaluated to " + result + " rather than a boolean; its result type should"
              + " have been rejected when it was compiled");
    }
    return result.booleanValue();
  }
}
