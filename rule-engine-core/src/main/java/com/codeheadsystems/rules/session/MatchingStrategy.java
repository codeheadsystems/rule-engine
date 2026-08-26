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
  NAIVE,

  /**
   * The streaming matcher: joins are materialised as facts arrive and maintained across inserts
   * (§11.1's option B, Phase 3).
   *
   * <p>For the long-lived session re-evaluating a large working memory against a small delta, which
   * is the workload {@link #NETWORK} serves badly -- §4.1 recomputes every dirty rule's joins at
   * every fire cycle, deliberately, because that is the right trade for the one-shot and batch
   * shapes v1 targets.
   *
   * <p><strong>A better curve, not merely a constant factor.</strong> The join is paid once per
   * fact here instead of once per fire cycle, and since §4.3's shape landed the conflict set is
   * <em>pushed and pulled</em> rather than rebuilt: a match enters when derived and leaves when it
   * fires, so a fire cycle ranks what is waiting rather than everything held. That is what stopped
   * the fire cycle from growing with the working set at all -- measured at 0.77-1.12us across a
   * sixteenfold range, where a rebuilt conflict set had been 19.9us-551.4us. Benchmark it against
   * your own workload rather than switching on the strategy name; see {@code docs/benchmarks.md}
   * for both columns and for what the measurement does not establish.
   *
   * <p><strong>Not a faster {@code NETWORK}, a different trade.</strong> A batch session that
   * inserts once and fires once does the same join work either way and additionally pays to
   * maintain a memory it reads exactly once. It also holds that memory: a streaming session that
   * never retracts grows without bound, which is a property of the workload rather than a defect,
   * and what §4.4's eviction exists for.
   *
   * <p>Held to the other two by {@code MatcherEquivalence}: §9's exit criterion for Phase 3 is that
   * this and the TREAT shape produce identical firing sequences on the same input.
   */
  RETE
}
