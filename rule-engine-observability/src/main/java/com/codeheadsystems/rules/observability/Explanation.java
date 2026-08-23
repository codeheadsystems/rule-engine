package com.codeheadsystems.rules.observability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Why a rule did or did not fire (spec §7.2).
 *
 * <p>"Why did rule R fire?" is answerable from the trace. "Why did rule R <em>not</em> fire?" is
 * much harder, because a non-firing is the absence of a record — there is nothing to look up. This
 * is the explicit diagnostic that answers it.
 *
 * @param ruleId the rule being explained
 * @param patterns one result per positive LHS pattern, in declaration order. A {@code NOT_EXISTS}
 *     pattern is not here, for the reason {@code CompiledRule.patterns()} does not carry one: it
 *     binds nothing and produces no candidates, so it has none of what this record reports
 * @param negations one result per {@code NOT_EXISTS} pattern, in declaration order; empty for the
 *     overwhelming majority of rules, which assert no absence
 * @param verdict a one-sentence answer, when one can be given
 * @param complete whether the search finished. When false it ran into a budget, so every count in
 *     this explanation is a lower bound rather than a total. Reported rather than hidden, because
 *     a diagnostic that states a wrong number confidently is worse than one that admits it stopped
 */
public record Explanation(String ruleId, List<PatternResult> patterns,
    List<NegationResult> negations, Optional<String> verdict, boolean complete) {

  /**
   * Canonical constructor. Defensively copies both result lists.
   *
   * @param ruleId the rule
   * @param patterns the per-pattern results
   * @param negations the per-negation results
   * @param verdict the summary
   * @param complete whether the search finished
   */
  public Explanation {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(verdict, "verdict");
    patterns = List.copyOf(patterns);
    negations = List.copyOf(negations);
  }

  /**
   * A rendering meant to be printed and read, not parsed.
   *
   * <p>The verdict leads, because it is the answer; the per-pattern detail follows, because it is
   * the evidence.
   *
   * @return the explanation as text
   */
  public String describe() {
    final StringBuilder text = new StringBuilder("rule ").append(ruleId).append(": ")
        .append(verdict.orElse("no verdict"))
        .append(System.lineSeparator());
    if (!complete) {
      text.append("  (search stopped at a budget; counts above are lower bounds)")
          .append(System.lineSeparator());
    }
    for (final PatternResult pattern : patterns) {
      text.append("  ").append(pattern.describe()).append(System.lineSeparator());
    }
    // After the patterns, because a negation is a question asked of a complete tuple: it is what
    // happened to the combinations the patterns above produced, not another way of producing them.
    for (final NegationResult negation : negations) {
      text.append("  ").append(negation.describe()).append(System.lineSeparator());
    }
    return text.toString();
  }
}
