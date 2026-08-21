package com.codeheadsystems.rules.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Value semantics for the two array-carrying types, which spec section 10 audits by name.
 *
 * <p>The failure mode is specific and catastrophic: a record's generated {@code equals}/
 * {@code hashCode} on an array component are <em>identity</em>-based, so two keys binding the same
 * facts would compare unequal. Refraction is keyed on exactly that, so it would silently stop
 * working -- every rule would re-fire on every cycle until the limit, and no test that asserts only
 * on matching would notice.
 */
class TupleAndKeyTest {

  private static final List<String> ALIASES = List.of("o", "c");

  @Test
  @DisplayName("tuples binding the same handles are equal and hash alike")
  void tupleValueEquality() {
    final Tuple left = new Tuple(new long[] {3L, 7L}, ALIASES);
    final Tuple right = new Tuple(new long[] {3L, 7L}, ALIASES);

    assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
    assertThat(new Tuple(new long[] {7L, 3L}, ALIASES)).isNotEqualTo(left);

    final Set<Tuple> deduplicated = new LinkedHashSet<>(List.of(left, right));
    assertThat(deduplicated).hasSize(1);
  }

  @Test
  @DisplayName("activation keys binding the same facts are equal and hash alike")
  void keyValueEquality() {
    final ActivationKey left = new ActivationKey("r", new long[] {3L, 7L});
    final ActivationKey right = new ActivationKey("r", new long[] {3L, 7L});

    assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
    assertThat(new ActivationKey("other", new long[] {3L, 7L})).isNotEqualTo(left);
    assertThat(new ActivationKey("r", new long[] {3L})).isNotEqualTo(left);
  }

  @Test
  @DisplayName("both copy defensively, so a hash key cannot be mutated out from under a map")
  void constructionCopies() {
    final long[] handles = {3L, 7L};
    final Tuple tuple = new Tuple(handles, ALIASES);
    final ActivationKey key = new ActivationKey("r", handles);
    final int keyHash = key.hashCode();

    handles[0] = 99L;

    assertThat(tuple).isEqualTo(new Tuple(new long[] {3L, 7L}, ALIASES));
    assertThat(key).isEqualTo(new ActivationKey("r", new long[] {3L, 7L}));
    assertThat(key.hashCode()).isEqualTo(keyHash);
  }

  @Test
  @DisplayName("accessors return copies too, so a reader cannot corrupt a live key")
  void accessorsCopy() {
    final ActivationKey key = new ActivationKey("r", new long[] {3L, 7L});
    key.handles()[0] = 99L;
    assertThat(key.handles()).containsExactly(3L, 7L);

    final Tuple tuple = new Tuple(new long[] {3L, 7L}, ALIASES);
    tuple.boundFacts()[0] = 99L;
    assertThat(tuple.boundFacts()).containsExactly(3L, 7L);
  }

  @Test
  @DisplayName("the lexicographic tie-break is a total order derived from the match itself")
  void lexicographicOrder() {
    final ActivationKey first = new ActivationKey("a", new long[] {1L, 2L});
    final ActivationKey second = new ActivationKey("a", new long[] {1L, 3L});
    final ActivationKey third = new ActivationKey("b", new long[] {0L});

    assertThat(ActivationKey.LEXICOGRAPHIC.compare(first, second)).isNegative();
    assertThat(ActivationKey.LEXICOGRAPHIC.compare(second, third)).isNegative();
    assertThat(ActivationKey.LEXICOGRAPHIC.compare(first, first)).isZero();
    // Consistency with equals: zero exactly when equal.
    assertThat(ActivationKey.LEXICOGRAPHIC.compare(first, new ActivationKey("a", new long[] {1L, 2L})))
        .isZero();
  }

  @Test
  @DisplayName("a tuple resolves an alias to its bound handle")
  void aliasResolution() {
    final Tuple tuple = new Tuple(new long[] {3L, 7L}, ALIASES);
    assertThat(tuple.handleOf("o").id()).isEqualTo(3L);
    assertThat(tuple.handleOf("c").id()).isEqualTo(7L);
    assertThat(tuple.size()).isEqualTo(2);
  }
}
