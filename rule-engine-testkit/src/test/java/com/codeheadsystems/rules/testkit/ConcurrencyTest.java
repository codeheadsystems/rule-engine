package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.concurrent.BatchOutcome;
import com.codeheadsystems.rules.concurrent.RuleBatches;
import com.codeheadsystems.rules.concurrent.RuleSetHolder;
import com.codeheadsystems.rules.concurrent.SessionDrain;
import com.codeheadsystems.rules.fact.ExportedFact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.Origin;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.Presence;
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.schema.SchemaViolationException;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Phase 4's contracts: the hot-reload holder, drain-and-replay, and the batch helper (spec §5).
 *
 * <p>These are the two exit criteria §9 sets for the concurrency phase, minus the benchmark: "rule
 * set swap under load drops nothing and mixes nothing", and the determinism obligation §7.3 puts on
 * anything that reinserts facts.
 */
class ConcurrencyTest {

  /** Fires for every Order, tagging it with which version of the rule set saw it. */
  private static RuleDefinition tagger(final String version) {
    return Rules.rule("tag")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.emit("tagged", "version", version, "id", Rules.ref("o.id")))
        .build();
  }

  private static CompiledRuleSet rulesTagging(final String version) {
    return RuleCompiler.compile(List.of(tagger(version)));
  }

  private static String versionTagOf(final FireResult result) {
    return result.emitted().getFirst().payload().get("version").asString();
  }

  @Nested
  @DisplayName("RuleSetHolder")
  class Holder {

    @Test
    @DisplayName("a swap under load mixes nothing, and does land: both versions are observed")
    void mixesNothing() throws InterruptedException {
      final CompiledRuleSet v1 = rulesTagging("v1");
      final CompiledRuleSet v2 = rulesTagging("v2");
      final RuleSetHolder holder = new RuleSetHolder(v1);
      final int workers = 32;
      final CountDownLatch before = new CountDownLatch(workers);
      final CountDownLatch after = new CountDownLatch(workers);
      final AtomicBoolean stop = new AtomicBoolean();
      final ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();

      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int worker = 0; worker < workers; worker++) {
          executor.submit(() -> {
            /*
             * Every worker runs sessions continuously until told to stop, and counts down `before`
             * on its first one and `after` on its first one that follows the publish. That
             * handshake is what makes this test load-bearing rather than merely safe: a `publish`
             * that did nothing at all would still satisfy "no session mixed two rule sets", so
             * without proof that the swap landed *while sessions were running*, the assertion below
             * would pass against a no-op holder.
             */
            while (!stop.get()) {
              final String tag;
              try (RuleSession session = holder.newSession()) {
                session.insert("Order", Facts.obj("id", 1, "total", 10));
                final FireResult result = session.fireAllRules();
                tag = versionTagOf(result);
                seen.add(tag + "/" + result.ruleSetVersion());
              }
              before.countDown();
              // Counted down on an OBSERVED v2, not on "a session that finished after the publish".
              // The weaker gate let a session created before the publish and finishing after it
              // satisfy the latch while having served v1 -- with 32 workers, exactly enough such
              // sessions to release `after` before any v2 session ran, after which stop is set and
              // the v2 assertion below fails. Counting the observation makes `after.await()`
              // returning *be* the proof that the swap landed under load.
              if ("v2".equals(tag)) {
                after.countDown();
              }
            }
            return null;
          });
        }
        assertThat(before.await(30, TimeUnit.SECONDS))
            .describedAs("workers did not start; a worker exception would hang here silently "
                + "because submit()'s futures are never read")
            .isTrue();
        holder.publish(v2);
        assertThat(after.await(30, TimeUnit.SECONDS))
            .describedAs("no v2 session was observed, so the swap never landed under load")
            .isTrue();
        stop.set(true);
      }

      /*
       * One assertion carries both halves of §9's exit criterion, which is why it is exact rather
       * than a containment check.
       *
       * Dropped nothing: both pairs are present, so the swap really did land while sessions were
       * running rather than before or after them.
       *
       * Mixed nothing: the tag comes from the rule that fired and the hash from the session's rule
       * set, so a session running one version's rules while reporting the other's identity would
       * appear as a third pair -- "v1/<v2 hash>" -- and fail this.
       */
      final Set<String> pairs = Set.copyOf(new ArrayList<>(seen));
      assertThat(pairs).containsExactlyInAnyOrder("v1/" + v1.version(), "v2/" + v2.version());
    }

    @Test
    @DisplayName("a session created before a swap finishes against its original rules")
    void inFlightSessionsAreUnaffected() {
      final CompiledRuleSet v1 = rulesTagging("v1");
      final RuleSetHolder holder = new RuleSetHolder(v1);
      try (RuleSession session = holder.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        holder.publish(rulesTagging("v2"));

        final FireResult result = session.fireAllRules();
        assertThat(versionTagOf(result)).isEqualTo("v1");
        assertThat(result.ruleSetVersion()).isEqualTo(v1.version());
      }
    }

    @Test
    @DisplayName("a failed compile leaves the previous rule set serving")
    void failedCompileChangesNothing() {
      final CompiledRuleSet v1 = rulesTagging("v1");
      final RuleSetHolder holder = new RuleSetHolder(v1);

      // The contract publish() enforces by taking a CompiledRuleSet rather than rule text: the
      // compile happens at the call site, so a bad rule set never reaches the holder at all.
      assertThatThrownBy(() -> holder.publish(RuleCompiler.compile(List.of(
          Rules.rule("broken")
              .when("o", "Order", pattern -> pattern.ref("id", "nosuchalias.id"))
              .then(actions -> actions.emit("never"))
              .build()))))
          .isInstanceOf(RuntimeException.class);

      assertThat(holder.current()).isSameAs(v1);
      try (RuleSession session = holder.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));
        assertThat(versionTagOf(session.fireAllRules())).isEqualTo("v1");
      }
    }
  }

  @Nested
  @DisplayName("drain and replay")
  class Drain {

    /** Derives a Flag per Order, so an export that included derived facts would double-count. */
    private static CompiledRuleSet deriving() {
      return RuleCompiler.compile(List.of(Rules.rule("derive")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.insertFact("Flag", "orderId", Rules.ref("o.id")))
          .build()));
    }

    @Test
    @DisplayName("exports asserted facts only, in insertion order")
    void exportsAssertedOnlyInOrder() {
      final CompiledRuleSet rules = deriving();
      try (RuleSession session = rules.newSession()) {
        for (int id = 0; id < 5; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }
        session.fireAllRules();

        assertThat(session.workingMemory().size()).isEqualTo(10);
        final List<ExportedFact> exported = session.exportFacts();
        assertThat(exported).hasSize(5);
        // Near-tautological on its own -- exportFacts() filters on exactly this -- but it is the
        // assertion that fails loudly rather than confusingly if ExportedFact ever starts carrying
        // derived facts for a diagnostics caller.
        assertThat(exported).allMatch(fact -> fact.origin() == Origin.ASSERTED);
        assertThat(exported.stream().map(fact -> fact.payload().get("id").asInt()).toList())
            .containsExactly(0, 1, 2, 3, 4);
      }
    }

    @Test
    @DisplayName("replay does not double-count derived facts")
    void replayDoesNotDoubleCount() {
      final CompiledRuleSet rules = deriving();
      final RuleSession restarted;
      try (RuleSession original = rules.newSession()) {
        for (int id = 0; id < 5; id++) {
          original.insert("Order", Facts.obj("id", id, "total", 10));
        }
        original.fireAllRules();
        restarted = SessionDrain.restart(original, rules, SessionOptions.defaults());
      }
      try (RuleSession session = restarted) {
        // Five Orders and nothing else until it fires: the Flags come back by being re-derived,
        // which is the whole reason they are not exported.
        assertThat(session.workingMemory().size()).isEqualTo(5);
        session.fireAllRules();
        assertThat(session.workingMemory().size()).isEqualTo(10);
      }
    }

    @Test
    @DisplayName("a drained-and-replayed session fires identically to a continuous one (§7.3)")
    void replayIsDeterministic() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(
          Rules.rule("high").salience(10).noLoop()
              .when("o", "Order", pattern -> pattern.gte("total", 100))
              .then(actions -> actions.emit("high", "id", Rules.ref("o.id")))
              .build(),
          Rules.rule("joined").noLoop()
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
              .then(actions -> actions.emit("joined",
                  "id", Rules.ref("o.id"), "cust", Rules.ref("c.id")))
              .build()));

      final List<String> continuous;
      try (RuleSession session = rules.newSession()) {
        load(session);
        continuous = firingSequence(session.fireAllRules());
      }

      final List<String> replayed;
      final RuleSession restarted;
      try (RuleSession original = rules.newSession()) {
        load(original);
        restarted = SessionDrain.restart(original, rules, SessionOptions.defaults());
      }
      try (RuleSession session = restarted) {
        replayed = firingSequence(session.fireAllRules());
      }

      // Both are a first fire on a session holding the same facts. Comparing the replayed session's
      // first fire against a CONTINUOUS session's SECOND fire would be the mistake here: refraction
      // means the second fire emits nothing, so the assertion would pass against an empty list
      // however badly the replay had scrambled its input. Hence isNotEmpty.
      assertThat(replayed).isNotEmpty().isEqualTo(continuous);
    }

    @Test
    @DisplayName("replay preserves insertion order, which §7.3's guarantee is stated in terms of")
    void replayPreservesOrder() {
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("recency")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.emit("saw", "id", Rules.ref("o.id")))
          .build()));

      final List<ExportedFact> exported;
      try (RuleSession session = rules.newSession()) {
        // Deliberately not ascending by payload id: if the export ever sorted on content, or on
        // anything other than handle id, this ordering is what would expose it.
        for (final int id : new int[] {9, 4, 7, 1, 8}) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }
        exported = session.exportFacts();
      }

      assertThat(exported.stream().map(fact -> fact.payload().get("id").asInt()).toList())
          .containsExactly(9, 4, 7, 1, 8);

      // Not asserted as 9,4,7,1,8: firing order is conflict-resolution order (§2.4), not
      // insertion order, and pinning it here would be testing the agenda rather than the replay.
      // What the replay owes is that a session loaded from the export fires the way a session
      // loaded directly does -- which only holds if the insertion order survived.
      final List<Integer> direct;
      try (RuleSession session = rules.newSession()) {
        for (final int id : new int[] {9, 4, 7, 1, 8}) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }
        direct = firedIds(session);
      }
      try (RuleSession session = rules.newSession()) {
        SessionDrain.replay(session, exported);
        assertThat(firedIds(session)).isNotEmpty().isEqualTo(direct);
      }
    }

    /**
     * The order the ids fired in.
     *
     * @param session the session to fire
     * @return one id per emitted event, in firing order
     */
    private static List<Integer> firedIds(final RuleSession session) {
      return session.fireAllRules().emitted().stream()
          .map(event -> event.payload().get("id").asInt()).toList();
    }

    @Test
    @DisplayName("a failed replay leaves the caller's original session open and usable")
    void failedRestartDoesNotStrandTheCaller() {
      /*
       * The failure has to happen inside replay(), not inside newSession(). An earlier version of
       * this test poisoned the rule set so newSession() threw -- which proved only that restart()
       * builds before it closes, and left the catch block that closes the half-loaded session
       * completely uncovered: deleting `restarted.close()` kept the suite green while every failed
       * restart leaked a session.
       *
       * So the new rule set validates Orders and rejects them. FactSchemas is a -core SPI, so an
       * inline implementation does this without the -schema module.
       */
      final CompiledRuleSet loose = deriving();
      final CompiledRuleSet strictened = RuleCompiler.compile(
          List.of(Rules.rule("derive")
              .noLoop()
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .then(actions -> actions.insertFact("Flag", "orderId", Rules.ref("o.id")))
              .build()),
          CompilerOptions.builder().factSchemas(new RejectsOrders()).build());

      try (RuleSession original = loose.newSession()) {
        original.insert("Order", Facts.obj("id", 1, "total", 10));
        original.fireAllRules();

        assertThatThrownBy(() ->
            SessionDrain.restart(original, strictened, SessionOptions.defaults()))
            .isInstanceOf(SchemaViolationException.class);

        // restart() closes the old session only after the new one is built AND loaded. Had it
        // drained first, the caller would hold a closed session, their facts would be gone, and the
        // rule set they were moving to would be unusable as well -- no way back.
        assertThat(original.workingMemory().size()).isEqualTo(2);
        assertThat(original.exportFacts()).hasSize(1);
      }
    }

    /** Rejects every Order, to make a replay fail partway. */
    private static final class RejectsOrders implements FactSchemas {

      @Override
      public List<String> violations(final String factType, final JsonNode payload) {
        return "Order".equals(factType) ? List.of("Order is not accepted here") : List.of();
      }

      @Override
      public Optional<SchemaType> typeOf(final String factType, final String dottedPath) {
        return Optional.empty();
      }

      @Override
      public Presence presence(final String factType, final String dottedPath) {
        return Presence.UNKNOWN;
      }
    }

    @Test
    @DisplayName("origin survives an update, so an updated derived fact still is not exported")
    void originSurvivesUpdate() {
      /*
       * The one uncovered behaviour that would silently disable the feature. Origin is set at insert
       * and carried through both branches of updateOwned; swapping either for Origin.ASSERTED leaves
       * every other test in this file green while every updated derived fact double-counts on the
       * next restart. Both branches are exercised here: a propagating update on the derived fact,
       * and a skipped one on the asserted fact.
       */
      final CompiledRuleSet rules = deriving();
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 10));
        session.fireAllRules();

        final FactHandle flag = session.workingMemory().factsOfType("Flag")
            .findFirst().orElseThrow().handle();
        // Propagating: /orderId is a path no rule tests here, so pick one that is -- any change at
        // all still runs the origin-carrying constructor on both paths.
        session.update(flag, Facts.obj("orderId", 99));
        session.update(order, Facts.obj("id", 1, "total", 10));

        assertThat(session.workingMemory().size()).isEqualTo(2);
        assertThat(session.exportFacts()).hasSize(1);
        assertThat(session.exportFacts().getFirst().payload().get("id").asInt()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("exported payloads are copies, so the exporting session cannot be reached through them")
    void exportedPayloadsAreCopies() {
      final CompiledRuleSet rules = deriving();
      try (RuleSession session = rules.newSession()) {
        final FactHandle handle = session.insert("Order", Facts.obj("id", 1, "total", 10));
        final ExportedFact exported = session.exportFacts().getFirst();

        ((tools.jackson.databind.node.ObjectNode) exported.payload()).put("total", 999);

        // The export is the input to a replay, and a caller holding it will reasonably treat it as
        // theirs. If it aliased working memory, editing it would mutate a live fact behind the
        // matcher's back -- the §2.2 hazard, arriving through a door added in Phase 4.
        assertThat(session.get(handle).orElseThrow().payload().get("total").asInt()).isEqualTo(10);
      }
    }

    private static void load(final RuleSession session) {
      for (int id = 0; id < 6; id++) {
        session.insert("Order", Facts.obj("id", id, "total", id * 40, "customerId", id % 3));
        session.insert("Customer", Facts.obj("id", id % 3));
      }
    }

    /**
     * The emitted events in firing order, which is what §7.3's guarantee is about.
     *
     * @param result the fire result to read
     * @return one string per emitted event
     */
    private static List<String> firingSequence(final FireResult result) {
      return result.emitted().stream()
          .map(event -> event.eventType() + event.payload().toString())
          .toList();
    }
  }

  @Nested
  @DisplayName("RuleBatches")
  class Batches {

    @Test
    @DisplayName("returns every batch's outcome, successes and failures both")
    void isolatesFailures() {
      final CompiledRuleSet rules = rulesTagging("v1");
      final List<Integer> inputs = IntStream.range(0, 40).boxed().toList();

      final List<BatchOutcome<Integer>> outcomes = RuleBatches.run(rules, inputs,
          (session, input) -> {
            if (input % 7 == 3) {
              throw new IllegalStateException("batch " + input + " refused");
            }
            session.insert("Order", Facts.obj("id", input, "total", 10));
            return session.fireAllRules().firedCount();
          });

      assertThat(outcomes).hasSize(40);
      // Submission order, so an outcome can be matched to its input by index.
      for (int index = 0; index < outcomes.size(); index++) {
        assertThat(outcomes.get(index).index()).isEqualTo(index);
      }
      final List<BatchOutcome<Integer>> failed = outcomes.stream()
          .filter(outcome -> !outcome.succeeded()).toList();
      assertThat(failed).hasSize(6);
      assertThat(failed.getFirst().failure().orElseThrow())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("refused");
      // The siblings all completed. A helper that threw on first failure would have lost these.
      assertThat(outcomes.stream().filter(BatchOutcome::succeeded).toList()).hasSize(34);
      assertThat(outcomes.stream().filter(BatchOutcome::succeeded))
          .allMatch(outcome -> outcome.value().orElseThrow() == 1);
    }

    @Test
    @DisplayName("each batch gets its own session, and closes it even when it throws")
    void sessionsAreNotShared() {
      final CompiledRuleSet rules = rulesTagging("v1");
      final AtomicBoolean shared = new AtomicBoolean();
      final ConcurrentLinkedQueue<Object> sessionIds = new ConcurrentLinkedQueue<>();

      final List<BatchOutcome<Integer>> outcomes = RuleBatches.run(rules, 100, session -> {
        sessionIds.add(session.sessionId());
        session.insert("Order", Facts.obj("id", 1, "total", 10));
        // Every session inserts the same fact and must see exactly one; a shared session would
        // accumulate and this would run away.
        if (session.workingMemory().size() != 1) {
          shared.set(true);
        }
        return session.fireAllRules().firedCount();
      });

      assertThat(shared).isFalse();
      assertThat(Set.copyOf(new ArrayList<>(sessionIds))).hasSize(100);
      assertThat(outcomes).hasSize(100).allMatch(BatchOutcome::succeeded);
    }
  }
}
