package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rule.AlphaTest;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.JoinTest;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.fasterxml.jackson.databind.JsonNode;
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

  /** How many real matches to enumerate before stopping. Enough to explain, bounded enough to end. */
  private static final int MATCH_LIMIT = 1_000;

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
    final List<long[]> matches = matches(rule, survivorsByAlias);
    final List<PatternResult> annotated = withJoinNotes(rule, results, matches);
    return new Explanation(ruleId, annotated, verdict(rule, annotated, matches));
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
    final List<long[]> matches = matches(rule, survivorsByAlias);
    final List<PatternResult> annotated = withJoinNotes(rule, results, matches);
    return new Explanation(ruleId, annotated, verdict(rule, annotated, matches));
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
      final List<PatternResult> results, final List<long[]> matches) {
    final List<PatternResult> annotated = new ArrayList<>(results.size());
    for (int position = 0; position < rule.patterns().size(); position++) {
      final CompiledPattern pattern = rule.patterns().get(position);
      final PatternResult result = results.get(position);
      final boolean joined = pattern.joinTests().isEmpty() || result.survivors().isEmpty();
      annotated.add(joined || !matches.isEmpty()
          ? result
          : new PatternResult(result.alias(), result.factType(), result.considered(),
              result.survivors(), result.firstFailure(),
              Optional.of(result.survivors().size() + " survivor(s), but no combination of them "
                  + "satisfies " + describeJoins(rule, pattern)),
              result.note()));
    }
    return annotated;
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
   * inequality. That is the point: §7.2 says this diagnostic should re-evaluate rather than ask the
   * network, because the network is built to skip work without recording why it skipped it.
   *
   * <p>Bounded, because "why did nothing fire" is a question people ask about large sessions and an
   * unbounded cross product would hang the thing that was supposed to help. Hitting the bound is
   * reported rather than hidden.
   *
   * @param rule the rule
   * @param survivorsByAlias each pattern's surviving handles
   * @return up to {@link #MATCH_LIMIT} real matches
   */
  private List<long[]> matches(final CompiledRule rule,
      final Map<String, List<Long>> survivorsByAlias) {
    final List<long[]> found = new ArrayList<>();
    extend(rule, survivorsByAlias, 0, new long[rule.patterns().size()], found);
    return found;
  }

  /**
   * Depth-first extension over the surviving candidates.
   *
   * @param rule the rule
   * @param survivorsByAlias each pattern's surviving handles
   * @param position the pattern to bind next
   * @param bound the handles bound so far
   * @param found the matches collected so far
   */
  private void extend(final CompiledRule rule, final Map<String, List<Long>> survivorsByAlias,
      final int position, final long[] bound, final List<long[]> found) {
    if (found.size() >= MATCH_LIMIT) {
      return;
    }
    if (position == rule.patterns().size()) {
      found.add(bound.clone());
      return;
    }
    final CompiledPattern pattern = rule.patterns().get(position);
    for (final long candidate : survivorsByAlias.getOrDefault(pattern.alias(), List.of())) {
      if (pattern.conflictsWith(bound, candidate) || !joinsHold(rule, pattern, candidate, bound)) {
        continue;
      }
      bound[position] = candidate;
      extend(rule, survivorsByAlias, position + 1, bound, found);
      if (found.size() >= MATCH_LIMIT) {
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
   * @return whether the joins hold
   */
  private boolean joinsHold(final CompiledRule rule, final CompiledPattern pattern,
      final long candidate, final long[] bound) {
    for (final JoinTest join : pattern.joinTests()) {
      if (!holds(join, candidate, bound[join.otherIndex()])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether one join holds between two specific facts.
   *
   * @param join the join test
   * @param mine the handle on the constraint-bearing side
   * @param theirs the handle on the referenced side
   * @return whether it holds, and false if either fact has since gone
   */
  private boolean holds(final JoinTest join, final long mine, final long theirs) {
    final Optional<JsonNode> left = session.get(new FactHandle(mine)).map(Fact::payload);
    final Optional<JsonNode> right = session.get(new FactHandle(theirs)).map(Fact::payload);
    return left.isPresent() && right.isPresent() && join.test(left.get(), right.get());
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
      final List<long[]> matches) {
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
  private Optional<String> refractionVerdict(final CompiledRule rule, final List<long[]> matches) {
    if (matches.isEmpty()) {
      return Optional.of("every pattern matched individually, but no combination of them satisfies"
          + " the rule's cross-fact constraints");
    }
    final List<String> fired = new ArrayList<>();
    int eligible = 0;
    for (final long[] match : matches) {
      final Optional<Long> recency = session.firedAt(new ActivationKey(rule.id(), match));
      if (recency.isPresent()) {
        fired.add(java.util.Arrays.toString(match) + " at recency " + recency.get());
      } else {
        eligible++;
      }
    }
    if (fired.isEmpty()) {
      return Optional.of(matches.size() + " match(es); all eligible, none has fired yet");
    }
    if (eligible == 0) {
      return Optional.of("matched, but refracted — already fired: " + String.join(", ", fired));
    }
    return Optional.of(matches.size() + " match(es): " + fired.size()
        + " already fired (" + String.join(", ", fired) + "), " + eligible + " still eligible");
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
