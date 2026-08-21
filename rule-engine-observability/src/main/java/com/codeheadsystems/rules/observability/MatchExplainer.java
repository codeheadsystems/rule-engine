package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.expr.ExpressionBindings;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.ExpressionTest;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

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
    return new Explanation(ruleId, annotated, verdict(rule, annotated, matches),
        matches.complete());
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
    return new Explanation(ruleId, annotated, verdict(rule, annotated, matches),
        matches.complete());
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
       * `matches.found()` excludes tuples a §6.4 condition removed, so an empty match set no longer
       * implies the joins failed -- and blaming a join that in fact holds sends the author to
       * inspect correct code. Same lesson as the budget guard beside it: only claim "nothing
       * joined" when nothing else can account for the emptiness.
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
    final int[] outcomes = {0, 0};
    extend(rule, survivorsByAlias, payloads, 0, new long[rule.patterns().size()], found, budget,
        outcomes);
    return new Matches(found, budget[0] > 0 && found.size() < MATCH_LIMIT,
        outcomes[0], outcomes[1]);
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
   * @param outcomes counts, in place, complete tuples a §6.4 condition rejected [0] and ones
   *     whose condition could not be evaluated at all [1]
   */
  private void extend(final CompiledRule rule, final Map<String, List<Long>> survivorsByAlias,
      final Map<Long, JsonNode> payloads, final int position, final long[] bound,
      final List<long[]> found, final int[] budget, final int[] outcomes) {
    if (found.size() >= MATCH_LIMIT || budget[0] <= 0) {
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
      switch (conditionOutcome(rule, bound, payloads)) {
        case HELD -> found.add(bound.clone());
        case REJECTED -> outcomes[0]++;
        case FAILED -> outcomes[1]++;
      }
      return;
    }
    final CompiledPattern pattern = rule.patterns().get(position);
    for (final long candidate : survivorsByAlias.getOrDefault(pattern.alias(), List.of())) {
      if (--budget[0] <= 0) {
        return;
      }
      if (pattern.conflictsWith(bound, candidate)
          || !joinsHold(rule, pattern, candidate, bound, payloads)) {
        continue;
      }
      bound[position] = candidate;
      extend(rule, survivorsByAlias, payloads, position + 1, bound, found, budget, outcomes);
      if (found.size() >= MATCH_LIMIT || budget[0] <= 0) {
        return;
      }
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
   */
  private record Matches(List<long[]> found, boolean complete, int rejectedByCondition,
      int failedToEvaluate) {

    /**
     * Whether a §6.4 condition is why nothing matched.
     *
     * @return true when conditions removed every complete tuple
     */
    private boolean removedEverything() {
      /*
       * `complete` is not optional here. A search stopped by its budget has examined some prefix of
       * the combinations, so "a condition rejected each of them" would assert an exhaustiveness the
       * walk never reached -- and the count would be the budget rather than a count, which is
       * exactly the defect commit 10588b2 fixed by hand ("stop it reporting a budget as a count").
       * Falling through leaves refractionVerdict's budget wording, which is already careful.
       */
      return complete && found.isEmpty() && rejectedByCondition + failedToEvaluate > 0;
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
        return Optional.of("no " + result.factType() + " fact exists");
      }
    }
    for (final PatternResult result : results) {
      if (result.survivors().isEmpty()) {
        return Optional.of(result.considered() + " " + result.factType()
            + "(s) considered; none matched " + result.alias()
            + result.firstFailure().map(failure -> " — " + failure.describe()).orElse(""));
      }
    }
    /*
     * Before the join notes, not after. A condition that removed every tuple has already suppressed
     * those notes above, but ordering it first says plainly that this branch is the more specific
     * answer: the joins did hold, and something after them said no.
     */
    if (matches.removedEverything()) {
      return Optional.of(conditionVerdict(matches));
    }
    for (final PatternResult result : results) {
      if (result.joinNote().isPresent()) {
        return result.joinNote();
      }
    }
    return refractionVerdict(rule, matches);
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
      return Optional.of(matches.complete()
          ? "every pattern matched individually, but no combination of them satisfies the rule's"
              + " cross-fact constraints"
          : "no match found before the search budget ran out; there may be one");
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
      return Optional.of(total + " match(es); all eligible, none has fired yet");
    }
    final String examples = String.join(", ", fired)
        + (firedCount > fired.size() ? ", and " + (firedCount - fired.size()) + " more" : "");
    if (eligible == 0) {
      return Optional.of("matched, but refracted — " + total
          + " match(es), all already fired: " + examples);
    }
    return Optional.of(total + " match(es): " + firedCount + " already fired (" + examples
        + "), " + eligible + " still eligible");
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
