package com.codeheadsystems.rules.eval;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.JoinTest;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The two halves of a pattern's tests, asked separately (spec §2.5).
 *
 * <p><strong>Splitting them is not a refactor; it is what makes {@code FOR_ALL} mean something
 * useful.</strong> For {@link Negations} the two halves are a single conjunction -- a fact defeats
 * an asserted absence when it satisfies everything written on the pattern. For {@link Universals}
 * they play different roles, and §2.5's amendment argues the case: the join tests choose
 * <em>which</em> facts the assertion is about, and the pattern's own constraints are what is
 * asserted about them. "Every {@code LineItem} of this order is in stock" is one pattern under that
 * reading and is inexpressible under any reading that conjoins the two.
 *
 * <p>Package-private on purpose. The split is a decision about what a quantifier means, and the two
 * classes that make that decision live here; exposing the halves would invite a caller to recombine
 * them into a third meaning nothing in the spec defines.
 */
final class PatternTests {

  /** Not instantiable: two predicates with no state of their own. */
  private PatternTests() {
  }

  /**
   * Whether a candidate satisfies a pattern's single-fact constraints.
   *
   * @param pattern the pattern
   * @param payload the candidate's payload
   * @return whether every alpha test holds
   */
  static boolean alphasHold(final CompiledPattern pattern, final JsonNode payload) {
    for (final AlphaTest test : pattern.alphaTests()) {
      if (!test.test(payload)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a candidate satisfies a pattern's cross-fact constraints against one binding.
   *
   * <p><strong>A join whose other side is no longer in working memory answers false, and that is
   * defensive rather than conservative.</strong> No caller is known to reach it: a recompute derives
   * the tuple and asks the quantifier with no retraction interleaved, and {@code MatchExplainer}
   * resolves a missing pinned handle to no survivors, so no tuple is built from one. If it were
   * reached the two callers would diverge -- for {@link Negations} false means "not the witness",
   * which loses nothing, but for {@link Universals} it means "out of scope", and a bound side that
   * has vanished empties the scope entirely, making the requirement vacuously true and the rule
   * fire. That is fail-open, and it is the one path where §2.5's vacuous truth would arrive with
   * nothing in the explanation to record it. Throwing instead would be worse -- a matcher that
   * throws on a legal interleaving -- so the answer stands and the cost is written down here.
   *
   * @param pattern the pattern
   * @param payload the candidate's payload
   * @param bound the handle ids bound by the positive tuple
   * @param memory the working memory the join tests dereference their other side from
   * @return whether every join test holds
   */
  static boolean joinsHold(final CompiledPattern pattern, final JsonNode payload,
      final long[] bound, final WorkingMemory memory) {
    for (final JoinTest test : pattern.joinTests()) {
      final Optional<Fact> other = memory.get(new FactHandle(bound[test.otherIndex()]));
      if (other.isEmpty() || !test.test(payload, other.get().payload())) {
        return false;
      }
    }
    return true;
  }
}
