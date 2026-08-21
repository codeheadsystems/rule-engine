package com.codeheadsystems.rules.rete;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.match.Tuple;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The beta memory's own bookkeeping (spec §9, Phase 3's steady-state criterion).
 *
 * <p>Tested directly rather than through a session because the thing at risk is not visible from
 * one. §9 asks that a streaming session reach a steady-state heap, and the leak that criterion is
 * about hides where a session cannot see it: the reverse index could retain an entry for every
 * handle ever inserted while the match count sat at zero, and every session-level assertion --
 * working memory empty, nothing fires -- would still pass.
 */
class BetaMemoryTest {

  private static Tuple tuple(final long... handles) {
    return new Tuple(handles, List.of("a", "b"));
  }

  @Test
  @DisplayName("removing a fact's matches removes its index entries too")
  void removeInvolvingClearsTheIndex() {
    final BetaMemory memory = new BetaMemory();
    memory.add(tuple(1L, 2L));
    memory.add(tuple(2L, 1L));

    assertThat(memory.size()).isEqualTo(2);
    assertThat(memory.indexedHandles()).isEqualTo(2);

    assertThat(memory.removeInvolving(1L)).hasSize(2);

    // Handle 2 appeared only in matches that also bound handle 1, so its index entry has to go as
    // well. Removing only the retracted handle's own entry is the leak: the map would keep growing
    // with empty sets for every handle that ever partnered a retracted one.
    assertThat(memory.size()).isZero();
    assertThat(memory.indexedHandles())
        .describedAs("no handle should still be indexed once every match is gone").isZero();
  }

  @Test
  @DisplayName("a long insert-and-retract cycle leaves nothing behind")
  void cyclesReachSteadyState() {
    final BetaMemory memory = new BetaMemory();
    for (long cycle = 0; cycle < 1_000; cycle++) {
      final long left = cycle * 2;
      final long right = left + 1;
      memory.add(tuple(left, right));
      memory.removeInvolving(left);
    }

    assertThat(memory.size()).isZero();
    assertThat(memory.indexedHandles())
        .describedAs("1000 cycles must not accumulate index entries").isZero();
  }

  @Test
  @DisplayName("retracting a handle that holds no matches is a no-op, not a failure")
  void unknownHandleIsHarmless() {
    final BetaMemory memory = new BetaMemory();
    memory.add(tuple(1L, 2L));

    assertThat(memory.removeInvolving(99L)).isEmpty();
    assertThat(memory.size()).isEqualTo(1);
    assertThat(memory.indexedHandles()).isEqualTo(2);
  }

  @Test
  @DisplayName("a partner's matches survive when only one of its tuples is removed")
  void partialRemovalKeepsTheRest() {
    final BetaMemory memory = new BetaMemory();
    memory.add(tuple(1L, 2L));
    memory.add(tuple(3L, 2L));

    memory.removeInvolving(1L);

    // Handle 2 is still in a live match, so it must stay indexed -- the mirror of the leak above,
    // and the failure mode that over-eager cleanup would produce: a retract that quietly drops a
    // surviving fact's matches.
    assertThat(memory.size()).isEqualTo(1);
    assertThat(memory.indexedHandles()).isEqualTo(2);
    assertThat(memory.tuples()).containsExactly(tuple(3L, 2L));
  }
}
