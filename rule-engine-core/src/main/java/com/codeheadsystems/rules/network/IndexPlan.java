package com.codeheadsystems.rules.network;

import com.fasterxml.jackson.core.JsonPointer;
import java.util.List;
import java.util.Objects;

/**
 * Which of a pattern's paths get indexed, and how (spec §3.3).
 *
 * <p>"Compile time produces an index <em>plan</em>; the session holds the index <em>contents</em>."
 * This is the plan half: it says "this pattern hash-indexes {@code /customer/id}", and the contents
 * live in that pattern's memory inside each session.
 *
 * <p><strong>The plan is driven by what joins probe, not by what alpha tests filter.</strong> §3.3
 * lists hash indexes for {@code EQ}/{@code IN}, and it is worth being precise about why an
 * <em>alpha</em> equality does not produce one here: a pattern's memory already contains exactly
 * the facts passing its alpha tests, so indexing {@code status == "PENDING"} on a pattern that
 * already filtered by it would build a structure with one bucket and no reader. What genuinely
 * needs an index is the other side of a join -- given a bound order, find the customers whose
 * {@code /id} matches -- and that path is named by a join constraint, not by an alpha one.
 *
 * @param hashed paths to hash-index, for equality joins
 * @param sorted paths to range-index, for ordering joins
 */
public record IndexPlan(List<JsonPointer> hashed, List<JsonPointer> sorted) {

  /**
   * Canonical constructor. Defensively copies both lists.
   *
   * @param hashed the hash-indexed paths
   * @param sorted the range-indexed paths
   */
  public IndexPlan {
    hashed = List.copyOf(hashed);
    sorted = List.copyOf(sorted);
  }

  /**
   * A plan that indexes nothing.
   *
   * @return the empty plan
   */
  public static IndexPlan none() {
    return new IndexPlan(List.of(), List.of());
  }

  /**
   * Whether this plan builds any index at all.
   *
   * @return true when the pattern's memory needs no index structures
   */
  public boolean isEmpty() {
    return hashed.isEmpty() && sorted.isEmpty();
  }

  /**
   * Whether a path is hash-indexed under this plan.
   *
   * @param path the path
   * @return true if an equality probe on it can use an index
   */
  public boolean isHashed(final JsonPointer path) {
    return hashed.contains(Objects.requireNonNull(path, "path"));
  }

  /**
   * Whether a path is range-indexed under this plan.
   *
   * @param path the path
   * @return true if an ordering probe on it can use an index
   */
  public boolean isSorted(final JsonPointer path) {
    return sorted.contains(Objects.requireNonNull(path, "path"));
  }
}
