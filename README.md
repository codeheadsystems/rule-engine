# rule-engine

An in-process, forward-chaining production rule engine for the JVM: JSON-native facts, an
immutable compiled rule set shared across everything, and cheap single-writer sessions that make
high-concurrency evaluation the default rather than an afterthought.

The design is specified in full in [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md). This
README covers what is built and how to run it.

## Status: Phase 1

Phases 0 and 1 of the spec's roadmap (§9) are complete.

**Phase 0** is the **naive matcher**: no network, no indexes, no incremental maintenance, and a cost
of `O(rules × facts^arity)`. It is deliberately unoptimised and it is still shipped, because it is
the **correctness oracle** every later phase is differential-tested against. §11.5's exit criterion
for Phase 3 — that two matching strategies produce identical firing sequences — is only checkable
against an implementation that still exists and still runs. Select it with
`SessionOptions.matching(MatchingStrategy.NAIVE)`; never in production.

**Phase 1** is the **alpha network**: one shared node per *distinct* constraint (ten rules
expressing two constraints compile to two nodes, evaluated once per fact), per-pattern memories
holding exactly what matches, hash and sorted indexes on the paths joins probe, §3.4.2's prefix
trie for the update diff, and the tracing and Flight Recorder listeners.

The two matchers are held to agreement by a differential suite covering retraction, join-key churn,
mutating right-hand sides and seeded random walks. The index is a *pure* optimisation: probe results
are intersected with actual pattern membership, so a corrupt index is slow rather than wrong.

**What works today:** single-fact and multi-fact (join) patterns, the full comparison semantics of
§2.6.1, refraction, salience/recency conflict resolution, the gated retract-and-reassert `update`,
RHS staging with the five actions, the firing loop with its work limits, dry runs, strict mode, and
the determinism contract.

**What does not, and where it arrives:** the TREAT join network and match explanations (Phase 2),
streaming sessions and Rete joins (Phase 3), the concurrency helpers and hot reload (Phase 4), the
JSON/YAML DSL and CEL (Phase 5). Negation, accumulation, truth maintenance and CEP are §1 non-goals
with documented interim answers. Rules are written in Java until the DSL lands.

## Modules

| Module | Contents |
|---|---|
| `rule-engine-core` | Fact model, working memory, matching primitives, agenda, refraction, sessions |
| `rule-engine-compiler` | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, version hash |
| `rule-engine-observability` | `TracingListener`, `JfrListener` |
| `rule-engine-testkit` | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks |

`-dsl`, `-schema` and `-cel` (§8) arrive with the phases that need them.

## Example

```java
RuleDefinition rule = Rules.rule("high-value-order-review")
    .salience(10)
    .noLoop()
    .when("o", "Order", p -> p.gt("total", 10000).eq("status", "PENDING"))
    .when("c", "Customer", p -> p.ref("id", "o.customerId").in("riskTier", "HIGH", "MEDIUM"))
    .then(t -> t
        .setField("o", "status", "REVIEW")
        .emit("order.flagged",
            "orderId", Rules.ref("o.id"),
            "reason", "high value + risk tier"))
    .build();

CompiledRuleSet rules = RuleCompiler.compile(List.of(rule));

try (RuleSession session = rules.newSession()) {
    session.insert("Order",    Facts.json("""
        {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
    session.insert("Customer", Facts.json("""
        {"id": 7, "riskTier": "HIGH"}"""));

    FireResult result = session.fireAllRules();
    // result.fired()    -> high-value-order-review, once
    // result.emitted()  -> order.flagged, stamped with the session id and the rule-set version
    // result.why()      -> DRAINED
}
```

This is `SmokeTest.readmeExample`. If the two disagree, the README is wrong.

Emitted events come back as the *return value* of the fire call, because the default sink collects
rather than performing I/O. That is what makes rules testable without mocking anything.

## Concurrency

A `CompiledRuleSet` is immutable and freely shareable; a `RuleSession` holds only mutable state and
is **never shared across threads**. So the natural unit of concurrency is one virtual thread per
session (§5.2):

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<FireResult>> futures = batches.stream()
        .map(batch -> executor.submit(() -> {
            try (RuleSession session = rules.newSession()) {
                batch.forEach(f -> session.insert(f.type(), f.payload()));
                return session.fireAllRules();
            }
        }))
        .toList();
}
```

`halt()` is the one method on a session that may be called from another thread.

## Building

Requires a JDK 25 toolchain; Gradle resolves one via the foojay plugin if it is not installed.

```bash
./gradlew build        # compile, test, and the strict-mode test run
./gradlew test         # the suite
./gradlew strictTest   # the same suite with -Drules.strict=true (spec §7.5)
./gradlew jmh          # benchmarks; see docs/benchmarks.md
./gradlew javadoc      # warnings fail the build; several contracts live only in Javadoc
./gradlew testCodeCoverageReport   # aggregated coverage across all three modules
```

Coverage is aggregated deliberately: most of `-core` is exercised by the end-to-end tests in
`-testkit`, and a per-module report attributes none of that back. It reported `-core` at 26% while
the aggregate figure was 91%.

**Strict mode** turns on every check that is too expensive for production but catches a contract
violation deterministically in test: engine-owned payloads are handed out as copies, an `update`
that aliases the stored payload is rejected, and the conflict-resolution strategy is asserted to be
a total order consistent with equality. §7.5 asks for the full suite to run under it in CI and
forbids it in production; `strictTest` is that run.

## If a rule action throws

The atomicity guarantee is **per-phase**, and the halves differ (§4.6). A staging-phase failure
applies nothing. A commit-phase failure — a `callFunction` handler, an `EventSink`, or a `setField`
whose path runs through a scalar — leaves the working-memory effects that already landed in place.
There is no compensating undo and there cannot be one: a sent message cannot be un-sent.

`FireRecord` is how that partial state is discoverable. It carries what actually committed, which
action threw, and which actions never ran.

**Under the default `RETHROW` policy, register a listener or you will not get that record.** The
spec requires the original exception to propagate to the caller, so — unlike a limit breach — it
cannot carry a partial result. Listeners are the trace mechanism (§7.1), and `onAfterFire` is
published for the failed firing *before* the rethrow. With no listener registered, the record of the
firing, and of every firing before it in that call, is gone with the stack unwind. Use
`ABORT_SESSION` instead if you want the partial `FireResult` returned rather than thrown.

A listener must not throw and must not call back into the session; neither is enforced.

## Things worth knowing before you write a rule

Three behaviours surprise people, and all three are deliberate (§2.6.1, §1):

- **Absent and null are different values.** `{ eq: null }` matches an explicit JSON null and never
  an absent field. Use `hasField: false` for "the field isn't there".
- **`ne` is true for an absent field.** `status != "CLOSED"` matches an order with no `status` at
  all, because `ne` is defined as `!eq`. Pair it with `hasField: true` when you mean "present and
  not closed".
- **Flatten collections at ingestion.** JSON Pointer has no wildcard, so "any line item with
  `qty > 10`" is inexpressible against a nested array. An `Order` with an `items[]` array becomes
  one `Order` fact plus N `LineItem` facts carrying `orderId`, joined normally. This is not a
  workaround — it is how you get indexing and incremental matching over collection elements at all,
  and retrofitting it means rewriting every rule that touches a collection.
