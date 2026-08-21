package com.codeheadsystems.rules.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dotted-path compilation and RFC 6901 escaping (spec section 2.6). */
class PathsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("a dotted path becomes a JSON Pointer")
  void dottedPaths() {
    assertThat(Paths.compile("customer.id").toString()).isEqualTo("/customer/id");
    assertThat(Paths.compile("total").toString()).isEqualTo("/total");
    assertThat(Paths.compile("").toString()).isEmpty();
  }

  @Test
  @DisplayName("a field name containing a slash or a tilde is escaped, not split")
  void escaping() throws Exception {
    // Without escaping, a field literally named "a/b" would compile to a two-segment path and the
    // rule would silently never match.
    final JsonNode payload = MAPPER.readTree("{\"a/b\": 1, \"c~d\": 2}");

    assertThat(new JsonPointerAccessor(Paths.compile("a/b")).get(payload).intValue()).isEqualTo(1);
    assertThat(new JsonPointerAccessor(Paths.compile("c~d")).get(payload).intValue()).isEqualTo(2);
  }

  @Test
  @DisplayName("array indices are addressable; wildcards do not exist")
  void arrayIndices() throws Exception {
    // RFC 6901 has no wildcard, which is why collection matching is deferred and why the supported
    // answer is to flatten collections into separate facts at ingestion.
    final JsonNode payload = MAPPER.readTree("{\"items\": [{\"qty\": 5}, {\"qty\": 11}]}");
    assertThat(new JsonPointerAccessor(Paths.compile("items.1.qty")).get(payload).intValue())
        .isEqualTo(11);
  }

  @Test
  @DisplayName("an absent path reads as MissingNode, never null")
  void absentPathsAreNodes() throws Exception {
    final JsonNode payload = MAPPER.readTree("{\"a\": 1}");
    final JsonNode read = new JsonPointerAccessor(Paths.compile("b.c")).get(payload);
    assertThat(read).isNotNull();
    assertThat(read.isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("an empty segment is a compile error, not a silently different path")
  void emptySegmentsRejected() {
    assertThatThrownBy(() -> Paths.compile("a..b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty path segment");
  }
}
