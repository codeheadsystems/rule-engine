package com.codeheadsystems.rules.network;

import tools.jackson.databind.JsonNode;
import java.util.BitSet;

/**
 * The result of evaluating one fact against every distinct alpha test of its type.
 *
 * <p>This is where node sharing actually pays. A rule set where fifty rules test
 * {@code status == "PENDING"} compiles to <em>one</em> alpha node, so inserting an order runs that
 * comparison once and fifty patterns read the answer. Without this, sharing would deduplicate the
 * node objects while still evaluating the same predicate once per rule, which is the shape of
 * sublinearity without any of its benefit.
 *
 * <p>Scoped to a single propagation and discarded afterwards. It is deliberately not a session-level
 * cache: a cached result would have to be invalidated on every update, and §3.4.1 already makes an
 * update a retract followed by a re-assert, which re-evaluates from scratch by design.
 */
public final class AlphaEvaluation {

  private final BitSet passed;

  private AlphaEvaluation(final BitSet passed) {
    this.passed = passed;
  }

  /**
   * Evaluates every distinct alpha test registered for a fact type.
   *
   * @param entry the fact type's entry node
   * @param payload the fact's payload
   * @return the results, addressed by alpha node id
   */
  public static AlphaEvaluation of(final EntryNode entry, final JsonNode payload) {
    final BitSet results = new BitSet();
    for (final AlphaNode alpha : entry.alphaNodes()) {
      if (alpha.test(payload)) {
        results.set(alpha.nodeId());
      }
    }
    return new AlphaEvaluation(results);
  }

  /**
   * Whether the fact passed one test.
   *
   * @param alphaNodeId the alpha node's id
   * @return the result
   */
  public boolean passed(final int alphaNodeId) {
    return passed.get(alphaNodeId);
  }
}
