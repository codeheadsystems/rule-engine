package com.codeheadsystems.rules.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.report.UnindexedConstraint;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The build-time gate on the rule file: what a service should assert about its own rules in CI.
 *
 * <p><strong>Copy this class.</strong> A rule set is source code and deserves the same treatment --
 * it compiles or it does not, and the compiler has an opinion about it worth reading. Everything
 * here runs in milliseconds and needs no facts.
 */
class ExampleRulesTest {

  private static CompiledRuleSet rules;

  @BeforeAll
  static void compileOnce() {
    rules = OrderRules.compile();
  }

  @Test
  @DisplayName("the rule file compiles, which is the syntax check")
  void itCompiles() {
    /*
     * Worth its own test even though every other test needs it. RuleFiles.compile reports every
     * problem in every file at once, each with a file, line and column -- including the compiler's
     * own diagnostics, re-reported against the line that caused them. A failure here is a message
     * to read, not a stack trace to decode.
     */
    assertThatCode(OrderRules::compile).doesNotThrowAnyException();
    assertThat(rules.rules()).hasSize(7);
  }

  @Test
  @DisplayName("no rule is unreachable")
  void nothingIsUnreachable() {
    /*
     * This is what declaredFactTypes buys, and it is the single highest-value assertion on this
     * page. A rule patterning `Ordr` instead of `Order` compiles perfectly and fires never; nothing
     * at runtime can tell you the difference between that and a rule whose facts simply have not
     * arrived yet.
     */
    assertThat(rules.report().unreachableRules())
        .describedAs("a rule nothing can activate -- check the fact type spellings against Ingest")
        .isEmpty();
  }

  @Test
  @DisplayName("every unindexed constraint is one somebody decided about")
  void theUnindexedSetIsExactlyWhatWeAccepted() {
    /*
     * An allowlist rather than `isEmpty()`, because `isEmpty()` is not achievable and a test that
     * cannot pass gets deleted. Three of these are `ne`, which no index can serve; one is §6.4's
     * expression, which is never indexed by construction; and one is the temporal join, which
     * Operator.reversed() declines for on purpose -- reversing the relation to probe from the far
     * end would leave the `within` bound behind and silently widen the rule.
     *
     * The value is in the DIFF. Add a rule whose join falls back to a linear scan and this test
     * names it, on the build, at the moment it is written -- which is the only moment it is cheap.
     */
    final List<String> unindexed = rules.report().unindexed().stream()
        .map(constraint -> constraint.ruleId() + ": " + constraint.alias() + "."
            + constraint.field() + " (" + constraint.reason() + ")")
        .sorted()
        .toList();

    assertThat(unindexed).containsExactly(
        "bulk-order-discount: o.status (NE)",
        "expedite-eligible: o. (CEL_EXPRESSION)",
        "paid-within-the-hour: p.paidAtEpochMs (RESIDUAL_JOIN_CONDITION)",
        "ready-to-ship: o.status (NE)",
        "unpaid-order: o.status (NE)");
  }

  @Test
  @DisplayName("the one residual join is the temporal one, and it cannot be anything else")
  void residualJoinsAreOnlyTheTemporalOne() {
    /*
     * RESIDUAL_JOIN_CONDITION is the reason worth watching on its own: it means a join edge fell
     * back to re-testing every candidate instead of probing an index, which is the difference
     * between a hash lookup and a scan, invisible in review and obvious in production.
     */
    assertThat(rules.report().unindexed())
        .filteredOn(constraint ->
            constraint.reason() == UnindexedConstraint.Reason.RESIDUAL_JOIN_CONDITION)
        .extracting(UnindexedConstraint::ruleId)
        .containsExactly("paid-within-the-hour");
  }

  @Test
  @DisplayName("the compiler raises no warning nobody has read")
  void warningsAreAccountedFor() {
    /*
     * Empty today. Left as an assertion rather than deleted because the interesting warnings are
     * the ones this rule set has not earned yet -- NE_ON_OPTIONAL_PATH needs a registered schema
     * (§2.3), IMPOSSIBLE_RANGE needs a between nothing can satisfy -- and the day one appears is
     * the day somebody should read it.
     */
    assertThat(rules.report().warnings())
        .describedAs("read the warning, then either fix the rule or add it here with a reason")
        .isEmpty();
  }

  @Test
  @DisplayName("node sharing actually happened")
  void constraintsAreShared() {
    final CompilerReport report = rules.report();
    /*
     * Not a performance assertion -- a modelling one. Sharing above 1.0 means several rules
     * expressed the same constraint and the network evaluates it once per fact rather than once per
     * rule. Sharing at exactly 1.0 across a large rule set usually means the constraints are all
     * subtly different, which is worth knowing about.
     */
    assertThat(report.sharing().distinctAlphaNodes())
        .isLessThan(report.sharing().alphaTestOccurrences());
  }
}
