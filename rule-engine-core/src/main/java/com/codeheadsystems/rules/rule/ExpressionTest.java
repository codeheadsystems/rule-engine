package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.expr.CompiledExpression;
import java.util.Objects;

/**
 * A compiled pattern condition, ready to evaluate (spec §6.4).
 *
 * <p>The expression counterpart of {@link AlphaTest} and {@link JoinTest}: the source constraint
 * beside the compiled artifact that evaluates it. Keeping the source is what lets a diagnostic say
 * <em>which</em> condition rejected a tuple, which is the whole job of {@code MatchExplainer}.
 *
 * <p>Note where this is <strong>not</strong> evaluated. An alpha test filters a fact on the way into
 * a pattern memory and a join test narrows a probe; a condition does neither, because §6.4 makes it
 * an unindexed post-filter. It runs against a complete tuple, in the agenda both matchers share.
 *
 * @param source the constraint as the author wrote it
 * @param program the compiled expression
 */
public record ExpressionTest(ExpressionConstraint source, CompiledExpression program) {

  /**
   * Canonical constructor.
   *
   * @param source the constraint
   * @param program the compiled expression
   */
  public ExpressionTest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(program, "program");
  }
}
