package com.codeheadsystems.rules.rule;

/**
 * How many facts a pattern requires (spec §2.5).
 *
 * <p>{@link #EXISTS_AT_LEAST_ONE} and {@link #NOT_EXISTS} are implemented. {@link #FOR_ALL} and
 * {@link #ACCUMULATE} are reserved so that adding them later is a new enum constant plus a new node
 * type, rather than a reshape of {@link PatternDefinition}; §1 states what each costs and gives an
 * interim answer for each. The compiler rejects both by name, pointing at that answer.
 */
public enum Quantifier {

  /** At least one fact matches the pattern. What a pattern means when it says nothing. */
  EXISTS_AT_LEAST_ONE,

  /**
   * No fact matches the pattern (§1's amendment; the DSL spells it {@code notExists}).
   *
   * <p>A negated pattern <strong>binds nothing</strong>: its alias exists so that its own
   * constraints can be written, and nothing else in the rule may reference it. It may join against
   * the aliases the rule does bind, in either direction of declaration, and where its fact type is
   * one the rule already binds, §1's implicit inequality applies -- the question is about some
   * <em>other</em> fact of that type.
   *
   * <p>§1 priced this as needing per-tuple match counters and a {@code NotNode}, and neither was
   * built: because the pattern binds nothing, the predicate is a function of a <em>complete</em>
   * tuple, so it is answered in the shared agenda base where the three matchers cannot disagree,
   * and the 1-to-0 transitions fall out of §4.1's dirty tracking.
   *
   * <p><strong>Two boundaries.</strong> There is no truth maintenance -- a rule that fired because
   * something was absent is not undone when that thing arrives. And a negated type must not be one
   * a session evicts (§4.4): an evicted fact and an absent fact are indistinguishable here, so a
   * cap on the negated type turns a lost firing into a false conclusion.
   */
  NOT_EXISTS,

  /** Every fact of the type matches the pattern. Deferred (§1). */
  FOR_ALL,

  /** Aggregation over matching facts. Deferred (§1); aggregate at ingestion instead. */
  ACCUMULATE
}
