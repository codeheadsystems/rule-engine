package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.network.AlphaNode;
import com.codeheadsystems.rules.network.EntryNode;
import com.codeheadsystems.rules.network.IndexPlan;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.network.PatternNode;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.rule.Operator;
import com.fasterxml.jackson.core.JsonPointer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the matching network from already-compiled rules (spec §6.5).
 *
 * <p>The stage order is the one §6.5 specifies, and it specifies it for a reason: <strong>node
 * sharing comes before anything derived per node.</strong> Sharing changes which nodes exist, so
 * computing per-node properties first means computing them for nodes that are about to be merged
 * away, and then either recomputing them or carrying stale ones into the shared graph.
 *
 * <ol>
 *   <li>Deduplicate alpha tests by their originating constraint. Records compare structurally and
 *       literals are deep-copied at construction, so two rules expressing the same constraint
 *       produce the same key without any hand-written structural hash.
 *   <li>Assign dense node ids, alpha nodes first so that the per-fact result set stays a small
 *       {@code BitSet}.
 *   <li>Build one pattern node per pattern, holding the conjunction of its shared alpha nodes.
 *   <li>Group patterns by fact type into entry nodes.
 * </ol>
 *
 * <p>On the sublinearity claim, following §6.5's own caution: sharing is genuinely sublinear for the
 * <em>alpha</em> network, where duplicate single-fact constraints across rules are common. It says
 * nothing about joins, which share only when two rules have an identical pattern prefix. Do not
 * promise a flat curve here and then measure one that is not.
 */
final class NetworkBuilder {

  private NetworkBuilder() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Builds the network for a compiled rule set.
   *
   * @param rules the compiled rules, in compilation order
   * @return the shared, immutable network
   */
  static Network build(final List<CompiledRule> rules) {
    // Stage 1: node sharing. Insertion-ordered so that node ids are a deterministic function of
    // compilation order rather than of hash iteration.
    final Map<Constraint, AlphaTest> distinct = new LinkedHashMap<>();
    for (final CompiledRule rule : rules) {
      for (final CompiledPattern pattern : rule.patterns()) {
        for (final AlphaTest test : pattern.alphaTests()) {
          distinct.putIfAbsent(test.constraint(), test);
        }
      }
    }

    // Stage 2: dense ids, alpha nodes first.
    int nextNodeId = 0;
    final Map<Constraint, AlphaNode> shared = new LinkedHashMap<>();
    for (final Map.Entry<Constraint, AlphaTest> entry : distinct.entrySet()) {
      shared.put(entry.getKey(), new AlphaNode(nextNodeId++, entry.getValue()));
    }

    // Stage 3: one pattern node per pattern, carrying the conjunction and its index plan.
    final Map<String, List<PatternNode>> byRuleId = new LinkedHashMap<>();
    final Map<String, List<PatternNode>> byFactType = new LinkedHashMap<>();
    for (final CompiledRule rule : rules) {
      final List<PatternNode> forRule = new ArrayList<>(rule.patterns().size());
      for (final CompiledPattern pattern : rule.patterns()) {
        final List<AlphaNode> conjunction = pattern.alphaTests().stream()
            .map(test -> shared.get(test.constraint()))
            .toList();
        final PatternNode node = new PatternNode(
            nextNodeId++, pattern.factType(), conjunction, planFor(pattern));
        forRule.add(node);
        byFactType.computeIfAbsent(pattern.factType(), ignored -> new ArrayList<>()).add(node);
      }
      byRuleId.put(rule.id(), forRule);
    }

    // Stage 4: entry nodes, each carrying the distinct alpha tests its patterns need.
    final List<EntryNode> entries = new ArrayList<>(byFactType.size());
    for (final Map.Entry<String, List<PatternNode>> entry : byFactType.entrySet()) {
      final Set<AlphaNode> alphas = new LinkedHashSet<>();
      entry.getValue().forEach(pattern -> alphas.addAll(pattern.alphaNodes()));
      entries.add(new EntryNode(
          nextNodeId++, entry.getKey(), List.copyOf(alphas), entry.getValue()));
    }
    return new Network(entries, byRuleId, nextNodeId);
  }

  /**
   * The index plan for one pattern.
   *
   * <p>Driven by the pattern's <em>join</em> constraints, not by its alpha ones. A pattern's memory
   * already contains exactly the facts passing its alpha tests, so indexing one of those would build
   * a structure whose only bucket is the memory itself. What needs an index is the path some join
   * probes: given a bound order, find the customers whose {@code /id} matches.
   *
   * @param pattern the compiled pattern
   * @return the paths to index, and how
   */
  private static IndexPlan planFor(final CompiledPattern pattern) {
    final Set<JsonPointer> hashed = new LinkedHashSet<>();
    final Set<JsonPointer> sorted = new LinkedHashSet<>();
    for (final JoinTest join : pattern.joinTests()) {
      final Operator operator = join.source().op();
      if (operator == Operator.EQ) {
        hashed.add(join.path());
      } else if (isOrdering(operator)) {
        sorted.add(join.path());
      }
      // Everything else -- NE, NOT_IN, IN, MATCHES -- is what §3.3 calls not indexable. An
      // anti-match is "everything except one bucket", which an index cannot narrow. Those joins
      // fall to the post-filter, correct but linear, and §7.4's report is where they become
      // visible at authoring time rather than under load.
    }
    return hashed.isEmpty() && sorted.isEmpty()
        ? IndexPlan.none()
        : new IndexPlan(List.copyOf(hashed), List.copyOf(sorted));
  }

  /**
   * Whether an operator is an ordering comparison a sorted index can serve.
   *
   * @param operator the operator
   * @return true for the four range operators
   */
  private static boolean isOrdering(final Operator operator) {
    return operator == Operator.GT || operator == Operator.GTE
        || operator == Operator.LT || operator == Operator.LTE;
  }
}
