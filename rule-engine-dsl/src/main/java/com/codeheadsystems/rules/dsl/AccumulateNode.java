package com.codeheadsystems.rules.dsl;

import tools.jackson.databind.JsonNode;

/**
 * The {@code accumulate} block of a pattern, as written (spec §2.5's second amendment).
 *
 * <p>Exactly one of the five function keys, so the shape reads as the thing it computes rather than
 * as a function name and an argument: {@code sum: "qty"} rather than
 * {@code function: sum, field: qty}. {@code count} takes no field and is written {@code count: true}
 * for the same reason {@code hasField} carries its polarity in the literal -- a key whose value is
 * ignored would be a key an author expects to mean something.
 *
 * <p>{@code having} is the optional test on the answer, written as §6.2.1 writes an operator map so
 * that {@code { gt: 100 }} means beside an accumulate what it means beside a field.
 *
 * @param sum the dotted field to total, or null
 * @param count true to count the facts in scope, or null
 * @param min the dotted field to take the minimum of, or null
 * @param max the dotted field to take the maximum of, or null
 * @param average the dotted field to average, or null
 * @param having a one-entry operator map testing the answer, or null
 */
record AccumulateNode(String sum, Boolean count, String min, String max, String average,
    JsonNode having) {
}
