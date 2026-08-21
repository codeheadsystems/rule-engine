package com.codeheadsystems.rules.session;

/**
 * Which matcher a session uses.
 *
 * <p>Spec §7.5 warns against a selector with one valid position -- "dead config, a knob with one
 * position, which readers reasonably assume has two". This one has two, and the second is not
 * hypothetical: §9 makes every phase after Phase 0 exit on producing firing sequences identical to
 * the naive matcher's, and that claim is only checkable if both matchers can be run over the same
 * input from the same rule set.
 */
public enum MatchingStrategy {

  /**
   * The compiled network: shared alpha tests, incrementally maintained pattern memories, indexed
   * join probes. The default, and what production should use.
   */
  NETWORK,

  /**
   * The naive matcher: rescan every fact of a type and re-test it, every fire cycle.
   *
   * <p><strong>Do not use this in production.</strong> Its cost is
   * {@code O(rules x facts^arity)} by construction. It exists as the correctness oracle -- the
   * reference implementation every optimisation is differential-tested against -- and as a
   * debugging tool for when the network is the thing under suspicion.
   */
  NAIVE
}
