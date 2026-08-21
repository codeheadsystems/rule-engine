package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
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
