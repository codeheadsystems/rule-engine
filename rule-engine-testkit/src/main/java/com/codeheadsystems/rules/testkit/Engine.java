package com.codeheadsystems.rules.testkit;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compiles and runs a scenario in one call.
 *
 * <p>A scenario is a consumer that drives a session -- inserting, updating, retracting -- after
 * which the engine fires to completion and the firing sequence is captured. Writing scenarios this
 * way rather than as a data model keeps them ordinary Java that a test author can read, while still
 * making them replayable against a different engine implementation in a later phase: the same
 * consumer runs against any {@link CompiledRuleSet}.
 */
public final class Engine {

  private Engine() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Compiles rules.
   *
   * @param rules the definitions
   * @return the compiled rule set
   */
  public static CompiledRuleSet compile(final RuleDefinition... rules) {
    return RuleCompiler.compile(List.of(rules));
  }

  /**
   * Compiles rules, drives a scenario, and fires to completion with default options.
   *
   * @param scenario what to do to the session before firing
   * @param rules the definitions
   * @return the canonical firing sequence
   */
  public static FiringSequence run(final Consumer<RuleSession> scenario,
      final RuleDefinition... rules) {
    return run(compile(rules), SessionOptions.defaults(), scenario);
  }

  /**
   * Drives a scenario against an already-compiled rule set and fires to completion.
   *
   * @param ruleSet the compiled rules
   * @param options the session configuration
   * @param scenario what to do to the session before firing
   * @return the canonical firing sequence
   */
  public static FiringSequence run(final CompiledRuleSet ruleSet, final SessionOptions options,
      final Consumer<RuleSession> scenario) {
    return FiringSequence.of(result(ruleSet, options, scenario));
  }

  /**
   * Drives a scenario and returns the full result rather than the canonical sequence.
   *
   * @param ruleSet the compiled rules
   * @param options the session configuration
   * @param scenario what to do to the session before firing
   * @return the fire result
   */
  public static FireResult result(final CompiledRuleSet ruleSet, final SessionOptions options,
      final Consumer<RuleSession> scenario) {
    try (RuleSession session = ruleSet.newSession(options)) {
      scenario.accept(session);
      return session.fireAllRules();
    }
  }
}
