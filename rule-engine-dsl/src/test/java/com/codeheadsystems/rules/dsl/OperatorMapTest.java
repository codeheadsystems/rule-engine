package com.codeheadsystems.rules.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.RangeConstraint;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * §6.2.1's operator table, a row at a time.
 *
 * <p>The table is normative for syntax and §2.6.1 is normative for meaning, so what these tests
 * assert is the mapping between them: that a DSL key produces the {@code Operator} the table says
 * it does, wrapped in the constraint type §2.5 defines for it. Behaviour is not retested here --
 * it belongs to {@code Comparisons}, and a DSL that produces the right AST inherits it.
 */
class OperatorMapTest {

  private final List<DslDiagnostic> raised = new ArrayList<>();

  /** Compiles {@code total: <operatorMap>} and returns the constraints. */
  private List<Constraint> compile(final String operatorMapYaml) {
    return compile("total", operatorMapYaml);
  }

  private List<Constraint> compile(final String field, final String operatorMapYaml) {
    final JsonNode map;
    try {
      map = RuleFormat.YAML.mapper().readTree(operatorMapYaml);
    } catch (final JacksonException broken) {
      throw new AssertionError("the test fixture is not valid YAML: " + operatorMapYaml, broken);
    }
    final Diagnostics diagnostics = new Diagnostics(
        SourceIndex.of(RuleSource.yaml("t.yaml", operatorMapYaml)), raised);
    return OperatorMaps.constraintsOf(field, map, "", diagnostics);
  }

  private List<Constraint> compileCleanly(final String operatorMapYaml) {
    final List<Constraint> constraints = compile(operatorMapYaml);
    assertThat(raised).as("unexpected diagnostics").isEmpty();
    return constraints;
  }

