package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.rule.TestedPaths;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.node.ObjectNode;

/**
 * The prefix trie against the probe loop it replaced (spec §3.4.2).
 *
 * <p>§3.4.2 gives the method as well as the optimisation: "write the probe loop first and use it as
 * the oracle for the trie". The loop is still there -- it is
 * {@link TestedPaths#changedPaths}'s default -- so the oracle is not a test fixture that can drift
 * from the thing it checks. It <em>is</em> the interface's definition of the answer.
 *
 * <p>What makes this worth testing rather than eyeballing: a trie that under-reports does not lose
 * a firing. §3.4.1 step 6 re-asserts unconditionally, so the match is rebuilt either way. What it
 * loses is the refraction clear in step 5 — surfacing much later, in a different rule, as one that
 * should have re-fired and did not. Nothing about that failure points back here.
 */
class PathTrieEquivalenceTest {

  /** Deliberately overlapping paths: an ancestor, its child, a sibling, and an array element. */
  private static final RuleDefinition NESTED = Rules.rule("nested-paths")
      .when("o", "Order", pattern -> pattern
          .hasField("customer", true)
          .eq("customer.email", "a@example.com")
          .eq("customer.address.city", "Ely")
          .gt("total", 10)
          .eq("items.0.sku", "A")
          .eq("status", "PENDING"))
      .then(actions -> actions.emit("hit"))
      .build();

  @Test
  @DisplayName("an ancestor path is reported when only its child changed")
  void ancestorsAreMarked() {
    // §3.4.2 calls this out as the easy thing to get wrong. A rule testing /customer observes a
    // change to /customer/email, so both belong in the changed set.
    final TestedPaths paths = RuleCompiler.compile(List.of(NESTED)).testedPaths();
    final ObjectNode before = order("a@example.com", "Ely");
    final ObjectNode after = order("b@example.com", "Ely");

    final Set<JsonPointer> changed = paths.changedPaths("Order", before, after);

    assertThat(changed).contains(
        JsonPointer.compile("/customer/email"),
        JsonPointer.compile("/customer"));
    assertThat(changed).doesNotContain(JsonPointer.compile("/status"));
  }

  @Test
  @DisplayName("a deeply nested change marks every tested ancestor on the route")
  void deepChangeMarksTheWholeRoute() {
    final TestedPaths paths = RuleCompiler.compile(List.of(NESTED)).testedPaths();

    final Set<JsonPointer> changed = paths.changedPaths("Order",
        order("a@example.com", "Ely"), order("a@example.com", "Cambridge"));

    assertThat(changed).containsExactlyInAnyOrder(
        JsonPointer.compile("/customer"),
        JsonPointer.compile("/customer/address/city"));
  }

  @Test
  @DisplayName("a path through an array index is not silently invisible")
  void arrayIndicesAreWalked() {
    // JsonNode.path(String) returns missing for an array, so a trie that only ever used the string
    // overload would report "unchanged" for every update to items.0.sku -- a missed refraction
    // clear on every single one, and nothing would ever point here.
    final TestedPaths paths = RuleCompiler.compile(List.of(NESTED)).testedPaths();
    final ObjectNode before = order("a@example.com", "Ely");
    final ObjectNode after = order("a@example.com", "Ely");
    ((ObjectNode) after.get("items").get(0)).put("sku", "CHANGED");

    assertThat(paths.changedPaths("Order", before, after))
        .contains(JsonPointer.compile("/items/0/sku"));
  }

  @Test
  @DisplayName("an identical payload reports nothing changed")
  void identicalPayloads() {
    final TestedPaths paths = RuleCompiler.compile(List.of(NESTED)).testedPaths();
    assertThat(paths.changedPaths("Order",
        order("a@example.com", "Ely"), order("a@example.com", "Ely"))).isEmpty();
  }

  @Test
  @DisplayName("the trie agrees with the probe loop on ten thousand random mutations")
  void agreesWithTheOracle() {
    final TestedPaths trie = RuleCompiler.compile(List.of(NESTED)).testedPaths();
    final TestedPaths oracle = probeLoop(trie);
    // A fixed seed: a differential test that is itself non-deterministic reports a different
    // mutation every time it fails.
    final Random random = new Random(20250820L);

    for (int attempt = 0; attempt < 10_000; attempt++) {
      final ObjectNode before = randomOrder(random);
      final ObjectNode after = mutate(before.deepCopy(), random);

      assertThat(trie.changedPaths("Order", before, after))
          .describedAs("trie and probe loop disagree on:%n  before %s%n  after  %s",
              before, after)
          .isEqualTo(oracle.changedPaths("Order", before, after));
    }
  }

