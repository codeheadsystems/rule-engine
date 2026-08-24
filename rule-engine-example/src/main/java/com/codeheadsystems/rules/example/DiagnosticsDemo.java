package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.cel.CelExpressions;
import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.concurrent.RuleSetHolder;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.observability.Explanation;
import com.codeheadsystems.rules.observability.MatchExplainer;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;

/**
 * The three questions a rule engine actually gets in production, and what answers each.
 *
 * <p><strong>"Is this rule set sane?"</strong> -- {@link CompilerReport}, at build time. It names
 * every constraint no index can serve, how much node sharing actually happened, and which rules
 * nothing can activate. Assert on it in CI and you find out the day somebody writes a join that
 * quietly turns a hash probe into a linear scan, which is invisible in review and obvious in
 * production.
 *
 * <p><strong>"Why did that not fire?"</strong> -- {@link MatchExplainer}. A rule that did not fire
 * leaves nothing in a log to look at, because the fast path is optimised precisely not to record
 * what it eliminated. The explainer re-evaluates the constraints one at a time against working
 * memory: slower than matching, and the only thing that can name the constraint that emptied the
 * set. There is one thing it cannot see, and it says so below.
 *
 * <p><strong>"What will this change do?"</strong> -- a dry run, then a hot swap. A dry run matches
 * and resolves conflicts and executes nothing, so it answers "what would fire, in what order, on
 * these facts" -- diff that against the current rule set's answer. {@link RuleSetHolder} is then how
 * the new rules go live without a restart.
 */
public final class DiagnosticsDemo {

  private DiagnosticsDemo() {
    throw new AssertionError("static helper");
  }

  /**
   * Runs all three.
   *
   * @param rules the compiled rule set
   */
  public static void run(final CompiledRuleSet rules) {
    report(rules);
    explain(rules);
    dryRunAndSwap(rules);
  }

  /**
   * Prints the compiler's own view of the rule set.
   *
   * @param rules the compiled rule set
   */
  private static void report(final CompiledRuleSet rules) {
    final CompilerReport report = rules.report();
    System.out.println("  compiler report (assert on this in CI, not at runtime):");
    report.describe().lines().forEach(line -> System.out.printf("    %s%n", line));
    /*
     * Printed here; ASSERTED in ExampleRulesTest, which is where it belongs -- a report read at
     * runtime is a report nobody reads. Two assertions are worth wiring into any build:
     *   unreachableRules().isEmpty()  -- a rule nothing can activate is almost always a fact type
     *                                    spelled one way here and another way in Ingest
     *   the unindexed list, as an ALLOWLIST rather than as isEmpty()  -- `ne`, a CEL condition and
     *                                    a temporal join can never be indexed, so isEmpty() is a
     *                                    test that cannot pass and therefore gets deleted. The
     *                                    value is in the diff: a NEW residual join gets named.
     */
  }

  /**
   * Asks why {@code ready-to-ship} does not fire for O-2 before the restock.
   *
   * @param rules the compiled rule set
   */
  private static void explain(final CompiledRuleSet rules) {
    final List<OrderEvent> feed = EventFeed.load();
    final List<OrderEvent> beforeRestock = EventFeed.forOrder(feed, "O-2").stream()
        .filter(event -> !"item.restocked".equals(event.type()))
        .toList();

    try (RuleSession session =
             rules.newSession(OrderRules.options(new OpsPager(), MatchingStrategy.NETWORK))) {
      final Ingest ingest = new Ingest(session);
      beforeRestock.forEach(ingest::apply);
      session.fireAllRules();

      final Explanation why = new MatchExplainer(rules, session).explain("ready-to-ship");
      System.out.println("  why did ready-to-ship not fire for O-2?");
      why.describe().lines().forEach(line -> System.out.printf("    %s%n", line));
      /*
       * The explainer names the LineItem standing in the way of the forAll, which is the answer a
       * trace cannot give. What it cannot see is eviction: it re-asks the same question of the same
       * working memory the engine did, so over an evicted type it is fooled identically. It warns
       * rather than detecting -- a verdict on a rule that matched while a type it quantifies over
       * was being evicted carries the count.
       */
    }
  }

  /**
   * Shows a dry run against a changed rule set, then swaps it in.
   *
   * @param rules the rule set in service
   */
  private static void dryRunAndSwap(final CompiledRuleSet rules) {
    /*
     * A threshold change: flag orders over $1,000 rather than over $5,000. Compiled from text here
     * so the example stays one file, but the shape is the real one -- the new rules are COMPILED
     * before they are published, which is the whole contract of RuleSetHolder.publish: a rule file
     * with a typo in it fails at compile and the engine stays in service on the rules it has.
     */
    final CompiledRuleSet proposed = RuleFiles.compile(
        List.of(RuleSource.yaml("orders.yaml (proposed)",
            OrderRules.read(OrderRules.RESOURCE).replace("gt: 500000", "gt: 100000"))),
        CompilerOptions.builder()
            .declaredFunctions(OrderRules.FUNCTIONS)
            .declaredFactTypes(OrderRules.FACT_TYPES)
            .expressions(CelExpressions.create())
            .build());

    final List<OrderEvent> feed = EventFeed.load();
    final SessionOptions dryRun = SessionOptions.builder()
        .function("alertOps", new OpsPager())
        .dryRun(true)
        .build();
    try (RuleSession session = proposed.newSession(dryRun)) {
      final Ingest ingest = new Ingest(session);
      EventFeed.forOrder(feed, "O-3").forEach(ingest::apply);
      final FireResult result = session.fireAllRules();
      System.out.printf("  dry run of the proposed rules on O-3 would fire: %s%n",
          result.fired().stream().map(record -> record.key().ruleId()).toList());
      /*
       * Nothing happened to working memory and nothing reached the event sink. A dry run stages
       * every action and applies none, which is what makes it safe to run against a production
       * snapshot -- and why the events it reports are what it WOULD have emitted.
       */
    }

    final RuleSetHolder holder = new RuleSetHolder(rules);
    System.out.printf("  in service: %s%n", holder.current().version());
    holder.publish(proposed);
    System.out.printf("  published:  %s (new sessions only; sessions already open keep the old"
        + " rules for their whole life)%n", holder.current().version());
    /*
     * One volatile field and no locks. A session already running is NOT migrated -- SessionDrain is
     * the tool for that, and it replays in handle-id order and skips derived facts, because the new
     * session re-derives them and replaying would double-count.
     */
  }
}
