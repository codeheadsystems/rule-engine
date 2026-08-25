package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.concurrent.ActorOptions;
import com.codeheadsystems.rules.concurrent.SessionActor;
import com.codeheadsystems.rules.concurrent.SessionDrain;
import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.evict.EvictionView;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.rhs.StagedEffect;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.session.SessionStats;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Session fact-eviction (spec §4.4), and §9's steady-state exit criterion for Phase 3.
 *
 * <p>The mechanism is one sentence -- evicting a fact runs the full retract path -- and almost
 * everything worth testing is a consequence of it: the cascade into every memory keyed on handles,
 * the absence of a phantom match afterwards, and the fact that the three matchers stay
 * indistinguishable while it happens.
 */
class EvictionTest {

  /** A join, so that eviction has beta memory to cascade into under the streaming matcher. */
  private static List<RuleDefinition> joinRules() {
    return List.of(Rules.rule("review")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("review",
            "orderId", Rules.ref("o.id"), "customerId", Rules.ref("c.id")))
        .build());
  }

  private static List<RuleDefinition> singleFactRules() {
    return List.of(Rules.rule("seen")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
        .build());
  }

  private static SessionOptions.Builder streaming(final EvictionPolicy policy) {
    return SessionOptions.builder().matching(MatchingStrategy.RETE).eviction(policy);
  }

  @Nested
  @DisplayName("the cap is what it says it is")
  class Bounds {

    @Test
    @DisplayName("working memory never exceeds a total cap, however many facts arrive")
    void totalCapHolds() {
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(10)).build())) {
        for (int id = 0; id < 500; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
          assertThat(session.workingMemory().size())
              .describedAs("after inserting order %d", id)
              .isLessThanOrEqualTo(10);
        }
        assertThat(session.stats().evictedCount()).isEqualTo(490L);
      }
    }

    @Test
    @DisplayName("the survivors are the newest, and the evicted are gone")
    void survivorsAreTheNewest() {
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(3)).build())) {
        final List<FactHandle> handles = new ArrayList<>();
        for (int id = 0; id < 5; id++) {
          handles.add(session.insert("Order", Facts.obj("id", id, "total", 10)));
        }

        assertThat(session.get(handles.get(0))).isEmpty();
        assertThat(session.get(handles.get(1))).isEmpty();
        assertThat(session.get(handles.get(2))).isPresent();
        assertThat(session.get(handles.get(4))).isPresent();
      }
    }

    @Test
    @DisplayName("a per-type cap keeps the reference data a total cap would have eaten")
    void perTypeCapKeepsReferenceData() {
      /*
       * The case §4.4's amendment exists for. Customers are loaded first, so they hold the lowest
       * recency in the session; a total cap would evict them and keep the orders that were supposed
       * to be the bounded population. The rule needs its customers to fire at all, so this asserts
       * on firings rather than on counts.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(joinRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.perType(Map.of("Order", 5))).build())) {
        for (int id = 0; id < 3; id++) {
          session.insert("Customer", Facts.obj("id", id));
        }
        for (int id = 0; id < 100; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", id % 3));
        }

        assertThat(session.workingMemory().factsOfType("Customer").count())
            .describedAs("uncapped types are untouched")
            .isEqualTo(3L);
        assertThat(session.workingMemory().factsOfType("Order").count()).isEqualTo(5L);
        assertThat(session.fireAllRules().firedCount())
            .describedAs("five surviving orders, each matching its customer")
            .isEqualTo(5);
      }
    }
  }

  @Nested
  @DisplayName("eviction is a retract, with everything that follows from it")
  class FullRetractPath {

    @Test
    @DisplayName("an evicted fact leaves no match behind")
    void noPhantomMatch() {
      /*
       * The failure mode a hand-written removal path produces, and the reason §4.4 insists eviction
       * run the ordinary retract. Under the streaming matcher the join is materialised, so a fact
       * removed from working memory without its matches would keep firing against a fact that no
       * longer exists.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(joinRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.perType(Map.of("Order", 2))).build())) {
        session.insert("Customer", Facts.obj("id", 1));
        for (int id = 0; id < 6; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 1));
        }

        assertThat(session.fireAllRules().firedCount())
            .describedAs("only the two surviving orders may fire")
            .isEqualTo(2);
        final SessionStats stats = session.stats();
        assertThat(stats.materialisedMatchCount())
            .describedAs("four evicted orders' matches are gone from the beta memory")
            .isEqualTo(2);
        assertThat(stats.materialisedHandleCount())
            .describedAs("and so are their reverse-index entries -- two orders plus the customer")
            .isEqualTo(3);
      }
    }

    @Test
    @DisplayName("refraction forgets the evicted fact's matches")
    void refractionIsCascaded() {
      // §4.4's table: the fired-match memory is bounded by retract and per-rule invalidation and by
      // nothing else, so eviction is what bounds it in a session that never retracts.
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(4)).build())) {
        for (int id = 0; id < 4; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }
        session.fireAllRules();
        assertThat(session.stats().refractedMatchCount()).isEqualTo(4);

        for (int id = 4; id < 8; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }

        assertThat(session.stats().refractedMatchCount())
            .describedAs("the four fired matches were evicted with their facts")
            .isZero();
      }
    }

    @Test
    @DisplayName("the count is split by fact type, because the total cannot answer the question")
    void evictionsAreCountedPerType() {
      /*
       * The total says a session let go of 97 facts; it cannot say whether any of them were the
       * type the rule that stopped firing is waiting on. That is the question MatchExplainer asks,
       * and answering it is the difference between "no Order fact exists" -- true, complete, and
       * pointing an author at their rule -- and the same sentence with the reason attached.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(joinRules());
      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.perType(Map.of("Order", 2, "Customer", 1))).build())) {
        for (int id = 0; id < 4; id++) {
          session.insert("Customer", Facts.obj("id", id));
        }
        for (int id = 0; id < 5; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0));
        }

        final SessionStats stats = session.stats();
        assertThat(stats.evictedCount()).isEqualTo(6L);
        assertThat(stats.evictedByType())
            .describedAs("three customers over a cap of one, three orders over a cap of two")
            .containsExactlyInAnyOrderEntriesOf(Map.of("Customer", 3L, "Order", 3L));
      }
    }

    @Test
    @DisplayName("a type nothing was evicted from has no entry at all")
    void untouchedTypesAreAbsent() {
      // Absent rather than zero, so a reader of the map cannot mistake "never lost anything" for
      // "lost nothing recently", and so the map is bounded by types actually evicted from.
      final CompiledRuleSet rules = RuleCompiler.compile(joinRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.perType(Map.of("Order", 2))).build())) {
        session.insert("Customer", Facts.obj("id", 0));
        for (int id = 0; id < 4; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0));
        }

        assertThat(session.stats().evictedByType()).containsOnlyKeys("Order");
      }
    }

    @Test
    @DisplayName("the per-type map is not writable through the stats it came from")
    void perTypeCountsAreNotWritable() {
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(1)).build())) {
        session.insert("Order", Facts.obj("id", 0, "total", 10));
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        final Map<String, Long> counts = session.stats().evictedByType();

        assertThatThrownBy(() -> counts.put("Order", 999L))
            .describedAs("a diagnostic a caller can edit is a diagnostic nobody can trust")
            .isInstanceOf(UnsupportedOperationException.class);
      }
    }

    @Test
    @DisplayName("a listener sees the eviction and the retract it performs")
    void listenerSeesBoth() {
      final List<String> seen = new ArrayList<>();
      final RuleEngineListener listener = new RuleEngineListener() {
        @Override
        public void onEvicted(final Fact fact) {
          seen.add("evicted:" + fact.payload().get("id").asInt());
        }

        @Override
        public void onRetract(final Fact fact) {
          seen.add("retracted:" + fact.payload().get("id").asInt());
        }
      };

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.leastRecentlyUsed(2)).listener(listener).build())) {
        for (int id = 0; id < 3; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }
      }

      assertThat(seen)
          .describedAs("eviction is announced as itself and then performed as a retract")
          .containsExactly("evicted:0", "retracted:0");
    }
  }

  @Nested
  @DisplayName("recency, which is what \"oldest\" means")
  class RecencyOrder {

    /** A rule testing {@code total} and not {@code note}, so one field is tested and one is not. */
    private List<RuleDefinition> testsTotalOnly() {
      return singleFactRules();
    }

    @Test
    @DisplayName("an effective update makes a fact the newest, so it outlives what it arrived with")
    void effectiveUpdateMovesAFactToTheNewestEnd() {
      /*
       * The claim the whole of SessionEvictor's own order exists to make, and the one thing here
       * that is subtle: §3.4.1 bumps a fact's recency when an update changes a tested path, so a
       * fact updated late is no longer the oldest even though it was inserted first. Working memory
       * does not reorder itself on update -- its map re-puts the key in place -- which is exactly
       * why eviction cannot read its order and why this test is the check on the alternative.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(testsTotalOnly());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(3)).build())) {
        final FactHandle first = session.insert("Order", Facts.obj("id", 0, "total", 10));
        final FactHandle second = session.insert("Order", Facts.obj("id", 1, "total", 10));
        session.insert("Order", Facts.obj("id", 2, "total", 10));

        // Tested path, so §3.4.1 propagates and the recency is bumped.
        session.update(first, Facts.obj("id", 0, "total", 20));
        session.insert("Order", Facts.obj("id", 3, "total", 10));

        assertThat(session.get(first))
            .describedAs("updated last, so it is not the oldest any more")
            .isPresent();
        assertThat(session.get(second))
            .describedAs("and the fact that was next-oldest went instead")
            .isEmpty();
      }
    }

    @Test
    @DisplayName("an update touching nothing tested moves nothing")
    void skippedUpdateDoesNotMoveAFact() {
      // The other half, and the reason the class needs no special case for updates: §3.4.1 step 2
      // replaces the payload at the SAME recency and propagates neither callback, so a fact that
      // was oldest stays oldest. An implementation that moved a fact on every update call -- the
      // obvious way to write it -- would keep this one alive and evict the wrong fact.
      final CompiledRuleSet rules = RuleCompiler.compile(testsTotalOnly());
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(3)).build())) {
        final FactHandle first = session.insert("Order", Facts.obj("id", 0, "total", 10));
        final FactHandle second = session.insert("Order", Facts.obj("id", 1, "total", 10));
        session.insert("Order", Facts.obj("id", 2, "total", 10));

        // No rule tests `note`, so this propagates nothing and bumps nothing.
        session.update(first, Facts.obj("id", 0, "total", 10, "note", "touched"));
        session.insert("Order", Facts.obj("id", 3, "total", 10));

        assertThat(session.get(first))
            .describedAs("still the oldest, because nothing tested changed")
            .isEmpty();
        assertThat(session.get(second)).isPresent();
      }
    }
  }

  @Nested
  @DisplayName("nothing about it is visible to the matchers")
  class Equivalence {

    @Test
    @DisplayName("the three matchers fire identically with a total cap")
    void totalCapIsMatcherIndependent() {
      MatcherEquivalence.assertEquivalent(joinRules(), session -> {
        session.insert("Customer", Facts.obj("id", 1));
        session.insert("Customer", Facts.obj("id", 2));
        for (int id = 0; id < 40; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 1 + id % 2));
        }
      }, SessionOptions.builder().eviction(EvictionPolicy.leastRecentlyUsed(12)));
    }

    @Test
    @DisplayName("the three matchers fire identically with a per-type cap and firing in between")
    void perTypeCapIsMatcherIndependent() {
      // Firing between inserts matters: it puts matches in the refraction memory that eviction then
      // has to clear, on every shape, in the same order.
      MatcherEquivalence.assertEquivalent(joinRules(), session -> {
        session.insert("Customer", Facts.obj("id", 1));
        for (int id = 0; id < 20; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 1));
          if (id % 3 == 0) {
            session.fireAllRules();
          }
        }
      }, SessionOptions.builder().eviction(EvictionPolicy.perType(Map.of("Order", 4))));
    }

    @Test
    @DisplayName("the firing sequence does not depend on rule declaration order")
    void isDeterministicUnderShuffle() {
      ShuffleHarness.assertDeterministic(joinRules(), session -> {
        session.insert("Customer", Facts.obj("id", 1));
        for (int id = 0; id < 30; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 1));
        }
      }, ShuffleHarness.DEFAULT_PERMUTATIONS,
          SessionOptions.builder().eviction(EvictionPolicy.perType(Map.of("Order", 6))).build());
    }
  }

  @Nested
  @DisplayName("when the policy runs, and when it must not")
  class Quiescence {

    @Test
    @DisplayName("a right-hand side's own inserts are not evicted mid-commit")
    void rhsInsertsSurviveTheirOwnFiring() {
      /*
       * §4.6 stages every effect and then applies it. An eviction landing between the two could
       * retract a fact the firing activation binds, and the firing record would name a handle that
       * no longer exists -- so the policy is consulted at cycle boundaries, never inside one.
       *
       * The cap here is below what a single firing inserts, so an eviction that ran during the
       * commit would take one of the facts that commit was still writing.
       */
      final List<RuleDefinition> derives = List.of(Rules.rule("derive")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions
              .insertFact("Audit", "of", Rules.ref("o.id"), "seq", 1)
              .insertFact("Audit", "of", Rules.ref("o.id"), "seq", 2)
              .insertFact("Audit", "of", Rules.ref("o.id"), "seq", 3))
          .build());

      /*
       * Asserted on the ORDER of the callbacks, not on the surviving facts, and the difference is
       * the whole test. With Audit capped at 2 and three inserted, the survivors are the same two
       * whether the eviction lands mid-commit or at the cycle boundary -- so a test that asserts on
       * counts passes just as happily with the hook in the wrong place. That version of this test
       * existed first, and an eviction hook added to the working-memory insert path did not fail
       * one test in the suite.
       */
      final List<String> trace = new ArrayList<>();
      final RuleEngineListener tracer = new RuleEngineListener() {
        @Override
        public void onBeforeFire(final com.codeheadsystems.rules.match.Activation activation) {
          trace.add("beforeFire");
        }

        @Override
        public void onAfterFire(final com.codeheadsystems.rules.session.FireRecord record) {
          trace.add("afterFire");
        }

        @Override
        public void onEvicted(final Fact fact) {
          trace.add("evict:" + fact.type());
        }
      };

      final CompiledRuleSet rules = RuleCompiler.compile(derives);
      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.perType(Map.of("Audit", 2))).listener(tracer).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        assertThat(session.fireAllRules().fired())
            .describedAs("the firing completes; nothing it inserted was taken from under it")
            .hasSize(1);
        assertThat(session.workingMemory().factsOfType("Audit").count())
            .describedAs("the cap is applied at the next cycle boundary, not during the commit")
            .isEqualTo(2L);

        final int firingStarted = trace.indexOf("beforeFire");
        final int firingEnded = trace.indexOf("afterFire");
        assertThat(trace).contains("evict:Audit");
        assertThat(trace.subList(firingStarted, firingEnded))
            .describedAs("no eviction may happen between staging and commit: %s", trace)
            .doesNotContain("evict:Audit");
      }
    }

    @Test
    @DisplayName("a fact the firing activation binds cannot be evicted under it")
    void theBoundFactSurvivesItsOwnFiring() {
      /*
       * The half of the claim with teeth. The rule binds a capped type and its right-hand side
       * inserts one of that type, pushing it over the bound DURING the commit -- so an eviction
       * running there would take the oldest Order, which is the fact this very activation is bound
       * to. The second action then writes to that fact.
       *
       * Finding the observable took three tries, and the two that failed are worth recording because
       * both look right. Asserting on which facts SURVIVE does not work: the bound fact is evicted at
       * the cycle boundary either way, so the correct code and the defect end in the same state.
       * Asserting the firing FAILS does not work either -- RhsExecutor.PendingUpdate.apply returns
       * silently when its fact has gone, so a mid-commit eviction does not throw. It makes the
       * rule's write to its own bound fact disappear, with no failed action and no diagnostic, which
       * is a good deal worse than an exception and is exactly the silent-wrong-output class §4.4's
       * quiescence rule exists to prevent. So the observable is the effect itself: the firing record
       * either says it set the field or it does not.
       *
       * The derived Order carries total 0 so it does not match the pattern. An Order that did would
       * re-activate the rule on a new binding, which noLoop does not stop -- that is §4.5's
       * one-level-deep limitation, and it is the cycle limit's problem rather than this test's.
       */
      final List<RuleDefinition> spawnsAndWrites = List.of(Rules.rule("spawn")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions
              .insertFact("Order", "total", 0, "spawnedBy", Rules.ref("o.id"))
              .setField("o", "status", "SEEN"))
          .build());

      final CompiledRuleSet rules = RuleCompiler.compile(spawnsAndWrites);
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.perType(Map.of("Order", 1))).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        final List<com.codeheadsystems.rules.session.FireRecord> fired =
            session.fireAllRules().fired();

        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).failedAction()).isEmpty();
        assertThat(session.failed()).isFalse();
        assertThat(fired.get(0).effects())
            .describedAs("the write to the bound fact landed rather than silently vanishing")
            .anyMatch(effect -> effect instanceof StagedEffect.FieldSet);
        assertThat(session.stats().evictedCount())
            .describedAs("the eviction did happen -- at the boundary, after the firing")
            .isEqualTo(1L);
      }
    }

    @Test
    @DisplayName("the view produces only the facts a policy actually takes")
    void selectionDereferencesOnlyWhatItTakes() {
      /*
       * A session at its cap evicts on every insert, so anything the view does per fact is paid per
       * insert. This pins the half of that a test can observe: the number of facts the view is asked
       * to produce, which must be one per eviction rather than one per fact held. It would catch a
       * view that dereferenced every payload before handing any of them over -- in strict mode that
       * is a deep copy per fact per insert.
       *
       * Be clear about what it does NOT catch, because the obvious reading is wrong and was checked:
       * a view that materialises its handle ORDER eagerly and then streams facts from the copy
       * passes this untouched, since the copy is upstream of anything countable here and the stream
       * over it is still lazy. That version is O(facts) per insert and quadratic over a run, and the
       * only place it is ruled out is the argument in SessionEvictor.oldestFirst. Counted rather
       * than timed on purpose: the timing assertion that would catch it fails on a loaded machine
       * instead of on a defect.
       */
      final int cap = 200;
      final int inserts = 1_000;
      final int[] produced = {0};
      final EvictionPolicy counting = view -> {
        if (view.size() <= cap) {
          return List.of();
        }
        return view.oldestFirst().peek(fact -> produced[0]++)
            .limit(view.size() - cap).map(Fact::handle).toList();
      };

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(streaming(counting).build())) {
        for (int id = 0; id < inserts; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }

        // Strict mode consults the policy twice per eviction, to check it answers the same both
        // times, so the count it observes doubles. Derived rather than hardcoded because the suite
        // runs under both settings (§7.5) and an assertion true in only one of them is worse than
        // no assertion.
        final int consultationsPerEviction = SessionOptions.builder().build().strict() ? 2 : 1;

        assertThat(session.stats().evictedCount()).isEqualTo(inserts - cap);
        assertThat(produced[0])
            .describedAs("one fact examined per eviction, not one per fact held")
            .isEqualTo((inserts - cap) * consultationsPerEviction);
        assertThat(produced[0])
            .describedAs("a view that produced every held fact would be here instead")
            .isLessThan((inserts - cap) * cap);
      }
    }

    @Test
    @DisplayName("a listener inserting from onBeforeFire cannot evict under the selected match")
    void theSelectionWindowIsClosedToo() {
      /*
       * The window between selection and the right-hand side, which is not the same window as the
       * commit and was open when the guard covered only the RHS. An activation is CONSUMED by
       * nextToFire() before onBeforeFire is dispatched, so a listener inserting from there is
       * inserting under a match that is already selected and refracted -- and if that insert takes
       * the type over its cap, the fact the match binds is what goes.
       *
       * It fails loudly rather than silently, unlike the commit case: setField throws at staging, so
       * §4.6 discards the whole buffer and the default RETHROW policy then marks the session failed
       * and unusable on input that was perfectly valid. Both are the same root cause with opposite
       * symptoms, which is why both windows are tested rather than one standing in for the other.
       */
      final List<RuleDefinition> writesToItsOwn = List.of(Rules.rule("touch")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.setField("o", "status", "SEEN"))
          .build());

      final CompiledRuleSet rules = RuleCompiler.compile(writesToItsOwn);
      final AtomicBoolean inserted = new AtomicBoolean();
      final RuleSession[] holder = new RuleSession[1];
      final RuleEngineListener intruder = new RuleEngineListener() {
        @Override
        public void onBeforeFire(final com.codeheadsystems.rules.match.Activation activation) {
          // A listener that went out of its way to capture the session, which is the only way to
          // reach this at all: no shipped SPI hands one over.
          if (inserted.compareAndSet(false, true)) {
            holder[0].insert("Order", Facts.obj("id", 99, "total", 0));
          }
        }
      };

      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.perType(Map.of("Order", 1))).listener(intruder).build())) {
        holder[0] = session;
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        final List<com.codeheadsystems.rules.session.FireRecord> fired =
            session.fireAllRules().fired();

        assertThat(fired).hasSize(1);
        assertThat(session.failed())
            .describedAs("valid input must not leave the session permanently unusable")
            .isFalse();
        assertThat(fired.get(0).effects())
            .describedAs("the write landed on the fact the match was bound to")
            .anyMatch(effect -> effect instanceof StagedEffect.FieldSet);
      }
    }

    @Test
    @DisplayName("the policy is not consulted when nothing was inserted")
    void isGatedOnInserts() {
      /*
       * A cap can only be exceeded by an insert, so consulting the policy per fire cycle regardless
       * would be a scan per firing for an answer that cannot have changed. Counted through the
       * policy itself, because "it did not run" is not observable any other way.
       */
      final int[] consulted = {0};
      final EvictionPolicy counting = view -> {
        consulted[0]++;
        return List.of();
      };

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(streaming(counting).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));
        final int afterInsert = consulted[0];

        session.fireAllRules();
        session.fireAllRules();
        session.fireAllRules();

        assertThat(consulted[0])
            .describedAs("no insert since the last consultation, so no consultation")
            .isEqualTo(afterInsert);
      }
    }
  }

  @Nested
  @DisplayName("the steady state §9 asks for")
  class SteadyState {

    @Test
    @DisplayName("insert-without-retract under a cap reaches a steady state in every structure")
    void insertOnlyLoadReachesSteadyState() {
      /*
       * §9's Phase 3 exit criterion: "a streaming session under sustained insert-without-retract
       * load reaches a steady-state heap, not a rising one". Asserted on the structures rather than
       * on the heap, because a heap assertion fails on collector timing rather than on a defect --
       * and because these counts are what actually grows. All four of §4.4's structures are here:
       * working memory, the refraction memory, and the beta memory's matches and reverse index.
       *
       * A self-join, so the growth surface is the quadratic one the amendment names: N facts give
       * O(N²) matches, which is what makes "bounded by current matches" a weaker bound than it
       * sounds and eviction the only thing that answers it.
       */
      final List<RuleDefinition> selfJoin = List.of(Rules.rule("pair")
          .when("a", "Order", pattern -> pattern.gt("total", 0))
          .when("b", "Order", pattern -> pattern.ref("customerId", "a.customerId"))
          .then(actions -> actions.emit("pair",
              "a", Rules.ref("a.id"), "b", Rules.ref("b.id")))
          .build());

      final CompiledRuleSet rules = RuleCompiler.compile(selfJoin);
      try (RuleSession session =
          rules.newSession(streaming(EvictionPolicy.perType(Map.of("Order", 8))).build())) {
        SessionStats atFirstSample = null;
        for (int id = 0; id < 400; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 1));
          session.fireAllRules();
          if (id == 99) {
            atFirstSample = session.stats();
          }
        }
        final SessionStats atEnd = session.stats();

        assertThat(atFirstSample).isNotNull();
        assertThat(atEnd.factCount()).isEqualTo(8);
        assertThat(atEnd.factCount()).isEqualTo(atFirstSample.factCount());
        assertThat(atEnd.materialisedMatchCount())
            .describedAs("held matches are bounded by the surviving facts, not by history")
            .isEqualTo(atFirstSample.materialisedMatchCount());
        assertThat(atEnd.materialisedHandleCount())
            .describedAs("the reverse index is where a leak would hide, so it is asserted too")
            .isEqualTo(atFirstSample.materialisedHandleCount());
        assertThat(atEnd.refractedMatchCount())
            .describedAs("every match ever fired, bounded only by eviction")
            .isEqualTo(atFirstSample.refractedMatchCount());
        assertThat(atEnd.evictedCount()).isEqualTo(392L);
      }
    }
  }

  @Nested
  @DisplayName("what it does not do")
  class Boundaries {

    @Test
    @DisplayName("a drain does not resurrect what was evicted")
    void drainDoesNotResurrect() {
      // §5.6's drain-and-restart replays exported facts into a session on the new rules. An evicted
      // fact is not in working memory, so it is not in the export, so it does not come back.
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      final RuleSession original =
          rules.newSession(streaming(EvictionPolicy.leastRecentlyUsed(3)).build());
      for (int id = 0; id < 10; id++) {
        original.insert("Order", Facts.obj("id", id, "total", 10));
      }

      try (RuleSession restarted = SessionDrain.restart(original, rules,
          streaming(EvictionPolicy.leastRecentlyUsed(3)).build())) {
        assertThat(restarted.workingMemory().size()).isEqualTo(3);
        assertThat(restarted.stats().evictedCount())
            .describedAs("the new session evicted nothing; it never had the other seven")
            .isZero();
      }
    }

    @Test
    @DisplayName("no policy means no eviction and no counting")
    void withoutAPolicyNothingHappens() {
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession()) {
        for (int id = 0; id < 50; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10));
        }

        assertThat(session.workingMemory().size()).isEqualTo(50);
        assertThat(session.stats().evictedCount()).isZero();
        assertThat(session.stats().materialisedMatchCount())
            .describedAs("the recomputing shapes materialise nothing between fires")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("strict mode (§7.5)")
  class Strict {

    @Test
    @DisplayName("a policy that answers differently twice is rejected")
    void nonDeterministicPolicyIsRejected() {
      /*
       * The contract EvictionPolicy states and cannot enforce. A policy reading a clock, or
       * iterating a HashMap, produces a different firing sequence on a different run -- which §7.3
       * forbids and which nothing else would notice until it mattered.
       */
      final int[] calls = {0};
      final EvictionPolicy drifting = view -> {
        calls[0]++;
        return calls[0] % 2 == 0 ? List.of() : view.oldestFirst().limit(1)
            .map(Fact::handle).toList();
      };

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(drifting).strict(true).build())) {
        assertThatThrownBy(() -> session.insert("Order", Facts.obj("id", 1, "total", 10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not deterministic");
      }
    }

    @Test
    @DisplayName("a policy naming a fact that is not there is rejected")
    void unknownHandleIsRejected() {
      final EvictionPolicy inventing = view -> List.of(new FactHandle(9_999L));

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(inventing).strict(true).build())) {
        assertThatThrownBy(() -> session.insert("Order", Facts.obj("id", 1, "total", 10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not in working memory");
      }
    }

    @Test
    @DisplayName("outside strict mode a stale handle is skipped rather than fatal")
    void unknownHandleIsSkippedOutsideStrictMode() {
      final EvictionPolicy inventing = view -> List.of(new FactHandle(9_999L));

      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(inventing).strict(false).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 10));

        assertThat(session.workingMemory().size()).isEqualTo(1);
        assertThat(session.stats().evictedCount()).isZero();
      }
    }
  }

  @Nested
  @DisplayName("the shape it exists for")
  class UnderTheActor {

    @Test
    @DisplayName("a long-running actor session stays bounded under sustained inserts")
    void actorSessionStaysBounded() throws Exception {
      /*
       * §4.4's eviction and §5.4's actor are the two halves of the same deliverable, and neither is
       * worth much alone: the actor is what makes a session long-lived, and this is what makes a
       * long-lived session survive. Asserted through the actor rather than by driving a session
       * directly, because the fire loop is the actor's own business -- eviction happens at cycle
       * boundaries the caller never sees.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      final SessionOptions options =
          streaming(EvictionPolicy.perType(Map.of("Order", 25))).build();

      final List<SessionStats> samples = new ArrayList<>();
      try (SessionActor actor = SessionActor.start(rules, options, ActorOptions.defaults())) {
        for (int id = 0; id < 2_000; id++) {
          final int order = id;
          final CompletableFuture<SessionStats> stats = actor.submit(session -> {
            session.insert("Order", Facts.obj("id", order, "total", 10));
            return session.stats();
          });
          if (id == 999 || id == 1_999) {
            samples.add(stats.get(30, TimeUnit.SECONDS));
          }
        }
      }

      assertThat(samples).hasSize(2);
      assertThat(samples.get(1).factCount())
          .describedAs("two thousand inserts, twenty-five facts held, and flat between samples")
          .isEqualTo(samples.get(0).factCount())
          .isEqualTo(25);
      assertThat(samples.get(1).materialisedMatchCount())
          .describedAs("the beta memory is flat too, not merely working memory")
          .isEqualTo(samples.get(0).materialisedMatchCount());
      assertThat(samples.get(1).materialisedHandleCount())
          .isEqualTo(samples.get(0).materialisedHandleCount());
      assertThat(samples.get(1).refractedMatchCount())
          .isEqualTo(samples.get(0).refractedMatchCount());
      assertThat(samples.get(1).evictedCount())
          .describedAs("the rest were let go rather than accumulated")
          .isGreaterThan(samples.get(0).evictedCount());
    }
  }

  @Nested
  @DisplayName("a window in the data, rather than in the arrival count")
  class WindowedRetention {

    /** Ten minutes of failures, in the units the facts themselves are stamped in. */
    private static final long WINDOW = 600_000L;

    private static final long ORIGIN = 1_000_000L;

    /**
     * "Five failures for one user inside ten minutes", which is what velocity means.
     *
     * <p>Four, not five, and the arithmetic is worth stating: {@code before} is strict on the near
     * edge and an accumulate over a type the rule already binds excludes the bound fact (§1's
     * implicit inequality), so the count is of the failures <em>preceding</em> the trigger. The
     * trigger is the fifth.
     *
     * @return the rule
     */
    private static List<RuleDefinition> velocityRules() {
      return List.of(Rules.rule("login-velocity")
          .when("trigger", "LoginFailure")
          .accumulate("recent", "LoginFailure",
              Rules.count(com.codeheadsystems.rules.rule.Operator.GTE, 4),
              pattern -> pattern.ref("user", "trigger.user").before("at", "trigger.at", WINDOW))
          .then(actions -> actions.emit("account.locked", "user", Rules.ref("trigger.user")))
          .build());
    }

    private static SessionOptions windowed() {
      return streaming(EvictionPolicy.window("LoginFailure", "at", WINDOW)).build();
    }

    @Test
    @DisplayName("working memory holds the window, however long the stream runs")
    void steadyState() {
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.window("Order", "at", 1_000L)).build())) {
        for (int i = 0; i < 200; i++) {
          session.insert("Order", Facts.obj("id", i, "total", 1, "at", ORIGIN + i * 100L));
          session.fireAllRules();
        }
        assertThat(session.workingMemory().size())
            .describedAs("a thousand of the facts' own units, at one every hundred, is eleven")
            .isEqualTo(11);
      }
    }

    @Test
    @DisplayName("time advances only when a fact carrying a later time arrives")
    void nothingArrivingAgesNothing() {
      // The engine still owns no clock, and this policy does not give it one. A session that goes
      // quiet holds what it held: "no fact moved" is the input an engine acting on fact movement
      // never receives, which is exactly what §2.5's third amendment says cannot be worked around.
      final CompiledRuleSet rules = RuleCompiler.compile(singleFactRules());
      try (RuleSession session = rules.newSession(
          streaming(EvictionPolicy.window("Order", "at", 1_000L)).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 1, "at", ORIGIN));
        for (int i = 0; i < 50; i++) {
          session.fireAllRules();
        }
        assertThat(session.workingMemory().size()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("velocity: the rule fires on the burst, and not on the same facts spread out")
    void velocityFiresInsideTheWindowOnly() {
      final CompiledRuleSet rules = RuleCompiler.compile(velocityRules());
      try (RuleSession session = rules.newSession(windowed())) {
        for (int i = 0; i < 5; i++) {
          session.insert("LoginFailure", Facts.obj("user", "ana", "at", ORIGIN + i * 60_000L));
        }
        assertThat(session.fireAllRules().emitted())
            .describedAs("five inside ten minutes: the fifth is the trigger, four precede it")
            .hasSize(1);
      }
      try (RuleSession session = rules.newSession(windowed())) {
        for (int i = 0; i < 5; i++) {
          // The same five failures, an hour apart. Each one ages the ones before it out of both
          // the rule's window and the session's memory.
          session.insert("LoginFailure", Facts.obj("user", "ana", "at", ORIGIN + i * 3_600_000L));
          session.fireAllRules();
        }
        assertThat(session.workingMemory().factsOfType("LoginFailure").count()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("a conclusion held up by a windowed match is withdrawn when its facts age out")
    void aWindowedConclusionExpires() {
      /*
       * The one that makes windowing worth having: eviction is an ordinary retract, and §4.4's
       * truth maintenance re-asks the tuple -- so a conclusion whose justification aged out of the
       * window withdraws itself. Self-expiring state, in an engine with no clock, out of two
       * mechanisms that already existed.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("lock-on-burst")
          .when("trigger", "LoginFailure")
          .accumulate("recent", "LoginFailure",
              Rules.count(com.codeheadsystems.rules.rule.Operator.GTE, 2),
              pattern -> pattern.ref("user", "trigger.user").before("at", "trigger.at", WINDOW))
          .then(actions -> actions.insertLogical("Lock", "user", Rules.ref("trigger.user")))
          .build()));
      try (RuleSession session = rules.newSession(windowed())) {
        for (int i = 0; i < 3; i++) {
          session.insert("LoginFailure", Facts.obj("user", "ana", "at", ORIGIN + i * 60_000L));
        }
        session.fireAllRules();
        assertThat(session.workingMemory().factsOfType("Lock").count())
            .describedAs("three inside the window concludes a lock")
            .isEqualTo(1);

        session.insert("LoginFailure", Facts.obj("user", "bo", "at", ORIGIN + 5_000_000L));
        session.fireAllRules();

        assertThat(session.workingMemory().factsOfType("LoginFailure").count())
            .describedAs("the burst is outside the window the newest fact opened")
            .isEqualTo(1);
        assertThat(session.workingMemory().factsOfType("Lock").count())
            .describedAs("and the conclusion goes with the justification, unasked")
            .isZero();
      }
    }
  }

  /** Compile-time proof that the view is read-only: it exposes no mutator to call. */
  @Test
  @DisplayName("the view a policy sees cannot mutate working memory")
  void viewIsReadOnly() {
    assertThat(EvictionView.class.getMethods())
        .describedAs("handing a selection function the ability to mutate is the hazard this avoids")
        .noneMatch(method -> List.of("insert", "update", "retract", "insertOwned", "updateOwned")
            .contains(method.getName()));
  }
}
