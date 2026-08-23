package com.codeheadsystems.rules.truth;

import com.codeheadsystems.rules.agenda.Agenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.match.TupleMatch;
import com.codeheadsystems.rules.rule.CompiledRule;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Re-asks whether each conclusion's reason still holds, and retracts what nothing supports (spec
 * §4.4's amendment).
 *
 * <p><strong>Runs only at quiescence, and that is a correctness rule rather than a scheduling
 * preference.</strong> It is consulted at the top of a fire cycle, exactly where §4.4 already
 * consults the eviction policy, and never between §4.6's staging and commit -- retracting a fact a
 * firing activation binds, halfway through applying that firing, is the same breach eviction is
 * barred from making. A conclusion therefore outlives its reason until the next cycle boundary,
 * which is the honest description of when this engine withdraws things.
 *
 * <p><strong>A retraction here is an ordinary retract and must stay one.</strong> Same rule
 * {@code SessionEvictor} follows: reaching into the node memories by hand would make this a further
 * place facts are removed from, and the alpha memories, indexes, beta memory, refraction memory and
 * this graph are all kept consistent by the one path.
 *
 * <p><strong>Invalidating a justification clears refraction for it</strong>, and the coupling is
 * load-bearing rather than tidy. A justification dies when its tuple stops matching; if the same
 * tuple starts matching again -- the {@code Payment} that blocked a negation is itself retracted --
 * the handles are unchanged, so the {@link ActivationKey} is unchanged, and a rule still refracted
 * on it would never re-fire and never re-derive. The conclusion would be withdrawn permanently by
 * something temporary. Forgetting the key is what makes a withdrawal reversible.
 *
 * <p><strong>One forward pass cascades completely, and the argument for that is the interesting
 * part of this class.</strong> Justifications are visited in the order they were recorded, which is
 * the order their rules fired. A justification can only bind facts that existed when it fired, so
 * every fact it depends on positively was concluded <em>earlier</em> in that order -- dependencies
 * point backwards. And withdrawal only ever <em>removes</em> facts, which can never invalidate an
 * earlier justification it does not bind: removing a fact can only make a {@code NOT_EXISTS} more
 * satisfied and a {@code FOR_ALL} more satisfied, and a §6.4 condition reads bound payloads alone.
 * So by the time the pass reaches a justification, everything that could have invalidated it has
 * already happened. A {@code while (changed)} loop was written first and removed: no test could kill
 * it, because nothing can reach it.
 *
 * <p>What would break that argument, if anyone changes it: a conclusion shared by two
 * justifications (see {@link Justifications} for why there is no such thing here); a withdrawal that
 * inserted rather than only retracting; or a listener that inserts from {@code onRetract}, since
 * {@code workingMemory.retract} dispatches it synchronously and an insert from inside the pass
 * breaks "withdrawal only removes facts" exactly as a shared conclusion would. Any of the three
 * would let a later withdrawal invalidate an earlier justification, and the pass would need to
 * repeat. Either would let a later withdrawal invalidate an
 * earlier justification, and the pass would need to repeat.
 */
public final class TruthMaintenance {

  private final Justifications justifications;
  private final WorkingMemory workingMemory;
  private final RefractionMemory refraction;
  private final Agenda agenda;
  private final Map<String, CompiledRule> rulesById;

  /**
   * The fact types changed since the last pass, so a pass can skip justifications nothing touched.
   *
   * <p>Conservative on purpose: a type is recorded on any insert, retract or effective update, and a
   * justification is rechecked when its rule reads any recorded type. Narrowing this to the paths a
   * rule tests would be §11.2's {@code dependsOn()} trap in miniature -- under-declaring here does
   * not lose a firing, it leaves a conclusion standing after its reason has gone, which is worse.
   */
  private final Set<String> touchedTypes = new LinkedHashSet<>();

  /**
   * Creates the pass over one session's justifications.
   *
   * @param justifications the session's graph
   * @param workingMemory the session's facts
   * @param refraction the session's refraction memory, cleared for each dead justification
   * @param agenda the session's agenda, told when a match is un-refracted
   * @param rulesById the compiled rules, by id, to resolve a justification's rule
   */
  public TruthMaintenance(final Justifications justifications, final WorkingMemory workingMemory,
      final RefractionMemory refraction, final Agenda agenda,
      final Map<String, CompiledRule> rulesById) {
    this.justifications = Objects.requireNonNull(justifications, "justifications");
    this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
    this.refraction = Objects.requireNonNull(refraction, "refraction");
    this.agenda = Objects.requireNonNull(agenda, "agenda");
    this.rulesById = Map.copyOf(rulesById);
  }

  /**
   * Records that facts of a type changed, so the next pass reconsiders the rules reading it.
   *
   * @param factType the type inserted, retracted or effectively updated
   */
  public void factTypeTouched(final String factType) {
    /*
     * Unconditional, and an earlier version guarded this on the graph being non-empty. That was
     * wrong in a way only a session's history revealed: justifications are recorded AFTER the
     * right-hand side returns, so during the firing that draws the first conclusion the graph is
     * still empty and every change that firing made was dropped. A rule that concluded and then
     * retracted its own binding left the conclusion standing until some unrelated insert happened
     * to run the pass -- the same rule and the same facts giving different answers depending on
     * what the session had done before. A LinkedHashSet.add per insert is noise beside the alpha
     * network walk it sits next to.
     */
    touchedTypes.add(factType);
  }

