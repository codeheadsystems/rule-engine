package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.rule.Quantifier;
import java.util.Optional;

/**
 * A pattern's {@code quantifier} key, written as the DSL spells it (spec §2.5).
 *
 * <p>The spelling follows §6.2.1's rule for operators rather than inventing a second one: a DSL key
 * is the camelCase of the constant it names, so {@code notExists} is {@link Quantifier#NOT_EXISTS}
 * exactly as {@code notIn} is {@code NOT_IN}. Absent means {@code exists}, which is what every
 * pattern written before this key existed meant and must keep meaning.
 *
 * <p><strong>Only the implemented quantifiers are spellable.</strong> §2.5's enum still reserves
 * {@code ACCUMULATE} and §1 defers it, and the temptation is to accept it here so the compiler's
 * "not implemented, see §1" message reaches the author. It is refused for a reason that outlives
 * the message: {@code rules.v1} is a published schema, and admitting a spelling for a feature that
 * does not exist promises a shape the version implementing it may not want. A rule file naming it
 * is rejected by the schema, and this class says the same thing in words that name §1's interim
 * answer.
 */
final class Quantifiers {

  /** What an absent {@code quantifier} key means, and what it must go on meaning. */
  private static final String EXISTS = "exists";

  /** The negation of §1, landed as the first slice of §9's Phase 6. */
  private static final String NOT_EXISTS = "notExists";

  /** The universal of §2.5's amendment, landed as Phase 6's second slice. */
  private static final String FOR_ALL = "forAll";

  private Quantifiers() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Reads a pattern's quantifier.
   *
   * @param written the value of the {@code quantifier} key, or null when it is absent
   * @param pointer the key's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the quantifier, or empty when the spelling is not one this engine implements
   */
  static Optional<Quantifier> of(final String written, final String pointer,
      final Diagnostics diagnostics) {
    if (written == null || EXISTS.equals(written)) {
      return Optional.of(Quantifier.EXISTS_AT_LEAST_ONE);
    }
    if (NOT_EXISTS.equals(written)) {
      return Optional.of(Quantifier.NOT_EXISTS);
    }
    if (FOR_ALL.equals(written)) {
      return Optional.of(Quantifier.FOR_ALL);
    }
    diagnostics.error(DslError.UNKNOWN_QUANTIFIER, pointer,
        "'" + written + "' is not a quantifier; write '" + EXISTS + "' (the default), '"
            + NOT_EXISTS + "' or '" + FOR_ALL + "'. §1 defers 'accumulate', and gives the interim"
            + " answer for it: compute it at ingestion and insert the answer as a fact");
    return Optional.empty();
  }
}
