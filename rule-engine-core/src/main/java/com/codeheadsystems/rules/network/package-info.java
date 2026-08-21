/**
 * The compiled matching network: entry nodes, shared alpha nodes and per-pattern memories.
 *
 * <p>This is the Phase 1 replacement for the naive matcher's per-fire scan. Two mechanisms do the
 * work, and they are independent:
 *
 * <p><strong>Node sharing</strong> makes the cost of an insert grow with the number of
 * <em>distinct constraints</em> rather than with the number of rules. Fifty rules that all test
 * {@code status == "PENDING"} compile to one alpha node, evaluated once per fact. Spec section 6.5
 * is careful about how far that claim goes: sharing is genuinely sublinear for the alpha network,
 * where duplicate single-fact constraints across rules are common, and much weaker for joins.
 *
 * <p><strong>Pattern memories</strong> make the cost of a fire cycle grow with the number of facts
 * that actually match a pattern rather than with the number of facts of its type. A pattern's
 * memory holds exactly the facts satisfying the conjunction of its alpha tests, maintained
 * incrementally on insert and retract.
 *
 * <p>Everything here lives in the shared, immutable rule set. Everything these nodes
 * <em>store</em> lives in {@link com.codeheadsystems.rules.network.SessionMemories}, addressed by
 * node id.
 */
package com.codeheadsystems.rules.network;
