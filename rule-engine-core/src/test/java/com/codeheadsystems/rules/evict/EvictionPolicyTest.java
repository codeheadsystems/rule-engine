package com.codeheadsystems.rules.evict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.Origin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

/** The built-in eviction policies of §4.4, over a hand-built view. */
class EvictionPolicyTest {

  /**
   * A view of facts in the order given, which is taken to be recency order.
   *
   * <p>Hand-built rather than driven through a session, because these tests are about the selection
   * rule and nothing else. That a real session presents its facts in recency order is
   * {@code SessionEvictor}'s claim, and it is asserted end-to-end in {@code EvictionTest}.
   */
  private record FixedView(List<Fact> facts) implements EvictionView {

    @Override
    public int size() {
      return facts.size();
    }

    @Override
    public int sizeOfType(final String type) {
      return (int) facts.stream().filter(fact -> fact.type().equals(type)).count();
    }

    @Override
    public Set<String> types() {
      return facts.stream().map(Fact::type)
          .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    @Override
    public Stream<Fact> oldestFirst() {
      return facts.stream();
    }

    @Override
    public Stream<Fact> oldestOfType(final String type) {
      return facts.stream().filter(fact -> fact.type().equals(type));
    }
  }

  private static Fact fact(final long id, final String type) {
    return new Fact(new FactHandle(id), type, JsonNodeFactory.instance.objectNode(), id,
        Origin.ASSERTED);
  }

  private static FixedView view(final Fact... facts) {
    return new FixedView(List.of(facts));
  }

  private static List<Long> ids(final List<FactHandle> handles) {
    return handles.stream().map(FactHandle::id).toList();
  }

  @Nested
  @DisplayName("a total cap")
  class Lru {

    @Test
    @DisplayName("selects nothing until the cap is exceeded")
    void underCapSelectsNothing() {
      final EvictionPolicy policy = EvictionPolicy.leastRecentlyUsed(3);

      assertThat(policy.selectVictims(view(fact(1, "Order"), fact(2, "Order")))).isEmpty();
      assertThat(policy.selectVictims(
          view(fact(1, "Order"), fact(2, "Order"), fact(3, "Order")))).isEmpty();
    }

