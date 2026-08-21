package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Operator;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The indexed matcher: candidates come from pattern memories, and joins probe the smaller side.
 *
 * <p>Three savings over the oracle, each independent of the others.
 *
 * <p><strong>The pattern's alpha tests are already applied.</strong> A pattern's memory holds
 * exactly the facts satisfying its constraints, maintained on insert and retract, so a fire cycle
 * enumerates matching facts rather than every fact of the type and re-testing it.
 *
 * <p><strong>Joins probe an index rather than scanning.</strong> Given a bound order, the customers
 * whose {@code /id} matches are found by a hash lookup. §3.3 calls this "the single biggest lever
 * for join-heavy rule sets, and exactly what hand-rolled 'simple' engines skip and then can't
 * scale".
 *
 * <p><strong>The binding order is chosen per fire cycle, smallest memory first.</strong> That is the
 * other half of §3.3's sentence -- which side is smaller "is a per-fire decision under TREAT, since
 * both memory sizes are known" -- and it is what stops a rule's <em>written</em> order from
 * dictating its cost. See {@link JoinPlan}.
 *
 * <p><strong>Correctness never depends on any of it</strong>, and that is enforced rather than
 * assumed. Every join is re-evaluated against whatever a probe returns, and every probe result is
 * intersected with the pattern's actual membership, so an index that narrows too little is merely
 * slow. An index that narrowed too <em>much</em> would be a lost firing, which is why the probe
 * declines rather than guesses whenever it cannot prove it is safe. The differential suite against
 * the oracle is what keeps that honest.
 */
public final class NetworkAgenda extends RecomputingAgenda {

  private final Network network;
  private final SessionMemories memories;

  /**
   * Creates the indexed matcher.
   *
   * @param rules the compiled rules, in compilation order
   * @param network the compiled node graph, shared and immutable
   * @param memories this session's node memories
   * @param workingMemory the session's working memory
   * @param refraction the session's refraction memory
   * @param strategy how ties are broken
   * @param listeners the session's listeners, in registration order
   * @param strict whether to assert the conflict-resolution contract
   */
  public NetworkAgenda(final List<CompiledRule> rules, final Network network,
      final SessionMemories memories, final WorkingMemory workingMemory,
      final RefractionMemory refraction, final ConflictResolutionStrategy strategy,
      final List<RuleEngineListener> listeners, final boolean strict) {
    super(rules, workingMemory, refraction, strategy, listeners, strict);
    this.network = Objects.requireNonNull(network, "network");
    this.memories = Objects.requireNonNull(memories, "memories");
  }

  @Override
  protected List<Activation> matchesOf(final CompiledRule rule, final List<String> aliases) {
    final List<PatternNode> nodes = network.patternsOf(rule.id());
    if (nodes.size() != rule.patterns().size()) {
      // A rule whose patterns were not compiled into the network cannot be matched here. Returning
      // nothing would silently drop firings, so fail loudly instead.
      throw new IllegalStateException("network has " + nodes.size() + " pattern nodes for "
          + rule.id() + ", which has " + rule.patterns().size() + " patterns");
    }
    final int[] sizes = new int[nodes.size()];
    for (int position = 0; position < nodes.size(); position++) {
      sizes[position] = memories.of(nodes.get(position)).size();
    }
    final List<Activation> matches = new ArrayList<>();
    extend(rule, aliases, JoinPlan.of(rule, sizes), 0, new long[nodes.size()], matches);
    return matches;
  }

  /**
   * Depth-first extension of a partial binding, following the plan's order rather than the rule's.
   *
   * @param rule the rule
   * @param aliases the rule's aliases
   * @param plan the binding order chosen for this recomputation
   * @param depth how many patterns are already bound
   * @param bound the handles bound so far, indexed by <em>pattern position</em>, not by depth
   * @param matches the list to append completed matches to
   */
  private void extend(final CompiledRule rule, final List<String> aliases, final JoinPlan plan,
      final int depth, final long[] bound, final List<Activation> matches) {
    if (depth == plan.steps().size()) {
      // `bound` is indexed by pattern position throughout, so the tuple binds aliases in the rule's
      // written order however the plan chose to reach them.
      matches.add(buildActivation(rule, bound, aliases));
      return;
    }
    final JoinPlan.Step step = plan.steps().get(depth);
    for (final Fact candidate : candidates(rule, step, bound)) {
      if (step.conflicts(bound, candidate.handle().id()) || !satisfies(step, candidate, bound)) {
        continue;
      }
      bound[step.position()] = candidate.handle().id();
      extend(rule, aliases, plan, depth + 1, bound, matches);
    }
  }

  /**
   * Whether a candidate passes every join that became applicable at this step.
   *
   * @param step the binding step
   * @param candidate the candidate fact
   * @param bound the handles bound so far
   * @return whether it passes
   */
  private boolean satisfies(final JoinPlan.Step step, final Fact candidate, final long[] bound) {
    for (final JoinPlan.Edge edge : step.edges()) {
      final Optional<JsonNode> other = payloadAt(bound[edge.otherPosition()]);
      if (other.isEmpty() || !edge.holds(candidate.payload(), other.get())) {
        return false;
      }
    }
    return true;
  }

