package com.codeheadsystems.rules.network;

import java.util.Objects;

/**
 * Every node's per-session state, addressed by node id (spec §3.2.3).
 *
 * <p>Sized once at session creation from the compiled rule set's node count. Entries are null for
 * node types that store nothing, which is most of them: an entry node fans out and an alpha node is
 * a pure test, so only pattern nodes have a memory in Phase 1.
 *
 * <p>Single-writer, like everything else on a session, and therefore unsynchronised.
 */
public final class SessionMemories {

  private final NodeMemory[] byNodeId;

  /**
   * Allocates the memories a network needs.
   *
   * @param network the compiled network
   */
  public SessionMemories(final Network network) {
    Objects.requireNonNull(network, "network");
    this.byNodeId = new NodeMemory[network.nodeCount()];
    for (final PatternNode pattern : network.patternNodes()) {
      byNodeId[pattern.nodeId()] = new PatternMemory(pattern.indexPlan());
    }
  }

  /**
   * One pattern's memory.
   *
   * @param pattern the node
   * @return its memory
   */
  public PatternMemory of(final PatternNode pattern) {
    return (PatternMemory) byNodeId[pattern.nodeId()];
  }
}
