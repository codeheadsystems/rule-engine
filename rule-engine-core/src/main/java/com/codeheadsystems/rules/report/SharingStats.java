package com.codeheadsystems.rules.report;

/**
 * How much node sharing the compiler achieved (spec §6.5, §7.4).
 *
 * <p>§7.4's purpose for this: "how you check §6.5's sublinearity claim against your actual rule set
 * instead of trusting it". §6.5 is careful about that claim and this record has to be equally
 * careful, because a statistic is read as a fact long after the caveat is forgotten.
 *
 * <p><strong>{@code joinNodes} is 0 in v1, and that is not a measurement.</strong> §11.5 chose a
 * TREAT-shaped conflict set for v1, which has no beta network at all: there is no {@code JoinNode}
 * to count, because the join order is chosen fresh on each fire cycle by {@code JoinPlan} rather
 * than materialised as a graph. The Rete shape and its persistent beta memory arrive in Phase 3
 * (§9), and this component starts reporting a real number then. {@link #joinEdges} is what v1 can
 * honestly say about join complexity, and is offered alongside rather than in place of it.
 *
 * @param ruleCount how many rules compiled
 * @param distinctAlphaNodes how many alpha nodes exist after sharing
 * @param alphaTestOccurrences how many times a pattern refers to an alpha node. This is what
 *     {@code distinctAlphaNodes} would equal if nothing were shared
 * @param patternNodes how many pattern nodes exist, which is one per pattern and never shared
 * @param joinNodes always 0 in v1. See the class note before reading anything into it
 * @param joinEdges how many join constraints the rule set declares, which is what v1 has in place
 *     of a beta network size
 * @param alphaSharingRatio {@code alphaTestOccurrences / distinctAlphaNodes}: 1.0 means nothing was
 *     shared, and higher is better. Stated this way round so that "bigger is better" needs no
 *     explaining; 0.0 when the rule set has no alpha tests at all
 */
public record SharingStats(
    int ruleCount,
    int distinctAlphaNodes,
    int alphaTestOccurrences,
    int patternNodes,
    int joinNodes,
    int joinEdges,
    double alphaSharingRatio) {

  /**
   * Builds the statistics, deriving the ratio.
   *
   * @param ruleCount how many rules compiled
   * @param distinctAlphaNodes how many alpha nodes exist after sharing
   * @param alphaTestOccurrences how many pattern-to-alpha-node references exist
   * @param patternNodes how many pattern nodes exist
   * @param joinEdges how many join constraints the rule set declares
   * @return the statistics
   */
  public static SharingStats of(final int ruleCount, final int distinctAlphaNodes,
      final int alphaTestOccurrences, final int patternNodes, final int joinEdges) {
    return new SharingStats(ruleCount, distinctAlphaNodes, alphaTestOccurrences, patternNodes,
        0, joinEdges,
        distinctAlphaNodes == 0 ? 0.0 : (double) alphaTestOccurrences / distinctAlphaNodes);
  }
}
