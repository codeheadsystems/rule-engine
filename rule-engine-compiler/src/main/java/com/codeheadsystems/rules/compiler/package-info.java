/**
 * Turns rule definitions into an immutable compiled rule set.
 *
 * <p>This is the Phase 0 subset of spec §6.5's pipeline: semantic validation, the implicit
 * inequality between same-type aliases, literal and accessor compilation, the tested-path artifact
 * and its inverse index, and the rule-set version hash. It deliberately does <em>not</em> do the
 * network work -- node sharing, node id assignment, the index plan -- because Phase 0 has no
 * network. Those arrive in Phase 1, and keeping the module boundary honest from day one gives them
 * somewhere to go without moving code.
 *
 * <p>Everything this module produces is immutable and is read concurrently by every session
 * (invariant 1, §0). A rule set is source code and deserves the same treatment: compilation errors
 * fail the build.
 */
package com.codeheadsystems.rules.compiler;
