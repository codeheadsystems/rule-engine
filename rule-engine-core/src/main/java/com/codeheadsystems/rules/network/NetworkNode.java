package com.codeheadsystems.rules.network;

/**
 * One node of the compiled matching network (spec §3.2.1).
 *
 * <p>Every node gets a dense {@code nodeId} at compile time, and that is how spec invariant 1 is
 * realised: the node graph lives in the shared, immutable compiled rule set and holds
 * <em>structure and plans</em>, while everything a node <em>stores</em> lives in the session, in a
 * {@link SessionMemories} array addressed by that id. Without node ids and a session-side memory
 * array, "a shared node graph" and "incrementally maintained indexes" are contradictory claims.
 *
 * <p>Sealed for exhaustiveness inside the engine, but the hierarchy is expected to grow -- the
 * deferred negation and accumulation node types will be added here, and Phase 2 adds the join node
 * -- so downstream code must not rely on exhaustive switches over it.
 *
 * <p>Note which node types are <em>absent</em> in Phase 1 rather than present-and-empty. There is
 * no join node and no terminal node yet: joins are still enumerated by the agenda, and a complete
 * match becomes an activation without passing through a node. Declaring them now would mean
 * declaring behaviour nothing invokes.
 */
public sealed interface NetworkNode permits EntryNode, AlphaNode, PatternNode {

  /**
   * This node's dense identifier, assigned at compile time and fixed for the life of the rule set.
   *
   * @return an index in {@code 0..nodeCount-1}
   */
  int nodeId();
}
