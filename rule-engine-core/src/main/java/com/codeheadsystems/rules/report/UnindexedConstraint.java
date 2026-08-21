package com.codeheadsystems.rules.report;

import java.util.Objects;

/**
 * A constraint the engine cannot serve from an index (spec §7.4, §3.3).
 *
 * <p>§10 sets "no unknown unindexed access" as a goal, and §7.4 observes that it "is a goal you can
 * only hold yourself to if you can enumerate the violations". This is the enumeration.
 *
 * <p><strong>Read the reason, not just the count, because the two kinds cost very different
 * things.</strong> The distinction is not decoration; an author who treats them alike will spend
 * effort on the cheap one.
 *
 * <ul>
 *   <li>{@link Reason#RESIDUAL_JOIN_CONDITION} is the expensive kind. A join whose operator cannot
 *       probe an index is re-evaluated against candidates every fire cycle, so its cost scales with
 *       the product of two pattern memories. §3.3 calls indexed joins "the single biggest lever for
 *       join-heavy rule sets"; this is a rule that gave the lever up.
 *   <li>{@link Reason#NE}, {@link Reason#NOT_IN} and {@link Reason#MATCHES} on a <em>single-fact</em>
 *       constraint are the cheap kind, and it is worth being precise rather than alarming about
 *       them. A pattern memory holds exactly the facts passing its alpha tests, so such a test runs
 *       <em>once per insert</em> and never again -- not once per fact per cycle. They are listed
 *       because §7.4 asks for every unindexed constraint and because an author choosing between
 *       {@code ne} and {@code hasField} deserves to know which one the index can use, not because
 *       each one is a problem to go and fix.
 * </ul>
 *
 * @param ruleId the rule the constraint belongs to
 * @param alias the pattern alias it constrains
 * @param field the dotted field path, in the form the author wrote
 * @param reason why no index can serve it
 */
public record UnindexedConstraint(String ruleId, String alias, String field, Reason reason) {

  /**
   * Canonical constructor.
   *
   * @param ruleId the owning rule
   * @param alias the pattern alias
   * @param field the field path
   * @param reason why it is unindexed
   */
  public UnindexedConstraint {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(reason, "reason");
  }

  /** Why a constraint cannot be indexed (spec §7.4). */
  public enum Reason {

    /** An anti-match: "everything except one bucket", which an index cannot narrow (§3.3). */
    NE,

    /** An anti-match over a set, for the same reason as {@link #NE}. */
    NOT_IN,

    /** A regular expression. Linear under RE2, but not decomposable into an index key (§2.6.3). */
    MATCHES,

    /**
     * A CEL {@code condition}, which §6.4 makes an explicit, visible cost.
     *
     * <p>Never produced in v1: the escape hatch arrives with the {@code -cel} module, and until
     * then a {@code condition} is a compile error rather than an unindexed constraint.
     */
    CEL_EXPRESSION,

    /**
     * No schema declares the field's type, so no index key type can be chosen (§6.3).
     *
     * <p>Never produced in v1: it needs the optional {@code SchemaRegistry} of §2.3, which arrives
     * with the {@code -schema} module.
     */
    NO_SCHEMA,

    /**
     * A join whose operator cannot probe an index, leaving it to the post-filter (§3.3).
     *
     * <p>This is the one that costs per fire cycle. See the class note.
     */
    RESIDUAL_JOIN_CONDITION
  }
}
