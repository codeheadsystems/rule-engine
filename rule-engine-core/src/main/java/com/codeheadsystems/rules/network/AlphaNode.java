package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.rule.AlphaTest;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * A single-fact test, shared across every rule that expresses it (spec §3.2.1).
 *
 * <p>Pure and side-effect free, which is what makes it safe to share structurally: two rules with
 * an identical constraint reuse this one instance, and it is evaluated once per fact rather than
 * once per rule. That sharing is the whole reason the alpha network exists.
 *
 * <p>It holds no memory. Classic Rete gives each alpha node one, but with constraint-level sharing
 * that memory is a superset spanning every rule testing the constraint (§3.2.4) and nothing in
 * Phase 1 reads it -- the useful set is the <em>pattern's</em> memory, which is the conjunction.
 * What sharing needs instead is that each distinct test runs once per fact, and
 * {@link AlphaEvaluation} provides that with a per-propagation result cache. An alpha memory can be
 * added if profiling ever shows re-evaluation cost mattering; adding one now would be storage with
 * no reader.
 */
public final class AlphaNode implements NetworkNode {

  private final int nodeId;
  private final AlphaTest test;

  /**
   * Creates a node.
   *
   * @param nodeId the dense compile-time id
   * @param test the compiled constraint
   */
  public AlphaNode(final int nodeId, final AlphaTest test) {
    this.nodeId = nodeId;
    this.test = Objects.requireNonNull(test, "test");
  }

  @Override
  public int nodeId() {
    return nodeId;
  }

  /**
   * The compiled test.
   *
   * @return the test, kept for diagnostics and for §7.2's explanations
   */
  public AlphaTest test() {
    return test;
  }

  /**
   * Evaluates the test.
   *
   * @param payload the fact's payload
   * @return whether the fact satisfies this constraint
   */
  public boolean test(final JsonNode payload) {
    return test.test(payload);
  }

  @Override
  public String toString() {
    return "alpha#" + nodeId + "(" + test.constraint() + ")";
  }
}