  /**
   * Forgets a concluded fact that left by another door.
   *
   * @param handleId the retracted fact's handle
   */
  public void factRetracted(final long handleId) {
    justifications.forget(handleId);
  }

  /**
   * Withdraws every conclusion whose reason has stopped holding.
   *
   * @return how many facts were retracted. Nothing reads it -- the fire loop does not need it and
   *     the tests assert on {@code SessionStats.concludedFactCount} instead -- and it is returned
   *     rather than dropped because a withdrawal that retracts nothing and one that retracts a
   *     hundred are worth telling apart from a debugger or a future listener
   */
  public int revalidate() {
    int retractedSuperseded = 0;
    for (final long handleId : justifications.drainSuperseded()) {
      // Conclusions a re-firing replaced. Retracted here rather than at the firing that replaced
      // them, because a firing is mid-commit and §4.6 bars a retraction there.
      if (workingMemory.get(new FactHandle(handleId)).isPresent()) {
        workingMemory.retract(new FactHandle(handleId));
        retractedSuperseded++;
      }
    }
    if (justifications.isEmpty() || touchedTypes.isEmpty()) {
      // Nothing concluded, or nothing has changed since the last pass. The common case by far, and
      // the reason a session that never inserts logically pays a set-emptiness check per cycle.
      // Cleared here too, so the field means the same thing on both exits from this method.
      touchedTypes.clear();
      return retractedSuperseded;
    }
    int retracted = retractedSuperseded;
    for (final ActivationKey key : justifications.keys()) {
      final CompiledRule rule = rulesById.get(key.ruleId());
      if (rule == null) {
        /*
         * Unreachable: rulesById is built from the session's own CompiledRuleSet, which never
         * changes -- §5.6's swap affects new sessions only -- and every key in the graph came from
         * an activation of that set. Defensive rather than a case that happens, and it withdraws
         * rather than throwing because a conclusion whose rule cannot be found is one nothing can
         * ever re-derive.
         */
        retracted += withdraw(key);
        continue;
      }
      /*
       * Read live rather than snapshotted, so a justification later in the pass sees the types this
       * pass has already retracted. That is what makes a chain -- an Order concluding OrderUnpaid
       * concluding AccountFlagged -- come apart in one traversal rather than needing another.
       */
      if (touches(rule) && !TupleMatch.holds(rule, key.handles(), workingMemory)) {
        retracted += withdraw(key);
      }
    }
    touchedTypes.clear();
    return retracted;
  }

  /**
   * Whether a rule reads any type that has changed since the last pass.
   *
   * @param rule the rule a justification names
   * @return whether its justifications are worth rechecking
   */
  private boolean touches(final CompiledRule rule) {
    for (final String factType : rule.factTypes()) {
      if (touchedTypes.contains(factType)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Drops one justification, retracting whatever it was the last support for.
   *
   * @param key the justification that no longer holds
   * @return how many facts were retracted
   */
  private int withdraw(final ActivationKey key) {
    final List<Long> unsupported = justifications.invalidate(key);
    if (unsupported.isEmpty()) {
      /*
       * Defensive rather than a case that happens: concludedBy never holds an empty set -- forget
       * removes the key when its last conclusion goes and supersede removes it outright -- so a key
       * returned by keys() always has conclusions. Kept because the alternative is un-refracting a
       * rule on the strength of a withdrawal that did not occur.
       */
      return 0;
    }
    /*
     * Both halves are needed and only together. Forgetting the key is what lets the rule conclude
     * again when its reason returns -- the handles are unchanged, so the key would be too. Telling
     * the agenda is what makes that true under §4.3's shape as well: it holds a match from
     * derivation until it fires and never re-derives a tuple whose facts did not move, so an
     * un-refracted match it was never told about is one it will never offer. Clearing refraction
     * alone withdrew conclusions under all three matchers and re-derived them under two.
     */
    refraction.forget(key);
    int retracted = 0;
    for (final long handleId : unsupported) {
      if (workingMemory.get(new FactHandle(handleId)).isPresent()) {
        workingMemory.retract(new FactHandle(handleId));
        retracted++;
      }
    }
    /*
     * Last, and only the failure ordering decides that. reactivate resolves the rule id and throws
     * on an id this agenda does not know -- unreachable, since the caller resolved the same id a
     * moment ago, but the branch exists. Called earlier it would throw with the justification
     * already gone from the graph and refraction already cleared, leaving the conclusions in
     * working memory supported by nothing and unreachable by any future pass, because the key no
     * longer exists to revisit. Here the worst case is a completed withdrawal that failed to
     * re-offer the match. The ordering is not otherwise load-bearing: a justification's conclusions
     * are never among its own bound facts, so the retracts above cannot change what
     * ReteAgenda.reactivate finds in the beta memory.
     */
    agenda.reactivate(key);
    return retracted;
  }
}
