package com.codeheadsystems.rules.rete;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.match.Tuple;
import com.codeheadsystems.rules.network.JoinEnumerator;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.network.SessionMemories;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The streaming matcher: joins are materialised as facts arrive, not recomputed per fire (§11.1).
 *
 * <p>Where {@code NetworkAgenda} walks the join for every dirty rule at fire time, this walks it
 * once per arriving fact -- with that fact's position pinned -- and keeps the completions. A fire
 * cycle then reads a memory instead of re-joining.
 *
 * <p><strong>It amortises the fire cycle too, and only since §4.3's shape landed.</strong> Until
 * then this class held its matches and then rebuilt an activation for every one of them on every
 * fire, ranked them, and discarded all but one to refraction -- so a session holding four thousand
 * matches paid four thousand allocations to fire one, measured at 1.5MB of garbage per firing and
 * 99.5% of the operation. The join was amortised and the thing reading it was not.
 *
 * <p>Now the conflict set is <em>pushed and pulled</em>: {@link #pendingByRule} holds the matches
 * that have not fired, a match enters when it is derived and leaves when it fires or when one of
 * its facts goes, and a fire cycle ranks what is waiting instead of what is held. On the streaming
 * benchmark at a working set of four thousand that took an insert-and-fire from 554us to 3.8us and
 * its allocation from 1.5MB to 8.3KB per operation, and -- the part that matters more than either
 * number -- the fire cycle stopped growing with the working set at all: 0.77us, 1.02us, 1.12us
 * across a sixteenfold range where it had been 19.9us, 100.5us, 551.4us.
 *
 * <p><strong>TREAT is untouched by this and that is expected</strong>, not a failed optimisation:
 * §4.3's push-and-pull interface is the Rete one, and the recomputing shapes have nothing to push.
 * See {@code docs/benchmarks.md} for both columns and for what the measurement does not establish.
 *
 * <p><strong>The same join walk, not an agreeing one.</strong> Both matchers call
 * {@link JoinEnumerator}; this one passes a pinned position. That makes an incremental result a
 * subset of the full result by construction, which is the only argument for correctness here that
 * does not decay -- CLAUDE.md's rule about keeping divergence-capable code in one place is what
 * §9's "TREAT and Rete produce identical firing sequences" rests on, and a second hand-written join
 * would put that back in play at every future change.
 *
 * <p><strong>What it costs, since §11.1 asks for it plainly.</strong> Persistent beta memory is
 * real mutable state. A fact that is never retracted keeps its matches forever, so a streaming
 * session that only inserts grows without bound -- which is a property of the workload rather than
 * a defect, and why §4.4's eviction exists. Every match a fact takes part in is removed on retract
 * through {@link BetaMemory#removeInvolving}, index entries included.
 *
 * <p><strong>Where it is slower than TREAT.</strong> A batch session that inserts N facts and fires
 * once does the join work either way, and pays here for maintaining a memory it reads exactly once.
 * §11.1 chose one-shot and batch as the v1 default for that reason; this is selected per session
 * through {@code SessionOptions}, not made the default.
 */
public final class ReteAgenda extends RecomputingAgenda {

  private final List<CompiledRule> rules;
  private final JoinEnumerator joins;
  private final List<BetaMemory> betaByRule;
  private final List<List<String>> aliasesByRule;

  /**
   * Per rule, the held matches that have not fired yet -- §4.3's conflict set, pushed and pulled.
   *
   * <p><strong>This is the whole of Phase 3's last slice, and it is worth being precise about what
   * it changes.</strong> The beta memory holds every match; this holds the ones that could still
   * fire. Before it existed, a fire cycle asked the beta memory for everything and built an
   * activation per held match -- so a session holding four thousand matches allocated four thousand
   * activations, ranked them, and discarded all but one to refraction, on every single firing. That
   * was measured at 1.5MB of garbage per fire and about 138ns per held match, and it is why §9's
   * streaming workload was not served at the complexity a reader would assume.
   *
   * <p>Maintained by the two callbacks the shape already receives: a match enters when it is
   * derived and leaves when it fires or when one of its facts goes. §4.3 describes the interface as
   * {@code activate}/{@code deactivate}/{@code deactivateAllInvolving}; those are not public methods
   * here, for §4.3's own stated reason -- it declined to specify methods no code path invokes, and
   * nothing outside this class would call them. {@code factInserted} activates,
   * {@code onConsumed} deactivates, and {@code factRetracted} deactivates everything involving a
   * handle, which is that trio under the names the {@code Agenda} interface already had.
   *
   * <p>A {@code LinkedHashSet} in derivation order, because {@link #matchesOf} reads it and §7.3
   * covers any path to the agenda.
   */
  private final List<Set<Tuple>> pendingByRule;

  /**
   * Which rules have a pattern of a given fact type, and at which positions.
   *
   * <p>Computed once here rather than asked of the network per insert. An insert only has to walk
   * the rules that could possibly match the arriving type, and only pin the positions where it
   * could possibly sit.
   */
  private final Map<String, List<PatternSite>> sitesByFactType;

  /** Rule instance to its position, so {@code matchesOf} is a lookup rather than a scan. */
  private final Map<CompiledRule, Integer> ruleIndices;

  /**
   * Creates the streaming matcher.
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
  public ReteAgenda(final List<CompiledRule> rules, final Network network,
      final SessionMemories memories, final WorkingMemory workingMemory,
      final RefractionMemory refraction, final ConflictResolutionStrategy strategy,
      final List<RuleEngineListener> listeners, final boolean strict) {
    super(rules, workingMemory, refraction, strategy, listeners, strict);
    this.rules = List.copyOf(rules);
    this.joins = new JoinEnumerator(Objects.requireNonNull(network, "network"),
        Objects.requireNonNull(memories, "memories"), workingMemory);
    this.betaByRule = new ArrayList<>(this.rules.size());
    this.pendingByRule = new ArrayList<>(this.rules.size());
    for (int index = 0; index < this.rules.size(); index++) {
      this.betaByRule.add(new BetaMemory());
      this.pendingByRule.add(new LinkedHashSet<>());
    }
    this.sitesByFactType = indexPatternSites(this.rules);
    this.ruleIndices = new IdentityHashMap<>(this.rules.size());
    for (int index = 0; index < this.rules.size(); index++) {
      this.ruleIndices.put(this.rules.get(index), index);
    }
    this.aliasesByRule = this.rules.stream()
        .map(rule -> rule.patterns().stream().map(CompiledPattern::alias).toList())
        .toList();
  }

  /**
   * Reads the materialised matches rather than walking the join.
   *
   * <p>Activations are rebuilt on every call rather than cached alongside the tuples, and the reason
   * is {@code Activation.recency}: it is a snapshot taken in the constructor, so an activation
   * cached beside a tuple that outlived an update to its facts would carry a stale recency into
   * conflict resolution, which is a firing-order defect rather than a trace one. An earlier version
   * of this paragraph gave listener-callback counts as the reason; that reason expired when the
   * conflict set stopped being rebuilt from every held match, and the decision it defended is right
   * for the better reason.
   *
   * <p><strong>Two trace differences from the recomputing shapes, neither of which reaches
   * behaviour.</strong> {@code onActivationCreated} fires a different number of times -- once per
   * pending match per cycle here, once per held match per cycle there -- and in derivation rather
   * than plan order. And {@code onActivationSuppressed} with {@code REFRACTED} is effectively
   * TREAT-only: a refracted match is declined at {@link #factInserted} or pulled at
   * {@code onConsumed}, so selection never meets one to report. §4.3's "selection still checks, for
   * every shape" is about the check, not about that callback. {@code FiringSequence} compares
   * firings and emitted events rather than listener traffic, so {@code MatcherEquivalence} is
   * unaffected -- but a listener counting either callback across matchers is comparing different
   * things, and nothing in the engine will tell it so.
   *
   * @param rule the rule
   * @param aliases the rule's aliases
   * @return the rule's current matches
   */
  @Override
  protected List<Activation> matchesOf(final CompiledRule rule, final List<String> aliases) {
    final Set<Tuple> pending = pendingByRule.get(indexOf(rule));
    if (pending.isEmpty()) {
      return List.of();
    }
    final List<Activation> matches = new ArrayList<>(pending.size());
    for (final Tuple tuple : pending) {
      matches.add(buildActivation(rule, tuple));
    }
    return matches;
  }

  /**
   * Extends the beta memory with every match the arriving fact completes.
   *
   * <p>Called after the alpha network has taken the fact, so the pattern memories this walk reads
   * already include it -- which is what lets a rule with two patterns of one type match a fact
   * against facts of the same type, itself excluded by the implicit inequality between distinct
   * aliases.
   *
   * @param fact the fact that has just entered working memory
   */
  @Override
  public void factInserted(final Fact fact) {
    /*
     * A throw part-way through this loop is a failure mode the recomputing shapes cannot have, and
     * it is worth naming rather than discovering. The fact is already in working memory and already
     * in the alpha network; if the walk throws after some sites have been extended, the beta memory
     * is permanently incomplete for the rest, nothing marks the session failed, and every later
     * fire cycle quietly under-fires. Under TREAT the same throw recurs loudly at every fire,
     * because there is no state to be left half-built.
     *
     * Not guarded, and here is the reasoning rather than an omission: this walk runs no user code
     * and no rule-authored code. Alpha tests are not re-run in it, Comparisons returns false rather
     * than throwing on a type mismatch, and `matches` patterns are precompiled re2j. The reachable
     * throws are engine invariant violations -- a rule whose patterns are not in the network.
     *
     * Note what does NOT rescue it, because an earlier draft of this comment claimed it did: under
     * TREAT such a violation throws from matchesOf at every fire, loudly and forever. Here matchesOf
     * reads the beta memory and never calls enumerate, so the same violation throws once at insert
     * and then under-fires in silence. That is the paragraph's opening restated, not an exception to
     * it. If this loop ever grows a call into a HostFunction or an expression, the reasoning above
     * expires and this needs to mark the session unusable instead.
     */
    for (final PatternSite site : sitesByFactType.getOrDefault(fact.type(), List.of())) {
      final CompiledRule rule = rules.get(site.ruleIndex());
      final BetaMemory beta = betaByRule.get(site.ruleIndex());
      final Set<Tuple> pending = pendingByRule.get(site.ruleIndex());
      joins.enumerate(rule, site.position(), fact.handle().id(), bound -> {
        final Tuple tuple = new Tuple(bound, aliasesByRule.get(site.ruleIndex()));
        // §4.3's activate. Only a match that is new to the beta memory is new to the agenda: the
        // same completion can be reached from either end of a join, and adding it twice would put
        // one tuple in the conflict set twice.
        if (!beta.add(tuple)) {
          return;
        }
        /*
         * §4.4's suppression at creation, and it is a cost measure rather than a correctness one --
         * selection checks refraction for every shape, always. A match arrives already refracted by
         * exactly one route: §3.4.1's effective update destroys and re-derives every match binding
         * the fact but clears refraction only for the rules testing a changed path, so a rule that
         * tests nothing that changed gets its match handed straight back. Holding it would let the
         * conflict set drift back toward a copy of the whole join memory, which is the cost this
         * shape exists to remove.
         */
        if (!isRefracted(new ActivationKey(rule.id(), tuple.boundFacts()))) {
          pending.add(tuple);
        }
      });
    }
  }

  /**
   * Drops every match the departing fact took part in.
   *
   * <p>Called with the payload the fact had when asserted, like every other retract path (§3.4.1
   * step 3) -- though this one needs only the handle, because a materialised match is identified by
   * the handles it binds rather than by anything it read.
   *
   * @param fact the fact leaving working memory
   */
  @Override
  public void factRetracted(final Fact fact) {
    for (final PatternSite site : sitesByFactType.getOrDefault(fact.type(), List.of())) {
      // §4.3's deactivateAllInvolving, and the reason BetaMemory.removeInvolving returns what it
      // removed rather than a count: the matches that leave the join memory are exactly the ones
      // that must leave the conflict set, and the reverse index has already found them.
      final List<Tuple> removed = betaByRule.get(site.ruleIndex()).removeInvolving(
          fact.handle().id());
      if (!removed.isEmpty()) {
        pendingByRule.get(site.ruleIndex()).removeAll(removed);
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>§4.3's {@code deactivate}. A held match that has fired is out of the conflict set for good
   * unless something re-derives it -- refraction would suppress it at every selection anyway, so
   * keeping it would be a scan per fire over matches that cannot fire. This is the half of the
   * shape that turns "an activation per held match, per firing" into "an activation per match, once".
   */
  @Override
  protected void onConsumed(final Activation activation) {
    // indexOf, not a null-tolerant lookup: a rule this agenda does not know is the same engine
    // invariant violation it refuses to scan harder for, and swallowing it here would leave a fired
    // match pending for good -- the one failure pendingMatchCount is advertised as revealing.
    pendingByRule.get(indexOf(activation.rule())).remove(activation.tuple());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Summed across rules rather than held as a counter, because this is a diagnostic read once
   * per assertion or per health check, and a counter would be one more thing every add and remove
   * had to keep honest.
   */
  @Override
  public int materialisedMatchCount() {
    int total = 0;
    for (final BetaMemory beta : betaByRule) {
      total += beta.size();
    }
    return total;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The number a fire cycle is proportional to under this shape, and the one that would reveal a
   * match this class failed to pull back out after firing. In a streaming session at a steady state
   * it sits near zero however many matches the beta memory holds -- which is the whole claim of
   * §4.3, stated as something a test can assert.
   *
   * <p><strong>With one exception, and it is the one an operator reading this number needs to
   * know.</strong> A §6.4 {@code condition} is applied in {@code RecomputingAgenda.postFilter},
   * after {@link #matchesOf} has returned, so a match the condition rejects is never fired and
   * therefore never pulled back out -- it stays here and is rebuilt and re-evaluated on every cycle
   * the rule is dirty for. A rule set that rejects most of what it matches drives this number toward
   * the beta memory's, and §4.3's win is lost for it. That is a cost rather than a defect, since the
   * post-filter re-runs every cycle and a match whose condition starts holding fires; it is pinned
   * by {@code CelEngineTest.theConflictSetHoldsWhatAConditionRejects}, which also records why
   * pruning on rejection was not built.
   */
  @Override
  public int pendingMatchCount() {
    int total = 0;
    for (final Set<Tuple> pending : pendingByRule) {
      total += pending.size();
    }
    return total;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Summed across rules, so a handle bound by matches of two rules counts twice. That is the
   * number a leak shows up in -- the question is whether any index still tracks a fact that is
   * gone, not how many distinct facts are tracked.
   */
  @Override
  public int materialisedHandleCount() {
    int total = 0;
    for (final BetaMemory beta : betaByRule) {
      total += beta.indexedHandles();
    }
    return total;
  }

  /**
   * The position of a rule in compilation order.
   *
   * @param rule the rule
   * @return its index
   */
  private int indexOf(final CompiledRule rule) {
    final Integer index = ruleIndices.get(rule);
    if (index == null) {
      // Identity, not equality: the base class hands back the very instances it was constructed
      // with, and a rule that is equal-but-not-identical would be a different agenda's rule with a
      // beta memory that does not describe it. Failing is the right answer, not scanning harder.
      throw new IllegalStateException("rule " + rule.id() + " is not in this agenda's rule set");
    }
    return index;
  }

  /**
   * Builds the fact-type to pattern-site index.
   *
   * @param rules the compiled rules
   * @return every position at which a type could bind, keyed by type
   */
  private static Map<String, List<PatternSite>> indexPatternSites(final List<CompiledRule> rules) {
    final Map<String, List<PatternSite>> sites = new LinkedHashMap<>();
    for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
      final CompiledRule rule = rules.get(ruleIndex);
      for (int position = 0; position < rule.patterns().size(); position++) {
        sites.computeIfAbsent(rule.patterns().get(position).factType(),
            ignored -> new ArrayList<>()).add(new PatternSite(ruleIndex, position));
      }
    }
    /*
     * LinkedHashMap with copied values, not Map.copyOf. Two reasons, and only the second bites
     * today. Map.copyOf keeps the mutable ArrayList values, so the result is immutable only one
     * level deep. And it returns an ImmutableCollections.MapN, whose iteration order is salted per
     * JVM -- this map is on the beta-memory maintenance path, which is a path to the agenda, and
     * §7.3's determinism rule covers exactly that. Nothing iterates it now; the next person to add
     * a diagnostic that does should not have to notice.
     */
    final Map<String, List<PatternSite>> copied = new LinkedHashMap<>();
    sites.forEach((factType, positions) -> copied.put(factType, List.copyOf(positions)));
    return Collections.unmodifiableMap(copied);
  }

  /**
   * One place a fact type can bind: a rule and a pattern position within it.
   *
   * @param ruleIndex the rule's position in compilation order
   * @param position the pattern position within that rule
   */
  private record PatternSite(int ruleIndex, int position) {}
}
