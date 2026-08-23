package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Truth maintenance: withdrawing a conclusion once its reason stops holding (spec §4.4's amendment).
 *
 * <p>§1 deferred this and named the ordering dependency it expected -- truth maintenance and
 * negation "want to land together, since the strongest motivation for the former is retracting
 * matches justified by an absence". Negation landed alone and carried the cost as a documented
 * boundary, and {@code NegationTest.thereIsNoTruthMaintenance} is where that boundary was pinned.
 * This is the boundary being paid off, and {@link Negations} below is the case §1 actually named.
 *
 * <p>Every case asserts what is <em>in working memory</em> rather than only what fired. A withdrawal
 * leaves no firing record -- it is the absence of a fact, not an event -- so a suite built from
 * firing counts alone would pass with the whole mechanism deleted.
 */
class TruthMaintenanceTest {

  /** §1's own example: conclude that an order is unpaid, revocably. */
  private static List<RuleDefinition> unpaidOrder() {
    return List.of(Rules.rule("unpaid")
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
        .then(actions -> actions.insertLogical("OrderUnpaid", "orderId", Rules.ref("o.id")))
        .build());
  }

  /** How many facts of a type the session currently holds. */
  private static long count(final RuleSession session, final String factType) {
    return session.workingMemory().factsOfType(factType).count();
  }

  @Nested
  @DisplayName("a conclusion justified by an absence")
  class Negations {

    @Test
    @DisplayName("is withdrawn when the absence ends -- the case §1 named")
    void thePaymentArrives() {
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid"))
            .describedAs("no payment, so the conclusion stands")
            .isEqualTo(1);

        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid"))
            .describedAs("the absence ended, so the conclusion is withdrawn (§4.4's amendment)")
            .isZero();
      }
    }

    @Test
    @DisplayName("comes back when the absence returns, which needs refraction cleared")
    void andReturnsWhenThePaymentGoes() {
      /*
       * The coupling that makes a withdrawal reversible. The tuple is the same Order either way, so
       * the ActivationKey is unchanged -- a rule left refracted on it would never re-fire and never
       * re-conclude, and a conclusion withdrawn by something temporary would stay withdrawn for the
       * life of the session. TruthMaintenance.withdraw clears refraction for exactly this reason.
       */
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        // Concluded FIRST, so the rule is refracted on this tuple before anything is withdrawn.
        // An earlier version of this test inserted the payment up front, so nothing was ever
        // concluded and nothing withdrawn -- it passed with truth maintenance deleted entirely.
        session.fireAllRules();
        assertThat(count(session, "OrderUnpaid")).isEqualTo(1);

        final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1));
        session.fireAllRules();
        assertThat(count(session, "OrderUnpaid")).isZero();

        session.retract(payment);
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid"))
            .describedAs("unpaid again -- and the tuple is unchanged, so this only happens because"
                + " withdrawing the conclusion also forgot the refraction on it")
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("a conclusion justified by facts")
  class Bindings {

    @Test
    @DisplayName("is withdrawn when a fact the match binds is retracted")
    void theBoundFactGoes() {
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).isEqualTo(1);

        session.retract(order);
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid"))
            .describedAs("nothing left to be unpaid")
            .isZero();
      }
    }

    @Test
    @DisplayName("is withdrawn when an update makes the match stop holding")
    void theBoundFactStopsMatching() {
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).isEqualTo(1);

        // total > 0 no longer holds, so the tuple is no longer a match of this rule.
        session.update(order, Facts.obj("id", 1, "total", 0));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).isZero();
      }
    }

    @Test
    @DisplayName("two matches concluding the same thing are two facts, withdrawn independently")
    void oneConclusionPerMatch() {
      /*
       * Where this departs from classical truth maintenance, and the departure follows from §6.2.2
       * rather than from a choice: a logical insert allocates a fresh handle, so two matches
       * concluding "the same" thing produce two facts, each supported by its own match. There is no
       * content-based deduplication and therefore no fact held up by two reasons -- a multi-support
       * graph was written first and removed as unreachable.
       *
       * The consequence is the part to know: a rule counting CustomerAtRisk counts two for one
       * customer. Deduplicating by payload is a separate feature from withdrawing, needing an
       * equality index and a change to what fact identity means (§2.1), so it is not smuggled in.
       */
      final RuleDefinition atRisk = Rules.rule("at-risk")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertLogical("CustomerAtRisk", "customerId",
              Rules.ref("o.customerId")))
          .build();
      final CompiledRuleSet rules = Engine.compile(atRisk);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 10, "customerId", 7));
        session.insert("Order", Facts.obj("id", 2, "total", 20, "customerId", 7));
        session.fireAllRules();

        assertThat(count(session, "CustomerAtRisk"))
            .describedAs("one conclusion per match, not one conclusion held up twice")
            .isEqualTo(2);

        session.insert("Payment", Facts.obj("orderId", 1));
        session.fireAllRules();

        assertThat(count(session, "CustomerAtRisk"))
            .describedAs("order 1's conclusion goes; order 2's is untouched by it")
            .isEqualTo(1);

        session.insert("Payment", Facts.obj("orderId", 2));
        session.fireAllRules();

        assertThat(count(session, "CustomerAtRisk"))
            .describedAs("both reasons gone, both conclusions gone")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("what an ordinary insert still does")
  class Ordinary {

    @Test
    @DisplayName("stands after its reason goes, which is the behaviour every rule had before")
    void anOrdinaryInsertIsNotWithdrawn() {
      // The control. Without it this suite proves only that something retracts facts, not that the
      // `logical` flag is what asked for it.
      final RuleDefinition stated = Rules.rule("unpaid")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertFact("OrderUnpaid", "orderId", Rules.ref("o.id")))
          .build();
      final CompiledRuleSet rules = Engine.compile(stated);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();
        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid"))
            .describedAs("a stated conclusion outlives its reason, as it always has")
            .isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("the cascade")
  class Cascade {

    @Test
    @DisplayName("withdrawing a conclusion withdraws what rested on it")
    void withdrawalCascades() {
      /*
       * Two links: an Order with no Payment concludes OrderUnpaid, and OrderUnpaid concludes
       * AccountFlagged. Paying the order withdraws the first, which leaves the second's
       * justification resting on a fact that no longer exists. A pass that ran once would leave
       * AccountFlagged standing; the loop is what makes the whole chain go.
       */
      final List<RuleDefinition> chain = List.of(
          Rules.rule("unpaid")
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.insertLogical("OrderUnpaid", "orderId", Rules.ref("o.id")))
              .build(),
          Rules.rule("flag")
              .when("u", "OrderUnpaid")
              .then(actions -> actions.insertLogical("AccountFlagged", "orderId",
                  Rules.ref("u.orderId")))
              .build());
      final CompiledRuleSet rules = Engine.compile(chain.toArray(new RuleDefinition[0]));
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "AccountFlagged"))
            .describedAs("derived from a derived fact")
            .isEqualTo(1);

        session.insert("Payment", Facts.obj("orderId", 1, "amount", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).isZero();
        assertThat(count(session, "AccountFlagged"))
            .describedAs("its justification rested on a fact that has gone")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("a conclusion justified by a forAll")
  class Universals {

    @Test
    @DisplayName("is withdrawn when a counterexample arrives")
    void aCounterexampleWithdrawsIt() {
      // The §2.5 boundary, paid off by the same mechanism: FOR_ALL's "not undone when a
      // counterexample arrives" was the same missing piece negation's boundary named.
      final RuleDefinition ready = Rules.rule("ready")
          .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
          .forAll("li", "LineItem", pattern -> pattern
              .ref("orderId", "o.id")
              .eq("inStock", true))
          .then(actions -> actions.insertLogical("OrderReady", "orderId", Rules.ref("o.id")))
          .build();
      final CompiledRuleSet rules = Engine.compile(ready);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", true));
        session.fireAllRules();

        assertThat(count(session, "OrderReady")).isEqualTo(1);

        session.insert("LineItem", Facts.obj("orderId", 1, "inStock", false));
        session.fireAllRules();

        assertThat(count(session, "OrderReady"))
            .describedAs("the requirement stopped holding, so the conclusion is withdrawn")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("from a rule file")
  class FromADslFile {

    @Test
    @DisplayName("logical: true withdraws, and its absence does not")
    void theDslKeyReachesTheEngine() {
      /*
       * End to end through the front end, because `logical` is a key that changes what happens
       * AFTER a firing -- so a DSL layer that parsed it and dropped it would produce a rule that
       * compiles, fires identically, and quietly never withdraws anything. DslEquivalence catches
       * the dropped key through the version hash; this catches it through behaviour.
       */
      final String yaml = """
          apiVersion: rules.v1
          rules:
            - id: unpaid
              when:
                - fact: Order
                  as: o
                  where:
                    status: { eq: "PENDING" }
                - fact: Payment
                  as: p
                  quantifier: notExists
                  where:
                    orderId: { eq: { $ref: o.id } }
              then:
                - action: insertFact
                  fact: OrderUnpaid
                  logical: %s
                  payload: { orderId: { $ref: o.id } }
          """;

      for (final boolean logical : new boolean[] {true, false}) {
        final CompiledRuleSet rules = com.codeheadsystems.rules.dsl.RuleFiles.compile(
            com.codeheadsystems.rules.dsl.RuleSource.yaml("t.yaml", yaml.formatted(logical)));
        try (RuleSession session = rules.newSession()) {
          session.insert("Order", Facts.obj("id", 1, "status", "PENDING"));
          session.fireAllRules();
          assertThat(count(session, "OrderUnpaid"))
              .describedAs("concluded either way")
              .isEqualTo(1);

          session.insert("Payment", Facts.obj("orderId", 1));
          session.fireAllRules();

          assertThat(count(session, "OrderUnpaid"))
              .describedAs("logical=%s", logical)
              .isEqualTo(logical ? 0 : 1);
        }
      }
    }
  }

  @Nested
  @DisplayName("the three matchers")
  class EveryMatcher {

    @Test
    @DisplayName("agree about withdrawing AND about re-deriving")
    void allThreeAgree() {
      /*
       * Truth maintenance is the first thing in the engine that clears refraction with no
       * accompanying fact event, and that is exactly where the three matchers can come apart. The
       * recomputing shapes rebuild a dirty rule's whole slice, so an un-refracted match returns by
       * itself; §4.3's shape holds a match from derivation until it fires and never re-derives a
       * tuple whose facts did not move, so it needs telling. Before Agenda.reactivate existed this
       * script withdrew under all three and re-derived under two -- the divergence §9's exit
       * criterion forbids, and invisible to a suite that ran on the default matcher alone.
       */
      for (final MatchingStrategy strategy : MatchingStrategy.values()) {
        final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
        try (RuleSession session = rules.newSession(
            SessionOptions.builder().matching(strategy).build())) {
          session.insert("Order", Facts.obj("id", 1, "total", 100));
          session.fireAllRules();
          assertThat(count(session, "OrderUnpaid")).describedAs("%s concluded", strategy)
              .isEqualTo(1);

          final FactHandle payment = session.insert("Payment", Facts.obj("orderId", 1));
          session.fireAllRules();
          assertThat(count(session, "OrderUnpaid")).describedAs("%s withdrew", strategy)
              .isZero();

          session.retract(payment);
          session.fireAllRules();
          assertThat(count(session, "OrderUnpaid")).describedAs("%s re-derived", strategy)
              .isEqualTo(1);
        }
      }
    }
  }

  @Nested
  @DisplayName("the pass sees what the concluding firing itself did")
  class SelfConsuming {

    @Test
    @DisplayName("a rule that retracts its own binding has its conclusion withdrawn")
    void theFirstConclusionCountsToo() {
      /*
       * Justifications are recorded AFTER the right-hand side returns, so during the firing that
       * draws the FIRST conclusion the graph is still empty. An earlier version of factTypeTouched
       * skipped recording while the graph was empty, which threw away every change that firing made
       * -- so this rule left Alert standing until some unrelated insert happened to run the pass.
       * The same rule and the same facts gave different answers depending on what the session had
       * done before it, which is the kind of defect no single-scenario test finds.
       */
      final RuleDefinition consume = Rules.rule("consume")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions
              .insertLogical("Alert", "orderId", Rules.ref("o.id"))
              .retractFact("o"))
          .build();
      final CompiledRuleSet rules = Engine.compile(consume);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "Order")).describedAs("the rule consumed its own binding").isZero();
        assertThat(count(session, "Alert"))
            .describedAs("the reason went with the firing that made the conclusion")
            .isZero();
        assertThat(session.stats().concludedFactCount()).isZero();
      }
    }
  }

  @Nested
  @DisplayName("a dry run")
  class DryRun {

    @Test
    @DisplayName("concludes nothing, because nothing was inserted")
    void nothingIsJustified() {
      /*
       * §7.5's dry run releases the handle and still records the effect -- the effects list is what
       * WOULD have landed. Recording a justification from it grows the graph without bound over a
       * long-lived dry-run session and spends a TupleMatch.holds per cycle on tuples that never
       * existed, every one of them naming a handle working memory does not have.
       */
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession(
          SessionOptions.builder().dryRun(true).build())) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).describedAs("nothing was inserted").isZero();
        assertThat(session.stats().concludedFactCount())
            .describedAs("and nothing is being held up")
            .isZero();
      }
    }
  }

  @Nested
  @DisplayName("a conclusion that leaves by another door")
  class OtherDoors {

    @Test
    @DisplayName("is forgotten, so the graph does not hold a handle working memory lacks")
    void aCallerRetractsTheConclusion() {
      // Nothing in the engine stops a caller retracting a derived fact. Leaving its support in the
      // graph would leave the pass revalidating a justification for a fact that is already gone,
      // and calling retract on it again every time its reason finally expired.
      final CompiledRuleSet rules = Engine.compile(unpaidOrder().getFirst());
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();
        assertThat(session.stats().concludedFactCount()).isEqualTo(1);

        final FactHandle conclusion = session.workingMemory().factsOfType("OrderUnpaid")
            .findFirst().orElseThrow().handle();
        session.retract(conclusion);

        assertThat(session.stats().concludedFactCount())
            .describedAs("forgotten the moment it left")
            .isZero();

        // And the justification expiring afterwards is a no-op rather than a second retract.
        session.insert("Payment", Facts.obj("orderId", 1));
        session.fireAllRules();

        assertThat(count(session, "OrderUnpaid")).isZero();
        assertThat(session.stats().concludedFactCount()).isZero();
      }
    }
  }

  @Nested
  @DisplayName("a justification that fires again")
  class Refiring {

    @Test
    @DisplayName("replaces its conclusion rather than standing beside it")
    void aRefiringSupersedes() {
      /*
       * An update to a tested path clears refraction while leaving the match valid, so the same
       * ActivationKey fires again -- and the conclusion it drew the first time was drawn from the
       * old payload. Leaving both alive is the same belief twice, and in a streaming session that
       * updates its facts it is unbounded growth of exactly the thing this feature adds.
       *
       * It cannot be retracted at the firing that replaces it: §4.6 stages and commits a right-hand
       * side as a unit and nothing may be retracted in between. So the superseded conclusion waits
       * for the pass, which is the same quiescence rule everything else here follows.
       */
      final RuleDefinition snapshot = Rules.rule("snapshot")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.insertLogical("Snapshot", "total", Rules.ref("o.total")))
          .build();
      final CompiledRuleSet rules = Engine.compile(snapshot);
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();
        assertThat(count(session, "Snapshot")).isEqualTo(1);

        session.update(order, Facts.obj("id", 1, "total", 200));
        session.fireAllRules();

        assertThat(count(session, "Snapshot"))
            .describedAs("one conclusion, redrawn -- not two")
            .isEqualTo(1);
        assertThat(session.stats().concludedFactCount()).isEqualTo(1);
        assertThat(session.workingMemory().factsOfType("Snapshot").findFirst().orElseThrow()
            .payload().get("total").asInt())
            .describedAs("and it is the one drawn from the current payload")
            .isEqualTo(200);
      }
    }

    @Test
    @DisplayName("that applies nothing leaves the previous conclusion standing")
    void aFailedRefiringDoesNotDestroyTheConclusion() {
      /*
       * §4.6: a staging failure applies nothing. An earlier version superseded before reading the
       * effects, so it acted on the INTENT to re-fire -- and a firing that threw during staging
       * deleted the previous conclusion, removed the key from the graph so the pass never visited
       * it, and left the rule refracted. A transient right-hand-side failure silently destroyed a
       * belief whose justification still held, permanently.
       *
       * The setField below throws at STAGING once the marker's payload is a scalar rather than an
       * object -- which is the case that matters, because §4.6's commit applies working-memory
       * operations before any function or sink, so a later failure cannot precede the insert.
       * SKIP_ACTIVATION keeps the session usable so the outcome is observable.
       */
      final RuleDefinition snapshot = Rules.rule("snapshot")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .when("m", "Marker")
          .then(actions -> actions
              .insertLogical("Snapshot", "total", Rules.ref("o.total"))
              .setField("m", "touched", 1))
          .build();
      final CompiledRuleSet rules = Engine.compile(snapshot);
      try (RuleSession session = rules.newSession(SessionOptions.builder()
          .onRhsError((activation, failed, cause) ->
              com.codeheadsystems.rules.rhs.RhsErrorHandler.Decision.SKIP_ACTIVATION)
          .build())) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 100));
        final FactHandle marker = session.insert("Marker", Facts.obj("ok", true));
        session.fireAllRules();
        assertThat(count(session, "Snapshot")).isEqualTo(1);
        assertThat(session.stats().concludedFactCount()).isEqualTo(1);

        // A scalar payload makes the setField fail at staging, so the re-firing applies nothing at
        // all -- not even the insert written before it. Built directly rather than through Facts,
        // which refuses a non-object on the way in.
        session.update(marker, tools.jackson.databind.node.IntNode.valueOf(7));
        // The order still matches and its tested path changed, so the rule really does re-fire.
        session.update(order, Facts.obj("id", 1, "total", 200));
        session.fireAllRules();

        assertThat(count(session, "Snapshot"))
            .describedAs("the justification still holds, so the conclusion must still stand")
            .isEqualTo(1);
        assertThat(session.stats().concludedFactCount()).isEqualTo(1);
        assertThat(session.workingMemory().factsOfType("Snapshot").findFirst().orElseThrow()
            .payload().get("total").asInt())
            .describedAs("and it is the STALE one -- nothing landed to replace it, so a value of"
                + " 200 here would mean the graph had been re-recorded from the rule's actions")
            .isEqualTo(100);
      }
    }

    @Test
    @DisplayName("still withdraws cleanly after superseding")
    void supersedeThenWithdraw() {
      final RuleDefinition snapshot = Rules.rule("snapshot")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.insertLogical("Snapshot", "total", Rules.ref("o.total")))
          .build();
      final CompiledRuleSet rules = Engine.compile(snapshot);
      try (RuleSession session = rules.newSession()) {
        final FactHandle order = session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();
        session.update(order, Facts.obj("id", 1, "total", 200));
        session.fireAllRules();

        session.retract(order);
        session.fireAllRules();

        assertThat(count(session, "Snapshot")).isZero();
        assertThat(session.stats().concludedFactCount()).isZero();
      }
    }
  }

  @Nested
  @DisplayName("a conclusion that defeats its own reason")
  class SelfDefeating {

    @Test
    @DisplayName("oscillates, and the cycle limit is the defence")
    void aSelfDefeatingConclusionHitsTheCycleLimit() {
      /*
       * Worth pinning because `notExists` plus `insertFact` is the "do this once" idiom the guide
       * teaches, and adding logical: true to it turns a terminating rule set into a livelock.
       * Conclude -> the conclusion defeats the negation -> withdraw -> refraction forgotten ->
       * conclude again. That is what truth maintenance MEANS for such a rule rather than a defect
       * in it, so the engine does not try to detect it; §4.7's cycle limit is what stops it, and
       * this test is the record that it does.
       */
      final RuleDefinition selfDefeating = Rules.rule("alert-once")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("a", "Alert", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertLogical("Alert", "orderId", Rules.ref("o.id")))
          .build();
      final CompiledRuleSet rules = Engine.compile(selfDefeating);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));

        org.assertj.core.api.Assertions.assertThatThrownBy(session::fireAllRules)
            .describedAs("the conclusion un-makes its own reason, forever")
            .isInstanceOf(com.codeheadsystems.rules.session.RuleEngineLimitExceeded.CycleLimit.class);
      }
    }

    @Test
    @DisplayName("and an ordinary insert in the same shape still terminates")
    void withoutLogicalItTerminates() {
      // The control, and the reason the paragraph in the guide is needed: the only difference
      // between a rule that settles and one that livelocks is the flag.
      final RuleDefinition once = Rules.rule("alert-once")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .notExists("a", "Alert", pattern -> pattern.ref("orderId", "o.id"))
          .then(actions -> actions.insertFact("Alert", "orderId", Rules.ref("o.id")))
          .build();
      final CompiledRuleSet rules = Engine.compile(once);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));

        assertThat(session.fireAllRules().firedCount()).isEqualTo(1);
        assertThat(count(session, "Alert")).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("§7.3's determinism contract")
  class Determinism {

    @Test
    @DisplayName("the order conclusions are withdrawn in does not depend on rule order")
    void withdrawalOrderIsStable() {
      /*
       * Retraction order reaches the agenda through the rules those retracts dirty, and §7.3 covers
       * every path to it. The justification graph is insertion-ordered for that reason; swapping it
       * for a HashMap is the mutation this is here to fail. Two rules, several conclusions, all
       * withdrawn in one pass.
       */
      final List<RuleDefinition> both = List.of(
          Rules.rule("unpaid")
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.insertLogical("OrderUnpaid", "orderId", Rules.ref("o.id")))
              .build(),
          Rules.rule("watch")
              .when("o", "Order", pattern -> pattern.gt("total", 0))
              .notExists("p", "Payment", pattern -> pattern.ref("orderId", "o.id"))
              .then(actions -> actions.insertLogical("Watched", "orderId", Rules.ref("o.id")))
              .build());

      ShuffleHarness.assertDeterministic(both, session -> {
        for (int id = 1; id <= 4; id++) {
          session.insert("Order", Facts.obj("id", id, "total", 10 * id));
        }
        session.fireAllRules();
        for (int id = 1; id <= 4; id++) {
          session.insert("Payment", Facts.obj("orderId", id));
        }
      });
    }
  }

  @Nested
  @DisplayName("what is recorded")
  class Recording {

    @Test
    @DisplayName("comes from what landed, not from what the rule asked for")
    void onlyAppliedInsertsAreJustified() {
      /*
       * §4.6's commit is atomic per phase and not across it, so a right-hand side can fail partway
       * with some inserts applied and some not. Reading the rule's ACTIONS rather than its EFFECTS
       * would justify a fact that was never inserted, leaving the graph naming a handle working
       * memory does not have and the pass trying to withdraw it on every cycle for the life of the
       * session. The retractFact below cancels the insert at commit -- the effect never lands --
       * so nothing may be justified by it.
       */
      final RuleDefinition cancelled = Rules.rule("cancelled")
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions
              .insertLogicalAs("Alert", "a", "orderId", Rules.ref("o.id"))
              .retractFact("a"))
          .build();
      final CompiledRuleSet rules = Engine.compile(cancelled);
      try (RuleSession session = rules.newSession()) {
        session.insert("Order", Facts.obj("id", 1, "total", 100));
        session.fireAllRules();

        assertThat(count(session, "Alert")).describedAs("cancelled at commit").isZero();
        assertThat(session.stats().concludedFactCount())
            .describedAs("and so nothing is held up by a fact that never existed")
            .isZero();
      }
    }
  }
}
