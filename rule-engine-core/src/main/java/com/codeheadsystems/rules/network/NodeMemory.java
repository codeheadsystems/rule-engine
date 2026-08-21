package com.codeheadsystems.rules.network;

/**
 * Per-session mutable state belonging to one node, addressed by node id (spec §3.2.3).
 *
 * <p>This is how spec invariant 1 is realised. Indexes are maintained incrementally on insert,
 * retract and update -- that is per-session mutable state, and it cannot live on a node object that
 * thousands of virtual threads share. Node ids plus a session-side memory array are what make "a
 * shared node graph" and "incrementally maintained indexes" consistent claims rather than
 * contradictory ones.
 *
 * <p>Sealed over one implementation today. §3.2.3 also names alpha, join and terminal memories:
 * join memory arrives with the Phase 2 join node, and alpha and terminal memories are deliberately
 * absent because nothing reads them in this shape ({@link AlphaNode} explains why).
 */
public sealed interface NodeMemory permits PatternMemory {}
