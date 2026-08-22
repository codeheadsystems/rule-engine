package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireOptions;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleEngineLimitExceeded;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Rete shape's own properties (spec §11.1, Phase 3).
 *
 * <p>Deliberately <em>not</em> a correctness suite. §9's exit criterion -- that Rete and TREAT
 * produce identical firing sequences -- is asserted by {@code MatcherEquivalence} on every scenario
 * any test in this repository already writes, which is far better coverage than a Rete-specific
 * suite of cases someone remembered to duplicate. What lives here is what only this shape has: a
 * materialised memory, and the question of whether it is maintained rather than merely built.
 */
class ReteMatcherTest {

  private static CompiledRuleSet selfJoin() {
    return RuleCompiler.compile(List.of(Rules.rule("same-customer")
        .when("a", "Order", pattern -> pattern.gt("total", 0))
        .when("b", "Order", pattern -> pattern.ref("customerId", "a.customerId"))
        .then(actions -> actions.emit("pair", "left", Rules.ref("a.id")))
        .build()));
  }

  private static RuleSession streaming(final CompiledRuleSet rules) {
    return rules.newSession(SessionOptions.builder().matching(MatchingStrategy.RETE).build());
  }

  @Nested
  @DisplayName("the materialised memory agrees with a matcher that holds none")
  class AgreesWithTreat {

    @Test
    @DisplayName("a self-join derives each ordered pair once, not each unordered pair twice")
    void selfJoinDerivesEachOrderedPairOnce() {
      /*
       * The case where double-derivation would be easiest to introduce: one fact type at two
       * positions, so an arriving fact walks the join twice. [O2, O1] and [O1, O2] are two distinct
       * matches of a rule whose aliases bind distinct facts; deriving either one twice would double
       * the firings, and deriving only one would halve them.
       *
       * Asserted against the TREAT matcher rather than a hand-counted number, so the expectation
       * cannot drift away from what the engine actually means by a match.
       */
      final CompiledRuleSet rules = selfJoin();
      final List<Integer> treat = new ArrayList<>();
      final List<Integer> rete = new ArrayList<>();

      for (final MatchingStrategy strategy : List.of(MatchingStrategy.NETWORK,
          MatchingStrategy.RETE)) {
        try (RuleSession session = rules.newSession(
            SessionOptions.builder().matching(strategy).build())) {
          for (int id = 0; id < 6; id++) {
            session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", id % 2));
          }
          final int fired = session.fireAllRules().firedCount();
          (strategy == MatchingStrategy.NETWORK ? treat : rete).add(fired);
        }
      }

      assertThat(rete).isEqualTo(treat);
      assertThat(rete.getFirst()).describedAs("two customers, three orders each, ordered pairs")
          .isEqualTo(12);
    }
  }

  @Nested
  @DisplayName("cases a materialised memory has to survive and a recomputing one does not")
  class MutationDuringUse {

    @Test
    @DisplayName("a fact an RHS inserts mid-fire joins correctly against what is already held")
    void derivedFactsArriveMidFire() {
      /*
       * The hardest case for this shape and the easiest to get wrong. Under TREAT a fact inserted
       * by a right-hand side is simply present at the next recomputation. Here it mutates the beta
       * memory while the fire loop is running -- the loop is between materialisations, holding a
       * conflict set built from the memory it is now changing.
       *
       * Two rules rather than one: the second joins against the derived fact, so the new fact has
       * to complete matches with facts already in memory rather than merely appear.
       *
       * The whole engine's corpus reaches Rete through MatcherEquivalence, but only one scenario
       * there inserts from an RHS at all, so this is written rather than assumed.
       */
      MatcherEquivalence.assertEquivalent(List.of(
          Rules.rule("derive").noLoop()
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .then(actions -> actions.insertFact("Flag", "orderId", Rules.ref("o.id")))
              .build(),
          Rules.rule("consume").noLoop()
              .when("f", "Flag", pattern -> pattern.gt("orderId", -1))
              .when("o", "Order", pattern -> pattern.ref("id", "f.orderId"))
              .then(actions -> actions.emit("flagged", "id", Rules.ref("o.id")))
              .build()),
          session -> {
            for (int id = 0; id < 4; id++) {
              session.insert("Order", Facts.obj("id", id, "total", 10));
            }
          });
    }

