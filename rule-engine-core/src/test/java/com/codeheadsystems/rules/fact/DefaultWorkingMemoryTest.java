package com.codeheadsystems.rules.fact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.rule.TestedPaths;
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.SchemaViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Working memory, and spec section 3.4.1's update algorithm.
 *
 * <p>Section 10 audits three things here, all of which produce silently wrong output rather than a
 * crash: the retract half runs against the old payload before the new one is installed, the handle
 * survives an update, and an update touching no tested path is a <em>measured</em> no-op.
 */
class DefaultWorkingMemoryTest {

  private static final JsonPointer STATUS = Paths.compile("status");
  private static final JsonPointer TOTAL = Paths.compile("total");

  /** A tested-path artifact wired by hand, so these tests need no compiler. */
  private static final TestedPaths PATHS = new TestedPaths() {

    @Override
    public Set<JsonPointer> forType(final String factType) {
      return "Order".equals(factType) ? Set.of(STATUS, TOTAL) : Set.of();
    }

    @Override
    public Set<JsonPointer> forRule(final String ruleId, final String factType) {
      return forType(factType);
    }

    @Override
    public Set<String> rulesTesting(final String factType, final JsonPointer changed) {
      // "status" is read by rule A only; "total" by rule B only. The split is the point: it lets
      // the refraction-scoping assertion below distinguish per-rule from type-wide clearing.
      return Map.of(STATUS, Set.of("A"), TOTAL, Set.of("B")).getOrDefault(changed, Set.of());
    }
  };

  private final Recorder recorder = new Recorder();
  private final DefaultWorkingMemory memory =
      new DefaultWorkingMemory(PATHS, FactSchemas.none(), recorder, false);

  private static ObjectNode order(final String status, final int total) {
    return JsonNodeFactory.instance.objectNode().put("status", status).put("total", total);
  }

  @Nested
  @DisplayName("payload ownership")
  class Ownership {

    @Test
    @DisplayName("insert deep-copies, so the caller keeps its own tree")
    void insertCopies() {
      final ObjectNode mine = order("PENDING", 100);
      final FactHandle handle = memory.insert("Order", mine);

      mine.put("status", "MUTATED BEHIND THE ENGINE'S BACK");

      assertThat(memory.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("insertOwned does not copy, which is the whole point of the variant")
    void insertOwnedRetains() {
      final ObjectNode parsed = order("PENDING", 100);
      final FactHandle handle = memory.insertOwned("Order", parsed);
      assertThat(memory.get(handle).orElseThrow().payload()).isSameAs(parsed);
    }

    @Test
    @DisplayName("update deep-copies too, not just insert")
    void updateCopies() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      final ObjectNode replacement = order("SHIPPED", 100);

      memory.update(handle, replacement);
      replacement.put("status", "MUTATED");

      assertThat(memory.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("SHIPPED");
    }
  }

  @Nested
  @DisplayName("the update algorithm")
  class Updates {

    @Test
    @DisplayName("an update touching no tested path propagates nothing, and it is counted")
    void untestedFieldIsAMeasuredNoOp() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      final long recencyBefore = memory.get(handle).orElseThrow().recency();
      recorder.events.clear();

      final ObjectNode changedElsewhere = order("PENDING", 100);
      changedElsewhere.put("customerEmail", "new@example.com");
      memory.update(handle, changedElsewhere);

      // Asserted on a counter, not inferred: "no propagation happened" is trivially satisfied by
      // an implementation that never propagates.
      assertThat(memory.skippedUpdateCount()).isEqualTo(1);
      assertThat(memory.propagatedUpdateCount()).isZero();
      assertThat(recorder.events).containsExactly("skipped");

      // The payload is ALWAYS replaced; only propagation is conditional.
      final Fact after = memory.get(handle).orElseThrow();
      assertThat(after.payload().get("customerEmail").stringValue()).isEqualTo("new@example.com");
      assertThat(after.recency()).isEqualTo(recencyBefore);
    }

    @Test
    @DisplayName("a re-sent identical payload short-circuits on the fast-path guard")
    void identicalPayloadIsANonEvent() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      recorder.events.clear();

      memory.update(handle, order("PENDING", 100));

      assertThat(memory.skippedUpdateCount()).isEqualTo(1);
      assertThat(recorder.events).containsExactly("skipped");
    }

