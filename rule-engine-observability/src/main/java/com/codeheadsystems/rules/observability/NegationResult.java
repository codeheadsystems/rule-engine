package com.codeheadsystems.rules.observability;

import java.util.Objects;
import java.util.Optional;

/**
 * What one {@code NOT_EXISTS} pattern of a rule did (spec §1's amendment, reported under §7.2).
 *
 * <p><strong>It is not a {@link PatternResult} and must not be reported as one.</strong> A negated
 * pattern binds no alias and contributes no tuple position, so it has no survivors and eliminates no
 * candidates -- the two numbers a {@code PatternResult} exists to carry. Its numbers run the other
 * way: how many facts of the type are present, and how many otherwise-complete matches their
 * presence suppressed. Folding the two records together would put a "3 considered, 3 matched" line
 * against a pattern asserting that nothing matches, which reads as success at the precise moment it
 * means failure.
 *
 * @param alias the negated pattern's name. It binds nothing -- nothing in the rule may reference it
 *     -- but the author wrote it and it is how they will recognise which negation this is
 * @param factType the type whose absence the rule asserts
 * @param present how many facts of that type are in working memory -- the whole population, not the
 *     subset that could actually block, which depends on the tuple being asked about. Zero means the
 *     absence holds trivially and this negation is not why anything failed. Exact even when the
 *     walk was truncated, unlike {@code suppressed}, because it is read rather than accumulated
 * @param suppressed how many otherwise-complete matches this negation removed. Attributed to the
 *     <em>first</em> negation that defeated each match, in declaration order, so the counts across a
 *     rule's negations sum to the number of matches lost rather than double-counting a tuple that
 *     two negations each defeat
 * @param exampleWitness a handle whose fact defeated the asserted absence, when one did. This is the
 *     part an author cannot derive: "some Payment exists" tells them the rule is suppressed, "fact
 *     #7" tells them what to go and look at
 */
public record NegationResult(String alias, String factType, int present, int suppressed,
    Optional<Long> exampleWitness) {

  /**
   * Canonical constructor.
   *
   * @param alias the negated pattern's name
   * @param factType the type whose absence is asserted
   * @param present the population of that type
   * @param suppressed how many complete matches this negation removed
   * @param exampleWitness a fact that defeated the absence, if any did
   */
  public NegationResult {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(exampleWitness, "exampleWitness");
  }

  /**
   * A one-line rendering, meant to be read alongside the pattern results.
   *
   * @return the negation's outcome
   */
  public String describe() {
    final StringBuilder text = new StringBuilder()
        .append("not ").append(alias).append(": ").append(factType).append(" — ")
        .append(present).append(" present");
    if (suppressed == 0) {
      // Said explicitly rather than left to be inferred from a zero. A negation that suppressed
      // nothing is the common case and the reader needs to move past it, not work it out.
      return text.append(", suppressed nothing").toString();
    }
    text.append(", suppressed ").append(suppressed).append(" match(es)");
    exampleWitness.ifPresent(handle -> text.append(" — e.g. fact #").append(handle));
    return text.toString();
  }
}
