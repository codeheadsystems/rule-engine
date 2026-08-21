package com.codeheadsystems.rules.naive;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import java.util.ArrayList;
import java.util.List;

/**
 * The brute-force matcher: for every pattern, scan every fact of its type and test it.
 *
 * <p>Its cost is {@code O(rules x facts^arity)}, which is exactly what spec §3.1's "naive re-scan"
 * row says it should be. That is the point. §9 makes Phase 0 a real deliverable because this is the
 * <strong>correctness oracle</strong> every later phase is differential-tested against, and the
 * baseline that proves each optimisation actually helped.
 *
 * <p>It stays in the shipped engine rather than in a test source set for the same reason: §8 wants
 * the oracle available to consumers, and §11.5's Phase 3 exit criterion -- that two matching
 * strategies produce identical firing sequences -- is only checkable against an implementation that
 * still exists and still runs.
 *
 * <p><strong>Keep it readable over fast.</strong> A clever naive matcher is a contradiction: every
 * optimisation here is a place the oracle and the thing it is checking could be wrong in the same
 * way.
 */
public final class NaiveAgenda extends RecomputingAgenda {

  /**
   * Creates the oracle matcher.
   *
   * @param rules the compiled rules, in compilation order
   * @param workingMemory the session's working memory
   * @param refraction the session's refraction memory
   * @param strategy how ties are broken
   * @param listeners the session's listeners, in registration order
   * @param strict whether to assert the conflict-resolution contract
   */
  public NaiveAgenda(final List<CompiledRule> rules, final WorkingMemory workingMemory,
      final RefractionMemory refraction, final ConflictResolutionStrategy strategy,
      final List<RuleEngineListener> listeners, final boolean strict) {
    super(rules, workingMemory, refraction, strategy, listeners, strict);
  }

  /**
   * {@inheritDoc}
   *
   * <p>A snapshot of every fact of the type, in ascending handle id (§2.4), filtered by running the
   * pattern's alpha tests one at a time. No index, no memory, no sharing -- if fifty rules test the
   * same constraint, this evaluates it fifty times per fact per fire cycle.
   */
  @Override
  protected List<Fact> candidates(final CompiledRule rule, final int position, final long[] bound) {
    final CompiledPattern pattern = rule.patterns().get(position);
    final List<Fact> matching = new ArrayList<>();
    workingMemory().factsOfType(pattern.factType()).forEach(fact -> {
      if (satisfiesAlpha(pattern, fact)) {
        matching.add(fact);
      }
    });
    return matching;
  }

  /**
   * Whether a candidate passes every single-fact test of a pattern.
   *
   * @param pattern the pattern
   * @param candidate the candidate fact
   * @return whether it passes
   */
  private static boolean satisfiesAlpha(final CompiledPattern pattern, final Fact candidate) {
    for (final AlphaTest test : pattern.alphaTests()) {
      if (!test.test(candidate.payload())) {
        return false;
      }
    }
    return true;
  }
}
