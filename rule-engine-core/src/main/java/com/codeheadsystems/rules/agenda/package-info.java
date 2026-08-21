/**
 * The agenda: which activations are eligible, and which one fires next.
 *
 * <p>Spec §4.1 chose the TREAT shape for v1, and the consequence is easy to miss: at insert time
 * there is nothing to push onto the agenda, and at retract time nothing to pull. An
 * incrementally-maintained agenda is the <em>Rete</em> shape and presumes materialised join
 * results, which v1 does not have. So the conflict set is computed lazily, on demand, by
 * recomputing matches for dirty rules -- and a retracted fact's matches disappear because the next
 * recomputation simply does not produce them.
 *
 * <p>{@link com.codeheadsystems.rules.agenda.Agenda} stays an interface so the Rete shape can
 * arrive in Phase 3 as a second implementation. The two would share activations, conflict
 * resolution, refraction, RHS execution and the firing loop, and differ only in <em>when</em> the
 * conflict set is computed.
 */
package com.codeheadsystems.rules.agenda;
