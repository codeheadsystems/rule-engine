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
import tools.jackson.core.JsonPointer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
      final Map<Integer, IndexPlan> plans = plansFor(rule);
      for (int position = 0; position < rule.patterns().size(); position++) {
        final CompiledPattern pattern = rule.patterns().get(position);
        final List<AlphaNode> conjunction = pattern.alphaTests().stream()
            .map(test -> shared.get(test.constraint()))
            .toList();
        final PatternNode node = new PatternNode(nextNodeId++, pattern.factType(), conjunction,
            plans.getOrDefault(position, IndexPlan.none()));
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
   * The index plans for every pattern of one rule.
   *
   * <p>Rule-scoped rather than pattern-scoped, because a join has <em>two</em> ends and either may
   * be the one worth probing. Given {@code Customer(id == $ref o.customerId)}, the constraint is
   * written on the customer pattern -- but §3.3 makes choosing the smaller side a per-fire decision,
   * so the matcher may want to bind customers first and then find the orders belonging to one. That
   * needs an index on {@code Order./customerId}, a path the constraint does not sit on. Planning one
   * pattern at a time can only ever see half of each edge.
   *
   * <p>Driven by <em>join</em> constraints, not alpha ones: a pattern's memory already contains
   * exactly the facts passing its alpha tests, so indexing one of those would build a structure
   * whose only bucket is the memory itself.
   *
   * @param rule the compiled rule
   * @return the plan for each pattern position that needs one
   */
  private static Map<Integer, IndexPlan> plansFor(final CompiledRule rule) {
    final Map<Integer, Set<JsonPointer>> hashed = new LinkedHashMap<>();
    final Map<Integer, Set<JsonPointer>> sorted = new LinkedHashMap<>();
    for (int position = 0; position < rule.patterns().size(); position++) {
      for (final JoinTest join : rule.patterns().get(position).joinTests()) {
        record(hashed, sorted, position, join.path(), join.source().op());
        // The far end of the same edge, read backwards. Everything section 3.3 calls unindexable --
        // NE, NOT_IN, MATCHES -- reverses to nothing and is simply not indexed, on either side.
        join.source().op().reversed().ifPresent(reversed ->
            record(hashed, sorted, join.otherIndex(), join.otherAccessor().pointer(), reversed));
      }
    }
    final Map<Integer, IndexPlan> plans = new LinkedHashMap<>();
    Stream.concat(hashed.keySet().stream(), sorted.keySet().stream()).distinct().forEach(position ->
        plans.put(position, new IndexPlan(
            List.copyOf(hashed.getOrDefault(position, Set.of())),
            List.copyOf(sorted.getOrDefault(position, Set.of())))));
    return plans;
  }

  /**
   * Records one path as needing an index, if its operator can use one.
   *
   * @param hashed the equality-index requirements, by pattern position
   * @param sorted the range-index requirements, by pattern position
   * @param position the pattern the path belongs to
   * @param path the path
   * @param operator how the join reads that path
   */
  private static void record(final Map<Integer, Set<JsonPointer>> hashed,
      final Map<Integer, Set<JsonPointer>> sorted, final int position, final JsonPointer path,
      final Operator operator) {
    if (operator == Operator.EQ) {
      hashed.computeIfAbsent(position, ignored -> new LinkedHashSet<>()).add(path);
    } else if (isOrdering(operator)) {
      sorted.computeIfAbsent(position, ignored -> new LinkedHashSet<>()).add(path);
    }
    // Everything else is what section 3.3 calls not indexable. An anti-match is "everything except
    // one bucket", which an index cannot narrow. Those joins fall to the post-filter, correct but
    // linear, and section 7.4's report is where they become visible at authoring time.
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
