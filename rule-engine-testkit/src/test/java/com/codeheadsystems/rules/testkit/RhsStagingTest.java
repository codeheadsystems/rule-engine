package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.rhs.RhsErrorHandler;
import com.codeheadsystems.rules.rhs.StagedEffect;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.session.TerminationReason;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Right-hand-side staging and commit (spec section 4.6).
 *
 * <p>Section 4.6 names the two sharpest edges here explicitly, and both have a test below: merging
 * several field sets on one handle into a single update, and the honest per-phase atomicity
 * boundary. Section 10 audits a third -- deltas must materialise onto a copy, never onto the stored
 * payload in place -- which is what the "an effective update actually propagates" assertion checks.
 */
class RhsStagingTest {

  @Nested
  @DisplayName("field deltas")
  class Deltas {

    @Test
    @DisplayName("several setFields on one handle all survive, in declaration order")
    void multipleSetFieldsMerge() {
      // Section 4.6 calls this "the single most likely week-one bug in this design": staging each
      // field set as an independent update built from the pre-firing payload means the second
      // overwrites the first and the earlier change silently vanishes.
      final RuleDefinition rule = Rules.rule("review")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "REVIEW")
              .setField("o", "reviewedBy", "risk-team")
              .setField("o", "priority", 1))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        final var handle = session.insert("Order", Facts.obj("status", "PENDING"));
        session.fireAllRules();

