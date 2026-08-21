package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.cel.CelExpressions;
import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.testkit.Facts;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * What §6.4's escape hatch actually costs, against the operator map it replaces.
 *
 * <p>§6.4 argues that an expression is an "explicit, visible cost" and makes one quantified claim
 * about it: "an unindexed CEL condition against 100 000 facts is 100 000 evaluations per fire
 * cycle. Cheap-per-call is not cheap." That is the claim this class exists to put a number on,
 * because a cost an author is told to accept should be a cost somebody has measured.
 *
 * <p><strong>Only the fire cycle is measured.</strong> Loading the session happens in
 * per-invocation setup, and that placement is the entire point of the benchmark rather than a
 * detail: the two forms do their work at different <em>times</em>, and a measured region containing
 * the inserts would be dominated by the term they share.
 *
 * <ul>
 *   <li>{@code OPERATOR_MAP} -- {@code total: { gt: N }} is an alpha test, applied once as each
 *       fact enters the pattern memory. By fire time it has already happened, and the facts that
 *       failed it are not candidates. So this column is close to the floor: what a fire cycle costs
 *       when the indexed constraints have already eliminated everything.
 *   <li>{@code EXPRESSION} -- the same predicate written as {@code condition:}. §6.4 makes it an
 *       unindexed post-filter, so the pattern memory holds every fact and each one is evaluated,
 *       every cycle.
 *   <li>{@code EXPRESSION_VALUE} -- the other position, expected to be uninteresting for exactly
 *       the reason it exists: a value runs once per firing rather than once per candidate.
 * </ul>
 *
 * <p>What the operator map pays instead is one alpha test per insert, which
 * {@code EngineBenchmarks.comparison} already measures at a little over a nanosecond. That is the
 * other half of the comparison and it does not belong on this clock.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ExpressionBenchmarks {

  /** A session pre-loaded with facts, rebuilt per invocation so each fire cycle starts cold. */
  @State(Scope.Thread)
  public static class Firing {

    /**
     * How many facts the fire cycle has to consider.
     *
     * <p>Both values are large enough that even the cheaper arm's measured region stays well above
     * a microsecond, which is what makes per-invocation setup safe to use here.
     */
    @Param({"1000", "10000"})
    public int facts;

    /** Which constraint form this invocation should set up. */
    @Param({"OPERATOR_MAP", "EXPRESSION", "EXPRESSION_VALUE"})
    public String form;

    private CompiledRuleSet operatorMap;
    private CompiledRuleSet expression;
    private CompiledRuleSet expressionValue;
    private RuleSession session;

    @Setup(Level.Trial)
    public void setUpTrial() {
      final CompilerOptions options = CompilerOptions.builder()
          .expressions(CelExpressions.create())
          .build();

      operatorMap = RuleCompiler.compile(List.of(Rules.rule("via-operator-map")
          .when("o", "Order", pattern -> pattern.gt("total", 500_000))
          .then(actions -> actions.emit("hit", "id", Rules.ref("o.id")))
          .build()), options);

      final RuleDefinition viaExpression = Rules.rule("via-expression")
          .when("o", "Order", pattern -> pattern.constraint(
              new ExpressionConstraint("o.total > 500000", Set.of("o"))))
          .then(actions -> actions.emit("hit", "id", Rules.ref("o.id")))
          .build();
      expression = RuleCompiler.compile(List.of(viaExpression), options);

      // One firing, so the value expression is evaluated once however many facts exist.
      expressionValue = RuleCompiler.compile(List.of(Rules.rule("value-expression")
          .when("o", "Order", pattern -> pattern.eq("id", 0))
          .then(actions -> actions.emit("computed", "v",
              new com.codeheadsystems.rules.rule.ExpressionValue(
                  "o.total * 2 + o.id", Set.of("o"))))
          .build()), options);
    }

    /**
     * Opens a session and loads it, <strong>outside</strong> the measured region.
     *
     * <p>This is the whole correction. An earlier version called this from inside the benchmark
     * methods, and {@code session.insert} then accounted for 96-98% of the cheaper arm -- so the
     * headline ratio was {@code (insert + expression) / (insert + almost nothing)}, a figure that
     * moved when the insert path changed and had almost nothing to do with either constraint form.
     * {@code EngineBenchmarks.selectiveJoin} had already learned this and hoists its population for
     * the same reason.
     *
     * <p>Nothing matching is the interesting shape, not a degenerate one: it is what makes every
     * fact a <em>candidate</em> the condition must be evaluated against, which is the cost §6.4
     * warns about. A workload whose indexed constraints eliminated everything first would measure
     * the index.
     */
    @Setup(Level.Invocation)
    public void setUpInvocation() {
      if (session != null) {
        session.close();
      }
      session = switch (form) {
        case "OPERATOR_MAP" -> operatorMap.newSession();
        case "EXPRESSION" -> expression.newSession();
        default -> expressionValue.newSession();
      };
      for (int index = 0; index < facts; index++) {
        session.insert("Order", Facts.obj("id", index, "total", 1_000));
      }
    }

    public RuleSession session() {
      return session;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (session != null) {
        session.close();
        session = null;
      }
    }
  }

  /**
   * One fire cycle, with the constraint form chosen by the {@code form} parameter.
   *
   * <p>One benchmark rather than three, because the three forms need different sessions and the
   * session has to be built in setup. The {@code form} parameter is what selects it; read the
   * result rows by that column.
   *
   * @param state the loaded session
   * @return the fire result, so nothing is optimised away
   */
  @Benchmark
  public FireResult fireCycle(final Firing state) {
    return state.session().fireAllRules();
  }
}
