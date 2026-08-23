# rule-engine

An in-process, forward-chaining production rule engine for the JVM: JSON-native facts, an
immutable compiled rule set shared across everything, and cheap single-writer sessions that make
high-concurrency evaluation the default rather than an afterthought.

The design is specified in full in [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md). This
README covers what is built and how to run it.

## Status: Phase 2 is v1; Phases 4 and 5 have landed on top

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

**Phase 5 is now complete**, including both halves §9 marks optional: `FactSchemas` (§2.3) and the
CEL escape hatch (§6.4). An expression can appear as a pattern `condition:` or as an `$expr` value in
a `then` block — the second being a deliberate extension of §11.3's closed verb set, argued in
`docs/dsl-reference.md`. Both live in modules nobody has to depend on.

**Phase 4 is complete**: the immutability invariant §5.5 rests every scaling claim on is now
audited and checked, `RuleSetHolder` swaps a rule set under load, `SessionDrain` moves a running
session onto new rules, and `RuleBatches` runs a batch per session across virtual threads. The
scaling curve is measured rather than asserted — see `docs/benchmarks.md`.

**Phase 3 has started.** `SessionOptions.matching(RETE)` selects a third matcher that materialises
joins as facts arrive instead of recomputing them per fire cycle, for long-lived streaming sessions.
It is held to the same oracle as the other two — every differential scenario in the suite runs under
all three. Both halves of a streaming session's cost are now amortised: the join as facts arrive,
and — since §4.3 — the fire cycle, which ranks the matches waiting to fire rather than every match
held. `SessionActor` (§5.4) makes such a session genuinely long-lived: one worker thread owns it, many
producers feed a bounded inbox, and firing until halt is what the actor does rather than a method
you call — a blocking fire loop plus inserts from another thread would be a data race.

**Session fact-eviction (§4.4) is what makes a long-lived session survive**, and it is the phase's
steady-state exit criterion: a streaming session under sustained insert-without-retract load must
reach a steady heap, not a rising one. `SessionOptions.eviction(...)` takes a policy — a total cap,
or a cap per fact type so a bounded stream can flow past unbounded reference data — and evicting a
fact runs the **full retract path**, so working memory, the node memories and their indexes, the
refraction memory and the beta memory are all bounded by one mechanism rather than four. The policy
is consulted only between operations, never inside a right-hand side. `RuleSession.stats()` is how
that steady state is observed, and asserted: two thousand inserts through a `SessionActor` against a
cap of twenty-five leave twenty-five facts held, with the beta memory, its reverse index and the
refraction memory flat between the thousandth insert and the two-thousandth.

**§4.3's agenda shape has landed, and it is the one change in this project that moved a curve rather
than a constant.** The streaming matcher's conflict set is now pushed and pulled — a match enters
when it is derived and leaves when it fires — instead of being rebuilt from every held match on
every fire. On the streaming benchmark at a working set of 4000 that took an insert-and-fire from
554µs to 3.8µs and its allocation from 1.5MB to 8.3KB per operation, and the fire cycle stopped
growing with the working set: 0.77µs, 1.02µs, 1.12µs across a sixteenfold range where it had been
19.9µs, 100.5µs, 551.4µs. TREAT is unchanged, as predicted — §4.3's interface is the Rete one. See
[`docs/benchmarks.md`](docs/benchmarks.md) for both columns, the profile that decided the scope, and
what it still does not show.

**Phase 3's deliverables are all built.** Its last item, §11.2's differential propagation, is
measured but not decided: §11.2 says to build it only if profiling shows constraint re-testing
dominating, and on the workload §11.2 itself names — a hot fact type, up to sixty four
mutually-disjoint rules, one field changing — **it does.** Update cost is linear in the number of
rules patterning the hot type (227ns, 742ns, 5 911ns per update at 1, 8, 64 rules), because the fact
is retracted from and re-asserted into every one of their pattern memories whichever field changed.
Differential propagation touches only the patterns reading a changed path, so its floor is the
one-rule column: 26x at single-pattern rules, 82x under the streaming matcher with joins.

