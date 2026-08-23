package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.DefaultWorkingMemory;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireOptions;
import com.codeheadsystems.rules.session.RuleEngineLimitExceeded;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.core.JsonPointer;

/**
 * A §6.4 condition makes the paths it reads tested paths (spec §3.4.1).
 *
 * <p>Lives here rather than in {@code ReviewRegressionTest}, where defects found by review usually
 * go, because only a real condition reaches the code under test and {@code -testkit} does not
 * depend on {@code -cel}.
 *
 * <p><strong>The defect this pins.</strong> {@code RuleCompiler} recorded a tested path from field
 * constraints, ranges and joins, and not from conditions -- so a path read only by a
 * {@code condition:} was invisible to {@code TestedPaths}, and §3.4.1's gate concluded that an
 * update touching it changed nothing any rule tests. The rule did not re-evaluate, and an update
 * that made a condition newly true fired nothing while the equivalent retract-plus-insert fired.
 *
 * <p>It survived because every matcher committed the omission identically: the gate lives in
 * {@code DefaultWorkingMemory}, upstream of the matcher entirely, so {@code MatcherEquivalence} --
 * the harness this engine leans on hardest -- is structurally incapable of seeing it. That is worth
 * more than the bug: a differential suite proves the shapes agree, never that they agree on the
 * right answer.
 */
class ConditionTestedPathsTest {

  private static CompilerOptions withCel() {
    return CompilerOptions.builder().expressions(CelExpressions.create()).build();
  }

  /** One pattern whose only constraint is a condition reading {@code /total}. */
  private static CompiledRuleSet conditionOnly() {
    return RuleCompiler.compile(List.of(Rules.rule("big-order")
        .when("o", "Order", pattern -> pattern.constraint(
            new ExpressionConstraint("o.total > 1000", Set.of("o"))))
        .then(actions -> actions.emit("big", "id", Rules.ref("o.id")))
        .build()), withCel());
  }

