package com.codeheadsystems.rules.report;

import java.util.Objects;

/**
 * One CEL expression's static cost estimate against the configured budget (spec §6.4, §7.4).
 *
 * <p>Never produced in v1 -- the escape hatch arrives with the {@code -cel} module -- and defined
 * now because §7.4 defines it and because a report field added later would churn every caller that
 * had learned to read the record.
 *
 * <p>When it does arrive, note what §6.4 is careful about: a cost limit bounds a <em>single</em>
 * evaluation, not how many times the engine runs it. An unindexed condition against 100 000 facts
 * is 100 000 evaluations per cycle, each of them within budget.
 *
 * @param ruleId the rule carrying the expression
 * @param estimated what the static estimator made of it
 * @param budget the configured ceiling
 * @param overBudget whether the estimate exceeds the ceiling. Always false on a report you can
 *     reach, since an over-budget expression fails compilation; the field records which
 *     ceiling was in force rather than reporting a survivor
 */
public record CelCost(String ruleId, long estimated, long budget, boolean overBudget) {

  /**
   * Canonical constructor.
   *
   * @param ruleId the owning rule
   * @param estimated the estimated cost
   * @param budget the configured ceiling
   * @param overBudget whether the estimate exceeds it
   */
  public CelCost {
    Objects.requireNonNull(ruleId, "ruleId");
  }
}
