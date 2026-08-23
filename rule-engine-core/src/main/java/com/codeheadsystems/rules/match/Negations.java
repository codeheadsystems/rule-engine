package com.codeheadsystems.rules.match;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.JoinTest;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Whether the absence a {@code NOT_EXISTS} pattern asserts holds for one complete tuple (spec §1's
 * amendment).
 *
 * <p><strong>One implementation, two callers, and that is the whole point of the class.</strong>
 * {@code RecomputingAgenda} asks this to decide what fires; §7.2's {@code MatchExplainer} asks it to
 * explain why nothing did. Those two answers must not be able to disagree -- an explainer that says
 * a rule is suppressed by an absence the agenda thinks holds is worse than one that says nothing,
 * because it sends an author to fix a rule that is already correct. Writing the predicate twice
 * against exactly the semantics where divergence is hardest to notice is the mistake §1's amendment
 * declined to make for the three matchers; it is the same mistake here.
 *
 * <p><strong>It answers with the witness rather than a boolean</strong>, which costs the agenda
 * nothing -- it calls {@code isPresent()} -- and is the entire value to the explainer. "Some
 * {@code Payment} exists" tells an author their rule is suppressed; "fact #7 is the {@code Payment}"
 * tells them what to go and look at. The first fact found wins: an author fixes one thing at a time,
 * which is the same reason {@code MatchExplainer.firstFailing} reports one constraint rather than
 * every constraint.
 *
 * <p>Scanning working memory for the type rather than probing a pattern memory or an index is
 * deliberate. It is the one implementation all three matchers and the explainer can share, and the
 * naive oracle has no memories to probe. The cost is O(facts of the negated type) per match per fire
 * cycle -- the naive matcher's own cost profile, and the thing a real {@code NotNode} would fix.
 */
public final class Negations {

  /** Not instantiable: a predicate with no state of its own. */
  private Negations() {
  }

  /**
   * The fact whose presence defeats a negated pattern against one complete binding.
   *
   * @param negation the negated pattern, whose join tests point at positions in {@code bound}
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param memory the working memory to ask
   * @return the first fact satisfying the negated pattern, or empty when the asserted absence holds
   */
  public static Optional<Fact> witness(final CompiledPattern negation, final long[] bound,
      final WorkingMemory memory) {
    return scan(negation, bound, () -> memory.factsOfType(negation.factType()).iterator(), memory)
        .witness();
  }

  /**
   * What one scan for a witness found, and how much of the population it had to look at.
   *
   * @param witness the first fact satisfying the negated pattern, or empty when the absence holds
   * @param examined how many candidates the scan pulled from the population before it stopped.
   *     Reported because a scan short-circuits on the first witness, so the population's size is an
   *     upper bound on this and not a measure of it. §7.2's explainer charges its work budget with
   *     this number; charging the upper bound instead made it stop early and report "there may be a
   *     match" on searches that had already proved there is not
   */
  public record Scan(Optional<Fact> witness, int examined) {

    /**
     * Canonical constructor.
     *
     * @param witness the fact that defeats the asserted absence, if one does
     * @param examined how many candidates were pulled from the population
     */
    public Scan {
      Objects.requireNonNull(witness, "witness");
    }
  }

  /**
   * The same question, against a population the caller already holds, reporting what it cost.
   *
   * <p>{@link WorkingMemory#factsOfType} answers with a snapshot -- a copy -- which is the right
   * contract for a matcher asking once per fire cycle and the wrong cost for §7.2's explainer, which
   * asks once per complete tuple it examines. This overload lets that caller take the snapshot once.
   * The agenda has no use for it and calls the one above.
   *
   * <p>The caller owns what it passes: a population that is not every fact of the negated type
   * answers a narrower question than the engine does, which for a diagnostic would be a wrong
   * answer rather than a fast one.
   *
   * @param negation the negated pattern, whose join tests point at positions in {@code bound}
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param candidates every fact of the negated pattern's type
   * @param memory the working memory the join tests dereference their other side from
   * @return the witness, if any, and how many candidates were examined to decide
   */
  public static Scan scan(final CompiledPattern negation, final long[] bound,
      final Iterable<Fact> candidates, final WorkingMemory memory) {
    int examined = 0;
    final Iterator<Fact> iterator = candidates.iterator();
    while (iterator.hasNext()) {
      final Fact candidate = iterator.next();
      examined++;
      if (negation.conflictsWith(bound, candidate.handle().id())) {
        // §1's implicit inequality: a negated pattern of a type the rule already binds asks about
        // some OTHER fact, not about the one already bound.
        continue;
      }
      if (satisfies(negation, candidate.payload(), bound, memory)) {
        return new Scan(Optional.of(candidate), examined);
      }
    }
    return new Scan(Optional.empty(), examined);
  }

  /**
   * Whether one candidate satisfies a negated pattern's own tests against a binding.
   *
   * @param negation the negated pattern
   * @param payload the candidate's payload
   * @param bound the handle ids bound by the positive tuple
   * @param memory the working memory the join tests dereference their other side from
   * @return whether this candidate is the fact whose absence was asserted
   */
  private static boolean satisfies(final CompiledPattern negation, final JsonNode payload,
      final long[] bound, final WorkingMemory memory) {
    for (final AlphaTest test : negation.alphaTests()) {
      if (!test.test(payload)) {
        return false;
      }
    }
    for (final JoinTest test : negation.joinTests()) {
      final Optional<Fact> other = memory.get(new FactHandle(bound[test.otherIndex()]));
      if (other.isEmpty() || !test.test(payload, other.get().payload())) {
        return false;
      }
    }
    return true;
  }
}
