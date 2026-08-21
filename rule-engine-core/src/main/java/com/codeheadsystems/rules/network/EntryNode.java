package com.codeheadsystems.rules.network;

import java.util.List;
import java.util.Objects;

/**
 * The root for one fact type: where every insert of that type enters the network (spec §3.2.1).
 *
 * <p>It fans out to the distinct alpha tests registered for the type, and then to the patterns that
 * consume them. The fan-out is stored as two flat lists rather than as a chain of node references,
 * because Phase 1 evaluates a type's alpha tests as a batch: every distinct test once, then each
 * pattern's conjunction read off the results. A chain would re-derive the same information one
 * pointer hop at a time.
 *
 * @param nodeId the dense compile-time id
 * @param factType the type this node roots
 * @param alphaNodes every distinct alpha test any pattern of this type uses, in id order
 * @param patterns every pattern of this type
 */
public record EntryNode(int nodeId, String factType, List<AlphaNode> alphaNodes,
    List<PatternNode> patterns) implements NetworkNode {

  /**
   * Canonical constructor. Defensively copies the fan-out lists.
   *
   * @param nodeId the dense compile-time id
   * @param factType the fact type
   * @param alphaNodes the distinct alpha tests for the type
   * @param patterns the patterns of the type
   */
  public EntryNode {
    Objects.requireNonNull(factType, "factType");
    alphaNodes = List.copyOf(alphaNodes);
    patterns = List.copyOf(patterns);
  }
}