What that does not settle is whether it is worth its price — a `dependsOn()` obligation on every
node where under-declaring silently loses a firing. The narrower fix that might have avoided that
question was scoped and is not cheap: skipping the index churn needs either the same per-pattern
dependency declaration, or a value-based comparison costing a second alpha evaluation for an
estimated 9% net, and either way a combined update path in place of the retract-and-reassert the
agenda, refraction and eviction all hang off. Both are recorded as measured and not built. See
[`docs/benchmarks.md`](docs/benchmarks.md).

**Negation (`NOT_EXISTS`) exists**, as the first slice of §9's Phase 6. A negated pattern binds
nothing and joins nothing into the tuple, so it is a question asked of a *complete* match — the same
shape a §6.4 condition has — and it is answered in the shared agenda base, which is what makes the
three matchers agree about it by construction rather than by testing. Two boundaries ship with it
and both are documented on `Rules.notExists`: a rule that fired because something was absent is not
undone when that thing arrives — unless what it concluded was inserted logically, which is what the
truth-maintenance section below adds; and a negated type must not be
one a session evicts, because an evicted fact and an absent one are indistinguishable to a negation.
It is written in a rule file as `quantifier: notExists` on a pattern, and from the `Rules` builder
as `notExists`; `docs/dsl-reference.md` carries the rule-file half. The compiler refuses what a
negated alias cannot do -- be referenced by a `$ref`, be named by an action, carry a §6.4
`condition`, or stand as a rule's only pattern -- and says why in each case, because an alias the
author can see in front of them, reported as one the rule does not have, sends them looking for a
typo that is not there.

**`FOR_ALL` now exists too**, as Phase 6's second slice, and it lands on the seam negation opened:
binds nothing, joins against bound aliases, answered in the shared agenda base against a complete
match. What is new is the reading. **A universal pattern's join tests choose the scope; its own
constraints are what is asserted about it** — `forAll li: LineItem (orderId = o.id, inStock)` says
"every line item *of this order* is in stock". §2.5's enum said "every fact of the type matches the
pattern", and that reading is not merely weaker but a trap: it would assert that every `LineItem`
anywhere belongs to this order, false the moment a second order exists, so the rule could never
fire and nothing would say why.

It earns its place on *multi*-constraint requirements. One constraint is already writable as a
negation of its complement — "every order is shipped" is "no order is not shipped" — but the
complement of "in stock **and** qty above zero" is a disjunction, and no pattern here expresses one.
Three boundaries ship with it, documented on `Rules.forAll`: negation's two, inherited unchanged,
plus **vacuous truth** — an order with no line items is "ready to ship", because nothing is there to
fail the requirement. Pair it with a positive pattern of the same type to mean "there are some, and
all of them". Combined with eviction that boundary is the sharpest in the engine: evicting facts can
only remove counterexamples, so a cap does not weaken the requirement but deletes it.

**Truth maintenance now exists**, as Phase 6's third slice, and it pays off the boundary both
quantifiers shipped with. `insertFact` gains a `logical` flag (`insertLogical` from the builder):
a logical insert is a **conclusion held up by the match that made it**, withdrawn when that match
stops holding — the `Payment` arrives, a `forAll` counterexample turns up, a bound fact is
retracted or updated out of matching. Withdrawal cascades, and it is reversible: invalidating a
justification also forgets the refraction on it, or a conclusion withdrawn by something temporary
would stay withdrawn for the life of the session.

Validity is re-asked of the *tuple* rather than diffed against a match set, which is what makes it
work identically under all three matchers — §4.3's streaming conflict set holds only what is waiting
to fire, so a diff would read every fired match as gone. `TupleMatch` shares all four predicates
with the agenda rather than restating them. Two things to know: withdrawal happens at a cycle
boundary, not the instant the reason goes, because §4.6 stages and commits a right-hand side as a
unit; and there is **exactly one justification per conclusion**, so two matches concluding the same
thing produce two facts rather than one held up twice. Deduplicating by payload is a separate
feature from withdrawing — it would change what §2.1 means by fact identity — and is not smuggled in.

**What does not exist:** accumulation and CEP, which are §1 non-goals with documented interim
answers, and distributed evaluation, which §5's immutability split makes feasible without making it
built.

