package com.codeheadsystems.rules.session;

/**
 * The work limits every fire call runs under (spec §4.7).
 *
 * <p><strong>Built, never constructed positionally.</strong> The reason is a decision §4.7 leaves
 * explicitly open: a wall-clock bound. {@code maxCycles} and {@code maxFacts} bound <em>work</em>,
 * not <em>time</em>, and neither is a proxy for latency -- an unindexed condition over a hundred
 * thousand facts is a hundred thousand evaluations inside a <em>single</em> cycle, tripping neither
 * limit. That bound is deferred, and a builder is what makes adding it later a non-breaking change
 * rather than one that breaks every positional construction in every caller. Until then, a caller
 * with a per-decision latency budget runs its own watchdog against
 * {@link RuleSession#halt()}.
 *
 * <p>There is deliberately no limit-less fire. Both limits are always in force.
 */
public final class FireOptions {

  /**
   * The default cycle limit: finite, not unlimited.
   *
   * <p>Ten thousand firings is far past any rule set that is behaving, and far short of the time a
   * runaway loop needs to become someone's incident.
   */
  public static final int DEFAULT_MAX_CYCLES = 10_000;

  /** The default fact limit: finite, and low enough to surface an insert-per-firing loop. */
  public static final int DEFAULT_MAX_FACTS = 1_000_000;

  private final int maxCycles;
  private final int maxFacts;

  /**
   * Creates options.
   *
   * @param maxCycles the firing limit
   * @param maxFacts the working-memory size limit
   */
  private FireOptions(final int maxCycles, final int maxFacts) {
    this.maxCycles = maxCycles;
    this.maxFacts = maxFacts;
  }

  /**
   * A fresh builder, preloaded with the finite defaults.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The default limits.
   *
   * @return options carrying {@link #DEFAULT_MAX_CYCLES} and {@link #DEFAULT_MAX_FACTS}
   */
  public static FireOptions defaults() {
    return builder().build();
  }

  /**
   * The maximum number of firings one fire call may perform.
   *
   * @return the cycle limit
   */
  public int maxCycles() {
    return maxCycles;
  }

  /**
   * The maximum number of facts working memory may hold.
   *
   * <p>This pairs with the cycle limit rather than duplicating it: a rule inserting a fact per
   * firing without retracting exhausts the heap long before a high cycle limit trips, and an
   * out-of-memory error says nothing about which rule did it.
   *
   * @return the fact limit
   */
  public int maxFacts() {
    return maxFacts;
  }

  /** Builds {@link FireOptions}. */
  public static final class Builder {

    private int maxCycles = DEFAULT_MAX_CYCLES;
    private int maxFacts = DEFAULT_MAX_FACTS;

    /** Creates a builder carrying the defaults. */
    private Builder() {
      // Defaults are assigned inline.
    }

    /**
     * Sets the firing limit.
     *
     * @param value the maximum number of firings; must be positive
     * @return this builder
     */
    public Builder maxCycles(final int value) {
      this.maxCycles = value;
      return this;
    }

    /**
     * Sets the working-memory size limit.
     *
     * @param value the maximum number of facts; must be positive
     * @return this builder
     */
    public Builder maxFacts(final int value) {
      this.maxFacts = value;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     * @throws IllegalArgumentException if either limit is not positive
     */
    public FireOptions build() {
      if (maxCycles <= 0) {
        throw new IllegalArgumentException("maxCycles must be positive, was " + maxCycles);
      }
      if (maxFacts <= 0) {
        throw new IllegalArgumentException("maxFacts must be positive, was " + maxFacts);
      }
      return new FireOptions(maxCycles, maxFacts);
    }
  }
}
