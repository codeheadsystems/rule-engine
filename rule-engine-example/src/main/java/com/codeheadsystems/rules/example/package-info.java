/**
 * A worked in-process application: one rule file, one feed of events, and the deployment shapes
 * §5 of the specification describes.
 *
 * <p>Nothing here is engine machinery. It is the code a service would write around the engine, kept
 * in the build so that CI compiles it and fires it -- a teaching artifact that is not executed is a
 * teaching artifact that is wrong within two releases. Read
 * {@code rule-engine-example/README.md} first; it is the narrative, and every class here is
 * something it points at.
 *
 * <p>The four demos differ in <em>session scope</em>, which is the decision that shapes everything
 * else and the one a newcomer makes by accident:
 *
 * <ul>
 *   <li>{@link com.codeheadsystems.rules.example.PerOrderDemo} -- a session per order. The
 *       request-scoped default. A rule can only see what the session holds, so a rule spanning two
 *       orders cannot fire here at all.
 *   <li>{@link com.codeheadsystems.rules.example.BatchDemo} -- the same thing across virtual
 *       threads, one session each, through §5.2's {@code RuleBatches}.
 *   <li>{@link com.codeheadsystems.rules.example.StreamingDemo} -- one long-lived session fed by an
 *       actor, where cross-order rules do fire and where unbounded growth becomes the problem.
 *   <li>{@link com.codeheadsystems.rules.example.DiagnosticsDemo} -- the tools for the question a
 *       rule engine actually gets: why did that not fire?
 * </ul>
 */
package com.codeheadsystems.rules.example;
