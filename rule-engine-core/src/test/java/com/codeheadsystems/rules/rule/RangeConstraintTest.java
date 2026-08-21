package com.codeheadsystems.rules.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RangeConstraint}'s canonical form (spec §2.5, §6.2.1).
 *
 * <p>A range is the one constraint an author can write two ways, so it is the one whose equality
 * has to be canonical. Two constraints that behave identically must compare equal, because
 * {@code NetworkBuilder} shares alpha nodes on constraint equality and §5.6's version hash is
 * computed over these records: unequal-but-identical costs a duplicated node and a rule-set version
 * that changes when nothing did.
 */
class RangeConstraintTest {

  @Test
  @DisplayName("the inclusivity of an absent bound is normalised away")
  void absentBoundHasNoInclusivity() {
    final RangeConstraint declaredInclusive = new RangeConstraint(
        "total", Optional.of(IntNode.valueOf(100)), true, Optional.empty(), true);

    assertThat(declaredInclusive.upperInclusive()).isFalse();
    assertThat(declaredInclusive.lowerInclusive()).isTrue();
  }

  @Test
  @DisplayName("so a one-sided between equals the short form, exactly as §6.2.1 promises")
  void oneSidedBetweenEqualsShortForm() {
    // What `between: { from: 100 }` produces: both inclusivity flags defaulting to true.
    final RangeConstraint viaBetween = new RangeConstraint(
        "total", Optional.of(IntNode.valueOf(100)), true, Optional.empty(), true);
    final RangeConstraint viaGte =
        RangeConstraint.of("total", Operator.GTE, IntNode.valueOf(100));

    assertThat(viaBetween).isEqualTo(viaGte);
    assertThat(viaBetween).hasSameHashCodeAs(viaGte);
  }

  @Test
  @DisplayName("the same holds on the upper side")
  void oneSidedUpperBound() {
    final RangeConstraint viaBetween = new RangeConstraint(
        "total", Optional.empty(), true, Optional.of(IntNode.valueOf(500)), true);

    assertThat(viaBetween).isEqualTo(RangeConstraint.of("total", Operator.LTE,
        IntNode.valueOf(500)));
  }

  @Test
  @DisplayName("a present bound keeps the inclusivity it was given")
  void presentBoundsKeepTheirFlags() {
    final RangeConstraint halfOpen = new RangeConstraint(
        "total", Optional.of(IntNode.valueOf(100)), true,
        Optional.of(IntNode.valueOf(500)), false);

    assertThat(halfOpen.lowerInclusive()).isTrue();
    assertThat(halfOpen.upperInclusive()).isFalse();
  }

  @Test
  @DisplayName("a range bounding nothing is still rejected")
  void noBoundsRejected() {
    assertThatThrownBy(() -> new RangeConstraint(
        "total", Optional.empty(), true, Optional.empty(), true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no bounds");
  }
}
