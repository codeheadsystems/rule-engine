/**
 * Match representation -- tuples, activation keys and activations -- and the strategy that orders
 * them.
 *
 * <p><strong>This package is the matching contract, and that is what decides what may live in
 * it.</strong> A consumer names {@link com.codeheadsystems.rules.match.Activation} to implement
 * §7.1's listener or read a {@code FireRecord}, and names
 * {@link com.codeheadsystems.rules.match.ConflictResolutionStrategy} to plug into §4.2. The
 * predicates that decide whether a tuple <em>holds</em> are a different thing entirely: they are
 * shared between the agenda, truth maintenance and the explainer, and they live in
 * {@link com.codeheadsystems.rules.eval} so that being public to a sibling is not the same as
 * being published.
 *
 * <p>The three representation types are keyed on <em>identity</em> -- handles and rule ids -- never
 * on payload content. That is spec invariant 3 (§3.2.2), and it is the most important structural
 * decision in the matching design: there is exactly one place a payload lives, so nothing
 * downstream of matching can serve a stale one.
 *
 * <p>Both {@link com.codeheadsystems.rules.match.Tuple} and
 * {@link com.codeheadsystems.rules.match.ActivationKey} carry arrays, and both hand-write
 * {@code equals}/{@code hashCode} over them. A record's generated versions are <em>identity</em>-based
 * on an array component, which would silently defeat refraction entirely -- §10 audits this
 * specifically.
 */
package com.codeheadsystems.rules.match;
