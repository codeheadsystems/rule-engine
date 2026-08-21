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
    final List<PatternResult> annotated = withJoinNotes(rule, results, survivorsByAlias);
    return new Explanation(ruleId, annotated, verdict(rule, annotated, survivorsByAlias));
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
    final List<PatternResult> annotated = withJoinNotes(rule, results, survivorsByAlias);
    return new Explanation(ruleId, annotated, verdict(rule, annotated, survivorsByAlias));
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
        Optional.empty());
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
      return new PatternResult(pattern.alias(), pattern.factType(), 0, List.of(), Optional.empty(),
          Optional.of("fact #" + pinned.id() + " is not in working memory"));
    }
    if (!fact.get().type().equals(pattern.factType())) {
      return new PatternResult(pattern.alias(), pattern.factType(), 1, List.of(), Optional.empty(),
          Optional.of("fact #" + pinned.id() + " is a " + fact.get().type()
              + ", but " + pattern.alias() + " matches " + pattern.factType()));
    }
    final Optional<AlphaTest> failed = firstFailing(pattern, fact.get());
    return new PatternResult(pattern.alias(), pattern.factType(), 1,
        failed.isPresent() ? List.of() : List.of(pinned.id()),
        failed.map(test -> new ConstraintFailure(test.constraint(), pinned.id(),
            test.accessor().get(fact.get().payload()), 1)),
        Optional.empty());
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
   * <p>A pattern can have survivors and still contribute nothing, because none of them joins to
   * anything. That is a distinct failure from "no fact passed the constraints", and it is the one
   * an author is least likely to work out for themselves — everything they can see looks right.
   *
   * @param rule the rule
   * @param results the per-pattern results so far
   * @param survivorsByAlias each pattern's survivors
   * @return the results, with join notes filled in
   */
  private List<PatternResult> withJoinNotes(final CompiledRule rule,
      final List<PatternResult> results, final Map<String, List<Long>> survivorsByAlias) {
    final List<PatternResult> annotated = new ArrayList<>(results.size());
    for (int position = 0; position < rule.patterns().size(); position++) {
      final CompiledPattern pattern = rule.patterns().get(position);
      final PatternResult result = results.get(position);
      annotated.add(pattern.joinTests().isEmpty() || result.survivors().isEmpty()
          ? result
          : new PatternResult(result.alias(), result.factType(), result.considered(),
              result.survivors(), result.firstFailure(),
              joinNote(rule, pattern, result, survivorsByAlias)));
    }
    return annotated;
  }

  /**
   * Describes what a pattern's joins did to its survivors.
   *
   * @param rule the rule
   * @param pattern the pattern
   * @param result its result so far
   * @param survivorsByAlias each pattern's survivors
   * @return the note, or empty when at least one pairing joined
   */
  private Optional<String> joinNote(final CompiledRule rule, final CompiledPattern pattern,
      final PatternResult result, final Map<String, List<Long>> survivorsByAlias) {
    for (final JoinTest join : pattern.joinTests()) {
      final CompiledPattern other = rule.patterns().get(join.otherIndex());
      final List<Long> otherSurvivors = survivorsByAlias.getOrDefault(other.alias(), List.of());
      final boolean anyPairJoins = result.survivors().stream().anyMatch(mine ->
          otherSurvivors.stream().anyMatch(theirs -> holds(join, mine, theirs)));
      if (!anyPairJoins) {
        return Optional.of(result.survivors().size() + " survivor(s), none joined to any "
            + other.alias() + " on " + join.source().field()
            + " " + join.source().op() + " " + other.alias() + "." + join.source().otherField());
      }
    }
    return Optional.empty();
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
   * @param survivorsByAlias each pattern's survivors
   * @return the verdict
   */
  private Optional<String> verdict(final CompiledRule rule, final List<PatternResult> results,
      final Map<String, List<Long>> survivorsByAlias) {
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
        return Optional.of(result.joinNote().get());
      }
    }
    return refractionVerdict(rule, survivorsByAlias);
  }

  /**
   * The verdict for a rule whose patterns all matched: it probably already fired.
   *
   * <p>§7.2 calls this "the one nobody guesses". A rule that "stopped working" has usually already
   * fired on those exact facts, and saying so — with the recency it fired at — is the difference
   * between an explanation and a list of constraints the author can see are satisfied.
   *
   * @param rule the rule
   * @param survivorsByAlias each pattern's survivors
   * @return the verdict
   */
  private Optional<String> refractionVerdict(final CompiledRule rule,
      final Map<String, List<Long>> survivorsByAlias) {
    final long[] firstMatch = new long[rule.patterns().size()];
    for (int position = 0; position < rule.patterns().size(); position++) {
      final List<Long> survivors =
          survivorsByAlias.getOrDefault(rule.patterns().get(position).alias(), List.of());
      if (survivors.isEmpty()) {
        return Optional.empty();
      }
      firstMatch[position] = survivors.getFirst();
    }
    return session.firedAt(new ActivationKey(rule.id(), firstMatch))
        .map(recency -> "matched, but refracted — already fired at recency " + recency)
        .or(() -> Optional.of("every pattern matched; the rule is eligible and has not fired yet"));
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
