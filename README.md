# rule-engine

[![build](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml/badge.svg)](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=Maven%20Central)](https://central.sonatype.com/namespace/com.codeheadsystems)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

An in-process, forward-chaining rule engine for the JVM. It decides things that depend on more than
one fact, such as "an order over $10,000 from a high-risk customer", "an order whose every line item
is in stock", or "a customer with three unpaid orders". It is for services where the rules change
more often than the code deploys, or where somebody has to justify a decision six months after it
was made.

Rules are written in YAML or JSON, validated against a published schema, and compiled once at
startup. Facts are the host application's own JSON. Firing a rule set is a pure function of the
facts put in: the same facts in the same order produce the same firings, on any machine, in any
year, because the engine owns no clock. That is what makes a decision reproducible long after it was
made.

## Installation

The modules are on Maven Central under `com.codeheadsystems`. A build imports `rule-engine-bom` and
names modules without a version; `rule-engine-dsl` brings the compiler and the core with it. The
badge above shows the version that is actually published. The snippets below are updated by hand, so
where the two disagree the badge is correct:

```gradle
implementation(platform("com.codeheadsystems:rule-engine-bom:1.1.0"))
implementation("com.codeheadsystems:rule-engine-dsl")
testImplementation("com.codeheadsystems:rule-engine-testkit")
```

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.codeheadsystems</groupId>
      <artifactId>rule-engine-bom</artifactId>
      <version>1.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.codeheadsystems</groupId>
    <artifactId>rule-engine-dsl</artifactId>
  </dependency>
</dependencies>
```

The engine requires Java 25 at runtime and puts Jackson 3 (`tools.jackson`) on the consumer's
classpath: a fact is a `JsonNode`, handed to the engine unconverted. The BOM also sets that Jackson
version, to the one the engine was built and tested against, so the host's own Jackson code uses the
same one. [`docs/embedding.md`](docs/embedding.md#platform-requirements) has the details.

## A rule and a session

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

`salience` is an author-assigned priority, used when several rules are eligible at once. `noLoop`
stops a rule re-triggering itself when its own actions write to a fact it matched.

The rule set is compiled once, at startup, and shared by everything:

```java
// RuleSource.of(Path) reads the file, so it declares IOException; the text-taking
// factories (RuleSource.yaml / RuleSource.json) do not.
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));
```

Each unit of work (a request, a message, a batch) gets its own session:

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

Emitted events come back as the return value of the fire call. Nothing performs I/O by default: the
default sink discards, and `FireResult.emitted()` is sourced from the firing records. A rule set is
therefore testable with no mocking at all.

`DocExamplesTest` compiles the rule file above, and `SmokeTest.readmeExample` runs the session
shown. The same rule can be built in Java rather than parsed from a file, and the two produce an
identical rule set, down to the version hash;
[`docs/embedding.md`](docs/embedding.md#building-rules-in-java) has that form.

### Computing a value

A rule is not limited to setting constants. One optional module,
[`rule-engine-cel`](rule-engine-cel), adds an expression escape hatch. In an action it is the
cheaper of its two positions: it runs once per firing, where the same expression filtering a pattern
runs once per candidate:

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

It covers the two things operator maps cannot express: arithmetic across fields, and nested
`OR`/`NOT`. It gives up the indexed fast path, which is why reaching it takes an extra module and an
explicit registration. Constraints that can be indexed belong in `where`.

The `double(...)` calls are required: comparisons work across integers and decimals, but CEL has no
`int + double` overload, so adding a whole number to a decimal throws without them. Money arithmetic
is better done before the fact reaches the engine.
[`docs/dsl-guide.md`](docs/dsl-guide.md#the-expression-escape-hatch) has the other pitfalls of
expressions, including how CEL treats an absent field and why money arithmetic belongs at
ingestion.

## How it works

Facts are JSON. A fact is a type name plus a `JsonNode` payload, not an object that happens to
serialise. Field paths are dotted and map to RFC 6901 JSON Pointers, so `customer.tier` reads
`/customer/tier`. Facts come from wherever the host gets them (an event, a request body, reference
data), and the host inserts them; the engine persists nothing. Fact identity is the handle the
engine hands back, not anything in the payload, so inserting the same customer twice makes two
facts.

A rule is `when` (patterns) and `then` (actions). Patterns are AND-ed, and so is everything inside a
pattern. There is no `or`; two rules, or an `in`, express the alternatives. Any constraint can
compare two facts by naming the other's field with `{ $ref: alias.field }`, and both ends of an
equality or ordering join are indexed. Anything else is re-evaluated per fire cycle, and the
compiler report names it.

There are four kinds of pattern. An ordinary one binds a fact; `notExists` asserts an absence;
`forAll` asserts that everything the join selects meets a requirement; `accumulate` folds a scope
into a number. The last three bind no fact, and `accumulate` binds a value, so nothing may join to
it. `after` and `before` relate two facts in time within a required bound. The engine reads no
clock, so every time it uses comes from an inserted fact.

There are five actions and no more: `setField`, `insertFact`, `retractFact`, `emit`, and
`callFunction`. A closed vocabulary stays diffable and reviewable by someone who is not a
programmer. `insertFact` with `logical: true` makes a conclusion, withdrawn when the match that made
it stops holding. Derived facts feed back in and other rules match them, so a rule set is a small
program rather than a flat list of filters.

The engine has two tiers, and the split between them is the whole design. A `CompiledRuleSet` is
immutable, thread-safe, and shared by everything. A `RuleSession` is single-writer, cheap to
allocate (248ns), and never shared across threads; it holds all the state. The pattern is one
compile, one session per unit of work, and one virtual thread per session. `halt()` is the only
method legal to call from another thread.

A fire cycle matches, resolves the conflict set (the activations currently eligible), and fires one,
repeating until nothing is eligible. Order is salience, then recency, then a total tiebreak, and
refraction stops the same match firing twice on the same facts. Right-hand sides are staged and then
committed as a unit, so no action sees a half-applied world.

## The worked example

[`rule-engine-example`](rule-engine-example/) is a complete small application, with one rule file,
one feed of ten events, and four deployment shapes side by side. It runs with:

```bash
./gradlew :rule-engine-example:run
```

It is the fastest way to see what a reference page cannot show: what belongs in the ingestion path
rather than in a rule, how session scope decides what a rule can possibly see, what a long-lived
session has to do to stay bounded, and what to assert about a rule set in CI.
[Its README](rule-engine-example/README.md) is a companion to the DSL guide.

## Three modelling traps

All three are intended behaviour, and all three shape the data model from the first day.

- Absent and null are different values. `{ eq: null }` matches an explicit JSON null and never an
  absent field. `hasField: false` is the test for "the field isn't there".
- `ne` is true for an absent field, because `ne` is defined as `!eq`. `status != "CLOSED"` matches
  an order with no `status` at all. Pairing it with `hasField: true` expresses "present and not
  closed". The same applies to `notIn`.
- Collections are flattened at ingestion. JSON Pointer has no wildcard, so `items.*.qty` does not
  exist and is not planned. An `Order` with an `items[]` array becomes one `Order` fact plus N
  `LineItem` facts carrying `orderId`, joined normally. Flattening is the only way to get indexing
  and incremental matching over collection elements, and retrofitting it means rewriting every rule
  that touches a collection.

[`docs/dsl-guide.md`](docs/dsl-guide.md) opens with the fuller version of these, and the DSL
reference has the rest: vacuous `forAll` over an empty scope, what `min` of nothing means, and why
nothing may name a quantified pattern's alias.

The sharpest hazard in the engine appears at operations time rather than authoring time. A fact
type that the rules negate, quantify over, fold, or conclude must never be evicted. An evicted fact
and an absent one are indistinguishable, so a cap on a negated type stops costing a firing and
starts asserting a false conclusion.
[`docs/embedding.md`](docs/embedding.md#long-lived-sessions-and-eviction) works through all four
shapes.

## Rules that do not fire

A firing leaves a record; a non-firing leaves nothing to look up, because the fast path is optimised
not to record what it eliminated. `MatchExplainer` re-evaluates the constraints one at a time and
names the one that emptied the set:

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted — already fired at recency 4
//   o: Order — 1 considered, 1 matched
//   c: Customer — 1 considered, 1 matched
```

