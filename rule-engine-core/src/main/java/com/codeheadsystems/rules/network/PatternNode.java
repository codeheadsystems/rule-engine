package com.codeheadsystems.rules.network;

import java.util.List;
import java.util.Objects;

/**
 * One pattern's node: the terminal of its alpha chain, and the anchor for its memory (spec §3.2.4).
 *
 * <p>This node type does not appear in a plain Rete sketch, and reconciling two things is why it
 * has to. Constraint-level sharing gives one alpha node per <em>distinct constraint</em> across all
 * rules, so no alpha node holds the set of facts matching a particular pattern -- the shared
 * {@code status == "PENDING"} node holds a superset spanning every rule that tests it. But what the
 * matcher needs, for each pattern, is the set satisfying the <strong>conjunction</strong> of that
 * pattern's alpha tests. Intersecting several shared memories at fire time would defeat the point
 * of sharing them.
 *
 * <p>So the conjunction is materialised here, and this node's memory is the pattern's alpha memory:
 * exactly the facts matching this pattern. It is what the agenda enumerates, what a join probes,
 * and where the per-pattern index is anchored.
 *
 * <p>Pattern nodes are shared only between patterns with an <em>identical</em> constraint set,
 * which is far rarer than constraint-level sharing and should not be expected to help much. That is
 * fine: the alpha chain above them is still shared, which is where the sublinearity comes from.
 *
 * @param nodeId the dense compile-time id
 * @param factType the type this pattern matches
 * @param alphaNodes the alpha tests whose conjunction defines this pattern's memory
 * @param indexPlan which of this pattern's paths the session should index
 */
public record PatternNode(int nodeId, String factType, List<AlphaNode> alphaNodes,
    IndexPlan indexPlan) implements NetworkNode {

  /**
   * Canonical constructor. Defensively copies the alpha list.
   *
   * @param nodeId the dense compile-time id
   * @param factType the fact type
   * @param alphaNodes the conjunction
   * @param indexPlan the index plan
   */
  public PatternNode {
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(indexPlan, "indexPlan");
    alphaNodes = List.copyOf(alphaNodes);
  }

  /**
   * Whether a fact passes every one of this pattern's alpha tests.
   *
   * <p>Reads the batch results rather than re-evaluating, so a test shared by twenty patterns costs
   * one evaluation and twenty array reads.
   *
   * @param evaluation the per-fact results for this fact type
   * @return whether the fact belongs in this pattern's memory
   */
  public boolean accepts(final AlphaEvaluation evaluation) {
    for (final AlphaNode alpha : alphaNodes) {
      if (!evaluation.passed(alpha.nodeId())) {
        return false;
      }
    }
    return true;
  }

  @Override
  public String toString() {
    return "pattern#" + nodeId + "(" + factType + ", " + alphaNodes.size() + " tests)";
  }
}
