package com.codeheadsystems.rules.schema;

/**
 * Whether a schema requires a path to be there (spec §2.6.1, §7.4).
 *
 * <p>Three values rather than a boolean, because "no schema said" is a real and common answer and
 * is not the same as "the schema said optional". §2.6.1's {@code NE}-on-an-optional-path warning
 * depends on telling them apart: warning whenever a path is not known to be required would fire on
 * every {@code ne} in every rule set with no schema registered, which is how a warning channel
 * stops being read.
 */
public enum Presence {

  /** The schema lists this path as required. */
  REQUIRED,

  /** The schema describes this path and does not require it. */
  OPTIONAL,

  /**
   * Nothing is known: no schema is registered for the type, the path is not described, or the
   * schema describes it through a composition this engine does not attempt to read.
   */
  UNKNOWN
}
