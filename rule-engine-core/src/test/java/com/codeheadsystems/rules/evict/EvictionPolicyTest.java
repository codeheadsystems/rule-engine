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
import tools.jackson.databind.node.ObjectNode;

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

  /**
   * A fact carrying a time field, for the windowing policy.
   *
   * @param id the handle id, which is also its recency
   * @param type the fact type
   * @param at the value of the {@code at} field, or null to leave the field absent
   * @return the fact
   */
  private static Fact timed(final long id, final String type, final Number at) {
    final ObjectNode payload = JsonNodeFactory.instance.objectNode();
    switch (at) {
      case null -> { }
      case Double value -> payload.put("at", value);
      default -> payload.put("at", at.longValue());
    }
    return new Fact(new FactHandle(id), type, payload, id, Origin.ASSERTED);
  }

  /**
   * A fact whose time sits under {@code meta}, because a payload is not always flat.
   *
   * @param id the handle id
   * @param at the value of the {@code meta.at} field
   * @return the fact
   */
  private static Fact nested(final long id, final long at) {
    final ObjectNode payload = JsonNodeFactory.instance.objectNode();
    payload.putObject("meta").put("at", at);
    return new Fact(new FactHandle(id), "Event", payload, id, Origin.ASSERTED);
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

  @Nested
  @DisplayName("a window measured in the facts' own time")
  class Window {

    private final EvictionPolicy policy = EvictionPolicy.window("LoginFailure", "at", 600);

    @Test
    @DisplayName("evicts what is older than the newest time held, minus the span")
    void evictsWhatIsOutsideTheWindow() {
      assertThat(ids(policy.selectVictims(view(
          timed(1, "LoginFailure", 1_000),
          timed(2, "LoginFailure", 1_500),
          timed(3, "LoginFailure", 1_900)))))
          .describedAs("the newest is 1900, so the window opens at 1300")
          .containsExactly(1L);
    }

    @Test
    @DisplayName("the far edge is inclusive, as a temporal join's is")
    void theEdgeIsInclusive() {
      // `before` holds when other - within <= mine < other, so a fact sitting exactly on the edge
      // is inside a rule's window -- and retention that dropped it would lose the author a match.
      assertThat(policy.selectVictims(view(
          timed(1, "LoginFailure", 1_000),
          timed(2, "LoginFailure", 1_600))))
          .isEmpty();
    }

    @Test
    @DisplayName("time comes only from the type it names")
    void otherTypesNeitherAgeNorAdvanceTheWatermark() {
      assertThat(policy.selectVictims(view(
          timed(1, "LoginFailure", 1_000),
          timed(2, "Account", 9_999_999),
          timed(3, "LoginFailure", 1_100))))
          .describedAs("the Account's clock is not the LoginFailure's, and it is never a victim")
          .isEmpty();
    }

    @Test
    @DisplayName("the newest fact is never a victim, which is why the span must be positive")
    void theNewestSurvives() {
      assertThat(policy.selectVictims(view(timed(1, "LoginFailure", 1_000)))).isEmpty();
      assertThatThrownBy(() -> EvictionPolicy.window("LoginFailure", "at", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("a fact with no usable time is never evicted, and never moves the watermark")
    void unusableTimesDecline() {
      // Declining is the safe direction: eviction is the destructive act, and a policy that cannot
      // prove a fact is old must not guess. The cost is the leak this documents rather than plugs.
      assertThat(policy.selectVictims(view(
          timed(1, "LoginFailure", null),
          timed(2, "LoginFailure", 1_000),
          timed(3, "LoginFailure", 1_200))))
          .describedAs("nothing is outside the window, and the untimed fact is not swept up")
          .isEmpty();
      assertThat(ids(policy.selectVictims(view(
          timed(1, "LoginFailure", 1),
          timed(2, "LoginFailure", null),
          timed(3, "LoginFailure", 700)))))
          .describedAs("the untimed fact did not advance the watermark; 700 did")
          .containsExactly(1L);
    }

    @Test
    @DisplayName("a non-finite time is not a time")
    void nonFiniteDeclines() {
      assertThat(policy.selectVictims(view(
          timed(1, "LoginFailure", Double.NaN),
          timed(2, "LoginFailure", Double.POSITIVE_INFINITY))))
          .describedAs("Canonical will not order either, so neither is a watermark or a victim")
          .isEmpty();
    }

    @Test
    @DisplayName("victims come back oldest-recency first, whatever order the times arrived in")
    void victimOrderIsRecencyOrder() {
      assertThat(ids(policy.selectVictims(view(
          timed(1, "LoginFailure", 500),
          timed(2, "LoginFailure", 9_000),
          timed(3, "LoginFailure", 100),
          timed(4, "LoginFailure", 300)))))
          .describedAs("retract order is visible to listeners, so it is a §7.3 decision")
          .containsExactly(1L, 3L, 4L);
    }

    @Test
    @DisplayName("a nested time field, because a payload is not always flat")
    void nestedField() {
      assertThat(ids(EvictionPolicy.window("Event", "meta.at", 100)
          .selectVictims(view(nested(1, 10), nested(2, 500)))))
          .containsExactly(1L);
    }

    @Test
    @DisplayName("an empty or malformed field path is a call the caller got wrong")
    void badFieldPath() {
      assertThatThrownBy(() -> EvictionPolicy.window("T", "", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("timeField");
      assertThatThrownBy(() -> EvictionPolicy.window("T", "a..b", 1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("selecting nothing is the answer for a type no fact has")
    void unknownType() {
      assertThat(policy.selectVictims(view(timed(1, "Account", 1)))).isEmpty();
    }

    @Test
    @DisplayName("a fact that arrives already outside the window is selected on arrival")
    void lateArrivalsAreEvictedImmediately() {
      /*
       * The ordinary out-of-order case for any watermark-based window, and the one eviction that
       * costs a firing an author might have expected: the fact is taken inside the insert call that
       * added it, so the handle comes back pointing at nothing. Correct -- a fact older than the
       * window is one no windowed rule can match -- and asserted here so that it is a decision
       * rather than a surprise. EvictionPolicy.window's fourth note says the same in prose.
       */
      assertThat(ids(policy.selectVictims(view(
          timed(1, "LoginFailure", 1_000),
          timed(2, "LoginFailure", 1_050),
          timed(3, "LoginFailure", 10)))))
          .describedAs("the newest arrival is the oldest fact, and it goes straight back out")
          .containsExactly(3L);
    }

    @Test
    @DisplayName("retracting the newest fact widens retention rather than breaking it")
    void theWatermarkFollowsWhatIsHeld() {
      // "The newest value the type currently holds", not "has ever been shown" -- the view is all
      // this policy sees. Widening is the harmless direction, and it stays a pure function.
      assertThat(policy.selectVictims(view(
          timed(1, "LoginFailure", 1_000),
          timed(2, "LoginFailure", 1_200))))
          .describedAs("with the 1900 of the first case retracted, nothing is outside the window")
          .isEmpty();
    }

    @Test
    @DisplayName("it names itself the way it was configured")
    void describesItself() {
      assertThat(policy).hasToString("window(LoginFailure.at, 600)");
    }
  }
}
