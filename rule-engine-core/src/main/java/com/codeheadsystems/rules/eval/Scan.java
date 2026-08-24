package com.codeheadsystems.rules.eval;

import com.codeheadsystems.rules.fact.Fact;
import java.util.Objects;
import java.util.Optional;

/**
 * What one walk over a quantified pattern's population found, and what it cost (spec §2.5).
 *
 * <p>Shared by {@link Negations} and {@link Universals} because both ask the same shape of question
 * of a complete tuple -- "is there a fact of this type that would settle it" -- and differ only in
 * which fact settles it. A negation looks for a <em>witness</em>, a fact that defeats an asserted
 * absence; a universal looks for a <em>counterexample</em>, a fact in scope that fails the
 * requirement. Either way the first one found ends the walk and is the answer.
 *
 * @param found the fact that settles the quantifier, or empty when it holds. Deliberately not named
 *     for either role: the two callers mean opposite things by it, and a component called
 *     {@code witness} would read as a defeat on the side where it is a failed requirement
 * @param examined how many candidates the walk pulled from the population before it stopped.
 *     Reported because a walk short-circuits on the first result, so the population's size is an
 *     upper bound on this and not a measure of it. §7.2's explainer charges its work budget with
 *     this number; charging the upper bound instead made it stop early and report "there may be a
 *     match" on searches that had already proved there is not
 */
public record Scan(Optional<Fact> found, int examined) {

  /**
   * Canonical constructor.
   *
   * @param found the fact that settles the quantifier, if one does
   * @param examined how many candidates were pulled from the population
   */
  public Scan {
    Objects.requireNonNull(found, "found");
  }
}
