package com.codeheadsystems.rules.agenda;

import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.SuppressReason;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.Tuple;
import tools.jackson.databind.node.MissingNode;
import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.expr.ExpressionEvaluationException;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.ExpressionTest;
import com.codeheadsystems.rules.rule.CompiledRule;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
    if (matches.isEmpty() || !rule.hasExpressionTests()) {
      return matches;
    }
    final List<Activation> surviving = new ArrayList<>(matches.size());
    for (final Activation activation : matches) {
      if (holdsFor(rule, activation)) {
        surviving.add(activation);
      }
    }
    return surviving;
  }

  /**
   * Whether every condition on a rule holds for one match.
   *
   * @param rule the rule
   * @param activation the complete match
   * @return true when no condition rejected it
   */
  private boolean holdsFor(final CompiledRule rule, final Activation activation) {
    final ExpressionBindings bindings =
        alias -> activation.tuple().aliases().contains(alias)
            ? activation.tuple().payloadOf(alias, workingMemory)
            : MissingNode.getInstance();
    for (final CompiledPattern pattern : rule.patterns()) {
      for (final ExpressionTest test : pattern.expressionTests()) {
        if (!evaluate(rule, pattern, test, bindings)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Evaluates one condition, naming it if it fails.
   *
   * <p><strong>A condition that throws aborts the fire cycle, and there is no policy that catches
   * it.</strong> That is worth stating plainly rather than discovering: §4.6's {@code RhsErrorHandler}
   * governs the right-hand side, and a condition is evaluated here, in the agenda, while the
   * conflict set is being built. There is nothing to skip and continue past -- matching is not a
   * per-rule operation the way firing is, and a matcher that silently treated an evaluation failure
   * as "no match" would turn a broken expression into rules that quietly stop firing, which is the
   * failure mode this engine works hardest to avoid.
   *
   * <p>So the exception propagates, and the one thing this method owes the operator is enough
   * context to find the cause immediately: which rule, which alias, and the expression text. The
   * bare message from the expression compiler names none of them.
   *
   * @param rule the rule being matched
   * @param pattern the pattern the condition was written on
   * @param test the compiled condition
   * @param bindings the tuple's facts
   * @return whether the condition holds
   */
  private static boolean evaluate(final CompiledRule rule, final CompiledPattern pattern,
      final ExpressionTest test, final ExpressionBindings bindings) {
    try {
      return test.program().test(bindings);
    } catch (final RuntimeException failed) {
      throw new ExpressionEvaluationException(
          "rule '" + rule.id() + "', condition on alias '" + pattern.alias() + "' ("
              + test.source().expression() + "): " + failed.getMessage()
              + ". A condition that fails to evaluate stops the fire cycle -- there is no"
              + " per-match error policy on the left-hand side (§6.4)", failed);
    }
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
