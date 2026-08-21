package com.codeheadsystems.rules.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * The compiled node graph: immutable, shared by every session (spec §3.2.3, §6.5).
 *
 * <p>Holds structure and plans, never data. Everything these nodes store lives in a
 * {@link SessionMemories} keyed by node id.
 *
 * <p>Propagation is the two operations below, and the asymmetry between them is deliberate and is
 * the thing §10 audits: an insert <em>evaluates tests</em> to decide where the fact belongs, and a
 * retract <em>never does</em>. A retract removes by handle identity and computes index-removal keys
 * from the payload the fact had when it was asserted. Re-deriving "which memories should I remove
 * it from" by running the tests against current data is the classic way incremental matching
 * breaks: if the data changed, the tests select the wrong set, and the leftover entries produce
 * phantom matches forever.
 */
public final class Network {

  private final Map<String, EntryNode> byFactType;
  private final Map<String, List<PatternNode>> byRuleId;
  private final List<PatternNode> patternNodes;
  private final int nodeCount;

  /**
   * Creates a network.
   *
   * @param entryNodes one per fact type any rule patterns
   * @param byRuleId each rule's pattern nodes, in that rule's pattern order
   * @param nodeCount the total node count, which sizes each session's memory array
   */
  public Network(final List<EntryNode> entryNodes, final Map<String, List<PatternNode>> byRuleId,
      final int nodeCount) {
    final Map<String, EntryNode> entries = new LinkedHashMap<>();
    entryNodes.forEach(entry -> entries.put(entry.factType(), entry));
    this.byFactType = Map.copyOf(entries);
    final Map<String, List<PatternNode>> rules = new LinkedHashMap<>();
    byRuleId.forEach((ruleId, patterns) -> rules.put(ruleId, List.copyOf(patterns)));
    this.byRuleId = Map.copyOf(rules);
    this.patternNodes = entryNodes.stream()
        .flatMap(entry -> entry.patterns().stream())
        .distinct()
        .toList();
    this.nodeCount = nodeCount;
  }

  /**
   * One rule's pattern nodes.
   *
   * <p>Kept here rather than on {@code CompiledRule} so that the rule model stays free of any
   * dependency on the network. A rule is a rule whichever matcher runs it, and the oracle matcher
   * has no network at all.
   *
   * @param ruleId the rule
   * @return its pattern nodes, in pattern order
   */
  public List<PatternNode> patternsOf(final String ruleId) {
    return byRuleId.getOrDefault(ruleId, List.of());
  }

  /**
   * How many nodes the network has.
   *
   * @return the count, which sizes a session's memory array
   */
  public int nodeCount() {
    return nodeCount;
  }

  /**
   * Every pattern node.
   *
   * @return the nodes, in compilation order
   */
  public List<PatternNode> patternNodes() {
    return patternNodes;
  }

  /**
   * Propagates an insert: evaluate the type's distinct alpha tests once, then file the fact in
   * every pattern memory whose conjunction it satisfies.
   *
   * @param factType the fact's type
   * @param handleId the fact's handle id
   * @param payload the fact's payload
   * @param memories the session's memories
   */
  public void insert(final String factType, final long handleId, final JsonNode payload,
      final SessionMemories memories) {
    final EntryNode entry = byFactType.get(factType);
    if (entry == null) {
      // No rule patterns this type. Storing it would be memory nothing can ever read.
      return;
    }
    final AlphaEvaluation evaluation = AlphaEvaluation.of(entry, payload);
    for (final PatternNode pattern : entry.patterns()) {
      if (pattern.accepts(evaluation)) {
        memories.of(pattern).add(handleId, payload);
      }
    }
  }

  /**
   * Propagates a retract, without re-evaluating any test.
   *
   * <p>The fact is removed from every pattern memory of its type. Membership removal is by handle
   * identity, so a fact whose payload has drifted is still removed from wherever it actually is;
   * index-key removal reads {@code payload}, which the caller must supply as the payload the fact
   * had when it was asserted.
   *
   * @param factType the fact's type
   * @param handleId the fact's handle id
   * @param payload the payload the fact had when it was inserted
   * @param memories the session's memories
   */
  public void retract(final String factType, final long handleId, final JsonNode payload,
      final SessionMemories memories) {
    final EntryNode entry = byFactType.get(factType);
    if (entry == null) {
      return;
    }
    for (final PatternNode pattern : entry.patterns()) {
      memories.of(pattern).remove(handleId, payload);
    }
  }

  /**
   * How many distinct alpha tests the whole network holds.
   *
   * <p>This is the numerator of §6.5's sublinearity claim, and §7.4 wants it reported so that the
   * claim can be checked "against your actual rule set rather than trusting it". Compare it with
   * the total number of constraints across all rules: the gap is what node sharing bought.
   *
   * @return the distinct alpha node count
   */
  public int alphaNodeCount() {
    final java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
    byFactType.values().forEach(entry ->
        entry.alphaNodes().forEach(alpha -> ids.add(alpha.nodeId())));
    return ids.size();
  }

  /**
   * The entry node for a fact type, if any rule patterns it.
   *
   * @param factType the type
   * @return the entry node, or null when nothing patterns the type
   */
  public EntryNode entryFor(final String factType) {
    return byFactType.get(Objects.requireNonNull(factType, "factType"));
  }
}