Four answers cover nearly every real case, and the last two are the least obvious:

1. No fact of some type exists. The usual cause is a fact type spelled differently from how the host
   inserts it. `declaredFactTypes` surfaces it as `report().unreachableRules()`, which a CI build
   can assert is empty.
2. N facts were considered and all failed a named constraint. The report gives the value that failed
   it.
3. The session could not see the facts. A rule spanning two orders cannot fire in a session holding
   one, however it is written. Scope is the thing to check before the rule.
4. The rule already fired on those exact facts. That is refraction, and it is what stops rules
   firing forever.

The three modelling traps above come before any of these. A rule that "matches nothing" is very
often an `eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often
a bare `ne`.

At 3am the session is closed and the facts are gone, so the postmortem path (capture with a listener
or `exportFacts()`, replay, then explain) has to be built before it is needed.
[`docs/embedding.md`](docs/embedding.md#diagnosing-production) has it.

## Rule sets as source code

`CompiledRuleSet.report()` is data rather than a printed string, so a build can assert on it:

```java
CompilerReport report = rules.report();
// rule set sha256:4073bf55c15edf78
//   2 rules, 3 distinct alpha nodes from 4 tests (sharing 1.33x), 2 patterns, 1 join edges
//   unindexed: fraud-check: o.region (NOT_IN)
```

Two options turn a runtime problem into a build-time one, and both belong in a build from the first
day:

```java
CompilerOptions.builder()
    .declaredFunctions(Set.of("notifySlack"))       // a typo becomes a compile error
    .declaredFactTypes(Set.of("Order", "Customer")) // fills report().unreachableRules() -- assert it
    .build();