    @Test
    @DisplayName("takes exactly the excess, oldest first")
    void takesTheExcessOldestFirst() {
      final List<FactHandle> victims = EvictionPolicy.leastRecentlyUsed(2).selectVictims(
          view(fact(1, "Order"), fact(2, "Order"), fact(3, "Order"), fact(4, "Order")));

      assertThat(ids(victims))
          .describedAs("two over a cap of two, and the two oldest are the ones to go")
          .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("does not care what type a fact is, which is the reason perType exists")
    void isBlindToType() {
      // The trap §4.4's amendment names: reference data is loaded first, so it holds the lowest
      // recency, so a global bound evicts exactly what a streaming session meant to keep.
      final List<FactHandle> victims = EvictionPolicy.leastRecentlyUsed(2).selectVictims(
          view(fact(1, "Customer"), fact(2, "Customer"), fact(3, "Order"), fact(4, "Order")));

      assertThat(ids(victims)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("rejects a cap that could never hold anything")
    void rejectsNonPositiveCap() {
      assertThatThrownBy(() -> EvictionPolicy.leastRecentlyUsed(0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must be positive");
    }
  }

  @Nested
  @DisplayName("a cap per fact type")
  class PerType {

    @Test
    @DisplayName("bounds the named type and leaves the others alone")
    void boundsOnlyNamedTypes() {
      final List<FactHandle> victims = EvictionPolicy.perType(Map.of("Order", 2)).selectVictims(
          view(fact(1, "Customer"), fact(2, "Customer"), fact(3, "Customer"),
              fact(4, "Order"), fact(5, "Order"), fact(6, "Order")));

      assertThat(ids(victims))
          .describedAs("the three customers are unbounded; one order is over the cap of two")
          .containsExactly(4L);
    }

    @Test
    @DisplayName("counts within the type, not across the session")
    void countsWithinTheType() {
      // A total of six facts with a cap of two on Order selects one, not four: the customers are
      // not competing for the order type's budget.
      final List<FactHandle> victims = EvictionPolicy.perType(Map.of("Order", 2)).selectVictims(
          view(fact(1, "Order"), fact(2, "Customer"), fact(3, "Customer"),
              fact(4, "Customer"), fact(5, "Customer"), fact(6, "Order"), fact(7, "Order")));

      assertThat(ids(victims)).containsExactly(1L);
    }

    @Test
    @DisplayName("caps several types at once, in type-name order")
    void capsSeveralTypes() {
      // Built in an order that is not name order, to pin that the answer does not depend on it.
      final Map<String, Integer> caps = new LinkedHashMap<>();
      caps.put("Shipment", 1);
      caps.put("Order", 1);

      final List<FactHandle> victims = EvictionPolicy.perType(caps).selectVictims(
          view(fact(1, "Order"), fact(2, "Shipment"), fact(3, "Order"), fact(4, "Shipment")));

      assertThat(ids(victims))
          .describedAs("Order before Shipment, whatever order the caps were declared in")
          .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("the victim list does not depend on the map's iteration order")
    void isIndependentOfMapOrder() {
      /*
       * The §7.3 hazard this policy's constructor sorts to avoid. A caller's HashMap iterates in an
       * order that is unspecified and, for the immutable maps Map.of produces, salted per JVM --
       * and that order would otherwise reach the victim list, which is retracted in order and
       * therefore visible to listeners.
       */
      final Map<String, Integer> forward = new LinkedHashMap<>();
      forward.put("A", 1);
      forward.put("B", 1);
      final Map<String, Integer> backward = new LinkedHashMap<>();
      backward.put("B", 1);
      backward.put("A", 1);

      final List<Fact> facts =
          List.of(fact(1, "A"), fact(2, "B"), fact(3, "A"), fact(4, "B"));

      assertThat(ids(EvictionPolicy.perType(forward).selectVictims(new FixedView(facts))))
          .isEqualTo(ids(EvictionPolicy.perType(backward).selectVictims(new FixedView(facts))))
          .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("selects nothing when every capped type is under its bound")
    void underEveryCapSelectsNothing() {
      assertThat(EvictionPolicy.perType(Map.of("Order", 5, "Customer", 5))
          .selectVictims(view(fact(1, "Order"), fact(2, "Customer")))).isEmpty();
    }

    @Test
    @DisplayName("a cap on a type no fact has is not an error")
    void unknownTypeIsHarmless() {
      assertThat(EvictionPolicy.perType(Map.of("Shipment", 1))
          .selectVictims(view(fact(1, "Order"), fact(2, "Order")))).isEmpty();
    }

    @Test
    @DisplayName("rejects a cap that could never hold anything")
    void rejectsNonPositiveCap() {
      final Map<String, Integer> caps = new LinkedHashMap<>();
      caps.put("Order", 0);

      assertThatThrownBy(() -> EvictionPolicy.perType(caps))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Order");
    }
  }

  @Nested
  @DisplayName("selection is lazy in what it dereferences")
  class Laziness {

    @Test
    @DisplayName("a cap exceeded by one looks at one fact, not the whole memory")
    void dereferencesOnlyWhatItTakes() {
      /*
       * Not a micro-optimisation: this is what keeps a streaming session at its cap from paying a
       * scan of working memory on every insert. The view counts what the policy pulls through it.
       */
      final List<Fact> facts = new ArrayList<>();
      for (int id = 1; id <= 1_000; id++) {
        facts.add(fact(id, "Order"));
      }
      final int[] dereferenced = {0};
      final EvictionView counting = new EvictionView() {
        @Override
        public int size() {
          return facts.size();
        }

        @Override
        public int sizeOfType(final String type) {
          return facts.size();
        }

        @Override
        public Set<String> types() {
          return Set.of("Order");
        }

        @Override
        public Stream<Fact> oldestFirst() {
          return facts.stream().peek(fact -> dereferenced[0]++);
        }

        @Override
        public Stream<Fact> oldestOfType(final String type) {
          return oldestFirst();
        }
      };

      assertThat(ids(EvictionPolicy.leastRecentlyUsed(999).selectVictims(counting)))
          .containsExactly(1L);
      assertThat(dereferenced[0])
          .describedAs("one over the cap means one fact examined")
          .isEqualTo(1);
    }
  }
}
