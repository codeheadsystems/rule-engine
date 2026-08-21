/**
 * The Rete shape: joins materialised as facts arrive rather than recomputed per fire (spec §11.1's
 * option B, Phase 3 in §9).
 *
 * <p>The third matcher, and it exists for one workload the other two serve badly: the long-lived
 * streaming session, where a large working memory is re-evaluated repeatedly against a small delta.
 * TREAT re-joins everything at every fire cycle, which §4.1 accepts deliberately for the one-shot
 * and batch shapes v1 targets and which §11.1 calls "wasteful if a large fact set is re-evaluated
 * repeatedly with small deltas".
 *
 * <p><strong>It is the same engine, differing only in when the conflict set is computed.</strong>
 * §4.3 says so and this package is built to keep it true: {@code ReteAgenda} extends the same
 * {@code RecomputingAgenda} the other two do, so refraction, conflict resolution, expression
 * post-filtering, strict-mode checks and the firing loop are shared code rather than agreeing
 * implementations. What differs is that {@code matchesOf} reads a materialised memory instead of
 * walking the join.
 *
 * <p>§9's exit criterion for the phase is that TREAT and Rete produce identical firing sequences on
 * the same input, established by differential test against the shipped v1 engine. That is what
 * {@code MatcherEquivalence} does, and it is why the beta memory is maintained by the same join
 * walk the network matcher uses rather than a second one written to agree with it.
 *
 * <p><strong>What this package does not yet contain.</strong> §9 lists five things for Phase 3 and
 * this is two of them: the persistent beta memory, and its selection through {@code SessionOptions}.
 * The Rete <em>agenda</em> shape is not here -- §4.3 defines that as activations being pushed in and
 * pulled out as tokens arrive, and the conflict set is still replaced wholesale by
 * {@code markDirty} plus {@code materialise}, which is the TREAT agenda. Differential propagation
 * (§11.2), {@code fireUntilHalt} with a hardened {@code SessionActor} (§5.4) and fact eviction
 * (§4.4) are all still to come, and the first of those is what would turn the measured constant
 * factor into a better curve.
 */
package com.codeheadsystems.rules.rete;
