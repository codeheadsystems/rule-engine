# rule-engine

An in-process, forward-chaining production rule engine for the JVM: JSON-native facts, an
immutable compiled rule set shared across everything, and cheap single-writer sessions that make
high-concurrency evaluation the default rather than an afterthought.

The design is specified in full in [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md). This
README covers what is built and how to run it.

## Status: Phase 2 is v1, and the DSL of Phase 5 has landed

Phases 0, 1 and 2 of the spec's roadmap (§9) are complete. §9 marks the end of Phase 2 as v1: the
complete engine for one-shot and batch sessions. Phase 5's rule-file front end is built on top of
it, so rules are written in YAML or JSON rather than in Java.

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

**Phase 2** is the **join**: both ends of every join edge are indexed, and the binding order is
chosen fresh on each fire cycle — smallest memory first, connected before disconnected. A rule's
*written* order no longer dictates its cost, which is §3.3's "which side is smaller is a per-fire
decision under TREAT". It also adds `MatchExplainer`, which answers the question a trace cannot:
**why did rule R *not* fire?**

The two matchers are held to agreement by a differential suite covering retraction, join-key churn,
mutating right-hand sides and seeded random walks. The index is a *pure* optimisation: probe results
are intersected with actual pattern membership, so a corrupt index is slow rather than wrong.

**What works today:** single-fact and multi-fact (join) patterns, the full comparison semantics of
§2.6.1, refraction, salience/recency conflict resolution, the gated retract-and-reassert `update`,
RHS staging with the five actions, the firing loop with its work limits, dry runs, strict mode, and
the determinism contract.

**Phase 5's DSL front end** has landed ahead of Phases 3 and 4, because nothing in it depends on
them and everything about authoring rules does. Rule files are JSON or YAML, validated against a
published `rules.v1` schema, and every diagnostic names a file, line and column — including the
compiler's own, which are re-reported against the line that caused them. `CompilerReport` (§7.4)
came with it: `CompiledRuleSet.report()` names every constraint no index can serve, how much node
sharing actually happened, and which rules nothing can activate.

**What does not, and where it arrives:** streaming sessions and Rete joins (Phase 3), the
concurrency helpers and hot reload (Phase 4), and Phase 5's two optional halves — the CEL escape
hatch of §6.4 and the `SchemaRegistry` of §2.3. Negation, accumulation, truth maintenance and CEP
are §1 non-goals with documented interim answers.

## Modules

| Module | Contents |
|---|---|
| `rule-engine-core` | Fact model, working memory, matching primitives, agenda, refraction, sessions |
| `rule-engine-compiler` | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, version hash, `CompilerReport` |
| `rule-engine-dsl` | JSON *and* YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema |
| `rule-engine-observability` | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks |

`-schema` and `-cel` (§8) arrive with the optional halves of Phase 5: the `SchemaRegistry` of §2.3
and the CEL escape hatch of §6.4.

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

## The same rule, as a rule file

The Java builder above is the constraint AST written by hand. The DSL is how rules are meant to be
authored — and the two are held to producing the same rule definitions, constraint for constraint,
by `DslEquivalence`, the way the two matchers are held to identical firing sequences by
`MatcherEquivalence`.

```yaml
apiVersion: rules.v1
rules:
  - id: high-value-order-review
    salience: 10
    noLoop: true
    when:
      - fact: Order
        as: o
        where:
          total:  { gt: 10000 }
          status: { eq: "PENDING" }
      - fact: Customer
        as: c
        where:
          id:       { eq: { $ref: o.customerId } }
          riskTier: { in: ["HIGH", "MEDIUM"] }
    then:
      - action: setField
        target: o
        field: status
        value: "REVIEW"
      - action: emit
        event: "order.flagged"
        payload:
          orderId: { $ref: o.id }
          reason: "high value + risk tier"
```

```java
// RuleSource.of(Path) reads the file, so it declares IOException; the text-taking
// factories (RuleSource.yaml / RuleSource.json) do not.
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
```

JSON and YAML are one language here: both parse into the same object model and compile to the same
rule set, and the entire difference is which Jackson factory reads the text.

A rule file is validated against a published `rules.v1` JSON Schema before anything is built, and
**every diagnostic names a file, line and column** — including the compiler's own semantic ones,
which are re-reported against the line that caused them:

```
rule file is not valid:
  - orders.yaml:8:15: [unknown-dollar-key] '$reff' is not a key this DSL recognises, and §6.2.3
    rejects unrecognised $-prefixed keys rather than passing them through. Write '$$reff' if you
    meant a literal field named '$reff'
```

- **[`docs/dsl-guide.md`](docs/dsl-guide.md)** — start here if you are writing a rule. It opens with
  the three behaviours that surprise people, because two of them shape how you model your data.
- **[`docs/dsl-reference.md`](docs/dsl-reference.md)** — every operator, every action, every
  diagnostic code.

Every rule file printed in either document is a fixture in `DocExamplesTest`, on the same contract
the Java example above has.

## What the compiler knows

`CompiledRuleSet.report()` is §7.4's answer to "no unknown unindexed access" — data, not a printed
string, so a build can assert on it:

```java
CompilerReport report = rules.report();
// rule set sha256:4073bf55c15edf78
//   2 rules, 3 distinct alpha nodes from 4 tests (sharing 1.33x), 2 patterns, 1 join edges
//   unindexed: fraud-check: o.region (NOT_IN)
```

Read `unindexed` by *reason*, not by count. A `RESIDUAL_JOIN_CONDITION` is a join that gave up the
index and is re-evaluated every fire cycle; an `NE` on a single-fact constraint runs once per insert
and never again. The report lists both because §7.4 asks for every unindexed constraint, and
distinguishes them because treating them alike sends people to optimise the wrong one.

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
./gradlew testCodeCoverageReport   # aggregated coverage across every module
```

Coverage is aggregated deliberately: most of `-core` is exercised by the end-to-end tests in
`-testkit`, and a per-module report attributes none of that back. It reported `-core` at 26% while
the aggregate figure was 91%.

**Strict mode** turns on every check that is too expensive for production but catches a contract
violation deterministically in test: engine-owned payloads are handed out as copies, an `update`
that aliases the stored payload is rejected, and the conflict-resolution strategy is asserted to be
a total order consistent with equality. §7.5 asks for the full suite to run under it in CI and
forbids it in production; `strictTest` is that run.

## Why didn't my rule fire?

A firing leaves a record; a *non*-firing leaves nothing to look up. `MatchExplainer` is the
diagnostic that goes and looks (§7.2):

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted — already fired at recency 4
//   o: Order — 1 considered, 1 matched
//   c: Customer — 1 considered, 1 matched
```

It deliberately does not use the matching network. The network is optimised to *not* compute what
you want here: an index skips non-candidates without recording why, and a pattern memory holds the
survivors and has forgotten the casualties. So it re-evaluates constraints one at a time against
working memory — slower by every measure, and the only way to know which constraint did the
eliminating.

Pin the facts you are actually asking about when you have them, which is the sharper question:

```java
why = explainer.explain("high-value-order-review", Map.of("o", orderHandle, "c", customerHandle));
```

Three verdicts cover most real cases: no fact of some type exists; N considered and all failed a
named constraint, *with the value that failed it*; and the one nobody guesses — **the rule already
fired on those exact facts**, with the recency it fired at.

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
