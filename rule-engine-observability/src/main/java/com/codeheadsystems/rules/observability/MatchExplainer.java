package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.match.Accumulators;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.match.Negations;
import com.codeheadsystems.rules.match.Scan;
import com.codeheadsystems.rules.match.Universals;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.AggregateTest;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledAccumulate;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.ExpressionTest;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.value.Comparisons;
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
 * <h2>The quantifiers</h2>
 *
 * <p>A {@code NOT_EXISTS} or {@code FOR_ALL} pattern is not in {@code CompiledRule.patterns()} --
 * deliberately, so that the join planner, the join walk and the streaming matcher's pattern sites
 * need not know quantifiers exist -- so the walk above cannot see one, and for a while this class
 * could not either. A rule suppressed because the fact whose absence it asserts is <em>present</em>
 * reported "N match(es); all eligible, none has fired yet": the opposite of the truth, with the
 * offending fact named nowhere. That was §7.2's claim failing on precisely the rules §1 calls "the
 * first ten rules most people write".
 *
 * <p>So quantifiers are evaluated here too, against each complete tuple and <strong>before</strong>
 * the §6.4 conditions, which is the order {@code RecomputingAgenda.postFilter} applies them in. The
 * predicates are not re-implemented: they are {@link Negations#scan}, {@link Universals#scan} and
 * {@link Accumulators#evaluate}, the same code the agenda decides with. That sharing is the point rather than a convenience -- a
 * diagnostic that disagrees with the engine it is diagnosing sends an author to fix a rule that is
 * already correct -- and it is also what supplies the part an author cannot derive, the
 * {@link QuantifierResult#example()} that says <em>which</em> fact is standing in the way.
 *
 * <p>Two limits remain, and both are properties of the quantifiers rather than of this class.
 * Because a quantified pattern binds nothing, there is no candidate population to report survivors
 * and casualties over, so {@link QuantifierResult} answers different questions from
 * {@link PatternResult} and is kept in a separate list. And an evicted fact is indistinguishable
 * from one that was never there (§4.4), so over an evicted type this explains exactly what the
 * engine does -- which is the wrong answer, identically arrived at. What it can do is say so, and
 * {@code evictionHazardWarning} is where it does.
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
    return new Explanation(ruleId, annotated, matches.quantifiers(),
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
     * A pinned alias naming a quantifier leads, the way analysePinned's notes do: the question is
     * malformed and answering the one that was meant would be a guess. It became worth saying only
     * once quantifiers were reported -- the output now prints "not p: Payment" and "all li:
     * LineItem", each an invitation to pin that alias, and before this the answer to doing so was
     * silence.
     */
    return new Explanation(ruleId, annotated, matches.quantifiers(),
        pinnedQuantifiedAlias(rule, proposedBindings).or(() -> verdict(rule, annotated, matches)),
        matches.complete());
  }

  /**
   * Whether the caller pinned an alias belonging to a quantified pattern.
   *
   * @param rule the rule
   * @param proposedBindings the facts the caller pinned, by alias
   * @return the diagnostic, or empty when every pinned alias binds something
   */
  private static Optional<String> pinnedQuantifiedAlias(final CompiledRule rule,
      final Map<String, FactHandle> proposedBindings) {
    for (final CompiledPattern negation : rule.negations()) {
      if (proposedBindings.containsKey(negation.alias())) {
        // Named, not merely reported as unknown: the compiler refuses a $ref to a quantified alias
        // with the same distinction, because an alias the author can see, reported as one the rule
        // does not have, sends them hunting a typo that is not there.
        return Optional.of("'" + negation.alias() + "' is a NOT_EXISTS pattern: it binds no fact,"
            + " so pinning it constrains nothing (§1)");
      }
    }
    for (final CompiledPattern universal : rule.universals()) {
      if (proposedBindings.containsKey(universal.alias())) {
        return Optional.of("'" + universal.alias() + "' is a FOR_ALL pattern: it asserts something"
            + " about every fact in its scope rather than binding one, so pinning it constrains"
            + " nothing (§2.5)");
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
            + rule.patterns().get(join.otherIndex()).alias() + "." + join.source().otherField()
            // The bound is half the relation, so a temporal join reported without it names a
            // constraint the author did not write and would go looking for.
            + join.source().within().map(window -> " within " + window).orElse(""))
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
    final Tally tally = new Tally(quantified(rule));
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
        tally.results());
  }

  /**
   * The rule's quantified patterns, paired with the populations they range over.
   *
   * <p>Negations then universals, which is the order {@code RecomputingAgenda.postFilter} applies
   * them in. The order cannot change whether a tuple survives -- it must pass all of them -- but it
   * decides which one a removed tuple is attributed to, and an attribution that depended on
   * evaluation order would be a different explanation on a different day.
   *
   * <p>Populations are snapshotted once rather than per tuple. {@code factsOfType} answers with a
   * copy by contract, so asking it again for every complete tuple turned an O(population) check
   * into an O(population) allocation.
   *
   * @param rule the rule
   * @return one entry per quantified pattern, in evaluation order
   */
  private List<Quantified> quantified(final CompiledRule rule) {
    final List<Quantified> all =
        new ArrayList<>(rule.negations().size() + rule.universals().size()
            + rule.accumulates().size());
    for (final CompiledPattern negation : rule.negations()) {
      all.add(new Quantified(Quantifier.NOT_EXISTS, negation,
          session.workingMemory().factsOfType(negation.factType()).toList(), Optional.empty()));
    }
    for (final CompiledPattern universal : rule.universals()) {
      all.add(new Quantified(Quantifier.FOR_ALL, universal,
          session.workingMemory().factsOfType(universal.factType()).toList(), Optional.empty()));
    }
    /*
     * Accumulates last, matching RecomputingAgenda.postFilter -- negations, universals, havings,
     * then §6.4's conditions. Only the ones carrying a `having` can suppress anything, but all of
     * them are listed: an author looking at a silent rule needs to see the fold that did not
     * suppress it as much as the one that did.
     */
    for (final CompiledAccumulate accumulate : rule.accumulates()) {
      all.add(new Quantified(Quantifier.ACCUMULATE, accumulate.scope(),
          session.workingMemory().factsOfType(accumulate.scope().factType()).toList(),
          Optional.of(accumulate)));
    }
    return all;
  }

  /**
   * One quantified pattern and the facts it ranges over.
   *
   * @param kind which quantifier it carries
   * @param pattern the compiled pattern, whose join tests point at positive positions
   * @param population every fact of its type, snapshotted when the walk started
   * @param accumulate the fold, present only for {@link Quantifier#ACCUMULATE}
   */
  private record Quantified(Quantifier kind, CompiledPattern pattern, List<Fact> population,
      Optional<CompiledAccumulate> accumulate) {
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
       * Quantifiers before conditions, which is the order RecomputingAgenda.postFilter applies
       * them in -- absences(), then requirements(), then conditions(). It decides the counts as
       * well as the answer: a tuple a quantifier removes is never offered to the condition at run
       * time, so counting it as condition-rejected here would report a rejection the engine never
       * made.
       */
      if (removedByQuantifier(bound, budget, tally)) {
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
   * Whether any quantifier this rule carries removes one complete tuple (§2.5).
   *
   * <p>The predicates are {@link Negations#scan} and {@link Universals#scan}, the agenda's own.
   * Re-implementing either here would put a second copy of quantifier semantics against exactly the
   * case where a disagreement is hardest to notice: an explanation that contradicts the engine, on a
   * rule the author already believes is broken.
   *
   * <p><strong>Charged against the work budget for the candidates it actually examined.</strong>
   * Some charge is required: a rule with many complete tuples over a large quantified type
   * multiplies the two, which is the shape of the blow-up {@link #WORK_LIMIT} exists to stop.
   * Charging the population's size instead was tried and is worse than it looks -- a scan
   * short-circuits on the first result, so a rule whose quantifiers are settled early paid for a
   * walk it never took, and the search stopped to report "there may be a match" on a case where it
   * had already found the exact, nameable answer. The scans report the real number, which is bounded
   * by the same worst case and never overstates it.
   *
   * <p>Attributed to the <em>first</em> quantifier that removes the tuple and then stopped, so the
   * per-quantifier counts sum to the matches lost rather than double-counting a tuple two of them
   * each remove. Same reason {@link #firstFailing} reports one constraint: an author fixes one thing
   * at a time.
   *
   * @param bound the handles bound, in pattern order
   * @param budget remaining candidate examinations, decremented in place
   * @param tally the quantified patterns, and where the removal is recorded
   * @return whether some quantifier does not hold
   */
  private boolean removedByQuantifier(final long[] bound, final int[] budget, final Tally tally) {
    for (int index = 0; index < tally.size(); index++) {
      final Quantified quantified = tally.quantified(index);
      if (quantified.kind() == Quantifier.ACCUMULATE) {
        final CompiledAccumulate accumulate = quantified.accumulate().orElseThrow();
        final Optional<AggregateTest> having = accumulate.having();
        if (having.isEmpty()) {
          // A binding-only accumulate suppresses nothing, so nothing is folded and nothing is
          // charged. Debiting here anyway -- which an earlier version did, before the emptiness
          // check -- burned the budget on work never done and could truncate the walk into "there
          // may be a match" on a rule this class could have answered exactly.
          continue;
        }
        // Charged at the population's size: a fold has no witness to short-circuit on, so it walks
        // the whole scope every time. That is the honest number here, unlike for the two scans.
        budget[0] -= quantified.population().size();
        if (!Comparisons.test(having.get().op(),
            Accumulators.evaluate(accumulate, bound, quantified.population(),
                session.workingMemory()),
            having.get().literal())) {
          // No example handle: what suppressed the match is the ANSWER, and naming one contributor
          // would point the author at a fact that is doing nothing wrong.
          tally.suppress(index, Tally.NO_EXAMPLE);
          return true;
        }
        continue;
      }
      final Scan scan = quantified.kind() == Quantifier.NOT_EXISTS
          ? Negations.scan(quantified.pattern(), bound, quantified.population(),
              session.workingMemory())
          : Universals.scan(quantified.pattern(), bound, quantified.population(),
              session.workingMemory());
      budget[0] -= scan.examined();
      if (scan.found().isPresent()) {
        tally.suppress(index, scan.found().orElseThrow().handle().id());
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
    static final long NO_EXAMPLE = -1L;

    private final List<Quantified> quantified;
    private final int[] suppressedBy;
    private final long[] exampleOf;
    private int rejectedByCondition;
    private int failedToEvaluate;
    private int suppressed;
    private boolean truncated;

    /**
     * Starts a tally over a rule's quantified patterns.
     *
     * @param quantified the quantified patterns and their populations, in evaluation order
     */
    private Tally(final List<Quantified> quantified) {
      this.quantified = quantified;
      this.suppressedBy = new int[quantified.size()];
      this.exampleOf = new long[quantified.size()];
      java.util.Arrays.fill(exampleOf, NO_EXAMPLE);
    }

    /**
     * How many quantified patterns this rule carries.
     *
     * @return the count
     */
    private int size() {
      return quantified.size();
    }

    /**
     * One quantified pattern and the facts it ranges over.
     *
     * @param index the pattern's position in evaluation order
     * @return the pattern, its kind and its population
     */
    private Quantified quantified(final int index) {
      return quantified.get(index);
    }

    /**
     * Records that one quantifier removed one otherwise-complete tuple.
     *
     * @param index the quantifier's position in evaluation order
     * @param exampleHandle the handle of the fact that settled it
     */
    private void suppress(final int index, final long exampleHandle) {
      suppressedBy[index]++;
      suppressed++;
      if (exampleOf[index] == NO_EXAMPLE) {
        // First example kept, not last: the walk runs in the rule's written order, so the first is
        // reproducible and the last depends on where a budget happened to stop.
        exampleOf[index] = exampleHandle;
      }
    }

    /**
     * One result per quantified pattern, whether or not it suppressed anything.
     *
     * <p>Reported even when it suppressed nothing, because "the rule asserts no {@code Payment}
     * exists and none does" is evidence the author needs in order to stop suspecting the quantifier
     * and look elsewhere. A list that appeared only on the failing case would leave them unable to
     * tell a quantifier that held from one this class forgot to check.
     *
     * @return the results, in evaluation order
     */
    private List<QuantifierResult> results() {
      final List<QuantifierResult> results = new ArrayList<>(quantified.size());
      for (int index = 0; index < quantified.size(); index++) {
        final Quantified one = quantified.get(index);
        results.add(new QuantifierResult(one.kind(),
            one.accumulate().map(CompiledAccumulate::alias).orElseGet(one.pattern()::alias),
            one.pattern().factType(), one.population().size(), suppressedBy[index],
            exampleOf[index] == NO_EXAMPLE ? Optional.empty() : Optional.of(exampleOf[index])));
      }
      return results;
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
     * How many tuples the rule's quantifiers removed between them.
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
   * @param suppressedByQuantifier how many otherwise-complete tuples the rule's quantifiers removed
   *     between them (§2.5). Counted apart from the condition outcomes because the fix is a
   *     different one: a condition sends the author to their expression, a quantifier sends them to
   *     a fact that exists and that they expected not to, or to one that fails a requirement they
   *     expected everything in scope to meet
   * @param quantifiers one result per quantified pattern, in evaluation order; empty for a rule that
   *     quantifies over nothing
   */
  private record Matches(List<long[]> found, boolean complete, int rejectedByCondition,
      int failedToEvaluate, int suppressedByQuantifier, List<QuantifierResult> quantifiers) {

    /**
     * Whether something after the joins is why nothing matched.
     *
     * <p>Covers the §2.5 quantifiers as well as §6.4's conditions, and it has to. All of them
     * remove tuples that satisfied every pattern and every join, so any one of them leaving the
     * match set empty would otherwise be reported as "no combination satisfies the joins" --
     * blaming constraints that in fact held, and sending the author to inspect correct code.
     *
     * @return true when a quantifier or a condition removed every complete tuple
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
          && rejectedByCondition + failedToEvaluate + suppressedByQuantifier > 0;
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
   * How to say that a §2.5 quantifier is why nothing matched.
   *
   * <p>The verdict a rule author is least able to reach on their own, and the one this class used to
   * get exactly backwards. Every pattern matched, every join held, and the rule is silent because of
   * facts it does not bind -- one that is there and was asserted not to be, one in scope that fails
   * what was asserted of everything in scope, or a fold whose answer misses its {@code having}. The
   * first two name the fact; the third cannot, because what suppressed the match is the answer and
   * no single contributor is at fault. A sentence
   * saying only "a quantifier suppressed the match" leaves the author scanning a whole population
   * for a fact they believe is absent or compliant.
   *
   * <p>The count is the <em>total</em> the rule's quantifiers suppressed, because the condition
   * clauses appended after it count tuples the quantifiers let through -- the numbers have to
   * partition the complete tuples rather than overlap. Only the named example is attributed, to the
   * quantifier that suppressed the most, as {@code firstFailure} attributes a pattern's casualties;
   * ties keep the earlier position so two runs read the same. The rest are in
   * {@link Explanation#quantifiers()} for anyone who needs them.
   *
   * @param rule the rule being explained
   * @param matches what the walk found
   * @return the verdict
   */
  private String quantifierVerdict(final CompiledRule rule, final Matches matches) {
    final QuantifierResult worst = matches.quantifiers().stream()
        .max((left, right) -> Integer.compare(left.suppressed(), right.suppressed()))
        .orElseThrow();
    final StringBuilder text = new StringBuilder()
        .append(matches.suppressedByQuantifier())
        .append(" combination(s) matched every pattern and join, but ");
    if (worst.kind() == Quantifier.ACCUMULATE) {
      text.append("the rule folds ").append(worst.factType()).append(" into '")
          .append(worst.alias()).append("' and the answer fails its 'having' (§2.5's ACCUMULATE)");
    } else if (worst.kind() == Quantifier.NOT_EXISTS) {
      text.append("the rule asserts that no ").append(worst.factType())
          .append(" matches '").append(worst.alias()).append("' and ")
          .append(worst.example().map(handle -> "fact #" + handle + " does").orElse("one does"))
          .append(" (§1's NOT_EXISTS)");
    } else {
      text.append("the rule asserts that every ").append(worst.factType())
          .append(" in scope for '").append(worst.alias()).append("' matches it and ")
          .append(worst.example().map(handle -> "fact #" + handle + " does not")
              .orElse("one does not"))
          .append(" (§2.5's FOR_ALL)");
    }
    /*
     * The condition counts are tuples the quantifiers let through and something else then removed,
     * so they are additional to the number above rather than another view of it. Kept as a trailing
     * clause rather than folded into conditionVerdict's sentence, which opens by claiming the
     * combinations it counts are all of them.
     */
    /*
     * A FOLD is the one gate here that eviction can be the cause of. A negation or a universal is
     * defeated by a fact that is PRESENT, and eviction only removes -- so it cannot be why either
     * suppressed a match, and saying so would send the author to inspect a cap that is innocent. A
     * fold answers a smaller number over an evicted scope, which crosses a `having` in whichever
     * direction its operator faces. This is the third branch that needed the clause, and the two
     * that already had it are the no-match and join-note ones.
     *
     * Asked of EVERY quantifier rather than of `worst`, and the distinction is the point: `worst`
     * decides the verdict's attribution -- whose example gets named -- while this is a caveat about
     * cause. A rule carrying both a negation and a fold, where the negation happened to suppress
     * more, would otherwise get no clause at all even though its scope was capped and the fold is a
     * live candidate.
     */
    if (matches.quantifiers().stream()
        .anyMatch(each -> each.kind() == Quantifier.ACCUMULATE && each.suppressed() > 0)) {
      text.append(evictionNote(rule));
    }
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
      // A quantifier first when both applied, because it is the earlier gate: the tuples it
      // removed were never offered to the condition, so a condition-shaped verdict would describe
      // a filtering that did not happen to them.
      return Optional.of(matches.suppressedByQuantifier() > 0
          ? quantifierVerdict(rule, matches)
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
    /*
     * An accumulate's scope belongs here as well as in the hazard warning, and it is the one of the
     * four that cuts both ways. Evicting a negated or quantified type can only ever manufacture a
     * match, so its note belongs on the branches that found one; evicting a FOLDED type changes a
     * number, which can cost this rule a match as readily as hand it one -- and the scope is not a
     * positive pattern, so without naming it here a rule whose only reference to LineItem is a fold
     * gets no eviction clause at all on the branch this method exists for.
     */
    final List<CompiledPattern> patterned = new ArrayList<>(rule.patterns());
    rule.accumulates().forEach(accumulate -> patterned.add(accumulate.scope()));
    for (final CompiledPattern pattern : patterned) {
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
   * A warning for a rule that matched while the session was evicting a type §4.4 makes hazardous.
   *
   * <p><strong>This clause inverts the rule the note beside it follows, for three of the four
   * categories.</strong> The fourth -- a folded type -- inverts nothing and appears in both, because
   * eviction changes a number rather than an absence and a number can cross a {@code having} in
   * either direction. Anyone adding a fifth category should decide which of those three shapes it
   * has before deciding where its clause belongs.
   * {@link #evictionNote(CompiledRule)} is attached only where a rule did <em>not</em> match,
   * because a clause on every explanation of every capped session stops being read. A quantified
   * type is the opposite case: eviction there can only ever cost the rule a <em>suppression</em>,
   * so the danger is precisely when the rule <em>did</em> match. §4.4 is blunt about what that
   * means -- everywhere else eviction costs a firing, here it manufactures a false conclusion, and
   * the engine announces that a paid order is unpaid.
   *
   * <p>Every amendment calls its own case the sharpest, which is a sign that ranking them is not
   * useful: a negation over a half-evicted type may still find its witness, a universal over an
   * emptied scope is <em>vacuously</em> true, a fold over one is quietly short by whatever aged out,
   * and a conclusion that was evicted never returns because its justification still holds and its
   * rule is still refracted. The wording names the family rather than picking a winner.
   *
   * <p>The explainer cannot detect it, because it re-asks the same question of the same working
   * memory and is fooled identically. What it can do is put the count in front of the reader, which
   * is the part that changes what they do next.
   *
   * @param rule the rule being explained
   * @return a trailing warning naming each category that has lost facts, or empty text when none
   *     has
   */
  private String evictionHazardWarning(final CompiledRule rule) {
    final Map<String, Long> evicted = evictedByType();
    /*
     * Four faces of §4.4's one fact -- the engine cannot tell an evicted value from one that was
     * never there -- and they are kept apart because what each one does to a rule differs, and a
     * merged sentence said something false about the last. An earlier version was gated on the
     * first two alone, so exactly half of the hazard went unwarned; a later one covered all four
     * and told an author their match "may be a false conclusion" when what had actually happened
     * was that a perfectly good conclusion had been dropped and could not come back.
     */
    final Set<String> quantified = new LinkedHashSet<>();
    rule.negations().forEach(pattern -> quantified.add(pattern.factType()));
    rule.universals().forEach(pattern -> quantified.add(pattern.factType()));
    final Set<String> folded = new LinkedHashSet<>();
    rule.accumulates().forEach(accumulate -> folded.add(accumulate.scope().factType()));
    final Set<String> concluded = new LinkedHashSet<>();
    for (final ActionDefinition action : rule.actions()) {
      if (action instanceof InsertFact insert && insert.logical()) {
        concluded.add(insert.factType());
      }
    }
    final StringBuilder text = new StringBuilder();
    clause(text, evicted, quantified,
        "this rule quantifies over them, so an absence it asserts may be an artefact of the cap"
            + " and this match may be a false conclusion");
    clause(text, evicted, folded,
        "this rule folds them, so its answer is short by whatever aged out -- which can cost"
            + " this rule a match as readily as it can hand one over, depending on the 'having'");
    clause(text, evicted, concluded,
        "this rule concludes them, so a conclusion it drew may have been evicted and will not be"
            + " redrawn: its justification still holds and the rule is still refracted");
    return text.isEmpty() ? "" : text.append(" (§4.4)").toString();
  }

  /**
   * Appends one category's clause, when that category has lost anything.
   *
   * @param text the warning being built
   * @param evicted this session's per-type counts
   * @param factTypes the types this rule reads in one of §4.4's hazardous ways
   * @param because what that way costs, phrased for the end of the clause
   */
  private static void clause(final StringBuilder text, final Map<String, Long> evicted,
      final Set<String> factTypes, final String because) {
    final StringBuilder named = new StringBuilder();
    for (final String factType : factTypes) {
      final long count = evicted.getOrDefault(factType, 0L);
      if (count == 0L) {
        continue;
      }
      named.append(named.isEmpty() ? "" : ", ")
          .append(count).append(' ').append(factType).append(" fact(s) evicted");
    }
    if (named.isEmpty()) {
      return;
    }
    text.append(text.isEmpty() ? " — WARNING: " : "; ").append(named).append(" this session, and ")
        .append(because);
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
       * A QUANTIFIED type is the exact inverse and the four branches below DO carry a warning for
       * it; see evictionHazardWarning, which argues why. The two are not in conflict: eviction
       * of a type the rule needs can only cost it a match, so the note belongs where it found none,
       * while eviction of a type the rule quantifies over can only manufacture one, so the warning
       * belongs where it found some.
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
              + (matches.suppressedByQuantifier() > 0
                  ? " (at least " + matches.suppressedByQuantifier() + " combination(s) examined so"
                      + " far were suppressed by a quantifier the rule carries)"
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
          + evictionHazardWarning(rule));
    }
    final String examples = String.join(", ", fired)
        + (firedCount > fired.size() ? ", and " + (firedCount - fired.size()) + " more" : "");
    if (eligible == 0) {
      return Optional.of("matched, but refracted — " + total
          + " match(es), all already fired: " + examples + evictionHazardWarning(rule));
    }
    return Optional.of(total + " match(es): " + firedCount + " already fired (" + examples
        + "), " + eligible + " still eligible" + evictionHazardWarning(rule));
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
