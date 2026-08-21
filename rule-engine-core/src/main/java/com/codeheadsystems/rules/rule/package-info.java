/**
 * The rule model: what a DSL front-end compiles into, and what the compiler turns it into.
 *
 * <p>Two layers live here, and the split is deliberate (spec §2.5).
 *
 * <p><strong>Definitions</strong> -- {@link com.codeheadsystems.rules.rule.RuleDefinition} and
 * everything it holds -- are post-parse, pre-compile. They are DSL-agnostic on purpose: a second
 * front-end (a text DSL, say) could be bolted on later without touching anything below this line.
 *
 * <p><strong>Compiled forms</strong> -- {@link com.codeheadsystems.rules.rule.CompiledRule} and
 * the {@link com.codeheadsystems.rules.rule.AlphaTest} / {@link com.codeheadsystems.rules.rule.JoinTest}
 * hierarchy -- are immutable, live in the shared compiled rule set, and are read concurrently by
 * every session (§3.2.3). Nothing at runtime should be re-deriving a compile-time fact from a
 * definition.
 */
package com.codeheadsystems.rules.rule;
