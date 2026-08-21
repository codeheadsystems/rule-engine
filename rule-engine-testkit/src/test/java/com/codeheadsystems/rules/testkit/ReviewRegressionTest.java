package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.SuppressReason;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rhs.RhsErrorHandler;
import com.codeheadsystems.rules.rhs.StagedEffect;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CollectingEventSink;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.EventSink;
import com.codeheadsystems.rules.session.FireRecord;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.session.TerminationReason;
import com.codeheadsystems.rules.value.Comparisons;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Regressions for the defects a senior review found after Phase 0 was first written.
 *
 * <p>Kept together rather than scattered into the topic suites, because what they have in common is
 * more useful than what separates them: every one is a case where the implementation's own Javadoc
 * asserted a spec rule it did not actually enforce. That is the failure mode worth a named suite.
 */
class ReviewRegressionTest {

  @Nested
  @DisplayName("commit-phase failures are reported, not just handler failures")
  class CommitFailures {

    /** Its {@code meta} field is a string, so writing through {@code meta.x} cannot work. */
    private static final RuleDefinition WRITES_THROUGH_A_SCALAR = Rules.rule("bad-path")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.setField("o", "meta.reviewed", true))
        .build();

    @Test
    @DisplayName("a failing working-memory effect goes through the error policy, not past it")
    void workingMemoryFailureReachesThePolicy() {
      // Before the fix, the exception propagated straight out of fireAllRules: the policy was never
      // consulted, no record was produced, no result was returned, and the session was left usable
      // with a half-applied right-hand side.
      final List<String> policyCalls = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .onRhsError((activation, failed, cause) -> {
            policyCalls.add(failed.toString());
            return RhsErrorHandler.Decision.SKIP_ACTIVATION;
          })
          .build();

      try (RuleSession session = Engine.compile(WRITES_THROUGH_A_SCALAR).newSession(options)) {
        session.insert("Order",
            Facts.obj("id", 1, "status", "PENDING", "meta", "not-an-object"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING", "meta", "also-a-string"));

        final java.util.concurrent.atomic.AtomicReference<FireResult> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        assertThatCode(() -> captured.set(session.fireAllRules()))
            .describedAs("SKIP_ACTIVATION must absorb a commit-phase working-memory failure")
            .doesNotThrowAnyException();

        // Asserted on the run that did the work. Firing a second time and asserting on THAT would
        // be near-vacuous: both activations are refracted by then, so it drains trivially.
        final FireResult result = captured.get();
        assertThat(policyCalls).hasSize(2);
        assertThat(result.firedCount()).isEqualTo(2);
        assertThat(result.why()).isEqualTo(TerminationReason.DRAINED);
        assertThat(result.fired()).allSatisfy(record ->
            assertThat(record.failedAction()).isPresent());
      }
    }

    @Test
    @DisplayName("the firing record says what landed and what never ran")
    void partialCommitIsDiscoverable() {
      final RuleDefinition rule = Rules.rule("partial")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFact("Audit", "orderId", Rules.ref("o.id"))
              .setField("o", "meta.reviewed", true)
              // A sibling on the same handle: it merges into the same update, so it is discarded
              // with it. Without this the test passed incidentally, because the failing field set
              // was the only delta in its operation.
              .setField("o", "alsoLost", true)
              .callFunction("never")
              .emit("also.never"))
          .build();

      final List<String> called = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .function("never", args -> called.add("never"))
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.ABORT_SESSION)
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        session.insert("Order",
            Facts.obj("id", 1, "status", "PENDING", "meta", "not-an-object"));

        final FireResult result = session.fireAllRules();

        assertThat(result.why()).isEqualTo(TerminationReason.RHS_ERROR);
        final FireRecord record = result.fired().getFirst();

        // The insert landed and cannot be rolled back; the record says so.
        assertThat(record.effects()).anySatisfy(effect ->
            assertThat(effect).isInstanceOf(StagedEffect.FactInserted.class));
        assertThat(session.workingMemory().factsOfType("Audit")).hasSize(1);

        // The failing action is named, and everything downstream is reported as never run.
        assertThat(record.failedAction()).isPresent();
        assertThat(record.notRun())
            .describedAs("the sibling field set, the handler and the emit all failed to run")
            .hasSize(3);
        assertThat(called).isEmpty();
        assertThat(result.emitted()).isEmpty();
      }
    }
  }

  @Nested
  @DisplayName("the default error policy keeps the trace")
  class RethrowTrace {

    @Test
    @DisplayName("a rethrown failure still publishes the firing record to listeners first")
    void rethrowPublishesTheRecord() {
      // A commit-phase failure leaves working-memory effects applied. Rethrowing without building
      // the record destroyed the only evidence that partial state existed.
      final RuleDefinition rule = Rules.rule("boom")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "flagged", true)
              .callFunction("explodes"))
          .build();

      final List<FireRecord> trace = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .function("explodes", args -> {
            throw new IllegalStateException("nope");
          })
          .listener(new RuleEngineListener() {
            @Override
            public void onAfterFire(final FireRecord record) {
              trace.add(record);
            }
          })
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        final var handle = session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));

        assertThatThrownBy(session::fireAllRules)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("nope");

        // The effect landed...
        assertThat(session.workingMemory().get(handle).orElseThrow()
            .payload().get("flagged").booleanValue()).isTrue();
        // ...and there is now a record of it.
        assertThat(trace).singleElement().satisfies(record -> {
          assertThat(record.failedAction()).isPresent();
          assertThat(record.effects()).isNotEmpty();
        });
      }
    }

    @Test
    @DisplayName("earlier successful firings are on the trace too, not lost with the exception")
    void earlierFiringsSurvive() {
      final RuleDefinition alert = Rules.rule("alert")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
          .build();
      final RuleDefinition boom = Rules.rule("boom").salience(-10)
          .when("o", "Order", pattern -> pattern.eq("status", "DOOMED"))
          .then(actions -> actions.callFunction("explodes"))
          .build();

      final List<String> trace = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .function("explodes", args -> {
            throw new IllegalStateException("nope");
          })
          .listener(new RuleEngineListener() {
            @Override
            public void onAfterFire(final FireRecord record) {
              trace.add(record.key().ruleId());
            }
          })
          .build();

      try (RuleSession session = Engine.compile(alert, boom).newSession(options)) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("Order", Facts.obj("id", 2, "status", "PENDING"));
        session.insert("Order", Facts.obj("id", 3, "status", "DOOMED"));

        assertThatThrownBy(session::fireAllRules).isInstanceOf(IllegalStateException.class);

        assertThat(trace).containsExactly("alert", "alert", "boom");
      }
    }
  }

  @Nested
  @DisplayName("numeric equality means the same thing at every depth")
  class ContainerNumericEquality {

    /*
     * Found reviewing the Jackson 3 migration. Jackson 2's DecimalNode.equals compared with
     * BigDecimal.compareTo (scale-insensitive); Jackson 3 uses BigDecimal.equals (scale-SENSITIVE),
     * so 100.00 and 100.0 stopped being equal inside a container.
     *
     * That left the engine contradicting itself. §2.6.1 puts every number in one {number} class and
     * Canonical strips trailing zeros, which is what makes `10000` and `10000.0` agree -- and the
     * scalar path still said they did while the container path, which delegated straight to
     * Jackson, said they did not. Same two numbers, different answer depending on whether they sat
     * inside an object.
     *
     * The literal reading of §2.6.1 ("EQ on two object/array values is Jackson's structural
     * equals") was still satisfied, which is exactly why this is worth a test rather than a shrug:
     * that sentence was written when Jackson's structural equals happened to agree with this
     * engine's numeric equality, and it silently stopped describing the same thing. The spec
     * sentence is amended; the code follows the engine's own numeric semantics at every depth.
     */
    private static final ObjectNode SCALED =
        Facts.obj("amount", new java.math.BigDecimal("100.00"));
    private static final ObjectNode UNSCALED =
        Facts.obj("amount", new java.math.BigDecimal("100.0"));

    @Test
    @DisplayName("a decimal's scale does not decide an object-valued EQ")
    void objectValuedEqualityIgnoresScale() {
      assertThat(Comparisons.test(Operator.EQ, SCALED, UNSCALED))
          .describedAs("{amount: 100.00} EQ {amount: 100.0}")
          .isTrue();
    }

    @Test
    @DisplayName("nor an array-valued one, at any nesting depth")
    void arrayValuedEqualityIgnoresScale() {
      assertThat(Comparisons.test(Operator.EQ,
          Facts.array(new java.math.BigDecimal("2.50")),
          Facts.array(new java.math.BigDecimal("2.5")))).isTrue();
      assertThat(Comparisons.test(Operator.EQ,
          Facts.obj("nested", Facts.array(Facts.obj("v", new java.math.BigDecimal("1.000")))),
          Facts.obj("nested", Facts.array(Facts.obj("v", new java.math.BigDecimal("1"))))))
          .describedAs("object > array > object > number")
          .isTrue();
    }

    @Test
    @DisplayName("and the scalar path still agrees, which is the property that broke")
    void scalarAgreesWithContainer() {
      assertThat(Comparisons.test(Operator.EQ,
          Facts.obj("v", new java.math.BigDecimal("100.00")).get("v"),
          Facts.obj("v", new java.math.BigDecimal("100.0")).get("v"))).isTrue();
    }

    @Test
    @DisplayName("§2.6.1's other container rules still hold: key order free, element order not")
    void structuralRulesUnchanged() {
      assertThat(Comparisons.test(Operator.EQ,
          Facts.json("{\"a\": 1, \"b\": 2}"), Facts.json("{\"b\": 2, \"a\": 1}")))
          .describedAs("object key order does not matter").isTrue();
      assertThat(Comparisons.test(Operator.EQ,
          Facts.array(1, 2), Facts.array(2, 1)))
          .describedAs("array element order does").isFalse();
      assertThat(Comparisons.test(Operator.EQ,
          Facts.json("{\"a\": 1}"), Facts.json("{\"a\": 1, \"b\": 2}")))
          .describedAs("a missing key is not equal").isFalse();
      assertThat(Comparisons.test(Operator.EQ, Facts.json("{}"), Facts.array()))
          .describedAs("an object is not an array").isFalse();
    }

    @Test
    @DisplayName("an object matches a fact through the whole engine, not just the comparator")
    void endToEnd() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("exact-object")
          .when("o", "Order", pattern -> pattern.eq("breakdown",
              Facts.obj("net", new java.math.BigDecimal("100.0"))))
          .then(actions -> actions.emit("matched"))
          .build()));

      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("breakdown",
            Facts.obj("net", new java.math.BigDecimal("100.00"))));
        assertThat(session.fireAllRules().firedCount())
            .describedAs("a money amount written at a different scale still matches")
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("the compiled rule set is genuinely immutable")
  class Immutability {

    @Test
    @DisplayName("the tested-path inverse index is not a live set the caller can clear")
    void inverseIndexIsNotLive() {
      /*
       * DefaultTestedPaths.deepCopyInverse built its inner maps with Map.copyOf, which is shallow,
       * so the VALUES stayed the compiler's live LinkedHashSets and rulesTesting() handed one
       * straight back. Its two sibling copiers both used Set.copyOf; only the inverse index did not.
       *
       * Worse than the literal hole the Phase 4 fingerprint detects, and invisible to it. This set
       * is what §3.4.1 step 5 reads to decide which rules get un-refracted after an update, so
       * clearing it does not change matching -- it stops rules being un-refracted, and a rule that
       * should re-fire after an update simply never fires again. No exception, version() unmoved,
       * and RuleSetFingerprint walks constraints and action values, not this.
       *
       * CompiledRule's own compact constructor carries a comment about exactly this trap.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("watches-total")
          .when("o", "Order", pattern -> pattern.gt("total", 100))
          .then(actions -> actions.emit("hit"))
          .build()));

      final Set<String> testing =
          rules.testedPaths().rulesTesting("Order", JsonPointer.compile("/total"));
      assertThat(testing).containsExactly("watches-total");

      assertThatThrownBy(testing::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an update still un-refracts, which is what the live set would have broken")
    void updateStillUnrefracts() {
      // The behaviour the above protects, asserted end to end so the guard is not the only thing
      // standing between a shallow copy and a silently dead rule.
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("watches-total")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 100))
          .then(actions -> actions.emit("hit", "total", Rules.ref("o.total")))
          .build()));

      try (RuleSession session = rules.newSession()) {
        final FactHandle handle = session.insert("Order", Facts.obj("total", 500));
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

        session.update(handle, Facts.obj("total", 900));

        // Refraction was cleared for this rule because /total is a path it tests, so it fires again
        // on the new value. With a cleared inverse index this would be 0, forever.
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("mutating a literal after compilation changes nothing")
    void constraintLiteralsAreCopied() {
      // Before the fix this was a write, with no synchronisation, to state that every session
      // reads on every alpha test.
      final ArrayNode tiers = Facts.array("HIGH");
      final Constraint constraint = new FieldConstraint("riskTier", Operator.IN, tiers);
      final RuleDefinition rule = Rules.rule("risky")
          .when("c", "Customer", pattern -> pattern.constraint(constraint))
          .then(actions -> actions.emit("risky", "id", Rules.ref("c.id")))
          .build();
      final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(rule));

      tiers.add("LOW");

      assertThat(Engine.result(ruleSet, SessionOptions.defaults(),
          session -> session.insert("Customer", Facts.obj("id", 1, "riskTier", "LOW")))
          .firedCount())
          .describedAs("a rule set compiled before the literal was mutated must not see the change")
          .isZero();
    }

    @Test
    @DisplayName("a rule's tested-path sets cannot be cleared through the shared rule set")
    void testedPathSetsAreFrozen() {
      final RuleDefinition rule = Rules.rule("reads")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("out"))
          .build();
      final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(rule));

      assertThatThrownBy(() -> ruleSet.rules().getFirst().testedPaths().get("Order").clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("the rule-set version hash is stable")
  class VersionHash {

    @Test
    @DisplayName("tag iteration order is sorted, not per-JVM randomised")
    void tagsAreOrdered() {
      // This is the actual mechanism of the bug: Set.copyOf randomises iteration with a per-process
      // salt, and the hash was derived from a record's toString. No same-JVM test could catch the
      // symptom, so the property is pinned at the field instead.
      final RuleDefinition rule = new RuleDefinition("v", 0,
          Rules.rule("x").when("o", "Order").then(t -> t.emit("e")).build().when(),
          Rules.rule("x").when("o", "Order").then(t -> t.emit("e")).build().then(),
          false, Optional.empty(),
          Set.of("epsilon", "alpha", "delta", "beta", "gamma"));

      assertThat(rule.tags()).containsExactly("alpha", "beta", "delta", "epsilon", "gamma");
    }

    @Test
    @DisplayName("the same rules hash identically however their tags were supplied")
    void hashIsIndependentOfTagInsertionOrder() {
      assertThat(versionWithTags(List.of("alpha", "beta", "gamma", "delta", "epsilon")))
          .isEqualTo(versionWithTags(List.of("epsilon", "delta", "gamma", "beta", "alpha")));
    }

    @Test
    @DisplayName("a different tag set still changes the hash")
    void hashStillTracksContent() {
      assertThat(versionWithTags(List.of("alpha"))).isNotEqualTo(versionWithTags(List.of("beta")));
    }

    private static String versionWithTags(final List<String> tags) {
      final RuleDefinition base = Rules.rule("v")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("e"))
          .build();
      final var ordered = new java.util.LinkedHashSet<>(tags);
      return RuleCompiler.compile(List.of(new RuleDefinition(base.id(), base.salience(),
          base.when(), base.then(), base.noLoop(), base.agendaGroup(), ordered))).version();
    }
  }

  @Nested
  @DisplayName("session options carry no shared mutable state")
  class SharedState {

    @Test
    @DisplayName("a listener held in options really is shared by every session built from them")
    void listenersAreSharedAcrossSessions() {
      /*
       * §7.1 line 1435 licenses every listener in this engine to be unsynchronised: "Registered per
       * session via SessionOptions, so a listener is never shared mutable state across sessions and
       * nothing on the path synchronizes."
       *
       * The premise is false, and this pins the falseness rather than the symptom. SessionOptions is
       * per *configuration*: one instance is built once and used for many sessions -- which is
       * precisely what RuleBatches.run(rules, inputs, batch, options) does, N times concurrently.
       * The sink defect in this same class was the identical mistake and was fixed by resolving the
       * sink per session; that move is not available for listeners, because they are the caller's
       * objects and the caller expects to read them afterwards.
       */
      final CountingListener listener = new CountingListener();
      final SessionOptions options = SessionOptions.builder().listener(listener).build();
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("fires")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.emit("hit"))
          .build()));

      try (RuleSession first = rules.newSession(options);
          RuleSession second = rules.newSession(options)) {
        first.insert("Order", Facts.obj("total", 1));
        second.insert("Order", Facts.obj("total", 1));
        first.fireAllRules();
        second.fireAllRules();
      }

      // Two sessions, one listener object, both sets of firings landing in it. Not a bug in itself
      // -- it is what a caller collecting a trace wants -- but it is the fact that makes an
      // unsynchronised listener a data race the moment those sessions run concurrently.
      assertThat(listener.count).isEqualTo(2);
    }

    /** Counts firings, to show one listener instance serving two sessions. */
    private static final class CountingListener implements RuleEngineListener {

      private int count;

      @Override
      public void onAfterFire(final FireRecord record) {
        count++;
      }
    }

    @Test
    @DisplayName("options hold no sink instance, which is the property that was broken")
    void optionsHoldNoSinkByDefault() {
      // This is the regression guard, and it has to be mechanism-level. The two tests that used to
      // stand here asserted on FireResult.emitted(), which is sourced from the firing records and
      // therefore cannot observe sink identity or a sink race at all -- they passed just as happily
      // against the shared-sink bug they were written for.
      //
      // The default sink is now stateless, so "one instance shared by every session" is no longer a
      // hazard even if it happened. A stateful default reappearing here is the thing to catch.
      assertThat(SessionOptions.defaults().events())
          .describedAs("a stateful default held in options is shared by every session built "
              + "from them, which is exactly the defect this pins")
          .isEmpty();
    }

    @Test
    @DisplayName("the default sink is stateless, so sharing it cannot lose or mix events")
    void defaultSinkIsStateless() {
      final EventSink first = EventSink.discarding();
      final EventSink second = EventSink.discarding();

      assertThat(first).isSameAs(second);
      assertThat(first.getClass().getDeclaredFields())
          .describedAs("a stateless sink is safe to share; a field here would end that")
          .allMatch(field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()));
    }

    @Test
    @DisplayName("a long run does not retain every event it ever emitted")
    void eventsAreNotAccumulatedForever() {
      // The old default collected into a list nothing ever read, retaining every event for the life
      // of the session -- an unbounded growth surface in a long-lived session, and one §4.4's table
      // of growth surfaces does not list.
      final RuleDefinition rule = Rules.rule("emit-per-order")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        for (int order = 0; order < 500; order++) {
          session.insert("Order", Facts.obj("id", order, "status", "PENDING"));
        }
        final FireResult result = session.fireAllRules();

        // Every event is still reported, because the result reads the firing records.
        assertThat(result.emitted()).hasSize(500);
        // ...and a second fire call reports only its own, retaining nothing from the first.
        session.insert("Order", Facts.obj("id", 500, "status", "PENDING"));
        assertThat(session.fireAllRules().emitted()).hasSize(1);
      }
    }

    @Test
    @DisplayName("concurrent sessions do not lose or cross-contaminate events")
    void concurrentSessionsReportIndependently() {
      // Retained from the original pair. It does not observe the sink -- see above -- but it is
      // still worth having: it proves the record-sourced result is correct under concurrency.
      final RuleDefinition rule = Rules.rule("alert")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
          .build();
      final CompiledRuleSet ruleSet = Engine.compile(rule);
      final SessionOptions shared = SessionOptions.defaults();

      final List<FireResult> results;
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        results = IntStream.range(0, 400)
            .mapToObj(index -> executor.submit(() -> Engine.result(ruleSet, shared,
                session -> session.insert("Order",
                    Facts.obj("id", index, "status", "PENDING")))))
            .toList()
            .stream()
            .map(future -> {
              try {
                return future.get();
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              } catch (final java.util.concurrent.ExecutionException failed) {
                throw new IllegalStateException(failed.getCause());
              }
            })
            .toList();
      }

      assertThat(results).hasSize(400).allSatisfy(result ->
          assertThat(result.emitted()).hasSize(1));
    }

    @Test
    @DisplayName("an explicitly supplied sink is still honoured, and still shared by design")
    void explicitSinkIsRespected() {
      final CollectingEventSink mine = new CollectingEventSink();
      final EventSink sink = mine;
      final RuleDefinition rule = Rules.rule("alert")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("alert", "id", Rules.ref("o.id")))
          .build();
      final SessionOptions options = SessionOptions.builder().events(sink).build();

      for (int run = 0; run < 3; run++) {
        Engine.result(Engine.compile(rule), options,
            session -> session.insert("Order", Facts.obj("id", 1, "status", "PENDING")));
      }

      // Three sessions, one sink, three events. That accumulation is the caller's choice and their
      // responsibility to make thread-safe -- the contrast with the stateless default is the point.
      assertThat(mine.collected()).hasSize(3);
    }
  }

  @Nested
  @DisplayName("staging cancels work that the same firing makes pointless")
  class StagingCancellation {

    @Test
    @DisplayName("setField followed by retract does not propagate a doomed update")
    void updateThenRetractCancelsTheUpdate() {
      final RuleDefinition rule = Rules.rule("purge")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "DOOMED")
              .retractFact("o"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        // Only the retract landed. Before the fix this ran a full retract-and-reassert first:
        // a recency bump, per-rule refraction invalidation and a listener burst, on a fact that
        // was deleted microseconds later in the same commit.
        assertThat(result.fired().getFirst().effects())
            .singleElement()
            .isInstanceOf(StagedEffect.FactRetracted.class);
        assertThat(session.workingMemory().size()).isZero();
      }
    }

    @Test
    @DisplayName("setField on a fact this firing inserted merges into the insert")
    void setFieldOnAStagedInsertMerges() {
      final RuleDefinition rule = Rules.rule("signal")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFactAs("Alert", "a", "level", "LOW")
              .setField("a", "level", "HIGH"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.fireAllRules();

        final var alert = session.workingMemory().factsOfType("Alert").toList();
        assertThat(alert).singleElement().satisfies(fact -> {
          assertThat(fact.payload().get("level").stringValue()).isEqualTo("HIGH");
          // Inserted once, at one recency. Committing this as insert-then-update would make the
          // fact briefly visible carrying LOW and bump its recency a second time, reordering
          // conflict resolution against every other fact for no reason an author could predict.
          assertThat(fact.recency()).isEqualTo(2L);
        });
      }
    }

    @Test
    @DisplayName("a $ref to a fact this firing inserted sees the value that will actually land")
    void referenceToAStagedInsertSeesMergedFields() {
      // A judgement call, pinned deliberately. §4.6's general rule is that an action cannot read an
      // earlier action's result -- but §4.6 already carves staged inserts out of it so that later
      // actions can name them, and reading the pending payload was always supported.
      //
      // The question is only whether a setField on that alias is visible to a later $ref. It is,
      // and the alternative is worse: before the fields were merged, the reference resolved to the
      // insert's original value while the fact committed with the updated one, so the emitted event
      // carried a value no fact ever held. Reading what will actually land is the honest answer.
      final RuleDefinition rule = Rules.rule("signal")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFactAs("Alert", "a", "level", "LOW")
              .setField("a", "level", "HIGH")
              .emit("raised", "level", Rules.ref("a.level")))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        assertThat(result.emitted()).singleElement()
            .extracting(event -> event.payload().get("level").stringValue())
            .isEqualTo("HIGH");
        assertThat(session.workingMemory().factsOfType("Alert")).singleElement()
            .extracting(fact -> fact.payload().get("level").stringValue())
            .isEqualTo("HIGH");

        // One working-memory effect, because one thing landed: an insert. A FieldSet alongside it
        // would read as though the field were written twice.
        assertThat(result.fired().getFirst().effects())
            .filteredOn(effect -> effect instanceof StagedEffect.FieldSet)
            .isEmpty();
      }
    }

    @Test
    @DisplayName("an LHS-bound fact is still NOT readable through its own setField")
    void deferredCommitStillHoldsForBoundFacts() {
      // The merge applies only to facts this firing created. The general rule is untouched for
      // facts the left-hand side bound, which is the case §4.6 is actually about.
      final RuleDefinition rule = Rules.rule("set-then-emit")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "status", "REVIEW")
              .emit("decision", "status", Rules.ref("o.status")))
          .build();

      assertThat(Engine.result(Engine.compile(rule), SessionOptions.defaults(),
          session -> session.insert("Order", Facts.obj("status", "PENDING")))
          .emitted()).singleElement()
          .extracting(event -> event.payload().get("status").stringValue())
          .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("retracting the same handle twice records one effect, not two")
    void doubleRetractIsRecordedOnce() {
      final RuleDefinition rule = Rules.rule("twice")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.retractFact("o").retractFact("o"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        final FireResult result = session.fireAllRules();

        // The second retract was always a silent no-op against working memory, but the record is
        // the audit log and must not claim work the engine did not do.
        assertThat(result.fired().getFirst().effects()).hasSize(1);
      }
    }
  }

  @Nested
  @DisplayName("regressions from the review fixes themselves")
  class FixRegressions {

    @Test
    @DisplayName("a listener that retracts during onRetract terminates instead of recursing")
    void reentrantRetractIsGuarded() {
      // Dispatching factRetracted before removal is what §3.4.1 step 3 requires, but it removed the
      // accidental guard that the removal itself used to provide: the fact is still installed when
      // the callback runs, so a listener that retracts it again re-enters forever. The recursion is
      // blocked explicitly rather than by reverting the ordering.
      //
      // The session cannot be handed to the listener at construction -- the listener goes into the
      // options that build the session -- so it is injected afterwards through a holder.
      final java.util.concurrent.atomic.AtomicReference<RuleSession> current =
          new java.util.concurrent.atomic.AtomicReference<>();
      final List<Long> retracted = new ArrayList<>();

      final RuleDefinition rule = Rules.rule("noop")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions.emit("seen"))
          .build();

      final SessionOptions options = SessionOptions.builder()
          .listener(new RuleEngineListener() {
            @Override
            public void onRetract(final com.codeheadsystems.rules.fact.Fact fact) {
              retracted.add(fact.handle().id());
              current.get().retract(fact.handle());
            }
          })
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        current.set(session);
        final var handle = session.insert("Order", Facts.obj("status", "PENDING"));

        assertThatCode(() -> session.retract(handle))
            .describedAs("a re-entrant retract must terminate, not blow the stack")
            .doesNotThrowAnyException();

        // Dispatched exactly once: the re-entrant call short-circuits rather than recursing.
        assertThat(retracted).containsExactly(handle.id());
        assertThat(session.workingMemory().size()).isZero();
      }
    }

    @Test
    @DisplayName("notRun names the sibling field sets that a failed update discarded")
    void notRunCoversMergedSiblings() {
      // Several field sets on one handle merge into one update applied in a single call, so when
      // one throws none of them land. A record naming only the thrower tells the reader the others
      // succeeded.
      final RuleDefinition rule = Rules.rule("three-sets")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .setField("o", "a", 1)
              .setField("o", "meta.x", 1)
              .setField("o", "c", 3))
          .build();

      final var options = SessionOptions.builder()
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.ABORT_SESSION)
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        final var handle = session.insert("Order",
            Facts.obj("status", "PENDING", "meta", "not-an-object"));
        final FireResult result = session.fireAllRules();

        final FireRecord record = result.fired().getFirst();
        assertThat(record.effects()).isEmpty();
        assertThat(record.failedAction()).isPresent();
        assertThat(record.notRun())
            .describedAs("the two sibling field sets did not land either")
            .hasSize(2);
        // And nothing landed, so the record's claim is true.
        final var payload = session.workingMemory().get(handle).orElseThrow().payload();
        assertThat(payload.has("a")).isFalse();
        assertThat(payload.has("c")).isFalse();
      }
    }

    @Test
    @DisplayName("a staging failure hands back the handles it had already reserved")
    void stagingFailureReleasesReservations() {
      final RuleDefinition rule = Rules.rule("leaky")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFactAs("Alert", "x", "level", "LOW")
              .retractFact("o")
              .setField("o", "boom", true))
          .build();

      final var options = SessionOptions.builder()
          .onRhsError((activation, failed, cause) -> RhsErrorHandler.Decision.SKIP_ACTIVATION)
          .build();

      try (RuleSession session = Engine.compile(rule).newSession(options)) {
        for (int order = 0; order < 5; order++) {
          session.insert("Order", Facts.obj("id", order, "status", "PENDING"));
        }
        session.fireAllRules();

        // Five firings, five abandoned inserts, and no reservations left behind. This is the path
        // that actually accumulates under a skip-and-continue policy.
        assertThat(((com.codeheadsystems.rules.fact.DefaultWorkingMemory) session.workingMemory())
            .reservedHandleCount()).isZero();
      }
    }

    @Test
    @DisplayName("a cancelled insert and a dry run also hand their reservations back")
    void cancelAndDryRunReleaseReservations() {
      final RuleDefinition rule = Rules.rule("churn")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .then(actions -> actions
              .insertFactAs("Alert", "x", "level", "LOW")
              .retractFact("x"))
          .build();

      try (RuleSession session = Engine.compile(rule).newSession()) {
        session.insert("Order", Facts.obj("status", "PENDING"));
        session.fireAllRules();
        assertThat(((com.codeheadsystems.rules.fact.DefaultWorkingMemory) session.workingMemory())
            .reservedHandleCount()).isZero();
      }

      try (RuleSession dry = Engine.compile(rule)
          .newSession(SessionOptions.builder().dryRun(true).build())) {
        dry.insert("Order", Facts.obj("status", "PENDING"));
        dry.fireAllRules();
        assertThat(((com.codeheadsystems.rules.fact.DefaultWorkingMemory) dry.workingMemory())
            .reservedHandleCount()).isZero();
      }
    }
  }

  @Nested
  @DisplayName("noLoop suppression is observable")
  class NoLoopReporting {

    @Test
    @DisplayName("holding a match back for noLoop is reported to listeners")
    void noLoopIsDispatched() {
      // The callback and the enum constant existed but nothing ever dispatched them, so the one
      // suppression this agenda shape can actually observe was invisible.
      final RuleDefinition rule = Rules.rule("discount").noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.setField("o", "total", 50))
          .build();

      final List<ActivationKey> suppressed = new ArrayList<>();
      final SessionOptions options = SessionOptions.builder()
          .listener(new RuleEngineListener() {
            @Override
            public void onActivationSuppressed(final ActivationKey key, final SuppressReason why) {
              if (why == SuppressReason.NO_LOOP) {
                suppressed.add(key);
              }
            }
          })
          .build();

      final FireResult result = Engine.result(Engine.compile(rule), options,
          session -> session.insert("Order", Facts.obj("total", 100)));

      assertThat(result.firedCount()).isEqualTo(1);
      assertThat(suppressed).singleElement()
          .extracting(ActivationKey::ruleId).isEqualTo("discount");
    }
  }

  @Nested
  @DisplayName("a field path the compiler cannot parse")
  class MalformedFieldPath {

    /*
     * Found reviewing the Phase 5 DSL. compileField, compileRange and compileJoin all called
     * Paths.compile without a guard, and Paths.compile throws IllegalArgumentException on an empty
     * segment. So `a..b` -- a one-character typo -- escaped as a raw IllegalArgumentException
     * instead of becoming a diagnostic.
     *
     * It matters most through the DSL, where it defeated both of that module's headline promises:
     * every diagnostic names a file, line and column, and every problem is reported in one pass.
     * The guard belongs in the compiler rather than in that front end because a rule built in Java
     * reached the same throw -- these four tests build their rules with {@code Rules} and never
     * touch a rule file.
     *
     * <p>That is true of CONSTRAINT paths, which is what these tests cover, and deliberately not of
     * ACTION paths. {@code SetField.of}, {@code PayloadField.of} and {@code FieldRef.of} still throw
     * {@link IllegalArgumentException} on a malformed path, from the builder, before the compiler
     * runs at all. Left alone on purpose: those are static factories, and failing at the call site
     * that got the argument wrong is what a Java caller wants -- the same contract
     * {@code RangeConstraint}'s no-bounds check already keeps. The DSL never routes through them;
     * {@code Actions} builds those records from an already-guarded pointer.
     */

    @Test
    @DisplayName("is a diagnostic, not an IllegalArgumentException, on a single-fact constraint")
    void alphaConstraint() {
      final RuleDefinition rule = Rules.rule("bad-alpha")
          .when("o", "Order", pattern -> pattern.eq("a..b", 1))
          .then(actions -> actions.emit("e"))
          .build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("empty path segment");
    }

    @Test
    @DisplayName("is a diagnostic on a range constraint too")
    void rangeConstraint() {
      final RuleDefinition rule = Rules.rule("bad-range")
          .when("o", "Order", pattern -> pattern.gt("a..b", 1))
          .then(actions -> actions.emit("e"))
          .build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("empty path segment");
    }

    @Test
    @DisplayName("is a diagnostic on either side of a join")
    void joinConstraint() {
      final RuleDefinition nearSide = Rules.rule("bad-join-near")
          .when("o", "Order")
          .when("c", "Customer", pattern -> pattern.ref("a..b", "o.id"))
          .then(actions -> actions.emit("e"))
          .build();
      final RuleDefinition farSide = Rules.rule("bad-join-far")
          .when("o", "Order")
          .when("c", "Customer", pattern -> pattern.ref("id", "o.a..b"))
          .then(actions -> actions.emit("e"))
          .build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(nearSide)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("empty path segment");
      assertThatThrownBy(() -> RuleCompiler.compile(List.of(farSide)))
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("empty path segment");
    }

    @Test
    @DisplayName("reports every bad path in one pass rather than dying on the first")
    void everyBadPathReported() {
      final RuleDefinition rule = Rules.rule("several-bad")
          .when("o", "Order", pattern -> pattern.eq("a..b", 1).gt("c..d", 2))
          .then(actions -> actions.emit("e"))
          .build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .isInstanceOf(RuleCompilationException.class)
          .satisfies(thrown -> assertThat(
              ((RuleCompilationException) thrown).diagnostics()).hasSize(2));
    }
  }
}
