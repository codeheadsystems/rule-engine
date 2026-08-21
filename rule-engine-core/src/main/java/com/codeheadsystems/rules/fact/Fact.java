package com.codeheadsystems.rules.fact;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One unit of data in working memory: a handle, a type name and a JSON payload (spec §2.2).
 *
 * <p><strong>Why a class and not a record.</strong> A record's generated {@code equals}/
 * {@code hashCode} would cover the payload, making every comparison a deep {@code JsonNode} tree
 * walk -- catastrophic the moment a fact lands in a set or as a map key, and semantically wrong
 * besides, since two facts with identical content are still two facts. Identity is the handle, and
 * only the handle.
 *
 * <p><strong>A fact is an immutable snapshot at one recency.</strong> Working memory replaces the
 * object wholesale on update rather than mutating it, which is exactly why tuples bind handles and
 * dereference through working memory rather than holding {@code Fact} references (§3.2.2). There is
 * one place a payload lives, so nothing downstream of matching can serve a stale one.
 */
public final class Fact {

  private final FactHandle handle;
  private final String type;
  private final JsonNode payload;
  private final long recency;

  /**
   * Creates a fact. Working memory owns this; callers reach facts through
   * {@link WorkingMemory#get(FactHandle)}.
   *
   * @param handle the identity
   * @param type the fact type
   * @param payload the payload. Engine-owned from this point (§2.2): the caller must not retain a
   *     reference and must not mutate it
   * @param recency the ordering counter at which this snapshot was taken
   */
  public Fact(final FactHandle handle, final String type, final JsonNode payload,
      final long recency) {
    this.handle = Objects.requireNonNull(handle, "handle");
    this.type = Objects.requireNonNull(type, "type");
    this.payload = Objects.requireNonNull(payload, "payload");
    this.recency = recency;
  }

  /**
   * This fact's identity.
   *
   * @return the handle
   */
  public FactHandle handle() {
    return handle;
  }

  /**
   * This fact's type.
   *
   * @return the type name given at insert
   */
  public String type() {
    return type;
  }

  /**
   * The ordering counter, used for conflict resolution and staleness checks (§2.1).
   *
   * <p>It advances on insert and on any update that changes a path the network tests. A fact whose
   * untested fields churn never becomes "fresher" for conflict resolution, which is intended.
   *
   * @return the recency
   */
  public long recency() {
    return recency;
  }

  /**
   * The payload.
   *
   * <p><strong>The returned node is engine-owned and read-only.</strong> Jackson has no immutable
   * tree type and no cheap read-only wrapper, so this is a documented contract rather than an
   * enforced one. Mutating it breaks the assumption that a fact's content changes only through
   * {@code update()} -- an assumption accessor caching, index maintenance and the update diff all
   * depend on -- and the symptom is silent wrong matches, not a crash. Strict mode (§7.5) hands out
   * a deep copy here instead, so integration tests catch violators.
   *
   * @return the payload; never {@code null}
   */
  public JsonNode payload() {
    return payload;
  }

  /**
   * Identity is the handle, and only the handle -- not the payload.
   *
   * @param other the object to compare against
   * @return whether {@code other} is a fact with the same handle
   */
  @Override
  public boolean equals(final Object other) {
    return other instanceof Fact fact && fact.handle.equals(handle);
  }

  /**
   * Hashes the handle alone, consistent with {@link #equals(Object)}.
   *
   * @return the handle's hash
   */
  @Override
  public int hashCode() {
    return handle.hashCode();
  }

  /**
   * A short, diagnostic description. Deliberately does not render the payload, which may be large.
   *
   * @return the type, handle id and recency
   */
  @Override
  public String toString() {
    return type + "#" + handle.id() + "@r" + recency;
  }
}