    @Test
    @DisplayName("an update whose tested paths did not change leaves no stale match behind")
    void skippedUpdateDoesNotStaleTheMemory() {
      /*
       * §3.4.1 gates update on a tested-path diff: when nothing a rule tests changed, the update
       * propagates nothing and NO retract/insert observer callbacks run. So the beta memory keeps
       * tuples derived before the payload changed.
       *
       * That is correct rather than stale, and the reason is the invariant CLAUDE.md calls out
       * first: tuples bind FactHandles, never Fact objects, and payloads are dereferenced from
       * working memory at read time. A materialised match therefore cannot serve an old payload --
       * but this shape is the one where it would if that invariant were ever weakened, so it is
       * pinned here rather than trusted.
       */
      MatcherEquivalence.assertEquivalent(List.of(Rules.rule("watches-total")
              .when("o", "Order", pattern -> pattern.gt("total", 100))
              .then(actions -> actions.emit("seen", "note", Rules.ref("o.note")))
              .build()),
          session -> {
            final FactHandle handle =
                session.insert("Order", Facts.obj("total", 500, "note", "before"));
            session.fireAllRules();
            // /note is tested by nothing, so this is the skipped path.
            session.update(handle, Facts.obj("total", 500, "note", "after"));
          });
    }

    @Test
    @DisplayName("an update that changes a tested path rebuilds the matches it invalidated")
    void propagatingUpdateRebuildsMatches() {
      MatcherEquivalence.assertEquivalent(List.of(Rules.rule("pairs")
              .when("a", "Order", pattern -> pattern.gt("total", 0))
              .when("b", "Order", pattern -> pattern.ref("customerId", "a.customerId"))
              .then(actions -> actions.emit("pair", "left", Rules.ref("a.id")))
              .build()),
          session -> {
            final FactHandle moved =
                session.insert("Order", Facts.obj("id", 1, "total", 10, "customerId", 1));
            session.insert("Order", Facts.obj("id", 2, "total", 10, "customerId", 1));
            session.insert("Order", Facts.obj("id", 3, "total", 10, "customerId", 2));
            session.fireAllRules();
            // Moves the fact from one join group to the other: every match it had must go, and the
            // matches it now forms must appear.
            session.update(moved, Facts.obj("id", 1, "total", 10, "customerId", 2));
          });
    }
  }

  @Nested
  @DisplayName("the conflict set is pushed and pulled, not rebuilt (§4.3)")
  class PushedConflictSet {

    @Test
    @DisplayName("a fired match leaves the conflict set and the held memory keeps it")
    void firingRemovesTheMatchFromTheConflictSet() {
      /*
       * The distinction the shape is built on. The beta memory holds every match; the conflict set
       * holds the ones that could still fire. Before §4.3 they were the same thing at fire time --
       * every held match was rebuilt into an activation on every cycle and all but one discarded to
       * refraction, which is what made a fire cycle cost the size of the memory rather than the size
       * of the change.
       */
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        for (int id = 0; id < 4; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0));
        }
        assertThat(session.stats().pendingMatchCount())
            .describedAs("derived and waiting: four facts give twelve ordered pairs")
            .isEqualTo(12);

        session.fireAllRules();

