package com.codeheadsystems.rules.rule;

/**
 * The RHS vocabulary (spec §2.5, §11.3).
 *
 * <p>Sealed on purpose: §11.3 chose a fixed closed set of five verbs, and adding a verb is a
 * deliberate, reviewable change to the safety properties of the DSL -- not an extension point.
 * The payoff is that a non-engineer can read a rule file and know exactly what categories of side
 * effect are possible, and that §7.4's compiler report can enumerate every external call surface.
 *
 * <p>{@link CallFunction} is the one escape, and §4.6 draws the boundary sharply: it is
 * <strong>not</strong> transactional. Working-memory effects roll back on a staging failure; a
 * sent notification does not.
 */
public sealed interface ActionDefinition
    permits SetField, InsertFact, RetractFact, Emit, CallFunction {}