  private FieldConstraint singleField(final String operatorMapYaml) {
    return assertThat(compileCleanly(operatorMapYaml))
        .singleElement().asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(FieldConstraint.class))
        .actual();
  }

  private RangeConstraint singleRange(final String operatorMapYaml) {
    return assertThat(compileCleanly(operatorMapYaml))
        .singleElement().asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(RangeConstraint.class))
        .actual();
  }

  private JoinConstraint singleJoin(final String operatorMapYaml) {
    return assertThat(compileCleanly(operatorMapYaml))
        .singleElement().asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.type(JoinConstraint.class))
        .actual();
  }

  private DslDiagnostic onlyDiagnostic() {
    assertThat(raised).hasSize(1);
    return raised.getFirst();
  }

  @Nested
  @DisplayName("equality")
  class Equality {

    @Test
    @DisplayName("eq compiles to FieldConstraint(EQ)")
    void eq() {
      final FieldConstraint constraint = singleField("{ eq: \"PENDING\" }");

      assertThat(constraint.field()).isEqualTo("total");
      assertThat(constraint.op()).isEqualTo(Operator.EQ);
      assertThat(constraint.literal().stringValue()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("eq null carries an explicit null, which §2.6.1 distinguishes from absent")
    void eqNull() {
      final FieldConstraint constraint = singleField("{ eq: null }");

      assertThat(constraint.op()).isEqualTo(Operator.EQ);
      assertThat(constraint.literal().isNull()).isTrue();
      assertThat(constraint.literal().isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("ne compiles to FieldConstraint(NE)")
    void ne() {
      assertThat(singleField("{ ne: \"CLOSED\" }").op()).isEqualTo(Operator.NE);
    }
  }

  @Nested
  @DisplayName("the ordered operators")
  class Ordered {

    @Test
    @DisplayName("gt is an exclusive lower bound")
    void gt() {
      final RangeConstraint range = singleRange("{ gt: 10000 }");

      assertThat(range.lower()).map(JsonNode::decimalValue).contains(new BigDecimal("10000"));
      assertThat(range.lowerInclusive()).isFalse();
      assertThat(range.upper()).isEmpty();
    }

    @Test
    @DisplayName("gte is an inclusive lower bound")
    void gte() {
      final RangeConstraint range = singleRange("{ gte: 10000 }");

      assertThat(range.lowerInclusive()).isTrue();
      assertThat(range.upper()).isEmpty();
    }

    @Test
    @DisplayName("lt is an exclusive upper bound")
    void lt() {
      final RangeConstraint range = singleRange("{ lt: 500 }");

      assertThat(range.lower()).isEmpty();
      assertThat(range.upperInclusive()).isFalse();
    }

    @Test
    @DisplayName("lte is an inclusive upper bound")
    void lte() {
      final RangeConstraint range = singleRange("{ lte: 500 }");

      assertThat(range.lower()).isEmpty();
      assertThat(range.upperInclusive()).isTrue();
    }
  }

  @Nested
  @DisplayName("between")
  class Between {

    @Test
    @DisplayName("takes named bounds, both inclusivity flags defaulting to true")
    void defaultsAreInclusive() {
      final RangeConstraint range = singleRange("{ between: { from: 100, to: 500 } }");

      assertThat(range.lower()).map(JsonNode::decimalValue).contains(new BigDecimal("100"));
      assertThat(range.upper()).map(JsonNode::decimalValue).contains(new BigDecimal("500"));
      assertThat(range.lowerInclusive()).isTrue();
      assertThat(range.upperInclusive()).isTrue();
    }

    @Test
    @DisplayName("honours explicit inclusivity, which is why §2.5 named the bounds")
    void explicitInclusivity() {
      final RangeConstraint range = singleRange(
          "{ between: { from: 100, to: 500, fromInclusive: true, toInclusive: false } }");

      assertThat(range.lowerInclusive()).isTrue();
      assertThat(range.upperInclusive()).isFalse();
    }

    @Test
    @DisplayName("one-sided is identical to the short form, exactly as §6.2.1 promises")
    void oneSidedEqualsShortForm() {
      final RangeConstraint viaBetween = singleRange("{ between: { from: 100 } }");
      raised.clear();
      final RangeConstraint viaGte = singleRange("{ gte: 100 }");

      assertThat(viaBetween).isEqualTo(viaGte);
    }

    @Test
    @DisplayName("with no bound at all is rejected rather than compiled into nothing")
    void noBounds() {
      assertThat(compile("{ between: { fromInclusive: true } }")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.EMPTY_RANGE);
    }

    @Test
    @DisplayName("mixes a literal bound with a referencing one, giving a range and a join")
    void mixedBounds() {
      final List<Constraint> constraints =
          compileCleanly("{ between: { from: { $ref: c.floor }, to: 500 } }");

      assertThat(constraints).hasSize(2);
      assertThat(constraints.getFirst()).isEqualTo(
          new JoinConstraint("total", "c", "floor", Operator.GTE));
      final RangeConstraint range = (RangeConstraint) constraints.get(1);
      assertThat(range.lower()).isEmpty();
      assertThat(range.upper()).map(JsonNode::decimalValue).contains(new BigDecimal("500"));
    }

    @Test
    @DisplayName("folds inclusivity into the operator of a referencing bound")
    void referencingBoundCarriesInclusivity() {
      final List<Constraint> constraints = compileCleanly(
          "{ between: { from: { $ref: c.floor }, to: { $ref: c.ceiling }, "
              + "fromInclusive: false, toInclusive: true } }");

      assertThat(constraints).containsExactly(
          new JoinConstraint("total", "c", "floor", Operator.GT),
          new JoinConstraint("total", "c", "ceiling", Operator.LTE));
    }
  }

  @Nested
  @DisplayName("membership and the single-fact tests")
  class LiteralOnly {

    @Test
    @DisplayName("in compiles to FieldConstraint(IN) over an array literal")
    void in() {
      final FieldConstraint constraint = singleField("{ in: [\"HIGH\", \"MEDIUM\"] }");

      assertThat(constraint.op()).isEqualTo(Operator.IN);
      assertThat(constraint.literal().isArray()).isTrue();
      assertThat(constraint.literal()).hasSize(2);
    }

    @Test
    @DisplayName("notIn compiles to FieldConstraint(NOT_IN), the camelCase to SCREAMING_SNAKE case")
    void notIn() {
      assertThat(singleField("{ notIn: [\"XX\"] }").op()).isEqualTo(Operator.NOT_IN);
    }

    @Test
    @DisplayName("matches compiles to FieldConstraint(MATCHES) carrying the pattern text")
    void matches() {
      final FieldConstraint constraint = singleField("{ matches: \"^[a-z]+@example\\\\.com$\" }");

      assertThat(constraint.op()).isEqualTo(Operator.MATCHES);
      assertThat(constraint.literal().stringValue()).isEqualTo("^[a-z]+@example\\.com$");
    }

    @Test
    @DisplayName("hasField carries its polarity in the literal, not in a second operator")
    void hasField() {
      final FieldConstraint present = singleField("{ hasField: false }");

      assertThat(present.op()).isEqualTo(Operator.HAS_FIELD);
      assertThat(present.literal().booleanValue()).isFalse();
    }

    @Test
    @DisplayName("isNull carries its polarity in the literal too")
    void isNull() {
      final FieldConstraint constraint = singleField("{ isNull: true }");

      assertThat(constraint.op()).isEqualTo(Operator.IS_NULL);
      assertThat(constraint.literal().booleanValue()).isTrue();
    }

    @Test
    @DisplayName("a $ref is rejected on a single-fact test, which has no other fact to name")
    void refRejectedOnSingleFactTest() {
      assertThat(compile("{ matches: { $ref: c.pattern } }")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.MALFORMED_OPERAND);
    }
  }

  @Nested
  @DisplayName("$ref, the join-reference syntax")
  class Refs {

    @Test
    @DisplayName("an eq against a $ref is a JoinConstraint, not a literal comparison")
    void eqRef() {
      final JoinConstraint join = singleJoin("{ eq: { $ref: o.customerId } }");

      assertThat(join).isEqualTo(new JoinConstraint("total", "o", "customerId", Operator.EQ));
    }

    @Test
    @DisplayName("an ordered operator against a $ref joins too, which §3.3 indexes from both ends")
    void orderedRef() {
      assertThat(singleJoin("{ gt: { $ref: c.creditLimit } }"))
          .isEqualTo(new JoinConstraint("total", "c", "creditLimit", Operator.GT));
    }

    @Test
    @DisplayName("a dotted reference keeps its whole path on the other side")
    void dottedRef() {
      assertThat(singleJoin("{ eq: { $ref: o.customer.id } }").otherField())
          .isEqualTo("customer.id");
    }

    @Test
    @DisplayName("a $ref whose target is not a string is rejected")
    void nonTextualRefTarget() {
      // Reachable end to end: the schema types `eq`'s operand as `true`, i.e. anything.
      assertThat(compile("{ eq: { $ref: 5 } }")).isEmpty();

      final DslDiagnostic diagnostic = onlyDiagnostic();
      assertThat(diagnostic.error()).isEqualTo(DslError.MALFORMED_REFERENCE);
      assertThat(diagnostic.message()).contains("number");
    }

    @Test
    @DisplayName("a reference that is not 'alias.field' is rejected")
    void malformedRef() {
      assertThat(compile("{ eq: { $ref: customerId } }")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.MALFORMED_REFERENCE);
    }

    @Test
    @DisplayName("a reference carrying extra keys is rejected, since §6.2.3 only reserves the shape")
    void refWithExtraKeys() {
      assertThat(compile("{ eq: { $ref: o.id, transform: upper } }")).isEmpty();

      assertThat(onlyDiagnostic().message()).contains("transform");
    }
  }

  @Nested
  @DisplayName("the $$ref escape and $-key strictness")
  class Escaping {

    @Test
    @DisplayName("$$ref writes the literal object §6.2.3 says a bare $ref would swallow")
    void escapeProducesLiteral() {
      final FieldConstraint constraint = singleField("{ eq: { $$ref: \"not-a-reference\" } }");

      assertThat(constraint.op()).isEqualTo(Operator.EQ);
      assertThat(constraint.literal().isObject()).isTrue();
      assertThat(constraint.literal().get("$ref").stringValue()).isEqualTo("not-a-reference");
    }

    @Test
    @DisplayName("an unrecognised $-key is rejected rather than passed through")
    void unknownDollarKeyRejected() {
      assertThat(compile("{ eq: { $reff: o.id } }")).isEmpty();

      final DslDiagnostic diagnostic = onlyDiagnostic();
      assertThat(diagnostic.error()).isEqualTo(DslError.UNKNOWN_DOLLAR_KEY);
      assertThat(diagnostic.message()).contains("$reff").contains("$$reff");
    }

    @Test
    @DisplayName("a $ref nested inside a literal is rejected, because only an operand may join")
    void nestedRefRejected() {
      assertThat(compile("{ eq: { wrapper: { $ref: o.id } } }")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.MALFORMED_REFERENCE);
    }

    @Test
    @DisplayName("an unrecognised $-key is caught inside an array element too")
    void unknownDollarKeyInsideArray() {
      assertThat(compile("{ in: [ { $nope: 1 } ] }")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.UNKNOWN_DOLLAR_KEY);
    }
  }

  @Nested
  @DisplayName("a map holding more than one operator")
  class Conjunction {

    @Test
    @DisplayName("AND-s them, the way several fields in one where block are AND-ed")
    void severalOperatorsAnd() {
      final List<Constraint> constraints = compileCleanly("{ gt: 100, lt: 500 }");

      assertThat(constraints).hasSize(2);
      assertThat(constraints).allSatisfy(constraint ->
          assertThat(constraint).isInstanceOf(RangeConstraint.class));
    }

    @Test
    @DisplayName("keeps document order, which reaches the rule-set content hash")
    void keepsDocumentOrder() {
      final List<Constraint> constraints =
          compileCleanly("{ hasField: true, ne: \"CLOSED\" }");

      assertThat(constraints).extracting(constraint -> ((FieldConstraint) constraint).op())
          .containsExactly(Operator.HAS_FIELD, Operator.NE);
    }

    @Test
    @DisplayName("reports every bad operator in the map, not merely the first")
    void reportsEveryProblem() {
      compile("{ eq: { $nope: 1 }, ne: { $alsoNope: 2 } }");

      assertThat(raised).hasSize(2);
    }
  }

  @Nested
  @DisplayName("the field path")
  class Fields {

    @Test
    @DisplayName("is carried through in the DSL's dotted form, not pre-compiled to a pointer")
    void dottedFieldKept() {
      final List<Constraint> constraints = compile("customer.id", "{ eq: 7 }");

      assertThat(constraints).singleElement()
          .extracting(Constraint::field).isEqualTo("customer.id");
    }
  }

  @Nested
  @DisplayName("an unknown operator")
  class Unknown {

    @Test
    @DisplayName("is rejected here as well as by the schema, so the switch has no silent default")
    void unknownOperator() {
      assertThat(compile("{ greaterThan: 10 }")).isEmpty();

      final DslDiagnostic diagnostic = onlyDiagnostic();
      assertThat(diagnostic.error()).isEqualTo(DslError.UNKNOWN_OPERATOR);
      assertThat(diagnostic.message()).contains("greaterThan");
    }
  }

  @Nested
  @DisplayName("an operator map with nothing usable in it")
  class Empties {

    @Test
    @DisplayName("an empty map compiles to no constraints and no complaint")
    void emptyMap() {
      assertThat(compileCleanly("{}")).isEmpty();
    }

    @Test
    @DisplayName("a map that is not a map at all is rejected, not silently ignored")
    void scalarWhereAnOperatorMapBelongs() {
      // `total: 5` instead of `total: { eq: 5 }`. The schema catches this first in a real file;
      // this class is the reason it is caught here too rather than compiling to nothing.
      assertThat(compile("5")).isEmpty();

      assertThat(onlyDiagnostic().error()).isEqualTo(DslError.MALFORMED_OPERAND);
    }
  }
}
