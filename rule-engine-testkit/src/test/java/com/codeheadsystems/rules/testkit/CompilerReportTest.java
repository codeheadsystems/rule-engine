package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.report.Diagnostic;
import com.codeheadsystems.rules.report.UnindexedConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The compiler report (spec §7.4).
 *
 * <p>§9's Phase 5 criterion ends "...and gets a report naming every constraint that isn't"
 * index-eligible. These are the tests that make that a fact rather than a claim.
 */
class CompilerReportTest {

  private static CompilerReport reportOf(final RuleDefinition... rules) {
    return reportOf(CompilerOptions.defaults(), rules);
  }

  private static CompilerReport reportOf(final CompilerOptions options,
      final RuleDefinition... rules) {
    return RuleCompiler.compile(List.of(rules), options).report();
  }

  @Nested
  @DisplayName("identity")
  class Identity {

    @Test
    @DisplayName("carries the version of the rule set it describes, so logs can be matched up")
    void versionMatches() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(
          Rules.rule("r").when("o", "Order").then(actions -> actions.emit("e")).build()));

      assertThat(rules.report().ruleSetVersion()).isEqualTo(rules.version());
    }

    @Test
    @DisplayName("has no errors, because a rule set that compiled had none")
    void errorsAlwaysEmpty() {
      assertThat(reportOf(Rules.rule("r").when("o", "Order")
          .then(actions -> actions.emit("e")).build()).errors()).isEmpty();
    }

    @Test
    @DisplayName("has no CEL costs, because the escape hatch is not implemented in v1")
    void noCelCostsYet() {
      assertThat(reportOf(Rules.rule("r").when("o", "Order")
          .then(actions -> actions.emit("e")).build()).celCosts()).isEmpty();
    }
  }

  @Nested
  @DisplayName("unindexed constraints")
  class Unindexed {

    @Test
    @DisplayName("names an ne, with the rule, alias and field that carry it")
    void reportsNe() {
      final CompilerReport report = reportOf(Rules.rule("anti-match")
          .when("o", "Order", pattern -> pattern.ne("status", "CLOSED"))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.unindexed()).singleElement().satisfies(constraint -> {
        assertThat(constraint.ruleId()).isEqualTo("anti-match");
        assertThat(constraint.alias()).isEqualTo("o");
        assertThat(constraint.field()).isEqualTo("status");
        assertThat(constraint.reason()).isEqualTo(UnindexedConstraint.Reason.NE);
      });
    }

    @Test
    @DisplayName("names notIn and matches with their own reasons")
    void reportsNotInAndMatches() {
      final CompilerReport report = reportOf(Rules.rule("several")
          .when("o", "Order", pattern -> pattern
              .op("region", Operator.NOT_IN, Facts.array("XX"))
              .matches("email", "^[a-z]+@example[.]com$"))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.unindexed()).extracting(UnindexedConstraint::reason)
          .containsExactly(UnindexedConstraint.Reason.NOT_IN,
              UnindexedConstraint.Reason.MATCHES);
    }

    @Test
    @DisplayName("names a join no index can probe as a residual condition, the costly kind")
    void reportsResidualJoin() {
      final CompilerReport report = reportOf(Rules.rule("residual")
          .when("c", "Customer")
          .when("o", "Order", pattern -> pattern.ref("region", "c.region", Operator.NE))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.unindexed()).singleElement()
          .extracting(UnindexedConstraint::reason)
          .isEqualTo(UnindexedConstraint.Reason.RESIDUAL_JOIN_CONDITION);
    }

    @Test
    @DisplayName("says nothing about an equality join, which §3.3 hash-indexes from both ends")
    void equalityJoinIsIndexed() {
      assertThat(reportOf(Rules.rule("indexed")
          .when("o", "Order")
          .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
          .then(actions -> actions.emit("e")).build()).unindexed()).isEmpty();
    }

    @Test
    @DisplayName("says nothing about an ordered join, which the sorted index serves")
    void orderedJoinIsIndexed() {
      assertThat(reportOf(Rules.rule("ordered")
          .when("c", "Customer")
          .when("o", "Order", pattern -> pattern.ref("total", "c.ceiling", Operator.GT))
          .then(actions -> actions.emit("e")).build()).unindexed()).isEmpty();
    }

    @Test
    @DisplayName("says nothing about a presence test, which has no indexed alternative to compare")
    void presenceTestsAreNotNoise() {
      assertThat(reportOf(Rules.rule("presence")
          .when("o", "Order", pattern -> pattern.hasField("coupon", true).isNull("closedAt", true))
          .then(actions -> actions.emit("e")).build()).unindexed()).isEmpty();
    }

    @Test
    @DisplayName("says nothing about a single-fact eq or range: there is no index for one to lose")
    void singleFactConstraintsAreNotIndexFailures() {
      assertThat(reportOf(Rules.rule("plain")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 10_000))
          .then(actions -> actions.emit("e")).build()).unindexed()).isEmpty();
    }
  }

  @Nested
  @DisplayName("sharing statistics")
  class Sharing {

    @Test
    @DisplayName("counts one alpha node where two rules express the same constraint")
    void sharingIsCounted() {
      final CompilerReport report = reportOf(
          Rules.rule("first")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("second")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .then(actions -> actions.emit("b")).build());

      assertThat(report.sharing().ruleCount()).isEqualTo(2);
      assertThat(report.sharing().alphaTestOccurrences()).isEqualTo(2);
      assertThat(report.sharing().distinctAlphaNodes()).isEqualTo(1);
      assertThat(report.sharing().alphaSharingRatio()).isEqualTo(2.0);
      assertThat(report.sharing().patternNodes()).isEqualTo(2);
    }

    @Test
    @DisplayName("reports a ratio of 1.0 when nothing was shared")
    void noSharing() {
      final CompilerReport report = reportOf(
          Rules.rule("first")
              .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("second")
              .when("o", "Order", pattern -> pattern.eq("status", "SHIPPED"))
              .then(actions -> actions.emit("b")).build());

      assertThat(report.sharing().alphaSharingRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("counts join edges, and reports 0 join nodes because v1 has no beta network")
    void joinCounts() {
      final CompilerReport report = reportOf(Rules.rule("joined")
          .when("o", "Order")
          .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.sharing().joinEdges()).isEqualTo(1);
      assertThat(report.sharing().joinNodes())
          .as("§11.5 chose TREAT for v1; there is no JoinNode to count until Phase 3")
          .isZero();
    }
  }

  @Nested
  @DisplayName("warnings")
  class Warnings {

    @Test
    @DisplayName("flags a tested path that contains a deeper one, per §3.4.2's subtree cost")
    void shallowTestedPath() {
      final CompilerReport report = reportOf(
          Rules.rule("whole-subtree")
              .when("o", "Order", pattern ->
                  pattern.op("customer", Operator.EQ, Facts.json("{\"id\": 7}")))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("one-scalar")
              .when("o", "Order", pattern -> pattern.eq("customer.id", 7))
              .then(actions -> actions.emit("b")).build());

      assertThat(report.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.ruleId()).isEqualTo("whole-subtree");
        assertThat(warning.code()).isEqualTo(CompilerReport.SHALLOW_TESTED_PATH);
        assertThat(warning.fieldPath()).contains("/customer");
      });
    }

    @Test
    @DisplayName("orders warnings deterministically, not by a salted set's iteration order")
    void warningOrderIsStable() {
      final CompilerReport report = reportOf(
          Rules.rule("two-subtrees")
              .when("o", "Order", pattern -> pattern
                  .op("shipping", Operator.EQ, Facts.json("{\"zip\": \"1\"}"))
                  .op("customer", Operator.EQ, Facts.json("{\"id\": 7}")))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("deeper")
              .when("o", "Order", pattern -> pattern
                  .eq("customer.id", 7).eq("shipping.zip", "1"))
              .then(actions -> actions.emit("b")).build());

      /*
       * The rule declares 'shipping' before 'customer'. CompiledRule.testedPaths() freezes its
       * sets with Set.copyOf, whose iteration order carries a per-JVM salt, so the only stable
       * answer is a sorted one -- and a report §7.4 wants asserted on in CI has to be stable.
       */
      assertThat(report.warnings()).extracting(Diagnostic::fieldPath)
          .containsExactly(java.util.Optional.of("/customer"),
              java.util.Optional.of("/shipping"));
    }

    @Test
    @DisplayName("flags a constraint on the whole payload, which is the worst case of all")
    void rootTestedPath() {
      final CompilerReport report = reportOf(
          Rules.rule("whole-payload")
              .when("o", "Order", pattern ->
                  pattern.op("", Operator.EQ, Facts.json("{\"id\": 7}")))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("one-scalar")
              .when("o", "Order", pattern -> pattern.eq("id", 7))
              .then(actions -> actions.emit("b")).build());

      assertThat(report.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.ruleId()).isEqualTo("whole-payload");
        assertThat(warning.message()).contains("(the whole payload)");
      });
    }

    @Test
    @DisplayName("flags a range whose lower bound sits above its upper")
    void invertedRange() {
      final CompilerReport report = reportOf(Rules.rule("inverted")
          .when("o", "Order", pattern -> pattern.between("total", 500, 100))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.warnings()).singleElement().satisfies(warning -> {
        assertThat(warning.code()).isEqualTo(CompilerReport.IMPOSSIBLE_RANGE);
        assertThat(warning.ruleId()).isEqualTo("inverted");
        assertThat(warning.fieldPath()).contains("total");
        assertThat(warning.message()).contains("500").contains("100");
      });
    }

    @Test
    @DisplayName("flags equal bounds when either end is exclusive")
    void degenerateRange() {
      final CompilerReport report = reportOf(Rules.rule("degenerate")
          .when("o", "Order", pattern -> pattern.constraint(new RangeConstraint("total",
              Optional.of(IntNode.valueOf(100)), true,
              Optional.of(IntNode.valueOf(100)), false)))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.warnings()).singleElement()
          .extracting(Diagnostic::code).isEqualTo(CompilerReport.IMPOSSIBLE_RANGE);
    }

    @Test
    @DisplayName("accepts equal bounds when both ends are inclusive, which matches exactly one value")
    void closedPointRange() {
      final CompilerReport report = reportOf(Rules.rule("point")
          .when("o", "Order", pattern -> pattern.between("total", 100, 100))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("flags the split form too, which §6.2.1 documents as equivalent")
    void splitImpossibleRange() {
      // `{ gt: 500, lt: 100 }` compiles to two one-sided ranges that are individually fine and
      // jointly unsatisfiable. Constraints in a pattern are AND-ed, so this matches nothing.
      final CompilerReport report = reportOf(Rules.rule("split")
          .when("o", "Order", pattern -> pattern.gt("total", 500).lt("total", 100))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.warnings()).singleElement()
          .extracting(Diagnostic::code).isEqualTo(CompilerReport.IMPOSSIBLE_RANGE);
    }

    @Test
    @DisplayName("says nothing about a satisfiable split range")
    void satisfiableSplitRange() {
      assertThat(reportOf(Rules.rule("fine")
          .when("o", "Order", pattern -> pattern.gt("total", 100).lt("total", 500))
          .then(actions -> actions.emit("e")).build()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("does not cross fields, since a bound on one says nothing about another")
    void doesNotCrossFields() {
      assertThat(reportOf(Rules.rule("two-fields")
          .when("o", "Order", pattern -> pattern.gt("total", 500).lt("quantity", 100))
          .then(actions -> actions.emit("e")).build()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("says nothing about a one-sided range, which bounds nothing to contradict")
    void oneSidedRange() {
      assertThat(reportOf(Rules.rule("one-sided")
          .when("o", "Order", pattern -> pattern.gt("total", 10_000))
          .then(actions -> actions.emit("e")).build()).warnings()).isEmpty();
    }

    @Test
    @DisplayName("says nothing when the bounds cannot be ordered, which is a different problem")
    void incomparableBounds() {
      final CompilerReport report = reportOf(Rules.rule("incomparable")
          .when("o", "Order", pattern -> pattern.constraint(new RangeConstraint("total",
              Optional.of(TextNode.valueOf("abc")), true,
              Optional.of(IntNode.valueOf(100)), true)))
          .then(actions -> actions.emit("e")).build());

      assertThat(report.warnings()).isEmpty();
    }

    @Test
    @DisplayName("stays quiet when no path contains another")
    void noWarningWhenPathsAreDisjoint() {
      assertThat(reportOf(Rules.rule("scalars")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 1))
          .then(actions -> actions.emit("e")).build()).warnings()).isEmpty();
    }
  }

  @Nested
  @DisplayName("unreachable rules")
  class Unreachable {

    @Test
    @DisplayName("are not guessed at when the caller did not declare its fact types")
    void emptyWithoutDeclaration() {
      assertThat(reportOf(Rules.rule("on-a-type-nobody-inserts")
          .when("g", "Ghost").then(actions -> actions.emit("e")).build())
          .unreachableRules()).isEmpty();
    }

    @Test
    @DisplayName("name a rule patterning a type the host never inserts")
    void namedWhenDeclared() {
      final CompilerReport report = reportOf(
          CompilerOptions.builder().declaredFactTypes(Set.of("Order")).build(),
          Rules.rule("reachable").when("o", "Order")
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("ghost").when("g", "Ghost")
              .then(actions -> actions.emit("b")).build());

      assertThat(report.unreachableRules()).containsExactly("ghost");
    }

    @Test
    @DisplayName("include a rule whose only producer is itself unreachable, which needs a fixpoint")
    void transitivelyUnreachable() {
      final CompilerReport report = reportOf(
          CompilerOptions.builder().declaredFactTypes(Set.of("Order")).build(),
          Rules.rule("ghost-derives").when("g", "Ghost")
              .then(actions -> actions.insertFact("Derived", "k", 1)).build(),
          Rules.rule("consumes-derived").when("d", "Derived")
              .then(actions -> actions.emit("b")).build());

      // Nothing can ever insert Derived, because its only producer can never fire. Reporting only
      // ghost-derives would be an under-report, and the guide tells people to gate CI on this list.
      assertThat(report.unreachableRules())
          .containsExactly("ghost-derives", "consumes-derived");
    }

    @Test
    @DisplayName("exclude a type the rule set derives for itself through insertFact")
    void derivedTypesAreReachable() {
      final CompilerReport report = reportOf(
          CompilerOptions.builder().declaredFactTypes(Set.of("Order")).build(),
          Rules.rule("derives").when("o", "Order")
              .then(actions -> actions.insertFact("RiskSignal", "severity", "HIGH")).build(),
          Rules.rule("consumes-derived").when("s", "RiskSignal")
              .then(actions -> actions.emit("b")).build());

      assertThat(report.unreachableRules()).isEmpty();
    }
  }

  @Nested
  @DisplayName("rendering")
  class Rendering {

    @Test
    @DisplayName("groups warnings by rule, the order an author reads their file in")
    void warningsGroupedByRule() {
      final CompilerReport report = reportOf(
          Rules.rule("first")
              .when("o", "Order", pattern -> pattern.between("total", 500, 100))
              .then(actions -> actions.emit("a")).build(),
          Rules.rule("second")
              .when("o", "Order", pattern -> pattern.between("total", 900, 800))
              .then(actions -> actions.emit("b")).build());

      assertThat(report.warnings()).extracting(Diagnostic::ruleId)
          .containsExactly("first", "second");
    }

    @Test
    @DisplayName("summarises the rule set for a build log")
    void describe() {
      final String text = reportOf(Rules.rule("anti-match")
          .when("o", "Order", pattern -> pattern.ne("status", "CLOSED"))
          .then(actions -> actions.emit("e")).build()).describe();

      assertThat(text)
          .contains("1 rules")
          .contains("unindexed: anti-match: o.status (NE)");
    }

    @Test
    @DisplayName("keeps its lists unmodifiable, since the report is frozen into the rule set")
    void immutable() {
      final CompilerReport report = reportOf(Rules.rule("r").when("o", "Order")
          .then(actions -> actions.emit("e")).build());

      assertThat(report.unindexed()).isUnmodifiable();
      assertThat(report.warnings()).isUnmodifiable();
      assertThat(report.unreachableRules()).isUnmodifiable();
    }
  }

  @Test
  @DisplayName("a warning names the field it concerns, which is what makes it actionable")
  void warningCarriesItsFieldPath() {
    final CompilerReport report = reportOf(
        Rules.rule("whole-subtree")
            .when("o", "Order", pattern ->
                pattern.op("customer", Operator.EQ, Facts.json("{\"id\": 7}")))
            .then(actions -> actions.emit("a")).build(),
        Rules.rule("one-scalar")
            .when("o", "Order", pattern -> pattern.eq("customer.id", 7))
            .then(actions -> actions.emit("b")).build());

    assertThat(report.warnings()).singleElement()
        .extracting(Diagnostic::fieldPath, Diagnostic::ruleId)
        .containsExactly(java.util.Optional.of("/customer"), "whole-subtree");
  }
}
