/**
 * The expression escape hatch (spec §6.4), as an interface {@code -core} can live without.
 *
 * <p>§6.3 keeps operator maps the default because the engine can only index a constraint it can
 * statically decompose into {@code (field, operator, literal)}. An expression buys the things that
 * shape cannot say -- nested boolean logic, arithmetic across fields -- and pays for them by
 * dropping off the indexed path. §6.4 wants that cost <em>explicit</em>, which is why an expression
 * is opt-in syntax rather than a default, and why it appears in the compiler report.
 *
 * <p>{@code -core} declares the shape and no more: the implementation is {@code rule-engine-cel},
 * which nobody has to depend on. That follows {@code FactSchemas}, {@code TestedPaths} and
 * {@code HostFunction}, and it matters more here than for any of them -- CEL brings protobuf,
 * guava and antlr, and none of that belongs on the classpath of a rule set that never writes an
 * expression.
 */
package com.codeheadsystems.rules.expr;
