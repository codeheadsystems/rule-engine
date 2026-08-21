package com.codeheadsystems.rules.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.rule.Operator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spec section 2.6.1's comparison table, one case per cell.
 *
 * <p>That table is normative and this test is its executable form. It is the highest-value test in
 * Phase 0 for one reason: "why didn't my rule fire?" is the most common question a rule engine
 * gets, the answer is almost always an undefined comparison edge case, and every later phase --
 * the alpha network, the hash and sorted indexes, the join probes -- inherits whatever this layer
 * decides.
 */
class ComparisonsTest {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final JsonNode ABSENT = MissingNode.getInstance();
  private static final JsonNode EXPLICIT_NULL = NODES.nullNode();
  private static final JsonNode COMPARABLE = NODES.textNode("PENDING");
  private static final JsonNode WRONG_TYPE = NODES.numberNode(42);

  private static final JsonNode NULL_LITERAL = NODES.nullNode();
  private static final JsonNode TEXT_LITERAL = NODES.textNode("PENDING");
  private static final JsonNode OTHER_TEXT_LITERAL = NODES.textNode("SHIPPED");
  private static final JsonNode TRUE = NODES.booleanNode(true);
  private static final JsonNode FALSE = NODES.booleanNode(false);

  /**
   * One cell of the table.
   *
   * @param cell a description used as the test name
   * @param actual the value read from the fact
   * @param op the operator
   * @param literal the constraint literal
   * @param expected whether the constraint should hold
   */
  private record Cell(String cell, JsonNode actual, Operator op, JsonNode literal,
      boolean expected) {

    @Override
    public String toString() {
      return cell;
    }
  }

