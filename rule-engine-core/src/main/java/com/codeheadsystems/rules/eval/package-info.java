/**
 * The predicates that decide whether a complete tuple still holds -- shared, and internal.
 *
 * <p><strong>Every public type here is public for one reason and it is not the API.</strong> §2.5's
 * quantifiers and §4.4's truth maintenance all ask the same questions of the same tuple, from four
 * places: {@code RecomputingAgenda} deciding what fires, §4.4's {@code TruthMaintenance}
 * revalidating a justification, §7.2's {@code MatchExplainer} explaining why nothing did, and
 * {@code RhsExecutor} folding an accumulate at read time. Those answers must not be able to
 * disagree, so the predicate lives once and every caller asks it -- which in a language without
 * {@code internal} means {@code public}.
 *
 * <p>All seven -- six of them public, plus package-private {@code PatternTests} -- were in
 * {@code match} until the pre-publish API pass, beside {@link
 * com.codeheadsystems.rules.match.Activation} and {@link
 * com.codeheadsystems.rules.match.ActivationKey} -- which <em>are</em> contract, named by §7.1's
 * listener interface, {@code FireRecord}, {@code RhsErrorHandler} and §4.2's conflict-resolution
 * strategy. One package holding both meant the boundary could not be drawn at package granularity,
 * which is the granularity JPMS gives you and the granularity {@code ApiSurfaceTest} enforces. So
 * they were split: {@code match} is what a consumer names, this is what the engine shares with
 * itself.
 *
 * <p>Nothing outside this project should import from here. {@code ApiSurfaceTest} is what says so.
 */
package com.codeheadsystems.rules.eval;
