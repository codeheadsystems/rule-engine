/**
 * Truth maintenance: retracting what a firing concluded once its reason stops holding (spec §4.4's
 * amendment).
 *
 * <p>§1 deferred this and named the ordering dependency it expected -- "truth maintenance and
 * negation want to land together, since the strongest motivation for the former is retracting
 * matches justified by an absence". Negation landed alone and carried the cost as a documented
 * boundary; this package is that boundary being paid off.
 *
 * <p>Two pieces and no more. {@link com.codeheadsystems.rules.truth.Justifications} is the graph --
 * which firing concluded which fact, and what is left holding a fact up when one of them goes. {@link
 * com.codeheadsystems.rules.truth.TruthMaintenance} is the pass that re-asks whether each
 * justification still holds and retracts what nothing supports any more.
 *
 * <p>Neither decides what a match <em>is</em>. That question is
 * {@link com.codeheadsystems.rules.eval.TupleMatch}'s, in the shared evaluation package, asked by
 * the agenda too so a revalidation and a match cannot disagree about the same tuple.
 */
package com.codeheadsystems.rules.truth;
