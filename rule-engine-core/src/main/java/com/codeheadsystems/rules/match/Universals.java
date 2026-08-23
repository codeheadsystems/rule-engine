package com.codeheadsystems.rules.match;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.CompiledPattern;
import java.util.Iterator;
import java.util.Optional;

/**
 * Whether the requirement a {@code FOR_ALL} pattern asserts holds for one complete tuple (spec
 * §2.5's amendment).
 *
 * <p><strong>The join tests choose the scope; the pattern's own constraints are the
 * requirement.</strong> That split is the whole design, and §2.5's amendment argues why the obvious
 * alternative is not merely weaker but a trap. Read literally -- "every fact of the type matches the
 * pattern" -- a {@code FOR_ALL} carrying a join asserts that every {@code LineItem} in working
 * memory belongs to this order <em>and</em> is in stock, which is false the moment a second order
 * exists. An author writes what looks right and gets a rule that can never fire, with nothing in the
 * diagnostic to say why. Under the split, the same pattern says "every {@code LineItem} <em>of this
 * order</em> is in stock", which is what they meant.
 *
 * <p>With no joins the two readings coincide and the quantifier is the global one the enum
 * originally described: every fact of the type satisfies the constraints.
 *
 * <p><strong>What it buys over {@link Negations}, precisely.</strong> A single-constraint
 * requirement is already expressible as a negation of its complement -- "every {@code Order} is
 * shipped" is "no {@code Order} is not shipped". A <em>multi</em>-constraint one is not: the
 * complement of "in stock and qty above zero" is a disjunction, and no pattern in this engine
 * expresses one. That is the expressiveness {@code FOR_ALL} adds, and it is worth stating because a
 * reader who only tries the single-constraint case will conclude the quantifier is redundant.
 *
 * <p><strong>Vacuously true when nothing is in scope</strong>, which is classical and is also the
 * sharpest hazard here. A rule asserting that every {@code LineItem} of an order is in stock fires
 * for an order with no line items at all. §2.5's amendment states it as a boundary rather than a
 * footnote, and pairing the {@code FOR_ALL} with a positive pattern of the same type -- which most
 * rules that want it already have -- is what turns "all of them" into "there are some, and all of
 * them".
 *
 * <p>The evaluation shape, the cost profile and the reasons for both are {@link Negations}': one
 * implementation shared by the three matchers and §7.2's explainer, answering with the fact that
 * settles the question rather than a boolean, scanning working memory rather than probing a memory
 * the naive oracle does not have.
 */
public final class Universals {

  /** Not instantiable: a predicate with no state of its own. */
  private Universals() {
  }

  /**
   * The in-scope fact that fails a universal pattern's requirement, against one complete binding.
   *
   * @param universal the universal pattern, whose join tests point at positions in {@code bound}
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param memory the working memory to ask
   * @return the first in-scope fact failing the requirement, or empty when the assertion holds
   */
  public static Optional<Fact> counterexample(final CompiledPattern universal, final long[] bound,
      final WorkingMemory memory) {
    return scan(universal, bound, () -> memory.factsOfType(universal.factType()).iterator(), memory)
        .found();
  }

  /**
   * The same question, against a population the caller already holds, reporting what it cost.
   *
   * <p>Exists for the reason {@link Negations#scan} does: §7.2's explainer asks once per complete
   * tuple and must not re-snapshot working memory each time, and it charges its work budget with
   * what the walk actually examined.
   *
   * @param universal the universal pattern, whose join tests point at positions in {@code bound}
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param candidates every fact of the universal pattern's type
   * @param memory the working memory the join tests dereference their other side from
   * @return the counterexample, if any, and how many candidates were examined to decide
   */
  public static Scan scan(final CompiledPattern universal, final long[] bound,
      final Iterable<Fact> candidates, final WorkingMemory memory) {
    int examined = 0;
    final Iterator<Fact> iterator = candidates.iterator();
    while (iterator.hasNext()) {
      final Fact candidate = iterator.next();
      examined++;
      if (universal.conflictsWith(bound, candidate.handle().id())) {
        /*
         * §1's implicit inequality, and it lands on the SCOPE rather than on the requirement: the
         * fact the tuple already binds is not one this assertion is about. Without it, "every other
         * Order is shipped" written beside a bound Order that is not shipped would be unsatisfiable
         * by construction -- the rule's own fact would be its own counterexample.
         */
        continue;
      }
      if (!PatternTests.joinsHold(universal, candidate.payload(), bound, memory)) {
        // Out of scope: the assertion says nothing about this fact, so it cannot fail it.
        continue;
      }
      if (!PatternTests.alphasHold(universal, candidate.payload())) {
        return new Scan(Optional.of(candidate), examined);
      }
    }
    return new Scan(Optional.empty(), examined);
  }
}
