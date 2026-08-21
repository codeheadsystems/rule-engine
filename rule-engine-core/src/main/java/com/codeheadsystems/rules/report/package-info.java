/**
 * What the compiler learned while compiling, as data (spec §7.4).
 *
 * <p>These types live in {@code -core} while the code that fills them lives in {@code -compiler},
 * which is the arrangement {@code TestedPaths} and {@code Network} already use: the type is read
 * off a {@code CompiledRuleSet}, which is a {@code -core} type, so a report reachable from it
 * cannot live downstream of it. §8's placement of {@code CompilerReport} in {@code -compiler}
 * describes where it is <em>produced</em>, and that stays true.
 *
 * <p>Data, not a printed string. §7.4's reason: CI asserts on it and the DSL tooling renders it.
 */
package com.codeheadsystems.rules.report;
