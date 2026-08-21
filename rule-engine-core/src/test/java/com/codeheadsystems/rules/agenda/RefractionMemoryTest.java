package com.codeheadsystems.rules.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.match.ActivationKey;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Refraction bookkeeping (spec section 4.4).
 *
 * <p>The scoping rules here are the ones section 10 audits: a retract makes every match binding the
 * fact eligible again, an effective update makes only the matching rules' matches eligible, and
 * {@code noLoop} protects one specific match from its own right-hand side.
 */
class RefractionMemoryTest {

  private static final ActivationKey RULE_A = new ActivationKey("A", new long[] {1L, 2L});
  private static final ActivationKey RULE_B = new ActivationKey("B", new long[] {1L});

  @Test
  @DisplayName("a recorded match is not eligible again")
  void recordSuppresses() {
    final RefractionMemory memory = new RefractionMemory();
    assertThat(memory.shouldFire(RULE_A)).isTrue();

    memory.record(RULE_A, 10L);

    assertThat(memory.shouldFire(RULE_A)).isFalse();
    assertThat(memory.firedAt(RULE_A)).contains(10L);
    assertThat(memory.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("retracting a bound fact makes every match binding it eligible again")
  void retractInvalidatesEverything() {
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);
    memory.record(RULE_B, 11L);

    memory.invalidateAll(1L);

    assertThat(memory.shouldFire(RULE_A)).isTrue();
    assertThat(memory.shouldFire(RULE_B)).isTrue();
    assertThat(memory.size()).isZero();
  }

  @Test
  @DisplayName("an effective update clears only the rules that test a changed path")
  void updateInvalidatesPerRule() {
    // This is the scoping section 4.4 calls essential. Clearing type-wide instead means an update
    // to a field only rule B tests re-enables rule A's already-fired match, so A fires twice for a
    // reason no author can predict from reading their rule.
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);
    memory.record(RULE_B, 11L);

    memory.invalidateFor(1L, Set.of("B"));

    assertThat(memory.shouldFire(RULE_A)).isFalse();
    assertThat(memory.shouldFire(RULE_B)).isTrue();
  }

  @Test
  @DisplayName("an update touching no rule's paths clears nothing")
  void emptyRuleSetClearsNothing() {
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);

    memory.invalidateFor(1L, Set.of());

    assertThat(memory.shouldFire(RULE_A)).isFalse();
  }

  @Test
  @DisplayName("noLoop protects one match from its own right-hand side, and nothing else")
  void noLoopGuardsOneKey() {
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);
    memory.record(RULE_B, 11L);

    memory.guardNoLoop(RULE_A);
    memory.invalidateFor(1L, Set.of("A", "B"));

    assertThat(memory.shouldFire(RULE_A)).isFalse();
    assertThat(memory.shouldFire(RULE_B)).isTrue();

    memory.guardNoLoop(null);
    memory.invalidateFor(1L, Set.of("A"));
    assertThat(memory.shouldFire(RULE_A)).isTrue();
  }

  @Test
  @DisplayName("the handle index is cleaned up, so invalidation does not leak entries")
  void handleIndexIsMaintained() {
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);

    // The key binds handles 1 and 2. Invalidating through either must remove it from both.
    memory.invalidateAll(2L);
    assertThat(memory.size()).isZero();

    memory.record(RULE_A, 12L);
    memory.invalidateAll(1L);
    assertThat(memory.size()).isZero();
    assertThat(memory.firedAt(RULE_A)).isEmpty();
  }

  @Test
  @DisplayName("recording the same match twice is idempotent")
  void recordIsIdempotent() {
    final RefractionMemory memory = new RefractionMemory();
    memory.record(RULE_A, 10L);
    memory.record(RULE_A, 20L);

    assertThat(memory.size()).isEqualTo(1);
    assertThat(memory.firedAt(RULE_A)).contains(10L);
  }
}
