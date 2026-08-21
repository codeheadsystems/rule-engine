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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

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
  private final JoinEnumerator joins;

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
    this.joins = new JoinEnumerator(network, memories, workingMemory);
  }

  @Override
  protected List<Activation> matchesOf(final CompiledRule rule, final List<String> aliases) {
    final List<Activation> matches = new ArrayList<>();
    // Nothing pinned: TREAT re-joins the whole working memory at fire time, which is the bargain
    // §4.1 describes. The Rete shape calls the same walk with one position pinned instead.
    joins.enumerate(rule, -1, 0L, bound -> matches.add(buildActivation(rule, bound, aliases)));
    return matches;
  }

  /**
   * How many facts a rule's pattern currently matches. A diagnostic, and what the plan orders on.
   *
   * @param rule the rule
   * @param position the pattern position
   * @return the memory size, which is what the matcher enumerates instead of the whole type
   */
  public int memorySize(final CompiledRule rule, final int position) {
    return joins.memorySize(rule, position);
  }
}
