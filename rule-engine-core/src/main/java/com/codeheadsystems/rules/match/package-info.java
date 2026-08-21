/**
 * Match representation: tuples, activation keys and activations.
 *
 * <p>Everything here is keyed on <em>identity</em> -- handles and rule ids -- never on payload
 * content. That is spec invariant 3 (§3.2.2), and it is the most important structural decision in
 * the matching design: there is exactly one place a payload lives, so nothing downstream of
 * matching can serve a stale one.
 *
 * <p>Both {@link com.codeheadsystems.rules.match.Tuple} and
 * {@link com.codeheadsystems.rules.match.ActivationKey} carry arrays, and both hand-write
 * {@code equals}/{@code hashCode} over them. A record's generated versions are <em>identity</em>-based
 * on an array component, which would silently defeat refraction entirely -- §10 audits this
 * specifically.
 */
package com.codeheadsystems.rules.match;
