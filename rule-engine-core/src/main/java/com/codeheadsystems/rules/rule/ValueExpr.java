package com.codeheadsystems.rules.rule;

/**
 * A value in a {@code then} block: either a constant or a reference to a bound fact's field
 * (spec §2.5).
 *
 * <p>Note that {@code $ref} resolves at two different times depending on where it appears: a
 * {@code where} reference resolves at <em>compile</em> time against the join graph and becomes a
 * {@link JoinConstraint}; a {@code then} reference resolves at <em>fire</em> time against the
 * tuple. This type is the fire-time half. §6.2.3 says not to unify the two implementations.
 */
public sealed interface ValueExpr permits Literal, FieldRef {}