```

An *alpha node* is one single-fact test, shared across every rule that expresses it; the sharing
figure is how much of that work the rule set has in common. A *join edge* is a constraint relating
two facts. Neither is configured; they are how the report describes what it built.

[`docs/dsl-guide.md`](docs/dsl-guide.md#checking-your-rules-in-ci) has the whole gate, including how
to handle unindexed constraints and what registering fact schemas provides.
[`ExampleRulesTest`](rule-engine-example/src/test/java/com/codeheadsystems/rules/example/ExampleRulesTest.java)
is a copyable version.

## Production operation

A host compiles once and shares the `CompiledRuleSet`, and creates a cheap single-writer
`RuleSession` per unit of work, one virtual thread each.
[`docs/embedding.md`](docs/embedding.md) is the host-side manual and covers the operational
surface. Four of its answers in brief:

- Rules swap under load. `RuleSetHolder` is one volatile field and no locks, and `publish` takes an
  already-compiled rule set, so a rule file with a typo in it fails at compile and the engine stays
  in service on the rules it has. A swap affects new sessions only.
- Running many at once is the default shape. `RuleBatches` gives one virtual thread and one session
  per batch, returning a per-batch outcome that carries either a result or a failure.
- Choosing a matcher is not required. The default is the indexed network, which is the right choice
  for per-request work; the streaming matcher matters only for long-lived sessions.
- A decision is fast enough for a request path. Allocating a session, inserting twenty facts, and
  firing to completion measures about 15µs on the default matcher; two hundred facts is about 455µs.
  Neither is a latency guarantee (work is bounded, wall time is not), so a host with a hard budget
  supplies a watchdog. [`docs/benchmarks.md`](docs/benchmarks.md) has the method and the error bars.

Registering a `HostFunction`, every `SessionOptions` setting, the work limits, eviction, tracing and
Flight Recorder, and reconstructing a decision after the session is closed are all in the manual.

## Modules

| Artifact | Version | Contents |
|---|---|---|
| `rule-engine-core` | [![Maven Central: rule-engine-core](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-core?label=rule-engine-core)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-core) | Fact model, working memory, all three matchers, agenda, refraction, RHS execution, sessions, and the concurrency helpers |
| `rule-engine-compiler` | [![Maven Central: rule-engine-compiler](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-compiler?label=rule-engine-compiler)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-compiler) | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, network build, version hash, and `CompilerReport` |
| `rule-engine-dsl` | [![Maven Central: rule-engine-dsl](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=rule-engine-dsl)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-dsl) | JSON and YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema and located diagnostics. The starting point; it brings `rule-engine-compiler` and `rule-engine-core` with it |
| `rule-engine-cel` | [![Maven Central: rule-engine-cel](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-cel?label=rule-engine-cel)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-cel) | Optional. The expression escape hatch, backed by dev.cel |
| `rule-engine-schema` | [![Maven Central: rule-engine-schema](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-schema?label=rule-engine-schema)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-schema) | Optional. Fact schemas, backed by JSON Schema |
| `rule-engine-observability` | [![Maven Central: rule-engine-observability](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-observability?label=rule-engine-observability)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-observability) | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | [![Maven Central: rule-engine-testkit](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-testkit?label=rule-engine-testkit)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-testkit) | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, and JMH benchmarks. Not optional: a consumer testing its own rules wants exactly these |
| `rule-engine-bom` | [![Maven Central: rule-engine-bom](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-bom?label=rule-engine-bom)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-bom) | The modules above and the Jackson 3 version they are built against, as one import. No code |
| `rule-engine-example` | not published | The worked application. An artifact is a promise to keep something compiling for whoever depends on it, and nobody should depend on the example |

Every badge reads the live version from Maven Central. All eight move together (a release tags one
version and publishes all of them in a single deployment), so a badge showing a different number
from its neighbours means a deployment went wrong rather than that the modules drifted.

## Documentation

| Document | Contents |
|---|---|
| [`rule-engine-example/README.md`](rule-engine-example/README.md) | the starting point: a complete, runnable application |
| [`docs/dsl-guide.md`](docs/dsl-guide.md) | writing a rule, from a blank file |
| [`docs/dsl-reference.md`](docs/dsl-reference.md) | every operator, every action, every diagnostic code |
| [`docs/embedding.md`](docs/embedding.md) | the host side: sessions, options, limits, concurrency, operations, and diagnosing production |
| [`docs/choosing-this-engine.md`](docs/choosing-this-engine.md) | where it fits, where it does not, the comparisons, and getting out |
| [`docs/rule-engine-spec.md`](docs/rule-engine-spec.md) | the specification, and the source of truth |
| [`docs/benchmarks.md`](docs/benchmarks.md) | what is measured, on what, and what the numbers do not show |
| [`docs/style.md`](docs/style.md) | the register, punctuation, and terminology the documentation is written to |

## Building

Building requires a JDK 25 toolchain; Gradle resolves one via the foojay plugin if none is
installed.

```bash
./gradlew build        # compile, test, and the strict-mode test run
./gradlew test         # the suite
./gradlew strictTest   # the same suite with -Drules.strict=true, which turns on the contract
                       # checks too expensive for production. CI runs both; never run it in prod
./gradlew javadoc      # warnings fail the build; several contracts live only in Javadoc
./gradlew testCodeCoverageReport   # aggregated across modules, currently 93.5% line

./gradlew :rule-engine-example:run # the worked example
```

[`RELEASING.md`](RELEASING.md) covers releasing: a `vX.Y.Z` tag starts the workflow, which does the
rest.

## License

Copyright 2026 Ned Wolpert.

Licensed under the Apache License, Version 2.0; see [`LICENSE`](LICENSE).
