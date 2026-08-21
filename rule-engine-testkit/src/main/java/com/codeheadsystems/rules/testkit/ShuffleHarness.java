package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Asserts spec §7.3's determinism contract by shuffling the things that must not matter.
 *
 * <p>The contract is: <em>same rule set, same facts, same insertion order, same firing sequence</em>
 * -- on every host and every run. It is not free. It is a property you keep by getting a series of
 * small decisions right and lose permanently by getting any one wrong, and §7.3 is explicit that
 * the threat which actually bites is hash iteration order reaching the agenda, "because it usually
 * <em>looks</em> stable in testing".
 *
 * <p>So this harness varies what the contract says is irrelevant and asserts the output does not
 * move:
 *
 * <ul>
 *   <li><strong>Rule declaration order.</strong> Conflict resolution is defined as a total order on
 *       the match itself, so which order the rules were compiled in must not reach the firing
 *       sequence. If it does, the comparator is not total, or something is iterating a hash-ordered
 *       structure on the way to the agenda.
 *   <li><strong>Repetition.</strong> Two runs of the identical configuration must agree, which
 *       catches an identity hash or a clock read that a single run cannot.
 * </ul>
 *
 * <p>What it deliberately does <strong>not</strong> shuffle is fact insertion order. That is an
 * input to the contract, not an irrelevance: the guarantee is conditioned on it.
 */
public final class ShuffleHarness {

  /** Runs enough permutations to catch an ordering dependency without slowing the suite. */
  public static final int DEFAULT_PERMUTATIONS = 12;

  private ShuffleHarness() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Asserts that a scenario produces one firing sequence regardless of rule declaration order.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @return the firing sequence every permutation produced
   */
  public static FiringSequence assertDeterministic(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario) {
    return assertDeterministic(rules, scenario, DEFAULT_PERMUTATIONS, SessionOptions.defaults(),
        CompilerOptions.defaults());
  }

  /**
   * Asserts that a scenario produces one firing sequence regardless of rule declaration order.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @param compilerOptions how to compile -- needed by any rule set that registers an expression
   *     compiler (§6.4) or fact schemas (§2.3)
   * @return the firing sequence every permutation produced
   */
  public static FiringSequence assertDeterministic(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final CompilerOptions compilerOptions) {
    return assertDeterministic(rules, scenario, DEFAULT_PERMUTATIONS, SessionOptions.defaults(),
        compilerOptions);
  }

  /**
   * Asserts that a scenario produces one firing sequence regardless of rule declaration order.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @param permutations how many orderings to try. The seed is fixed, so a failure reproduces
   * @param options the session configuration
   * @return the firing sequence every permutation produced
   */
  public static FiringSequence assertDeterministic(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final int permutations,
      final SessionOptions options) {
    return assertDeterministic(rules, scenario, permutations, options, CompilerOptions.defaults());
  }

  /**
   * Asserts that a scenario produces one firing sequence regardless of rule declaration order.
   *
   * @param rules the rule set
   * @param scenario what to do to the session before firing
   * @param permutations how many orderings to try. The seed is fixed, so a failure reproduces
   * @param options the session configuration
   * @param compilerOptions how to compile
   * @return the firing sequence every permutation produced
   */
  public static FiringSequence assertDeterministic(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final int permutations,
      final SessionOptions options, final CompilerOptions compilerOptions) {
    final FiringSequence expected = run(rules, scenario, options, compilerOptions);
    // A fixed seed: a determinism test that is itself non-deterministic reports a different
    // permutation every time it fails, which makes the failure much harder to act on.
    final Random shuffler = new Random(20250820L);
    for (int attempt = 0; attempt < permutations; attempt++) {
      final List<RuleDefinition> permuted = new ArrayList<>(rules);
      Collections.shuffle(permuted, shuffler);
      final FiringSequence actual = run(permuted, scenario, options, compilerOptions);
      assertThat(actual)
          .describedAs(
              "firing sequence changed when the rules were declared in a different order "
                  + "(permutation %d). Expected:%n%s%nActual:%n%s%n"
                  + "Rule declaration order is not an input to the determinism contract, so "
                  + "something on the path to the agenda is iterating in an order that depends "
                  + "on it -- a HashSet or HashMap, or a conflict-resolution comparator that is "
                  + "not a total order.",
              attempt, expected.describe(), actual.describe())
          .isEqualTo(expected);
    }
    return expected;
  }

  /**
   * Compiles one ordering of the rules and runs the scenario against it.
   *
   * @param rules the rule set, in some order
   * @param scenario what to do to the session before firing
   * @param options the session configuration
   * @param compilerOptions how to compile
   * @return the firing sequence
   */
  private static FiringSequence run(final List<RuleDefinition> rules,
      final Consumer<RuleSession> scenario, final SessionOptions options,
      final CompilerOptions compilerOptions) {
    return Engine.run(RuleCompiler.compile(rules, compilerOptions), options, scenario);
  }
}
