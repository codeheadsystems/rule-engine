# rule-engine

[![build](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml/badge.svg)](https://github.com/codeheadsystems/rule-engine/actions/workflows/gradle.yml)
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

## Before you adopt

Five facts that decide whether to read further. They are here rather than buried because two of them
are flat disqualifiers for some shops, and finding that out at `NoClassDefFoundError` is worse than
finding it out now.

- **Java 25 at runtime**, not just to build. The published jars are class-file major version 69 with
  no multi-release fallback, so they will not load on 17 or 21. Spec §5 has the reasoning.
- **You are taking a Jackson 3 dependency** (`tools.jackson`), declared `api` because `JsonNode` is
  in about sixty public signatures. It coexists with Jackson 2 — different group, different package —
  but it is a second Jackson on your tree, and a Jackson major is a major version here.
- **First release August 2026. One maintainer. No known production deployments.** The test suite and
  the specification are unusually thorough for a project this young; that is not the same as having
  been run by anyone else. [Project status](#project-status) is candid about what that means.
- **Work is bounded; wall time is not.** `maxCycles` (10,000) and `maxFacts` (1,000,000) bound a fire
  call, and neither is a proxy for latency. If you have a per-decision budget, run your own watchdog
  against `halt()` — see [`docs/embedding.md`](docs/embedding.md#limits-and-the-one-the-engine-does-not-enforce).
- **Getting out is a real option, and worth checking before you get in.** Rules are text against a
  published schema, facts are your own JSON and `exportFacts()` hands them back; what does not unwind
  for free is the flattened fact model. [The exit
  section](docs/embedding.md#getting-out) is honest about both halves.

## Contents

**Evaluating this?** → [what it deliberately does not
do](#what-it-deliberately-does-not-do) · [what the compiler
knows](#what-the-compiler-knows) · [running it in production](#running-it-in-production) · [why
didn't my rule fire?](#why-didnt-my-rule-fire) · [project status](#project-status)

**Writing your first rule?** → [start here](#start-here), then
[`docs/dsl-guide.md`](docs/dsl-guide.md), then [the worked example](rule-engine-example/README.md).

- [Start here](#start-here)
- [The worked example](#the-worked-example)
- [How it fits together](#how-it-fits-together)
- [What a rule looks like](#what-a-rule-looks-like)
- [Things that surprise people](#things-that-surprise-people)
- [What it deliberately does not do](#what-it-deliberately-does-not-do)
- [Running it in production](#running-it-in-production)
- [Why didn't my rule fire?](#why-didnt-my-rule-fire)
- [What the compiler knows](#what-the-compiler-knows)
- [Modules](#modules)
- [Documentation](#documentation)
- [Building](#building)
- [Project status](#project-status)
- [License](#license)

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

## What a rule looks like

A rule is `when` (patterns) and `then` (actions). Patterns are AND-ed, and so is everything inside
one; there is no `or` — write two rules, or use `in`. Any constraint can compare two facts by naming
the other's field with `{ $ref: alias.field }`, and both ends of every join edge are indexed.

Four kinds of pattern: an ordinary one binds a fact; **`notExists`** asserts an absence;
**`forAll`** asserts that everything the join selects meets a requirement; **`accumulate`** folds a
scope into a number. The last three bind no fact, and the last one binds a *value*, so nothing may
join to it.

**`after` and `before` relate two facts in time**, within a required bound — and the engine reads no
clock, so every time it uses comes off a fact you inserted. That is what makes a replay reproduce the
original decision. (Sliding windows and "nothing happened for 24h" are a different problem and are
[not built](#what-it-deliberately-does-not-do): both need to notice time passing with no fact
arriving.)

Five actions and no more — `setField`, `insertFact`, `retractFact`, `emit`, `callFunction` — because
a closed vocabulary stays diffable and reviewable by someone who is not a programmer. `insertFact`
with `logical: true` makes a **conclusion**, withdrawn when the match that made it stops holding.

**[`docs/dsl-reference.md`](docs/dsl-reference.md) is the complete surface** — every operator, every
action, every diagnostic code — and [`docs/dsl-guide.md`](docs/dsl-guide.md) walks you there from a
blank file. This section is deliberately only enough to know what is expressible.


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
does and why. [`docs/embedding.md`](docs/embedding.md#long-lived-sessions-and-eviction) has the full
analysis, and the example's README works it through a real rule set and names the one type of six
that can safely be capped.

## What it deliberately does not do

§9.1 has the full accounting; these are the ones people ask for.

| not built | why not |
|---|---|
| `collect` | answers with a collection, so it has no meaningful `having`, and binding a list needs a way to take one apart that §2.5 does not have |
| sliding windows, "nothing for 24h" | both need something to notice time passing with *no fact arriving* — the one input an engine that acts on fact movement never receives. Needs either a clock, which would end the determinism contract, or a caller-driven session time, which would not but is a contract of its own |
| `or` inside a `where` | write two rules, use `in`, or reach for §6.4's `condition:` |
| backward chaining | §1's forward-only decision stands |
| distributed evaluation | §5's immutability split makes it *feasible* and no more. The partitioning, the wire protocol and cross-node routing are an architecture, not a slice |

## Running it in production

Compile once and share the `CompiledRuleSet`; create a cheap single-writer `RuleSession` per unit of
work, one virtual thread each.

**[`docs/embedding.md`](docs/embedding.md) is the host-side manual** and covers what is not on this
page: every `SessionOptions` setting, the work limits and the wall-clock bound the engine does *not*
enforce, registering a `HostFunction`, choosing between the three matchers, `RuleBatches` and
`SessionActor`, hot reload, what happens when a rule action throws, the operational surface
(`session.stats()`, the tracing and Flight Recorder listeners, `EmitContext` for audit), and how to
reconstruct a decision after the fact.

Measured on an 8-core machine, 256 concurrent batches scale 5.79x against a shared-nothing control's
7.16x — 81% of what that box can do. Sharing one rule set across every thread costs nothing
measurable; see [`docs/benchmarks.md`](docs/benchmarks.md).

## Why didn't my rule fire?

A firing leaves a record; a *non*-firing leaves nothing to look up, because the fast path is
optimised precisely not to record what it eliminated. `MatchExplainer` re-evaluates the constraints
one at a time against working memory and names the one that emptied the set:

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted — already fired at recency 4
//   o: Order — 1 considered, 1 matched
//   c: Customer — 1 considered, 1 matched
```

Four answers cover nearly every real case, and the last two are the ones nobody guesses:

1. **No fact of some type exists** — usually a fact type spelled differently from how the host
   inserts it. `declaredFactTypes` does not reject that at compile time; it surfaces it as
   `report().unreachableRules()`, which you assert is empty in CI. See below.
2. **N considered, all failed a named constraint** — reported with the value that failed it.
3. **The session could not see the facts.** A rule spanning two orders cannot fire in a session
   holding one, however it is written. Check scope before you check the rule.
4. **The rule already fired on those exact facts.** That is refraction, and it is what stops rules
   firing forever.

It sees quantifiers too, and names the fact in the way — the `Payment` defeating a `notExists`, the
`LineItem` failing a `forAll`. It cannot see the eviction hazard above: it re-asks the same question
of the same working memory and is fooled identically, so it warns rather than detects.

Before any of that, check the three modelling traps above. A rule that "matches nothing" is very
often an `eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often
a bare `ne`.

**At 3am the session is closed and the facts are gone**, so the postmortem path — capture with a
listener or `exportFacts()`, replay, then explain — is worth building before you need it.
[`docs/embedding.md`](docs/embedding.md#diagnosing-production) has it, along with the pinned-handle
form of `explain` and the `EmitContext` audit trail.

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
build-time one, and both are worth setting from the first day — though only the first is literally a
compile error:

```java
CompilerOptions.builder()
    .declaredFunctions(Set.of("notifySlack"))       // a typo becomes a compile error
    .declaredFactTypes(Set.of("Order", "Customer")) // fills report().unreachableRules() -- assert it
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

## Modules

| Artifact | Version | Contents |
|---|---|---|
| `rule-engine-core` | [![Maven Central: rule-engine-core](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-core?label=rule-engine-core)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-core) | Fact model, working memory, all three matchers, agenda, refraction, RHS execution, sessions, the concurrency helpers |
| `rule-engine-compiler` | [![Maven Central: rule-engine-compiler](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-compiler?label=rule-engine-compiler)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-compiler) | `RuleDefinition` → `CompiledRuleSet`: validation, accessor and pattern compilation, tested paths, network build, version hash, `CompilerReport` |
| `rule-engine-dsl` | [![Maven Central: rule-engine-dsl](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-dsl?label=rule-engine-dsl)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-dsl) | JSON *and* YAML rule files → `RuleDefinition`, plus the `rules.v1` rule-file schema and located diagnostics. **Start here** — it brings `-compiler` and `-core` with it |
| `rule-engine-cel` | [![Maven Central: rule-engine-cel](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-cel?label=rule-engine-cel)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-cel) | Optional. The §6.4 expression escape hatch, backed by dev.cel |
| `rule-engine-schema` | [![Maven Central: rule-engine-schema](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-schema?label=rule-engine-schema)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-schema) | Optional. The `FactSchemas` of §2.3, backed by JSON Schema |
| `rule-engine-observability` | [![Maven Central: rule-engine-observability](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-observability?label=rule-engine-observability)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-observability) | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | [![Maven Central: rule-engine-testkit](https://img.shields.io/maven-central/v/com.codeheadsystems/rule-engine-testkit?label=rule-engine-testkit)](https://central.sonatype.com/artifact/com.codeheadsystems/rule-engine-testkit) | Fixtures, the firing-sequence oracle, the shuffle-determinism and matcher-equivalence harnesses, JMH benchmarks. **Not optional** — a consumer testing their own rules wants exactly these |
| `rule-engine-example` | *not published* | The worked application. An artifact is a promise to keep something compiling for whoever depends on it, and nobody should depend on the example |

Depend on `rule-engine-dsl` if you write your rules in YAML: it brings `-compiler` and `-core` with
it, so that is the whole dependency. The two optional modules exist so that nobody pays for JSON
Schema or for CEL's protobuf/guava/antlr footprint without asking.

**Every badge above reads the live version from Maven Central**, so they are the answer to "what is
current" rather than a number somebody remembered to edit. All seven move together — a release tags
one version and publishes all of them in a single deployment, so a badge showing a different number
from its neighbours means a deployment went wrong rather than that the modules drifted.
[`RELEASING.md`](RELEASING.md) is how a version ships.

## Documentation

| | |
|---|---|
| [`rule-engine-example/README.md`](rule-engine-example/README.md) | **start here** — a complete application you can run |
| [`docs/dsl-guide.md`](docs/dsl-guide.md) | writing a rule, from a blank file. Opens with the three things that surprise everybody |
| [`docs/dsl-reference.md`](docs/dsl-reference.md) | every operator, every action, every diagnostic code |
| [`docs/embedding.md`](docs/embedding.md) | the host side: sessions, options, limits, concurrency, operations, and diagnosing production |
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
`-testkit`, and a per-module report attributes none of that back — it once reported `-core` at 26%
against an aggregate three times that. The aggregate is currently 93.5% line, 94.6% instruction,
88.3% branch, over a suite that runs twice in full: once normally and once under strict mode.

Releasing is [`RELEASING.md`](RELEASING.md): tag `vX.Y.Z` and the workflow does the rest.

**Strict mode** turns on every check that is too expensive for production but catches a contract
violation deterministically in test: engine-owned payloads are handed out as copies, an `update` that
aliases the stored payload is rejected, the conflict-resolution strategy is asserted to be a total
order consistent with equality, and an eviction policy is consulted twice and compared. §7.5 asks for
the full suite to run under it in CI and forbids it in production; `strictTest` is that run.

## Project status

**First release: August 2026. One maintainer. No known production deployments.**

That is stated plainly because the rest of this page reads like a mature project and the test suite
encourages the impression. Nine hundred-odd tests, 93.5% coverage, a 2,000-line specification that amends itself
when reality disagrees, and a benchmark document that retracts its own claims are all real — and none
of them is the same thing as having been run by somebody who is not the author. The risk is not that
the code is bad; it is that no workload has hit it that the author did not think of.

What that means concretely, and what would change it:

- **Support** is best-effort. Issues and pull requests are welcome; there is no SLA, and there is
  currently no second committer.
- **Security reports** go to the address in [`SECURITY.md`](SECURITY.md). This parses rule files and
  JSON payloads and ships a CEL evaluator, so please report privately rather than in an issue.
- **What changed between releases** is in [`CHANGELOG.md`](CHANGELOG.md).
- **If it stalls**, forking is a genuine option rather than a formality: Apache 2.0, sources and
  javadoc jars published, the whole design and its rejected alternatives written down, and a naive
  correctness oracle shipped in the testkit so a fork can check itself.

Everything on this page is built and tested. §9's roadmap is complete through Phase 6 except for the
items in [what it deliberately does not do](#what-it-deliberately-does-not-do) — including one,
§11.2's differential propagation, that was profiled, found to matter on its own benchmark, and left
unbuilt because the correctness obligation it imposes is worse than the cost it removes.

What the version number promises is the surface `ApiSurfaceTest` calls exported; see
[`RELEASING.md`](RELEASING.md) for what moves a major. Note that the boundary is enforced by a test
in this repository rather than by a `module-info` in the jar (§8.1 explains why), so nothing stops
you importing an internal package — and nothing promises it will still be there.

## License

Copyright 2026 Ned Wolpert.

Licensed under the Apache License, Version 2.0 — see [`LICENSE`](LICENSE). Every published POM
declares the same, which is why that file has to exist rather than be implied: an artifact on Maven
Central asserting a licence the source does not grant is permanent and not fixable by a patch
release.