    @Test
    @DisplayName("an effective update retracts against the OLD payload before installing the new")
    void retractHalfSeesTheOldPayload() {
      // This ordering is the one that produces orphaned index entries and permanent phantom
      // matches if reversed. Phase 0 has no indexes to orphan, but the Phase 1 network inherits
      // this code path, so the ordering is pinned here where it is cheap to check.
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      recorder.events.clear();

      memory.update(handle, order("SHIPPED", 100));

      assertThat(recorder.events)
          .containsExactly("retracted", "refraction:[A]", "inserted", "propagated");
      assertThat(recorder.payloadAtRetract).isEqualTo("PENDING");
      assertThat(recorder.payloadInMemoryAtRetract).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the handle survives an update, which is what keeps refraction working")
    void handleSurvives() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      memory.update(handle, order("SHIPPED", 100));

      final Fact after = memory.get(handle).orElseThrow();
      assertThat(after.handle()).isEqualTo(handle);
      assertThat(memory.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an effective update bumps recency")
    void effectiveUpdateBumpsRecency() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      final long before = memory.get(handle).orElseThrow().recency();

      memory.update(handle, order("SHIPPED", 100));

      assertThat(memory.get(handle).orElseThrow().recency()).isGreaterThan(before);
      assertThat(memory.propagatedUpdateCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("refraction invalidation is scoped to the rules that test a changed path")
    void refractionScopingIsPerRule() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      recorder.events.clear();

      memory.update(handle, order("PENDING", 999));

      assertThat(recorder.events).contains("refraction:[B]");
    }

    @Test
    @DisplayName("updating an unknown handle fails loudly")
    void unknownHandle() {
      assertThatThrownBy(() -> memory.update(new FactHandle(42L), order("X", 1)))
          .isInstanceOf(java.util.NoSuchElementException.class);
    }
  }

  @Nested
  @DisplayName("retract")
  class Retracts {

    @Test
    @DisplayName("a retract clears all of the fact's refraction, unlike an update")
    void retractClearsEverything() {
      final FactHandle handle = memory.insert("Order", order("PENDING", 100));
      recorder.events.clear();

      memory.retract(handle);

      assertThat(recorder.events).containsExactly("retracted", "refraction-all");
      assertThat(memory.get(handle)).isEmpty();
      assertThat(memory.size()).isZero();
    }

    @Test
    @DisplayName("retracting an unknown handle is a no-op, because an RHS may double-retract")
    void retractIsIdempotent() {
      memory.retract(new FactHandle(42L));
      assertThat(recorder.events).isEmpty();
    }
  }

  @Nested
  @DisplayName("factsOfType")
  class Snapshots {

    @Test
    @DisplayName("iteration is by ascending handle id, that is, insertion order")
    void ascendingHandleId() {
      memory.insert("Order", order("A", 1));
      memory.insert("Customer", order("X", 0));
      memory.insert("Order", order("B", 2));

      assertThat(memory.factsOfType("Order").map(fact -> fact.handle().id()).toList())
          .containsExactly(0L, 2L);
    }

    @Test
    @DisplayName("an update does not move a fact in the iteration order")
    void updateDoesNotReorder() {
      // Ordering by recency would: an update moves a fact to the end, so iteration order would
      // silently depend on unrelated update traffic.
      final FactHandle first = memory.insert("Order", order("A", 1));
      memory.insert("Order", order("B", 2));

      memory.update(first, order("A-UPDATED", 1));

      assertThat(memory.factsOfType("Order").map(fact -> fact.handle().id()).toList())
          .containsExactly(0L, 1L);
    }

    @Test
    @DisplayName("the stream survives inserts and retracts made while it is being consumed")
    void snapshotSemantics() {
      final FactHandle first = memory.insert("Order", order("A", 1));
      memory.insert("Order", order("B", 2));

      final List<String> seen = new ArrayList<>();
      memory.factsOfType("Order").forEach(fact -> {
        seen.add(fact.payload().get("status").stringValue());
        // Exactly what an RHS does: insert a derived fact and retract a handled one while a
        // callFunction walks the same type.
        if (seen.size() == 1) {
          memory.insert("Order", order("C", 3));
          memory.retract(first);
        }
      });

      // "B" is still visible; "C" is invisible to an already-started stream.
      assertThat(seen).containsExactly("A", "B");
    }

    @Test
    @DisplayName("a type nothing has inserted yields an empty stream, not a failure")
    void unknownType() {
      assertThat(memory.factsOfType("Nothing")).isEmpty();
    }
  }

  @Nested
  @DisplayName("reserved handles")
  class Reservations {

    @Test
    @DisplayName("a handle can be reserved before the fact exists, then used")
    void reserveThenInsert() {
      final FactHandle reserved = memory.reserveHandle();
      assertThat(memory.get(reserved)).isEmpty();

      memory.insertReserved(reserved, "Order", order("PENDING", 100));

      assertThat(memory.get(reserved)).isPresent();
      assertThat(memory.factsOfType("Order").map(Fact::handle)).containsExactly(reserved);
    }

    @Test
    @DisplayName("an unreserved handle cannot be inserted under")
    void unreservedHandleRejected() {
      assertThatThrownBy(() -> memory.insertReserved(new FactHandle(99L), "Order", order("A", 1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("was not reserved");
    }
  }

  @Nested
  @DisplayName("strict mode")
  class Strict {

    private final DefaultWorkingMemory strict =
        new DefaultWorkingMemory(PATHS, FactSchemas.none(), new Recorder(), true);

    @Test
    @DisplayName("payload() hands out a copy, so a caller cannot mutate engine state")
    void payloadIsCopied() {
      final FactHandle handle = strict.insert("Order", order("PENDING", 100));

      ((ObjectNode) strict.get(handle).orElseThrow().payload()).put("status", "MUTATED");

      assertThat(strict.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an update aliasing the stored payload is rejected deterministically")
    void aliasingRejected() {
      // The get-mutate-update sequence is reachable entirely through supported API, and it breaks
      // the update algorithm in two places. Strict mode catches it in test rather than letting it
      // be discovered as a wrong decision in production.
      final DefaultWorkingMemory lenient = new DefaultWorkingMemory(PATHS, FactSchemas.none(), new Recorder(), false);
      final FactHandle handle = lenient.insertOwned("Order", order("PENDING", 100));
      final JsonNode live = lenient.get(handle).orElseThrow().payload();

      final DefaultWorkingMemory checked = new DefaultWorkingMemory(PATHS, FactSchemas.none(), new Recorder(), true);
      final FactHandle checkedHandle = checked.insertOwned("Order", (ObjectNode) live);

      assertThatThrownBy(() -> checked.update(checkedHandle, live))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shares a node");
    }

    @Test
    @DisplayName("sharing a SUBTREE is caught too, not just an identical root")
    void subtreeSharingRejected() {
      final ObjectNode customer = JsonNodeFactory.instance.objectNode().put("id", 7);
      final ObjectNode original = JsonNodeFactory.instance.objectNode().put("status", "PENDING");
      original.set("customer", customer);
      final FactHandle handle = strict.insertOwned("Order", original);

      final ObjectNode rebuilt = JsonNodeFactory.instance.objectNode().put("status", "SHIPPED");
      rebuilt.set("customer", customer);

      assertThatThrownBy(() -> strict.update(handle, rebuilt))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("shares a node");
    }

    @Test
    @DisplayName("Jackson's interned scalar nodes are not aliasing, and must not be reported as it")
    void internedScalarsAreNotAliasing() {
      // Jackson interns BooleanNode.TRUE, NullNode.instance and small IntNode values, so two
      // payloads built entirely independently routinely share those instances. An identity check
      // that counted them would reject every honest caller whose fact holds a small integer --
      // which is most of them. Immutable nodes have no mutation to observe, so they cannot cause
      // the bug the check exists to find.
      final FactHandle handle = strict.insert("Order",
          JsonNodeFactory.instance.objectNode()
              .put("id", 1).put("flag", true).putNull("closedAt"));

      strict.update(handle, JsonNodeFactory.instance.objectNode()
          .put("id", 1).put("flag", true).putNull("closedAt").put("status", "SHIPPED"));

      assertThat(strict.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("SHIPPED");
    }

    @Test
    @DisplayName("an honestly fresh payload is accepted")
    void freshPayloadAccepted() {
      final FactHandle handle = strict.insert("Order", order("PENDING", 100));
      strict.update(handle, order("SHIPPED", 100));
      assertThat(strict.get(handle).orElseThrow().payload().get("status").stringValue())
          .isEqualTo("SHIPPED");
    }
  }

  /** Records the observer callbacks, in order, plus what was visible when they fired. */
  private final class Recorder implements WorkingMemoryObserver {

    private final List<String> events = new ArrayList<>();
    private String payloadAtRetract;
    private String payloadInMemoryAtRetract;

    @Override
    public void factInserted(final Fact fact) {
      events.add("inserted");
    }

    @Override
    public void factRetracted(final Fact fact) {
      events.add("retracted");
      // stringValue(null), not stringValue(). path() yields a MissingNode for an absent field, and
      // Jackson 3's no-arg accessor THROWS on a non-string where Jackson 2's textValue() returned
      // null. The one-arg form restores the old semantics, which is what this recorder wants: it is
      // asking "what was the status, if there was one".
      payloadAtRetract = fact.payload().path("status").stringValue(null);
      payloadInMemoryAtRetract = memory.get(fact.handle())
          .map(current -> current.payload().path("status").stringValue(null))
          .orElse("(gone)");
    }

    @Override
    public void refractionInvalidatedAll(final FactHandle handle) {
      events.add("refraction-all");
    }

    @Override
    public void refractionInvalidated(final FactHandle handle, final Set<String> ruleIds) {
      events.add("refraction:" + ruleIds);
    }

    @Override
    public void updatePropagated(final Fact before, final Fact after,
        final Set<JsonPointer> changedTestedPaths) {
      events.add("propagated");
    }

    @Override
    public void updateSkipped(final Fact fact) {
      events.add("skipped");
    }
  }

  @Nested
  @DisplayName("a reserved handle whose payload is rejected")
  class RejectedReservation {

    /*
     * RhsExecutor reserves a handle at stage time and explains why one must never escape
     * unreleased: it would leak one id per firing, forever, under a skip-and-continue policy.
     * Schema validation added a new way for insertReserved to throw, so the reservation has to be
     * consumed on that path too -- which is only observable inside one working memory, since
     * `reserved` is per-session state.
     */

    private final DefaultWorkingMemory memory = new DefaultWorkingMemory(
        PATHS, rejectAll("Order"), new Recorder(), false);

    @Test
    @DisplayName("is consumed, not left reserved for a later insert to find")
    void reservationConsumedOnRejection() {
      final FactHandle handle = memory.reserveHandle();

      assertThatThrownBy(() -> memory.insertReserved(handle, "Order", JsonNodeFactory.instance.objectNode()))
          .isInstanceOf(SchemaViolationException.class);

      assertThatThrownBy(() -> memory.insertReserved(handle, "Order", JsonNodeFactory.instance.objectNode()))
          .as("the id is spent; validating before consuming it would leak one per firing")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("was not reserved");
    }

    /** A registry that rejects every payload of one type, and knows nothing else. */
    private FactSchemas rejectAll(final String factType) {
      return new FactSchemas() {
        @Override
        public java.util.List<String> violations(final String type,
            final tools.jackson.databind.JsonNode payload) {
          return factType.equals(type) ? java.util.List.of("rejected by the test") : List.of();
        }

        @Override
        public java.util.Optional<com.codeheadsystems.rules.schema.SchemaType> typeOf(
            final String type, final String dottedPath) {
          return java.util.Optional.empty();
        }

        @Override
        public com.codeheadsystems.rules.schema.Presence presence(final String type,
            final String dottedPath) {
          return com.codeheadsystems.rules.schema.Presence.UNKNOWN;
        }
      };
    }
  }
}
