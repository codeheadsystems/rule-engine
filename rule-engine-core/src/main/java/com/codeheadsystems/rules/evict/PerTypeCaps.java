package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cap per fact type, oldest by recency first within each (spec §4.4).
 *
 * @see EvictionPolicy#perType(Map)
 */
final class PerTypeCaps implements EvictionPolicy {

  private final List<Cap> caps;

  /**
   * Creates the policy.
   *
   * @param capsByType the most facts to hold per type
   */
  PerTypeCaps(final Map<String, Integer> capsByType) {
    Objects.requireNonNull(capsByType, "capsByType");
    final List<Cap> sorted = new ArrayList<>(capsByType.size());
    capsByType.forEach((type, limit) -> {
      Objects.requireNonNull(type, "fact type");
      Objects.requireNonNull(limit, "cap for " + type);
      if (limit <= 0) {
        throw new IllegalArgumentException("cap for " + type + " must be positive, was " + limit);
      }
      sorted.add(new Cap(type, limit));
    });
    /*
     * Sorted by type name, and not merely tidy. The caller's map may be a HashMap, whose iteration
     * order is unspecified and, for the immutable maps Map.of produces, salted per JVM. That order
     * would reach the victim list, the victim list is retracted in order, and retract order is
     * visible to listeners -- so an unordered walk here is a §7.3 violation that appears as a
     * different trace on a different host and nowhere else.
     */
    sorted.sort(Comparator.comparing(Cap::type));
    this.caps = List.copyOf(sorted);
  }

  @Override
  public List<FactHandle> selectVictims(final EvictionView view) {
    List<FactHandle> victims = null;
    for (final Cap cap : caps) {
      final int excess = view.sizeOfType(cap.type()) - cap.limit();
      if (excess <= 0) {
        // The common case by a wide margin: one comparison per capped type, no walk, no allocation.
        continue;
      }
      if (victims == null) {
        victims = new ArrayList<>(excess);
      }
      view.oldestOfType(cap.type()).limit(excess).map(Fact::handle).forEach(victims::add);
    }
    return victims == null ? List.of() : List.copyOf(victims);
  }

  @Override
  public String toString() {
    return "perType" + caps;
  }

  /**
   * One type's bound.
   *
   * @param type the fact type
   * @param limit the most facts of that type to hold
   */
  private record Cap(String type, int limit) {

    @Override
    public String toString() {
      return type + "=" + limit;
    }
  }
}
