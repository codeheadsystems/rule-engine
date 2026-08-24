# rule-engine

[![Maven Central](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=Maven%20Central)](https://central.sonatype.com/namespace/com.codeheadsystems)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

An in-process, forward-chaining production rule engine for the JVM: JSON-native facts, an immutable
compiled rule set shared across everything, and cheap single-writer sessions that make
high-concurrency evaluation the default rather than an afterthought.

Rules are written in YAML or JSON, validated against a published schema, and compiled once at
startup. Firing a rule set is a pure function of the facts you put in — the same facts in the same
order produce the same firings, on any host, in any year — which is what makes a decision
reproducible months after it was made.

The design is specified in full in [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md). This
README is the guide to what is here and how to use it.

## Contents

- [Start here](#start-here)
- [The worked example](#the-worked-example)
- [How it fits together](#how-it-fits-together)
- [What a rule can say](#what-a-rule-can-say)
- [What a rule can do](#what-a-rule-can-do)
- [Things that surprise people](#things-that-surprise-people)
- [What it deliberately does not do](#what-it-deliberately-does-not-do)
- [Choosing a matcher](#choosing-a-matcher)
- [Concurrency and long-lived sessions](#concurrency-and-long-lived-sessions)
- [Why didn't my rule fire?](#why-didnt-my-rule-fire)
- [What the compiler knows](#what-the-compiler-knows)
- [If a rule action throws](#if-a-rule-action-throws)
- [Modules](#modules)
- [Documentation](#documentation)
- [Building](#building)

## Start here

On Maven Central under `com.codeheadsystems`. One line is usually the whole dependency —
`rule-engine-dsl` brings the compiler and the core with it. The badge above is the version that is
actually there; the snippets below are bumped by hand, so trust the badge if they disagree:

```gradle
implementation("com.codeheadsystems:rule-engine-dsl:1.0.0")
testImplementation("com.codeheadsystems:rule-engine-testkit:1.0.0")
```

```xml
<dependency>
  <groupId>com.codeheadsystems</groupId>
  <artifactId>rule-engine-dsl</artifactId>
  <version>1.0.0</version>
</dependency>
```

A rule file:

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

Compiled once, at startup, and shared by everything:

```java
// RuleSource.of(Path) reads the file, so it declares IOException; the text-taking
// factories (RuleSource.yaml / RuleSource.json) do not.
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
```

Then a session per unit of work — a request, a message, a batch:

```java
try (RuleSession session = rules.newSession()) {
    session.insert("Order",    json.readTree("""
        {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
    session.insert("Customer", json.readTree("""
        {"id": 7, "riskTier": "HIGH"}"""));

    FireResult result = session.fireAllRules();
    // result.emitted() -> order.flagged, stamped with the session id and the rule-set version
}
```

**Emitted events come back as the return value of the fire call.** Nothing performs I/O by default —
the default sink discards, and `FireResult.emitted()` is sourced from the firing records — so a rule
set is testable with no mocking at all.

The same rule, built in Java rather than parsed from a file — the constraint AST written by hand,
which is what the DSL front end produces:

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

This is `SmokeTest.readmeExample`. If the two disagree, the README is wrong — and the YAML above is
held to producing the *identical* rule set, down to the version hash, by `DslEquivalence`.

JSON and YAML are one language here: both parse into the same object model and compile to the same
rule set, and the entire difference is which Jackson factory reads the text.

## The worked example

[`rule-engine-example`](rule-engine-example/) is a complete small application — one rule file, one
feed of ten events, and the deployment shapes side by side. Run it:

```bash
./gradlew :rule-engine-example:run
```

It is the fastest way to see the parts of this engine that a reference page cannot show you: what
belongs in the ingestion path rather than in a rule, how session scope decides what a rule can
possibly see, what a long-lived session has to do to stay bounded, and what to assert about a rule
set in CI. Read [its README](rule-engine-example/README.md) alongside the DSL guide.

## How it fits together

**Facts are JSON.** A fact is a type name plus a `JsonNode` payload (§2.2), not an object that
happens to serialise. Field paths are dotted and map to RFC 6901 JSON Pointers, so `customer.tier`
reads `/customer/tier`. The engine copies payloads on the way in and hands out copies where it
matters; nothing downstream can serve a stale one, because tuples bind handles and dereference at
read time.

**A rule is `when` (patterns) and `then` (actions).** Patterns are AND-ed, and so is everything
inside a pattern. There is no `or` — write two rules, or use `in`.

**Two tiers, and the split is the whole design.** A `CompiledRuleSet` is immutable, thread-safe and
shared by everything — the network, the plans, the version hash. A `RuleSession` is single-writer,
cheap to allocate, and never shared across threads; it holds all the state. Compile once, session
per unit of work, one virtual thread per session. `halt()` is the only method legal to call from
another thread.

**A fire cycle** matches, resolves the conflict set, and fires one activation, repeating until
nothing is eligible. Conflict resolution is salience, then recency, then a total tiebreak (§4.2), and
**refraction** stops the same match firing twice on the same facts. Right-hand sides are staged and
then committed as a unit, so a rule can insert, update, retract and emit without any action seeing a
half-applied world.

**Derived facts feed back in.** A rule's `insertFact` is an ordinary fact that other rules match, so
a rule set is a small program rather than a flat list of filters.

## What a rule can say

**Constraints and joins.** The full comparison surface of §2.6.1 — `eq`, `ne`, `gt`, `gte`, `lt`,
`lte`, `between`, `in`, `notIn`, `matches`, `hasField`, `isNull` — and any of them can compare two
facts by naming the other's field with `{ $ref: alias.field }`. Both ends of every join edge are
indexed, and the binding order is chosen fresh on each fire cycle: smallest memory first, connected
before disconnected. **The order you write patterns in is for readability, not for speed.**

**`notExists`** asserts an absence. The pattern binds nothing and joins nothing into the tuple, so it
is a question asked of a complete match.

**`forAll`** asserts that everything in scope meets a requirement — and *the join picks the scope*.
`forAll li: LineItem (orderId = o.id, inStock)` is "every line item **of this order** is in stock".
It earns its place on multi-constraint requirements: one constraint is already writable as a negation
of its complement, but the complement of "in stock **and** qty above zero" is a disjunction, and no
pattern here expresses one.

**`accumulate`** folds a scope into one value — `sum`, `count`, `min`, `max`, `average`, with an
optional `having` on the answer. The alias binds a **number, not a fact**, so nothing may join to it,
and the fold is computed from working memory at every read rather than stored. That is what keeps it
correct: a stored aggregate goes stale the instant any fact in its scope moves.

**`after` / `before`** relate two facts by a time field within a required bound —
`paidAt: { after: { $ref: o.placedAt, within: 86400000 } }`. **The engine reads no clock.** Every
time it uses comes off a fact you inserted, which is what makes a replay reproduce the original
decision. The bound is in the time field's own units, because only you know what they are, and it is
required: an unbounded ordering is `gt` against the same `$ref` and always was.

**`condition:`** is §6.4's escape hatch, for the two things an operator map genuinely cannot say:
nested `OR`/`NOT`, and arithmetic across fields. It is a CEL expression evaluated after the indexed
constraints, once per surviving candidate, and it needs the `rule-engine-cel` module and an explicit
registration. Keep your indexable constraints in `where` — what the index removes is work the
expression never does.

## What a rule can do

Five actions, and no more (§4.6). A closed vocabulary is diffable, reviewable, and has bounded cost;
config that is really code is much of what makes rule engines feel heavy.

| Action | What it does |
|---|---|
| `setField` | change a field on a matched fact; routes through `update`, so it is gated on the tested-path diff |
| `insertFact` | derive a new fact. `logical: true` makes it a *conclusion* — see below |
| `retractFact` | remove a matched fact |
| `emit` | produce an event, delivered to the session's sink and returned on `FireResult` |
| `callFunction` | the escape hatch: dispatch by name to a registered Java function |

**`logical: true` is truth maintenance.** A logical insert is a conclusion held up by the match that
made it, withdrawn when that match stops holding — the `Payment` arrives, a `forAll` counterexample
turns up, a bound fact is retracted or updated out of matching. Withdrawal cascades and is
reversible. Three things to know: it happens at a cycle boundary rather than instantly, because
right-hand sides commit as a unit; there is exactly one justification per conclusion, so two matches
concluding the same thing make two facts; and concluding the very fact your own `notExists` is about
is a livelock.

**An expression can also stand in for a value.** `value: { $expr: "o.total > 500 ? 'HIGH' : 'LOW'" }`
resolves once per firing, wherever an action takes a value — a deliberate extension of §11.3's closed
verb set, argued in the DSL reference. It is the better answer to "I need to compute something" than
`callFunction`, and it needs the same `rule-engine-cel` registration a `condition:` does.

**`callFunction` is the wrong default.** It runs at commit, outside the staging that makes everything
else atomic, and it cannot be withdrawn. Prefer `emit` and act on the result after the fire call
returns.

## Things that surprise people

All deliberate, and the first three shape how you model your data on day one.

- **Absent and null are different values.** `{ eq: null }` matches an explicit JSON null and never an
  absent field. Use `hasField: false` for "the field isn't there".
- **`ne` is true for an absent field**, because `ne` is defined as `!eq`. `status != "CLOSED"`
  matches an order with no `status` at all. Pair it with `hasField: true` when you mean "present and
  not closed". The same applies to `notIn`.
- **Flatten collections at ingestion.** JSON Pointer has no wildcard, so `items.*.qty` does not exist
  and is not coming. An `Order` with an `items[]` array becomes one `Order` fact plus N `LineItem`
  facts carrying `orderId`, joined normally. This is not a workaround — it is how you get indexing
  and incremental matching over collection elements at all, and retrofitting it means rewriting every
  rule that touches a collection.
- **Distinct aliases in one rule bind distinct facts.** The compiler inserts an implicit inequality
  between same-type aliases.
- **A `forAll` is vacuously true over an empty scope.** An order with no line items is "ready to
  ship". Pair it with a positive pattern of the same type to mean "there are some, and all of them" —
  and see [the example](rule-engine-example/README.md#there-are-some-and-all-of-them) for why that
  companion is usually best written as an `accumulate count`.
- **A quantified pattern binds nothing, and nothing may name its alias** — not a `$ref`, not an
  action, not a §6.4 expression. All of those are compile errors that name which quantifier it is.
- **An empty scope is not zero for everything.** `count` and `sum` of nothing are `0`; `min`, `max`
  and `average` of nothing are *absent*, so a `having` on them does not hold. The average of no
  orders is not zero.
- **A `condition:` makes every field of the facts it reads a tested path**, so any update to such a
  fact un-refracts the rule — including an update to a field nothing reads. A rule whose right-hand
  side writes to its own fact therefore needs `noLoop`.

### The one warning that comes in four shapes

§4.4's fact eviction bounds a long-lived session by dropping facts, and **an evicted fact is
indistinguishable from one that was never there.** Each of the quantifiers collides with that
differently: evicting a **negated** type manufactures a false conclusion, evicting a **quantified**
type deletes a requirement rather than weakening it, evicting an **accumulated** type quietly changes
a number, and evicting a **concluded** type loses a belief nothing can redraw. `MatchExplainer` warns
for all four and can detect none of them, because it re-asks the same question of the same working
memory and is fooled identically.

**Never cap a type your rules negate, quantify over, fold, or conclude.** Cap the types you bind — or
let the application retract what it knows is finished, which is what
[`StreamingDemo`](rule-engine-example/src/main/java/com/codeheadsystems/rules/example/StreamingDemo.java)
does and why.

## What it deliberately does not do

§9.1 has the full accounting; these are the ones people ask for.

| not built | why not |
|---|---|
| `collect` | answers with a collection, so it has no meaningful `having`, and binding a list needs a way to take one apart that §2.5 does not have |
| sliding windows, "nothing for 24h" | both need something to notice time passing with *no fact arriving* — the one input an engine that acts on fact movement never receives. Needs either a clock, which would end the determinism contract, or a caller-driven session time, which would not but is a contract of its own |
| `or` inside a `where` | write two rules, use `in`, or reach for §6.4's `condition:` |
| backward chaining | §1's forward-only decision stands |
| distributed evaluation | §5's immutability split makes it *feasible* and no more. The partitioning, the wire protocol and cross-node routing are an architecture, not a slice |

## Choosing a matcher

Three matchers, held to producing **identical firing sequences**. Everything that decides *which*
activation fires lives in one shared base — dirty tracking, refraction, negation, the §6.4
post-filter, conflict resolution — so the three can only differ in how matches are *found*.

| `SessionOptions.matching(...)` | Use it when |
|---|---|
| `NETWORK` (default) | a session is created, filled, fired and closed. Joins are recomputed per fire cycle from indexed pattern memories |
| `RETE` | a session is long-lived and fires thousands of times. Joins are materialised as facts arrive, and the conflict set is pushed and pulled rather than rebuilt |
| `NAIVE` | never in production. No network, no indexes, `O(rules × facts^arity)` — the **correctness oracle** every other matcher is differentially tested against, and shipped so that you can test your own rules against it |

`MatcherEquivalence` and `ShuffleHarness` in `rule-engine-testkit` are how you point that oracle at
your own rule set; see
[`MatcherAgreementTest`](rule-engine-example/src/test/java/com/codeheadsystems/rules/example/MatcherAgreementTest.java).

The streaming matcher is measured, not asserted — see [`docs/benchmarks.md`](docs/benchmarks.md) for
the curves, including the fire cycle that stopped growing with the working set.

## Concurrency and long-lived sessions

A `CompiledRuleSet` is immutable and freely shareable; a `RuleSession` holds all the mutable state
and is never shared. So the natural unit of concurrency is one virtual thread per session (§5.2):

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

**For a stream rather than a batch, `SessionActor`.** A session is single-writer and "fire until told
to stop" is a blocking loop, so inserting from a producer thread while that loop runs is a data race.
One worker owns the session, many producers feed a bounded inbox, and a burst of inserts costs one
fire cycle rather than one each.

**A long-lived session has to be bounded**, because every structure it grows — working memory, the
node memories and their indexes, the refraction memory, the beta memory — is keyed on handles.
`SessionOptions.eviction(...)` takes a policy (a total cap, or a cap per fact type so a bounded
stream can flow past unbounded reference data) and evicting a fact runs the **full retract path**, so
one mechanism bounds all of them. Read the four-shaped warning above before configuring one.

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

It deliberately does not use the matching network. The network is optimised to *not* compute what you
want here: an index skips non-candidates without recording why, and a pattern memory holds the
survivors and has forgotten the casualties. So it re-evaluates constraints one at a time against
working memory — slower by every measure, and the only way to know which constraint did the
eliminating.

**It sees quantifiers, and names the fact that is in the way** — the `Payment` that defeats a
`notExists`, or the `LineItem` that fails a `forAll`:

```java
// rule unpaid-shipped-order: 1 combination(s) matched every pattern and join, but the rule asserts that no Payment matches 'p' and fact #2 does (§1's NOT_EXISTS)
//   o: Order — 1 considered, 1 matched
//   not p: Payment — 1 present, suppressed 1 match(es) — e.g. fact #2
```

`Explanation.negations()` carries one entry per negated pattern whether or not it suppressed
anything, and the numbers run the other way from a pattern's: how many facts of the type are
*present*, and how many matches their presence removed.

It cannot *detect* the eviction hazard, for the reason given above — but it puts the count in front
of you, which is the part that changes what you do next:

```
// rule unpaid-shipped-order: 1 match(es); all eligible, none has fired yet — WARNING: 2 Payment fact(s) evicted this session, and this rule asserts their absence: an evicted fact and an absent one are indistinguishable to a negation, so this match may be a false conclusion (§4.4)
```

Pin the facts you are actually asking about when you have them, which is the sharper question:

```java
why = explainer.explain("high-value-order-review", Map.of("o", orderHandle, "c", customerHandle));
```

Three verdicts cover most real cases: no fact of some type exists — usually a fact type spelled
differently from how the host inserts it; N considered and all failed a named constraint, *with the
value that failed it*; and the one nobody guesses — **the rule already fired on those exact facts**.

Before any of that, check the three modelling traps above. A rule that "matches nothing" is very
often an `eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often
a bare `ne`. And check the session's scope: a rule can only see the facts the session holds.

## What the compiler knows

`CompiledRuleSet.report()` is §7.4's answer to "no unknown unindexed access" — data, not a printed
string, so a build can assert on it:

```java
CompilerReport report = rules.report();
// rule set sha256:4073bf55c15edf78
//   2 rules, 3 distinct alpha nodes from 4 tests (sharing 1.33x), 2 patterns, 1 join edges
//   unindexed: fraud-check: o.region (NOT_IN)
```

**A rule set is source code; treat it like source code.** Two options turn a runtime surprise into a
compile error, and both are worth setting from the first day:

```java
CompilerOptions.builder()
    .declaredFunctions(Set.of("notifySlack"))       // a typo becomes a compile error
    .declaredFactTypes(Set.of("Order", "Customer")) // finds rules nothing can activate
    .build();
```

Read `unindexed` by *reason*, not by count. A `RESIDUAL_JOIN_CONDITION` is a join that gave up the
index and is re-evaluated every fire cycle; an `NE` on a single-fact constraint runs once per insert
and never again. The report lists both because §7.4 asks for every unindexed constraint, and
distinguishes them because treating them alike sends people to optimise the wrong one.

Registering fact schemas (`CompilerOptions.factSchemas`) sharpens it further: a literal the field's
declared type could never hold becomes a compile error rather than a rule that silently never
matches, a malformed payload is rejected at `insert` rather than quietly matching nothing, and
§2.6.1's `ne`-on-an-optional-path trap becomes a named warning. All opt-in; the engine needs no
schemas at all.

[`ExampleRulesTest`](rule-engine-example/src/test/java/com/codeheadsystems/rules/example/ExampleRulesTest.java)
is a copyable version of the whole gate.

## If a rule action throws

The atomicity guarantee is **per-phase**, and the halves differ (§4.6). A staging-phase failure
applies nothing. A commit-phase failure — a `callFunction` handler, an `EventSink`, or a `setField`
whose path runs through a scalar — leaves the working-memory effects that already landed in place.
There is no compensating undo and there cannot be one: a sent message cannot be un-sent.

`FireRecord` is how that partial state is discoverable. It carries what actually committed, which
action threw, and which actions never ran.

**Under the default `RETHROW` policy, register a listener or you will not get that record.** The spec
requires the original exception to propagate to the caller, so — unlike a limit breach — it cannot
carry a partial result. Listeners are the trace mechanism (§7.1), and `onAfterFire` is published for
the failed firing *before* the rethrow. With no listener registered, the record of the firing, and of
every firing before it in that call, is gone with the stack unwind. Use `ABORT_SESSION` instead if
you want the partial `FireResult` returned rather than thrown.

A listener must not throw and must not call back into the session; neither is enforced.

## Modules

| Module | Contents |
|---|---|
| `rule-engine-core` | Fact model, working memory, all three matchers, agenda, refraction, RHS execution, sessions, the concurrency helpers |
| `rule-engine-compiler` | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, network build, version hash, `CompilerReport` |
| `rule-engine-dsl` | JSON *and* YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema and located diagnostics |
| `rule-engine-cel` | Optional. The §6.4 expression escape hatch, backed by dev.cel |
| `rule-engine-schema` | Optional. The `FactSchemas` of §2.3, backed by JSON Schema |
| `rule-engine-observability` | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks. **Not optional** — a consumer testing their own rules wants exactly these |
| `rule-engine-example` | The worked application. Not a library |

Depend on `rule-engine-dsl` if you write your rules in YAML: it brings `-compiler` and `-core` with
it, so that is the whole dependency. The two optional modules exist so that nobody pays for JSON
Schema or for CEL's protobuf/guava/antlr footprint without asking.

All seven are published to Maven Central at the same version. `rule-engine-example` is not, and that
is deliberate — an artifact is a promise to keep something compiling for whoever depends on it, and
the example exists to be read and run here. [`RELEASING.md`](RELEASING.md) is how a version ships.

## Documentation

| | |
|---|---|
| [`rule-engine-example/README.md`](rule-engine-example/README.md) | **start here** — a complete application you can run |
| [`docs/dsl-guide.md`](docs/dsl-guide.md) | writing a rule, from a blank file. Opens with the three things that surprise everybody |
| [`docs/dsl-reference.md`](docs/dsl-reference.md) | every operator, every action, every diagnostic code |
| [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md) | the specification. The source of truth, including §9's roadmap and what is deliberately not built |
| [`docs/benchmarks.md`](docs/benchmarks.md) | what is measured, on what, and what the numbers do not show |

Every rule file printed in the DSL documents, in this README, and in the example's README is a
compiled fixture in a test. If a document and the engine disagree, the document is wrong.

## Building

Requires a JDK 25 toolchain; Gradle resolves one via the foojay plugin if it is not installed.

```bash
./gradlew build        # compile, test, and the strict-mode test run
./gradlew test         # the suite
./gradlew strictTest   # the same suite with -Drules.strict=true (spec §7.5)
./gradlew jmh          # benchmarks; see docs/benchmarks.md
./gradlew javadoc      # warnings fail the build; several contracts live only in Javadoc
./gradlew testCodeCoverageReport   # aggregated coverage across every module

./gradlew :rule-engine-example:run # the worked example
```

Coverage is aggregated deliberately: most of `-core` is exercised by the end-to-end tests in
`-testkit`, and a per-module report attributes none of that back. It reported `-core` at 26% while
the aggregate figure was 91%.

Releasing is [`RELEASING.md`](RELEASING.md): tag `vX.Y.Z` and the workflow does the rest.

**Strict mode** turns on every check that is too expensive for production but catches a contract
violation deterministically in test: engine-owned payloads are handed out as copies, an `update` that
aliases the stored payload is rejected, the conflict-resolution strategy is asserted to be a total
order consistent with equality, and an eviction policy is consulted twice and compared. §7.5 asks for
the full suite to run under it in CI and forbids it in production; `strictTest` is that run.

### Status

Everything described on this page is built and tested. The specification's §9 roadmap is complete
through Phase 6 except for the items in [what it deliberately does not
do](#what-it-deliberately-does-not-do), each of which §9.1 records with the reason it was not built —
including one, §11.2's differential propagation, that was profiled, found to matter on its own
benchmark, and left unbuilt because the correctness obligation it imposes is worse than the cost it
removes.

**1.0.0 is the first published version.** §0's "no mandatory build/packaging layer" is unchanged by
that — a `CompiledRuleSet` is still an object you get back from a compile call, not a deployment
artifact — but there are now coordinates to depend on rather than a repository to clone. What the
version number promises is the surface `ApiSurfaceTest` calls exported; see
[`RELEASING.md`](RELEASING.md) for what moves a major.

## License

Copyright 2026 Ned Wolpert.

Licensed under the Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Every published POM
declares the same, which is why that file has to exist rather than be implied: an artifact on Maven
Central asserting a licence the source does not grant is permanent and not fixable by a patch
release.
