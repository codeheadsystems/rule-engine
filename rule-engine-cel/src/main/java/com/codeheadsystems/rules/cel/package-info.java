/**
 * §6.4's expression escape hatch, backed by <a href="https://cel.dev/">CEL</a>.
 *
 * <p>§6.4 picked CEL over MVEL, SpEL and Groovy for a specific reason: those are general-purpose
 * scripting languages, and this slot wants "fast, safe, compile-once-evaluate-often, no side
 * effects". CEL is non-Turing-complete, guaranteed to terminate, and sandboxed by construction.
 *
 * <p>§6.4 is also careful not to overclaim, and this implementation has to be equally careful.
 * <strong>CEL guarantees termination, not linear time.</strong> Its comprehension macros mean two
 * nested comprehensions over two lists are O(n·m), which is why {@link CelExpressions} bounds
 * comprehension iterations rather than trusting the language guarantee.
 *
 * <p>One place this implementation departs from what §6.4 assumed. §6.4 says {@code dev.cel} "ships
 * a static cost estimator and a runtime cost limit" and to set both. As of dev.cel 0.14.0 it ships
 * neither -- there is no cost API anywhere in the artifact. What it does ship is a bound on
 * comprehension iterations, on parse recursion depth and on expression node count, and those are
 * what {@link CelExpressions} sets. The compile-time estimate is this module's own, and says so.
 */
package com.codeheadsystems.rules.cel;
