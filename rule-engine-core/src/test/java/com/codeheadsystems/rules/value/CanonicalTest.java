package com.codeheadsystems.rules.value;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec section 2.6.2: numeric canonicalisation, and the two rules that are not optional.
 *
 * <p>Both failures this guards against are silent. A scale-sensitive hash puts {@code 10000} and
 * {@code 10000.0} in different index buckets and the rule simply never matches; a mixed key type
 * puts {@code TextNode("A")} and {@code String("A")} in different buckets and a probe built one way
 * misses an entry stored the other. Neither throws.
 */
class CanonicalTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  @Test
  @DisplayName("BigDecimal.equals really is scale-sensitive, which is why the rule exists")
  void theProblemIsReal() {
    // Documented in BigDecimal's own Javadoc, and the reason canonicalisation is not just
    // "call decimalValue()".
    assertThat(new BigDecimal("10000")).isNotEqualTo(new BigDecimal("10000.0"));
    assertThat(new BigDecimal("10000")).usingComparator(BigDecimal::compareTo)
        .isEqualTo(new BigDecimal("10000.0"));
  }

  @Test
  @DisplayName("10000, 10000.0 and 1e4 hash to one key, whatever the JSON source wrote")
  void numericEncodingsCanonicaliseTogether() throws Exception {
    final JsonNode asInt = MAPPER.readTree("10000");
    final JsonNode asDouble = MAPPER.readTree("10000.0");
    final JsonNode asExponent = MAPPER.readTree("1e4");

    // The three parse to genuinely different node types. That is the whole problem.
    assertThat(asInt.isIntegralNumber()).isTrue();
    assertThat(asDouble.isFloatingPointNumber()).isTrue();

    final Object key = Canonical.hashKey(asInt).orElseThrow();
    assertThat(Canonical.hashKey(asDouble)).contains(key);
    assertThat(Canonical.hashKey(asExponent)).contains(key);
    assertThat(Canonical.hashKey(asDouble).orElseThrow().hashCode()).isEqualTo(key.hashCode());
  }

  @Test
  @DisplayName("canonical keys survive a hash map, which is what index buckets actually are")
  void canonicalKeysShareABucket() throws Exception {
    final Map<Object, String> bucket = new LinkedHashMap<>();
    bucket.put(Canonical.hashKey(MAPPER.readTree("10000")).orElseThrow(), "stored as 10000");
    assertThat(bucket.get(Canonical.hashKey(MAPPER.readTree("10000.0")).orElseThrow()))
        .isEqualTo("stored as 10000");
    assertThat(bucket).hasSize(1);
  }

  @Test
  @DisplayName("exactly one Java type per compatibility class, and never a JsonNode")
  void oneTypePerClass() {
    assertThat(Canonical.hashKey(NODES.textNode("A"))).containsInstanceOf(String.class);
    assertThat(Canonical.hashKey(NODES.numberNode(1))).containsInstanceOf(BigDecimal.class);
    assertThat(Canonical.hashKey(NODES.numberNode(1.5d))).containsInstanceOf(BigDecimal.class);
    assertThat(Canonical.hashKey(NODES.booleanNode(true))).containsInstanceOf(Boolean.class);

    assertThat(Canonical.hashKey(NODES.textNode("A")).orElseThrow())
        .isNotInstanceOf(JsonNode.class)
        .isEqualTo("A");
  }

  @Test
  @DisplayName("absent, null and containers are not hash keys")
  void nonKeys() {
    assertThat(Canonical.hashKey(com.fasterxml.jackson.databind.node.MissingNode.getInstance()))
        .isEmpty();
    assertThat(Canonical.hashKey(NODES.nullNode())).isEmpty();
    assertThat(Canonical.hashKey(NODES.objectNode())).isEmpty();
    assertThat(Canonical.hashKey(NODES.arrayNode())).isEmpty();
  }

  @Test
  @DisplayName("the ordering path uses compareTo, so a TreeMap is correct without stripping")
  void orderingUsesCompareTo() {
    final TreeMap<BigDecimal, String> sorted = new TreeMap<>();
    sorted.put(new BigDecimal("10000"), "first");
    // compareTo says these are equal, so a TreeMap replaces rather than adds -- which is exactly
    // why the sorted path needs no stripTrailingZeros.
    sorted.put(new BigDecimal("10000.0"), "second");
    assertThat(sorted).hasSize(1).containsValue("second");

    assertThat(Canonical.compare(NODES.numberNode(1), NODES.numberNode(2))).hasValue(-1);
    assertThat(Canonical.compare(NODES.numberNode(2), NODES.numberNode(1))).hasValue(1);
    assertThat(Canonical.compare(NODES.numberNode(1), NODES.numberNode(1.0d))).hasValue(0);
  }

  @Test
  @DisplayName("comparison across compatibility classes is undefined, not arbitrary")
  void crossClassComparisonIsUndefined() {
    assertThat(Canonical.compare(NODES.numberNode(1), NODES.textNode("1"))).isEmpty();
    assertThat(Canonical.compare(NODES.booleanNode(true), NODES.booleanNode(false))).isEmpty();
    assertThat(Canonical.compare(NODES.objectNode(), NODES.objectNode())).isEmpty();
    assertThat(Canonical.compare(NODES.nullNode(), NODES.numberNode(1))).isEmpty();
  }

  @Test
  @DisplayName("a non-finite double is not canonicalisable rather than an engine failure")
  void nonFiniteDoublesDoNotThrow() {
    // JSON has no NaN, but a payload built in Java with JsonNodeFactory can carry one, and
    // BigDecimal.valueOf(Double.NaN) throws. Failing every comparison beats failing the engine.
    assertThat(Canonical.hashKey(NODES.numberNode(Double.NaN))).isEmpty();
    assertThat(Canonical.compare(NODES.numberNode(Double.POSITIVE_INFINITY), NODES.numberNode(1)))
        .isEmpty();
  }

  @Test
  @DisplayName("floating-point arithmetic still does not round-trip, and no engine can fix that")
  void floatingPointEqualityRemainsASmell() {
    // Documented in section 2.6.2: exact equality on floating-point fields is a rule-authoring
    // smell, and ranges are the correct tool. This test pins the behaviour so nobody "fixes" it
    // with an epsilon that would then be wrong for everyone else.
    final JsonNode computed = NODES.numberNode(0.1d + 0.2d);
    final JsonNode literal = NODES.numberNode(0.3d);
    assertThat(Canonical.hashKey(computed)).isNotEqualTo(Canonical.hashKey(literal));
    assertThat(Canonical.compare(computed, literal)).hasValue(1);
  }
}
