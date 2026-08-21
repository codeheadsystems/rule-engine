package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Operator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * The join walk, in one place because two matchers run it (spec §3.3).
 *
 * <p>Lifted out of {@code NetworkAgenda} when the Rete shape of Phase 3 needed the same walk with
 * one position pinned to a newly-arrived fact. CLAUDE.md's rule for this engine is that the
 * divergence-capable code lives once -- it is what makes the matchers agree -- and a join
 * enumerated two ways is exactly the kind of thing that drifts silently and shows up as a firing
 * the oracle produced and the network did not.
 *
 * <p>Yields tuples of handle ids indexed by <em>pattern position</em>, not by the order the plan
 * chose to bind them, so a caller builds activations binding aliases in the rule's written order
 * whatever route the walk took.
 *
 * <p>Public because the Rete shape lives in its own package, as {@code naive} and {@code network}
 * already do -- not because it is API anyone outside the engine should call.
 *
 * <p>Correctness never depends on the index. Every join is re-evaluated against whatever a probe
 * returns and every probe result is intersected with real pattern membership, so a too-wide index
 * is merely slow; a too-narrow one would be a lost firing, which is why a probe that cannot prove
 * itself safe declines rather than guessing.
 */
public final class JoinEnumerator {

  private final Network network;
  private final SessionMemories memories;
  private final WorkingMemory workingMemory;

  /**
   * Creates an enumerator over one session's memories.
   *
   * @param network the compiled node graph, shared and immutable
   * @param memories this session's node memories
   * @param workingMemory the session's working memory
   */
  public JoinEnumerator(final Network network, final SessionMemories memories,
      final WorkingMemory workingMemory) {
    this.network = Objects.requireNonNull(network, "network");
    this.memories = Objects.requireNonNull(memories, "memories");
    this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
  }

  /**
   * Enumerates every complete match of a rule, optionally with one position pinned.
   *
   * <p>Pinning is what makes the Rete shape incremental: given a fact that has just entered pattern
   * position {@code p}, the completions it takes part in are found by walking the same join with
   * {@code p} restricted to that one handle, rather than re-joining the whole working memory. The
   * walk is otherwise identical, which is the point -- the incremental result is a subset of the
   * full one by construction rather than by argument.
   *
   * @param rule the rule to match
   * @param pinnedPosition the pattern position to restrict, or -1 to enumerate everything
   * @param pinnedHandle the handle id that position must bind; ignored when nothing is pinned
   * @param sink receives each complete tuple, indexed by pattern position. The array is reused
   *     between calls, so a sink that retains it must copy
   */
  public void enumerate(final CompiledRule rule, final int pinnedPosition, final long pinnedHandle,
      final Consumer<long[]> sink) {
    final List<PatternNode> nodes = network.patternsOf(rule.id());
    if (nodes.size() != rule.patterns().size()) {
      // A rule whose patterns were not compiled into the network cannot be matched here. Returning
      // nothing would silently drop firings, so fail loudly instead.
      throw new IllegalStateException("network has " + nodes.size() + " pattern nodes for "
          + rule.id() + ", which has " + rule.patterns().size() + " patterns");
    }
    final int[] sizes = new int[nodes.size()];
    for (int position = 0; position < nodes.size(); position++) {
      // A pinned position contributes one candidate however large its memory is, and the plan
      // orders on these sizes -- so telling it the truth here is what makes it bind the pinned
      // position first and narrow everything else against it.
      sizes[position] = position == pinnedPosition ? 1 : memories.of(nodes.get(position)).size();
    }
    extend(rule, JoinPlan.of(rule, sizes), 0, new long[nodes.size()],
        pinnedPosition, pinnedHandle, sink);
  }

  /**
   * Depth-first extension of a partial binding, following the plan's order rather than the rule's.
   *
   * @param rule the rule
   * @param plan the binding order chosen for this walk
   * @param depth how many patterns are already bound
   * @param bound the handles bound so far, indexed by pattern position, not by depth
   * @param pinnedPosition the restricted position, or -1
   * @param pinnedHandle the handle that position must bind
   * @param sink receives each complete tuple
   */
  private void extend(final CompiledRule rule, final JoinPlan plan, final int depth,
      final long[] bound, final int pinnedPosition, final long pinnedHandle,
      final Consumer<long[]> sink) {
    if (depth == plan.steps().size()) {
      sink.accept(bound);
      return;
    }
    final JoinPlan.Step step = plan.steps().get(depth);
    for (final Fact candidate : candidates(rule, step, bound, pinnedPosition, pinnedHandle)) {
      if (step.conflicts(bound, candidate.handle().id()) || !satisfies(step, candidate, bound)) {
        continue;
      }
      bound[step.position()] = candidate.handle().id();
      extend(rule, plan, depth + 1, bound, pinnedPosition, pinnedHandle, sink);
    }
  }

  /**
   * The facts to consider at one step: the pattern's memory, narrowed by an index where possible.
   *
   * @param rule the rule
   * @param step the binding step
   * @param bound the handles bound so far
   * @param pinnedPosition the restricted position, or -1
   * @param pinnedHandle the handle that position must bind
   * @return the candidates, ascending by handle id
   */
  private List<Fact> candidates(final CompiledRule rule, final JoinPlan.Step step,
      final long[] bound, final int pinnedPosition, final long pinnedHandle) {
    final PatternMemory memory = memories.of(network.patternsOf(rule.id()).get(step.position()));
    if (step.position() == pinnedPosition) {
      /*
       * Still checked against membership rather than taken on trust. The caller knows this fact
       * entered this pattern, but going through the memory keeps one definition of "is a member"
       * and means a pinned walk cannot produce a match a full walk would not.
       */
      if (!memory.members().contains(pinnedHandle)) {
        return List.of();
      }
      return dereference(new TreeSet<>(List.of(pinnedHandle)));
    }
    return dereference(narrow(step, memory, bound).orElseGet(memory::members));
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
    return workingMemory.get(new FactHandle(handleId)).map(Fact::payload);
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
      workingMemory.get(new FactHandle(handleId)).ifPresent(facts::add);
    }
    return facts;
  }

  /**
   * How many facts a rule's pattern currently matches. A diagnostic, and what the plan orders on.
   *
   * @param rule the rule
   * @param position the pattern position
   * @return the memory size
   */
  public int memorySize(final CompiledRule rule, final int position) {
    return memories.of(network.patternsOf(rule.id()).get(position)).size();
  }
}
