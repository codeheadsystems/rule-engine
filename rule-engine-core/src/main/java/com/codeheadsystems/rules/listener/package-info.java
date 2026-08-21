/**
 * Observability hooks.
 *
 * <p>Spec §7 opens with the reason this package exists at all: "why did this rule fire?" and "why
 * didn't it?" are the questions a rule engine gets in production, from people who cannot read the
 * network -- and the reason engines acquire a reputation for opacity is rarely the algorithm, it is
 * that answering those questions after the fact is hard. Every mechanism here is cheap to design in
 * now and expensive to retrofit, because each needs a hook at a point the optimiser would otherwise
 * be free to elide.
 *
 * <p>Listeners are registered per session, so a listener is never shared mutable state across
 * sessions and nothing on the path synchronises.
 */
package com.codeheadsystems.rules.listener;
