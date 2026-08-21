/**
 * Value semantics: comparison and canonicalisation.
 *
 * <p>"Why didn't my rule fire?" is the most common question a rule engine gets, and the answer is
 * almost always an undefined comparison edge case (spec §2.6.1). This package defines them once,
 * in one place, so that the naive matcher, the Phase 1 indexes and the Phase 2 join probes cannot
 * disagree about what equality means.
 *
 * <p>Two rules carry most of the weight:
 *
 * <ul>
 *   <li><strong>Absent and null are different values.</strong> Jackson gives
 *       {@code MissingNode} for "the path isn't there" and {@code NullNode} for
 *       {@code "field": null}. Both look falsy; conflating them makes {@code eq: null} mean
 *       different things depending on how the producer serialised.
 *   <li><strong>Numeric keys are canonicalised through {@code stripTrailingZeros()} before they
 *       are hashed</strong>, because {@code BigDecimal.equals} is scale-sensitive and would
 *       otherwise put {@code 10000} and {@code 10000.0} in different index buckets -- exactly the
 *       bug canonicalisation was meant to fix, failing silently as a rule that never matches.
 * </ul>
 */
package com.codeheadsystems.rules.value;
