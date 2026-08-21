/**
 * Optional fact-payload schemas (spec §2.3).
 *
 * <p>The engine is schema-agnostic by default and every v1 primitive works with zero schema
 * definitions. This package is the opt-in layer, and it is an <em>interface</em> here with the
 * implementation in {@code rule-engine-schema}, following the same shape as {@code TestedPaths},
 * {@code HostFunction} and {@code EventSink}: {@code -core} declares what the engine needs, and a
 * module nobody has to depend on supplies it.
 *
 * <p><strong>This deviates from §2.3's sketch, deliberately.</strong> That sketch has
 * {@code schemaFor} returning {@code com.networknt}'s {@code JsonSchema}, which would put a JSON
 * Schema library on the compile classpath of everything that uses this engine. {@code -core} has
 * exactly two runtime dependencies today, each argued for at length, and a third that only schema
 * users need does not clear that bar. So the interface speaks in this engine's own vocabulary and
 * the library stays where it is used.
 */
package com.codeheadsystems.rules.schema;
