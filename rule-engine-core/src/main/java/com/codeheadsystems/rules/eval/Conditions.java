package com.codeheadsystems.rules.eval;

import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.ExpressionTest;

/**
 * Whether every §6.4 {@code condition} on a rule holds for one complete tuple.
 *
 * <p>Extracted from {@code RecomputingAgenda} when truth maintenance arrived, because §4.4's
 * amendment re-asks exactly this question of a justifying tuple and two copies could disagree. The
 * argument is the one §1's amendment makes for negation: an expression is the part of matching most
 * likely to drift if written twice, and a truth-maintenance pass that judged a match still valid
 * when the agenda judged it gone would retract nothing and leave the conclusion standing -- or judge
 * it gone when the agenda kept it, and retract a fact the engine still believes.
 *
 * <p>The diagnostic wrapping moved with it, and deliberately. A condition that throws is not a
 * per-match error: it stops the fire cycle, and the only thing worth owing the operator is enough
 * context to find the cause -- which rule, which alias, which expression. That is as true when the
 * throw happens during revalidation as during matching, and the message says which by naming
 * neither.
 */
public final class Conditions {

  /** Not instantiable: a predicate with no state of its own. */
  private Conditions() {
  }

  /**
   * Whether every condition the rule's patterns carry holds for one binding.
   *
   * @param rule the rule
   * @param bindings resolves an alias to the payload the tuple binds it to
   * @return true when no condition rejected the tuple
   * @throws ExpressionEvaluationException when a condition cannot be evaluated at all
   */
  public static boolean holdFor(final CompiledRule rule, final ExpressionBindings bindings) {
    for (final CompiledPattern pattern : rule.patterns()) {
      for (final ExpressionTest test : pattern.expressionTests()) {
        if (!evaluate(rule, pattern, test, bindings)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Evaluates one condition, naming it if it fails.
   *
   * <p><strong>A condition that throws aborts the fire cycle, and there is no policy that catches
   * it.</strong> §4.6's {@code RhsErrorHandler} governs the right-hand side; a condition is
   * evaluated while the conflict set is being built, where there is nothing to skip and continue
   * past. A matcher that silently treated an evaluation failure as "no match" would turn a broken
   * expression into rules that quietly stop firing, which is the failure mode this engine works
   * hardest to avoid.
   *
   * @param rule the rule being matched
   * @param pattern the pattern the condition was written on
   * @param test the compiled condition
   * @param bindings the tuple's payloads by alias
   * @return whether the condition holds
   */
  private static boolean evaluate(final CompiledRule rule, final CompiledPattern pattern,
      final ExpressionTest test, final ExpressionBindings bindings) {
    try {
      return test.program().test(bindings);
    } catch (final RuntimeException failed) {
      throw new ExpressionEvaluationException(
          "rule '" + rule.id() + "', condition on alias '" + pattern.alias() + "' ("
              + test.source().expression() + "): " + failed.getMessage()
              + ". A condition that fails to evaluate stops the fire cycle -- there is no"
              + " per-match error policy on the left-hand side (§6.4)", failed);
    }
  }
}
