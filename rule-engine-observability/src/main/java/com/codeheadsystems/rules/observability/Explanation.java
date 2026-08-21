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
 * @param patterns one result per LHS pattern, in declaration order
 * @param verdict a one-sentence answer, when one can be given
 */
public record Explanation(String ruleId, List<PatternResult> patterns, Optional<String> verdict) {

  /**
   * Canonical constructor. Defensively copies the pattern results.
   *
   * @param ruleId the rule
   * @param patterns the per-pattern results
   * @param verdict the summary
   */
  public Explanation {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(verdict, "verdict");
    patterns = List.copyOf(patterns);
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
    for (final PatternResult pattern : patterns) {
      text.append("  ").append(pattern.describe()).append(System.lineSeparator());
    }
    return text.toString();
  }
}