  /**
   * The facts to consider at one step: the pattern's memory, narrowed by an index where possible.
   *
   * @param rule the rule
   * @param step the binding step
   * @param bound the handles bound so far
   * @return the candidates, ascending by handle id
   */
  private List<Fact> candidates(final CompiledRule rule, final JoinPlan.Step step,
      final long[] bound) {
    final PatternMemory memory = memories.of(network.patternsOf(rule.id()).get(step.position()));
    return dereference(narrow(step, memory, bound).orElseGet(memory::members));
  }

  /**
   * Narrows a pattern's memory using an index, when one of the step's joins can be probed.
   *
   * <p>Returns empty to mean "no index applied, use the whole memory" -- always correct, just
   * slower. Several probeable joins would each produce a candidate set; the smallest is taken,
   * because every join is re-applied afterwards anyway and the cheapest starting point wins.
   *
   * @param step the binding step
   * @param memory the pattern's memory
   * @param bound the handle ids bound so far
   * @return the narrowed handle ids, or empty when no index could be used
   */
  private Optional<SortedSet<Long>> narrow(final JoinPlan.Step step, final PatternMemory memory,
      final long[] bound) {
    SortedSet<Long> best = null;
    for (final JoinPlan.Edge edge : step.edges()) {
      final Optional<JsonNode> other = payloadAt(bound[edge.otherPosition()]);
      if (other.isEmpty()) {
        continue;
      }
      final Optional<SortedSet<Long>> probed = probe(edge, memory, other.get());
      if (probed.isPresent() && (best == null || probed.get().size() < best.size())) {
        best = probed.get();
      }
    }
    return Optional.ofNullable(best).map(probed -> intersectWithMembership(probed, memory));
  }

  /**
   * Probes one join against an index, if the operator and the plan allow it.
   *
   * @param edge the join edge, tagged with which end is being bound
   * @param memory the memory to probe
   * @param otherPayload the payload of the already-bound fact at the other end
   * @return the matching handle ids, or empty when this join is not indexable from this end
   */
  private static Optional<SortedSet<Long>> probe(final JoinPlan.Edge edge,
      final PatternMemory memory, final JsonNode otherPayload) {
    final Optional<Operator> operator = edge.probeOperator();
    if (operator.isEmpty()) {
      // The relation cannot be read backwards -- IN and NOT_IN relate a scalar to an array, MATCHES
      // to a pattern -- so this end cannot be probed even though the join is still evaluated.
      return Optional.empty();
    }
    final JsonNode value = edge.probeValue(otherPayload);
    final JsonPointer path = edge.probePath();
    return switch (operator.get()) {
      case EQ -> memory.probeEqual(path, value);
      case GT -> memory.probeRange(path, Optional.of(value), false, Optional.empty(), false);
      case GTE -> memory.probeRange(path, Optional.of(value), true, Optional.empty(), false);
      case LT -> memory.probeRange(path, Optional.empty(), false, Optional.of(value), false);
      case LTE -> memory.probeRange(path, Optional.empty(), false, Optional.of(value), true);
      // NE and NOT_IN are anti-matches: an index cannot narrow "everything except one bucket".
      // §3.3 names them, along with MATCHES, as unindexable; they fall to the post-filter.
      default -> Optional.empty();
    };
  }

  /**
   * Keeps only probe results that are actually in the pattern's memory.
   *
   * <p><strong>This is what makes the index a pure optimisation.</strong> Without it, an index and a
   * membership set that disagreed for any reason would produce <em>wrong matches</em> rather than
   * merely slow ones -- a handle left in a stale bucket sails past the alpha tests it no longer
   * passes, because nothing downstream re-checks them.
   *
   * <p>Found by deliberately corrupting index maintenance and watching the differential suite: the
   * network invented a self-join match the oracle did not produce. The corruption was artificial;
   * the exposure was not, and every index-maintenance path added since inherits the same knife-edge.
   *
   * @param probed the handle ids an index returned
   * @param memory the pattern's memory
   * @return the probed ids that really do match the pattern
   */
  private static SortedSet<Long> intersectWithMembership(final SortedSet<Long> probed,
      final PatternMemory memory) {
    if (probed.isEmpty()) {
      return probed;
    }
    final SortedSet<Long> members = memory.members();
    final SortedSet<Long> intersection = new TreeSet<>();
    for (final Long handleId : probed) {
      if (members.contains(handleId)) {
        intersection.add(handleId);
      }
    }
    return intersection;
  }

  /**
   * The payload of a bound fact.
   *
   * @param handleId the handle id
   * @return the payload, or empty if the fact is gone
   */
  private Optional<JsonNode> payloadAt(final long handleId) {
    return workingMemory().get(new FactHandle(handleId)).map(Fact::payload);
  }

  /**
   * Turns handle ids into facts, dropping any that are no longer asserted.
   *
   * @param handleIds the ids, ascending
   * @return the facts, ascending by handle id
   */
  private List<Fact> dereference(final SortedSet<Long> handleIds) {
    final List<Fact> facts = new ArrayList<>(handleIds.size());
    for (final long handleId : handleIds) {
      workingMemory().get(new FactHandle(handleId)).ifPresent(facts::add);
    }
    return facts;
  }

  /**
   * How many facts a rule's pattern currently matches. A diagnostic, and what the plan orders on.
   *
   * @param rule the rule
   * @param position the pattern position
   * @return the memory size, which is what the matcher enumerates instead of the whole type
   */
  public int memorySize(final CompiledRule rule, final int position) {
    return memories.of(network.patternsOf(rule.id()).get(position)).size();
  }
}