        assertThat(session.stats().materialisedMatchCount())
            .describedAs("the matches are still held; the facts have not gone anywhere")
            .isEqualTo(12);
        assertThat(session.stats().pendingMatchCount())
            .describedAs("but none of them can fire again, so none is still in the conflict set")
            .isZero();
      }
    }

    @Test
    @DisplayName("a streaming session's conflict set stays flat while its memory grows")
    void theConflictSetDoesNotGrowWithTheMemory() {
      // §4.3's claim as something a test can assert: what a fire cycle ranks is the size of the
      // change, not the size of what is held.
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        for (int id = 0; id < 60; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0));
          session.fireAllRules();
          assertThat(session.stats().pendingMatchCount())
              .describedAs("after inserting and firing on order %d", id)
              .isZero();
        }

        assertThat(session.stats().materialisedMatchCount())
            .describedAs("while the held memory is quadratic in the facts, as it must be")
            .isEqualTo(60 * 59);
      }
    }

    @Test
    @DisplayName("clearing refraction re-offers the match, because the update re-derives it")
    void refractionClearedByAnUpdateReOffersTheMatch() {
      /*
       * §11.5's recorded hazard, and the reason this shape can drop a firing that TREAT performs:
       *
       *   "Rete's terminal-side refraction check would suppress an activation that the subsequent
       *    invalidation then made eligible -- with no further token to recreate it -- silently
       *    dropping a firing TREAT performs."
       *
       * This shape declines to hold a match that is already refracted, so it is safe only because
       * §3.4.1 clears refraction at step 5 and re-derives at step 6, in that order. Swap those two
       * and the re-derived match arrives while still refracted, is dropped, and nothing ever offers
       * it again. That ordering was incidental while both shapes rebuilt; it is load-bearing now.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("watch")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
          .build()));
      try (RuleSession session = streaming(rules)) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 10));
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
        assertThat(session.stats().pendingMatchCount()).isZero();

        // A tested path, so §3.4.1 propagates: refraction is cleared and the match re-derived.
        session.update(order, Facts.obj("id", 1, "total", 20));

        assertThat(session.stats().pendingMatchCount())
            .describedAs("the re-derived match is eligible again and back in the conflict set")
            .isEqualTo(1);
        assertThat(session.fireAllRules().firedCount())
            .describedAs("a firing TREAT performs, and this shape must not drop")
            .isEqualTo(1);
      }
    }

    @Test
    @DisplayName("an update a rule does not test leaves its match refracted and unoffered")
    void anUntestedUpdateDoesNotReOfferTheMatch() {
      // The other side of the same ordering. The match is destroyed and re-derived by the update,
      // but refraction is cleared only for rules testing a changed path -- so this one arrives
      // already refracted and must not be offered, or the rule fires twice on unchanged data.
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(Rules.rule("watch")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
          .build()));
      try (RuleSession session = streaming(rules)) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 10));
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);

        // `note` is tested by nothing, so §3.4.1 step 2 replaces the payload and propagates nothing.
        session.update(order, Facts.obj("id", 1, "total", 10, "note", "untested"));

        assertThat(session.fireAllRules().firedCount())
            .describedAs("nothing a rule tests changed, so nothing may fire again")
            .isZero();
        assertThat(session.stats().pendingMatchCount()).isZero();
      }
    }

    @Test
    @DisplayName("a partially drained conflict set survives the next rebuild intact")
    void aPartiallyDrainedConflictSetSurvivesARebuild() {
      /*
       * The case the other tests here cannot see, and the reason this one exists: every one of them
       * drains the conflict set completely, so "nothing pending" is the right answer whether the
       * shape removes the fired match or removes everything. Within a single fire call the
       * difference is invisible anyway -- materialise only rebuilds a DIRTY rule, so a slice that
       * was over-emptied still carries the rest of the cycle. The damage appears one rebuild later.
       *
       * So: stop the loop mid-drain with a cycle limit, then dirty the rule and force a rebuild.
       * An over-eager pull shows up as eleven matches that quietly stopped existing.
       */
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        for (int id = 0; id < 4; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0));
        }
        assertThat(session.stats().pendingMatchCount()).isEqualTo(12);

        assertThatThrownBy(() -> session.fireAllRules(FireOptions.builder().maxCycles(1).build()))
            .isInstanceOf(RuleEngineLimitExceeded.CycleLimit.class);

        assertThat(session.stats().pendingMatchCount())
            .describedAs("one fired; the other eleven are still waiting")
            .isEqualTo(11);

        // Dirties the rule, so the next selection rebuilds the slice from the pending set.
        session.insert("Order", Facts.obj("id", 99, "total", 10, "customerId", 1));

        assertThat(session.stats().pendingMatchCount())
            .describedAs("and the rebuild finds them, rather than a set something over-emptied")
            .isEqualTo(11);
      }
    }

    @Test
    @DisplayName("a match re-derived while still refracted is not put back in the conflict set")
    void aReDerivedRefractedMatchIsNotHeld() {
      /*
       * Needs two rules, which is why it was nearly missed. §3.4.1's effective update destroys and
       * re-derives every match binding the fact, but clears refraction only for the rules testing a
       * changed path -- so a rule that tests nothing that changed gets its match handed back while
       * still refracted. It must not be held: it cannot fire until something re-derives it again,
       * and keeping it would let the conflict set drift back toward a copy of the whole join memory,
       * which is the cost §4.3 was built to remove.
       *
       * Nothing about the firing sequence changes either way -- selection filters refracted matches
       * for every shape -- so this is asserted on what is held rather than on what fires. A test
       * written against firings passes with the suppression removed.
       */
      final CompiledRuleSet rules = RuleCompiler.compile(List.of(
          Rules.rule("tests-total")
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .then(actions -> actions.emit("a", "id", Rules.ref("o.id")))
              .build(),
          Rules.rule("tests-status")
              .when("o", "Order", pattern -> pattern.eq("status", "OPEN"))
              .then(actions -> actions.emit("b", "id", Rules.ref("o.id")))
              .build()));
      try (RuleSession session = streaming(rules)) {
        final FactHandle order =
            session.insert("Order", Facts.obj("id", 1, "total", 10, "status", "OPEN"));
        assertThat(session.fireAllRules().firedCount()).isEqualTo(2);
        assertThat(session.stats().pendingMatchCount()).isZero();

        // Changes /total only: tests-total is un-refracted, tests-status is not.
        session.update(order, Facts.obj("id", 1, "total", 20, "status", "OPEN"));

        assertThat(session.stats().pendingMatchCount())
            .describedAs("only the rule whose tested path changed is offered again")
            .isEqualTo(1);
        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
      }
    }

    @Test
    @DisplayName("a retracted fact's matches leave the conflict set as well as the memory")
    void retractPullsPendingMatchesToo() {
      // §4.3's deactivateAllInvolving. A match left in the conflict set after its fact has gone is
      // a phantom firing -- the activation binds a handle working memory can no longer resolve.
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        final List<FactHandle> handles = new ArrayList<>();
        for (int id = 0; id < 3; id++) {
          handles.add(session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0)));
        }
        assertThat(session.stats().pendingMatchCount()).isEqualTo(6);

        session.retract(handles.get(0));

        assertThat(session.stats().pendingMatchCount())
            .describedAs("the four pairs binding the retracted fact are gone, unfired")
            .isEqualTo(2);
        assertThat(session.stats().materialisedMatchCount()).isEqualTo(2);
      }
    }
  }

  @Nested
  @DisplayName("the memory is maintained, not merely built")
  class Maintenance {

    @Test
    @DisplayName("a retract removes the matches its fact took part in")
    void retractRemovesMatches() {
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        final List<FactHandle> handles = new ArrayList<>();
        for (int id = 0; id < 4; id++) {
          handles.add(session.insert("Order", Facts.obj("id", id, "total", 10, "customerId", 0)));
        }
        assertThat(session.fireAllRules().firedCount()).isEqualTo(12);

        handles.forEach(session::retract);

        // Nothing left to match, and nothing left over from what did. A memory that kept the
        // retracted facts' matches would fire them again here against facts that no longer exist.
        assertThat(session.fireAllRules().firedCount()).isZero();
        assertThat(session.workingMemory().size()).isZero();
      }
    }

    @Test
    @DisplayName("insert-and-retract cycles reach a steady state rather than accumulating")
    void reachesSteadyState() {
      /*
       * §9's third Phase 3 exit criterion, in the form it can be asserted in a unit test: "a
       * streaming session under sustained insert-without-retract load reaches a steady-state heap".
       * Heap is not measurable here without flakiness, and the beta memory is not reachable from a
       * session, so this asserts what a session CAN see across many more cycles than the working
       * set ever holds: every fact retracted, and nothing firing afterwards.
       *
       * That is deliberately only half the criterion. A leak that retained a reverse-index entry
       * per handle ever seen would pass every assertion here while growing without bound, so the
       * structure itself is asserted in BetaMemoryTest, where it is visible.
       */
      final CompiledRuleSet rules = selfJoin();
      try (RuleSession session = streaming(rules)) {
        for (int cycle = 0; cycle < 200; cycle++) {
          final FactHandle first =
              session.insert("Order", Facts.obj("id", cycle, "total", 10, "customerId", 0));
          final FactHandle second =
              session.insert("Order", Facts.obj("id", cycle, "total", 10, "customerId", 0));
          session.fireAllRules();
          session.retract(first);
          session.retract(second);
        }

        assertThat(session.workingMemory().size())
            .describedAs("every fact inserted was retracted").isZero();
        assertThat(session.fireAllRules().firedCount())
            .describedAs("no match survives its facts").isZero();
      }
    }
  }
}
