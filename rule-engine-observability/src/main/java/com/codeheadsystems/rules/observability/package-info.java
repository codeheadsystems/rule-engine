/**
 * Listeners that make a running engine legible.
 *
 * <p>Spec section 7 opens with why this exists: "why did this rule fire?" and "why didn't it?" are
 * the questions a rule engine gets in production, from people who cannot read the network -- and
 * engines acquire a reputation for opacity not because of the algorithm but because answering those
 * questions after the fact is hard.
 *
 * <p>Section 7.1 asks for three implementations, because the interface alone is not the feature.
 * The no-op default lives in core and costs nothing. The other two are here.
 */
package com.codeheadsystems.rules.observability;