  @Test
  @DisplayName("a type no rule patterns has no tested paths, so nothing ever changes")
  void unknownType() {
    final TestedPaths paths = RuleCompiler.compile(List.of(NESTED)).testedPaths();
    assertThat(paths.changedPaths("Telemetry", Facts.obj("a", 1), Facts.obj("a", 2))).isEmpty();
  }

  /**
   * Wraps a compiled artifact so that {@code changedPaths} falls back to the interface default.
   *
   * <p>The oracle is therefore the shipped default implementation, not a copy of it written for the
   * test -- which is the difference between an oracle and a second thing that can also be wrong.
   *
   * @param delegate the compiled tested-path artifact
   * @return the same data, answered by the probe loop
   */
  private static TestedPaths probeLoop(final TestedPaths delegate) {
    return new TestedPaths() {
      @Override
      public Set<JsonPointer> forType(final String factType) {
        return delegate.forType(factType);
      }

      @Override
      public Set<JsonPointer> forRule(final String ruleId, final String factType) {
        return delegate.forRule(ruleId, factType);
      }

      @Override
      public Set<String> rulesTesting(final String factType, final JsonPointer changed) {
        return delegate.rulesTesting(factType, changed);
      }
    };
  }

  /**
   * A payload shaped like the rule's paths.
   *
   * @param email the customer email
   * @param city the customer city
   * @return the payload
   */
  private static ObjectNode order(final String email, final String city) {
    final ObjectNode payload = Facts.json("""
        {"status": "PENDING", "total": 100,
         "customer": {"email": "x", "address": {"city": "y", "postcode": "CB7"}},
         "items": [{"sku": "A", "qty": 1}, {"sku": "B", "qty": 2}]}""");
    ((ObjectNode) payload.get("customer")).put("email", email);
    ((ObjectNode) payload.get("customer").get("address")).put("city", city);
    return payload;
  }

  /**
   * A payload with randomised values at every interesting position, plus some the rules ignore.
   *
   * @param random the seeded source
   * @return the payload
   */
  private static ObjectNode randomOrder(final Random random) {
    final ObjectNode payload = order(pick(random, "a@example.com", "b@example.com"),
        pick(random, "Ely", "Cambridge", "March"));
    payload.put("status", pick(random, "PENDING", "SHIPPED"));
    payload.put("total", random.nextInt(50));
    // A field no rule reads, so it must never appear in a changed set.
    payload.put("ignored", random.nextInt(50));
    ((ObjectNode) payload.get("items").get(0)).put("sku", pick(random, "A", "B", "C"));
    ((ObjectNode) payload.get("items").get(1)).put("qty", random.nextInt(5));
    return payload;
  }

  /**
   * Applies a random mutation, including shape changes the walk has to survive.
   *
   * @param payload the payload to mutate, already a copy
   * @param random the seeded source
   * @return the mutated payload
   */
  private static ObjectNode mutate(final ObjectNode payload, final Random random) {
    switch (random.nextInt(8)) {
      case 0 -> payload.put("status", pick(random, "PENDING", "SHIPPED", "CANCELLED"));
      case 1 -> ((ObjectNode) payload.get("customer")).put("email", "mutated@example.com");
      case 2 -> ((ObjectNode) payload.get("customer").get("address")).put("city", "Norwich");
      case 3 -> ((ObjectNode) payload.get("items").get(0)).put("sku", "MUTATED");
      // Shape changes: a tested path can vanish entirely, or the container above it can.
      case 4 -> payload.remove("customer");
      case 5 -> ((ObjectNode) payload.get("customer")).remove("address");
      case 6 -> payload.set("items", Facts.array());
      // A field nothing reads. The answer must be "nothing changed".
      default -> payload.put("ignored", random.nextInt(1_000));
    }
    return payload;
  }

  /**
   * Picks one of the given values.
   *
   * @param random the seeded source
   * @param values the choices
   * @return one of them
   */
  private static String pick(final Random random, final String... values) {
    return values[random.nextInt(values.length)];
  }
}
