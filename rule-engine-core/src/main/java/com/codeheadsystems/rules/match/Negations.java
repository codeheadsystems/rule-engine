package com.codeheadsystems.rules.match;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.CompiledPattern;
import java.util.Iterator;
import java.util.Optional;

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
 * every constraint. {@link #scan} additionally reports how far it got, which only the explainer
 * needs: it is what lets a diagnostic charge its work budget for what a negation actually cost
 * rather than for what it might have.
 *
 * <p><strong>A negation conjoins the two halves of its pattern</strong>, where {@link Universals}
 * gives them different jobs. A fact defeats an asserted absence when it satisfies everything written
 * -- the joins that relate it to the tuple <em>and</em> the constraints on the fact itself. There is
 * no scope to choose, because the pattern names the thing whose absence is asserted rather than a
 * population to make an assertion about.
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
        .found();
  }

  /**
   * The same question, against a population the caller already holds, reporting what it cost.
   *
   * <p>{@link WorkingMemory#factsOfType} answers with a snapshot -- a copy -- which is the right
   * contract for a matcher asking once per fire cycle and the wrong cost for §7.2's explainer, which
   * asks once per complete tuple it examines. This overload lets that caller take the snapshot once.
   * The agenda has no use for it and calls {@link #witness}.
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
      /*
       * Alphas first, and the opposite of the order Universals uses -- deliberately, on both
       * counts. Here the two halves are one conjunction, so either order gives the same answer and
       * the cheap one should run first: an alpha test is a literal comparison, while a join
       * allocates a FactHandle and goes back to working memory for the other side. There it is the
       * semantics: the joins decide what is in scope before anything is required of it. Do not
       * "unify" the two.
       */
      if (PatternTests.alphasHold(negation, candidate.payload())
          && PatternTests.joinsHold(negation, candidate.payload(), bound, memory)) {
        return new Scan(Optional.of(candidate), examined);
      }
    }
    return new Scan(Optional.empty(), examined);
  }
}
