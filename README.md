# rule-engine

[![build](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml/badge.svg)](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=Maven%20Central)](https://central.sonatype.com/namespace/com.codeheadsystems)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

An in-process, forward-chaining rule engine for the JVM. It decides things that depend on **more than
one fact** — "an order over $10,000 from a high-risk customer", "an order whose every line item is in
stock", "a customer with three unpaid orders" — for services where the rules change more often than
the code deploys, or where somebody has to justify a decision six months after it was made.

Rules are written in YAML or JSON, validated against a published schema, and compiled once at
startup. Facts are your own JSON. Firing a rule set is a pure function of the facts you put in — the
same facts in the same order produce the same firings, on any host, in any year, because the engine
owns no clock — which is what makes a decision reproducible long after it was made.

## Install

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

Requires Java 25

## A rule, and running it

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

Two keys worth naming: `salience` is an author-assigned priority, used when several rules are
eligible at once, and `noLoop` stops a rule re-triggering itself when its own actions write to a fact
it matched.

Compiled once, at startup, and shared by everything:

```java
// RuleSource.of(Path) reads the file, so it declares IOException; the text-taking
// factories (RuleSource.yaml / RuleSource.json) do not.
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
```

Then a session per unit of work — a request, a message, a batch:

```java
ObjectMapper json = new ObjectMapper();

try (RuleSession session = rules.newSession()) {
    session.insert("Order",    json.readTree("""
        {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
    session.insert("Customer", json.readTree("""
        {"id": 7, "riskTier": "HIGH"}"""));

    FireResult result = session.fireAllRules();
    // result.fired()    -> high-value-order-review, once
    // result.emitted()  -> order.flagged, stamped with the session id and the rule-set version
    // result.why()      -> DRAINED
}
```

**Emitted events come back as the return value of the fire call.** Nothing performs I/O by default —
the default sink discards, and `FireResult.emitted()` is sourced from the firing records — so a rule
set is testable with no mocking at all.

The rule file above is a compiled fixture in `DocExamplesTest`, and what the session does with it is
`SmokeTest.readmeExample`. If the two disagree, the README is wrong. The same rule can be built in
Java rather than parsed from a file, and the two produce the *identical* rule set down to the version
hash; [`docs/embedding.md`](docs/embedding.md#building-rules-in-java) has that form.

### Computing a value

A rule is not limited to setting constants. One optional module, [`-cel`](rule-engine-cel), adds an
expression escape hatch. Used in an action it is the cheaper of its two positions — it runs once per
firing, where the same expression used to filter a pattern runs once per candidate:

```yaml
apiVersion: rules.v1
rules:
  - id: price-order
    noLoop: true
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "PENDING" }
    then:
      - action: setField
        target: o
        field: total
        value: { $expr: "double(o.subtotal) + double(o.tax)" }
```

Use it for the two things operator maps genuinely cannot say: arithmetic across fields, and nested
`OR`/`NOT`. It is deliberately a little inconvenient to reach — an extra module and an explicit
registration — because it gives up the indexed fast path. Keep what can be indexed in `where`.

The `double(...)` calls are not decoration: comparisons work across integers and decimals, but CEL has
no `int + double` overload, so adding a whole number to a decimal throws without them.
Money arithmetic is better done before the fact reaches the engine.
[`docs/dsl-guide.md`](docs/dsl-guide.md#when-operator-maps-arent-enough) has the things that will
bite you here, including how CEL treats an absent field and why money arithmetic belongs at
ingestion.

## How it works

**Facts are JSON.** A fact is a type name plus a `JsonNode` payload, not an object that happens to
serialise. Field paths are dotted and map to RFC 6901 JSON Pointers, so `customer.tier` reads
`/customer/tier`. Facts come from wherever you get them — an event, a request body, reference data —
and you insert them; the engine persists nothing. Fact identity is the handle it hands back, not
anything in the payload, so inserting the same customer twice makes two facts.

**A rule is `when` (patterns) and `then` (actions).** Patterns are AND-ed, and so is everything
inside a pattern. There is no `or` — write two rules, or use `in`. Any constraint can compare two
facts by naming the other's field with `{ $ref: alias.field }`, and both ends of an equality or
ordering join are indexed — anything else is re-evaluated per fire cycle, and the compiler report
names it.

**Four kinds of pattern.** An ordinary one binds a fact; `notExists` asserts an absence; `forAll`
asserts that everything the join selects meets a requirement; `accumulate` folds a scope into a
number. The last three bind no fact, and the last binds a *value*, so nothing may join to it.
`after` and `before` relate two facts in time within a required bound — and since the engine reads no
clock, every time it uses comes off a fact you inserted.

**Five actions and no more** — `setField`, `insertFact`, `retractFact`, `emit`, `callFunction` —
because a closed vocabulary stays diffable and reviewable by someone who is not a programmer.
`insertFact` with `logical: true` makes a **conclusion**, withdrawn when the match that made it stops
holding. Derived facts feed back in and other rules match them, so a rule set is a small program
rather than a flat list of filters.

**Two tiers, and the split is the whole design.** A `CompiledRuleSet` is immutable, thread-safe and
shared by everything. A `RuleSession` is single-writer, cheap to allocate (248ns), and never shared
across threads; it holds all the state. Compile once, session per unit of work, one virtual thread
per session. `halt()` is the only method legal to call from another thread.

**A fire cycle** matches, resolves the conflict set — the activations currently eligible — and fires
one, repeating until nothing is eligible. Order is salience, then recency, then a total tiebreak, and
**refraction** stops the same match firing twice on the same facts. Right-hand sides are staged and
then committed as a unit, so no action sees a half-applied world.

## Run the worked example

[`rule-engine-example`](rule-engine-example/) is a complete small application — one rule file, one
feed of ten events, and four deployment shapes side by side:

```bash
./gradlew :rule-engine-example:run
```

It is the fastest way to see what a reference page cannot show you: what belongs in the ingestion
path rather than in a rule, how session scope decides what a rule can possibly see, what a long-lived
session has to do to stay bounded, and what to assert about a rule set in CI. Read
[its README](rule-engine-example/README.md) alongside the DSL guide.

## Three things that will bite you

All deliberate, and all three shape how you model your data on day one.

- **Absent and null are different values.** `{ eq: null }` matches an explicit JSON null and never an
  absent field. Use `hasField: false` for "the field isn't there".
- **`ne` is true for an absent field**, because `ne` is defined as `!eq`. `status != "CLOSED"`
  matches an order with no `status` at all. Pair it with `hasField: true` when you mean "present and
  not closed". The same applies to `notIn`.
- **Flatten collections at ingestion.** JSON Pointer has no wildcard, so `items.*.qty` does not exist
  and is not coming. An `Order` with an `items[]` array becomes one `Order` fact plus N `LineItem`
  facts carrying `orderId`, joined normally. This is not a workaround — it is how you get indexing
  and incremental matching over collection elements at all — and retrofitting it means rewriting
  every rule that touches a collection.

[`docs/dsl-guide.md`](docs/dsl-guide.md) opens with the fuller version of these, and the DSL
reference has the rest: vacuous `forAll` over an empty scope, what `min` of nothing means, and why
nothing may name a quantified pattern's alias.

One more, because it is the sharpest hazard here and it bites at operations time rather than
authoring time: **never evict a fact type your rules negate, quantify over, fold, or conclude.** An
evicted fact and an absent one are indistinguishable, so a cap on a negated type stops costing a
firing and starts asserting a false conclusion.
[`docs/embedding.md`](docs/embedding.md#long-lived-sessions-and-eviction) works through all four
shapes.

## When it doesn't fire

A firing leaves a record; a *non*-firing leaves nothing to look up, because the fast path is
optimised precisely not to record what it eliminated. `MatchExplainer` re-evaluates the constraints
one at a time and names the one that emptied the set:

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted — already fired at recency 4
//   o: Order — 1 considered, 1 matched
//   c: Customer — 1 considered, 1 matched
```

Four answers cover nearly every real case, and the last two are the ones nobody guesses:

1. **No fact of some type exists** — usually a fact type spelled differently from how the host
   inserts it. `declaredFactTypes` surfaces it as `report().unreachableRules()`, which you assert is
   empty in CI.
2. **N considered, all failed a named constraint** — reported with the value that failed it.
3. **The session could not see the facts.** A rule spanning two orders cannot fire in a session
   holding one, however it is written. Check scope before you check the rule.
4. **The rule already fired on those exact facts.** That is refraction, and it is what stops rules
   firing forever.

Before any of that, check the three modelling traps above. A rule that "matches nothing" is very
often an `eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often
a bare `ne`.

**At 3am the session is closed and the facts are gone**, so the postmortem path — capture with a
listener or `exportFacts()`, replay, then explain — is worth building before you need it.
[`docs/embedding.md`](docs/embedding.md#diagnosing-production) has it.

## Rule sets are source code

`CompiledRuleSet.report()` is data rather than a printed string, so a build can assert on it:

```java
CompilerReport report = rules.report();
// rule set sha256:4073bf55c15edf78
//   2 rules, 3 distinct alpha nodes from 4 tests (sharing 1.33x), 2 patterns, 1 join edges
//   unindexed: fraud-check: o.region (NOT_IN)
```

Two options turn a runtime surprise into a build-time one, and both are worth setting from the first
day:

```java
CompilerOptions.builder()
    .declaredFunctions(Set.of("notifySlack"))       // a typo becomes a compile error
    .declaredFactTypes(Set.of("Order", "Customer")) // fills report().unreachableRules() -- assert it
    .build();
```

An *alpha node* is one single-fact test, shared across every rule that expresses it — the sharing
figure is how much of that work the rule set has in common. A *join edge* is a constraint relating
two facts. Neither is something you configure; they are how the report describes what it built.

[`docs/dsl-guide.md`](docs/dsl-guide.md#checking-your-rules-in-ci) has the whole gate, including what
to do about unindexed constraints and what registering fact schemas buys you.
[`ExampleRulesTest`](rule-engine-example/src/test/java/com/codeheadsystems/rules/example/ExampleRulesTest.java)
is a copyable version.

## Running it in production

Compile once and share the `CompiledRuleSet`; create a cheap single-writer `RuleSession` per unit of
work, one virtual thread each. **[`docs/embedding.md`](docs/embedding.md) is the host-side manual**
and is where the operational surface lives, but four answers belong here because people ask them
before they open it:

- **Rules swap under load.** `RuleSetHolder` is one volatile field and no locks, and `publish` takes
  an already-*compiled* rule set — so a rule file with a typo in it fails at compile and the engine
  stays in service on the rules it has. A swap affects new sessions only.
- **Running many at once is the default shape.** `RuleBatches` gives one virtual thread and one
  session per batch, returning a per-batch outcome that carries either a result or a failure.
- **You do not have to choose a matcher.** The default is the indexed network and it is the right
  answer for per-request work; the streaming matcher is worth reading about only for long-lived
  sessions.
- **A decision is fast enough for a request path.** Allocating a session, inserting twenty facts and
  firing to completion measures about 15µs on the default matcher; two hundred facts is about 455µs.
  Neither is a latency *guarantee* — work is bounded, wall time is not — so bring a watchdog if you
  have a hard budget. [`docs/benchmarks.md`](docs/benchmarks.md) has the method and the error bars.

Registering a `HostFunction`, every `SessionOptions` setting, the work limits, eviction, tracing and
Flight Recorder, and reconstructing a decision after the session is closed are all in the manual.

## Modules

| Artifact | Version | Contents |
|---|---|---|
| `rule-engine-core` | [![Maven Central: rule-engine-core](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-core?label=rule-engine-core)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-core) | Fact model, working memory, all three matchers, agenda, refraction, RHS execution, sessions, the concurrency helpers |
| `rule-engine-compiler` | [![Maven Central: rule-engine-compiler](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-compiler?label=rule-engine-compiler)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-compiler) | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, network build, version hash, `CompilerReport` |
| `rule-engine-dsl` | [![Maven Central: rule-engine-dsl](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=rule-engine-dsl)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-dsl) | JSON *and* YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema and located diagnostics. **Start here** — it brings `-compiler` and `-core` with it |
| `rule-engine-cel` | [![Maven Central: rule-engine-cel](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-cel?label=rule-engine-cel)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-cel) | Optional. The expression escape hatch, backed by dev.cel |
| `rule-engine-schema` | [![Maven Central: rule-engine-schema](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-schema?label=rule-engine-schema)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-schema) | Optional. Fact schemas, backed by JSON Schema |
| `rule-engine-observability` | [![Maven Central: rule-engine-observability](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-observability?label=rule-engine-observability)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-observability) | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | [![Maven Central: rule-engine-testkit](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-testkit?label=rule-engine-testkit)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-testkit) | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks. **Not optional** — a consumer testing their own rules wants exactly these |
| `rule-engine-example` | *not published* | The worked application. An artifact is a promise to keep something compiling for whoever depends on it, and nobody should depend on the example |

Every badge reads the live version from Maven Central. All seven move together — a release tags one
version and publishes all of them in a single deployment — so a badge showing a different number from
its neighbours means a deployment went wrong rather than that the modules drifted.

## Documentation

| | |
|---|---|
| [`rule-engine-example/README.md`](rule-engine-example/README.md) | **start here** — a complete application you can run |
| [`docs/dsl-guide.md`](docs/dsl-guide.md) | writing a rule, from a blank file |
| [`docs/dsl-reference.md`](docs/dsl-reference.md) | every operator, every action, every diagnostic code |
| [`docs/embedding.md`](docs/embedding.md) | the host side: sessions, options, limits, concurrency, operations, diagnosing production |
| [`docs/choosing-this-engine.md`](docs/choosing-this-engine.md) | where it fits, where it does not, the comparisons, and getting out |
| [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md) | the specification, and the source of truth |
| [`docs/benchmarks.md`](docs/benchmarks.md) | what is measured, on what, and what the numbers do not show |

## Building

Requires a JDK 25 toolchain; Gradle resolves one via the foojay plugin if it is not installed.

```bash
./gradlew build        # compile, test, and the strict-mode test run
./gradlew test         # the suite
./gradlew strictTest   # the same suite with -Drules.strict=true, which turns on the contract
                       # checks too expensive for production. CI runs both; never run it in prod
./gradlew javadoc      # warnings fail the build; several contracts live only in Javadoc
./gradlew testCodeCoverageReport   # aggregated across modules, currently 93.5% line

./gradlew :rule-engine-example:run # the worked example
```

Releasing is [`RELEASING.md`](RELEASING.md): tag `vX.Y.Z` and the workflow does the rest.

## License

Copyright 2026 Ned Wolpert.

Licensed under the Apache License, Version 2.0 — see [`LICENSE`](LICENSE). 