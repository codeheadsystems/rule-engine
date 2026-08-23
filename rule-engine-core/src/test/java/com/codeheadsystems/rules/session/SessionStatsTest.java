package com.codeheadsystems.rules.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The counts §4.4's growth surfaces are asserted through, and the copy that keeps them honest. */
class SessionStatsTest {

  @Test
  @DisplayName("the per-type counts are a snapshot, not a view of a session that keeps evicting")
  void perTypeCountsAreASnapshot() {
    /*
     * The session hands over its live counter map, so the only thing between this record and a
     * live view is the copy in the compact constructor. Removing that copy leaves every other test
     * in the suite green -- including the one asserting the map rejects writes, which pins
     * immutability and says nothing about snapshot-ness. This is the test that fails.
     *
     * It matters because a record's equals is expected to be stable: a caller holding two of these
     * to compare "before" and "after" would otherwise find both agreeing, whatever happened in
     * between.
     */
    final Map<String, Long> live = new LinkedHashMap<>();
    live.put("Order", 1L);

    final SessionStats before = new SessionStats(1, 0, 0, 0, 0, 0, 1L, live);
    live.put("Order", 99L);
    live.put("Customer", 5L);
    final SessionStats after = new SessionStats(1, 0, 0, 0, 0, 0, 100L, live);

    assertThat(before.evictedByType())
        .describedAs("taken when the record was built, not when it is read")
        .containsExactly(Map.entry("Order", 1L));
    assertThat(after.evictedByType()).containsExactlyInAnyOrderEntriesOf(
        Map.of("Order", 99L, "Customer", 5L));
    assertThat(before).isNotEqualTo(after);
  }

  @Test
  @DisplayName("the copy handed out rejects writes")
  void perTypeCountsRejectWrites() {
    final SessionStats stats = new SessionStats(0, 0, 0, 0, 0, 0, 0L, Map.of("Order", 1L));

    assertThatThrownBy(() -> stats.evictedByType().put("Order", 2L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("an empty session reports nothing rather than nulls")
  void emptyIsAllZero() {
    final SessionStats empty = SessionStats.empty();

    assertThat(empty.factCount()).isZero();
    assertThat(empty.refractedMatchCount()).isZero();
    assertThat(empty.materialisedMatchCount()).isZero();
    assertThat(empty.materialisedHandleCount()).isZero();
    assertThat(empty.pendingMatchCount()).isZero();
    assertThat(empty.evictedCount()).isZero();
    assertThat(empty.evictedByType()).isEmpty();
  }

  @Test
  @DisplayName("a null map is refused by name rather than at the first read")
  void nullMapIsRefused() {
    assertThatThrownBy(() -> new SessionStats(0, 0, 0, 0, 0, 0, 0L, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("evictedByType");
  }
}
