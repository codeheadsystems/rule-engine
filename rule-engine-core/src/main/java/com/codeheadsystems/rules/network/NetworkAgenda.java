package com.codeheadsystems.rules.network;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.rule.Operator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The Phase 1 matcher: candidates come from indexed pattern memories, not from a scan.
 *
 * <p>Two savings over the oracle, and they are independent.
 *
 * <p><strong>The pattern's alpha tests are already applied.</strong> A pattern's memory holds
 * exactly the facts satisfying its constraints, maintained on insert and retract, so a fire cycle
 * enumerates matching facts rather than every fact of the type and re-testing it. That is §9's
 * Phase 1 exit criterion: single-fact rules match without a full scan.
 *
 * <p><strong>Equality and range joins probe an index.</strong> Given a bound order, the customers
 * whose {@code /id} matches are found by a hash lookup rather than by walking every customer.
 * §3.3 calls this the single biggest lever for join-heavy rule sets, "exactly what hand-rolled
 * 'simple' engines skip and then can't scale".
 *
 * <p>What this is <em>not</em> yet is the TREAT join of §4.1. Joins are still enumerated
 * pattern-by-pattern with the cross-fact tests applied afterwards; the index narrows what gets
 * enumerated but there is no left/right memory and no smaller-side decision. That is Phase 2, and
 * keeping it out of Phase 1 is what makes this phase's change differentially testable in isolation.
 *
 * <p><strong>Correctness never depends on the narrowing</strong>, and that is enforced rather than
 * assumed. An index that narrows too little is merely slow, because every join test is re-applied
 * to whatever the probe returns and every probe result is intersected with the pattern's actual
 * membership. An index that narrowed too <em>much</em> would be a lost firing, which is why the
 * probe declines rather than guesses whenever it cannot prove it is safe.
 */
public final class NetworkAgenda extends RecomputingAgenda {

  private final Network network;
  private final SessionMemories memories;

  /**
   * Creates the network matcher.
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
  protected List<Fact> candidates(final CompiledRule rule, final int position,
      final long[] bound) {
    final List<PatternNode> nodes = network.patternsOf(rule.id());
    if (nodes.size() <= position) {
      // A rule whose patterns were not compiled into the network cannot be matched here. Returning
      // nothing would silently drop firings, so fail loudly instead.
      throw new IllegalStateException(
          "no network pattern node for " + rule.id() + " position " + position);
    }
    final PatternMemory memory = memories.of(nodes.get(position));
    return dereference(narrow(rule.patterns().get(position), memory, bound)
        .orElseGet(memory::members));
  }

  /**
   * Narrows a pattern's memory using an index, when one of its joins can be probed.
   *
   * <p>Returns empty to mean "no index applied, use the whole memory" -- which is always correct,
   * just slower. Several probeable joins would each produce a candidate set; the smallest is taken,
   * because every join is re-applied afterwards anyway and the cheapest starting point wins.
   *
   * @param pattern the pattern being bound
   * @param memory its memory
   * @param bound the handle ids bound at earlier positions
   * @return the narrowed handle ids, or empty when no index could be used
   */
  private Optional<SortedSet<Long>> narrow(final CompiledPattern pattern,
      final PatternMemory memory, final long[] bound) {
    SortedSet<Long> best = null;
    for (final JoinTest join : pattern.joinTests()) {
      final Optional<JsonNode> other = payloadAt(bound[join.otherIndex()]);
      if (other.isEmpty()) {
        continue;
      }
      final Optional<SortedSet<Long>> probed = probe(join, memory, other.get());
      if (probed.isPresent() && (best == null || probed.get().size() < best.size())) {
        best = probed.get();
      }
    }
    return Optional.ofNullable(best).map(probed -> intersectWithMembership(probed, memory));
  }

  /**
   * Keeps only probe results that are actually in the pattern's memory.
   *
   * <p><strong>This is what makes the index a pure optimisation.</strong> Without it, an index and a
   * membership set that disagreed for any reason would produce <em>wrong matches</em> rather than
   * merely slow ones -- and that is not a hypothetical: the base class contract is that
   * {@link #candidates} returns facts already satisfying the pattern's single-fact tests, so
   * nothing downstream re-checks them. A handle left in a stale bucket therefore sails past the
   * alpha tests it no longer passes, and the join filter has no reason to reject it.
   *
   * <p>Found by deliberately corrupting index maintenance and watching the differential suite: the
   * network invented a self-join match the oracle did not produce. The corruption was artificial;
   * the exposure was not. Phase 2 adds more index-maintenance paths, and every one of them would
   * inherit the same knife-edge.
   *
   * <p>The cost is one membership check per probed handle, against a set that is by construction at
   * least as small as the type's population. Cheap, and it converts "the index must be perfect or we
   * are silently wrong" into "the index must be perfect or we are slightly slow".
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
   * Probes one join against an index, if the operator and the plan allow it.
   *
   * @param join the join test
   * @param memory the memory to probe
   * @param otherPayload the payload of the already-bound fact
   * @return the matching handle ids, or empty when this join is not indexable
   */
  private static Optional<SortedSet<Long>> probe(final JoinTest join, final PatternMemory memory,
      final JsonNode otherPayload) {
    final JsonNode value = join.probeValue(otherPayload);
    final Operator operator = join.source().op();
    return switch (operator) {
      case EQ -> memory.probeEqual(join.path(), value);
      // The bound comes from the other side, so the inequality reads "this pattern's field is
      // greater than the bound value" -- which is a tail of the sorted index, not a head.
      case GT -> memory.probeRange(join.path(), Optional.of(value), false, Optional.empty(), false);
      case GTE -> memory.probeRange(join.path(), Optional.of(value), true, Optional.empty(), false);
      case LT -> memory.probeRange(join.path(), Optional.empty(), false, Optional.of(value), false);
      case LTE -> memory.probeRange(join.path(), Optional.empty(), false, Optional.of(value), true);
      // NE and NOT_IN are anti-matches: an index cannot narrow them, because the answer is
      // "everything except one bucket". MATCHES and IN over a join reference are not index shapes
      // either. §3.3 names all of these as unindexable, and they fall to the post-filter.
      default -> Optional.empty();
    };
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
   * A convenience for tests and diagnostics: how many facts a rule's pattern currently matches.
   *
   * @param rule the rule
   * @param position the pattern position
   * @return the memory size, which is what the matcher enumerates instead of the whole type
   */
  public int memorySize(final CompiledRule rule, final int position) {
    return memories.of(network.patternsOf(rule.id()).get(position)).size();
  }
}
