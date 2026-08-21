package com.codeheadsystems.rules.bench;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.testkit.Rules;
import java.util.ArrayList;
import java.util.List;
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

/**
 * What it costs to turn rules into a {@code CompiledRuleSet}, which is a startup cost.
 *
 * <p><strong>These are warm, steady-state figures, and a service's first compile is not.</strong>
 * The rule-file schema is compiled once in a static holder and both Jackson mappers are static
 * finals, so a JMH average -- which is what a JVM does on its thousandth call, after warmup has
 * amortised every one-time cost to zero -- says nothing about the cold path. Startup cost is a
 * real question and this benchmark does not answer it; what it answers is how the three stages
 * compare to each other, and how each grows with rule count.
 *
 * <p>Three columns, and the gaps between them are the interesting part:
 *
 * <ul>
 *   <li>{@code compileFromAst} -- rules already built in Java. This is §6.5's pipeline alone:
 *       validation, accessor and regex compilation, node sharing, index plans, tested paths, the
 *       version hash and the report.
 *   <li>{@code parseRuleFile} -- text to {@code RuleDefinition} and no further. The DSL's own cost:
 *       Jackson, the schema gate, the operator maps, the source index.
 *   <li>{@code compileRuleFile} -- both, which is what a service actually pays.
 * </ul>
 *
 * <p>The rule count parameter matters because §6.5 claims node sharing keeps the alpha network
 * "sublinear in rule count" while being weaker for joins. These rules deliberately share their
 * constraints, so a compile cost that grows faster than linearly is the interesting failure.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CompilationBenchmarks {

  /** A rule set of a given size, in both the forms a caller can supply it. */
  @State(Scope.Thread)
  public static class RuleSet {

    /** How many rules to compile. */
    @Param({"10", "100"})
    public int rules;

    private List<RuleDefinition> built;
    private RuleSource source;

    @Setup(Level.Trial)
    public void setUp() {
      built = new ArrayList<>(rules);
      final StringBuilder yaml = new StringBuilder("apiVersion: rules.v1\nrules:\n");
      for (int index = 0; index < rules; index++) {
        /*
         * Every rule shares `total > 10000` and the Customer join; half share each `status`. So the
         * rule set has about four distinct constraints however many rules there are, which makes
         * the ALPHA NETWORK constant in rule count. That is a realistic shape, but note what it
         * cannot do: it cannot distinguish sublinear network growth from anything else, because
         * there is no growth to observe. What the rule count does drive here is the per-rule work --
         * pattern and terminal nodes, tested paths, the hash -- and that is what these columns
         * measure.
         */
        final String status = index % 2 == 0 ? "PENDING" : "SHIPPED";
        final int ruleIndex = index;
        built.add(Rules.rule("rule-" + ruleIndex)
            .when("o", "Order", pattern -> pattern.eq("status", status).gt("total", 10_000))
            .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
            .then(actions -> actions.emit("out-" + ruleIndex, "id", Rules.ref("o.id")))
            .build());
        yaml.append("""
              - id: rule-%d
                when:
                  - fact: Order
                    as: o
                    where:
                      status: { eq: "%s" }
                      total:  { gt: 10000 }
                  - fact: Customer
                    as: c
                    where:
                      id: { eq: { $ref: o.customerId } }
                then:
                  - action: emit
                    event: out-%d
                    payload:
                      id: { $ref: o.id }
            """.formatted(index, status, index));
      }
      source = RuleSource.yaml("bench.yaml", yaml.toString());
    }

    public List<RuleDefinition> built() {
      return built;
    }

    public RuleSource source() {
      return source;
    }
  }

  /** §6.5's pipeline alone, from rules already in memory. */
  @Benchmark
  public CompiledRuleSet compileFromAst(final RuleSet state) {
    return RuleCompiler.compile(state.built());
  }

  /** The DSL's own cost: text to rule definitions, stopping before the compiler. */
  @Benchmark
  public List<RuleDefinition> parseRuleFile(final RuleSet state) {
    return RuleFiles.parse(state.source());
  }

  /** Both stages together: parse, then compile. */
  @Benchmark
  public CompiledRuleSet compileRuleFile(final RuleSet state) {
    return RuleFiles.compile(state.source());
  }
}
