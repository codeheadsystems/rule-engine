/**
 * Session fact-eviction: the mechanism that bounds a long-lived session's memory (spec §4.4).
 *
 * <p>§4.7's {@code maxFacts} bounds working memory and nothing bounds anything else directly. A
 * session accumulates node memories and their indexes, the refraction memory's record of every
 * match ever fired, and -- once §11.1's streaming shape is selected -- a beta memory holding every
 * materialised match. All four are keyed on handles, which is why §4.4 answers all four with one
 * mechanism: <strong>evicting a fact runs the full retract path</strong>, and the cascade is free.
 * An eviction that reached into the memories by hand would be a fifth place they are removed from,
 * and the one nobody updates when a sixth memory is added.
 *
 * <p>So there is very little machinery here. A {@link com.codeheadsystems.rules.evict.EvictionPolicy}
 * answers one question -- which handles should go -- over a read-only
 * {@link com.codeheadsystems.rules.evict.EvictionView} of working memory, and the session retracts
 * them. The policy never removes anything itself, and the view deliberately is not
 * {@code WorkingMemory}: handing a selection function the ability to insert and retract is the kind
 * of thing nobody does on purpose.
 *
 * <p><strong>Eviction order reaches the firing sequence, so every ordering decision in here is a
 * determinism decision</strong> (§7.3). A policy must be a pure function of the view: same facts in
 * the same order, same victims, on every host and run. Strict mode calls the policy twice and
 * compares, which is what catches one that read a clock or iterated a {@code HashMap}.
 *
 * <p><strong>Why no time-to-live policy ships here</strong>, though §4.4 names TTL first. Wall-clock
 * time is not an input the determinism contract admits: two runs over identical input would evict
 * different facts, and §7.3 is a contract rather than a preference. A caller who wants one writes
 * it against this interface with their own clock, and takes the trade knowingly. The policies
 * shipped here key on {@code recency}, which is derived from the input itself.
 */
package com.codeheadsystems.rules.evict;
