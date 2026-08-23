package com.codeheadsystems.rules.dsl;

import java.util.Objects;

/**
 * Every way a rule file can be wrong before the compiler ever sees it (spec §6.5's first two gates).
 *
 * <p>An enum rather than free strings, for two reasons that both showed up in the plan for this
 * module. Tooling needs to match on a code without matching on prose, and
 * {@code docs/dsl-reference.md} carries a catalogue that has to stay in step with the code -- which
 * is checkable when the set is enumerable and merely hoped for when it is not.
 *
 * <p>Codes are kebab-case words rather than numbers. A number has to be looked up; a reader seeing
 * {@code unknown-operator} beside the line that caused it needs no table.
 *
 * <p>Some constants are {@linkplain #shieldedBySchema shielded}: the rule-file schema states the
 * same rule and states it first, so an author never sees them. They are raised anyway, by the
 * component that would otherwise have to assume the gate ahead of it did its job. See
 * {@link #shieldedBySchema()} for why that is worth the dead code.
 */
public enum DslError {

  /** The file is not well-formed JSON or YAML at all. */
  MALFORMED_DOCUMENT("malformed-document"),

  /** {@code apiVersion} names a version this DSL does not implement, or is missing (§6.2.3). */
  UNKNOWN_API_VERSION("unknown-api-version"),

  /** The document violates the rule-file schema: a missing key, a wrong type, an unknown key. */
  SCHEMA_VIOLATION("schema-violation"),

  /**
   * An operator map uses a key that is not one of §6.2.1's.
   *
   * <p>{@linkplain #shieldedBySchema() Shielded}: the schema closes the operator set.
   */
  UNKNOWN_OPERATOR("unknown-operator", true),

  /**
   * A {@code $}-prefixed key that is neither {@code $ref} nor the {@code $$ref} escape.
   *
   * <p>§6.2.3 requires rejection rather than pass-through: {@code $}-prefixed keys are
   * conventionally reserved, and silently treating an unrecognised one as a literal object field is
   * how a typo becomes a rule that never matches.
   */
  UNKNOWN_DOLLAR_KEY("unknown-dollar-key"),

  /** A {@code $ref} whose operand does not parse as {@code alias.field}. */
  MALFORMED_REFERENCE("malformed-reference"),

  /** A {@code between} with neither a {@code from} nor a {@code to} (§6.2.1). */
  EMPTY_RANGE("empty-range"),

  /**
   * An operator's operand is the wrong shape, e.g. {@code in} given something that is not a list.
   *
   * <p>{@linkplain #shieldedBySchema() Shielded}: the schema types every operand that has one type.
   */
  MALFORMED_OPERAND("malformed-operand", true),

  /**
   * A pattern's {@code quantifier} is not one this engine implements (§2.5, §1).
   *
   * <p>{@linkplain #shieldedBySchema() Shielded}: the schema enumerates the two spellings. Raised
   * anyway, and with a message the schema cannot give -- §1's interim answer for the quantifiers it
   * defers.
   */
  UNKNOWN_QUANTIFIER("unknown-quantifier", true),

  /**
   * A pattern's {@code accumulate} block does not say exactly one thing (§2.5's second amendment).
   *
   * <p>Not shielded, and cannot be: the schema types every key and can require the block beside
   * {@code quantifier: accumulate}, but "exactly one of these five" and "this operator can test an
   * aggregate" are statements about meaning rather than about shape.
   */
  MALFORMED_ACCUMULATE("malformed-accumulate", false),

  /**
   * A {@code then} block names a verb that is not one of §6.2.2's five.
   *
   * <p>{@linkplain #shieldedBySchema() Shielded}: the schema enumerates the five.
   */
  UNKNOWN_ACTION("unknown-action", true),

  /**
   * An action names a field path that will not compile, such as {@code a..b}.
   *
   * <p>Not "a missing or extra key for this verb": the rule-file schema states each verb's
   * {@code required} and {@code additionalProperties} and reaches those first, so they arrive as
   * {@link #SCHEMA_VIOLATION}.
   */
  MALFORMED_ACTION("malformed-action"),

  /**
   * A diagnostic from {@link com.codeheadsystems.rules.compiler.RuleCompiler}, re-reported against
   * the line that caused it.
   *
   * <p>Deliberately one code rather than a mirror of the compiler's own set. The DSL does not
   * reimplement semantic validation and must not pretend to classify it; what it adds is the
   * location.
   */
  SEMANTIC("semantic");

  private final String code;
  private final boolean shieldedBySchema;

  DslError(final String code) {
    this(code, false);
  }

  DslError(final String code, final boolean shieldedBySchema) {
    this.code = code;
    this.shieldedBySchema = shieldedBySchema;
  }

  /**
   * Whether the rule-file schema rejects this case before the DSL compiler is reached.
   *
   * <p>A shielded code is unreachable through {@link RuleFiles} and is raised only when the
   * component that detects it is called directly. That is not dead weight, and deleting the check
   * would be the mistake: the schema and the compiler state the same rule in two languages, and
   * "the gate ahead of me guarantees this cannot happen" is precisely the assumption that turns a
   * loosened schema into a silently dropped constraint -- a rule that matches more than its author
   * wrote, which is the failure mode §2.6.1 exists to eliminate.
   *
   * <p>It also tells {@code docs/dsl-reference.md} which half of the catalogue an author will
   * actually meet.
   *
   * @return true when the schema gate catches this first
   */
  public boolean shieldedBySchema() {
    return shieldedBySchema;
  }

  /**
   * The stable, machine-matchable code.
   *
   * @return the kebab-case code, e.g. {@code unknown-operator}
   */
  public String code() {
    return code;
  }

  /**
   * The error carrying a code.
   *
   * @param code the kebab-case code
   * @return the matching constant
   * @throws IllegalArgumentException if no constant carries that code
   */
  public static DslError forCode(final String code) {
    Objects.requireNonNull(code, "code");
    for (final DslError candidate : values()) {
      if (candidate.code.equals(code)) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("no DSL error carries the code '" + code + "'");
  }
}