        final Fact after = session.get(handle).orElseThrow();
        assertThat(after.payload().get("status").textValue()).isEqualTo("REVIEW");
        assertThat(after.payload().get("reviewedBy").textValue()).isEqualTo("risk-team");
        assertThat(after.payload().get("priority").intValue()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("the last write to one path wins")
    void lastWriteWins() {
      final RuleDefinition rule = Rules.rule("twice")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.setField("o", "status", "FIRST")
              .setField("o", "status", "SECOND"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        final var handle = session.insert("Order", Facts.obj("status", "PENDING"));
        session.fireAllRules();
        assertThat(session.get(handle).orElseThrow().payload().get("status").textValue())
            .isEqualTo("SECOND");
      }
    }

    @Test
    @DisplayName("a nested path is created on the way down")
    void nestedPaths() {
      final RuleDefinition rule = Rules.rule("annotate")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.setField("o", "audit.decidedBy", "engine"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        final var handle = session.insert("Order", Facts.obj("status", "PENDING"));
        session.fireAllRules();
        assertThat(session.get(handle).orElseThrow()
            .payload().at("/audit/decidedBy").textValue()).isEqualTo("engine");
      }
    }

    @Test
    @DisplayName("a setField genuinely propagates, so the change is not invisible to matching")
    void mutationsPropagate() {
      // Applying the deltas to the stored node in place would make the update diff compare an
      // object against itself, propagate nothing, and leave the engine's view stale. The tell is
      // that a rule watching for the NEW value never fires.
      final RuleDefinition promote = Rules.rule("promote")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.setField("o", "status", "REVIEW"))
          .build();
      final RuleDefinition observe = Rules.rule("observe")
          .when("o", "Order", pattern -> pattern.eq("status", "REVIEW"))
          .then(actions -> actions.emit("saw.review", "status", Rules.ref("o.status")))
          .build();

      final FireResult result = Engine.result(Engine.compile(promote, observe),
          SessionOptions.defaults(),
          session -> session.insert("Order", Facts.obj("status", "PENDING")));

      assertThat(result.emitted()).singleElement()
          .extracting(event -> event.eventType()).isEqualTo("saw.review");
    }
  }

  @Nested
  @DisplayName("ordering and visibility")
  class Visibility {

    @Test
    @DisplayName("an action cannot observe an earlier action's effect")
    void deferredCommit() {
      // Propagating after each action would let action 2 see what action 1 did, making a rule's
      // behaviour depend on action ordering in ways invisible in the rule file.
      final RuleDefinition rule = Rules.rule("set-then-emit")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "REVIEW")
              .emit("decision", "status", Rules.ref("o.status")))
          .build();

      final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
          session -> session.insert("Order", Facts.obj("status", "PENDING")));

      assertThat(result.emitted()).singleElement()
          .extracting(event -> event.payload().get("status").textValue())
          .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a later action can name a fact this same RHS inserted")
    void insertedFactIsNameable() {
      // The handle is allocated at stage time, not at commit, precisely so that this shape works:
      // insert a derived fact, then emit an event naming it.
      final RuleDefinition rule = Rules.rule("signal")
          .when("o", "Order", pattern -> pattern.gt("total", 10000))
          .then(actions -> actions
              .insertFactAs("RiskSignal", "sig",
                  "orderId", Rules.ref("o.id"), "severity", "HIGH")
              .emit("risk.raised", "severity", Rules.ref("sig.severity")))
          .build();

      final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
          session -> session.insert("Order", Facts.obj("id", 1, "total", 25000)));

      assertThat(result.emitted()).singleElement()
          .extracting(event -> event.payload().get("severity").textValue())
          .isEqualTo("HIGH");
    }

    @Test
    @DisplayName("inserting and retracting in one RHS cancels both, rather than propagating both")
    void insertThenRetractCancels() {
      final RuleDefinition rule = Rules.rule("churn")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFactAs("RiskSignal", "sig", "orderId", Rules.ref("o.id"))
              .retractFact("sig"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("RiskSignal")).isEmpty();
        assertThat(result.fired()).singleElement()
            .extracting(record -> record.effects()).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(StagedEffect.class))
            .isEmpty();
      }
    }

    @Test
    @DisplayName("a rule can retract a fact its own left-hand side bound")
    void retractingABoundFact() {
      final RuleDefinition rule = Rules.rule("purge")
          .when("o", "Order", pattern -> pattern.eq("status", "CANCELLED"))
          .then(actions -> actions
              .emit("purged", "id", Rules.ref("o.id"))
              .retractFact("o"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "CANCELLED"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

        final FireResult result = session.fireAllRules();

        assertThat(result.firedCount()).isEqualTo(1);
        assertThat(session.workingMemory().factsOfType("Order"))
            .extracting(fact -> fact.payload().get("id").intValue())
            .containsExactly(2);
        assertThat(result.fired().getFirst().effects())
            .anySatisfy(effect ->
                assertThat(effect).isInstanceOf(StagedEffect.FactRetracted.class));
        // The emit was staged before the retract, so it still carries the fact's data even though
        // the fact is gone by the time anyone reads the result.
        assertThat(result.emitted()).singleElement()
            .extracting(event -> event.payload().get("id").intValue()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("a $ref to an absent field resolves to JSON null, not a failure")
    void absentReferencesAreNull() {
      final RuleDefinition rule = Rules.rule("emit-missing")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("out", "nope", Rules.ref("o.doesNotExist")))
          .build();

      final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
          session -> session.insert("Order", Facts.obj("status", "PENDING")));

      assertThat(result.emitted()).singleElement()
          .extracting(event -> event.payload().get("nope").isNull()).isEqualTo(true);
    }
  }

  @Nested
  @DisplayName("the atomicity boundary, which is per-phase")
  class Atomicity {

    @Test
    @DisplayName("a staging-phase failure applies nothing at all")
    void stagingFailureRollsBackEverything() {
      final RuleDefinition rule = Rules.rule("retract-then-set")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "touched", true)
              .retractFact("o")
              .setField("o", "status", "REVIEW"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(
          SessionOptions.builder()
              .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.ABORT_SESSION)
              .build())) {
        final var handle = session.insert("Order", Facts.obj("status", "PENDING"));
        final FireResult result = session.fireAllRules();

        assertThat(result.why()).isEqualTo(TerminationReason.RHS_ERROR);
        // Nothing landed: not the field set that was staged before the failure, not the retract.
        final Fact after = session.get(handle).orElseThrow();
        assertThat(after.payload().has("touched")).isFalse();
        assertThat(after.payload().get("status").textValue()).isEqualTo("PENDING");
        assertThat(result.fired()).singleElement()
            .satisfies(record -> {
              assertThat(record.effects()).isEmpty();
              assertThat(record.failedAction()).isPresent();
            });
      }
    }

    @Test
    @DisplayName("a commit-phase handler failure leaves working-memory effects applied")
    void commitFailureDoesNotRollBack() {
      // This is the honest half of the guarantee. A sent message cannot be un-sent, so there is no
      // compensating undo -- and the fire record has to say what actually landed, or the partial
      // state is undiscoverable.
      final List<String> called = new ArrayList<>();
      final RuleDefinition rule = Rules.rule("notify")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "REVIEW")
              .callFunction("first", "id", Rules.ref("o.id"))
              .callFunction("explodes")
              .callFunction("never-runs")
              .emit("never.delivered"))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .function("first", args -> called.add("first"))
          .function("explodes", args -> {
            called.add("explodes");
            throw new IllegalStateException("the notification service is down");
          })
          .function("never-runs", args -> called.add("never-runs"))
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.ABORT_SESSION)
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        // The working-memory effect landed and stays landed.
        assertThat(session.get(handle).orElseThrow().payload().get("status").textValue())
            .isEqualTo("REVIEW");
        // Handlers ran in declaration order, and stopped at the failure.
        assertThat(called).containsExactly("first", "explodes");
        // Emissions come after handlers, so nothing was delivered.
        assertThat(result.emitted()).isEmpty();

        // And the record says exactly what landed, including the failed call.
        final var record = result.fired().getFirst();
        assertThat(record.failedAction()).isPresent();
        assertThat(record.effects()).anySatisfy(effect ->
            assertThat(effect).isInstanceOfSatisfying(StagedEffect.FunctionCalled.class,
                call -> {
                  assertThat(call.name()).isEqualTo("explodes");
                  assertThat(call.succeeded()).isFalse();
                }));
        assertThat(record.effects()).noneSatisfy(effect ->
            assertThat(effect).isInstanceOfSatisfying(StagedEffect.FunctionCalled.class,
                call -> assertThat(call.name()).isEqualTo("never-runs")));
      }
    }

    @Test
    @DisplayName("the default error policy rethrows and marks the session failed")
    void rethrowIsTheDefault() {
      final RuleDefinition rule = Rules.rule("boom")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.callFunction("explodes"))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .function("explodes", args -> {
            throw new IllegalStateException("nope");
          })
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        session.insert("Order", Facts.obj("status", "PENDING"));

        assertThatThrownBy(session::fireAllRules)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("nope");

        // Silent continuation after an unexpected RHS exception is how a rule engine produces
        // confidently wrong output, so the session refuses further work.
        assertThatThrownBy(() -> session.insert("Order", Facts.obj("status", "PENDING")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("session failed");
      }
    }

    @Test
    @DisplayName("skip-and-continue refracts the failure so it cannot retry-loop")
    void skipActivationDoesNotRetryLoop() {
      final RuleDefinition rule = Rules.rule("flaky")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.callFunction("explodes"))
          .build();

      final List<String> attempts = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .function("explodes", args -> {
            attempts.add("attempt");
            throw new IllegalStateException("nope");
          })
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.SKIP_ACTIVATION)
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));

        final FireResult result = session.fireAllRules();

        // Each match is attempted exactly once. Recording refraction on success only would turn
        // this into an infinite retry loop that looks exactly like the non-termination refraction
        // exists to prevent.
        assertThat(attempts).hasSize(2);
        assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
        assertThat(result.fired()).hasSize(2)
            .allSatisfy(record -> assertThat(record.failedAction()).isPresent());
      }
    }

    @Test
    @DisplayName("abort reports RHS_ERROR, deliberately not a limit breach")
    void abortIsNotALimitBreach() {
      // A caller switching on the reason to decide "retry with a higher maxCycles" would otherwise
      // be told to do exactly the wrong thing.
      final RuleDefinition rule = Rules.rule("boom")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.callFunction("explodes"))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .function("explodes", args -> {
            throw new IllegalStateException("nope");
          })
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.ABORT_SESSION)
          .build();

      final FireResult result = Engine.result(Engine.compile(rule), options,
          session -> session.insert("Order", Facts.obj("status", "PENDING")));

      assertThat(result.why()).isEqualTo(TerminationReason.RHS_ERROR)
          .isNotEqualTo(TerminationReason.LIMIT_EXCEEDED);
    }
  }

  @Nested
  @DisplayName("host functions")
  class Functions {

    @Test
    @DisplayName("arguments are resolved and copied before the handler sees them")
    void argumentsAreCopied() {
      // Handing a handler the live node would put a hole the size of the escape hatch in the
      // payload ownership contract: the handler is arbitrary host Java and is under no obligation
      // not to mutate what it is given.
      final RuleDefinition rule = Rules.rule("call")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.callFunction("mutate", "id", Rules.ref("o.id")))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .function("mutate", args ->
              ((com.fasterxml.jackson.databind.node.ObjectNode) args).put("id", 999))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.fireAllRules();
        assertThat(session.get(handle).orElseThrow().payload().get("id").intValue()).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("dry run")
  class DryRun {

    @Test
    @DisplayName("a dry run reports what would fire and changes nothing")
    void dryRunStagesAndDiscards() {
      final List<String> called = new ArrayList<>();
      final RuleDefinition rule = Rules.rule("review")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "REVIEW")
              .insertFact("RiskSignal", "orderId", Rules.ref("o.id"))
              .callFunction("notify")
              .emit("order.flagged", "orderId", Rules.ref("o.id")))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .dryRun(true)
          .function("notify", args -> called.add("notify"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        // It answers "what would fire, in what order, on these facts".
        assertThat(result.firedCount()).isEqualTo(1);
        assertThat(result.fired().getFirst().effects()).isNotEmpty();
        assertThat(result.emitted()).hasSize(1);

        // ... and nothing actually happened.
        assertThat(session.get(handle).orElseThrow().payload().get("status").textValue())
            .isEqualTo("PENDING");
        assertThat(session.workingMemory().factsOfType("RiskSignal")).isEmpty();
        assertThat(called).isEmpty();
      }
    }
  }
}
