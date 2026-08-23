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
 * <p><strong>The pass repeats until a round changes nothing, and the history of why is worth
 * keeping.</strong> When only {@code NOT_EXISTS} and {@code FOR_ALL} existed, one forward pass was
 * provably enough: justifications are visited in the order their rules fired, a justification binds
 * only facts that existed when it fired so its positive dependencies are earlier in that order, and
 * withdrawal only ever <em>removes</em> facts -- which can only make an absence more true and a
 * universal more true, and cannot touch a §6.4 condition, which reads bound payloads alone. A
 * {@code while (changed)} loop was written, found unkillable by any mutation, and deleted.
 *
 * <p>§2.5's {@code ACCUMULATE} broke that argument in both halves, and the loop came back.
 * Removing a fact <em>decreases</em> a {@code count} or a {@code sum} and moves a {@code min},
 * {@code max} or {@code average}, so a withdrawal can make an earlier justification's {@code having}
 * stop holding rather than start. And an accumulate reads its scope from <em>current</em> working
 * memory rather than from the handles the tuple binds, so "dependencies point backwards" does not
 * cover it either: a justification can depend on a fact that did not exist when it fired.
 *
 * <p>Termination is unchanged and does not need a bound. Nothing adds a justification during the
 * pass -- only a firing does that, and firings do not happen here -- so every round that changes
 * anything removes at least one justification from a finite set.
 *
 * <p>The other two things that would break the ordering argument, if the loop were ever removed
 * again: a conclusion shared by two justifications (see {@link Justifications} for why there is no
 * such thing here), and a listener that inserts from {@code onRetract}, since
 * {@code workingMemory.retract} dispatches it synchronously. Either would let a later withdrawal invalidate an
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
    boolean changed = true;
    while (changed) {
      changed = false;
      for (final ActivationKey key : justifications.keys()) {
        final CompiledRule rule = rulesById.get(key.ruleId());
        if (rule == null) {
          /*
           * Unreachable: rulesById is built from the session's own CompiledRuleSet, which never
           * changes -- §5.6's swap affects new sessions only -- and every key in the graph came
           * from an activation of that set. Defensive rather than a case that happens, and it
           * withdraws rather than throwing because a conclusion whose rule cannot be found is one
           * nothing can ever re-derive.
           */
          retracted += withdraw(key);
          changed = true;
          continue;
        }
        /*
         * Read live rather than snapshotted, so a justification later in this round sees the types
         * the round has already retracted. That is what lets a chain -- an Order concluding
         * OrderUnpaid concluding AccountFlagged -- come apart in one traversal. The round that
         * follows is for the cases a single traversal cannot reach: an accumulate whose total a
         * later withdrawal moved, which the pass has already walked past.
         */
        if (touches(rule) && !TupleMatch.holds(rule, key.handles(), workingMemory)) {
          retracted += withdraw(key);
          changed = true;
        }
      }
      /*
       * NOT cleared between rounds. A withdrawal in this round records its own types, and clearing
       * here would throw away exactly the signal the next round needs -- which is how the version
       * of this loop that cleared per round managed to leave a conclusion standing forever, its
       * types recorded and then discarded before anything could act on them.
       */
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
