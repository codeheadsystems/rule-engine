/**
 * The text front end: JSON and YAML into {@code RuleDefinition}, and into facts (spec §6).
 *
 * <p>{@link com.codeheadsystems.rules.dsl.RuleFiles} is the entry point for rule files and the only
 * type most callers need. Everything else here is the machinery behind it.
 *
 * <p>{@link com.codeheadsystems.rules.dsl.FactFiles} is the second, smaller front door: a list of
 * typed facts, in the same two serializations, for seeding a session from a fixture or a captured
 * one. It lives here rather than in a module of its own because §6.1's answer is the whole of what
 * distinguishes the serializations -- one factory choice against an identical target type -- and
 * that factory choice is configured once, in
 * {@link com.codeheadsystems.rules.dsl.RuleFormat}. A separate module would have been a second
 * copy of it, a second published artifact, and a second place to defer the YAML classload. What it
 * is <em>not</em> is an ingestion path for a stream; see {@code FactFiles} for that boundary.
 *
 * <p><strong>This package parses and it validates syntax. It does not compile rules.</strong> The
 * boundary matters, because there are three gates and duplicating one in another is how they drift
 * apart:
 *
 * <ol>
 *   <li><strong>The rule-file JSON Schema</strong> ({@code rules.v1.json}) checks structure --
 *       required keys, value types, unknown keys, the closed set of action verbs. §6.5 puts it
 *       first and calls it "fail fast, before touching the network". It is also the artifact
 *       editors and CI linters validate against, which is what makes §6.2.3's {@code apiVersion}
 *       story real rather than aspirational.
 *   <li><strong>This package</strong> checks what a JSON Schema cannot say: that an operator key is
 *       one of §6.2.1's, that a {@code $}-prefixed key is {@code $ref} or the {@code $$ref} escape
 *       and nothing else, that a {@code between} has at least one bound, that a reference parses as
 *       {@code alias.field}.
 *   <li><strong>{@link com.codeheadsystems.rules.compiler.RuleCompiler}</strong> checks meaning:
 *       forward references, unknown aliases, duplicate ids, invalid regexes, unregistered function
 *       names. None of that is reimplemented here. What this package adds is
 *       {@linkplain com.codeheadsystems.rules.dsl.SourceLocation source locations} on the way back
 *       out, so a diagnostic written for a Java rule builder names a line in a file instead.
 * </ol>
 */
package com.codeheadsystems.rules.dsl;
