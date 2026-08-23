package com.codeheadsystems.rules.rule;

/**
 * How many facts a pattern requires (spec §2.5).
 *
 * <p>{@link #EXISTS_AT_LEAST_ONE}, {@link #NOT_EXISTS} and {@link #FOR_ALL} are implemented.
 * {@link #ACCUMULATE} is reserved so that adding it later is a new enum constant plus a new node
 * type, rather than a reshape of {@link PatternDefinition}; §1 states what it costs and gives an
 * interim answer. The compiler rejects it by name, pointing at that answer.
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

  /**
   * Every fact <em>in scope</em> matches the pattern (§2.5's amendment; the DSL spells it
   * {@code forAll}).
   *
   * <p><strong>The join tests choose the scope; the pattern's own constraints are the
   * requirement.</strong> This is a deliberate departure from the one-line reading this constant
   * carried while it was deferred -- "every fact of the type matches the pattern" -- and the
   * departure is the feature. Read literally, a universal carrying a join asserts that every
   * {@code LineItem} in working memory belongs to this order <em>and</em> is in stock, which is
   * false the moment a second order exists: an author writes what looks right and gets a rule that
   * can never fire. Under the split the same pattern says "every {@code LineItem} <em>of this
   * order</em> is in stock". With no joins the two readings coincide and this is the global
   * assertion the original wording described.
   *
   * <p><strong>Only a join can narrow the scope.</strong> Every literal-valued constraint compiles
   * to an alpha test and so lands in the requirement, which means "every <em>physical</em> line item
   * of this order is in stock" cannot be written: the type test would make a digital item a
   * counterexample rather than excluding it. §2.5's amendment records this as the quantifier's
   * remaining shape limit, and the interim answer is §1's -- narrow at ingestion, by fact type.
   *
   * <p>Like {@link #NOT_EXISTS} it <strong>binds nothing</strong>: its alias exists so that its own
   * constraints can be written, and nothing else in the rule may reference it. Where its fact type
   * is one the rule already binds, §1's implicit inequality applies to the <em>scope</em> -- the
   * fact the tuple binds is not one the assertion is about, so "every other order is shipped" is
   * what a same-type universal means.
   *
   * <p><strong>Three boundaries.</strong> There is no truth maintenance, inherited unchanged from
   * negation: a rule that fired because everything in scope complied is not undone when a
   * counterexample arrives. The quantified type must not be one a session evicts (§4.4), and this
   * is sharper than negation's -- eviction can only remove counterexamples, so a cap does not
   * weaken the requirement but strengthens it. And it is <strong>vacuously true over an empty
   * scope</strong>, which is classical and is the trap: pair it with a positive pattern of the same
   * type to mean "there are some, and all of them". Combine the last two and a cap that empties the
   * scope does not weaken the assertion but deletes it.
   */
  FOR_ALL,

  /** Aggregation over matching facts. Deferred (§1); aggregate at ingestion instead. */
  ACCUMULATE
}
