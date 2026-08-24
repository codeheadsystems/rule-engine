package com.codeheadsystems.rules.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.cel.CelExpressions;
import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import com.codeheadsystems.rules.testkit.FiringSequence;
import com.codeheadsystems.rules.testkit.MatcherEquivalence;
import com.codeheadsystems.rules.testkit.ShuffleHarness;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The testkit's own harnesses, pointed at these rules.
 *
 * <p>These are not tests of the engine -- the engine has its own. They are here because
 * {@code rule-engine-testkit} is a <em>consumer-facing</em> module and this is what using it on your
 * own rule set looks like. Both harnesses take a {@code List<RuleDefinition>}, which is what
 * {@link RuleFiles#parse} hands back, so a rule file goes into them unchanged.
 *
 * <p><strong>{@link MatcherEquivalence}</strong> runs a scenario under all three matchers and
 * asserts the firing <em>sequences</em> are identical. The naive matcher is the oracle: no network,
 * no indexes, {@code O(rules x facts^arity)}, and no state to leave stale. If your rule set produces
 * a different answer under it than under the default, that is a bug worth reporting -- and if it
 * produces the same one, you have checked your rules against an implementation that cannot have an
 * index too narrow or a memory not cleaned up on retract.
 *
 * <p><strong>{@link ShuffleHarness}</strong> varies what §7.3 says must not matter -- the order the
 * rules were declared in -- and asserts the output does not move. Insertion order is deliberately
 * <em>not</em> shuffled: that is an input to the determinism contract, not an irrelevance.
 */
class MatcherAgreementTest {

  private static List<RuleDefinition> definitions;
  private static CompilerOptions compilerOptions;
  private static List<OrderEvent> feed;

  @BeforeAll
  static void parseOnce() {
    definitions = RuleFiles.parse(
        RuleSource.yaml(OrderRules.RESOURCE, OrderRules.read(OrderRules.RESOURCE)));
    /*
     * The harnesses compile the definitions themselves, once per matcher, so they need the same
     * options OrderRules uses -- without the expression compiler, rule 7's condition is a compile
     * error and the whole file fails to load here while working perfectly in production.
     */
    compilerOptions = CompilerOptions.builder()
        .declaredFunctions(OrderRules.FUNCTIONS)
        .declaredFactTypes(OrderRules.FACT_TYPES)
        .expressions(CelExpressions.create())
        .build();
    feed = EventFeed.load();
  }

  /**
   * A slice of the feed, applied to whatever session the harness supplies.
   *
   * <p>The harness fires once, after the scenario has run -- so this is the whole picture arriving
   * at once, not the interleaved arrival {@link StreamingDemo} shows. That difference is not
   * cosmetic: {@code unpaid-order} concludes when an order arrives and withdraws when its payment
   * does, so a scenario that applies both before firing never sees the conclusion at all. The
   * interleaved case is covered by {@code OrderPipelineTest}; this one is covered by the second
   * scenario below.
   *
   * @param include which events to apply
   * @return the scenario
   */
  private static Consumer<RuleSession> feedOf(final Predicate<OrderEvent> include) {
    return session -> {
      final Ingest ingest = new Ingest(session);
      feed.stream().filter(include).forEach(ingest::apply);
    };
  }

  @Test
  @DisplayName("all three matchers produce the identical firing sequence on the whole feed")
  void matchersAgree() {
    final FiringSequence sequence = MatcherEquivalence.assertEquivalent(
        definitions, feedOf(event -> true),
        SessionOptions.builder()
            .function("alertOps", new OpsPager()),
        compilerOptions);

    /*
     * The sequence itself, not only that the three concur. Everything answered in the shared agenda
     * base -- negation, the universals, the fold, the §6.4 post-filter -- agrees BY CONSTRUCTION,
     * so an equivalence assertion alone cannot fail for those reasons. Assert what fired.
     */
    assertThat(sequence.describe())
        .contains("high-value-order-review")
        .contains("bulk-order-discount")
        .contains("ready-to-ship");
  }

  @Test
  @DisplayName("they agree about a rule that counts what another rule concluded")
  void matchersAgreeAboutDerivedFacts() {
    /*
     * The payments withheld, so two of C-2's orders are still unpaid when the harness fires and
     * `repeat-unpaid-customer` has something to count. Worth a scenario of its own: this is the one
     * path where a fold reads facts that a right-hand side inserted during the same fire loop, and
     * the three matchers arrive at it by three different routes.
     */
    final FiringSequence sequence = MatcherEquivalence.assertEquivalent(
        definitions, feedOf(event -> !"payment.received".equals(event.type())),
        SessionOptions.builder()
            .function("alertOps", new OpsPager()),
        compilerOptions);

    assertThat(sequence.describe()).contains("repeat-unpaid-customer");
  }

  @Test
  @DisplayName("rule declaration order does not reach the firing sequence")
  void declarationOrderIsIrrelevant() {
    ShuffleHarness.assertDeterministic(definitions, feedOf(event -> true),
        ShuffleHarness.DEFAULT_PERMUTATIONS,
        SessionOptions.builder()
            .function("alertOps", new OpsPager())
            .build(),
        compilerOptions);
  }
}
