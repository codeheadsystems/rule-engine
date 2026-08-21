package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireOptions;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleEngineLimitExceeded;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.session.TerminationReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The firing loop's four orderings (spec section 4.7), one test each.
 *
 * <p>Each of these reads naturally when written the wrong way round, which is exactly why they are
 * pinned. Three of the four produce silent misbehaviour rather than a failure.
 */
class FireLoopTest {

  /** Inserts a fact per firing, so it never drains. The canonical runaway. */
  private static final RuleDefinition RUNAWAY = Rules.rule("runaway")
      .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
      .then(actions -> actions.insertFact("Order", "status", "PENDING"))
      .build();

  /** Fires once per matching fact and mutates nothing. */
  private static final RuleDefinition ALERT = Rules.rule("alert")
      .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
      .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
      .build();

  @Test
  @DisplayName("termination beats the limit check: exactly maxCycles firings does NOT throw")
  void terminationBeatsTheLimit() {
    // Testing the cycle count first throws on a run that legitimately completed, at exactly the
    // boundary a well-tuned limit sits on. The tell that the other ordering is wrong is that the
    // exception has no offending activation to report.
    final FireResult result = Engine.result(Engine.compile(ALERT),
        SessionOptions.builder().limits(FireOptions.builder().maxCycles(3).build()).build(),
        session -> {
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
          session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
          session.insert("Order", Facts.obj("id", 3, "status", "PENDING"));
        });

    assertThat(result.firedCount()).isEqualTo(3);
    assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
  }

  @Test
  @DisplayName("the cycle limit names the culprit and carries every completed firing")
  void cycleLimitCarriesTheWork() {
    final CompiledRuleSet ruleSet = Engine.compile(RUNAWAY);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("status", "PENDING"));

      final RuleEngineLimitExceeded.CycleLimit breach = catchThrowableOfType(
          RuleEngineLimitExceeded.CycleLimit.class,
          () -> session.fireAllRules(FireOptions.builder().maxCycles(5).build()));