## Modules

| Module | Contents |
|---|---|
| `rule-engine-core` | Fact model, working memory, matching primitives, agenda, refraction, sessions |
| `rule-engine-compiler` | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, version hash, `CompilerReport` |
| `rule-engine-dsl` | JSON *and* YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema |
| `rule-engine-cel` | The optional §6.4 expression escape hatch, backed by dev.cel |
| `rule-engine-schema` | The optional `FactSchemas` of §2.3, backed by JSON Schema |
| `rule-engine-observability` | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks |

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
by `DslEquivalence`, the way the three matchers are held to identical firing sequences by
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

Registering fact schemas (`CompilerOptions.factSchemas`) sharpens it further: a literal the field's
declared type could never hold becomes a compile error rather than a rule that silently never
matches, a malformed payload is rejected at `insert` rather than quietly matching nothing, and
§2.6.1's `ne`-on-an-optional-path trap becomes a named warning. All opt-in; the engine needs no
schemas at all.

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

`RuleBatches` is that loop with the failure question answered. §5.2 insists you decide what a partial
batch result means before shipping, so every batch's outcome comes back — successes and failures
both — instead of the first exception throwing away its siblings' work:

```java
List<BatchOutcome<FireResult>> outcomes = RuleBatches.run(rules, batches, (session, batch) -> {
    batch.forEach(f -> session.insert(f.type(), f.payload()));
    return session.fireAllRules();
});
```

Measured on an 8-core machine, 256 such batches scale 3.30x on 4 threads and 5.79x on 8 — against a
shared-nothing control that manages 3.76x and 7.16x on the same box, so the engine reaches 81% of
what that machine can do at 8 threads. Sharing one `CompiledRuleSet` across every thread costs
nothing measurable at any thread count, which is the claim §5.5 stakes the design on.

### Swapping rules while running

`RuleSetHolder` is §5.6's hot reload: a volatile reference, and two contracts. Compile before you
publish, so a broken rule file leaves the previous version serving; and a swap affects new sessions
only — anything already running finishes against the rules it started with.

```java
RuleSetHolder rules = new RuleSetHolder(RuleFiles.compile(source));
...
rules.publish(RuleFiles.compile(newSource));   // compile first: a failure here changes nothing
```

There is no safe in-place swap for a session that is *already* running — its memories, refraction
state and agenda are all shaped by the old network's node ids. For long-lived sessions the answer is
`SessionDrain.restart`, which exports the session's facts, closes it, and replays them into a session
on the new rules. Facts a rule derived are deliberately not replayed: the new session re-derives them
when it fires, and exporting them would double-count every one.

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

**It sees negations, and names the fact that is in the way.** A `NOT_EXISTS` pattern is not in the
rule's pattern list — deliberately, so that nothing downstream has to know to skip it — so for a
while the explainer reported a rule suppressed by an absence as having eligible matches, which is
the opposite of the truth. It now evaluates the negations against each complete match, before the
§6.4 conditions, using the same `Negations` predicate the agenda decides with rather than a second
copy of the semantics:

```java
// rule unpaid-shipped-order: 1 combination(s) matched every pattern and join, but the rule asserts that no Payment matches 'p' and fact #2 does (§1's NOT_EXISTS)
//   o: Order — 1 considered, 1 matched
//   not p: Payment — 1 present, suppressed 1 match(es) — e.g. fact #2
```

`Explanation.negations()` carries one entry per negated pattern whether or not it suppressed
anything, and the numbers run the other way from a pattern's: how many facts of the type are
*present*, and how many matches their presence removed.

It still cannot *detect* §4.4's hazard — over a type the session evicts, an evicted fact and an
absent one are indistinguishable, so this re-asks the same question of the same working memory and
is fooled identically. What it does is put the count in front of you, which is the part that changes
what you do next:

```
// rule unpaid-shipped-order: 1 match(es); all eligible, none has fired yet — WARNING: 2 Payment fact(s) evicted this session, and this rule asserts their absence: an evicted fact and an absent one are indistinguishable to a negation, so this match may be a false conclusion (§4.4)
```

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
