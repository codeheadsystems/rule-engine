package com.codeheadsystems.rules.fact;

/**
 * Pure identity for one fact: session-scoped, opaque, cheap (spec §2.1).
 *
 * <p>It is stable for the life of the fact. {@code update()} never invalidates a handle, which is
 * the whole reason §11.2 rejected copy-on-write: a handle held across an update would go stale,
 * and that is a footgun in every API that stores one.
 *
 * <p><strong>Why a {@code long} rather than a UUID.</strong> This is the hottest key in the engine
 * -- every index bucket, join memory, tuple and refraction entry is keyed on it. A 64-bit key
 * hashes in one operation and keeps index buckets cache-dense; a 128-bit UUID doubles the key
 * width and costs a two-word {@code hashCode}/{@code equals} on every probe, all to buy
 * cross-session uniqueness that nothing in v1 consumes. Where global identity is needed, the pair
 * {@code (sessionId, handle.id())} supplies it at 16 bytes <em>per session</em> rather than per
 * fact.
 *
 * <p><strong>Two things are deliberately absent.</strong> Recency is not a field here: it is
 * mutable, so a handle carrying it would either go stale after an update or could never be bumped.
 * And there is no global UUID: a handle is meaningful only relative to its session, and the engine
 * never compares handles across sessions.
 *
 * <p><strong>Internally, key on the raw {@code long}.</strong> This record is a heap object, so
 * storing it in every index bucket costs an allocation and a pointer chase per entry and
 * forecloses primitive-keyed collections. Materialise a {@code FactHandle} at the public API
 * boundary only.
 *
 * @param id the session-scoped identifier, dense and allocated in insertion order
 */
public record FactHandle(long id) {}