      assertThat(breach).isNotNull();
      assertThat(breach.limit()).isEqualTo(5);
      // A limit alone tells you nothing actionable; a runaway loop is almost always one or two
      // rules, and the activation that was next in line identifies them immediately.
      assertThat(breach.next()).isPresent();
      assertThat(breach.next().orElseThrow().rule().id()).isEqualTo("runaway");
      // And a batch that fired 9,999 rules must not lose all of it on the 10,000th.
      assertThat(breach.partialResult().firedCount()).isEqualTo(5);
      assertThat(breach.partialResult().why()).isEqualTo(TerminationReason.LIMIT_EXCEEDED);
      assertThat(breach.partialResult().residualAgendaSize()).isPositive();
    }
  }

  @Test
  @DisplayName("the limit check PEEKS: the reported activation was not consumed")
  void limitCheckDoesNotConsume() {
    // Selecting an activation and then throwing without executing it destroys work the session can
    // never recover. It is easy to reintroduce, because "get the next activation, then validate"
    // reads naturally. The proof is that raising the limit fires it.
    final CompiledRuleSet ruleSet = Engine.compile(ALERT);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

      final RuleEngineLimitExceeded.CycleLimit breach = catchThrowableOfType(
          RuleEngineLimitExceeded.CycleLimit.class,
          () -> session.fireAllRules(FireOptions.builder().maxCycles(1).build()));

      assertThat(breach).isNotNull();
      assertThat(breach.partialResult().firedCount()).isEqualTo(1);

      // The peeked activation is still eligible. Had the check consumed it, this would fire
      // nothing and one alert would have been silently lost.
      final FireResult resumed = session.fireAllRules(FireOptions.builder().maxCycles(10).build());
      assertThat(resumed.firedCount()).isEqualTo(1);
      assertThat(resumed.why()).isEqualTo(TerminationReason.DRAINED);
    }
  }

  @Test
  @DisplayName("the fact limit pairs with the cycle limit and surfaces unretracted growth")
  void factLimit() {
    // A rule inserting a fact per firing exhausts the heap long before a high cycle limit trips,
    // and an out-of-memory error says nothing about which rule did it.
    final CompiledRuleSet ruleSet = Engine.compile(RUNAWAY);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("status", "PENDING"));

      final RuleEngineLimitExceeded.FactLimit breach = catchThrowableOfType(
          RuleEngineLimitExceeded.FactLimit.class,
          () -> session.fireAllRules(
              FireOptions.builder().maxCycles(1_000).maxFacts(4).build()));

      assertThat(breach).isNotNull();
      assertThat(breach.limit()).isEqualTo(4);
      assertThat(breach.actual()).isGreaterThan(4);
      assertThat(breach.partialResult().firedCount()).isPositive();
    }
  }

  @Test
  @DisplayName("halt is checked before consuming, so a halted session skips no firing")
  void haltIsCheckedBeforeConsuming() {
    // Checking after selection silently discards the selected activation, so a halted session
    // loses exactly one firing, non-deterministically.
    final RuleDefinition rule = Rules.rule("alert-and-halt")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions
            .emit("alert", "id", Rules.ref("o.id"))
            .callFunction("halt"))
        .build();

    final CompiledRuleSet ruleSet = Engine.compile(rule);
    try (RuleSession session = ruleSet.newSession(SessionOptions.builder()
        .function("halt", args -> { })
        .build())) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
      session.halt();

      final FireResult result = session.fireAllRules();

      assertThat(result.firedCount()).isZero();
      assertThat(result.why()).isEqualTo(TerminationReason.HALTED);
      // Both matches are still eligible: nothing was consumed and thrown away.
      assertThat(result.residualAgendaSize()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("residualAgendaSize counts eligible activations, so a drained session reports zero")
  void residualCountsWhatIsEligible() {
    // A count of everything sitting in the conflict set would report a residual for a session that
    // had genuinely drained, because refracted matches would still be in there.
    final CompiledRuleSet ruleSet = Engine.compile(ALERT);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

      assertThat(session.fireAllRules().residualAgendaSize()).isZero();
    }
  }

  @Test
  @DisplayName("both limits are always in force: there is no limit-less fire")
  void limitsAreMandatory() {
    assertThat(FireOptions.defaults().maxCycles()).isPositive();
    assertThat(FireOptions.defaults().maxFacts()).isPositive();
    assertThatThrownBy(() -> FireOptions.builder().maxCycles(0).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FireOptions.builder().maxFacts(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("noLoop stops a rule re-triggering itself through its own mutation")
  void noLoopStopsTheSelfTrigger() {
    // Refraction alone permits: R fires, its RHS changes a fact on a path R tests, refraction for
    // that match clears, and R fires again. That is the loop noLoop addresses.
    //
    // Note how hard this is to provoke, and why. The update is gated on a tested-path diff, so an
    // RHS that writes a literal converges on its second firing: the second write changes nothing
    // and propagates nothing. So the un-guarded version below fires twice, not forever -- which is
    // the honest demonstration of what this flag buys, and exactly why the spec insists noLoop is
    // not a loop guard.
    final RuleDefinition unguarded = Rules.rule("discount")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.setField("o", "total", 50))
        .build();

    final FireResult twice = Engine.result(Engine.compile(unguarded), SessionOptions.defaults(),
        session -> session.insert("Order", Facts.obj("total", 100)));
    assertThat(twice.firedCount()).isEqualTo(2);

    final RuleDefinition guarded = Rules.rule("discount")
        .noLoop()
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.setField("o", "total", 50))
        .build();

    final FireResult once = Engine.result(Engine.compile(guarded), SessionOptions.defaults(),
        session -> session.insert("Order", Facts.obj("total", 100)));
    assertThat(once.firedCount()).isEqualTo(1);
    assertThat(once.why()).isEqualTo(TerminationReason.DRAINED);
  }

  @Test
  @DisplayName("noLoop is one level deep: a two-rule ping-pong sails straight through it")
  void noLoopDoesNotStopMutualRecursion() {
    // Every engine offering this flag has the same limitation, and pretending otherwise leads
    // people to treat it as a loop guard. maxCycles is the actual loop defence.
    final RuleDefinition toB = Rules.rule("a-to-b").noLoop()
        .when("o", "Order", pattern -> pattern.eq("status", "A"))
        .then(actions -> actions.setField("o", "status", "B"))
        .build();
    final RuleDefinition toA = Rules.rule("b-to-a").noLoop()
        .when("o", "Order", pattern -> pattern.eq("status", "B"))
        .then(actions -> actions.setField("o", "status", "A"))
        .build();

    final CompiledRuleSet ruleSet = Engine.compile(toB, toA);
    try (RuleSession session = ruleSet.newSession()) {
      session.insert("Order", Facts.obj("status", "A"));

      final RuleEngineLimitExceeded.CycleLimit breach = catchThrowableOfType(
          RuleEngineLimitExceeded.CycleLimit.class,
          () -> session.fireAllRules(FireOptions.builder().maxCycles(8).build()));

      assertThat(breach).isNotNull();
      assertThat(breach.partialResult().firedCount()).isEqualTo(8);
      assertThat(breach.partialResult().fired())
          .extracting(record -> record.key().ruleId())
          .containsExactly("a-to-b", "b-to-a", "a-to-b", "b-to-a",
              "a-to-b", "b-to-a", "a-to-b", "b-to-a");
    }
  }
}
