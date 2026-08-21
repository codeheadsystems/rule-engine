package com.codeheadsystems.rules.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.concurrent.BatchOutcome;
import com.codeheadsystems.rules.concurrent.RuleBatches;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireOptions;
import com.codeheadsystems.rules.session.RuleEngineLimitExceeded;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.Engine;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tracing listener, exercised on the two situations it exists for (spec §7.1).
 */
class TracingListenerTest {

  @Test
  @DisplayName("a runaway loop is visible in the retained firings")
  void aLoopIsVisibleInTheTrace() {
    // "A runaway loop is almost always visible in the last dozen firings." The cycle-limit
    // exception names only the activation that was next; the dozen before it show the cycle.
    final RuleDefinition toB = Rules.rule("a-to-b")
        .when("o", "Order", pattern -> pattern.eq("status", "A"))
        .then(actions -> actions.setField("o", "status", "B"))
        .build();
    final RuleDefinition toA = Rules.rule("b-to-a")
        .when("o", "Order", pattern -> pattern.eq("status", "B"))
        .then(actions -> actions.setField("o", "status", "A"))
        .build();

    final TracingListener trace = new TracingListener(6);
    try (RuleSession session = Engine.compile(toB, toA)
        .newSession(SessionOptions.builder().listener(trace).build())) {
      session.insert("Order", Facts.obj("status", "A"));

      final RuleEngineLimitExceeded.CycleLimit breach = catchThrowableOfType(
          RuleEngineLimitExceeded.CycleLimit.class,
          () -> session.fireAllRules(FireOptions.builder().maxCycles(20).build()));

      assertThat(breach).isNotNull();
      // Bounded: twenty firings, six retained.
      assertThat(trace.recent()).hasSize(6);
      assertThat(trace.recent())
          .extracting(record -> record.key().ruleId())
          .containsExactly("a-to-b", "b-to-a", "a-to-b", "b-to-a", "a-to-b", "b-to-a");
      assertThat(trace.describe()).contains("a-to-b").contains("b-to-a");
    }
  }

  @Test
  @DisplayName("a rethrown failure leaves the record of what landed")
  void aRethrowLeavesTheRecord() {
    // The default error policy propagates the original exception, which cannot carry a partial
    // result. A commit-phase failure still leaves working-memory effects applied, so without a
    // listener there is no record that the partial state exists.
    final RuleDefinition rule = Rules.rule("boom")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions
            .setField("o", "flagged", true)
            .callFunction("explodes"))
        .build();

    final TracingListener trace = new TracingListener();
    final SessionOptions options = SessionOptions.builder()
        .listener(trace)
        .function("explodes", args -> {
          throw new IllegalStateException("the notification service is down");
        })
        .build();

