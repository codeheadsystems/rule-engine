package com.codeheadsystems.rules.match;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * The identity of a match: one rule plus the facts it matched (spec §4.2).
 *
 * <p>This is the refraction key, the deactivation key and the equality key -- one notion, one
 * place. Keying on {@code (ruleId, handles)} rather than on a generated id is not a stylistic
 * choice: retract propagation knows a handle, not a UUID, so a {@code deactivate(UUID)} signature
 * would have no way to find its argument. Keying on the rule and its bindings fixes that and
 * simultaneously supplies exactly the key refraction needs.
 *
 * <p>Note that this is a class rather than a record for the same reason {@link Tuple} is: the
 * {@code handles} component is an array, and a record's generated {@code equals}/{@code hashCode}
 * on an array component are identity-based, which would silently defeat refraction entirely.
 */
public final class ActivationKey {

  /**
   * A total order on {@code (ruleId, ascending handle ids)}.
   *
   * <p>This is the final tie-break in conflict resolution (§4.2), and it is derived from
   * <em>state</em> rather than from a counter on purpose. A monotonic sequence assigned at
   * activation-creation time is the obvious alternative and it is worse on its own merits: it is
   * more plumbing, and it makes firing order depend on the order activations happened to be
   * constructed -- which under lazy recomputation is an implementation detail of the rebuild loop,
   * not a property of the data. Ordering on the match itself costs nothing and is stable across any
   * recomputation order.
   */
  public static final Comparator<ActivationKey> LEXICOGRAPHIC =
      Comparator.comparing(ActivationKey::ruleId)
          .thenComparing(key -> key.handles, Arrays::compare);

  private final String ruleId;
  private final long[] handles;
  private final int hash;

  /**
   * Creates a key.
   *
   * @param ruleId the rule that matched
   * @param handles the bound handle ids, in pattern order. Copied defensively
   */
  public ActivationKey(final String ruleId, final long[] handles) {
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
    this.handles = Objects.requireNonNull(handles, "handles").clone();
    this.hash = 31 * ruleId.hashCode() + Arrays.hashCode(this.handles);
  }

  /**
   * The rule that matched.
   *
   * @return the rule id
   */
  public String ruleId() {
    return ruleId;
  }

  /**
   * The bound handle ids.
   *
   * @return a copy, in pattern order
   */
  public long[] handles() {
    return handles.clone();
  }

  /**
   * Value equality over the rule id and the bound handles.
   *
   * @param other the object to compare against
   * @return whether {@code other} is an equal key
   */
  @Override
  public boolean equals(final Object other) {
    return other instanceof ActivationKey key
        && ruleId.equals(key.ruleId)
        && Arrays.equals(handles, key.handles);
  }

  /**
   * Hashes the rule id and the bound handles, consistent with {@link #equals(Object)}.
   *
   * @return the hash
   */
  @Override
  public int hashCode() {
    return hash;
  }

  /**
   * A diagnostic rendering.
   *
   * @return e.g. {@code high-value-order-review[3, 7]}
   */
  @Override
  public String toString() {
    return ruleId + Arrays.toString(handles);
  }
}
