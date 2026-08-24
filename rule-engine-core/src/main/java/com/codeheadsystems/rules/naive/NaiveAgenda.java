package com.codeheadsystems.rules.naive;

import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.ConflictResolutionStrategy;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The brute-force matcher: bind the patterns in the order they are written, and for each one scan
 * every fact of its type and test it.
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
 * way. It deliberately does not reorder joins, does not consult an index, and does not remember
 * anything between fire cycles.
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

  @Override
  protected List<Activation> matchesOf(final CompiledRule rule, final List<String> aliases) {
    final List<Activation> matches = new ArrayList<>();
    extend(rule, aliases, 0, new long[rule.patterns().size()], matches);
    return matches;
  }

  /**
   * Depth-first extension of a partial binding, left to right through the written pattern order.
   *
   * @param rule the rule
   * @param aliases the rule's aliases
   * @param position the pattern to bind next
   * @param bound the handles bound so far, mutated in place and copied into each completed tuple
   * @param matches the list to append completed matches to
   */
  private void extend(final CompiledRule rule, final List<String> aliases, final int position,
      final long[] bound, final List<Activation> matches) {
    if (position == rule.patterns().size()) {
      matches.add(buildActivation(rule, bound, aliases));
      return;
    }
    final CompiledPattern pattern = rule.patterns().get(position);
    // A snapshot in ascending handle id (§2.4), which is what makes the enumeration order -- and
    // therefore anything derived from it -- reproducible across runs and hosts.
    for (final Fact candidate : workingMemory().factsOfType(pattern.factType()).toList()) {
      if (pattern.conflictsWith(bound, candidate.handle().id())
          || !satisfiesAlpha(pattern, candidate)
          || !satisfiesJoins(pattern, candidate, bound)) {
        continue;
      }
      bound[position] = candidate.handle().id();
      extend(rule, aliases, position + 1, bound, matches);
    }
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

  /**
   * Whether a candidate passes every cross-fact test of a pattern against the current binding.
   *
   * <p>Safe to evaluate every one of them here, without checking whether the other end is bound,
   * because this matcher binds strictly left to right and §6.5 guarantees a reference resolves to
   * an <em>earlier</em> alias.
   *
   * @param pattern the pattern
   * @param candidate the candidate fact
   * @param bound the handles bound so far
   * @return whether it passes
   */
  private boolean satisfiesJoins(final CompiledPattern pattern, final Fact candidate,
      final long[] bound) {
    for (final JoinTest test : pattern.joinTests()) {
      final Optional<Fact> other = workingMemory().get(new FactHandle(bound[test.otherIndex()]));
      if (other.isEmpty() || !test.test(candidate.payload(), other.get().payload())) {
        return false;
      }
    }
    return true;
  }
}
