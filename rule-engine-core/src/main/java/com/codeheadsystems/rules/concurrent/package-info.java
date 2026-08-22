/**
 * Running the engine concurrently, and swapping its rules while it runs (spec §5).
 *
 * <p>In {@code -core} rather than a module of its own, which §8 argues for directly: these are "a
 * few hundred lines with no dependencies beyond the JDK, and a module boundary there buys nothing
 * while making 'how do I run this concurrently' an extra artifact to discover".
 *
 * <p>Phase 3 adds {@code SessionActor} here: one long-lived session owned by one worker thread,
 * fed by a bounded inbox, for §11.1's streaming shape. It rests on the same split -- producers
 * never touch the session, they enqueue -- and is the one place in this package where a session
 * outlives the call that created it.
 *
 * <p>Everything here rests on §5.1's split and adds nothing to it. A {@code CompiledRuleSet} is
 * immutable and shared; a {@code RuleSession} is single-writer and cheap. That is what makes the
 * concurrency story "one virtual thread per session" rather than a locking scheme, and it is why
 * {@link com.codeheadsystems.rules.concurrent.RuleSetHolder} needs one volatile field and no locks.
 */
package com.codeheadsystems.rules.concurrent;
