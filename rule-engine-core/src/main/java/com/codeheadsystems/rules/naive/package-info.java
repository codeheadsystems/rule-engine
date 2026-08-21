/**
 * The Phase 0 naive matcher: the correctness oracle.
 *
 * <p>This package deliberately contains no network, no indexes and no incremental maintenance. It
 * enumerates candidates, tests them, and builds matches -- and its cost is
 * {@code O(rules x facts^arity)}, which is exactly what spec §3.1's "naive re-scan" row says it
 * should be.
 *
 * <p>That is the point. §9 makes Phase 0 a real deliverable because it is the correctness oracle
 * every later phase is differential-tested against, and the baseline that proves each optimisation
 * actually helped. §11.5 makes the same argument from the other direction: the Phase 3 exit
 * criterion is that two matching strategies produce identical firing sequences, and establishing
 * that against a shipped, exercised implementation is a much better position than anticipating in
 * a design document which mechanisms two hypothetical implementations would need to share.
 *
 * <p><strong>So keep this readable over fast.</strong> A clever naive matcher is a contradiction:
 * every optimisation here is a place the oracle and the thing it is checking could be wrong in the
 * same way.
 */
package com.codeheadsystems.rules.naive;