  @ParameterizedTest
  @EnumSource(MatchingStrategy.class)
  @DisplayName("an update that makes a condition newly true fires, on every matcher")
  void updateThatSatisfiesAConditionFires(final MatchingStrategy strategy) {
    /*
     * The behaviour §9's Phase 1 exit criterion asks for: "an update that does change a tested path
     * is oracle-equivalent to retract+insert with the handle preserved". Asserted against the
     * retract-plus-insert equivalent in the same test rather than against a literal, so it cannot
     * drift from what the engine means by an update.
     */
    final CompiledRuleSet rules = conditionOnly();

    final int viaUpdate;
    try (RuleSession session = rules.newSession(
        SessionOptions.builder().matching(strategy).build())) {
      final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "total", 100));
      assertThat(session.fireAllRules().firedCount()).describedAs("100 is not over 1000").isZero();
      session.update(handle, Facts.obj("id", 1, "total", 5000));
      viaUpdate = session.fireAllRules().firedCount();
    }

    final int viaRetractInsert;
    try (RuleSession session = rules.newSession(
        SessionOptions.builder().matching(strategy).build())) {
      final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "total", 100));
      session.fireAllRules();
      session.retract(handle);
      session.insert("Order", Facts.obj("id", 1, "total", 5000));
      viaRetractInsert = session.fireAllRules().firedCount();
    }

    assertThat(viaUpdate)
        .describedAs("update on %s must match retract+insert", strategy)
        .isEqualTo(viaRetractInsert)
        .isEqualTo(1);
  }

  @Test
  @DisplayName("a condition in a RULE FILE makes its type tested, which it did not")
  void aDslConditionRecordsItsRoot() throws Exception {
    /*
     * The defect this whole class exists to prevent, arriving through the door the class did not
     * watch. Every case here built its RuleDefinition by hand and passed the referenced aliases
     * explicitly -- and roots were recorded from exactly that set, so the tests passed while the
     * DSL, which is how rules are meant to be authored, recorded nothing at all.
     *
     * ExpressionConstraint.referencedAliases is documented as optional and advisory, and RuleFiles
     * passes it empty on purpose: populating it would change §5.6's content hash and make the same
     * rule authored in YAML and in Java carry different versions. So a correctness property must
     * not rest on it, and no longer does.
     *
     * The `where` block deliberately constrains a DIFFERENT field from the one the condition reads.
     * With `total` constrained as well, an alpha test records /total and covers for the bug -- which
     * is the common shape, and why this went unnoticed.
     */
    final CompiledRuleSet rules = RuleFiles.compile(
        List.of(RuleSource.yaml("conditions.yaml", """
            apiVersion: rules.v1
            rules:
              - id: big-open-order
                when:
                  - fact: Order
                    as: o
                    where:
                      status: { eq: "OPEN" }
                    condition: "o.total > 1000"
                then: [{ action: emit, event: big }]
            """)),
        withCel());

    assertThat(rules.testedPaths().changedPaths("Order",
        Facts.obj("status", "OPEN", "total", 100),
        Facts.obj("status", "OPEN", "total", 5_000)))
        .describedAs("the condition reads /total, so a change to it is a change the rule tests")
        .isNotEmpty();

    try (RuleSession session = rules.newSession()) {
      final FactHandle order =
          session.insert("Order", Facts.obj("status", "OPEN", "total", 100));
      assertThat(session.fireAllRules().firedCount()).describedAs("100 is not over 1000").isZero();

      session.update(order, Facts.obj("status", "OPEN", "total", 5_000));

      assertThat(session.fireAllRules().firedCount())
          .describedAs("the condition is true now, and an update is not a silent no-op")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a condition spanning two aliases makes both their types tested")
  void bothSidesOfACrossFactConditionAreRecorded() {
    // The half a per-pattern fix would miss: the condition hangs off the Order pattern but reads
    // the Customer too, so an update to the Customer changes its truth.
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("over-limit")
        .when("c", "Customer")
        .when("o", "Order", pattern -> pattern.ref("customerId", "c.id")
            .constraint(new ExpressionConstraint("o.total > c.creditLimit", Set.of("c", "o"))))
        .then(actions -> actions.emit("over", "id", Rules.ref("o.id")))
        .build()), withCel());

    // NOT asserted as "Customer has a tested path": the join `ref("customerId", "c.id")` already
    // records /id on Customer with or without this fix, so that assertion passes either way. Only
    // a change to a field the join does NOT record discriminates -- with /id alone in the trie the
    // walk descends to id, finds 7 == 7, and reports nothing.
    assertThat(rules.testedPaths().changedPaths("Customer",
        Facts.obj("id", 7, "creditLimit", 9000), Facts.obj("id", 7, "creditLimit", 10)))
        .describedAs("lowering a limit only the condition reads must register as a change")
        .isNotEmpty();

    // And the behavioural half, which is the point: the cross-alias case is exactly what a
    // per-pattern fix would break, so it is checked end to end rather than at the compiler surface.
    try (RuleSession session = rules.newSession()) {
      final FactHandle customer =
          session.insert("Customer", Facts.obj("id", 7, "creditLimit", 9000));
      session.insert("Order", Facts.obj("id", 1, "customerId", 7, "total", 5000));
      assertThat(session.fireAllRules().firedCount())
          .describedAs("5000 is under a 9000 limit").isZero();

      session.update(customer, Facts.obj("id", 7, "creditLimit", 10));

      assertThat(session.fireAllRules().firedCount())
          .describedAs("dropping the OTHER fact's limit makes the condition true")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("an unchanged payload propagates nothing, measured on the counter")
  void identicalPayloadsStillSkip() {
    /*
     * §9's Phase 1 criterion insists this be "asserted on a counter, not inferred", because "no
     * propagation happened" is trivially satisfied by an implementation that never propagates.
     * skippedUpdateCount is that counter, and it is the one the root path could plausibly have
     * broken -- re-sending an identical record is the common case in the streaming feeds this
     * matters for.
     *
     * Two distinct short-circuits are in play and only the second is asserted below on the trie:
     * DefaultWorkingMemory compares payloads before consulting tested paths at all, and PathTrie's
     * walk returns early on an equal subtree. The counter covers the first, which is the one
     * §3.4.2 calls the fast-path guard.
     */
    final CompiledRuleSet rules = conditionOnly();

    try (RuleSession session = rules.newSession()) {
      final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "total", 5000));
      session.fireAllRules();
      session.update(handle, Facts.obj("id", 1, "total", 5000));

      final DefaultWorkingMemory memory = (DefaultWorkingMemory) session.workingMemory();
      assertThat(memory.skippedUpdateCount())
          .describedAs("an identical payload must still skip, root path or not").isEqualTo(1);
      assertThat(memory.propagatedUpdateCount()).isZero();
      assertThat(session.fireAllRules().firedCount())
          .describedAs("and the rule must not re-fire on a no-op update").isZero();
    }

    assertThat(rules.testedPaths().changedPaths("Order",
        Facts.obj("id", 1, "total", 5000), Facts.obj("id", 1, "total", 5000)))
        .describedAs("the trie's own early return, a different short-circuit from the counter's")
        .isEmpty();
  }

  @Test
  @DisplayName("a real update to an unread field re-fires the rule, which is the cost of the root")
  void anyUpdateReFiresAConditionRule() {
    /*
     * The user-visible price of recording the root, pinned so it is a decision rather than a
     * surprise. /note is read by nothing at all, and the condition's truth is unchanged -- but the
     * root is a tested path, so §3.4.1 step 5 un-refracts the rule and it fires again.
     *
     * §6.4's amendment states this, and the sharper form of it: a rule whose RHS mutates a fact it
     * binds goes from firing once to reaching maxCycles. noLoop is the documented answer.
     */
    final CompiledRuleSet rules = conditionOnly();
    try (RuleSession session = rules.newSession()) {
      final FactHandle handle =
          session.insert("Order", Facts.obj("id", 1, "total", 5000, "note", "a"));
      assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

      session.update(handle, Facts.obj("id", 1, "total", 5000, "note", "b"));

      assertThat(session.fireAllRules().firedCount())
          .describedAs("re-fires on a field no rule reads; this is the conservative choice's cost")
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("a self-mutating RHS needs noLoop once the rule carries a condition")
  void selfMutatingRhsNeedsNoLoop() {
    /*
     * The sharper half of the root's cost, and the only place the §6.4 amendment gives the author
     * an instruction rather than a warning: "Give such a rule noLoop; it restores single firing."
     * CLAUDE.md's rule is that doc examples are fixtures rather than prose, and an amendment that
     * tells someone what to do is prose making a promise. This is the promise.
     *
     * `setField` stamping a counter is an ordinary shape -- an attempt count, a processedAt. It
     * fires once without a condition, and with one it un-refracts itself on its own write and runs
     * until the cycle limit.
     */
    final FireOptions shortLeash = FireOptions.builder().maxCycles(50).build();

    assertThatThrownBy(() -> fireStamping(false, shortLeash))
        .describedAs("without noLoop the rule re-fires on the field it just wrote")
        .isInstanceOf(RuleEngineLimitExceeded.class);

    assertThat(fireStamping(true, shortLeash))
        .describedAs("noLoop is the documented answer and has to actually work")
        .isEqualTo(1);
  }

  /**
   * Fires a condition-carrying rule whose right-hand side mutates the fact it matched.
   *
   * @param noLoop whether to mark the rule {@code noLoop}
   * @param limits the fire limits
   * @return how many times it fired
   */
  private static int fireStamping(final boolean noLoop, final FireOptions limits) {
    final Rules.RuleBuilder builder = Rules.rule("stamp");
    if (noLoop) {
      builder.noLoop();
    }
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(builder
        .when("o", "Order", pattern -> pattern.gt("total", 1000)
            .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
        .then(actions -> actions.setField("o", "seen",
            new ExpressionValue("o.seen + 1", Set.of("o"))))
        .build()), withCel());

    try (RuleSession session = rules.newSession(
        SessionOptions.builder().limits(limits).build())) {
      session.insert("Order", Facts.obj("total", 5000, "seen", 0));
      return session.fireAllRules().firedCount();
    }
  }

  @Test
  @DisplayName("a root the author wrote still warns, even on a rule that also has a condition")
  void authoredRootStillWarnsAlongsideACondition() {
    /*
     * The suppression is by provenance, not by "this rule has a condition". An author who writes
     * `field: ""` gets advice they can act on -- constrain a deeper path -- and must keep getting
     * it even if the same rule reaches for CEL elsewhere. Only the root this compiler inserted,
     * which they cannot remove without deleting their condition, is suppressed.
     *
     * Without this the suppression would be a trade rather than exact, and the earlier version of
     * it was: the provenance set was populated but never read.
     */
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("both")
        .when("o", "Order", pattern -> pattern
            .op("", com.codeheadsystems.rules.rule.Operator.EQ, Facts.json("{\"id\": 7}"))
            .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
        .then(actions -> actions.emit("x"))
        .build(),
        Rules.rule("deeper")
            .when("o", "Order", pattern -> pattern.eq("id", 7))
            .then(actions -> actions.emit("y"))
            .build()), withCel());

    assertThat(rules.report().warnings())
        .describedAs("the authored root is actionable and must survive the condition's suppression")
        .singleElement()
        .satisfies(warning -> assertThat(warning.ruleId()).isEqualTo("both"));
  }

  @Test
  @DisplayName("the compiler-inserted root is not reported as a shallow tested path")
  void rootFromAConditionIsNotWarnedAbout() {
    /*
     * The root contains every path by definition, so it trips §7.4's shallow-tested-path warning --
     * whose advice, "constrain the deeper path", the author cannot act on for a root they did not
     * write. §7.4 expects this report to be assertable in a build, so an unclearable warning is
     * worse than none. A root the author DOES write still warns; CompilerReportTest pins that.
     */
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("big")
        .when("o", "Order", pattern -> pattern.gt("status", 0)
            .constraint(new ExpressionConstraint("o.total > 1000", Set.of("o"))))
        .then(actions -> actions.emit("x"))
        .build()), withCel());

    assertThat(rules.report().warnings())
        .describedAs("a condition must not saddle its rule with a warning nobody can clear")
        .isEmpty();
  }

  @Test
  @DisplayName("the recorded path is the payload root, which is the conservative choice")
  void recordsTheRoot() {
    /*
     * Pinned because it is a deliberate over-approximation rather than an implementation detail.
     * The precise alternative is extracting read paths from the compiled CEL AST, which is more
     * work and carries the §11.2 trap in miniature: under-declare by one path and the engine
     * silently loses a firing. Recording the root costs a rule exactly what it opted into by
     * reaching for the escape hatch -- every update to a fact type carrying a condition propagates.
     */
    assertThat(conditionOnly().testedPaths().forType("Order"))
        .containsExactly(JsonPointer.compile(""));
  }
}
