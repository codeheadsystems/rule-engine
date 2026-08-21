package com.codeheadsystems.rules.naive;

import com.codeheadsystems.rules.agenda.Agenda;
import com.codeheadsystems.rules.agenda.ConflictResolutionStrategy;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.SuppressReason;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.Tuple;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The brute-force agenda: recompute every dirty rule's matches from working memory, then select.
 *
 * <p>This is the TREAT <em>shape</em> of spec §4.1 with the indexing left out. The conflict set is
 * computed lazily on demand for dirty rules; a retract needs no agenda surgery, because the next
 * recomputation simply will not produce that match. Where a real TREAT implementation probes
 * indexes, this enumerates.
 *
 * <p>Two properties of §4.1's loop are reproduced exactly, because they are what make firing
 * terminate rather than spin:
 *
 * <ul>
 *   <li><strong>Refraction is checked at selection, not only at recomputation.</strong> Filtering
 *       refracted matches only when the conflict set is rebuilt leaves a fired activation sitting
 *       in the set of any rule that does not become dirty again -- and "for every flagged order,
 *       emit an alert", an RHS that mutates nothing, is exactly such a rule. It would be
 *       re-selected every cycle until the limit.
 *   <li><strong>The selected activation is removed.</strong> Removing it <em>and</em> re-checking
 *       refraction closes both paths.
 * </ul>
 *
 * <p>Recomputation is memoised within a fire cycle: the dirty set is cleared once the slices are
 * rebuilt, so the firing loop's {@code isEmpty} then {@code peek} then {@code nextToFire} sequence
 * rebuilds once rather than three times.
 */
public final class NaiveAgenda implements Agenda {

  private final List<CompiledRule> rules;
  private final List<List<String>> aliasesByRule;
  private final List<List<Activation>> conflictSet;
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
  public NaiveAgenda(final List<CompiledRule> rules, final WorkingMemory workingMemory,
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

  @Override
  public void markDirty(final String factType) {
    final BitSet affected = rulesByFactType.get(factType);
    if (affected != null) {
      dirty.or(affected);
    }
  }

  @Override
  public Optional<Activation> peek() {
    return select(false);
  }

  @Override
  public Optional<Activation> nextToFire() {
    return select(true);
  }

  @Override
  public boolean isEmpty() {
    return select(false).isEmpty();
  }

  @Override
  public int size() {
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
  public List<Activation> rankEligible(final int limit) {
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
   * <p>Refracted entries encountered during the scan are dropped from the conflict set, which is
   * §4.1's "drop it and continue" -- leaving them in place would make every later scan re-walk them
   * and re-notify listeners about them.
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
   * @return the matches, in a deterministic order derived from ascending handle id at every pattern
   */
  private List<Activation> recompute(final CompiledRule rule, final List<String> aliases) {
    final List<Activation> matches = new ArrayList<>();
    extend(rule, aliases, 0, new long[rule.patterns().size()], matches);
    return matches;
  }

  /**
   * Depth-first extension of a partial binding across the remaining patterns.
   *
   * @param rule the rule
   * @param aliases the rule's aliases
   * @param position the pattern to bind next
   * @param bound the handles bound so far, mutated in place and copied into each completed tuple
   * @param matches the list to append completed matches to
   */
  private void extend(final CompiledRule rule, final List<String> aliases, final int position,
      final long[] bound, final List<Activation> matches) {
    if (position == rule.patterns().size()) {
      final Activation activation = new Activation(rule, new Tuple(bound, aliases), workingMemory);
      matches.add(activation);
      for (final RuleEngineListener listener : listeners) {
        listener.onActivationCreated(activation);
      }
      return;
    }
    final CompiledPattern pattern = rule.patterns().get(position);
    // A snapshot in ascending handle id (§2.4), which is what makes the enumeration order -- and
    // therefore the firing order -- reproducible across runs and hosts.
    final List<Fact> candidates = workingMemory.factsOfType(pattern.factType()).toList();
    for (final Fact candidate : candidates) {
      if (pattern.conflictsWith(bound, candidate.handle().id())
          || !satisfiesAlpha(pattern, candidate)
          || !satisfiesJoins(pattern, candidate, bound)) {
        continue;
      }
      bound[position] = candidate.handle().id();
      extend(rule, aliases, position + 1, bound, matches);
    }
  }

  /**
   * Whether a candidate passes every single-fact test of a pattern.
   *
   * @param pattern the pattern
   * @param candidate the candidate fact
   * @return whether it passes
   */
  private static boolean satisfiesAlpha(final CompiledPattern pattern, final Fact candidate) {
    for (final AlphaTest test : pattern.alphaTests()) {
      if (!test.test(candidate.payload())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a candidate passes every cross-fact test of a pattern against the current binding.
   *
   * @param pattern the pattern
   * @param candidate the candidate fact
   * @param bound the handles bound so far
   * @return whether it passes
   */
  private boolean satisfiesJoins(final CompiledPattern pattern, final Fact candidate,
      final long[] bound) {
    for (final JoinTest test : pattern.joinTests()) {
      final Optional<Fact> other = workingMemory.get(new FactHandle(bound[test.otherIndex()]));
      if (other.isEmpty() || !test.test(candidate.payload(), other.get().payload())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Strict-mode assertion of the conflict-resolution contract (§4.2, §7.5).
   *
   * <p>Both halves matter. If the comparator could return zero for distinct activations, ordering
   * would fall to internal accident and the determinism contract would be gone. If it could return
   * non-zero for equal ones, the conflict set and any key-indexed structure would disagree about
   * how many entries exist and the same match could be selected twice.
   *
   * <p>Quadratic on purpose: strict mode is explicitly too expensive for production, and a sampled
   * or adjacent-pairs-only check would miss exactly the violations that are hardest to find by hand.
   */
  private void assertTotalOrder() {
    final List<Activation> all = new ArrayList<>();
    conflictSet.forEach(all::addAll);
    for (final Activation activation : all) {
      // Reflexivity. The conflict set holds distinct activations, so comparing pairs drawn from it
      // can only ever exercise the "zero for distinct" half of the contract. Comparing an
      // activation with itself is what exercises the other half, and a strategy that always
      // returns a fixed non-zero value is caught here and nowhere else.
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
