# Choosing this engine

This page exists so that [`README.md`](../README.md) can teach instead of hedge. It is the honest
account of what this engine is good at, what it is bad at, what it will never do, and what it costs
to leave — written for somebody deciding whether to adopt it, or building the case for a team that
has to agree.

If you are only here for the two facts that end the conversation: **Java 25 at runtime, and Jackson 3
on your classpath.** Both are below.

## Contents

- [The two hard requirements](#the-two-hard-requirements)
- [Where this fits best](#where-this-fits-best)
- [Where it does not fit](#where-it-does-not-fit)
- [What it deliberately does not do](#what-it-deliberately-does-not-do)
- [Compared with the alternatives](#compared-with-the-alternatives)
- [How mature this is](#how-mature-this-is)
- [Getting out](#getting-out)

## The two hard requirements

These are first because they are the only two that end the conversation regardless of how well the
engine fits your problem. Finding them out at `UnsupportedClassVersionError` is worse than finding
them out here.

**Java 25 at runtime**, not just to build. The published jars are class-file major version 69 with no
multi-release fallback, so they will not load on 17 or 21. This is not a conservative floor that
might be relaxed: the concurrency model is built on virtual threads and the JDK 25 primitives around
them, which is where the two-tier split gets to be cheap rather than clever.
[`embedding.md`](embedding.md#platform-requirements) has the reasoning.

**Jackson 3** (`tools.jackson`), declared `api` rather than `implementation` because `JsonNode`
appears in about sixty public signatures. It coexists with Jackson 2 — different group, different
package, so both can be on one classpath — but it is a second Jackson on your tree, and some shops
forbid that outright. A Jackson major is a major version here, which is the other half of the cost:
the day Jackson 4 lands, this engine's version number moves with it.

## Where this fits best

Five shapes. Each names the property that makes it work, and points at something in
[`rule-engine-example`](../rule-engine-example/README.md) you can run.

**Per-request decisioning that somebody must justify later.** Eligibility, pricing, risk scoring,
fraud flags — a decision made inside one request, against facts that arrived with it. The property
that matters is that firing is a pure function of the facts you inserted and the order you inserted
them: the engine reads no clock, so replaying the same facts a year later reproduces the original
decision exactly. Regulated domains get this for free rather than building it. See `PerOrderDemo`.

**Correlating facts that arrive separately.** This is the capability that a chain of `if` statements
genuinely cannot reach, and the main reason to bring in an engine at all: joins across fact types,
absence (`notExists`), universals (`forAll`), and aggregates (`accumulate`) — "an order whose every
line item is in stock", "a customer with three unpaid orders", "a payment with no matching order".
Both ends of every join edge are indexed. See the rules in
[`orders.yaml`](../rule-engine-example/src/main/resources/rules/orders.yaml).

**Evaluating a lot of these at once.** One immutable `CompiledRuleSet` is shared by every thread and
one cheap single-writer `RuleSession` is created per unit of work. Allocating that session, inserting
twenty facts and firing to completion measures about 15µs on the default matcher, and session creation
is 248ns of it — so the per-decision cost is the matching, not the machinery. Sharing the rule set
across every thread costs nothing measurable, which is the claim the concurrency benchmark exists to
test rather than assert.
See `BatchDemo` and [`benchmarks.md`](benchmarks.md).

**Policy that changes more often than the service deploys.** Rules are text against a published
schema, diffable in review, with diagnostics that name a file, line and column. `RuleSetHolder`
swaps a rule set under load, and it takes a *compiled* rule set — so a rule file with a typo in it
fails at compile and the engine stays in service on the rules it has. A policy change becomes a
config push. The closed five-verb action vocabulary is what keeps a rule file reviewable by somebody
who is not a programmer. See `DiagnosticsDemo` and
[`embedding.md`](embedding.md#swapping-rules-while-running).

**Long-lived streaming correlation, when the application knows what is finished.** A session held
open across many events, with `MatchingStrategy.RETE` keeping joins materialised as facts arrive.
The condition is the caveat: every structure a long-lived session grows is keyed on fact handles, so
something has to remove facts. The safe version is the application retracting what it knows is done.
See `StreamingDemo` and [`embedding.md`](embedding.md#long-lived-sessions-and-eviction).

## Where it does not fit

Each of these names what to reach for instead. The first is the one that disqualifies most people,
and it is structural rather than a gap somebody will close.

**Anything that must notice time passing with no fact arriving.** SLA timers, "no payment received in
24 hours", sliding windows, session timeouts. The engine acts on fact movement, and "nothing arrived"
is the one input it never receives. It owns no clock on purpose — a wall clock would make the firing
sequence depend on when it ran, which is the determinism contract gone. Reach for a scheduler, a
timer wheel, or a CEP engine, and insert the resulting timeout as a fact if you still want rules to
see it.

**A hard per-decision latency ceiling with nothing supervising it.** `maxCycles` (10,000) and
`maxFacts` (1,000,000) bound the *work* a fire call does, and neither is a proxy for wall time. If
you have a p99 budget, run your own watchdog against `halt()` — it is the one method legal to call
from another thread. [`embedding.md`](embedding.md#limits-and-the-one-the-engine-does-not-enforce)
has the recipe, and the fact that you have to write it.

**Collection-shaped data you cannot flatten.** JSON Pointer has no wildcard, so `items.*.qty` does
not exist and is not coming. An order with a nested `items[]` array becomes one `Order` fact plus N
`LineItem` facts carrying the order id. This is not a workaround — flattening is the only way to get
an index over collection elements at all — but it is a real modelling constraint, it happens at
ingestion, and retrofitting it means rewriting every rule that touches a collection.

**Goal-driven questions.** "What would have to be true for this claim to be approved?" is backward
chaining, and this engine is forward-only. Reach for a solver or a logic-programming system.

**Evaluation spread across machines.** The immutability split makes it feasible and no more; the
partitioning, wire protocol and cross-node routing are an architecture nobody has built here.

**Rule authoring in a UI by non-engineers.** There is no workbench, no decision tables, no editor.
Rules are files in your repository that go through code review. The vocabulary is small enough for a
domain expert to *read* and to review, and that is a real property — but somebody who writes YAML in
a repo is the author this is designed for.

**Fewer than about ten single-fact rules.** Write the `if` statements. An engine earns its keep when
rules correlate multiple facts, when they change on a different cadence from the code, or when
somebody has to explain a decision afterwards. None of those apply to eight independent predicates
over one object, and a dependency you did not need is worse than a conditional you can read.

**Single-fact validation.** Use JSON Schema, or plain code. The optional
[`rule-engine-schema`](../rule-engine-schema) module exists precisely so that validation is a
separate concern from matching.

## What it deliberately does not do

The specification's §9.1 has the full accounting; these are the ones people ask for.

| not built | why not |
|---|---|
| `collect` | answers with a collection, so it has no meaningful `having`, and binding a list needs a way to take one apart that the pattern language does not have |
| sliding windows, "nothing for 24h" | both need something to notice time passing with *no fact arriving* — the one input an engine that acts on fact movement never receives. Needs either a clock, which would end the determinism contract, or a caller-driven session time, which would not but is a contract of its own |
| `or` inside a `where` | write two rules, use `in`, or reach for a `condition:` expression |
| backward chaining | the forward-only decision stands; it was made before any code was written |
| distributed evaluation | the immutability split makes it *feasible* and no more. The partitioning, the wire protocol and cross-node routing are an architecture, not a slice |

## Compared with the alternatives

Capability comparisons only. No performance claim about another engine appears here, because this
project benchmarks nothing but itself.

### Drools

The design premise, stated in the specification before any code was written: *you know
JESS/ILOG — classic Rete, tight and predictable — and find Drools cumbersome.* The parts people
usually mean by that are a heavyweight `KieBase`/`KieSession` object graph, MVEL/DRL as a
quasi-programming-language DSL that blurs config and code, XML/KJAR packaging ceremony, and an
execution model that is hard to reason about under concurrent load. This engine keeps indexed
incremental matching and avoids those four specifically: a compiled rule set is an object you get
back from a compile call with no mandatory build or packaging layer, the DSL is declarative-first
with an explicit opt-in escape hatch rather than a scripting language by default, and the
immutability split is the concurrency story rather than an afterthought.

**What Drools has that this does not**, and it is not a short list: two decades of production
hardening, an ecosystem, a workbench and decision tables, complex event processing with genuine
sliding windows, backward chaining, and an enormous number of deployments that have found the bugs.
If you need CEP or non-developer authoring tooling, that is the honest answer and it is not this.

### A hand-written chain of conditionals

Wins on debuggability, zero dependencies, and the fact that your team already understands it. It
stops winning at three specific moments: when a decision has to correlate facts that arrived
separately, when somebody asks why a decision *did not* happen and there is nothing to inspect, and
when the logic starts changing on a different cadence from the deploy. If none of those has happened
to you, the conditionals are the right answer and this page is telling you to keep them.

### A config-driven predicate list

The usual intermediate step: rules-as-config, where each rule is a field, an operator and a value
evaluated against one object. It works, and it is genuinely simpler than this. The crossover is the
first rule that spans two entities — at that point the config format grows a join, then aliases, then
a way to say "no matching payment exists", and it becomes this engine one under-specified feature at
a time. Naming the crossover is more useful than arguing the general case.

### A database view or a SQL query

Excellent at set correlation over data that is already stored, and if your facts all live in one
database and the decision can wait for a round trip, this is often the better tool. What it does not
give you is forward chaining (a conclusion feeding the next rule), refraction, in-request evaluation
against facts that never hit a table, or a closed action vocabulary that bounds what a rule can do.

## How mature this is

**First release August 2026. One maintainer. No known production deployments.**

That is stated plainly because the rest of the documentation reads like a mature project and the test
suite encourages the impression. Nine hundred-odd tests, 93.5% line coverage, a 2,000-line
specification that amends itself when reality disagrees, and a benchmark document that retracts its
own claims are all real — and none of them is the same thing as having been run by somebody who is
not the author. The risk is not that the code is bad; it is that no workload has hit it that the
author did not think of.

- **Support** is best-effort. Issues and pull requests are welcome; there is no SLA, and there is
  currently no second committer.
- **Security reports** go to the address in [`SECURITY.md`](../SECURITY.md). This parses rule files
  and JSON payloads and ships a CEL evaluator, so please report privately rather than in an issue.
- **If it stalls**, forking is a genuine option rather than a formality: Apache 2.0, sources and
  javadoc jars published, the whole design and its rejected alternatives written down, and a naive
  correctness oracle shipped in the engine itself — with the harness that holds it and the fast
  matchers to identical firing sequences — so a fork can check itself against a second
  implementation.

## Getting out

Worth checking before you get in, and the answer is better than it usually is for a rule engine.
Rules are text against a published schema rather than a proprietary binary; facts are your own JSON
and `exportFacts()` hands them back; nothing is persisted, so there is no store to migrate. What does
*not* unwind for free is the flattened fact model — if you split orders into `Order` plus `LineItem`
at ingestion to make them matchable, that shape has propagated into your ingestion code and possibly
your storage.

[`embedding.md`](embedding.md#getting-out) is honest about both halves and is the complete version.
