package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.TestedPaths;
import java.util.List;

/**
 * An immutable, thread-safe, compiled rule set (spec §5.1).
 *
 * <p>Building one is the expensive one-time step. Sharing one is free: thousands of concurrent
 * virtual threads reference the same instance with zero contention, because <strong>nothing about
 * it mutates after compile</strong>. That is spec invariant 1, and most of the design's non-obvious
 * choices exist to preserve it -- all per-session mutable state lives in the session, and the
 * shared structure holds plans, never data.
 *
 * <p>There is no mandatory build or packaging layer. This is just an object you get back from a
 * compile call; how you deploy it is your business. For hot reload, hold one in a
 * {@code volatile} field and swap the reference: a session keeps a strong reference to the rule set
 * it was created from, so in-flight sessions finish against the rules they started with, with no
 * torn state and no locking. Compile fully before publishing, so a compilation failure leaves the
 * previous version serving.
 */
public interface CompiledRuleSet {

  /**
   * Creates a session with default options.
   *
   * @return a fresh, single-writer session
   */
  RuleSession newSession();

  /**
   * Creates a session.
   *
   * @param options the session configuration
   * @return a fresh, single-writer session
   */
  RuleSession newSession(SessionOptions options);

  /**
   * The compiled rules, in compilation order.
   *
   * @return the rules
   */
  List<CompiledRule> rules();

  /**
   * Which payload paths the rule set reads.
   *
   * @return the compile-time tested-path artifact
   */
  TestedPaths testedPaths();

  /**
   * The rule set's identity: a content hash of the source rules plus the compiler version
   * (spec §5.6).
   *
   * <p>Stamped into every fire result and every emitted event. For anything audited, "which rules
   * produced this decision" is the question that actually gets asked, months later.
   *
   * @return the version string
   */
  String version();
}
