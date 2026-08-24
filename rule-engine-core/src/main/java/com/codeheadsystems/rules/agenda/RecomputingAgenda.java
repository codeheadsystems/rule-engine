package com.codeheadsystems.rules.agenda;

import com.codeheadsystems.rules.eval.Accumulators;
import com.codeheadsystems.rules.eval.Conditions;
import com.codeheadsystems.rules.eval.Negations;
import com.codeheadsystems.rules.eval.Universals;
import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.SuppressReason;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.ConflictResolutionStrategy;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.match.Tuple;
import com.codeheadsystems.rules.rule.AggregateTest;
import com.codeheadsystems.rules.rule.CompiledAccumulate;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.value.Comparisons;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * The lazily-recomputing agenda shape of spec §4.1, with candidate generation left to a subclass.
 *
 * <p>§4.1 chose TREAT for v1, and the consequence is easy to miss: at insert time there is nothing
 * to push onto the agenda, and at retract time nothing to pull. An incrementally-maintained agenda
 * is the <em>Rete</em> shape and presumes materialised join results. So the conflict set is computed
 * lazily for dirty rules, and a retracted fact's matches disappear because the next recomputation
 * does not produce them.
 *
 * <p>Everything that decides <em>which</em> activation fires lives here, shared by every matcher:
 * dirty tracking, the recomputation loop, refraction at selection, the conflict-resolution
 * comparator and the strict-mode contract checks. Subclasses supply only
 * {@link #matchesOf(CompiledRule, List)} -- how to find the matches in the first place.
 *
 * <p>That split is the point. The naive matcher and the network matcher must produce identical
 * firing sequences (§9's exit criterion for every phase after Phase 0), and the cheapest way to
 * guarantee that is for the code that could make them differ not to exist twice. What is left to
 * differ is exactly what each phase is about: how matches are found.
 *
 * <p>Two properties of §4.1's loop are reproduced here, because they are what make firing terminate
 * rather than spin:
 *
 * <ul>
 *   <li><strong>Refraction is checked at selection</strong>, not only at recomputation. Filtering
 *       only when the conflict set is rebuilt leaves a fired activation sitting in the set of any
 *       rule that does not become dirty again -- and "for every flagged order, emit an alert", an
 *       RHS that mutates nothing, is exactly such a rule.
 *   <li><strong>The selected activation is removed.</strong> Removing it <em>and</em> re-checking
 *       refraction closes both paths.
 * </ul>
 */
public abstract class RecomputingAgenda implements Agenda {

  /** The compiled rules, in compilation order. */
  private final List<CompiledRule> rules;

  /** Per-rule alias lists, shared by every tuple that rule produces. */
  private final List<List<String>> aliasesByRule;

  /** The conflict set, sliced per rule so a rebuild touches one rule's matches. */
  private final List<List<Activation>> conflictSet;

  /** Which rules a fact type makes dirty. The whole of §4.1's dirty predicate. */
  private final Map<String, BitSet> rulesByFactType;

  private final WorkingMemory workingMemory;
  private final RefractionMemory refraction;
  private final ConflictResolutionStrategy strategy;
  private final List<RuleEngineListener> listeners;
  private final boolean strict;
  private final BitSet dirty;

  /**
   * Creates an agenda over a compiled rule set.
   *
   * @param rules the compiled rules, in compilation order
   * @param workingMemory the session's working memory
   * @param refraction the session's refraction memory
   * @param strategy how ties are broken
   * @param listeners the session's listeners, in registration order
   * @param strict whether to assert the conflict-resolution contract (§7.5)
   */
  protected RecomputingAgenda(final List<CompiledRule> rules, final WorkingMemory workingMemory,
      final RefractionMemory refraction, final ConflictResolutionStrategy strategy,
      final List<RuleEngineListener> listeners, final boolean strict) {
    this.rules = List.copyOf(rules);
    this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
    this.refraction = Objects.requireNonNull(refraction, "refraction");
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    this.listeners = List.copyOf(listeners);
    this.strict = strict;
    this.dirty = new BitSet(this.rules.size());
    this.conflictSet = new ArrayList<>(this.rules.size());
    this.aliasesByRule = new ArrayList<>(this.rules.size());
    this.rulesByFactType = new LinkedHashMap<>();
    for (int index = 0; index < this.rules.size(); index++) {
      final CompiledRule rule = this.rules.get(index);
      conflictSet.add(new ArrayList<>());
      aliasesByRule.add(rule.patterns().stream().map(CompiledPattern::alias).toList());
      for (final String factType : rule.factTypes()) {
        rulesByFactType.computeIfAbsent(factType, ignored -> new BitSet()).set(index);
      }
    }
  }

  /**
   * Every complete match of one rule, against the current contents of working memory.
   *
   * <p>This is the <em>only</em> thing a matcher is free to do differently. Everything that decides
   * which of those matches fires -- dirty tracking, refraction at selection, the conflict-resolution
   * comparator, the strict-mode contract checks -- lives in this class and is shared, because §9
   * requires every matcher to produce identical firing sequences and the cheapest way to guarantee
   * that is for the code that could make them differ not to exist twice.
   *
   * <p>Implementations must produce matches in a deterministic order. Firing order does not depend
   * on it -- conflict resolution is a total order on the match itself, precisely so that
   * enumeration order cannot reach the agenda -- but a non-deterministic enumeration would still
   * make listener callbacks and explanations vary between runs.
   *
   * @param rule the rule to match
   * @param aliases the rule's aliases, to be shared by every tuple it produces
   * @return the complete matches
   */
  protected abstract List<Activation> matchesOf(CompiledRule rule, List<String> aliases);

  /**
   * Builds an activation and notifies listeners.
   *
   * @param rule the rule that matched
   * @param bound the handle ids bound at each pattern position
   * @param aliases the rule's aliases
   * @return the activation
   */
  protected final Activation buildActivation(final CompiledRule rule, final long[] bound,
      final List<String> aliases) {
    final Activation activation = new Activation(rule, new Tuple(bound, aliases), workingMemory);
    for (final RuleEngineListener listener : listeners) {
      listener.onActivationCreated(activation);
    }
    return activation;
  }

  /**
   * Builds an activation over a tuple that already exists, and notifies listeners.
   *
   * <p>For a shape that holds its tuples rather than deriving them per fire: building the
   * activation from the tuple it already has avoids constructing a second identical one.
   *
   * @param rule the rule that matched
   * @param tuple the match
   * @return the activation
   */
  protected final Activation buildActivation(final CompiledRule rule, final Tuple tuple) {
    final Activation activation = new Activation(rule, tuple, workingMemory);
    for (final RuleEngineListener listener : listeners) {
      listener.onActivationCreated(activation);
    }
    return activation;
  }

  /**
   * Whether a match has already fired.
   *
   * <p>§4.4 permits a Phase 3 shape to suppress an activation at <em>creation</em> as well as at
   * selection, and is equally clear that this is "an optimization on top of the selection-time
   * check, never a replacement for it" -- {@link #select} still checks, for every shape.
   *
   * <p><strong>Suppressing at creation is only safe because of when refraction is cleared.</strong>
   * A shape that declines to hold a refracted match must be sure that anything clearing that
   * refraction will also cause the match to be offered again, or the firing is lost with nothing
   * left to recreate it -- which is §11.5's recorded hazard, the one that made two agenda shapes
   * expensive to keep in agreement. It holds here because refraction is cleared in exactly two
   * places and both destroy the matches they clear: a retract, after which the match does not exist,
   * and §3.4.1's effective update, which clears at step 5 and re-derives at step 6. That step
   * ordering stops being incidental the moment a shape suppresses at creation.
   *
   * @param key the match's identity
   * @return whether this rule has already fired on these facts
   */
  protected final boolean isRefracted(final ActivationKey key) {
    return !refraction.shouldFire(key);
  }

  /**
   * Reports that a §6.4 condition rejected a match.
   *
   * <p>Default no-op, for the same reason as {@link #onConsumed}: a shape that rebuilds its conflict
   * set will simply not produce the match next time if the condition still rejects it, and will
   * produce it if the condition has started holding. A shape that <em>holds</em> its matches would
   * otherwise keep this one forever -- never firing it, and rebuilding and re-evaluating it on every
   * cycle -- so its conflict set drifts toward a copy of the whole join memory, which is the cost
   * §4.3 exists to remove.
   *
   * <p><strong>Dropping a rejected match is lossless, and the argument is two facts about the
   * compiler rather than an intuition about conditions.</strong> A condition is a pure function of
   * the payloads of the aliases it reads: {@code CelExpressions} binds only the tuple's aliases, and
   * the standard environment has no clock. And {@code RuleCompiler.compileCondition} records the
   * whole payload <em>root</em> of every fact type such an alias binds as a tested path. So anything
   * that could change a condition's answer is a change to a payload at a tested path -- an effective
   * update, which §3.4.1 implements as a retract and a re-assert, which destroys the match and
   * derives it again. A shape that dropped it gets it back.
   *
   * <p>Note what is <em>not</em> being claimed: the join memory still holds matches a condition
   * rejects, and must, because a condition is evaluated against a complete tuple and the memory is
   * what completes it. This is about the conflict set only.
   *
   * @param activation the match a condition rejected
   */
  protected void onRejected(final Activation activation) {
    // Nothing to do for a recomputing shape; see the contract above.
  }

  /**
   * Reports that an activation has been selected and will fire.
   *
   * <p>Called from {@link #nextToFire()} immediately after refraction records it and before the
   * right-hand side runs. Default no-op: a shape that rebuilds its conflict set does not need to
   * know, because the next recomputation simply will not produce it. A shape that <em>holds</em>
   * its matches does need to know, because nothing else will take the fired one out.
   *
   * @param activation the activation about to fire
   */
  protected void onConsumed(final Activation activation) {
    // Nothing to do for a recomputing shape; see the contract above.
  }

  /**
   * The session's working memory, for subclasses that dereference handles.
   *
   * @return the working memory
   */
  protected final WorkingMemory workingMemory() {
    return workingMemory;
  }

  @Override
  public final void markDirty(final String factType) {
    final BitSet affected = rulesByFactType.get(factType);
    if (affected != null) {
      dirty.or(affected);
    }
  }

  @Override
  public final Optional<Activation> peek() {
    return select(false);
  }

  @Override
  public final Optional<Activation> nextToFire() {
    return select(true);
  }

  @Override
  public final boolean isEmpty() {
    return select(false).isEmpty();
  }

  @Override
  public final int size() {
    materialise();
    int eligible = 0;
    for (final List<Activation> slice : conflictSet) {
      for (final Activation activation : slice) {
        if (refraction.shouldFire(activation.key())) {
          eligible++;
        }
      }
    }
    return eligible;
  }

  @Override
  public final List<Activation> rankEligible(final int limit) {
    if (limit <= 0) {
      return List.of();
    }
    materialise();
    final List<Activation> eligible = new ArrayList<>();
    for (final List<Activation> slice : conflictSet) {
      for (final Activation activation : slice) {
        if (refraction.shouldFire(activation.key())) {
          eligible.add(activation);
        }
      }
    }
    eligible.sort(strategy);
    return List.copyOf(eligible.subList(0, Math.min(limit, eligible.size())));
  }

  /**
   * Selects the most eligible activation, optionally consuming it.
   *
   * @param consume whether to remove the winner and record it as fired
   * @return the winner, or empty when nothing is eligible
   */
  private Optional<Activation> select(final boolean consume) {
    materialise();
    Activation best = null;
    int bestSlice = -1;
    for (int index = 0; index < conflictSet.size(); index++) {
      final List<Activation> slice = conflictSet.get(index);
      final Iterator<Activation> candidates = slice.iterator();
      while (candidates.hasNext()) {
        final Activation candidate = candidates.next();
        if (!refraction.shouldFire(candidate.key())) {
          candidates.remove();
          for (final RuleEngineListener listener : listeners) {
            listener.onActivationSuppressed(candidate.key(), SuppressReason.REFRACTED);
          }
          continue;
        }
        if (best == null || strategy.compare(candidate, best) < 0) {
          best = candidate;
          bestSlice = index;
        }
      }
    }
    if (best == null) {
      return Optional.empty();
    }
    if (consume) {
      conflictSet.get(bestSlice).remove(best);
      // Recorded on consumption, before the RHS runs. Recording on success only would let a rule
      // whose RHS throws under a skip-and-continue policy be re-selected and throw again, forever.
      refraction.record(best.key(), best.recency());
      onConsumed(best);
    }
    return Optional.of(best);
  }

  /** Rebuilds every dirty rule's slice, then clears the dirty set. */
  private void materialise() {
    if (dirty.isEmpty()) {
      return;
    }
    for (int index = dirty.nextSetBit(0); index >= 0; index = dirty.nextSetBit(index + 1)) {
      conflictSet.set(index, recompute(rules.get(index), aliasesByRule.get(index)));
    }
    dirty.clear();
    if (strict) {
      assertTotalOrder();
    }
  }

  /**
   * Enumerates every complete match of one rule.
   *
   * @param rule the rule
   * @param aliases the rule's aliases, shared across every tuple it produces
   * @return the matches
   */
  private List<Activation> recompute(final CompiledRule rule, final List<String> aliases) {
    return postFilter(rule, matchesOf(rule, aliases));
  }

  /**
   * Applies §6.4's expression conditions to whatever the matcher found.
   *
   * <p><strong>Here, in the shared base, rather than in either matcher -- and that placement is the
   * point.</strong> Everything that decides <em>which</em> activation fires already lives in this
   * class so that the two matchers cannot disagree, and an expression is exactly the kind of thing
   * that would drift if written twice. Filtering the matches each matcher returns means the naive
   * oracle and the network apply identical conditions by construction, and
   * {@code MatcherEquivalence} cannot fail for a reason that belongs to only one of them.
   *
   * <p>The network gives up nothing by it. §6.4 already makes an expression an unindexed
   * post-filter: there is no index for it to have used and no probe for it to have narrowed, which
   * is the visible cost §6.3 wants an escape hatch to carry.
   *
   * <p>Evaluated against a <em>complete</em> tuple, even for a condition that reads one alias.
   * Evaluating a single-alias condition earlier would be a real optimisation and would produce the
   * same answers, since a complete tuple binds that alias to the same fact -- so it is an
   * optimisation to make when there is a measurement asking for it, not before.
   *
   * @param rule the rule
   * @param matches every complete match the matcher found
   * @return the matches whose conditions all hold, in the order they were found
   */
  private List<Activation> postFilter(final CompiledRule rule, final List<Activation> matches) {
    if (matches.isEmpty()) {
      return matches;
    }
    final List<Activation> present = rule.hasNegations() ? absences(rule, matches) : matches;
    /*
     * Negations, then universals, then §6.4's conditions. A tuple must pass all three, so the order
     * cannot change the answer -- but it is fixed rather than incidental, because §7.2's explainer
     * attributes each removed tuple to whichever gate took it, and an attribution that depended on
     * evaluation order would be a different explanation on a different day.
     *
     * No cost ordering is claimed. A negation short-circuits on its first witness where a universal
     * that holds walks its whole scope, so the cheaper of the two is workload-dependent; the
     * condition is last because it is the one gate with nothing to scan and no early exit.
     */
    final List<Activation> required = rule.hasUniversals()
        ? requirements(rule, present)
        : present;
    final List<Activation> aggregated = rule.hasAccumulates() && !required.isEmpty()
        ? havings(rule, required)
        : required;
    if (aggregated.isEmpty() || !rule.hasExpressionTests()) {
      return aggregated;
    }
    return conditions(rule, aggregated);
  }

  /**
   * Drops the matches whose rule asserts an absence that is not absent (§1's {@code NOT_EXISTS}).
   *
   * <p><strong>Evaluated here, in the shared base, and that is the design rather than an
   * expedient.</strong> A negated pattern binds nothing and joins nothing: it is a question asked of
   * a <em>complete</em> tuple, exactly as a §6.4 condition is. Answering it here means the naive
   * oracle, the network matcher and the streaming matcher cannot disagree about it, which is the
   * property §9's exit criterion rests on and the reason CLAUDE.md keeps divergence-capable code in
   * one place. A {@code NotNode} in the network would be faster and would have to be written twice.
   *
   * <p><strong>A rejected match is not reported to {@link #onRejected}</strong>, and the difference
   * from a condition is the whole reason that hook takes a reason at all. A condition is a pure
   * function of the payloads the tuple binds, so anything that could flip it re-derives the tuple. A
   * negation is a question about facts the tuple does <em>not</em> bind: a {@code Payment} arriving
   * makes it false and a {@code Payment} leaving makes it true again, and neither touches the
   * {@code Order} the tuple is built from. A shape that dropped the match on a negation would have
   * nothing to re-offer it with when the absence returned -- §11.5's dropped firing, by a route the
   * condition case does not have. So negation-rejected matches stay held, and are re-asked on every
   * cycle the rule is dirty for. {@code CompiledRule.factTypes} includes negated types precisely so
   * that a change to one makes the rule dirty.
   *
   * @param rule the rule
   * @param matches its complete tuples
   * @return the tuples whose asserted absences all hold
   */
  private List<Activation> absences(final CompiledRule rule, final List<Activation> matches) {
    final List<Activation> surviving = new ArrayList<>(matches.size());
    for (final Activation activation : matches) {
      if (rule.negations().stream().noneMatch(negation -> exists(negation, activation))) {
        surviving.add(activation);
      }
    }
    return surviving;
  }

  /**
   * Drops the matches whose rule folds a scope into an answer a {@code having} rejects (§2.5's
   * {@code ACCUMULATE}).
   *
   * <p>Here for the reasons {@link #absences} and {@link #requirements} are here, and with the same
   * boundaries: the accumulated type must not be one the session evicts, because evicting facts
   * silently changes the answer rather than costing a firing, and a rule that fired on a total is
   * not undone when the total moves unless what it concluded was inserted logically (§4.4).
   *
   * <p>An accumulate with no {@code having} is not evaluated here at all. It binds and nothing
   * more, so there is nothing to filter on and folding the scope would be work spent on an answer
   * only the right-hand side will read -- and it will read it then, from working memory.
   *
   * @param rule the rule
   * @param matches its complete tuples
   * @return the tuples every {@code having} holds for
   */
  private List<Activation> havings(final CompiledRule rule, final List<Activation> matches) {
    final List<Activation> surviving = new ArrayList<>(matches.size());
    for (final Activation activation : matches) {
      if (rule.accumulates().stream().allMatch(accumulate -> holds(accumulate, activation))) {
        surviving.add(activation);
      }
    }
    return surviving;
  }

  /**
   * Whether one accumulate's answer passes its own {@code having}.
   *
   * @param accumulate the compiled accumulate
   * @param activation the complete positive binding
   * @return true when there is no test, or the answer passes it
   */
  private boolean holds(final CompiledAccumulate accumulate, final Activation activation) {
    final Optional<AggregateTest> having = accumulate.having();
    if (having.isEmpty()) {
      return true;
    }
    final JsonNode answer =
        Accumulators.evaluate(accumulate, activation.tuple().boundFacts(), workingMemory);
    /*
     * A missing answer -- an empty scope under MIN, MAX or AVERAGE -- is put through the same
     * comparison every other absent value goes through rather than short-circuited. §2.6.1 already
     * decides what each operator does with an absent value, and deciding again here would give the
     * engine two answers to "is absent greater than 10". It says no, and `ne` says yes, which is
     * exactly what a `hasField` pairing is for.
     */
    return Comparisons.test(having.get().op(), answer, having.get().literal());
  }

  /**
   * Drops the matches whose rule asserts a requirement that some in-scope fact fails (§2.5's
   * {@code FOR_ALL}).
   *
   * <p>Here for the reasons {@link #absences} is here, and it inherits the same two boundaries: a
   * rule that fired because everything in scope satisfied a requirement is not undone when a
   * counterexample arrives unless what it concluded was inserted logically (§4.4's amendment); and
   * the type must not be one the session evicts,
   * because evicting facts can only remove counterexamples and so can only make the assertion more
   * true. Both are §2.5's amendment, and the second is sharper than negation's -- a universal is
   * <em>vacuously</em> true over an emptied scope, so a cap that evicts a type entirely turns the
   * quantifier into a tautology rather than merely weakening it.
   *
   * <p><strong>Rejected matches stay held</strong>, exactly as negation-rejected ones do and for the
   * same reason: the question is about facts the tuple does not bind, so a shape that dropped the
   * match would have nothing to re-offer it with when the requirement started holding again.
   *
   * @param rule the rule
   * @param matches its complete tuples
   * @return the tuples whose asserted requirements all hold
   */
  private List<Activation> requirements(final CompiledRule rule, final List<Activation> matches) {
    final List<Activation> surviving = new ArrayList<>(matches.size());
    for (final Activation activation : matches) {
      if (rule.universals().stream().noneMatch(universal -> fails(universal, activation))) {
        surviving.add(activation);
      }
    }
    return surviving;
  }

  /**
   * Whether any in-scope fact fails a universal pattern against one binding.
   *
   * <p>Delegated to {@link com.codeheadsystems.rules.eval.Universals} for the reason
   * {@link #exists} delegates: §7.2's explainer has to answer the same question, and two copies
   * could disagree.
   *
   * @param universal the universal pattern
   * @param activation the complete positive binding
   * @return true when the requirement does not hold
   */
  private boolean fails(final CompiledPattern universal, final Activation activation) {
    return Universals.counterexample(universal, activation.tuple().boundFacts(), workingMemory)
        .isPresent();
  }

  /**
   * Whether any fact satisfies a negated pattern against one binding.
   *
   * <p>Delegated to {@link com.codeheadsystems.rules.eval.Negations} rather than written here,
   * because §7.2's {@code MatchExplainer} has to answer the same question to explain a rule that
   * did not fire. Two copies of this predicate could disagree, and the disagreement would surface
   * as a diagnostic contradicting the engine it is diagnosing.
   *
   * @param negation the negated pattern
   * @param activation the complete positive binding
   * @return true when the absence does not hold
   */
  private boolean exists(final CompiledPattern negation, final Activation activation) {
    return Negations.witness(negation, activation.tuple().boundFacts(), workingMemory).isPresent();
  }

  /**
   * Drops the matches a §6.4 condition rejects.
   *
   * @param rule the rule
   * @param matches its complete tuples
   * @return the tuples every condition holds for
   */
  private List<Activation> conditions(final CompiledRule rule, final List<Activation> matches) {
    final List<Activation> surviving = new ArrayList<>(matches.size());
    for (final Activation activation : matches) {
      if (!holdsFor(rule, activation)) {
        onRejected(activation);
        continue;
      }
      surviving.add(activation);
    }
    return surviving;
  }

  /**
   * Whether every condition on a rule holds for one match.
   *
   * <p>Delegated to {@link com.codeheadsystems.rules.eval.Conditions} rather than written here,
   * because §4.4's truth maintenance re-asks the same question of a justifying tuple. Two copies
   * could disagree, and a disagreement there means either a conclusion left standing after its
   * justification died or a fact retracted while the engine still believes it.
   *
   * @param rule the rule
   * @param activation the complete match
   * @return true when no condition rejected it
   */
  private boolean holdsFor(final CompiledRule rule, final Activation activation) {
    return Conditions.holdFor(rule, bindingsFor(rule, activation.tuple().boundFacts(),
        activation.tuple().aliases(), workingMemory));
  }

  /**
   * What a §6.4 expression sees for one tuple: its aliases, and any accumulate the rule folds.
   *
   * <p>The accumulate half is what lets a condition read a total, which is the only way to test one
   * against another value rather than against a literal -- {@code having} takes a literal, and
   * nothing may join to an accumulate alias. Folded per read from working memory, as
   * {@code RhsExecutor.payloadOf} does, so nothing stale is held.
   *
   * @param rule the rule being matched
   * @param bound the handle ids the tuple binds, in pattern order
   * @param aliases the tuple's alias names, in the same order
   * @param memory the working memory to read and fold from
   * @return the bindings
   */
  static ExpressionBindings bindingsFor(final CompiledRule rule, final long[] bound,
      final List<String> aliases, final WorkingMemory memory) {
    return alias -> {
      final int position = aliases.indexOf(alias);
      if (position >= 0) {
        return memory.get(new FactHandle(bound[position]))
            .map(Fact::payload)
            .orElseGet(MissingNode::getInstance);
      }
      return rule.accumulateNamed(alias)
          .map(accumulate -> Accumulators.evaluate(accumulate, bound, memory))
          .orElseGet(MissingNode::getInstance);
    };
  }

  /**
   * Strict-mode assertion of the conflict-resolution contract (§4.2, §7.5).
   *
   * <p>Both halves matter. If the comparator could return zero for distinct activations, ordering
   * would fall to internal accident and the determinism contract would be gone. If it could return
   * non-zero for equal ones, the conflict set and any key-indexed structure would disagree about
   * how many entries exist and the same match could be selected twice.
   */
  private void assertTotalOrder() {
    final List<Activation> all = new ArrayList<>();
    conflictSet.forEach(all::addAll);
    for (final Activation activation : all) {
      // Reflexivity. The conflict set holds distinct activations, so comparing pairs drawn from it
      // can only ever exercise the "zero for distinct" half of the contract.
      final int self = strategy.compare(activation, activation);
      if (self != 0) {
        throw new IllegalStateException(
            "strict mode: conflict resolution is not consistent with equality -- " + activation
                + " compared against itself gives " + self + ", not 0");
      }
    }
    for (int left = 0; left < all.size(); left++) {
      for (int right = left + 1; right < all.size(); right++) {
        final Activation first = all.get(left);
        final Activation second = all.get(right);
        final boolean equal = first.equals(second);
        final int comparison = strategy.compare(first, second);
        if (equal != (comparison == 0)) {
          throw new IllegalStateException(
              "strict mode: conflict resolution is not consistent with equality for "
                  + first + " and " + second + " (equals=" + equal + ", compare=" + comparison
                  + ")");
        }
      }
    }
  }
}
