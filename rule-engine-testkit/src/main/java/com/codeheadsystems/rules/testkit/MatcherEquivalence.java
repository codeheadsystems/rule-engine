package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.function.Consumer;

/**
 * Asserts that two matchers produce the same firing sequence on the same input.
 *
 * <p>This is what Phase 0 was built for. Spec §9 gives every phase after Phase 0 the same exit
 * criterion -- results identical to the naive oracle -- and §11.5 states the stake plainly: if two
 * matching strategies can diverge, "the choice of session type silently changes business outcomes"
 * and every argument about picking one per workload collapses.
 *
 * <p>The comparison is deliberately over the whole {@link FiringSequence}, not just the set of
 * rules that fired: which rule fired, on which facts, <em>in what order</em>, with what effects and
 * what events. A matcher that finds the right matches in the wrong order has broken the determinism
 * contract just as surely as one that finds the wrong matches.
 *
 * <p>Both matchers are driven through the identical scenario against separately compiled rule sets,
 * so a network that corrupted its own memories cannot hide behind shared state.
 */
public final class MatcherEquivalence {

  private MatcherEquivalence() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Runs a scenario under both matchers and asserts they agree.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @return the firing sequence both matchers produced
   */
  public static FiringSequence assertEquivalent(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario) {
    return assertEquivalent(rules, scenario, SessionOptions.builder());
  }

  /**
   * Runs a scenario under both matchers and asserts they agree.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @param options session configuration, which the harness completes with the matcher choice
   * @return the firing sequence both matchers produced
   */
  public static FiringSequence assertEquivalent(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final SessionOptions.Builder options) {
    return assertEquivalent(rules, scenario, options, CompilerOptions.defaults());
  }

  /**
   * Runs a scenario under both matchers and asserts they agree.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @param options session configuration, which the harness completes with the matcher choice
   * @param compilerOptions how to compile -- needed by any rule set that registers an expression
   *     compiler (§6.4) or fact schemas (§2.3), which otherwise cannot be checked for equivalence
   *     at all
   * @return the firing sequence both matchers produced
   */
  public static FiringSequence assertEquivalent(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final SessionOptions.Builder options,
      final CompilerOptions compilerOptions) {
    final CompiledRuleSet forOracle = RuleCompiler.compile(rules, compilerOptions);
    final CompiledRuleSet forNetwork = RuleCompiler.compile(rules, compilerOptions);

    final FiringSequence oracle = Engine.run(forOracle,
        options.matching(MatchingStrategy.NAIVE).build(), scenario);
    final FiringSequence network = Engine.run(forNetwork,
        options.matching(MatchingStrategy.NETWORK).build(), scenario);

    assertThat(network)
        .describedAs("""
            The network matcher and the naive oracle disagree.

            Oracle:
            %s

            Network:
            %s

            One of them is wrong, and the oracle is the definition -- it rescans and re-tests \
            everything, so it has no index to corrupt and no memory to leave stale. Suspect the \
            network first: an alpha test that is shared when it should not be, a pattern memory \
            that was not updated on retract, or an index probe that narrowed away a real match.""",
            oracle.describe(), network.describe())
        .isEqualTo(oracle);

    /*
     * The third shape, and §9's exit criterion for Phase 3 stated as an assertion: "TREAT and Rete
     * produce identical firing sequences on the same input ... established by differential test
     * against the v1 engine, which by then is a shipped oracle, not a thought experiment".
     *
     * Checked here rather than in a test of its own so that every scenario any caller already
     * writes covers it. A separate Rete suite would cover what someone remembered to write twice.
     */
    final FiringSequence rete = Engine.run(RuleCompiler.compile(rules, compilerOptions),
        options.matching(MatchingStrategy.RETE).build(), scenario);

    assertThat(rete)
        .describedAs("""
            The Rete matcher disagrees with the naive oracle.

            Oracle:
            %s

            Rete:
            %s

            Rete materialises matches as facts arrive instead of re-joining at fire time, so the \
            failure is almost always in maintenance rather than in matching: a beta memory that \
            kept a match whose fact was retracted, a pinned walk that missed completions because \
            the fact was not yet in its pattern memory when it ran, or the same match derived \
            twice through two positions of one type and not deduplicated.""",
            oracle.describe(), rete.describe())
        .isEqualTo(oracle);
    return network;
  }
}
