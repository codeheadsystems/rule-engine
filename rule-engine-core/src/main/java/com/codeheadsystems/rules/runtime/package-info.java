/**
 * The running implementations of {@code session}'s two contract interfaces, and the per-session
 * machinery only they use.
 *
 * <p><strong>Internal. Nothing outside this repository should name anything here.</strong> A
 * consumer gets a {@link com.codeheadsystems.rules.session.CompiledRuleSet} from the compiler and a
 * {@link com.codeheadsystems.rules.session.RuleSession} from
 * {@code CompiledRuleSet.newSession()}; the classes behind those two interfaces are an
 * implementation detail and this package is where saying so became possible.
 *
 * <p>It exists because the boundary is drawn at package granularity -- that is what JPMS gives you,
 * and what {@code ApiSurfaceTest} enforces in its place (spec §8.1). {@code DefaultCompiledRuleSet}
 * has to hand the compiled node graph to {@code DefaultRuleSession}, and {@code -compiler} has to
 * hand it to {@code DefaultCompiledRuleSet}; while those types sat in the exported {@code session}
 * package, saying that meant putting {@code network.Network} on a public signature -- including on
 * {@code CompiledRuleSet} itself, an interface a consumer reads. §7.4's {@code CompilerReport} is
 * the supported introspection; the node graph never was.
 *
 * <p>{@code RuleSetFingerprint} and {@code SessionEvictor} came with them rather than for their own
 * sake: both were already package-private, and both are reached only by the two implementations.
 *
 * <p><strong>{@code SessionIds} is the one that was not.</strong> It was {@code public} in the
 * exported {@code session} package purely so a sibling could call it, which is the shape §8.1 is
 * about -- and its own Javadoc schedules its deletion for whenever the build reaches JDK 26. Left
 * where it was, a fifteen-line stopgap would have been a published surface, and removing it would
 * have meant a major version. It is the only type in this move whose visibility actually shrank.
 */
package com.codeheadsystems.rules.runtime;
