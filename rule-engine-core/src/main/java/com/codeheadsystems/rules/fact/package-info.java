/**
 * The fact model and working memory.
 *
 * <p>Three jobs, three mechanisms, and spec §2.1 is emphatic that they should not be conflated:
 * {@link com.codeheadsystems.rules.fact.FactHandle} gives identity,
 * {@link com.codeheadsystems.rules.fact.Fact#recency()} gives order, and the session's UUID gives
 * global correlation.
 */
package com.codeheadsystems.rules.fact;
