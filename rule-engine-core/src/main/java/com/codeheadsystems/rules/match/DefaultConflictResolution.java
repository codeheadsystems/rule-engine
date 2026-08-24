package com.codeheadsystems.rules.match;

/**
 * Salience, then recency, then a state-derived tie-break (spec §4.2).
 *
 * <p><strong>Specificity is deliberately absent</strong>, reversing the classic JESS/ILOG/OPS5
 * strategy. Those engines break salience ties by counting LHS tests, and it is a reliable source of
 * author surprise: nobody counts constraints in their head, so "why did B fire before A" gets
 * answered by arithmetic on a number that appears nowhere in the rule file. Salience (explicit,
 * author-controlled) plus recency (explainable from the data) covers the real cases, and the
 * lexicographic key covers the rest deterministically.
 *
 * <p>That leaves <em>two</em> author-visible ordering terms instead of three, which is the point:
 * every input to a firing decision is now either written in the rule or readable from the facts.
 */
public final class DefaultConflictResolution implements ConflictResolutionStrategy {

  /** Whether ties on salience prefer the most recent facts. */
  private final boolean recencyLifo;

  /**
   * Creates the default strategy, preferring the most recently-changed facts on a salience tie.
   */
  public DefaultConflictResolution() {
    this(true);
  }

  /**
   * Creates the strategy with an explicit recency direction.
   *
   * @param recencyLifo {@code true} to prefer the freshest facts (LIFO, the classic default),
   *     {@code false} to prefer the oldest (FIFO)
   */
  public DefaultConflictResolution(final boolean recencyLifo) {
    this.recencyLifo = recencyLifo;
  }

  @Override
  public int compare(final Activation left, final Activation right) {
    final int bySalience = Integer.compare(right.rule().salience(), left.rule().salience());
    if (bySalience != 0) {
      return bySalience;
    }
    final int byRecency = recencyLifo
        ? Long.compare(right.recency(), left.recency())
        : Long.compare(left.recency(), right.recency());
    if (byRecency != 0) {
      return byRecency;
    }
    // A total order on the match itself, so two distinct activations never compare zero and the
    // result does not depend on the order the rebuild loop happened to construct them.
    return ActivationKey.LEXICOGRAPHIC.compare(left.key(), right.key());
  }
}
