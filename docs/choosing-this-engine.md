# Choosing the rule engine

What the rule engine is good at, what it is bad at, what it does not do, and what it costs to leave,
for somebody deciding whether to adopt it or building the case for a team that has to agree.

The two facts that end the conversation are Java 25 at runtime and Jackson 3 on the consumer's
classpath. The first section covers both.

## Contents

- [The two hard requirements](#the-two-hard-requirements)
- [Suitable workloads](#suitable-workloads)
- [Unsuitable workloads](#unsuitable-workloads)
- [Capabilities not built](#capabilities-not-built)
- [Comparison with alternatives](#comparison-with-alternatives)
- [Project maturity](#project-maturity)
- [Getting out](#getting-out)

## The two hard requirements

They are the only two requirements that rule the engine out regardless of how well it fits the
problem, and the first one otherwise surfaces as an `UnsupportedClassVersionError`.

### Java 25 at runtime

The engine needs Java 25 at runtime, not just to build. The published jars are class-file major
version 69 with no multi-release fallback, so they do not load on 17 or 21. It is not a conservative
floor that might be relaxed: the concurrency model is built on virtual threads and the JDK 25
primitives around them, which is where the two-tier split gets to be cheap rather than clever.
[`embedding.md`](embedding.md#platform-requirements) has the reasoning.

### Jackson 3

Jackson 3 (`tools.jackson`) is declared `api` rather than `implementation`, because `JsonNode`
appears in about sixty public signatures. It coexists with Jackson 2 (different group, different
package, so both can be on one classpath), but it is a second Jackson in the consumer's dependency
tree, and some shops forbid that outright. A Jackson major is a major version here, which is the
other half of the cost: when Jackson 4 lands, the engine's version number moves with it. Within a
major a consumer does not track the number: `rule-engine-bom` carries the Jackson version the engine
was tested against.

## Suitable workloads

Five shapes follow. Each names the property that makes it work and points at something runnable in
[`rule-engine-example`](../rule-engine-example/README.md).

### Per-request decisioning that somebody must justify later

Eligibility, pricing, risk scoring, and fraud flags: a decision made inside one request, against
facts that arrived with it. The property that matters is that firing is a pure function of the facts
inserted and the order they were inserted in. The engine reads no clock, so replaying the same facts
a year later reproduces the original decision exactly. Regulated domains get this for free rather
than building it. `PerOrderDemo` shows it.

### Correlating facts that arrive separately

This is the capability that a chain of `if` statements cannot reach, and the main reason to bring in
an engine at all: joins across fact types, absence (`notExists`), universals (`forAll`), and
aggregates (`accumulate`), as in "an order whose every line item is in stock", "a customer with
three unpaid orders", or "a payment with no matching order". Both ends of every join edge are
indexed. The rules in [`orders.yaml`](../rule-engine-example/src/main/resources/rules/orders.yaml)
show it.

### High-volume evaluation

One immutable `CompiledRuleSet` is shared by every thread, and one cheap single-writer `RuleSession`
is created per unit of work. Allocating that session, inserting twenty facts, and firing to
completion measures about 15µs on the default matcher, and session creation is 248ns of it, so the
per-decision cost is the matching, not the machinery. Sharing the rule set across every thread costs
nothing measurable; the concurrency benchmark tests that claim. `BatchDemo` and
[`benchmarks.md`](benchmarks.md) have the details.

### Policy that changes faster than the service deploys

Rules are text against a published schema, diffable in review, with diagnostics that name a file,
line, and column. `RuleSetHolder` swaps a rule set under load, and it takes a compiled rule set, so
a rule file with a typo in it fails at compile and the engine stays in service on the rules it has.
A policy change becomes a config push. The closed five-verb action vocabulary is what keeps a rule
file reviewable by somebody who is not a programmer. `DiagnosticsDemo` and
[`embedding.md`](embedding.md#swapping-rules-while-running) cover it.

### Long-lived streaming correlation

A session held open across many events, with `MatchingStrategy.RETE` keeping joins materialised as
facts arrive, fits when the application knows what is finished. That condition is the caveat: every
structure a long-lived session grows is keyed on fact handles, so something has to remove facts. The
safe version is the application retracting what it knows is done. `StreamingDemo` and
[`embedding.md`](embedding.md#long-lived-sessions-and-eviction) cover it.

## Unsuitable workloads

Each of these names the alternative. The first rules out the most workloads, and it is structural
rather than a gap awaiting a fix.

### Time passing with no fact arriving

SLA timers, "no payment received in 24 hours", and session timeouts are out of reach. The engine
acts on fact movement, and "nothing arrived" is the one input it never receives. It owns no clock,
because a wall clock would make the firing sequence depend on when it ran, which would end the
determinism contract. The alternative is a scheduler or a timer wheel whose output is inserted as a
fact: a `Clock` fact the application advances is enough to ask "no failure in the last ten minutes",
and the rules then see time the same way they see everything else. The engine does not supply the
scheduler.

Windows over facts that do arrive are a different matter, and they are supported: a bounded temporal
join windows what a rule matches, `EvictionPolicy.window` windows what the session keeps, and the
two together are a velocity rule ("five failures for one user in ten minutes") with no clock
anywhere. [The guide](dsl-guide.md#counting-things-in-a-window) has it.

### An unsupervised per-decision latency ceiling

`maxCycles` (10,000) and `maxFacts` (1,000,000) bound the work a fire call does, and neither is a
proxy for wall time. A host with a p99 budget runs its own watchdog against `halt()`, the one method
legal to call from another thread. [`embedding.md`](embedding.md#work-limits-and-wall-clock-time)
has the recipe; the host has to write the watchdog itself.

### Collection-shaped data that cannot be flattened

JSON Pointer has no wildcard, so `items.*.qty` does not exist and is not planned. An order with a
nested `items[]` array becomes one `Order` fact plus N `LineItem` facts carrying the order id.
Flattening is the only way to get an index over collection elements at all, but it is a real
modelling constraint, it happens at ingestion, and retrofitting it means rewriting every rule that
touches a collection.

### Goal-driven questions

"What would have to be true for this claim to be approved?" is backward chaining, and the engine is
forward-only. A solver or a logic-programming system fits instead.

### Distributed evaluation

The immutability split makes it feasible and no more; the partitioning, wire protocol, and
cross-node routing are an architecture nobody has built here.

### Rule authoring in a UI by non-engineers

There is no workbench, no decision tables, and no editor. Rules are files in the host's repository
that go through code review. The vocabulary is small enough for a domain expert to read and to
review, and that is a real property, but the author it is designed for is somebody who writes YAML
in a repository.

### Fewer than about ten single-fact rules

Plain `if` statements are the better tool. An engine earns its keep when rules correlate multiple
facts, when they change on a different cadence from the code, or when somebody has to explain a
decision afterwards. None of those apply to eight independent predicates over one object, and an
unneeded dependency is worse than a readable conditional.

### Single-fact validation

JSON Schema, or plain code, fits instead. The optional [`rule-engine-schema`](../rule-engine-schema)
module exists so that validation is a separate concern from matching.

<a id="what-it-deliberately-does-not-do"></a>
## Capabilities not built

The specification's §9.1 has the full accounting; these are the most requested.

| Capability | Reason |
|---|---|
| `collect` | answers with a collection, so it has no meaningful `having`, and binding a list needs a way to take one apart that the pattern language does not have |
| a sliding-window operator, "nothing for 24h" | there is no window keyword and no engine-owned clock. Nothing here notices time passing with no fact arriving, the one input an engine that acts on fact movement never receives. The substitute is a bounded temporal join for what a rule matches plus an `EvictionPolicy.window` for what the session keeps, and a caller-advanced `Clock` fact where the window has to end at "now" ([the recipes](dsl-guide.md#counting-things-in-a-window)). That is caller-driven session time, made of parts that already existed rather than a contract of its own |
| a lookup into host-owned data (a blocklist, a feature store) during matching | everything deciding which activation fires assumes a match's answer changes only because a fact moved, so a live lookup would blind refraction, the streaming conflict set, and truth maintenance at once. The host looks it up before the session and inserts the answer as a fact, `member: true` or `false`; a rule that adds to the list flips that field and emits the write. [The recipe](dsl-guide.md#checking-a-list-your-application-owns) and [the host half](embedding.md#host-owned-lists-and-reference-data) |
| `or` inside a `where` | two rules, an `in`, or a `condition:` expression covers it |
| backward chaining | the engine is forward-only, a decision made before any code was written, and it stands |
| distributed evaluation | the immutability split makes it feasible and no more. The partitioning, the wire protocol, and cross-node routing are an architecture, not a slice |

## Comparison with alternatives

The comparisons are of capability only. The project benchmarks nothing but itself, so it makes no
performance claim about another engine.

### Drools

The specification's design premise (§0) is a reader who knows JESS/ILOG (classic Rete, tight and
predictable) and finds Drools cumbersome. The parts people usually mean by that are a heavyweight
`KieBase`/`KieSession` object graph, MVEL/DRL as a quasi-programming-language DSL that blurs config
and code, XML/KJAR packaging ceremony, and an execution model that is hard to reason about under
concurrent load. The rule engine keeps indexed incremental matching and avoids those four
specifically: a compiled rule set is an object a compile call returns, with no mandatory build or
packaging layer; the DSL is declarative-first with an explicit opt-in escape hatch rather than a
scripting language by default; and the immutability split is the concurrency story rather than an
afterthought.

Drools has a long list of things the rule engine does not: two decades of production hardening, an
ecosystem, a workbench and decision tables, complex event processing with genuine sliding windows,
backward chaining, and an enormous number of deployments that have found the bugs. For CEP or
non-developer authoring tooling, Drools is the answer and the rule engine is not.

### A hand-written chain of conditionals

A chain of conditionals wins on debuggability, zero dependencies, and the fact that the team already
understands it. It stops winning at three specific moments: when a decision has to correlate facts
that arrived separately, when somebody asks why a decision did not happen and there is nothing to
inspect, and when the logic starts changing on a different cadence from the deploy. Until one of
those happens, the conditionals are the right answer.

### A config-driven predicate list

The usual intermediate step is rules-as-config, where each rule is a field, an operator, and a value
evaluated against one object. It works, and it is simpler than the rule engine. The crossover is the
first rule that spans two entities. At that point the config format grows a join, then aliases, then
a way to say "no matching payment exists", and it turns into a rule engine one under-specified
feature at a time.

### A database view or a SQL query

A database is excellent at set correlation over data that is already stored, and if the facts all
live in one database and the decision can wait for a round trip, it is often the better tool. What
it does not provide is forward chaining (a conclusion feeding the next rule), refraction, in-request
evaluation against facts that never hit a table, or a closed action vocabulary that bounds what a
rule can do.

## Project maturity

The first release was in August 2026. The project has one maintainer and no known production
deployments.

The test suite and the rest of the documentation can suggest a more mature project. Nine hundred-odd
tests, 93.5% line coverage, a 2,000-line specification that amends itself when reality disagrees,
and a benchmark document that retracts its own claims are all real, and none of them is the same
thing as having been run by somebody who is not the author. The risk is not that the code is bad; it
is that no workload has hit it that the author did not think of.

- Support is best-effort. Issues and pull requests are welcome; there is no SLA, and there is
  currently no second committer.
- Security reports go to the address in [`SECURITY.md`](../SECURITY.md). The engine parses rule
  files and JSON payloads and ships a CEL evaluator, so a report belongs in private rather than in
  an issue.
- If the project stalls, forking is a genuine option rather than a formality: Apache 2.0, sources
  and javadoc jars published, the whole design and its rejected alternatives written down, and a
  naive correctness oracle shipped in the engine itself (with the harness that holds it and the fast
  matchers to identical firing sequences), so a fork can check itself against a second
  implementation.

## Getting out

The exit cost is lower than is usual for a rule engine. Rules are text against a published schema
rather than a proprietary binary; facts are the host's own JSON and `exportFacts()` hands them back;
nothing is persisted, so there is no store to migrate. What does not unwind for free is the
flattened fact model: if orders were split into `Order` plus `LineItem` at ingestion to make them
matchable, that shape has propagated into the ingestion code and possibly the storage.

[`embedding.md`](embedding.md#getting-out) has the complete version, covering both halves.
