/**
 * The public API: compiled rule sets, sessions, options and results.
 *
 * <p>Spec §5.1's split is the concurrency primitive everything else builds on. A
 * {@link com.codeheadsystems.rules.session.CompiledRuleSet} is immutable, thread-safe, built once
 * and shared by every caller; a {@link com.codeheadsystems.rules.session.RuleSession} holds
 * <em>only</em> mutable state and is never shared across threads. That single-writer rule is what
 * lets the hot path skip locks entirely, and it is what makes "one virtual thread per session"
 * the natural unit of concurrency rather than pooling a small number of expensive stateful engine
 * instances.
 */
package com.codeheadsystems.rules.session;
