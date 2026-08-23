package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.rule.Quantifier;
import java.util.Objects;
import java.util.Optional;

/**
 * What one quantified pattern of a rule did (spec §2.5, reported under §7.2).
 *
 * <p><strong>It is not a {@link PatternResult} and must not be reported as one.</strong> A
 * {@code NOT_EXISTS} or {@code FOR_ALL} pattern binds no alias and contributes no tuple position, so
 * it has no survivors and eliminates no candidates -- the two numbers a {@code PatternResult} exists
 * to carry. Its numbers run the other way: how many facts of the type are present, and how many
 * otherwise-complete matches their presence suppressed. Folding the two records together would put a
 * "3 considered, 3 matched" line against a pattern asserting that nothing matches, which reads as
 * success at the precise moment it means failure.
 *
 * <p><strong>One record for all three rather than one apiece.</strong> They ask different
 * questions -- a negation is defeated by a fact that satisfies it, a universal by a fact in scope
 * that fails it, an accumulate by an answer its {@code having} rejects -- but they answer with the
 * same numbers, and a reader asking "what suppressed my rule" wants one list rather than three to
 * cross-reference. {@link #kind} is what says which reading applies, and it is what
 * {@link #describe} switches on.
 *
 * <p>An accumulate has no {@link #example}: what suppressed the match is the <em>answer</em>, not
 * any one fact in the scope, and naming an arbitrary contributor would point the author at a fact
 * that is doing nothing wrong. The answer itself is in the verdict.
 *
 * @param kind the quantifier this pattern carries: {@link Quantifier#NOT_EXISTS},
 *     {@link Quantifier#FOR_ALL} or {@link Quantifier#ACCUMULATE}
 * @param alias the pattern's name. It binds nothing -- nothing in the rule may reference it -- but
 *     the author wrote it and it is how they will recognise which quantifier this is
 * @param factType the type the quantifier ranges over
 * @param population how many facts of that type are in working memory -- all of them, not the subset
 *     in scope, which depends on the tuple being asked about. Zero means a negation holds trivially
 *     and, for a universal, that it holds <em>vacuously</em>, which is the sharper case: the rule
 *     fired because there was nothing to fail it. Exact even when the walk was truncated, unlike
 *     {@code suppressed}, because it is read rather than accumulated
 * @param suppressed how many otherwise-complete matches this quantifier removed. Attributed to the
 *     <em>first</em> quantifier that removed each match, in evaluation order, so the counts across a
 *     rule sum to the number of matches lost rather than double-counting a tuple that two of them
 *     each remove
 * @param example a handle whose fact settled the quantifier, when one did -- the witness that
 *     defeated an asserted absence, or the in-scope fact that failed an asserted requirement. This
 *     is the part an author cannot derive: "some Payment exists" tells them the rule is suppressed,
 *     "fact #7" tells them what to go and look at
 */
public record QuantifierResult(Quantifier kind, String alias, String factType, int population,
    int suppressed, Optional<Long> example) {

  /**
   * Canonical constructor.
   *
   * @param kind the quantifier
   * @param alias the pattern's name
   * @param factType the type quantified over
   * @param population the population of that type
   * @param suppressed how many complete matches this quantifier removed
   * @param example a fact that settled it, if any did
   */
  public QuantifierResult {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(example, "example");
  }

  /**
   * A one-line rendering, meant to be read alongside the pattern results.
   *
   * @return the quantifier's outcome
   */
  public String describe() {
    final StringBuilder text = new StringBuilder()
        // "not", "all" and "fold", not the enum's spelling: the line sits under the pattern lines,
        // which read "o: Order", and the prefix has to scan as part of that column rather than as a
        // constant name.
        // Exhaustive and without a default, so a new Quantifier constant fails this compile rather
        // than silently rendering as one of these. EXISTS_AT_LEAST_ONE never reaches here -- a
        // positive pattern is a PatternResult -- and says so rather than sharing a branch.
        .append(switch (kind) {
          case NOT_EXISTS -> "not ";
          case FOR_ALL -> "all ";
          case ACCUMULATE -> "fold ";
          case EXISTS_AT_LEAST_ONE -> throw new IllegalStateException(
              "a positive pattern is reported as a PatternResult, not a QuantifierResult");
        })
        .append(alias).append(": ").append(factType).append(" — ")
        .append(population).append(" present");
    if (suppressed == 0) {
      // Said explicitly rather than left to be inferred from a zero. A quantifier that suppressed
      // nothing is the common case and the reader needs to move past it, not work it out.
      return text.append(", suppressed nothing")
          .append(kind == Quantifier.FOR_ALL && population == 0 ? " (vacuously — nothing in scope)"
              : "")
          .toString();
    }
    text.append(", suppressed ").append(suppressed).append(" match(es)");
    example.ifPresent(handle -> text.append(kind == Quantifier.NOT_EXISTS
        ? " — e.g. fact #" + handle + " defeats it"
        : " — e.g. fact #" + handle + " is in scope and fails it"));
    return text.toString();
  }
}
