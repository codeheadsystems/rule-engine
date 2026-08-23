package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.match.Negations;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.ExpressionTest;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Answers "why did rule R <em>not</em> fire?" (spec §7.2).
 *
 * <p>"Why did it fire" is answerable from the trace: a firing record names the rule, the bindings,
 * the conflict-resolution inputs and every effect. The opposite question has no record to look up,
 * because a non-firing is the absence of one — so it needs a diagnostic that goes and looks.
 *
 * <p><strong>This deliberately does not use the matching network.</strong> §7.2 is explicit about
 * why: the network is optimised to <em>not</em> compute what is wanted here. An index skips
 * non-candidates without recording why they were skipped, a pattern memory holds the survivors and
 * has forgotten the casualties, and a join probe never sees the rows it did not fetch. Re-evaluating
 * the constraints one at a time against working memory is slower by every measure and is the only
 * way to know which constraint did the eliminating. Accept the slow path: correctness of the
 * explanation matters, speed does not.
 *
 * <p>It also runs entirely off public session API, so it observes the same engine a caller does and
 * cannot accidentally report on internal state the engine would never act on.
 *
 * <h2>The two overloads answer different questions</h2>
 *
 * <p>{@link #explain(String)} asks "why did nothing match", and reports per pattern over all
 * candidates. The right tool when a rule is silent.
 *
 * <p>{@link #explain(String, Map)} asks the sharper and more common question: "I am looking at
 * <em>these specific facts</em> and I expected them to match — what stopped them?" With the bindings
 * pinned, every pattern has exactly one candidate, so the answer is a single chain of constraint
 * evaluations ending at the first false. This is the overload rule authors actually reach for.
 *
 * <h2>Negation</h2>
 *
 * <p>A {@code NOT_EXISTS} pattern is not in {@code CompiledRule.patterns()} -- deliberately, so that
 * the join planner, the join walk and the streaming matcher's pattern sites need not know negation
 * exists -- so the walk above cannot see one, and for a while this class could not either. A rule
 * suppressed because the fact whose absence it asserts is <em>present</em> reported "N match(es); all
 * eligible, none has fired yet": the opposite of the truth, with the offending fact named nowhere.
 * That was §7.2's claim failing on precisely the rules §1 calls "the first ten rules most people
 * write".
 *
 * <p>So negations are evaluated here too, against each complete tuple and <strong>before</strong> the
 * §6.4 conditions, which is the order {@code RecomputingAgenda.postFilter} applies them in. The
 * predicate itself is not re-implemented: it is {@link Negations#witness}, the same code the agenda
 * decides with. That sharing is the point rather than a convenience -- a diagnostic that disagrees
 * with the engine it is diagnosing sends an author to fix a rule that is already correct -- and it is
 * also what supplies the part an author cannot derive, the {@link NegationResult#exampleWitness()}
 * that says <em>which</em> fact is standing in the way.
 *
 * <p>Two limits remain, and both are properties of negation rather than of this class. Because a
 * negated pattern binds nothing, there is no candidate population to report survivors and casualties
 * over, so {@link NegationResult} answers different questions from {@link PatternResult} and is kept
 * in a separate list. And an evicted fact is indistinguishable from an absent one (§4.4), so over an
 * evicted type this explains exactly what the engine does -- which is the wrong answer, identically
 * arrived at.
 */
public final class MatchExplainer {

  /** How many real matches to report. Enough to explain; more would be a data dump, not an answer. */
  private static final int MATCH_LIMIT = 1_000;

  /**
   * How many candidate bindings to examine before giving up.
   *
   * <p>Separate from {@link #MATCH_LIMIT}, and the separation is the point. A budget on <em>matches
   * found</em> bounds the output but not the work: when a rule has <em>no</em> matches the guard
   * never trips and the walk runs the whole cross product to completion — and "my rule has no
   * matches" is exactly the question this class exists to answer. Measured at roughly cubic on a
   * three-pattern rule with one disconnected pattern: 100 facts per type took 98ms, 200 took 489ms,
   * 400 took 4s. A budget on work examined is what actually bounds it.
   */
  private static final int WORK_LIMIT = 250_000;

  /** How many fired matches to name before summarising. §7.2 asks for a sentence, not a dump. */
  private static final int EXAMPLES = 3;

  private final CompiledRuleSet ruleSet;
  private final RuleSession session;

  /**
   * Creates an explainer over a live session.
   *
   * @param ruleSet the compiled rules
   * @param session the session whose working memory and refraction state to inspect
   */
  public MatchExplainer(final CompiledRuleSet ruleSet, final RuleSession session) {
    this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
    this.session = Objects.requireNonNull(session, "session");
  }

  /**
   * Explains why a rule has no match, across every candidate in working memory.
   *
   * @param ruleId the rule to explain
   * @return the explanation
   * @throws NoSuchElementException if no rule has that id
   */
  public Explanation explain(final String ruleId) {
    final CompiledRule rule = require(ruleId);
    final List<PatternResult> results = new ArrayList<>(rule.patterns().size());
    final Map<String, List<Long>> survivorsByAlias = new LinkedHashMap<>();

    for (final CompiledPattern pattern : rule.patterns()) {
      final PatternResult result = analyse(pattern);
      results.add(result);
      survivorsByAlias.put(pattern.alias(), result.survivors());
    }
    // Annotate first, then read the verdict off the annotated results: the join note IS one of the
    // verdicts, so handing the un-annotated list to verdict() silently loses the "nothing joined"
    // answer -- which is the one an author is least able to work out for themselves.
    final Matches matches = matches(rule, survivorsByAlias);
    final List<PatternResult> annotated = withJoinNotes(rule, results, matches);
    return new Explanation(ruleId, annotated, matches.negations(),
        verdict(rule, annotated, matches), matches.complete());
  }

  /**
   * Explains why a specific set of facts does not match a rule.
   *
   * <p>Unbound aliases are resolved as in {@link #explain(String)}, so a caller can pin the one or
   * two facts they are actually asking about and leave the rest open.
   *
   * @param ruleId the rule to explain
   * @param proposedBindings the facts the caller expected to match, by alias
   * @return the explanation
   * @throws NoSuchElementException if no rule has that id
   */
  public Explanation explain(final String ruleId, final Map<String, FactHandle> proposedBindings) {
    final CompiledRule rule = require(ruleId);
    final List<PatternResult> results = new ArrayList<>(rule.patterns().size());
    final Map<String, List<Long>> survivorsByAlias = new LinkedHashMap<>();

    for (final CompiledPattern pattern : rule.patterns()) {
      final FactHandle pinned = proposedBindings.get(pattern.alias());
      final PatternResult result = pinned == null ? analyse(pattern) : analysePinned(pattern, pinned);
      results.add(result);
      survivorsByAlias.put(pattern.alias(), result.survivors());
    }
    final Matches matches = matches(rule, survivorsByAlias);
    final List<PatternResult> annotated = withJoinNotes(rule, results, matches);
    /*
     * A pinned alias naming a negation leads, the way analysePinned's notes do: the question is
     * malformed and answering the one that was meant would be a guess. It became worth saying only
     * once negations were reported -- the output now prints "not p: Payment", which is an invitation
     * to pin `p`, and before this the answer to doing so was silence.
     */
    return new Explanation(ruleId, annotated, matches.negations(),
        pinnedNegatedAlias(rule, proposedBindings).or(() -> verdict(rule, annotated, matches)),
        matches.complete());
  }

  /**
   * Whether the caller pinned an alias belonging to a negated pattern.
   *
   * @param rule the rule
   * @param proposedBindings the facts the caller pinned, by alias
   * @return the diagnostic, or empty when every pinned alias binds something
   */
  private static Optional<String> pinnedNegatedAlias(final CompiledRule rule,
      final Map<String, FactHandle> proposedBindings) {
    for (final CompiledPattern negation : rule.negations()) {
      if (proposedBindings.containsKey(negation.alias())) {
        // Named, not merely reported as unknown: the compiler refuses a $ref to a negated alias
        // with the same distinction, because an alias the author can see, reported as one the rule
        // does not have, sends them hunting a typo that is not there.
        return Optional.of("'" + negation.alias() + "' is a NOT_EXISTS pattern: it binds no fact,"
            + " so pinning it constrains nothing (§1)");
      }
    }
    return Optional.empty();
  }

  /**
   * Runs one pattern's single-fact constraints over every fact of its type, recording casualties.
   *
   * @param pattern the pattern
   * @return what happened to it
   */
  private PatternResult analyse(final CompiledPattern pattern) {
    final List<Fact> population = session.workingMemory().factsOfType(pattern.factType()).toList();
    final List<Long> survivors = new ArrayList<>();
    // Keyed by the test's identity so that the constraint eliminating the most is reportable, which
    // is more useful than whichever happened to be written first.
    final Map<AlphaTest, ConstraintFailure> failures = new LinkedHashMap<>();

    for (final Fact candidate : population) {
      final Optional<AlphaTest> failed = firstFailing(pattern, candidate);
      if (failed.isEmpty()) {
        survivors.add(candidate.handle().id());
        continue;
      }
      final AlphaTest test = failed.get();
      failures.merge(test,
          new ConstraintFailure(test.constraint(), candidate.handle().id(),
              test.accessor().get(candidate.payload()), 1),
          (existing, ignored) -> new ConstraintFailure(existing.constraint(),
              existing.exampleHandle(), existing.actualValue(), existing.eliminated() + 1));
    }
    return new PatternResult(pattern.alias(), pattern.factType(), population.size(), survivors,
        failures.values().stream().max((left, right) ->
            Integer.compare(left.eliminated(), right.eliminated())),
        Optional.empty(), Optional.empty());
  }

  /**
   * Runs one pattern's constraints against a single pinned fact.
   *
   * @param pattern the pattern
   * @param pinned the fact the caller expected to match
   * @return what happened to it
   */
  private PatternResult analysePinned(final CompiledPattern pattern, final FactHandle pinned) {
    final Optional<Fact> fact = session.get(pinned);
    if (fact.isEmpty()) {
      // `considered` stays 1: a fact WAS named and looked at. Reporting 0 here made the verdict
      // announce "no Order fact exists" in a session holding fifty of them.
      return new PatternResult(pattern.alias(), pattern.factType(), 1, List.of(), Optional.empty(),
          Optional.empty(),
          Optional.of("fact #" + pinned.id() + " is not in working memory"));
    }
    if (!fact.get().type().equals(pattern.factType())) {
      return new PatternResult(pattern.alias(), pattern.factType(), 1, List.of(), Optional.empty(),
          Optional.empty(),
          Optional.of("fact #" + pinned.id() + " is a " + fact.get().type()
              + ", but " + pattern.alias() + " matches " + pattern.factType()));
    }
    final Optional<AlphaTest> failed = firstFailing(pattern, fact.get());
    return new PatternResult(pattern.alias(), pattern.factType(), 1,
        failed.isPresent() ? List.of() : List.of(pinned.id()),
        failed.map(test -> new ConstraintFailure(test.constraint(), pinned.id(),
            test.accessor().get(fact.get().payload()), 1)),
        Optional.empty(), Optional.empty());
  }

  /**
   * The first single-fact constraint a candidate fails.
   *
   * <p>First, not all: an author fixing a rule works one constraint at a time, and reporting every
   * failure for every fact produces noise rather than an answer.
   *
   * @param pattern the pattern
   * @param candidate the fact
   * @return the failing test, or empty when the fact passes them all
   */
  private static Optional<AlphaTest> firstFailing(final CompiledPattern pattern,
      final Fact candidate) {
    for (final AlphaTest test : pattern.alphaTests()) {
      if (!test.test(candidate.payload())) {
        return Optional.of(test);
      }
    }
    return Optional.empty();
  }

  /**
   * Adds a note to each pattern about what its cross-fact constraints did.
   *
   * <p>Derived from the <em>real</em> matches rather than from pairwise probing. An earlier version
   * asked, per join, "does any pair satisfy this one join", and returned on the first join that had
   * none. That is wrong in both directions: it reports nothing when two joins on one pattern are
   * each individually satisfiable but never together, and on a self-join it happily pairs a fact
   * with itself.
   *
   * @param rule the rule
   * @param results the per-pattern results so far
   * @param matches every real match, already enumerated
   * @return the results, with join notes filled in
   */
  private static List<PatternResult> withJoinNotes(final CompiledRule rule,
      final List<PatternResult> results, final Matches matches) {
    final List<PatternResult> annotated = new ArrayList<>(results.size());
    for (int position = 0; position < rule.patterns().size(); position++) {
      final CompiledPattern pattern = rule.patterns().get(position);
      final PatternResult result = results.get(position);
      // Only claim "nothing joined" when the search actually finished. An unfinished walk found no
      // combination YET, which is a different sentence -- and since verdict() returns a join note
      // before it ever reaches the budget-aware wording, emitting one here would make that wording
      // unreachable for every rule with a join and turn a lower bound into a definite negative.
      /*
       * `matches.found()` excludes tuples a §6.4 condition removed and tuples an asserted absence
       * suppressed, so an empty match set no longer implies the joins failed -- and blaming a join
       * that in fact holds sends the author to inspect correct code. Same lesson as the budget
       * guard beside it: only claim "nothing joined" when nothing else can account for the
       * emptiness.
       */
      final boolean joined = pattern.joinTests().isEmpty() || result.survivors().isEmpty()
          || !matches.complete() || matches.removedEverything();
      annotated.add(joined || !matches.found().isEmpty()
          ? result
          : new PatternResult(result.alias(), result.factType(), result.considered(),
              result.survivors(), result.firstFailure(),
              Optional.of(result.survivors().size() + " survivor(s), but no combination of them "
                  + "satisfies " + describeJoins(rule, pattern)
                  // Attributing an empty match set purely to the written joins misleads on a
                  // self-join, where it is §1's implicit inequality doing the rejecting and the
                  // named join would in fact hold.
                  + (pattern.distinctFrom().length > 0
                      ? ", and " + pattern.alias() + " must bind a different fact from "
                          + describeDistinct(rule, pattern)
                      : "")),
              result.note()));
    }
    return annotated;
  }

  /**
   * Renders the aliases a pattern must bind a different fact from.
   *
   * @param rule the rule
   * @param pattern the pattern
   * @return the alias names
   */
  private static String describeDistinct(final CompiledRule rule, final CompiledPattern pattern) {
    final StringBuilder text = new StringBuilder();
    for (final int other : pattern.distinctFrom()) {
      if (!text.isEmpty()) {
        text.append(" and ");
      }
      text.append(rule.patterns().get(other).alias());
    }
    return text.toString();
  }

  /**
   * Renders a pattern's join constraints for a diagnostic.
   *
   * @param rule the rule
   * @param pattern the pattern
   * @return the constraints, as the author wrote them
   */
  private static String describeJoins(final CompiledRule rule, final CompiledPattern pattern) {
    return pattern.joinTests().stream()
        .map(join -> pattern.alias() + "." + join.source().field()
            + " " + join.source().op() + " "
            + rule.patterns().get(join.otherIndex()).alias() + "." + join.source().otherField())
        .reduce((left, right) -> left + " and " + right)
        .orElse("its joins");
  }

  /**
   * Enumerates the rule's real matches over the surviving candidates.
   *
   * <p>Brute force, in the rule's written order, applying every cross-fact test and §1's implicit
   * inequality. That is deliberate: §7.2 says this diagnostic should re-evaluate rather than ask the
   * network, because the network is built to skip work without recording why it skipped it.
   *
   * <p>Bounded on both axes — matches reported and candidates examined — and which of the two ran
   * out is not reported separately, because for the reader the answer is the same: this is a lower
   * bound.
   *
   * @param rule the rule
   * @param survivorsByAlias each pattern's surviving handles
   * @return the matches found, and whether the search finished
   */
  private Matches matches(final CompiledRule rule,
      final Map<String, List<Long>> survivorsByAlias) {
    final List<long[]> found = new ArrayList<>();
    // One lookup per distinct handle instead of two per join evaluation. The walk is quadratic or
    // worse by nature, so a constant factor on its innermost operation is worth removing.
    final Map<Long, JsonNode> payloads = new LinkedHashMap<>();
    survivorsByAlias.values().forEach(handles -> handles.forEach(handle ->
        payloads.computeIfAbsent(handle, id ->
            session.get(new FactHandle(id)).map(Fact::payload).orElse(null))));
    final int[] budget = {WORK_LIMIT};
    // Snapshotted once, not per tuple. factsOfType copies (§ its own contract), and asking it again
    // for every complete tuple turned an O(population) check into an O(population) allocation.
    final List<List<Fact>> negated = rule.negations().stream()
        .map(negation -> session.workingMemory().factsOfType(negation.factType()).toList())
        .toList();
    final Tally tally = new Tally(negated);
    extend(rule, survivorsByAlias, payloads, 0, new long[rule.patterns().size()], found, budget,
        tally);
    /*
     * Whether a guard actually stopped the walk, rather than whether the budget happens to be
     * spent. The two differ now that one tuple can spend hundreds of budget units -- a negation is
     * charged for every candidate it scans, where a candidate step is charged one -- so the budget
     * can run out on the final tuple of a walk that then finished. That is not a truncation, and
     * inferring one would put "there may be a match" on a search that proved there is not.
     */
    return new Matches(found, !tally.truncated(),
        tally.rejectedByCondition(), tally.failedToEvaluate(), tally.suppressed(),
        negationResults(rule, tally));
  }

  /**
   * One result per negated pattern, whether or not it suppressed anything.
   *
   * <p>Reported even when it suppressed nothing, because "the rule asserts no {@code Payment} exists
   * and none does" is evidence the author needs in order to stop suspecting the negation and look
   * elsewhere. A list that appeared only on the failing case would leave them unable to tell a
   * negation that held from a negation this class forgot to check.
   *
   * @param rule the rule
   * @param tally what the walk accumulated
   * @return the per-negation results, in declaration order
   */
  private static List<NegationResult> negationResults(final CompiledRule rule, final Tally tally) {
    final List<NegationResult> results = new ArrayList<>(rule.negations().size());
    for (int index = 0; index < rule.negations().size(); index++) {
      final CompiledPattern negation = rule.negations().get(index);
      results.add(new NegationResult(negation.alias(), negation.factType(),
          tally.population(index).size(), tally.suppressedBy(index), tally.witnessOf(index)));
    }
    return results;
  }

  /**
   * Depth-first extension over the surviving candidates.
   *
   * @param rule the rule
   * @param survivorsByAlias each pattern's surviving handles
   * @param payloads the survivors' payloads, resolved once
   * @param position the pattern to bind next
   * @param bound the handles bound so far
   * @param found the matches collected so far
   * @param budget remaining candidate examinations, decremented in place
   * @param tally what became of the complete tuples, accumulated in place
   */
  private void extend(final CompiledRule rule, final Map<String, List<Long>> survivorsByAlias,
      final Map<Long, JsonNode> payloads, final int position, final long[] bound,
      final List<long[]> found, final int[] budget, final Tally tally) {
    // Unreachable as the callers stand -- a parent guarantees budget >= 1 before recursing and
    // re-checks after -- and kept as a guard rather than an assertion because the alternative is a
    // recursion whose bound lives entirely in its callers. It truncates for the same reason they
    // do, so relaxing a parent guard later cannot silently turn an abandoned subtree into a
    // complete search.
    if (found.size() >= MATCH_LIMIT || budget[0] <= 0) {
      tally.truncate();
      return;
    }
    if (position == rule.patterns().size()) {
      /*
       * §6.4's conditions are applied here, on the complete tuple, for the same reason the agenda
       * applies them there: they are an unindexed post-filter, not something that narrows a
       * pattern. Without this the explainer reported matches the engine would never fire -- the
       * exact inverse of the defect commit d973982 fixed, and worse, because §7.2 exists to answer
       * "why did my rule not fire" and a phantom match sends the author looking somewhere else.
       */
      /*
       * Negations before conditions, which is the order RecomputingAgenda.postFilter applies them
       * in -- absences() and then conditions(). It decides the counts as well as the answer: a
       * tuple an asserted absence defeats is never offered to the condition at run time, so
       * counting it as condition-rejected here would report a rejection the engine never made.
       */
      if (defeatedByNegation(rule, bound, budget, tally)) {
        return;
      }
      switch (conditionOutcome(rule, bound, payloads)) {
        case HELD -> found.add(bound.clone());
        case REJECTED -> tally.rejectByCondition();
        case FAILED -> tally.failToEvaluate();
      }
      return;
    }
    final CompiledPattern pattern = rule.patterns().get(position);
    final List<Long> candidates = survivorsByAlias.getOrDefault(pattern.alias(), List.of());
    for (int index = 0; index < candidates.size(); index++) {
      final long candidate = candidates.get(index);
      if (--budget[0] <= 0) {
        // This candidate was never looked at, so the walk really did leave something behind.
        tally.truncate();
        return;
      }
      if (pattern.conflictsWith(bound, candidate)
          || !joinsHold(rule, pattern, candidate, bound, payloads)) {
        continue;
      }
      bound[position] = candidate;
      extend(rule, survivorsByAlias, payloads, position + 1, bound, found, budget, tally);
      if (found.size() >= MATCH_LIMIT || budget[0] <= 0) {
        /*
         * Only a truncation if a candidate at this level remains. Spending the last of the budget
         * inside the FINAL candidate's subtree, which then finished, leaves nothing unexamined --
         * and reporting it as truncation is how a search that proved a rule cannot match ends up
         * saying "there may be a match". Negation made that reachable rather than theoretical: a
         * scan can spend hundreds of budget units on one tuple, where a candidate step spends one.
         */
        if (index < candidates.size() - 1) {
          tally.truncate();
        }
        return;
      }
    }
  }

  /**
   * Whether any absence this rule asserts is defeated for one complete tuple (§1's amendment).
   *
   * <p>The predicate is {@link Negations#scan}, which {@link Negations#witness} is the agenda's
   * one-line wrapper over. Re-implementing it here would
   * put a second copy of negation semantics against exactly the case where a disagreement is
   * hardest to notice: an explanation that contradicts the engine, on a rule the author already
   * believes is broken.
   *
   * <p><strong>Charged against the work budget for the candidates it actually examined.</strong>
   * Some charge is required: a rule with many complete tuples over a large negated type multiplies
   * the two, which is the shape of the blow-up {@link #WORK_LIMIT} exists to stop. Charging the
   * population's size instead was tried and is worse than it looks -- a scan short-circuits on the
   * first witness, so a rule whose absences are defeated early paid for a walk it never took, and
   * the search stopped to report "there may be a match" on a case where it had already found the
   * exact, nameable answer. {@link Negations#scan} reports the real number, which is bounded by the
   * same worst case and never overstates it.
   *
   * <p>Attributed to the <em>first</em> negation that defeats the tuple and then stopped, so the
   * per-negation counts sum to the matches lost rather than double-counting a tuple two negations
   * each defeat. Same reason {@link #firstFailing} reports one constraint: an author fixes one
   * thing at a time.
   *
   * @param rule the rule
   * @param bound the handles bound, in pattern order
   * @param budget remaining candidate examinations, decremented in place
   * @param tally where the suppression is recorded
   * @return whether some asserted absence does not hold
   */
  private boolean defeatedByNegation(final CompiledRule rule, final long[] bound,
      final int[] budget, final Tally tally) {
    for (int index = 0; index < rule.negations().size(); index++) {
      final Negations.Scan scan = Negations.scan(rule.negations().get(index), bound,
          tally.population(index), session.workingMemory());
      budget[0] -= scan.examined();
      if (scan.witness().isPresent()) {
        tally.suppress(index, scan.witness().orElseThrow().handle().id());
        return true;
      }
    }
    return false;
  }

  /**
   * What became of the complete tuples the walk reached, accumulated across the recursion.
   *
   * <p>Mutable, and threaded through {@link #extend} as an {@code int[]} was before it. Naming the
   * fields is what the array stopped being able to do once a third outcome joined "a condition
   * rejected it" and "a condition could not be evaluated" -- and the negated populations belong
   * here too, because they are read once per complete tuple and are per-negation state exactly as
   * the counts are.
   */
  private static final class Tally {

    /** No fact has this handle id, so it is unambiguously "nothing recorded yet". */
    private static final long NO_WITNESS = -1L;

    private final List<List<Fact>> populations;
    private final int[] suppressedBy;
    private final long[] witnessOf;
    private int rejectedByCondition;
    private int failedToEvaluate;
    private int suppressed;
    private boolean truncated;

    /**
     * Starts a tally over a rule's negated populations.
     *
     * @param populations the facts of each negated pattern's type, in declaration order
     */
    private Tally(final List<List<Fact>> populations) {
      this.populations = populations;
      this.suppressedBy = new int[populations.size()];
      this.witnessOf = new long[populations.size()];
      java.util.Arrays.fill(witnessOf, NO_WITNESS);
    }

    /**
     * The facts one negated pattern must be checked against.
     *
     * @param index the negation's declaration position
     * @return its type's population, snapshotted when the walk started
     */
    private List<Fact> population(final int index) {
      return populations.get(index);
    }

    /**
     * Records that one negation removed one otherwise-complete tuple.
     *
     * @param index the negation's declaration position
     * @param witnessHandle the handle of the fact that defeated the asserted absence
     */
    private void suppress(final int index, final long witnessHandle) {
      suppressedBy[index]++;
      suppressed++;
      if (witnessOf[index] == NO_WITNESS) {
        // First witness kept, not last: the walk runs in the rule's written order, so the first is
        // reproducible and the last depends on where a budget happened to stop.
        witnessOf[index] = witnessHandle;
      }
    }

    /**
     * Records a tuple a §6.4 condition answered false for.
     */
    private void rejectByCondition() {
      rejectedByCondition++;
    }

    /**
     * Records a tuple a §6.4 condition could not be evaluated against at all.
     */
    private void failToEvaluate() {
      failedToEvaluate++;
    }

    /**
     * How many tuples one negation removed.
     *
     * @param index the negation's declaration position
     * @return the count
     */
    private int suppressedBy(final int index) {
      return suppressedBy[index];
    }

    /**
     * The fact that defeated one negation, if any did.
     *
     * @param index the negation's declaration position
     * @return the witness handle id
     */
    private Optional<Long> witnessOf(final int index) {
      return witnessOf[index] == NO_WITNESS ? Optional.empty() : Optional.of(witnessOf[index]);
    }

    /**
     * How many tuples a §6.4 condition answered false for.
     *
     * @return the count
     */
    private int rejectedByCondition() {
      return rejectedByCondition;
    }

    /**
     * How many tuples a §6.4 condition could not be evaluated against.
     *
     * @return the count
     */
    private int failedToEvaluate() {
      return failedToEvaluate;
    }

    /**
     * How many tuples the rule's negations removed between them.
     *
     * @return the count
     */
    private int suppressed() {
      return suppressed;
    }

    /**
     * Records that a guard stopped the walk with combinations left unexamined.
     */
    private void truncate() {
      truncated = true;
    }

    /**
     * Whether any count in this tally is a lower bound rather than a total.
     *
     * @return true when the walk stopped early
     */
    private boolean truncated() {
      return truncated;
    }
  }

  /**
   * Whether a candidate satisfies every cross-fact test of its pattern.
   *
   * @param rule the rule
   * @param pattern the pattern
   * @param candidate the handle being considered
   * @param bound the handles bound so far
   * @param payloads the survivors' payloads
   * @return whether the joins hold
   */
  private boolean joinsHold(final CompiledRule rule, final CompiledPattern pattern,
      final long candidate, final long[] bound, final Map<Long, JsonNode> payloads) {
    for (final JoinTest join : pattern.joinTests()) {
      final JsonNode mine = payloads.get(candidate);
      final JsonNode theirs = payloads.get(bound[join.otherIndex()]);
      if (mine == null || theirs == null || !join.test(mine, theirs)) {
        return false;
      }
    }
    return true;
  }

  /**
   * What the combination walk found.
   *
   * @param found the complete tuples that satisfy every join and every §6.4 condition
   * @param complete false when a budget stopped the search, making every count a lower bound
   * @param rejectedByCondition how many otherwise-complete tuples a condition answered false for.
   *     Counted separately because it is the one verdict an author cannot work out from the
   *     per-pattern detail: every pattern matched, every join held, and the rule still did not fire
   * @param failedToEvaluate how many a condition could not be evaluated for at all. A different
   *     answer entirely, and the more urgent one: at run time that is not a filtered-out match, it
   *     is an exception that stops the fire cycle
   * @param suppressedByNegation how many otherwise-complete tuples an asserted absence removed
   *     between them (§1's {@code NOT_EXISTS}). Counted apart from the condition outcomes because
   *     the fix is a different one: a condition sends the author to their expression, a negation
   *     sends them to a fact that exists and that they expected not to
   * @param negations one result per negated pattern, in declaration order; empty for a rule that
   *     asserts no absence
   */
  private record Matches(List<long[]> found, boolean complete, int rejectedByCondition,
      int failedToEvaluate, int suppressedByNegation, List<NegationResult> negations) {

    /**
     * Whether something after the joins is why nothing matched.
     *
     * <p>Covers negation as well as §6.4's conditions, and it has to. Both remove tuples that
     * satisfied every pattern and every join, so either one leaving the match set empty would
     * otherwise be reported as "no combination satisfies the joins" -- blaming constraints that in
     * fact held, and sending the author to inspect correct code.
     *
     * @return true when a negation or a condition removed every complete tuple
     */
    private boolean removedEverything() {
      /*
       * `complete` is not optional here. A search stopped by its budget has examined some prefix of
       * the combinations, so "a condition rejected each of them" would assert an exhaustiveness the
       * walk never reached -- and the count would be the budget rather than a count, which is
       * exactly the defect commit 10588b2 fixed by hand ("stop it reporting a budget as a count").
       * Falling through leaves refractionVerdict's budget wording, which is already careful.
       */
      return complete && found.isEmpty()
          && rejectedByCondition + failedToEvaluate + suppressedByNegation > 0;
    }
  }

  /** What a rule's conditions did to one complete tuple. */
  private enum ConditionOutcome { HELD, REJECTED, FAILED }

  /**
   * How to say that a §6.4 condition is why nothing matched.
   *
   * <p>The two outcomes are worded apart because they are different problems. A condition that
   * answered false filtered the facts out, which may well be what the author intended elsewhere in
   * the rule. A condition that could not be evaluated does not filter anything at run time -- it
   * throws, and stops the fire cycle -- so telling somebody their facts were "rejected" would point
   * them at their data when the fault is in the expression.
   *
   * @param matches what the walk found
   * @return the verdict
   */
  private static String conditionVerdict(final Matches matches) {
    if (matches.rejectedByCondition() == 0) {
      return matches.failedToEvaluate()
          + " combination(s) matched every pattern and join, but a 'condition' expression could not"
          + " be evaluated against any of them. At run time that is not a filtered-out match: it"
          + " throws and stops the fire cycle (§6.4)";
    }
    if (matches.failedToEvaluate() == 0) {
      return matches.rejectedByCondition()
          + " combination(s) matched every pattern and join, but a 'condition' expression rejected"
          + " each of them (§6.4)";
    }
    return matches.rejectedByCondition() + " combination(s) were rejected by a 'condition'"
        + " expression and " + matches.failedToEvaluate() + " could not be evaluated at all,"
        + " the latter throwing and stopping the fire cycle at run time (§6.4)";
  }

  /**
   * How to say that an asserted absence is why nothing matched (§1's {@code NOT_EXISTS}).
   *
   * <p>The verdict a rule author is least able to reach on their own, and the one this class used
   * to get exactly backwards. Every pattern matched, every join held, and the rule is silent
   * because something the author asserted was <em>not</em> there is -- so the answer has to name it.
   * A sentence saying only "a negation suppressed the match" leaves them scanning the whole
   * population of a type for a fact they believe is absent.
   *
   * <p>The count is the <em>total</em> the rule's negations suppressed, because the condition
   * clauses appended after it count tuples the negations let through -- the numbers have to
   * partition the complete tuples rather than overlap. Only the named example is attributed, to the
   * negation that suppressed the most, as {@code firstFailure} attributes a pattern's casualties;
   * ties keep the earlier declaration so two runs read the same. The rest are in
   * {@link Explanation#negations()} for anyone who needs them.
   *
   * @param matches what the walk found
   * @return the verdict
   */
  private static String negationVerdict(final Matches matches) {
    final NegationResult worst = matches.negations().stream()
        .max((left, right) -> Integer.compare(left.suppressed(), right.suppressed()))
        .orElseThrow();
    /*
     * The headline is the TOTAL suppressed, not the worst negation's share, because the clauses
     * appended below count tuples the negations let through -- so the three numbers have to
     * partition the complete tuples rather than overlap. Only the named example comes from the
     * worst one.
     */
    final StringBuilder text = new StringBuilder()
        .append(matches.suppressedByNegation())
        .append(" combination(s) matched every pattern and join, but the rule asserts that no ")
        .append(worst.factType()).append(" matches '").append(worst.alias()).append("' and ")
        .append(worst.exampleWitness().map(handle -> "fact #" + handle + " does")
            .orElse("one does"))
        .append(" (§1's NOT_EXISTS)");
    /*
     * The condition counts are tuples the negations let through and something else then removed, so
     * they are additional to the number above rather than another view of it. Kept as a trailing
     * clause rather than folded into conditionVerdict's sentence, which opens by claiming the
     * combinations it counts are all of them.
     */
    if (matches.rejectedByCondition() > 0) {
      text.append("; a further ").append(matches.rejectedByCondition())
          .append(" were rejected by a 'condition' expression (§6.4)");
    }
    if (matches.failedToEvaluate() > 0) {
      text.append("; a further ").append(matches.failedToEvaluate())
          .append(" could not be evaluated by a 'condition' expression at all, which at run time")
          .append(" throws and stops the fire cycle (§6.4)");
    }
    return text.toString();
  }

  /**
   * Whether every §6.4 condition on a rule holds for one complete tuple.
   *
   * <p>Re-evaluated here rather than read from anywhere, which is {@code MatchExplainer}'s whole
   * approach: the matching network is optimised not to record why something failed, so the only way
   * to know is to ask again, slowly.
   *
   * <p>An expression that throws is reported as "did not hold" rather than propagated. Everywhere
   * else that is the wrong trade -- §6.4 wants failures visible -- but this class exists to explain
   * a rule that is already not firing, and a diagnostic tool that throws while diagnosing is worse
   * than one that says less. The count still surfaces in the verdict.
   *
   * @param rule the rule
   * @param bound the handles bound, in pattern order
   * @param payloads the survivors' payloads
   * @return whether the conditions held, said no, or could not be evaluated
   */
  private ConditionOutcome conditionOutcome(final CompiledRule rule, final long[] bound,
      final Map<Long, JsonNode> payloads) {
    if (!rule.hasExpressionTests()) {
      return ConditionOutcome.HELD;
    }
    final Map<String, JsonNode> byAlias = new LinkedHashMap<>();
    for (int position = 0; position < rule.patterns().size(); position++) {
      byAlias.put(rule.patterns().get(position).alias(), payloads.get(bound[position]));
    }
    final ExpressionBindings bindings = alias -> {
      final JsonNode payload = byAlias.get(alias);
      return payload == null ? MissingNode.getInstance() : payload;
    };
    for (final CompiledPattern pattern : rule.patterns()) {
      for (final ExpressionTest test : pattern.expressionTests()) {
        try {
          if (!test.program().test(bindings)) {
            return ConditionOutcome.REJECTED;
          }
        } catch (final RuntimeException failed) {
          return ConditionOutcome.FAILED;
        }
      }
    }
    return ConditionOutcome.HELD;
  }

  /**
   * The one-sentence answer.
   *
   * <p>Ordered by how early the rule failed, because the earliest failure is the one to fix. The
   * last case is the one §7.2 says nobody guesses.
   *
   * @param rule the rule
   * @param results the per-pattern results
   * @param matches the real matches
   * @return the verdict
   */
  private Optional<String> verdict(final CompiledRule rule, final List<PatternResult> results,
      final Matches matches) {
    for (final PatternResult result : results) {
      if (result.note().isPresent()) {
        return result.note();
      }
    }
    for (final PatternResult result : results) {
      if (result.considered() == 0) {
        return Optional.of("no " + result.factType() + " fact exists"
            + evictionNote(result.factType()));
      }
    }
    for (final PatternResult result : results) {
      if (result.survivors().isEmpty()) {
        return Optional.of(result.considered() + " " + result.factType()
            + "(s) considered; none matched " + result.alias()
            + result.firstFailure().map(failure -> " — " + failure.describe()).orElse("")
            + evictionNote(result.factType()));
      }
    }
    /*
     * Before the join notes, not after. A condition that removed every tuple has already suppressed
     * those notes above, but ordering it first says plainly that this branch is the more specific
     * answer: the joins did hold, and something after them said no.
     */
    if (matches.removedEverything()) {
      // Negation first when both applied, because it is the earlier gate: the tuples it removed
      // were never offered to the condition, so a condition-shaped verdict would describe a
      // filtering that did not happen to them.
      return Optional.of(matches.suppressedByNegation() > 0
          ? negationVerdict(matches)
          : conditionVerdict(matches));
    }
    for (final PatternResult result : results) {
      if (result.joinNote().isPresent()) {
        // The verdict a streaming eviction most often lands on, and the one this note was missing.
        // Cap a type, let a fact age out, and the facts that joined to it still pass their own
        // alpha tests -- so the pattern has survivors and the answer is the join, which is correct
        // and which the author will now go and inspect for a defect that is not there.
        return result.joinNote().map(note -> note + evictionNote(rule));
      }
    }
    return refractionVerdict(rule, matches);
  }

  /**
   * What the session has evicted of one fact type, phrased for the end of a verdict.
   *
   * <p><strong>The blind spot §4.4's eviction opens, and the reason this method exists.</strong> A
   * rule that stops matching because its facts were let go reads exactly like a rule that never
   * matched: "no Order fact exists" is true, complete, and sends an author to look at their rule
   * when the answer is their cap. This class exists to answer "why did R not fire", and after
   * eviction landed there was a whole category of that question it answered misleadingly.
   *
   * <p>The evicted facts themselves are gone, so a count is all there is to say -- but the count is
   * the part that changes what the reader does next. Empty when nothing of this type was evicted,
   * which is every session that configured no policy.
   *
   * @param factType the type the verdict is about
   * @return a trailing clause, or empty text when this type has lost nothing
   */
  private String evictionNote(final String factType) {
    final long evicted = evictedByType().getOrDefault(factType, 0L);
    return evicted == 0L ? "" : " (" + evicted + " " + factType + " fact(s) evicted this session)";
  }

  /**
   * The same clause for a verdict that is about a whole rule rather than one pattern.
   *
   * <p>A join or cross-fact verdict says no <em>combination</em> works, which is not a statement
   * about one fact type, so this names every patterned type that has lost facts. Ordered by the
   * rule's own patterns rather than by the map, so two rules over the same session read
   * consistently.
   *
   * @param rule the rule being explained
   * @return a trailing clause, or empty text when nothing the rule patterns has lost facts
   */
  private String evictionNote(final CompiledRule rule) {
    final Map<String, Long> evicted = evictedByType();
    final StringBuilder text = new StringBuilder();
    final Set<String> named = new LinkedHashSet<>();
    for (final CompiledPattern pattern : rule.patterns()) {
      final long count = evicted.getOrDefault(pattern.factType(), 0L);
      if (count == 0L || !named.add(pattern.factType())) {
        continue;
      }
      text.append(text.isEmpty() ? " (" : ", ")
          .append(count).append(' ').append(pattern.factType()).append(" fact(s) evicted");
    }
    return text.isEmpty() ? "" : text.append(" this session)").toString();
  }

  /**
   * A warning for a rule that matched while the session was evicting a type it negates.
   *
   * <p><strong>This clause inverts the rule the note beside it follows, and deliberately.</strong>
   * {@link #evictionNote(CompiledRule)} is attached only where a rule did <em>not</em> match,
   * because a clause on every explanation of every capped session stops being read. A negated type
   * is the opposite case: eviction there can only ever cost the rule a <em>suppression</em>, so the
   * danger is precisely when the rule <em>did</em> match. §4.4 is blunt about what that means --
   * everywhere else eviction costs a firing, here it manufactures a false conclusion, and the
   * engine announces that a paid order is unpaid.
   *
   * <p>The explainer cannot detect it, because it re-asks the same question of the same working
   * memory and is fooled identically. What it can do is put the count in front of the reader, which
   * is the part that changes what they do next.
   *
   * @param rule the rule being explained
   * @return a trailing warning, or empty text when no negated type has lost facts
   */
  private String negatedEvictionWarning(final CompiledRule rule) {
    if (!rule.hasNegations()) {
      return "";
    }
    final Map<String, Long> evicted = evictedByType();
    final StringBuilder text = new StringBuilder();
    final Set<String> named = new LinkedHashSet<>();
    for (final CompiledPattern negation : rule.negations()) {
      final long count = evicted.getOrDefault(negation.factType(), 0L);
      if (count == 0L || !named.add(negation.factType())) {
        continue;
      }
      text.append(text.isEmpty() ? " — WARNING: " : ", ")
          .append(count).append(' ').append(negation.factType()).append(" fact(s) evicted");
    }
    return text.isEmpty() ? "" : text.append(" this session, and this rule asserts their absence:"
        + " an evicted fact and an absent one are indistinguishable to a negation, so this match"
        + " may be a false conclusion (§4.4)").toString();
  }

  /**
   * This session's per-type eviction counts.
   *
   * <p>{@link RuleSession#stats()} assembles a record and copies this map to answer, so it is worth
   * calling once and holding the result -- which is what {@link #evictionNote(CompiledRule)} does,
   * turning one call per pattern into one per verdict. The per-pattern overload calls it once and
   * only ever runs on one branch, so it was never the cost here.
   *
   * @return the counts, empty when no eviction policy is configured
   */
  private Map<String, Long> evictedByType() {
    return session.stats().evictedByType();
  }

  /**
   * The verdict for a rule whose patterns all matched and whose joins hold: it probably fired.
   *
   * <p>§7.2 calls this "the one nobody guesses". A rule that "stopped working" has usually already
   * fired on those exact facts, and saying so — with the recency — is the difference between an
   * explanation and a list of constraints the author can see are satisfied.
   *
   * <p>Checked against <strong>every</strong> real match, not one representative. An earlier version
   * built a key from the first survivor of each pattern, which is an arbitrary combination that
   * usually is not a match at all — on any self-join it paired a fact with itself, a key that can
   * never have fired by construction — so the verdict this method exists for was unreachable in
   * every case with more than one fact per pattern.
   *
   * @param rule the rule
   * @param matches the real matches
   * @return the verdict
   */
  private Optional<String> refractionVerdict(final CompiledRule rule, final Matches matches) {
    if (matches.found().isEmpty()) {
      /*
       * The eviction clause for a PATTERNED type belongs to THIS branch and to none of the
       * others, and an earlier version of it was appended to all five. The four below say the rule
       * matched -- "all already fired" is a rule working exactly as intended -- and a session with
       * a cap has a permanently non-zero evicted count, so gluing the clause onto them puts it on
       * every explanation of every rule for the rest of that session's life. "A clause that is
       * always there stops being read" is the reason it is empty when nothing was evicted;
       * appending it to the success paths recreates that failure precisely where the note matters
       * most.
       *
       * A NEGATED type is the exact inverse and the four branches below DO carry a warning for it;
       * see negatedEvictionWarning, which argues why. The two are not in conflict: eviction of a
       * type the rule needs can only cost it a match, so the note belongs where it found none,
       * while eviction of a type the rule asserts the absence of can only manufacture one, so the
       * warning belongs where it found some.
       *
       * Not on the budget-exhausted case either: the search did not finish, so what the session let
       * go of is not the story. There may well be a match.
       */
      return Optional.of(matches.complete()
          ? "every pattern matched individually, but no combination of them satisfies the rule's"
              + " cross-fact constraints" + evictionNote(rule)
          // A truncated search cannot claim a negation is THE answer -- removedEverything() is
          // what does that, and it requires a finished walk -- but it can say what it saw. Without
          // this clause a heavily-suppressed rule falls back to a sentence that mentions nothing
          // the author can act on, which is the failure this whole section exists to stop.
          : "no match found before the search budget ran out; there may be one"
              + (matches.suppressedByNegation() > 0
                  ? " (at least " + matches.suppressedByNegation() + " combination(s) examined so"
                      + " far were suppressed by an absence the rule asserts)"
                  : ""));
    }
    final List<String> fired = new ArrayList<>();
    int eligible = 0;
    for (final long[] match : matches.found()) {
      final Optional<Long> recency = session.firedAt(new ActivationKey(rule.id(), match));
      if (recency.isPresent()) {
        if (fired.size() < EXAMPLES) {
          fired.add(java.util.Arrays.toString(match) + " at recency " + recency.get());
        }
      } else {
        eligible++;
      }
    }
    final int firedCount = matches.found().size() - eligible;
    // "at least", not a total, whenever a budget stopped the search. An earlier version printed the
    // budget itself as the count -- a confidently stated wrong number.
    final String total = (matches.complete() ? "" : "at least ") + matches.found().size();
    if (firedCount == 0) {
      return Optional.of(total + " match(es); all eligible, none has fired yet"
          + negatedEvictionWarning(rule));
    }
    final String examples = String.join(", ", fired)
        + (firedCount > fired.size() ? ", and " + (firedCount - fired.size()) + " more" : "");
    if (eligible == 0) {
      return Optional.of("matched, but refracted — " + total
          + " match(es), all already fired: " + examples + negatedEvictionWarning(rule));
    }
    return Optional.of(total + " match(es): " + firedCount + " already fired (" + examples
        + "), " + eligible + " still eligible" + negatedEvictionWarning(rule));
  }

  /**
   * Looks up a compiled rule.
   *
   * @param ruleId the rule id
   * @return the rule
   * @throws NoSuchElementException if no rule has that id
   */
  private CompiledRule require(final String ruleId) {
    return ruleSet.rules().stream()
        .filter(rule -> rule.id().equals(ruleId))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("no rule with id '" + ruleId + "'"));
  }
}
