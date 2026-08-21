package com.codeheadsystems.rules.report;

import java.util.List;
import java.util.Objects;

/**
 * What the compiler knows and production would otherwise teach you slowly (spec §7.4).
 *
 * <p>Produced once by §6.5's pipeline, frozen into the {@code CompiledRuleSet}, and reachable from
 * it. Data rather than a printed string, for §7.4's reason: CI asserts on it and tooling renders
 * it. "Fail the build on errors; surface warnings in CI. A rule set is source code and deserves the
 * same treatment."
 *
 * <p>{@link #errors()} is always empty on a report you can reach. Compilation throws when a rule
 * set has errors, so a {@code CompiledRuleSet} that exists has none -- the component is kept
 * because §7.4 defines it and because a future compiler that returns a report instead of throwing
 * would need it. Read {@link #warnings()} instead.
 *
 * @param ruleSetVersion §5.6's content hash, so a report can be matched to the rule set it
 *     describes long after both have been written to a log
 * @param errors always empty; see the class note
 * @param warnings what compiled but is worth a second look
 * @param unindexed every constraint no index can serve, with the reason. Read
 *     {@link UnindexedConstraint}'s note on the two very different costs this covers
 * @param celCosts empty in v1; the escape hatch arrives with the {@code -cel} module (§6.4)
 * @param sharing how much node sharing happened, for checking §6.5's sublinearity claim against a
 *     real rule set
 * @param unreachableRules rules no fact can ever activate. Empty unless the caller declared the
 *     fact types, because a compiler cannot otherwise know what will be inserted
 */
public record CompilerReport(
    String ruleSetVersion,
    List<Diagnostic> errors,
    List<Diagnostic> warnings,
    List<UnindexedConstraint> unindexed,
    List<CelCost> celCosts,
    SharingStats sharing,
    List<String> unreachableRules) {

  /** The warning code for a tested path that contains another (§3.4.2). */
  public static final String SHALLOW_TESTED_PATH = "shallow-tested-path";

  /**
   * Canonical constructor. Defensively copies every list.
   *
   * @param ruleSetVersion the content hash
   * @param errors the errors
   * @param warnings the warnings
   * @param unindexed the unindexed constraints
   * @param celCosts the CEL cost estimates
   * @param sharing the sharing statistics
   * @param unreachableRules the unreachable rules
   */
  public CompilerReport {
    Objects.requireNonNull(ruleSetVersion, "ruleSetVersion");
    Objects.requireNonNull(sharing, "sharing");
    errors = List.copyOf(errors);
    warnings = List.copyOf(warnings);
    unindexed = List.copyOf(unindexed);
    celCosts = List.copyOf(celCosts);
    unreachableRules = List.copyOf(unreachableRules);
  }

  /**
   * Renders the report the way a build log wants it.
   *
   * @return a short, human-readable summary
   */
  public String describe() {
    final StringBuilder text = new StringBuilder(256);
    text.append("rule set ").append(ruleSetVersion).append(System.lineSeparator())
        .append("  ").append(sharing.ruleCount()).append(" rules, ")
        .append(sharing.distinctAlphaNodes()).append(" distinct alpha nodes from ")
        .append(sharing.alphaTestOccurrences()).append(" tests (sharing ")
        .append(String.format(java.util.Locale.ROOT, "%.2f", sharing.alphaSharingRatio()))
        .append("x), ").append(sharing.patternNodes()).append(" patterns, ")
        .append(sharing.joinEdges()).append(" join edges");
    unindexed.forEach(constraint -> text.append(System.lineSeparator())
        .append("  unindexed: ").append(constraint.ruleId()).append(": ")
        .append(constraint.alias()).append('.').append(constraint.field())
        .append(" (").append(constraint.reason()).append(')'));
    warnings.forEach(warning -> text.append(System.lineSeparator())
        .append("  warning: ").append(warning.ruleId()).append(": ").append(warning.message()));
    unreachableRules.forEach(rule -> text.append(System.lineSeparator())
        .append("  unreachable: ").append(rule));
    return text.toString();
  }
}
