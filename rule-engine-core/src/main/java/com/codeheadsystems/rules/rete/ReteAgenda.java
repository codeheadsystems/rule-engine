package com.codeheadsystems.rules.rete;

import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RecomputingAgenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.Activation;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The streaming matcher: joins are materialised as facts arrive, not recomputed per fire (§11.1).
 *
 * <p>Where {@code NetworkAgenda} walks the join for every dirty rule at fire time, this walks it
 * once per arriving fact -- with that fact's position pinned -- and keeps the completions. A fire
 * cycle then reads a memory instead of re-joining.
 *
 * <p><strong>That amortises the join and not yet the fire cycle, and the difference is measurable
 * rather than a caveat.</strong> Streaming 1000 then 3000 orders against 200 preloaded customers,
 * firing after each insert: 333ms then 1245ms under TREAT, 92ms then 545ms here. Roughly a 2-3x
 * constant, and <em>both</em> curves are super-linear -- three times the facts costs this shape
 * about six times the time. The join really is paid once per fact, but
 * {@code RecomputingAgenda.materialise} still replaces a dirty rule's whole conflict-set slice per
 * fire cycle, so an activation is constructed for every held match every time the rule is dirty.
 * §9's "amortizes join cost" is met; §9's streaming workload is not yet served at the complexity a
 * reader would assume from that phrase, and §11.2's differential propagation is the commit that
 * changes it.
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
    for (int index = 0; index < this.rules.size(); index++) {
      this.betaByRule.add(new BetaMemory());
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
   * <p>Activations are rebuilt on every call rather than cached alongside the tuples, and that is
   * deliberate: {@code buildActivation} notifies listeners, so caching them would make
   * {@code onActivationCreated} fire a different number of times here than under TREAT, and
   * {@code MatcherEquivalence} compares emitted events as well as firings. The join is what this
   * class exists to avoid repeating; constructing an activation per held match is not.
   *
   * <p>The callback <em>count</em> matches the recomputing shapes; the <em>order</em> does not, and
   * nothing asserts that it should. Matches are notified in derivation order here and in plan order
   * there, so a {@code TracingListener} attached to two strategies sees the same events in
   * different sequences. Firing order is unaffected -- conflict resolution is a total order -- so
   * this shows up in a trace rather than in behaviour.
   *
   * @param rule the rule
   * @param aliases the rule's aliases
   * @return the rule's current matches
   */
  @Override
  protected List<Activation> matchesOf(final CompiledRule rule, final List<String> aliases) {
    final BetaMemory beta = betaByRule.get(indexOf(rule));
    final List<Activation> matches = new ArrayList<>(beta.size());
    for (final Tuple tuple : beta.tuples()) {
      matches.add(buildActivation(rule, tuple.boundFacts(), aliases));
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
      joins.enumerate(rule, site.position(), fact.handle().id(),
          bound -> beta.add(new Tuple(bound, aliasesByRule.get(site.ruleIndex()))));
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
      betaByRule.get(site.ruleIndex()).removeInvolving(fact.handle().id());
    }
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
