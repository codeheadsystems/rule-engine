package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.network.PatternNode;
import com.codeheadsystems.rules.network.SessionMemories;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.runtime.DefaultCompiledRuleSet;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 1's exit criterion, asserted on the network directly rather than inferred from timing.
 *
 * <p>Spec section 9 requires that single-fact rules "match via index lookup, not full scan", and
 * section 6.5 claims node sharing keeps the alpha network sublinear in rule count. Both are
 * structural properties, so both are checked structurally. A timing test would prove neither and
 * would be flaky besides.
 *
 * <p><strong>This is a white-box test and reaches into an internal package to be one.</strong> The
 * node graph is not API -- §8.1 -- so {@code CompiledRuleSet} does not expose it and
 * {@link #networkOf} casts to the implementation to get at it. That is a thing a test may do and
 * main source may not: {@code ApiSurfaceTest} checks each module's {@code src/main} precisely
 * because an end-to-end test of this engine has to drive internals a consumer never sees.
 */
class NetworkStructureTest {

  /**
   * The compiled node graph behind a rule set.
   *
   * @param ruleSet what the compiler returned
   * @return its network
   */
  private static Network networkOf(final CompiledRuleSet ruleSet) {
    return ((DefaultCompiledRuleSet) ruleSet).network();
  }

  /** Ten rules, all testing the same two constraints, plus one that differs. */
  private static List<RuleDefinition> sharedConstraints() {
    return IntStream.range(0, 10)
        .mapToObj(index -> Rules.rule("rule-" + index)
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 10_000))
            .then(actions -> actions.emit("hit-" + index, "id", Rules.ref("o.id")))
            .build())
        .toList();
  }

  @Test
  @DisplayName("ten rules expressing two distinct constraints compile to two alpha nodes")
  void nodeSharingIsRealSharing() {
    // Twenty constraints across ten rules. If sharing deduplicated the node objects but still
    // evaluated the predicate once per rule, this would be twenty -- that is sublinearity's shape
    // without any of its benefit.
    final CompiledRuleSet ruleSet = RuleCompiler.compile(sharedConstraints());

    assertThat(networkOf(ruleSet).alphaNodeCount())
        .describedAs("ten rules x two constraints, deduplicated")
        .isEqualTo(2);
    assertThat(networkOf(ruleSet).patternNodes())
        .describedAs("pattern nodes are per pattern, not per distinct constraint set")
        .hasSize(10);
  }

  @Test
  @DisplayName("differing constraints are not merged")
  void distinctConstraintsStayDistinct() {
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("a").when("o", "Order", p -> p.eq("status", "PENDING"))
            .then(t -> t.emit("a")).build(),
        Rules.rule("b").when("o", "Order", p -> p.eq("status", "SHIPPED"))
            .then(t -> t.emit("b")).build(),
        Rules.rule("c").when("o", "Order", p -> p.eq("status", "PENDING"))
            .then(t -> t.emit("c")).build()));

    assertThat(networkOf(ruleSet).alphaNodeCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a pattern's memory holds only what matches, so the matcher never scans the type")
  void memoryHoldsOnlyMatches() {
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("rare")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .then(actions -> actions.emit("hit", "id", Rules.ref("o.id")))
            .build()));
    final Network network = networkOf(ruleSet);
    final SessionMemories memories = new SessionMemories(network);
    final PatternNode pattern = network.patternNodes().getFirst();

    for (int order = 0; order < 1_000; order++) {
      final ObjectNode payload = Facts.obj(
          "id", order, "status", order % 250 == 0 ? "PENDING" : "SHIPPED");
      network.insert("Order", order, payload, memories);
    }

    // Four of a thousand. The naive matcher would enumerate all thousand and re-test each, every
    // fire cycle; this enumerates four.
    assertThat(memories.of(pattern).size()).isEqualTo(4);
    assertThat(memories.of(pattern).members()).containsExactly(0L, 250L, 500L, 750L);
  }

  @Test
  @DisplayName("retract removes from the memory and from every index bucket")
  void retractIsClean() {
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("join")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
            .then(actions -> actions.emit("hit"))
            .build()));
    final Network network = networkOf(ruleSet);
    final SessionMemories memories = new SessionMemories(network);
    final PatternNode customers = network.patternNodes().get(1);

    final ObjectNode alice = Facts.obj("id", 7);
    final ObjectNode bob = Facts.obj("id", 8);
    network.insert("Customer", 1L, alice, memories);
    network.insert("Customer", 2L, bob, memories);
    assertThat(memories.of(customers).size()).isEqualTo(2);
    assertThat(memories.of(customers).indexedKeyCount()).isEqualTo(2);

    network.retract("Customer", 1L, alice, memories);

    assertThat(memories.of(customers).size()).isEqualTo(1);
    assertThat(memories.of(customers).indexedKeyCount())
        .describedAs("an emptied bucket must be dropped, not left behind")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("both ends of a join are indexed, because either may be the smaller side")
  void indexPlanCoversBothEndsOfAJoin() {
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("join")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 10))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
            .then(actions -> actions.emit("hit"))
            .build()));
    final Network network = networkOf(ruleSet);

    // The constraint is written on the Customer pattern, so /id obviously needs an index.
    assertThat(network.patternNodes().get(1).indexPlan().hashed())
        .containsExactly(Paths.compile("id"));
    // ...and so does Order./customerId, which the constraint does not sit on. Section 3.3 makes
    // the smaller side a per-fire decision, so the matcher may bind customers first and probe for
    // their orders. Planning one pattern at a time can only ever see half of each edge.
    assertThat(network.patternNodes().getFirst().indexPlan().hashed())
        .containsExactly(Paths.compile("customerId"));
  }

  @Test
  @DisplayName("alpha-only paths are still not indexed")
  void alphaPathsAreNotIndexed() {
    // A pattern's memory already contains exactly the facts passing its alpha tests, so indexing
    // /status would build a structure whose only bucket is the memory itself.
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("alpha-only")
            .when("o", "Order", pattern -> pattern.eq("status", "PENDING").gt("total", 10))
            .then(actions -> actions.emit("hit"))
            .build()));

    assertThat(networkOf(ruleSet).patternNodes().getFirst().indexPlan().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("an unindexable join operator produces no index on either end")
  void unindexableJoinsIndexNothing() {
    // NE is an anti-match: "everything except one bucket", which an index cannot narrow. Section
    // 3.3 names it, and it reverses to NE rather than to something probeable.
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("anti")
            .when("a", "Quote", pattern -> pattern.eq("sku", "WIDGET"))
            .when("b", "Quote", pattern -> pattern.ref("vendor", "a.vendor", Operator.NE))
            .then(actions -> actions.emit("hit"))
            .build()));

    assertThat(networkOf(ruleSet).patternNodes())
        .allSatisfy(node -> assertThat(node.indexPlan().isEmpty()).isTrue());
  }

  @Test
  @DisplayName("a fact of a type no rule patterns is not stored at all")
  void unpatternedTypesAreNotStored() {
    final CompiledRuleSet ruleSet = RuleCompiler.compile(List.of(
        Rules.rule("orders-only").when("o", "Order").then(t -> t.emit("hit")).build()));
    final Network network = networkOf(ruleSet);
    final SessionMemories memories = new SessionMemories(network);

    network.insert("Telemetry", 1L, Facts.obj("noise", true), memories);

    assertThat(network.entryFor("Telemetry")).isNull();
    assertThat(memories.of(network.patternNodes().getFirst()).size()).isZero();
  }
}