  static Stream<Cell> tableCells() {
    return Stream.of(
        // ---- row: absent (MissingNode) ------------------------------------------------------
        new Cell("absent / hasField:true", ABSENT, Operator.HAS_FIELD, TRUE, false),
        new Cell("absent / hasField:false", ABSENT, Operator.HAS_FIELD, FALSE, true),
        new Cell("absent / isNull:true", ABSENT, Operator.IS_NULL, TRUE, false),
        new Cell("absent / eq null", ABSENT, Operator.EQ, NULL_LITERAL, false),
        new Cell("absent / eq non-null", ABSENT, Operator.EQ, TEXT_LITERAL, false),
        new Cell("absent / ne null", ABSENT, Operator.NE, NULL_LITERAL, true),
        new Cell("absent / ne non-null", ABSENT, Operator.NE, TEXT_LITERAL, true),
        new Cell("absent / gt", ABSENT, Operator.GT, NODES.numberNode(1), false),
        new Cell("absent / in", ABSENT, Operator.IN, arrayOf(TEXT_LITERAL), false),
        new Cell("absent / notIn", ABSENT, Operator.NOT_IN, arrayOf(TEXT_LITERAL), true),

        // ---- row: explicit null (NullNode) --------------------------------------------------
        new Cell("null / hasField:true", EXPLICIT_NULL, Operator.HAS_FIELD, TRUE, true),
        new Cell("null / hasField:false", EXPLICIT_NULL, Operator.HAS_FIELD, FALSE, false),
        new Cell("null / isNull:true", EXPLICIT_NULL, Operator.IS_NULL, TRUE, true),
        new Cell("null / isNull:false", EXPLICIT_NULL, Operator.IS_NULL, FALSE, false),
        new Cell("null / eq null", EXPLICIT_NULL, Operator.EQ, NULL_LITERAL, true),
        new Cell("null / eq non-null", EXPLICIT_NULL, Operator.EQ, TEXT_LITERAL, false),
        new Cell("null / ne null", EXPLICIT_NULL, Operator.NE, NULL_LITERAL, false),
        new Cell("null / ne non-null", EXPLICIT_NULL, Operator.NE, TEXT_LITERAL, true),
        new Cell("null / gt", EXPLICIT_NULL, Operator.GT, NODES.numberNode(1), false),
        new Cell("null / in non-nulls", EXPLICIT_NULL, Operator.IN, arrayOf(TEXT_LITERAL), false),
        new Cell("null / in including null", EXPLICIT_NULL, Operator.IN,
            arrayOf(NULL_LITERAL, TEXT_LITERAL), true),
        new Cell("null / notIn including null", EXPLICIT_NULL, Operator.NOT_IN,
            arrayOf(NULL_LITERAL), false),

        // ---- row: present, comparable -------------------------------------------------------
        new Cell("comparable / hasField:true", COMPARABLE, Operator.HAS_FIELD, TRUE, true),
        new Cell("comparable / isNull:true", COMPARABLE, Operator.IS_NULL, TRUE, false),
        new Cell("comparable / eq null", COMPARABLE, Operator.EQ, NULL_LITERAL, false),
        new Cell("comparable / eq matching", COMPARABLE, Operator.EQ, TEXT_LITERAL, true),
        new Cell("comparable / eq differing", COMPARABLE, Operator.EQ, OTHER_TEXT_LITERAL, false),
        new Cell("comparable / ne null", COMPARABLE, Operator.NE, NULL_LITERAL, true),
        new Cell("comparable / ne matching", COMPARABLE, Operator.NE, TEXT_LITERAL, false),
        new Cell("comparable / in, present", COMPARABLE, Operator.IN,
            arrayOf(OTHER_TEXT_LITERAL, TEXT_LITERAL), true),
        new Cell("comparable / in, absent", COMPARABLE, Operator.IN,
            arrayOf(OTHER_TEXT_LITERAL), false),
        new Cell("comparable / notIn, absent", COMPARABLE, Operator.NOT_IN,
            arrayOf(OTHER_TEXT_LITERAL), true),

        // ---- row: present, wrong type -------------------------------------------------------
        new Cell("wrong type / hasField:true", WRONG_TYPE, Operator.HAS_FIELD, TRUE, true),
        new Cell("wrong type / isNull:true", WRONG_TYPE, Operator.IS_NULL, TRUE, false),
        new Cell("wrong type / eq null", WRONG_TYPE, Operator.EQ, NULL_LITERAL, false),
        new Cell("wrong type / eq non-null", WRONG_TYPE, Operator.EQ, TEXT_LITERAL, false),
        new Cell("wrong type / ne null", WRONG_TYPE, Operator.NE, NULL_LITERAL, true),
        new Cell("wrong type / ne non-null", WRONG_TYPE, Operator.NE, TEXT_LITERAL, true),
        new Cell("wrong type / gt", WRONG_TYPE, Operator.GT, TEXT_LITERAL, false),
        new Cell("wrong type / in", WRONG_TYPE, Operator.IN, arrayOf(TEXT_LITERAL), false),
        new Cell("wrong type / notIn", WRONG_TYPE, Operator.NOT_IN, arrayOf(TEXT_LITERAL), true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tableCells")
  @DisplayName("section 2.6.1's table holds, cell by cell")
  void tableHolds(final Cell cell) {
    assertThat(Comparisons.test(cell.op(), cell.actual(), cell.literal()))
        .isEqualTo(cell.expected());
  }

  @Nested
  @DisplayName("the two cells the spec singles out as traps")
  class Traps {

    @Test
    @DisplayName("ne against an absent field is TRUE, which is the documented asymmetry")
    void neIsTrueForAbsent() {
      // status: { ne: "CLOSED" } matches an Order with no status at all. The spec accepts this
      // deliberately rather than moving to three-valued logic, and tells authors to pair it with
      // hasField: true when they mean "present and not CLOSED".
      assertThat(Comparisons.test(Operator.NE, ABSENT, NODES.textNode("CLOSED"))).isTrue();
      assertThat(Comparisons.test(Operator.NOT_IN, ABSENT, arrayOf(NODES.textNode("CLOSED"))))
          .isTrue();

      // And the companion that recovers the intended meaning.
      assertThat(Comparisons.test(Operator.HAS_FIELD, ABSENT, TRUE)).isFalse();
    }

    @Test
    @DisplayName("ne: null against an explicit null is FALSE, because NE is defined as !EQ")
    void neNullAgainstExplicitNull() {
      // Collapsing the two `ne` columns of the table into one gets exactly this cell wrong.
      assertThat(Comparisons.test(Operator.EQ, EXPLICIT_NULL, NULL_LITERAL)).isTrue();
      assertThat(Comparisons.test(Operator.NE, EXPLICIT_NULL, NULL_LITERAL)).isFalse();
    }

    @Test
    @DisplayName("eq: null matches an explicit null only, never an absent field")
    void eqNullDistinguishesAbsentFromNull() {
      assertThat(Comparisons.test(Operator.EQ, EXPLICIT_NULL, NULL_LITERAL)).isTrue();
      assertThat(Comparisons.test(Operator.EQ, ABSENT, NULL_LITERAL)).isFalse();
    }

    @Test
    @DisplayName("isNull:false is the negation of the predicate, so it matches an absent field")
    void isNullFalseInheritsTheSameAsymmetry() {
      assertThat(Comparisons.test(Operator.IS_NULL, ABSENT, FALSE)).isTrue();
      assertThat(Comparisons.test(Operator.IS_NULL, EXPLICIT_NULL, FALSE)).isFalse();
      assertThat(Comparisons.test(Operator.IS_NULL, COMPARABLE, FALSE)).isTrue();
    }
  }

  @Nested
  @DisplayName("type-compatibility classes")
  class TypeClasses {

    @Test
    @DisplayName("in and notIn compare a scalar against array ELEMENTS, not against the array")
    void membershipUnwrapsTheArray() {
      assertThat(Comparisons.test(Operator.IN, NODES.numberNode(2),
          arrayOf(NODES.numberNode(1), NODES.numberNode(2)))).isTrue();
      // A non-array literal is a cross-type comparison, hence false.
      assertThat(Comparisons.test(Operator.IN, NODES.numberNode(2), NODES.numberNode(2)))
          .isFalse();
    }

    @Test
    @DisplayName("in is defined as eq against any element, so in:[null] behaves like eq: null")
    void membershipAgreesWithEquality() {
      // §2.6.1's "per element" cell on the null row. Carving null out of membership would make
      // IN disagree with the EQ it is built from, and would stop NOT_IN being !IN.
      assertThat(Comparisons.test(Operator.IN, EXPLICIT_NULL, arrayOf(NULL_LITERAL))).isTrue();
      assertThat(Comparisons.test(Operator.IN, ABSENT, arrayOf(NULL_LITERAL))).isFalse();
    }

    @Test
    @DisplayName("eq on objects is structural: key order does not matter")
    void objectEqualityIsStructural() {
      final JsonNode left = NODES.objectNode().put("a", 1).put("b", 2);
      final JsonNode right = NODES.objectNode().put("b", 2).put("a", 1);
      assertThat(Comparisons.test(Operator.EQ, left, right)).isTrue();
    }

    @Test
    @DisplayName("eq on arrays is structural: element order DOES matter")
    void arrayEqualityIsOrdered() {
      assertThat(Comparisons.test(Operator.EQ,
          arrayOf(NODES.numberNode(1), NODES.numberNode(2)),
          arrayOf(NODES.numberNode(2), NODES.numberNode(1)))).isFalse();
    }

    @Test
    @DisplayName("a scalar never equals a container")
    void scalarsAndContainersDoNotMix() {
      assertThat(Comparisons.test(Operator.EQ, COMPARABLE, NODES.objectNode())).isFalse();
      assertThat(Comparisons.test(Operator.EQ, NODES.objectNode(), COMPARABLE)).isFalse();
      assertThat(Comparisons.test(Operator.EQ, NODES.objectNode(), arrayOf())).isFalse();
    }

    @Test
    @DisplayName("ordering is defined within a class: strings order, booleans do not")
    void orderingIsWithinAClass() {
      assertThat(Comparisons.test(Operator.GT, NODES.textNode("b"), NODES.textNode("a"))).isTrue();
      assertThat(Comparisons.test(Operator.LT, NODES.textNode("a"), NODES.textNode("b"))).isTrue();
      assertThat(Comparisons.test(Operator.GT, TRUE, FALSE)).isFalse();
      assertThat(Comparisons.test(Operator.GT, NODES.numberNode(1), NODES.textNode("a")))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("ranges honour each bound's inclusivity")
  class Ranges {

    @Test
    @DisplayName("half-open ranges, which the spec says are the common case in practice")
    void halfOpen() {
      final Optional<JsonNode> from = Optional.of(NODES.numberNode(100));
      final Optional<JsonNode> to = Optional.of(NODES.numberNode(500));

      assertThat(Comparisons.inRange(NODES.numberNode(100), from, true, to, false)).isTrue();
      assertThat(Comparisons.inRange(NODES.numberNode(500), from, true, to, false)).isFalse();
      assertThat(Comparisons.inRange(NODES.numberNode(100), from, false, to, true)).isFalse();
      assertThat(Comparisons.inRange(NODES.numberNode(500), from, false, to, true)).isTrue();
    }

    @Test
    @DisplayName("one-sided ranges leave the other side unbounded")
    void oneSided() {
      assertThat(Comparisons.inRange(NODES.numberNode(1_000_000),
          Optional.of(NODES.numberNode(100)), true, Optional.empty(), false)).isTrue();
      assertThat(Comparisons.inRange(NODES.numberNode(-1),
          Optional.empty(), false, Optional.of(NODES.numberNode(100)), true)).isTrue();
    }

    @Test
    @DisplayName("absent, null and wrong-typed values fall outside every range")
    void nonComparableValuesAreOutside() {
      final Optional<JsonNode> from = Optional.of(NODES.numberNode(0));
      assertThat(Comparisons.inRange(ABSENT, from, true, Optional.empty(), false)).isFalse();
      assertThat(Comparisons.inRange(EXPLICIT_NULL, from, true, Optional.empty(), false)).isFalse();
      assertThat(Comparisons.inRange(COMPARABLE, from, true, Optional.empty(), false)).isFalse();
    }
  }

  @Test
  @DisplayName("MATCHES is rejected here, because it needs a pattern compiled at rule-compile time")
  void matchesIsNotEvaluatedHere() {
    assertThatThrownBy(() -> Comparisons.test(Operator.MATCHES, COMPARABLE, TEXT_LITERAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rule-compile time");
  }

  /**
   * Builds an array literal.
   *
   * @param elements the elements
   * @return the array node
   */
  private static JsonNode arrayOf(final JsonNode... elements) {
    return NODES.arrayNode().addAll(List.of(elements));
  }
}
