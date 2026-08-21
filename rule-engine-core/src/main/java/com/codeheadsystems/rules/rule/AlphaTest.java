package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One precompiled single-fact test (spec §3.2.1's {@code AlphaNode.test}, without the network).
 *
 * <p>Implementations are <strong>pure and side-effect free</strong>, which is what makes them safe
 * to share structurally across sessions and, in Phase 1, safe for the node-sharing pass to
 * deduplicate: two rules with an identical constraint reuse one instance.
 *
 * <p>The originating {@link Constraint} is kept on every implementation. It costs one reference and
 * it is what §7.2's {@code MatchExplainer} needs to answer "which constraint eliminated the rest" --
 * a test compiled down to a bare predicate can say a rule did not match but never why.
 *
 * <p>There is deliberately no implementation for {@link ExpressionConstraint}: CEL arrives in
 * Phase 5, and until then the compiler rejects the constraint with a message saying so, rather
 * than the engine carrying a test type that throws.
 */
public sealed interface AlphaTest permits FieldTest, RangeTest, RegexTest {

  /**
   * The constraint this test was compiled from.
   *
   * @return the source constraint, for diagnostics and explanations
   */
  Constraint constraint();

  /**
   * The accessor this test reads through.
   *
   * @return the precompiled accessor
   */
  FieldAccessor accessor();

  /**
   * Evaluates the test against one fact payload.
   *
   * @param payload the fact's payload
   * @return whether the fact satisfies this constraint
   */
  boolean test(JsonNode payload);
}