    try (RuleSession session = Engine.compile(rule).newSession(options)) {
      final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

      assertThatThrownBy(session::fireAllRules)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("the notification service is down");

      // The effect landed and cannot be rolled back...
      assertThat(session.workingMemory().get(handle).orElseThrow()
          .payload().get("flagged").booleanValue()).isTrue();
      // ...and the trace is where that is discoverable.
      assertThat(trace.lastFailure()).isPresent().hasValueSatisfying(record -> {
        assertThat(record.failedAction()).isPresent();
        assertThat(record.effects()).isNotEmpty();
      });
      assertThat(trace.describe()).contains("FAILED at");
    }
  }

  @Test
  @DisplayName("earlier successful firings survive the rethrow too")
  void earlierFiringsSurvive() {
    final RuleDefinition alert = Rules.rule("alert")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
        .build();
    final RuleDefinition boom = Rules.rule("boom").salience(-10)
        .when("o", "Order", pattern -> pattern.eq("status", "DOOMED"))
        .then(actions -> actions.callFunction("explodes"))
        .build();

    final TracingListener trace = new TracingListener();
    final SessionOptions options = SessionOptions.builder()
        .listener(trace)
        .function("explodes", args -> {
          throw new IllegalStateException("nope");
        })
        .build();

    try (RuleSession session = Engine.compile(alert, boom).newSession(options)) {
      session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
      session.insert("Order", Facts.obj("id", 3, "status", "DOOMED"));

      assertThatThrownBy(session::fireAllRules).isInstanceOf(IllegalStateException.class);

      assertThat(trace.recent()).extracting(record -> record.key().ruleId())
          .containsExactly("alert", "alert", "boom");
    }
  }

  @Test
  @DisplayName("the buffer is bounded, so a long-running session cannot grow through it")
  void theBufferIsBounded() {
    final RuleDefinition rule = Rules.rule("alert")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
        .build();

    final TracingListener trace = new TracingListener(10);
    try (RuleSession session = Engine.compile(rule)
        .newSession(SessionOptions.builder().listener(trace).build())) {
      for (int order = 0; order < 500; order++) {
        session.insert("Order", Facts.obj("id", order, "status", "PENDING"));
      }
      session.fireAllRules();

      assertThat(trace.recent()).hasSize(10);
      assertThat(trace.last()).isPresent();
    }
  }

  @Test
  @DisplayName("an empty trace renders as something a person can read")
  void emptyTrace() {
    final TracingListener trace = new TracingListener();
    assertThat(trace.recent()).isEmpty();
    assertThat(trace.last()).isEmpty();
    assertThat(trace.lastFailure()).isEmpty();
    assertThat(trace.describe()).isEqualTo("(nothing fired)");
  }

  @Test
  @DisplayName("a non-positive capacity is rejected rather than silently retaining nothing")
  void capacityIsValidated() {
    assertThatThrownBy(() -> new TracingListener(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  @Test
  @DisplayName("one listener across concurrent sessions loses nothing and throws nothing")
  void survivesConcurrentSessionsFromOneOptionsObject() {
    /*
     * Found in review of Phase 4, and it is a defect in §7.1 rather than in this class as written.
     * Line 1435 says "Registered per session via SessionOptions, so a listener is never shared
     * mutable state across sessions and nothing on the path synchronizes" -- and licenses every
     * listener here to be unsynchronised on that basis.
     *
     * The premise is false. SessionOptions is per *configuration*: one instance, many sessions.
     * Phase 4 is what turns that from latent into the documented main path, because
     * RuleBatches.run(rules, inputs, batch, options) takes exactly one SessionOptions and builds N
     * concurrent sessions from it, and a caller who wants a trace out of a batch run has no other
     * move. The symptom was lost records, or an exception thrown from inside fireAllRules() and
     * surfacing as a BatchOutcome.failure() that looks like the caller's own rule threw.
     *
     * Capacity is above the firing count on purpose, so "records lost" and "records evicted" cannot
     * be confused.
     */
    /*
     * Sized for contention, not for readability. 200 sessions doing one firing each would spend
     * almost all their time in session setup and matching, leaving a sliver of a window where two
     * threads are inside addLast together -- so an unsynchronised version would fail this only
     * sometimes, and a test that detects a race one run in five is a test that reports the race is
     * gone four times in five. 50 sessions of 200 firings each puts two orders of magnitude more
     * traffic through the deque at about the same wall-clock cost.
     */
    final int sessions = 50;
    final int perSession = 200;
    final TracingListener trace = new TracingListener(sessions * perSession);
    final SessionOptions options = SessionOptions.builder().listener(trace).build();
    final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("fires")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.emit("hit"))
        .build()));

    final List<BatchOutcome<Integer>> outcomes = RuleBatches.run(rules,
        IntStream.range(0, sessions).boxed().toList(),
        (session, input) -> {
          for (int order = 0; order < perSession; order++) {
            session.insert("Order", Facts.obj("id", order, "total", 1));
          }
          return session.fireAllRules().firedCount();
        }, options);

    assertThat(outcomes).hasSize(sessions).allMatch(BatchOutcome::succeeded);
    // Capacity is exactly the expected total, so nothing is evicted and a short count is a lost
    // record rather than an eviction. A racing ArrayDeque loses records; it can also throw from
    // List.copyOf mid-resize, which allMatch(succeeded) above is what catches.
    assertThat(trace.recent()).hasSize(sessions * perSession);
  }
}
