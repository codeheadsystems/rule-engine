# Rule Engine Design Spec — Java 25 (LTS)

A forward-chaining production rule engine for the JVM: JSON-native facts, a declarative JSON/YAML rule language, and a two-tier split between an immutable compiled rule set and cheap single-writer sessions that makes high-concurrency evaluation the default rather than an afterthought. It keeps the good part of Rete-family engines — indexed, incremental pattern matching — while avoiding the packaging ceremony, embedded scripting languages, and opaque execution model that make existing engines heavy. This document is written to be implemented bottom-up, and specifies v1 completely enough to build from.

---

## Contents

| § | Section | What it settles |
|---|---|---|
| [0](#0-framing) | Framing, invariants, decisions | Why this shape; the three invariants everything depends on |
| [1](#1-scope--non-goals) | Scope & non-goals | What v1 does not do, and what to do instead |
| [2](#2-core-domain-model) | Core domain model | Facts, handles, working memory, constraints, field access |
| [3](#3-matching-algorithm) | Matching algorithm | Node types, indexing, propagation, differential update |
| [4](#4-agenda-conflict-resolution--rule-execution) | Agenda & execution | Conflict resolution, refraction, RHS semantics, the fire loop |
| [5](#5-concurrency-model) | Concurrency | The immutability split, virtual threads, sessions-as-actors, hot reload |
| [6](#6-dsl-jsonyaml) | DSL | Rule file format, operator maps, the CEL escape hatch, compilation |
| [7](#7-observability-diagnostics--determinism) | Observability | Tracing, explainability, the determinism contract, strict mode |
| [8](#8-suggested-module-layout) | Module layout | Artifact boundaries |
| [9](#9-phased-roadmap) | Roadmap | Seven phases (0–6) with exit criteria |
| [10](#10-correctness--performance-checklist) | Checklist | What to audit in every phase |
| [11](#11-design-decisions-resolved) | Decision log | Five decisions, with the rejected options |
| [12](#12-references) | References | Primary sources and library docs |

**If you are implementing this**, read §1 → §2 → §3 → §4, then build Phase 0 (§9) — the naive matcher — before reading anything else. It is the correctness oracle for everything after it, and building it first will teach you more about the semantics than §3 can.

**If you are reviewing the design**, §0's invariants and §11's decision log carry the argument; §1 bounds it.

### Glossary

Rete-family vocabulary, used throughout without further gloss.

| Term | Meaning here |
|---|---|
| **Fact** | One unit of data in working memory: a handle, a type name, and a JSON payload |
| **Working memory** | The per-session set of currently-asserted facts |
| **LHS / RHS** | Left-hand side (a rule's `when:` conditions) / right-hand side (its `then:` actions) |
| **Pattern** | One fact-matching clause in an LHS, bound to an alias (`Order as o`) |
| **Alpha test** | A constraint on a single fact, in isolation (`o.total > 10000`) |
| **Beta test / join** | A constraint relating two facts (`o.customerId == c.id`) |
| **Alpha memory** | The set of facts satisfying a pattern's alpha tests |
| **Beta memory** | The set of partial matches (tuples) that satisfy some prefix of an LHS. Called a *join memory* when referring to the Java type |
| **Tuple** | One partial or complete match: an ordered binding of pattern aliases to fact *handles* — never to `Fact` objects (invariant 3, §3.2.2) |
| **Token** | A tuple in transit through the network, tagged assert or retract |
| **Activation** | A complete match: one rule plus the tuple that satisfied it, eligible to fire |
| **Conflict set** | The activations currently eligible to fire |
| **Agenda** | The structure holding the conflict set and selecting from it — a heap plus two side indexes (§4.3). The two shapes differ in *when* the conflict set is computed (§4.1) |
| **Forward chaining** | Deriving conclusions from asserted facts, firing rules as their conditions become satisfied — as opposed to *backward chaining*, which works from a goal to the facts that would prove it. This engine is forward-chaining only (§1) |
| **Alpha / beta network** | The single-fact half of the node graph (entry, alpha, pattern nodes) / the cross-fact half (join nodes). Node sharing behaves very differently in the two — §6.5 |
| **Truth maintenance** | Retracting conclusions when the facts that justified them go away — including matches justified by an *absence*. A v1 non-goal (§1) |
| **CEP** | Complex Event Processing: sliding windows and event-sequencing operators (`after`, `within`). A v1 non-goal (§1) |
| **Conflict resolution** | Choosing which activation fires next |
| **Salience** | An author-assigned integer priority on a rule |
| **Recency** | How recently the facts behind an activation changed |
| **Refraction** | The guarantee that a rule does not re-fire on facts it has already fired on |
| **Node sharing** | Reusing one network node across every rule that expresses the identical test |
| **Rete / TREAT** | Two matching algorithms: Rete keeps beta memories between fires, TREAT recomputes joins at fire time |

---

## 0. Framing

You know JESS/ILOG (classic Rete, tight and predictable) and find Drools cumbersome. The parts of Drools people usually mean by that: a heavyweight `KieBase`/`KieSession` object graph, MVEL/DRL as a quasi-programming-language DSL that blurs config and code, XML/KJAR packaging ceremony, and an execution model that's hard to reason about under concurrent load. This spec keeps indexed incremental matching while deliberately avoiding those specific pain points:

- **Two-tier immutability split**: a compiled, thread-safe `CompiledRuleSet` shared across everything, and cheap, single-writer `RuleSession`s that hold no shared mutable state. This is the concurrency primitive everything else builds on.
- **Declarative-first DSL** (JSON/YAML, operator maps) with an explicit, opt-in escape hatch for expression logic — not a bespoke scripting language as the default.
- **No mandatory build/packaging layer** — a `CompiledRuleSet` is just an object you get back from a compile call; how you deploy it is your business.

### The three invariants

Stated once here, because most of the design's non-obvious choices exist to preserve one of them.

1. **Nothing in `CompiledRuleSet` mutates after compile.** All per-session mutable state — working memory, alpha and beta memories, indexes, agenda, refraction memory — lives in the session, addressed by node id (§3.2.3). The shared graph holds structure and plans, never data.
2. **Execution is deterministic.** The same rule set, the same facts, inserted in the same order, produce the same firing sequence — on every host, every run. This holds *given* deterministic `callFunction` handlers and no parallel alpha evaluation; §7.3 says what it costs to keep and where it can be lost.
3. **Tuples bind handles, never facts.** A tuple binds `FactHandle`s; the payload is dereferenced from working memory at read time (§3.2.2). There is exactly one place a payload lives, so nothing downstream of matching can serve a stale one — and the invariant holds regardless of how `update` is implemented (§11.2).

### The network, in one picture

```
   insert(type, payload)
            │
            ▼
      ┌───────────┐   one per fact type
      │ EntryNode │
      └─────┬─────┘
            │  fans out to every alpha chain registered for the type
            ▼
      ┌───────────┐   one per DISTINCT constraint, shared across rules
      │ AlphaNode │──▶ AlphaNode ──▶ …          (§3.2.1, sharing: §6.5)
      └─────┬─────┘
            │
            ▼
      ┌─────────────┐  one per PATTERN — holds the conjunction of that
      │ PatternNode │  pattern's alpha tests. This is the alpha memory
      └─────┬───────┘  TREAT probes and Rete joins against. (§3.2.4)
            │
            ▼
      ┌──────────┐   cross-fact join; indexed probe, not a cross product
      │ JoinNode │──▶ JoinNode ──▶ …            (§3.3)
      └─────┬────┘
            │
            ▼
      ┌──────────────┐   one per rule; reaching it = a complete match
      │ TerminalNode │
      └─────┬────────┘
            │
            ▼
      Agenda ──▶ conflict resolution ──▶ fire RHS ──▶ back to insert/update/retract
```

Everything in that diagram lives in the shared `CompiledRuleSet`. Everything those nodes *store* lives in the session, in a `NodeMemory[]` indexed by node id.

### Decisions at a glance

The five decisions the rest of the document assumes. Each is argued in full, with its rejected alternatives, in §11 — read that section when you want to know why, not what.

| Decision | Choice | Where |
|---|---|---|
| Session lifetime profile | One-shot/batch first, with TREAT joins; streaming sessions with Rete joins in Phase 3 | §11.1 |
| `update` semantics | Retract + reassert on the same handle, gated on a tested-path diff | §11.2 |
| RHS vocabulary | Fixed closed set of five verbs; `callFunction` is the only escape | §11.3 |
| Fact identity | Session-scoped `long`; the globally-unique id lives on the session | §11.4 |
| Agenda construction | TREAT-shaped conflict set only in v1; the Rete shape arrives in Phase 3 with the beta memory it needs | §11.5 |

---

## 1. Scope & non-goals

Read this before §2. Two of these bullets — collection flattening and negation — change how you model your data at ingestion, which is a decision you make on day one and pay for indefinitely if you make it wrong.

**v1 delivers:** forward-chaining evaluation, single-fact and multi-fact (join) patterns, indexed matching, updates that skip the network entirely when nothing tested changed, refraction, salience/recency conflict resolution, a JSON/YAML rule DSL with an optional CEL escape hatch, one-shot/batch sessions with high concurrency, and the observability described in §7.

**v1 does not deliver the following.** Each is deferred deliberately; the interfaces leave room, and each has a workable interim answer.

- **Collection matching inside a fact.** `JsonPointer` (RFC 6901) has no wildcard, so "any line item with `qty > 10`" is inexpressible against a nested array — and this matters *more* with a JSON-native fact model than it would with POJOs, because deeply nested arrays are what JSON payloads actually look like.

  **The supported answer, which you should design your ingestion around from the start:** flatten collections into separate facts. An `Order` with an `items[]` array becomes one `Order` fact plus N `LineItem` facts carrying `orderId`, joined normally. This is not merely a workaround — it is how you get indexing and incremental matching over collection elements at all. Retrofitting it later means rewriting every rule that touches a collection, so decide now.

- **Negation and quantified patterns (`NOT_EXISTS`, `FOR_ALL`).** "No `Payment` exists for this `Order`" is in the first ten rules most people write, so this is a deferral to be up-front about. It is deferred because negation is genuinely harder than positive matching: a `NotNode` needs per-tuple match *counters*, correct behavior when the count crosses 1↔0 in both directions, and correct interaction with truth maintenance, since a match justified by an absence must be retracted the moment the absence ends. §2.5's `Quantifier` reserves the syntax and §3.2's `NetworkNode` reserves the node type. **Interim answer:** compute the absence at ingestion and insert an explicit marker fact (`OrderUnpaid`) — less elegant, but the logic sits somewhere you can unit-test directly. See the amendment below: `NOT_EXISTS` is built, and none of the machinery this paragraph prices was needed.

- **Accumulation / aggregation (`ACCUMULATE`).** `sum`, `count`, `collect`, `average` over matching facts. Same reasoning — incremental aggregate maintenance under retract is its own correctness problem. **Interim answer:** aggregate at ingestion, insert the aggregate as a fact.

- **Backward chaining.** Forward-chaining only.

- **Full truth maintenance** (logical retract cascades, justification graphs). Land plain assert/retract first. Note the ordering dependency: truth maintenance and negation want to land together, since the strongest motivation for the former is retracting matches justified by an absence.

- **Temporal/CEP operators** (sliding windows, `after`/`within` sequencing). Leave room in `JoinConstraint`/`Operator`; don't build now. Time-windowed *eviction* is a separate concern from temporal *operators*, and §4.4's note on session growth surfaces argues the eviction half is needed sooner than this bullet implies — it is what bounds a long-lived session's memory, with or without temporal operators.

- **Distributed evaluation across machines.** The immutability split in §5 makes this feasible later — a `CompiledRuleSet` is trivially shippable to other JVMs, sessions are cheap to spin up anywhere, and §2.1's `(sessionId, handle)` pair is already the identity you would need — but it is out of scope here.

> **Amendment (Phase 6, first slice, as built).** **`NOT_EXISTS` is implemented.** `FOR_ALL`, `ACCUMULATE`, backward chaining, truth maintenance and the temporal operators are not, and the bullets above stand for them unchanged — the marker-fact interim answer included. What follows is only about negation, and it is worth reading against the bullet above rather than instead of it, because the bullet priced a feature that was not the one built.
>
> **None of the machinery the bullet names was needed, and that is a property of negation rather than a shortcut.** A negated pattern binds no alias and contributes no tuple position, so the predicate it computes is a function of a *complete* tuple and working memory — the same shape a §6.4 `condition` has. A `NotNode` placed in a join chain computes that same predicate over a *prefix*, and a prefix's bindings survive into the complete tuple, so the two answers coincide: placement is a pruning optimisation, not a semantics choice. Negation is therefore answered in `RecomputingAgenda`, the shared base where §4.1's selection logic already lives, and the naive oracle and both networks cannot disagree about it. A `NotNode` would be faster and would have to be written twice, against exactly the semantics where divergence is most likely and hardest to detect.
>
> **The per-tuple counters are replaced by dirty tracking.** The 1↔0 transitions the bullet worries about fall out of §4.1's existing mechanism: the compiled rule's fact types include the negated ones, so a `Payment` arriving or leaving marks the rule dirty and the absence is asked again. Leaving that out would have been the defect hardest to find — the rule would go on firing on an absence that had ended, silently and for as long as the session lived.
>
> **Positions are assigned to positive patterns only**, which is what keeps negation out of the rest of the engine. The join planner, the join walk, the streaming matcher's pattern sites and §7.2's explainer all read the pattern list, which holds only patterns that produce bindings. Negations compile against those same positions, so a negation may reference any bound alias in either direction of declaration, and a negated pattern of a type the rule already binds carries this section's implicit inequality — "no *other* order for this customer" is what an author means.
>
> **Two boundaries ship with it, and the second is not covered by this section's licence to defer truth maintenance.** There is no truth maintenance: a rule that fired because something was absent is not undone when that thing arrives, since refraction keys on the facts the match binds and the absent one is not among them. And **a negated type must not be one a session evicts** (§4.4): an evicted fact and an absent fact are indistinguishable to a negation, so a cap on `Payment` makes the engine announce that a paid order is unpaid. Everywhere else eviction can only cost a firing; here it manufactures a false conclusion, which is a different kind of wrong from a conclusion left unwithdrawn.
>
> **The ordering dependency this section states did not hold.** "Truth maintenance and negation want to land together" is the bullet on full truth maintenance, and negation landed alone. The cost is the first boundary above, and it is a real one — but pairing them would have deferred negation indefinitely behind a justification graph, for a feature §1 itself calls one of the first ten rules most people write.
>
> **The explainer gap is closed, and how it was closed is the point.** §7.2's `MatchExplainer` walks the positive pattern list, so for a while it could not see negations at all: a rule suppressed because the fact whose absence it asserts is *present* was reported as having eligible matches — the opposite of the truth, with the offending fact named nowhere. §7.2's whole claim is that it answers "why did R *not* fire" better than a trace can, and it was failing that on precisely the rules this section calls the first ten most people write.
>
> It now evaluates negations against each complete tuple, **before** the §6.4 conditions, which is the order the agenda applies them in — a tuple an absence defeats is never offered to a condition at run time, so counting it as condition-rejected would report a filtering the engine never performed. The predicate is not re-implemented: `Negations.witness` is extracted to `-core` and is the same code `RecomputingAgenda` decides with. **Sharing it is the requirement rather than the convenience.** A diagnostic that disagrees with the engine it is diagnosing is worse than one that says nothing, because it sends an author to fix a rule that is already correct — and negation is exactly where a second copy would drift unnoticed, which is the argument this amendment already made against writing a `NotNode` twice.
>
> It answers with the *witness* rather than a boolean, which costs the agenda nothing and is the whole value to the explainer: "some `Payment` exists" says the rule is suppressed, "fact #7" says what to go and look at. Negations are reported in their own list rather than folded into the pattern results, because a negated pattern binds nothing and so has neither the candidate population nor the survivors a `PatternResult` exists to carry — a "3 considered, 3 matched" line against a pattern asserting that nothing matches reads as success at the moment it means failure.
>
> **One limit is not closed and cannot be by this route:** over a type the session evicts, an evicted fact and an absent one remain indistinguishable, so the explainer explains exactly what the engine does — which is the wrong answer, identically arrived at. That is the eviction boundary above, not a defect in the diagnostic. What it can do is say so, and it does: a rule that *matched* while a type it negates was being evicted carries a warning naming the count. This is the one place §4.4's eviction clause belongs on a verdict announcing success rather than on a silent rule, and the inversion is the point — everywhere else eviction costs a firing, so a clause on every explanation of every capped session would stop being read; over a negated type it manufactures one, so the danger is precisely when the rule matched.

**One open modeling question v1 must answer explicitly**, because it comes up in week one: when a rule has two patterns of the same fact type (`Order as o1`, `Order as o2`), may one fact bind both aliases? **No.** Distinct aliases in one rule bind distinct facts; the compiler inserts an implicit inequality between same-type aliases. This matches what rule authors expect ("find two different orders…"), differs from OPS5, and must be documented prominently because the other reading is defensible and silently produces self-matches.

---

## 2. Core Domain Model

### 2.1 Identity

```java
/** Pure identity. Session-scoped, opaque, cheap. Carries no state that can change. */
public record FactHandle(long id) {}
```

A handle is identity and nothing else: a dense `long`, unique within one session, allocated from a plain counter on `WorkingMemory` (no atomics — a session is single-writer per §5.1). It is stable for the life of the fact. `update()` never invalidates a handle, which is the whole reason §11.2 rejected copy-on-write.

**Two things are deliberately not in the handle.**

*Recency is not a handle field.* Recency is mutable — it advances when an update changes a path the network tests (§2.4) — so a handle containing it would either go stale after an update or could never be bumped. It lives on `Fact` (§2.2), owned by working memory.

*There is no global UUID.* A handle is meaningful only relative to its session, and that is enough: the engine never compares handles across sessions. Global identity is a boundary concern, handled once by the session.

**Why a `long` rather than a UUID.** `FactHandle` is the hottest key in the engine — every index bucket, join memory, tuple, refraction entry, and the handle→activation reverse index (§4.3) is keyed on it. A 64-bit key hashes in one operation and keeps index buckets cache-dense; a 128-bit UUID doubles the key width, costs a two-word `hashCode`/`equals` on every probe, and pulls in a generator dependency — all to buy cross-session uniqueness nothing in v1 consumes.

**To actually collect that benefit, key internal structures on the raw `long`.** `FactHandle` is a record, so it is itself a heap object; storing `Set<FactHandle>` in every index bucket gives you an allocation and a pointer chase per entry and forecloses primitive-keyed collections. Internally — index buckets, node memories, tuple storage, the reverse indexes — store `long` ids and use primitive-keyed maps (`fastutil`'s `Long2ObjectOpenHashMap`, `LongLinkedOpenHashSet`) where profiling justifies the dependency. Materialize `FactHandle` at the public API boundary only. Code in this document writes `FactHandle` for readability; read it as "the handle's `long`" anywhere it appears inside a memory or index.

**Do not index node memories by handle id as a bare array.** Dense ids tempt you into `Fact[] byId` — O(1), no hashing — and that works exactly until the first retract. Under churn the array is sparse and grows monotonically with total inserts ever made, which is an unbounded leak in precisely the long-lived streaming session of §11.1's option B. Use a primitive-keyed map. Array indexing is defensible only for a session you know to be insert-only and short-lived, and it is not worth the special case.

**Global correlation, where you need it.** The session carries the globally-unique id, once:

```java
public interface RuleSession extends AutoCloseable {
    UUID sessionId();   // UUIDv7 — one per session, not one per fact
    // ...
}
```

Anything leaving the engine — a log line, an emitted event (§4.6), a trace record (§7.1), an exported fact — is stamped `(sessionId, handle.id())`. That pair is globally unique, sorts by session creation time, and costs 16 bytes *per session* rather than per fact. If you later shard sessions across machines (§1), it is already the distributed identity you need. Generating it is a session-construction cost, not a hot-path one; see §12 for the library and the JDK 26 note.

**Ordering: `recency`.** A per-session monotonic `long` counter, bumped on insert and on any update that changes a path the network tests (§2.4 defines "tested"; this is the only definition — a fact whose untested fields churn never becomes "fresher" for conflict resolution, which is intended). It gives exact, gap-free ordering for conflict resolution (§4.2) and staleness checks.

Three jobs, three mechanisms: the handle gives identity, `recency` gives order, the session UUID gives global correlation. Don't conflate them.

### 2.2 Facts — first-class JSON, not POJOs

```java
public final class Fact {
    private final FactHandle handle;
    private final String type;
    private final JsonNode payload;   // engine-owned; see the ownership rules below
    private final long recency;       // §2.1 — advances on every effective update

    public FactHandle handle()  { return handle; }
    public String     type()    { return type; }
    public long       recency() { return recency; }
    public JsonNode   payload() { return payload; }

    // Identity is the handle, and only the handle — NOT the payload.
    @Override public boolean equals(Object o) {
        return o instanceof Fact f && f.handle.equals(handle);
    }
    @Override public int hashCode() { return handle.hashCode(); }
}
```

**Why a class and not a record.** A record's generated `equals`/`hashCode` would cover `payload`, making every comparison a deep `JsonNode` tree walk — catastrophic the moment a `Fact` lands in a set or a map key, and semantically wrong besides, since two facts with identical content are still two facts. Identity is the handle. `Activation` (§4.2) is a class for the same reason plus one more.

**`recency` lives here.** Working memory owns it. Because `Fact` is replaced wholesale on update, a `Fact` object is an immutable snapshot at one recency — which is exactly why tuples bind handles and dereference through working memory rather than holding `Fact` references (§3.2.2).

The payload's canonical type is Jackson's [`JsonNode`](https://www.javadoc.io/doc/tools.jackson.core/jackson-databind/latest/tools/jackson/databind/JsonNode.html) — not `Object`, not a `FactAdapter` SPI over multiple representations. Committing to one representation means the matching network, field access (§2.6), and the DSL (§6) all speak the same tree model, with no adapter indirection and no "which kind is this fact" branching in the hot path. A fact's origin becomes irrelevant to how it is matched: parsed JSON, parsed YAML (same Jackson tree model — §6.1), or built with `JsonNodeFactory` all produce the identical shape in working memory.

**Payload ownership, and the cost of defending it.** Get this wrong and index entries point at values the fact no longer has: silent wrong matches, not a crash. `ObjectNode`/`ArrayNode` are mutable containers, and Jackson has no immutable tree type. If code outside the engine mutates a payload after insert, it breaks the assumption that a fact's content changes only through explicit `update()` — an assumption §2.6's accessor caching, §3.3's index maintenance, and §3.4's differential propagation all depend on.

The rules, in full:

- **`insert` and `update` both deep-copy by default.** Not just insert. An `update` that aliases the stored payload is reachable entirely through supported API — `get(h).payload()` returns the live node, mutate it, pass it back — and it breaks §3.4.1 in *two* places, either of which is fatal on its own. The diff (step 1) compares an object against itself, finds nothing changed, and returns without touching the network. And even if the diff were bypassed, the retract half (step 3) computes index-removal keys from a payload that has already become the new one, so the handle is never removed from its old bucket and the orphaned entry produces phantom matches indefinitely. Copying on `update` closes both.
- **`insertOwned` / `updateOwned` skip the copy** under a documented contract: the caller transfers ownership and must never touch that `JsonNode` again. Correct at an ingestion boundary where the tree was just parsed from bytes and nobody else holds a reference — a common case where the copy is pure waste.
- **In strict mode (§7.5), `update` rejects a payload that is reference-identical to, or shares any subtree with, the stored payload.** This is the aliasing bug above, caught deterministically in test rather than discovered as a wrong decision in production.
- **`Fact.payload()` returns the live node.** There is no cheap read-only wrapper in Jackson, so this is a documented contract, not an enforced one: the returned node is engine-owned and read-only. Strict mode hands out a `deepCopy()` here instead and lets integration tests catch violators.
- **The RHS `setField` action (§4.6) is the only supported way to change a fact's content**, and it routes through `update()`.

Note that this argument does not depend on how `update` propagates — it survived the §11.2 reversal unchanged, because the second failure above is a property of retract-by-old-key, which every implementation of `update` has. Be honest about the cost: `deepCopy()` is `O(payload size)` on every insert and every update, and for large payloads at high rates it is frequently the largest single per-operation cost in the engine — larger than the alpha tests it protects. That is what `insertOwned`/`updateOwned` are for.

**Type discriminator.** `insert()` takes the type explicitly rather than inferring it from the payload's shape or a magic `$type` field. Inferring from structure is a footgun at scale (ambiguous or colliding shapes), and a magic field couples the DSL's `fact: Order` to the payload's contents. If facts arrive as raw text, parsing is a one-line Jackson call at the ingestion boundary, not something `WorkingMemory` needs to know about.

**If your host application has POJOs already:** convert at the boundary with `objectMapper.valueToTree(pojo)`. POJO-to-JSON conversion is the caller's concern.

### 2.3 Optional schema binding

The core model is intentionally schema-agnostic: nothing requires a fact type's shape to be declared, and v1 primitives work with zero schema definitions. Since Jackson is already a dependency and JSON Schema is already planned for validating rule *files* (§6.5), letting it optionally validate fact *payloads* is a small additive step — an opt-in layer, not a requirement.

```java
/** Immutable once built. Frozen into the CompiledRuleSet. */
public interface SchemaRegistry {
    Optional<JsonSchema> schemaFor(String factType);   // unregistered types stay unstructured

    static Builder builder() { return new Builder(); }

    final class Builder {
        public Builder register(String factType, JsonSchema schema) { /* ... */ return this; }
        public SchemaRegistry build() { /* defensive copy into an immutable map */ }
    }
}
```

- No registered schema: `insert`/`update` behave exactly as in §2.2 — no validation, arbitrary shape. This is the default and requires no setup.
- A registered schema: `insert`/`update` validate before the fact enters the network — fail fast on a malformed fact rather than letting it silently not-match every rule expecting a field it lacks, which is a much harder bug to spot.
- A registered schema is also where a class of authoring mistakes gets caught at *rule-compile* time rather than *fact-insert* time. If `Order.total` is `"type": "number"`, a constraint `{ gt: "expensive" }` is a compile error in §6.5's pipeline instead of a rule that silently never matches. This is the single strongest argument for registering schemas on your important fact types.

**Note the builder.** A `SchemaRegistry` is referenced by the `CompiledRuleSet` and therefore by every running session; a mutable `register(...)` on the shared object would violate invariant 1 — a race between one thread registering and thousands reading, with no barrier and no defined ordering. Build it, freeze it, hand it to the compiler. Changing schemas means recompiling, which is the same operation as changing rules and is handled the same way (§5.6). `JsonSchema` above is [`com.networknt:json-schema-validator`](https://github.com/networknt/json-schema-validator)'s type.

Build the unstructured path first (Phases 0–2 in §9); add `SchemaRegistry` with the Phase 5 DSL work, since it shares infrastructure with rule-file validation.

### 2.4 Working memory

```java
public interface WorkingMemory {
    /** Deep-copies (§2.2). Throws if a registered schema rejects the payload. */
    FactHandle insert(String type, JsonNode payload);
    /** Ownership transfer: no copy. Caller must never touch `payload` again. */
    FactHandle insertOwned(String type, JsonNode payload);

    /** Deep-copies. Differential propagation (§3.4). The handle stays valid and identical;
     *  recency advances iff at least one tested path actually changed. */
    void update(FactHandle handle, JsonNode newPayload);
    void updateOwned(FactHandle handle, JsonNode newPayload);

    void retract(FactHandle handle);
    Optional<Fact> get(FactHandle handle);

    /** SNAPSHOT, not a live view — safe to consume while an RHS mutates working memory.
     *  Iterates in ascending handle id, i.e. insertion order. */
    Stream<Fact> factsOfType(String type);

    int size();   // for the maxFacts bound, §4.7
}
```

**`factsOfType` returns a snapshot, deliberately.** Streaming straight off the backing collection throws `ConcurrentModificationException` the moment an RHS inserts or retracts while iterating — not an exotic case but the normal one, since `then:` blocks routinely insert derived facts while a `callFunction` walks the same type. Materialize the handle list up front and dereference lazily: facts retracted mid-iteration surface as `Optional.empty()`, facts inserted mid-iteration are invisible to an already-started stream. Put that in the Javadoc; callers will depend on it.

**Ordering is by ascending handle id, not by recency.** Handle ids are allocated in insertion order and never change, so this is stable. Recency would *not* be — an update moves a fact to the end of a recency ordering, so iteration order would silently depend on unrelated update traffic, and maintaining a recency-sorted per-type structure would cost a re-sort on every effective update. This ordering is part of the determinism contract (§7.3).

**Tested paths, per rule and per type.** The compiler produces one artifact that two separate mechanisms consume, so it is defined here once:

```java
/** For each fact type: which JsonPointer paths the network reads, and which rules read them. */
public interface TestedPaths {
    Set<JsonPointer> forType(String factType);              // union across all rules — the diff set
    Set<JsonPointer> forRule(String ruleId, String type);   // just this rule's paths
    Set<String>      rulesTesting(String type, JsonPointer changed);  // inverse index
}
```

The two consumers:

1. **The diff** (§3.4.1 step 1) walks `forType` to decide whether this update needs to touch the network at all.
2. **Refraction invalidation** (§3.4.1 step 5, §4.4) clears a rule's fired-match memory only when a path *that rule* tests changes — which is why `rulesTesting` exists and why a single type-wide set was not enough. Clearing too much makes a rule re-fire on data it already handled, because an unrelated rule's field moved.

Dirty-rule tracking (§4.1) does **not** consume it: a rule is dirty when a fact of a type it patterns is inserted, retracted, or effectively updated, which needs only a `factType → ruleIds` map.

`update` is **retract + reassert on the same handle, gated on the diff**: if no tested path changed it replaces the payload and returns without touching the network; otherwise it runs the ordinary retract and insert paths. Full mechanics in §3.4.1; §11.2 records why this was chosen over differential propagation.

**The stored payload is always replaced, whether or not anything propagates.** Only propagation is conditional; storage never is. Getting this backwards is the most tempting way to reintroduce the stale-payload bug the design exists to prevent.

### 2.5 Rule definition (post-parse, pre-compile)

This is what the DSL compiles *into*.

```java
public record RuleDefinition(
    String id,
    int salience,
    List<PatternDefinition> when,   // LHS: ordered list of fact patterns + joins
    List<ActionDefinition> then,    // RHS: ordered list of declarative actions
    boolean noLoop,                 // suppress re-activation caused by this rule's own RHS (§4.5)
    String agendaGroup,             // optional partitioning of the agenda (§4.5)
    Set<String> tags
) {}

public record PatternDefinition(
    String alias,                   // binding name, e.g. "o"
    String factType,                // "Order"
    Quantifier quantifier,          // EXISTS_AT_LEAST_ONE, or NOT_EXISTS (§1's amendment)
    List<Constraint> constraints
) {}

/** The first two are implemented; FOR_ALL and ACCUMULATE are reserved so adding them later is a
 *  new enum constant plus a new node type, not a reshape of PatternDefinition. §1 states what each
 *  of those two costs and gives an interim answer; its amendment says why NOT_EXISTS needed no
 *  node type at all. */
public enum Quantifier { EXISTS_AT_LEAST_ONE, NOT_EXISTS, FOR_ALL, ACCUMULATE }

public sealed interface Constraint
    permits FieldConstraint, RangeConstraint, JoinConstraint, ExpressionConstraint {}

public record FieldConstraint(String field, Operator op, JsonNode literal) implements Constraint {}

/** BETWEEN and its one-sided forms. Explicit bounds beat overloading `literal` with a 2-array,
 *  and inclusivity has to be expressible — half-open ranges are the common case in practice. */
public record RangeConstraint(
    String field,
    Optional<JsonNode> lower, boolean lowerInclusive,
    Optional<JsonNode> upper, boolean upperInclusive
) implements Constraint {}

public record JoinConstraint(String field, String otherAlias, String otherField, Operator op)
    implements Constraint {}

/** The CEL escape hatch (§6.4). Opaque to the indexer — an unindexed postFilter (§6.4). */
public record ExpressionConstraint(String expression, Set<String> referencedAliases)
    implements Constraint {}

public enum Operator {
    EQ, NE, GT, GTE, LT, LTE,
    IN, NOT_IN,
    MATCHES,          // regex — §2.6.3 on RE2 and precompilation
    HAS_FIELD,        // field-presence on ONE fact; `literal` is a BooleanNode giving polarity
    IS_NULL           // explicit JSON null, as distinct from absent (§2.6.1); `literal` is a
                      // BooleanNode giving polarity, exactly as HAS_FIELD
}
```

`ActionDefinition`, the RHS half of `RuleDefinition`, is the closed set §11.3 decided on — five verbs, sealed so the compiler can check the set is exhaustive and §7.4's report can enumerate every external call surface:

```java
/** The RHS vocabulary. Sealed: §11.3 chose a fixed closed set, and adding a verb is a
 *  deliberate, reviewable change to the safety properties of the DSL — not an extension point. */
public sealed interface ActionDefinition
    permits SetField, InsertFact, RetractFact, Emit, CallFunction {}

/** target: an alias bound by the LHS. value: a literal, or a $ref resolved at fire time (§6.2). */
public record SetField(String targetAlias, String field, ValueExpr value) implements ActionDefinition {}
public record InsertFact(String factType, Map<String, ValueExpr> payload) implements ActionDefinition {}
public record RetractFact(String targetAlias) implements ActionDefinition {}
public record Emit(String eventType, Map<String, ValueExpr> payload) implements ActionDefinition {}
/** name resolves against the session's registered functions; unknown names are a compile error. */
public record CallFunction(String name, Map<String, ValueExpr> args) implements ActionDefinition {}

/** A value in a `then` block: either a constant or a reference to a bound fact's field.
 *  §6.2 notes the two resolve at different times — a `where` $ref at compile time against the
 *  join graph, a `then` $ref at FIRE time against the tuple. This type is the fire-time half. */
public sealed interface ValueExpr permits Literal, FieldRef {}
public record Literal(JsonNode value) implements ValueExpr {}
public record FieldRef(String alias, JsonPointer path) implements ValueExpr {}
```

**A `CallFunction`'s arguments are resolved to values before the handler sees them**, and those values are deep-copied. Handing a handler the live `JsonNode` from working memory would put a hole the size of the escape hatch in §2.2's ownership contract: the handler is arbitrary host Java, it is under no obligation not to mutate what it is given, and a mutation there bypasses `update()` and leaves every index stale. Resolve, copy, dispatch.

Keep this layer DSL-agnostic — a second front-end (a text DSL, say) could be bolted on later without touching anything below this line.

**`HAS_FIELD`, not `EXISTS`.** `HAS_FIELD` asks whether *this fact* has a value at this path — a single-fact alpha test. It has nothing to do with existential quantification over a *pattern* ("does any `Payment` exist for this `Order`"), which is `Quantifier`'s job and a v1 non-goal (§1). Two very different features; don't give them one word. Polarity is carried in `literal` (`{ hasField: false }` means "absent"), so a single operator covers both directions.

**`IS_NULL` carries polarity the same way, and inherits the same asymmetry as `NE`.** `{ isNull: false }` is the negation of the predicate, not "present and not null" — so it matches an **absent** field as readily as a present non-null one, for the same reason §2.6.1 gives for `{ ne: "CLOSED" }` matching an `Order` with no `status`. When you mean "present and not null," write `{ hasField: true }` alongside it. The compiler issues §2.6.1's optional-path warning for `{ isNull: false }` too.

**`BETWEEN` is a separate constraint type**, not an `Operator`, because `FieldConstraint(field, op, literal)` cannot express two bounds and their inclusivity without overloading `literal` into a positional array — an encoding that then has to be documented, validated, and remembered. `RangeConstraint` also unifies one-sided ranges, so `GT`/`LT` on an indexed path compile into the same structure the sorted index (§3.3) already understands.

**Note the two different Jackson uses, so they don't get conflated.** A *rule file* (a YAML/JSON document with `when:`/`then:` blocks) is parsed by Jackson into these records at compile time — a normal typed POJO binding. A *fact* (§2.2) is a `JsonNode` at runtime, with no POJO binding. `FieldConstraint.literal` being a `JsonNode` is what lets a DSL literal compare directly against a fact's field value (§2.6) with no coercion layer between: the DSL, the constraint AST, and the fact payload all speak one value model end to end.

**On extending the sealed hierarchies.** `Constraint` is sealed over records, which are implicitly final, so that one is complete as written. `NetworkNode` (§3.2) is a different case, discussed there.

### 2.6 Field access

Since the payload is canonically `JsonNode`, field access is JSON Pointer traversal, not reflection avoidance — the concern shifts from "don't call `Method.invoke`" to "don't re-parse a path string on every fact, every cycle."

```java
public interface FieldAccessor {
    JsonNode get(JsonNode payload); // returns MissingNode (not null) if the path isn't present
}

public record JsonPointerAccessor(JsonPointer pointer) implements FieldAccessor {
    public JsonNode get(JsonNode payload) { return payload.at(pointer); }
}
```

At compile time — not at match time — resolve each `(factType, field)` pair into a compiled [`JsonPointer`](https://www.javadoc.io/doc/tools.jackson.core/jackson-core/latest/tools/jackson/core/JsonPointer.html) once: `JsonPointer.compile("/" + field.replace('.', '/'))` turns `customer.id` into `/customer/id`. Reuse it across every session and every fact of that type. Jackson's `JsonPointer` is documented as immutable and shareable, so caching in the `CompiledRuleSet` needs no synchronization.

Two things worth being explicit about, since this is a different performance profile than a POJO + `MethodHandle` design:

- **Traversal cost is real.** Each path segment is a hash lookup into the backing `ObjectNode` (or an index into an `ArrayNode`) — `O(depth)` lookups per field read, not an inlined field access the JIT can collapse the way a compiled `MethodHandle` accessor can. This is the price of a tree-native representation (§2.2); the right trade for DSL-first, schema-flexible facts, but not free, and worth remembering if one alpha test lands on a very hot path.
- **Constraint evaluation must be node-type-aware.** A `JsonNode` value is one of a handful of concrete types (`TextNode`, `IntNode`, `DoubleNode`, `BooleanNode`, `NullNode`, `MissingNode`, …), and a literal must compare against the right one — never via generic `.equals()`. §2.6.1 gives the comparison matrix; §2.6.2 covers numeric canonicalization, which is sharper than it looks.

#### 2.6.1 Comparison semantics

"Why didn't my rule fire?" is the most common question a rule engine gets, and the answer is almost always an undefined comparison edge case. Define them once, and make the compiler enforce what it can.

**Absent and null are different values.** Jackson gives `MissingNode` for "the path isn't there" and `NullNode` for `"field": null`. Both look falsy; conflating them makes `{ eq: null }` mean different things depending on how the producer serialized.

| Fact value at path | `hasField: true` | `isNull` | `{ eq: null }` | `{ eq: <non-null> }` | `{ ne: null }` | `{ ne: <non-null> }` | range ops | `matches` | `in [...]` |
|---|---|---|---|---|---|---|---|---|---|
| absent (`MissingNode`) | **false** | **false** | **false** | false | **true** | **true** | **false** | false | false |
| explicit `null` (`NullNode`) | **true** | **true** | **true** | false | **false** | **true** | **false** | false | **per element** |
| present, comparable | true | false | false | per value | true | per value | per value | per value | per value |
| present, wrong type (see below) | true | false | false | **false** | **true** | **true** | **false** | false | false |

Note the `ne` split into two columns: `NE` is defined as `!EQ`, so `{ ne: null }` against an explicit `null` is **false**. Collapsing them into one column gets that cell wrong.

Note also the `in [...]` cell on the null row. **`IN` is defined as `EQ` against each element**, so `{ in: [null] }` matches an explicit `null` exactly as `{ eq: null }` does, and `{ in: ["A", "B"] }` does not. Defining membership any other way — carving null out of it, so that the cell read a flat `false` — would make `IN` disagree with the `EQ` it is built from and would stop `NOT_IN` being `!IN`, which is the property the two `notIn` cells in the absent and wrong-type rows depend on.

Three consequences worth stating out loud:

- **`eq: null` matches only an explicit JSON null, never an absent field.** Use `{ hasField: false }` for "the field isn't there" and `isNull` for "it's there and it's null." This is the opposite of JavaScript-shaped intuition, and it is the right choice: an engine that cannot distinguish "unknown" from "known to be nothing" cannot express half the rules people need.
- **Logic is two-valued, not three-valued.** A constraint against an absent or wrong-typed value is `false` — with the deliberate exception of `NE`/`NOT_IN`, which are `!EQ`/`!IN` and therefore **true** for absent fields. That exception is a genuine trap (`status: { ne: "CLOSED" }` matches an `Order` with no `status` at all), so the compiler warns whenever `NE`/`NOT_IN` is applied to a path a registered schema (§2.3) marks optional, suggesting the explicit `{ hasField: true }` companion. Three-valued logic was considered and rejected: it doubles every truth table and forces authors to reason about `UNKNOWN` propagation through `AND` — a larger cognitive cost than one documented asymmetry.
- **Cross-type comparison is `false` at runtime, but a compile error wherever a schema can prove it.** Runtime leniency is necessary because unstructured facts are the default (§2.3); compile-time strictness is what makes the leniency safe where you have declared a schema.

**Type-compatibility classes** for the "wrong type" row: `{number}`, `{string}`, `{boolean}`, `{array}`, `{object}`. Comparison is defined *within* a class only. Two exceptions: `IN`/`NOT_IN` compare a scalar against array *elements* (an array literal is expected, not a mismatch), and `EQ` on two `object`/`array` values is structural — object key order does not matter, array element order does, and **numbers inside a container compare exactly as they do outside one**.

> **Amendment (Jackson 3 migration).** This sentence read "is Jackson's structural `equals`" until the engine moved to Jackson 3, and delegating to a library is only safe while the library agrees with you. Jackson's node equality is *representation* equality — it distinguishes `IntNode` from `DoubleNode` from `DecimalNode`, and (from Jackson 3) one `DecimalNode` scale from another. This section puts all of them in one `{number}` class, so the two definitions were never the same thing; they merely agreed often enough for the difference to stay hidden.
>
> Three concrete consequences, all of which the container path now answers the same way the scalar path always did:
>
> | | before | now |
> |---|---|---|
> | `{a: 100.00}` vs `{a: 100.0}` | unequal *(Jackson 3 only)* | equal |
> | `{a: 1}` vs `{a: 1.0}`, straight from `readTree` | unequal *(Jackson 2 as well)* | equal |
> | `{a: NaN}` vs `{a: NaN}` | equal | unequal |
>
> Only the first was a Jackson 3 regression. The second is older and had nothing to do with the migration — `1` and `1.0` were already equal as scalars and unequal inside a container, and it needs no `BigDecimal` to reach, just ordinary parsed JSON. The migration exposed a narrow slice of a wider inconsistency and both are fixed together.
>
> **Note the direction: this widens what matches**, which is the outcome this section's design is otherwise built to avoid, so it is stated rather than left to be discovered. `Comparisons` walks containers itself and compares numbers through `Canonical` at every depth; `IN`/`NOT_IN` inherit it, being `EQ` against each element. Object key order still does not matter and array element order still does. See `ReviewRegressionTest.ContainerNumericEquality`.

#### 2.6.2 Numeric canonicalization

JSON number encoding is source-dependent: `10000` parses as `IntNode`, `10000.0` as `DoubleNode`, `1e4` as `DoubleNode`. An author writing `{ eq: 10000 }` means all three, so canonicalize through `JsonNode.decimalValue()` (`BigDecimal`) rather than comparing node types.

**But `BigDecimal.equals` is scale-sensitive, and that breaks hash indexing:**

```java
new BigDecimal("10000").equals(new BigDecimal("10000.0"))     // false  — different scale!
new BigDecimal("10000").compareTo(new BigDecimal("10000.0"))  // 0      — numerically equal
```

Since `equals` and `hashCode` move together, `10000` and `10000.0` land in **different buckets** — exactly the bug canonicalization was meant to fix, failing silently as a rule that never matches. Two rules, neither optional:

- **Equality and hashing** (alpha tests, `EQ`/`IN`, hash index keys, §3.3): canonicalize with `stripTrailingZeros()` before the value is ever used as a key. `new BigDecimal("10000").stripTrailingZeros()` and `new BigDecimal("10000.0").stripTrailingZeros()` both yield `1E+4`, which is `equals`-equal with a matching `hashCode`. Do this once, at index-insert and constraint-compile time — never per probe.
- **Ordering** (`RangeConstraint`, sorted indexes): use `compareTo`, never `equals`. A `TreeMap<BigDecimal, …>` uses `compareTo` and is therefore already correct — and note the deliberate `compareTo`/`equals` inconsistency, which is harmless for a `TreeMap` but must not be mixed with the hash path. Do **not** apply `stripTrailingZeros` on the sorted path; it is unnecessary there and obscures the intent.

**Canonicalization must also produce one Java type per compatibility class**, with the same force as the scale rule. An index keyed on `Object` will happily hold both `TextNode("A")` and `String("A")`, which are not `equals` — and then a probe built one way misses an entry stored the other. Canonicalize to `String`, `BigDecimal`, and `Boolean`; never store a `JsonNode` as an index key.

One residual edge: `DoubleNode.decimalValue()` returns `BigDecimal.valueOf(double)`, which round-trips through `Double.toString` and gives the short, human-expected decimal — the behavior you want. But a value produced by upstream floating-point arithmetic (`0.1 + 0.2` → `0.30000000000000004`) will not equal a literal `0.3`, and no engine-side canonicalization can fix that. Document it: exact equality on floating-point fields is a rule-authoring smell; ranges are the correct tool.

#### 2.6.3 `MATCHES`: precompile, and prefer RE2

- **Precompile at rule-compile time.** A `Pattern` or RE2 program is compiled once in §6.5's pipeline and cached in the `CompiledRuleSet` exactly like a `JsonPointer` — never per fact per cycle. `java.util.regex.Pattern` is immutable and thread-safe; `Matcher` is not, so allocate one per evaluation (cheap) and never cache it across sessions.
- **Use [`com.google.re2j:re2j`](https://github.com/google/re2j) for rule-authored patterns.** `java.util.regex` backtracks: a rule file containing `{ matches: "(a+)+$" }` against a moderately long non-matching string takes exponential time and pins a carrier thread until it finishes — a rule *file*, reviewed as config, taking down the service. RE2 guarantees linear time in the input and cannot backtrack catastrophically.

  The trade is real and worth stating: RE2 gives up backreferences and lookaround, and on *ordinary* patterns it is typically somewhat **slower** than `java.util.regex`, whose backtracking engine is well optimized for the common case. You are buying a bounded worst case with a slightly worse average — the same trade §6.4 makes in choosing CEL over MVEL, for the same reason: predictability matters more than peak speed in something a non-engineer may edit. If you keep `java.util.regex` for compatibility, `MATCHES` needs a wall-clock guard and §6.3's safety claim needs softening accordingly.

---

## 3. Matching Algorithm

### 3.1 Which algorithm, and why

Three real options:

| Approach | Memory | CPU per insert | Best for |
|---|---|---|---|
| **Naive re-scan** (no network) | O(1) | O(rules × facts) | Correctness baseline only |
| **TREAT-style** (indexed alpha memories, no persistent beta memory — recompute joins on demand) | Low | Lower per-insert, higher per-fire | Short-lived / stateless sessions, high fact churn, bursty batch evaluation |
| **Rete-style** (indexed alpha + persistent incremental beta memory) | Higher | Proportional to partial matches created | Long-lived sessions with continuous streaming inserts and many multi-fact joins |

A note on the Rete row, because the usual shorthand is misleading: Rete's per-insert cost is *not* `O(Δfacts)`. It is proportional to the number of partial matches the new fact creates, which is combinatorial in join arity and working-memory size — this is precisely why Rete blows up on cross-products and why join indexing (§3.3) is not optional. What Rete buys is that the work is done once per fact rather than once per fire cycle.

Recommendation: **build one shared node-graph abstraction and make beta-memory persistence a per-`JoinNode` strategy.** You don't have to pick once — a session evaluating a batch and discarding it wants TREAT semantics; a long-running session watching a fact stream wants Rete's incremental joins. Both share the node types below; only the `JoinNode` implementation differs (stateless probe-and-recompute vs. maintained left/right memories), and — as a direct consequence that is easy to miss — so does the way the agenda's conflict set is built, which §4.1 specifies.

Primary sources, in reading order:

1. C.L. Forgy, ["Rete: A Fast Algorithm for the Many Pattern/Many Object Pattern Match Problem"](https://doi.org/10.1016/0004-3702(82)90020-0), *Artificial Intelligence* 19(1), 1982, pp. 17–37 — the original algorithm. [Accessible mirror](https://www.csl.sri.com/users/mwfong/public_html/Technical/RETE%20Match%20Algorithm%20-%20Forgy%20OCR.pdf).
2. D.P. Miranker, "TREAT: A Better Match Algorithm for AI Production Systems," 1987 — the no-persistent-beta-memory alternative this spec borrows from. Not freely hosted; Doorenbos summarizes and compares it in his ch. 2.
3. R.B. Doorenbos, ["Production Matching for Large Learning Systems"](https://www.csd.cs.cmu.edu/sites/default/files/phd-thesis/CMU-CS-95-113.pdf), CMU-CS-95-113, 1995 — the most accessible detailed treatment of Rete internals (node sharing, indexing, left/right unlinking).

### 3.2 Node types

#### 3.2.1 Declarations

```java
// Sealed for exhaustiveness inside the engine. §1's deferred AccumulateNode will be added here,
// so downstream code must not rely on exhaustive switches over it. The NotNode this comment also
// reserved was NOT built: negation binds nothing, so it is answered over the complete tuple in the
// shared agenda base rather than as a node -- §1's amendment gives the argument.
// NOTE: every permitted subtype must itself be sealed or non-sealed (JLS 8.1.1.2).
public sealed interface NetworkNode
    permits EntryNode, AlphaNode, PatternNode, JoinNode, TerminalNode {
    int nodeId();   // dense, 0..nodeCount-1, assigned at compile time (§6.5)
}

/** Root per fact-type. Fans out to the alpha chains registered for that type. */
public non-sealed interface EntryNode extends NetworkNode {
    String factType();
}

/** Single-fact test, no cross-fact state. Pure and side-effect free — safe to share
 *  structurally: two rules with an identical FieldConstraint reuse the same instance. */
public non-sealed interface AlphaNode extends NetworkNode {
    boolean test(WorkingMemory wm, FactHandle handle);
}

/** One per PATTERN. Holds the alpha memory: the facts satisfying the conjunction of that
 *  pattern's alpha tests. See §3.2.4 — this node is why constraint-level sharing works. */
public non-sealed interface PatternNode extends NetworkNode {
    String factType();
    String alias();
}

/** Cross-fact join. Left input = partial match from upstream; right input = a PatternNode's
 *  alpha memory. Index keys are canonicalized (§2.6.2) so 10000 and 10000.0 index identically. */
public non-sealed interface JoinNode extends NetworkNode {
    Object leftIndexKey(WorkingMemory wm, Tuple partialMatch);
    Object rightIndexKey(WorkingMemory wm, FactHandle handle);
    boolean postFilter(WorkingMemory wm, Tuple partialMatch, FactHandle handle);
}

/** One per RuleDefinition. Reaching this node = a complete match = an Activation. */
public non-sealed interface TerminalNode extends NetworkNode {
    CompiledRule rule();
}

/** Binds aliases to fact IDENTITIES. Never to Fact objects — §3.2.2. */
public record Tuple(long[] boundFacts, List<String> aliases) {
    // Array component: equals/hashCode MUST be hand-written with Arrays.equals/hashCode.
    // `aliases` is shared per TerminalNode (a property of the rule, not of the match),
    // so per-tuple cost is one long[].
}
```

**Every method takes `WorkingMemory`.** A tuple carries only handles, so any key computation, test, or filter has to dereference — which means a `WorkingMemory` parameter, not a `Fact` parameter. `postFilter` is also where CEL conditions land, and those may read *every* bound alias.

**`Tuple` and `ActivationKey` contain arrays**, and a record's generated `equals`/`hashCode` on an array component are *identity*-based. Both types must hand-write them with `Arrays.equals`/`Arrays.hashCode`, and both must defensively copy on construction — these values are hash keys in the refraction memory (§4.4), and a mutable hash key that something else can modify is a defect waiting for a long-running session.

#### 3.2.2 Tuples bind handles, never facts

This is invariant 3 from §0, and the most important structural decision in this section.

`update()` (§3.4) replaces a fact's payload. If a tuple held `Fact` *objects*, every tuple already in a join memory, an agenda activation, or a refraction entry would still point at the pre-update `Fact`. The consequences:

- an RHS reading a field no rule tests (`{ $ref: o.customerEmail }` in an `emit` payload) reads the **old** value;
- a `postFilter` or CEL condition re-evaluated later reads the **old** value;
- two tuples for the same fact, created either side of an update, disagree about its contents.

Binding handles and dereferencing through `WorkingMemory.get` at read time makes this structurally impossible: there is exactly one place a payload lives, and updates replace it there. The cost is one lookup per field read at fire time — small next to a whole category of silent staleness bugs.

**A worked example, so nobody re-introduces it.** Rules test `Order./status` only. An RHS emits `{"orderId": {"$ref": "o.id"}, "email": {"$ref": "o.customerEmail"}}` (§6.2's reference syntax). A `customerEmail` update touches no tested path. With handle-binding tuples: payload replaced, no propagation, the next `emit` uses the new address. With fact-binding tuples: no propagation, the activation still references the old `Fact`, and the email goes to the previous address for as long as that activation lives. No test that asserts only on *matching* will ever catch this.

The same argument applies to `Activation` (§4.2) and the refraction memory (§4.4): everything downstream of matching is keyed on identity.

#### 3.2.3 Node identity and where mutable state lives

Every node gets a dense `int nodeId` at compile time. This is how invariant 1 is realized:

```java
/** Per-session mutable state for one node, addressed by nodeId. Never touches the shared graph. */
public sealed interface NodeMemory
    permits AlphaMemory, PatternMemory, JoinMemory, TerminalMemory {}

public final class SessionMemories {
    private final NodeMemory[] byNodeId;   // sized at session creation from CompiledRuleSet.nodeCount()
    NodeMemory of(int nodeId) { return byNodeId[nodeId]; }
}
```

§3.3's indexes are maintained incrementally on insert/retract/update — that is per-session mutable state, and it cannot live on a node object thousands of virtual threads share. Neither can alpha memories, beta memories, or join-probe indexes. Without node ids and a session-side memory array, "a shared node graph" and "incrementally maintained indexes" are contradictory claims.

| Lives in `CompiledRuleSet` (shared, immutable) | Lives in `RuleSession` (per-session, mutable) |
|---|---|
| Node objects: structure, wiring, `nodeId`, compiled `JsonPointer`s, RE2 programs, CEL programs, literals | `NodeMemory[]` indexed by `nodeId` |
| The *index plan*: which paths are hash- vs range-indexed, on which node | The *index contents*: canonicalized key → handle sets |
| `TestedPaths` (§2.4), type→rules index (§4.1) | Working memory, agenda, refraction memory, recency counter |
| `CompiledRule`: rule-level tested paths, compiled actions | Fired-activation log, event listeners (§7.1), dirty-rule bitset |

`AlphaNode.test` and `JoinNode.postFilter` are pure functions of their arguments — that is what makes the left column safe to share. **Every method that stores something takes `SessionMemories`**, which is why the propagation interface in §3.4 carries it explicitly.

`NetworkNode` and `RuntimeNode` (§3.4) are two views of one object, not two graphs. `NetworkNode` is the structural face — what does this node test, what does it depend on, what is its id. `RuntimeNode` is the propagation face — given a token, what do you do. All five node types implement both, `EntryNode` included: its propagation face is the fan-out to the alpha chains for its fact type, which is where every insert enters the network.

#### 3.2.4 `PatternNode`: the per-pattern alpha memory

Constraint-level node sharing (§6.5) and TREAT need different things, and reconciling them requires a node type that a plain Rete sketch doesn't have.

Sharing gives one `AlphaNode` per *distinct constraint* across all rules. But TREAT needs, for each pattern, the set of facts satisfying the **conjunction** of that pattern's alpha tests — and with constraint-level sharing no node holds that set. The shared `status EQ "PENDING"` node holds a superset spanning every rule that tests it; intersecting the memories of several shared nodes at fire time would defeat the point.

`PatternNode` is the terminal node of one pattern's alpha chain. Its `PatternMemory` is the pattern's alpha memory — exactly the facts matching that pattern — and it is:

- what TREAT probes when it recomputes a rule's joins (§4.1);
- what a Rete `JoinNode` uses as its right input;
- the unit of "dirty" for a rule's alpha side;
- where the per-pattern index (§3.3) is anchored.

`PatternNode`s are shared only between patterns with an *identical* constraint set, which is much rarer than constraint-level sharing and should not be expected to help much. That is fine: the alpha *chain* above it is still shared, which is where the sublinearity comes from.

### 3.3 Indexing

This is the difference between "fast" and "fast at 10 rules, falls over at 500."

**Compile time produces an index *plan*; the session holds the index *contents*** (§3.2.3). The plan says "node 47 hash-indexes `/customer/id`"; the contents live in that node's memory inside each session.

**Hash indexes** for `EQ`/`IN`, anchored on `PatternNode`s and `JoinNode`s:

```java
Map<Object, LinkedHashSet<Long>> byKey;   // key: canonicalized (§2.6.2); value: handle ids
```

Three details that are easy to get wrong and expensive to debug:

- **Keys are canonicalized once, on the way in** — numerics through `stripTrailingZeros()`, and every key materialized to exactly one Java type per compatibility class (`String`, `BigDecimal`, `Boolean` — never a `JsonNode`). §2.6.2 explains why both halves matter.
- **Buckets support O(1) removal and deterministic iteration.** `List` makes retract an O(bucket) scan, degrading worst exactly when a bucket is hot. Iteration order reaches the agenda, so a plain `HashSet` would make firing order depend on hash seeds — `LinkedHashSet`, or `fastutil`'s `LongLinkedOpenHashSet` to drop the boxing (§2.1).

**Sorted indexes** for `RangeConstraint`: `TreeMap<BigDecimal, LinkedHashSet<Long>>`, navigated with `headMap`/`tailMap`/`subMap`, honoring the constraint's per-bound inclusivity. `compareTo` semantics throughout, and no `stripTrailingZeros` on this path (§2.6.2). A sorted array with binary search is a reasonable alternative only if inserts are strictly batched.

**Joins probe the smaller side.** `leftIndexKey`/`rightIndexKey` let a join look up the matching side rather than enumerate a cross product — the single biggest lever for join-heavy rule sets, and exactly what hand-rolled "simple" engines skip and then can't scale. Which side is smaller is a per-fire decision under TREAT (both memory sizes are known) and a per-propagation decision under Rete.

**What is not indexable**, stated plainly because §10 audits it: `NE`/`NOT_IN` (an anti-match is a scan of everything else), `MATCHES`, and anything behind an `ExpressionConstraint`. These become `postFilter`s over whatever the indexed constraints already narrowed the candidate set to — correct, but linear. The compiler report (§7.4) names every one, so the cost is visible at authoring time rather than discovered under load.

### 3.4 Propagation semantics

```java
public interface RuntimeNode {
    void propagateAssert(WorkingMemory wm, SessionMemories mems, Token token, Agenda agenda);
    void propagateRetract(WorkingMemory wm, SessionMemories mems, Token token, Agenda agenda);
}

public record Token(Tuple tuple, TokenKind kind) {}
public enum TokenKind { ASSERT, RETRACT }
```

An `ASSERT` reaching a `TerminalNode` creates an `Activation`; a `RETRACT` removes any matching pending activation — a fact that stops matching before its rule fires must not fire, the guarantee a naive re-scan engine gets wrong under concurrent mutation.

**Retract propagation never re-evaluates a test.** It removes tokens from node memories by *handle identity*, and removes index entries using keys computed from the payload the fact had when it was asserted. Re-deriving "which tokens should I remove" by running the node's test against current data is the classic way incremental matching breaks: if the data changed, the test selects the wrong set, and you get orphaned memory entries that produce phantom matches forever. This rule is why the update algorithm below is ordered the way it is.

#### 3.4.1 The `update` algorithm

`update` does not introduce a third `TokenKind`, and — as of this revision — it does not introduce a second propagation path either. It is **retract + reassert, gated on a diff**: cheap when nothing the network tests changed, and the ordinary retract/insert machinery when something did.

1. **Diff.** Fetch the current `Fact` as `oldFact`. For each path `P` in `TestedPaths.forType(type)` (§2.4), compare `oldFact.payload().at(P)` against `newPayload.at(P)`. Collect the changed paths. (Compare each path once even when several rules test it.)
2. **No tested path changed?** Replace the stored `Fact` with one carrying the new payload and the **same recency**, and return. No traversal, no index work, no refraction invalidation, no recency bump. This is the case that matters in streaming feeds, and it stays O(tested paths) rather than O(network).
3. **Retract, against `oldFact`.** Run the ordinary retract path: propagate `RETRACT` for the fact's tokens, and remove the handle from every index using keys computed from `oldFact`'s payload. Working memory still holds `oldFact` throughout — which is the point: `rightIndexKey(wm, handle)` must compute the **old** key here, or the handle is never removed from its old bucket and the orphaned entry produces phantom matches indefinitely.
4. **Install.** Replace the stored `Fact`: new payload, new recency. **The handle is unchanged and stays valid** (§2.1) — this is a reassert of the same identity, not a new fact.
5. **Invalidate refraction.** Clear refraction for every rule in `TestedPaths.rulesTesting(type, changed)` (§4.4). Note the scoping: *that rule's* paths, not the type-wide set.
6. **Assert, against the new fact.** Run the ordinary insert path with the same handle, and mark every rule patterning this type dirty (§4.1).

**Why the handle survives, and why that is what makes this cheap.** Refraction is keyed on `(ruleId, handles)` (§4.2). Because step 4 keeps the handle, a match destroyed in step 3 and recreated in step 6 arrives at selection time with the **same `ActivationKey`** — so a rule that does not test any changed path is still refracted and does not re-fire, with no special casing anywhere. Step 5 is what deliberately un-refracts the rules that *do* test a changed path. The two steps together give the same observable semantics that differential propagation gave, through a mechanism the engine already has.

**What this gives up, stated plainly.** Every constraint on the fact is re-tested, not only the ones reading a changed path. For a fact with many unrelated constraints under a high update rate — a price ticking on a `MarketData` fact 1000×/sec — that is a real cost, and nothing relocates it to the caller. §11.2 records why v1 takes that trade and what would reverse it: the workload that pays for differential propagation is the long-lived streaming session, which is Phase 3.

**Retract propagation still never re-evaluates a test** (§3.4), which is why step 3 runs before step 4. That rule is unchanged and is the one thing here that produces silently wrong output if you get it backwards.

#### 3.4.2 What the diff costs

Step 1 is `O(|tested-path set|)` traversals plus a structural `equals` per path, and that set is the union across all rules for the type. A 300-rule set touching 200 distinct paths on `Order` pays 200 traversals and 200 subtree comparisons on *every* update, including ones that change nothing. It grows with the rule set rather than with the update.

Two mitigations, in the order to reach for them:

1. **Fast-path guard.** Compare `oldPayload.equals(newPayload)` first — one structural walk that short-circuits on the first difference. Producers re-sending unchanged records are extremely common in streaming feeds, and this collapses the whole diff to one comparison.
2. **Prefix trie over tested paths.** Walk both payloads together once, descending only into subtrees a compiled trie marks interesting, stopping wherever the two nodes are `equals`. Cost becomes proportional to the size of the *change*. **The trie must mark ancestors, not just the deepest match:** if `/customer` and `/customer/email` are both tested and only the email changes, both belong in the changed set, because the rule testing `/customer` observes that change too. Under-reporting a changed path no longer costs a missed activation — step 6 re-asserts unconditionally — but it does cost a missed *refraction clear* in step 5, which shows up as a rule that should have re-fired and didn't. Write the probe loop first and use it as the oracle for the trie.

**Watch `JsonNode.equals` on large subtrees.** A rule constraining `/customer` compares the whole customer subtree on every order update. Where a coarse path is genuinely what the rule tests that is unavoidable, but it belongs in the compiler report (§7.4): a shallow tested path over a large subtree is a performance smell, and usually the rule could name something narrower.

**A note on where this used to be.** Earlier revisions of this document specified differential *propagation* — a compile-time `dependsOn()` set on every node, a `Reachability` service computing an affected subgraph, and a traversal scoped to it. That mechanism placed a permanent correctness obligation on every future node type (`dependsOn()` must be a superset of what the node reads, or activations go missing), and it bought a performance property whose payoff case is the streaming session Phase 3 exists to build. §11.2 records the reversal.

## 4. Agenda, Conflict Resolution & Rule Execution

### 4.1 The agenda shape

§11.1 chose **TREAT** for v1, where joins are recomputed at fire time and nothing is materialized between fires. That has a consequence for the agenda that is easy to miss: **at insert time there is nothing to push onto it, and at retract time nothing to pull.** An incrementally-maintained agenda is the *Rete* shape and presumes materialized join results, which v1 does not have.

So v1 builds one shape, not two. The conflict set is computed **lazily, on demand, by recomputing joins for dirty rules**; a retract needs no agenda surgery, because the next recomputation simply won't produce that match; and dirty tracking is a `BitSet` of rule ids.

**Rete's agenda arrives in Phase 3, with the persistent beta memory it requires** (§11.5). It is a different implementation of the same `Agenda` interface — the two shapes share `Activation`, `ConflictResolutionStrategy`, refraction, RHS execution and the firing loop, and differ only in *when* the conflict set is computed. Deferring it is not a design gap; it is declining to maintain agreement between two implementations when only one of them exists. §11.5 records what that decision costs and what Phase 3 must then prove.

**Dirty tracking is by fact type, and it is one line:**

> A rule is dirty when a fact of a type it patterns is inserted, retracted, or effectively updated (§3.4.1) — including by its own RHS.

That is the whole predicate. It reads one compile-time map, `factType → ruleIds`, and costs a set union into a `BitSet` at runtime.

**Note what it does *not* have to be.** The tempting refinement — "dirty only the rules whose alpha memories actually changed" — is wrong, and wrong in a way that silently serves stale matches. Consider `Order(status EQ "PENDING")` joined to `Customer` on `o.customerId == c.id`, and an update changing `Order./customerId`: `status` is unchanged, so the order still passes every alpha test and alpha-memory membership does not change — yet the rule's *join* result is now stale, and under the refined predicate its conflict set is never rebuilt. The type-level predicate covers this for free, because §3.4.1's update retracts and re-asserts the fact, so it leaves and re-enters every memory regardless of which field moved.

An earlier revision derived a per-changed-path predicate (`rulesTesting(type, changedPath)`) to make differential propagation safe. With retract+reassert (§11.2) that precision buys nothing at the agenda: the fact has left and re-entered the network either way. `TestedPaths.rulesTesting` survives for the one job that still needs path-level precision — refraction invalidation (§4.4), where clearing too much makes a rule re-fire on data it already handled.

**The fire cycle.** Recomputation hides behind the `Agenda` interface, so §4.7's loop is unaware of it:

```text
Agenda.nextToFire():
    if dirtyRules is non-empty:
        for each dirty rule R:
            recompute R's joins from current PatternMemory / index state
            # Each match is a fresh Activation; its recency is max(Fact.recency) over the
            # tuple's bound facts, computed in the constructor (§4.2). Nothing is carried
            # across recomputations — a rule is dirty only when a fact it patterns was
            # inserted, retracted, or effectively updated, which is exactly when its
            # activations' recency should be recomputed anyway.
            replace conflictSet[R] with the resulting matches
        clear dirtyRules
    loop:
        candidate = best remaining entry in conflictSet by conflict resolution (§4.2)
        if none: return empty
        if refraction memory already holds candidate.key(): drop it and continue
        remove candidate from conflictSet
        return candidate
```

**Refraction is checked at selection, not only at recomputation** — and this is what makes the loop terminate. Filtering refracted matches only when the conflict set is rebuilt leaves a fired activation sitting in the set of any rule that doesn't become dirty again, and "for every flagged order, emit an alert" — an RHS that mutates nothing — is exactly such a rule. It would be re-selected every cycle until `maxCycles`. Removing the selected candidate *and* re-checking refraction at selection closes both paths.

`isEmpty()` and `peek()` carry the same lazy-recomputation semantics as `nextToFire()`, and — equally important — **the same refraction filtering**. `isEmpty()` returning false must mean something is genuinely eligible to fire, not merely that the conflict set is non-empty: a set containing only refracted matches is empty for every purpose the firing loop cares about. Without that, §4.7's loop cannot distinguish "drained" from "not yet computed" from "computed but all refracted," and the last case would spin. Both methods are therefore *not* side-effect-free, and both must apply the selection-time refraction check; say so in their Javadoc.

TREAT recomputation is not a full re-scan: it probes indexes (§3.3), and only for dirty rules. That is the whole bargain — cheaper writes, more work per fire — and it is right for the short-lived batch sessions §11.1 targets, where there are few fire cycles per session.

### 4.2 Activations and conflict resolution

```java
/** A class, not a record: `key` is cached, and identity is the key alone (cf. §2.2's Fact). */
public final class Activation {
    private final CompiledRule rule;
    private final Tuple tuple;
    private final long recency;         // snapshot; see "defining recency" below
    private final ActivationKey key;    // computed ONCE in the constructor

    public ActivationKey key() { return key; }
    public CompiledRule rule() { return rule; }
    public Tuple tuple()       { return tuple; }
    public long recency()      { return recency; }

    @Override public boolean equals(Object o) {
        return o instanceof Activation a && a.key.equals(key);
    }
    @Override public int hashCode() { return key.hashCode(); }
}

/** The refraction key, the deactivation key, and the equality key — one notion, one place.
 *  Array component: hand-written equals/hashCode via Arrays, defensive copy on construction. */
public record ActivationKey(String ruleId, long[] handles) { /* ... */ }
```

**Identity is `(ruleId, handles)`, not a generated id.** Retract propagation knows a handle, not a UUID, so a `deactivate(UUID)` signature has no way to find its argument. Keying on the rule and its bindings fixes that and simultaneously supplies exactly the key refraction needs (§4.4) — one notion of "this rule, matched against these facts," serving identity, deactivation, and refraction.

**Cache the key.** Computing it inside `hashCode()` allocates on every probe of the refraction set and the agenda's key map — the hottest maps after the fact indexes. That caching is also why `Activation` is a class: a record cannot hold a derived field computed from its own components without giving up the generated constructor.

**Defining recency.** Two readings are available — when the match was found, or how fresh its facts are — and the classic engines use the second. Take it, plainly:

> An activation's `recency` is the maximum `Fact.recency` across its bound facts, computed in the `Activation` constructor.

That is the whole definition. It needs no snapshot store and no "captured when" refinement, and the reason is worth stating because an earlier draft of this spec carried both: **a rule is dirty only when a fact it patterns was inserted, retracted, or effectively updated** (§4.1) — which is exactly the condition under which its activations' recency *should* change. Recomputation therefore cannot silently re-rank a match on unrelated traffic, because unrelated traffic does not make the rule dirty. A rule that becomes dirty has, by construction, had one of its facts change.

The `final` field also cannot go stale in the heap: an effective update bumps `Fact.recency` and dirties every rule patterning that type (§3.4.1), which rebuilds those activations from scratch on the next `nextToFire()`. No activation survives an update to a fact it binds.

This is one of the mechanisms §11.5 records as a cost of *deferring* the Rete shape rather than building both at once. If Phase 3 lands a second shape that creates activations at different times, it will need to persist recency across recomputations so the two agree — and it will be able to build that against a working implementation and a differential test, rather than against a thought experiment.

```java
public interface ConflictResolutionStrategy {
    int compare(Activation a, Activation b);
}

/** Salience, then recency, then a state-derived tie-break.
 *  A TOTAL order — never returns 0 for two distinct activations (§7.3). */
public final class DefaultConflictResolution implements ConflictResolutionStrategy {
    public int compare(Activation a, Activation b) {
        int bySalience = Integer.compare(b.rule().salience(), a.rule().salience());
        if (bySalience != 0) return bySalience;

        int byRecency = Long.compare(b.recency(), a.recency());   // LIFO; configurable to FIFO
        if (byRecency != 0) return byRecency;

        return ActivationKey.LEXICOGRAPHIC.compare(a.key(), b.key());
    }
}
```

**The last tie-break is derived from state, not from a counter.** A monotonic `sequence` assigned at activation-creation time is the obvious alternative and it is worse on its own merits, before any argument about future agenda shapes: it is more plumbing (a counter to own, thread through, and reset), and it makes firing order depend on the order activations happened to be *constructed* — which under lazy recomputation is an implementation detail of the rebuild loop, not a property of the data. Ordering lexicographically on `(ruleId, ascending handle ids)` is a pure function of the match itself, costs nothing, and is stable across any recomputation order. It is also, as a free consequence, the one §11.5 mechanism that a future Rete shape would need and that already exists.

**The comparator must be a total order**, and it must be **consistent with `Activation.equals`**. Both hold here: `equals` compares keys, and the final tie-break is a total order on keys, so two activations compare 0 exactly when they are `equals`. This is not decoration. §4.3's agenda holds a heap *and* a key-indexed map; if the comparator could return 0 for distinct activations, the heap would order them by internal accident and break §7.3, and if it could return non-0 for equal ones, the map and the heap would disagree about how many entries exist and the same match could be polled twice. Custom strategies inherit both obligations; strict mode (§7.5) asserts them.

**Specificity is deliberately absent, reversing the classic JESS/ILOG/OPS5 strategy.** Those engines break salience ties by counting LHS tests, and this spec originally followed them. It is a reliable source of author surprise: nobody counts constraints in their head, so "why did B fire before A" — §7.2 names it a top-three question — gets answered by arithmetic on a number that appears nowhere in the rule file. Salience (explicit, author-controlled) plus recency (explainable from the data) covers the real cases, and the lexicographic key covers the rest deterministically.

That leaves **two** author-visible ordering terms instead of three, which is the point: every input to a firing decision is now either written in the rule or readable from the facts. If you ever reinstate it, precompute it on `CompiledRule` — this comparator runs on every agenda operation and must not walk the rule — and make §7.1's trace show the computed value, or the surprise is undiagnosable.

**`CompiledRule`, referenced above, is the compile-time companion to `RuleDefinition`**: the definition plus everything §6.5's pipeline precomputed for it. Activations hold a `CompiledRule`; nothing at runtime should be re-deriving compile-time facts from a `RuleDefinition`.

```java
/** Immutable, lives in the CompiledRuleSet, shared by every session. Produced by §6.5. */
public record CompiledRule(
    String id,
    int salience,
    boolean noLoop,                         // §4.5
    String agendaGroup,                     // null when ungrouped (§4.5 defers grouping to v2)
    int terminalNodeId,                     // index into SessionMemories (§3.2.3)
    Map<String, Set<JsonPointer>> testedPaths,  // per fact type — TestedPaths.forRule's backing
    List<ActionDefinition> actions,         // §2.5, in declaration order
    RuleDefinition source                   // kept for diagnostics and §7.2's explanations
) {}
```

### 4.3 The agenda structure

```java
public interface Agenda {
    Optional<Activation> peek();      // lazily recomputes, then filters refraction (§4.1)
    Optional<Activation> nextToFire();// selects, applies refraction, removes, returns
    boolean isEmpty();                // lazily recomputes, then filters refraction (§4.1)
    int size();                       // eligible, non-refracted activations — backs
                                      // FireResult.residualAgendaSize (§4.7)
}
```

**`size()` counts what `isEmpty()` means.** It is the eligible, non-refracted count, so `size() == 0` and `isEmpty()` always agree; a count of everything sitting in the conflict set would report a residual agenda for a session that had genuinely drained.

**All four methods lazily recompute, so memoize the recomputation within a fire cycle.** §4.7's loop calls `isEmpty()`, then `peek()` on a limit breach, then `nextToFire()` — three calls that would otherwise rebuild every dirty rule's conflict set three times over. Recompute when the dirty set is non-empty, clear the dirty set, and let the following calls read the materialized result; that is what makes these methods' shared lazy semantics affordable rather than quadratic in the number of methods the loop happens to call.

**Four methods, because the other three would have no caller.** An `activate`/`deactivate`/`deactivateAllInvolving` trio is the *Rete* interface: it exists so propagation can push activations in and pull them out as tokens arrive. Under TREAT nothing pushes and nothing pulls — the conflict set for a dirty rule is replaced wholesale at recomputation, and a retracted fact's matches disappear because the next recomputation does not produce them. Specifying those methods now would mean specifying, testing, and maintaining behavior that no v1 code path invokes. They arrive in Phase 3 with the shape that needs them.

Internally: per-rule match lists, plus a heap over the rule heads ordered by the conflict-resolution comparator. Replacing one rule's slice on recomputation then touches that rule's entry rather than re-heapifying the whole conflict set — which matters, because §4.1's bargain is more work per fire cycle, and a rebuild that is linear in the *whole* agenda rather than in the dirty rule's matches would compound it.

**The handle→activation reverse index goes too, and its diagnostic survives.** §7.1's "which activations does this fact participate in" is answered by scanning the current conflict set for tuples binding that handle — off the hot path, on a structure you already hold, at a cost nobody measures. Maintaining an index on every activation to serve a debugging question is the wrong trade when the conflict set is rebuilt wholesale anyway. Refraction keeps its own handle index (§4.4) for a different reason: fired activations are not in the conflict set at all, so there is nothing to scan.

Removing from the middle of a binary heap is O(n) unless entries carry their position. With per-rule slices this stops being a hot concern — you replace a slice rather than extract from the middle — but if you implement the agenda as one flat heap instead, either store the index and sift, or mark-dead-and-skip-on-poll with periodic compaction.

> **Amendment (Phase 3, as built).** The Rete agenda shape exists, and three things this section specifies were decided differently once it met the measurement. None of them contradicts the reasoning above; two of them follow it more closely than its own wording does.
>
> **The `activate`/`deactivate`/`deactivateAllInvolving` trio is implemented and is not on the interface.** This section's argument against specifying them in v1 was that they would be "behavior that no v1 code path invokes" — and that argument survives the arrival of the shape that needs them, because nothing *outside* the streaming matcher calls them either. They exist as operations on callbacks `Agenda` already had: `factInserted` derives the new matches and pushes the ones that can fire, `RecomputingAgenda.onConsumed` pulls a match out when it fires, and `factRetracted` pulls out everything binding a departing handle. Three public methods with one caller each, all of it inside one class, would have been a public surface maintained for its own sake.
>
>
> **Two things it changes that are not firing behaviour.** §7.1's `onActivationSuppressed` with `REFRACTED` becomes effectively TREAT-only: a refracted match is now declined when it is derived or pulled when it fires, so selection never meets one to report. "Selection still checks, for every shape" below is a statement about the check, not about that callback. And `onActivationCreated` fires once per *pending* match per cycle rather than once per *held* match. Neither reaches a firing sequence, so `MatcherEquivalence` is unaffected — but a listener comparing either count across matchers is comparing different things.
>
> **A §6.4 `condition` is the exception to all of it.** Conditions are applied after the conflict set has been read, so a match a condition rejects is never fired and never pulled back out: it stays in the conflict set and is rebuilt and re-evaluated every cycle. For a rule set that rejects most of what it matches the set converges on the join memory and the improvement above does not apply. Pruning on rejection is the structural fix and is deliberately not built — the argument for its safety runs through §3.4.1 making a condition's payload root a tested path, and a wrong step in that reasoning costs a firing that silently never happens.
>
> **The conflict set holds unfired matches only, and that is the whole change.** Before it, a fire cycle asked the join memory for every held match and constructed an activation for each, ranked them, and discarded all but one to refraction — measured at 1.5MB of garbage and 99.5% of the operation, for a session holding four thousand matches of which at most one could fire. Suppressing a match at creation when it is already refracted is what §4.4 permits ("an optimization on top of the selection-time check, never a replacement for it"), and selection still checks, for every shape.
>
> **That suppression is safe only because of §3.4.1's step ordering, and §11.5 predicted exactly this.** A shape that declines to hold a refracted match must be certain that anything clearing that refraction also re-offers the match, or the firing is lost with nothing left to recreate it — §11.5's recorded hazard, and the specific cost it cited for keeping two shapes in agreement. It holds because refraction is cleared in exactly two places and both destroy the matches they clear: a retract, after which the match does not exist, and an effective update, which clears at step 5 and re-derives at step 6. **Swapping those two steps now drops a firing rather than merely being untidy**, which is a property `ReteMatcherTest` asserts rather than a comment.
>
> **The heap over rule heads was not built, and the measurement is why.** This section proposes "a heap over the rule heads ordered by the conflict-resolution comparator" so that replacing one rule's slice does not re-heapify the whole conflict set. That is the right structure for a conflict set that stays large — which is what a *rebuilding* agenda has. Once the set holds only unfired matches it is near-empty at a streaming steady state, and a heap over nothing buys nothing. It becomes worth building if a workload appears that holds many simultaneously-eligible matches; the number to watch is `SessionStats.pendingMatchCount`, which exists so that question has an answer rather than an opinion.

### 4.4 Refraction

**Without refraction, `fireAllRules` does not terminate on ordinary rule sets.** A rule whose RHS doesn't invalidate its own LHS — "for every flagged order, emit an alert," which mutates nothing — would re-fire on the same match until `maxCycles`. This is a Phase 0 primitive, not an optimization.

```java
/** Per-session. "This rule has already fired on this exact set of facts." */
final class RefractionMemory {
    private final Set<ActivationKey> fired = new LinkedHashSet<>();
    private final Map<Long, Set<ActivationKey>> byHandle = new LinkedHashMap<>();

    boolean shouldFire(ActivationKey k) { return !fired.contains(k); }
    void record(ActivationKey k) { fired.add(k); for (long h : k.handles()) byHandle.computeIfAbsent(h, …).add(k); }

    /** Retract: the fact is gone, so EVERY match binding it is eligible again. */
    void invalidateAll(long handle) { /* remove every key in byHandle[handle] */ }
    /** Effective update: only rules that test a changed path are affected (see below). */
    void invalidateFor(long handle, Set<String> ruleIds) { /* remove those rules' keys only */ }
}
```

Semantics, precisely:

- An activation whose key is in `fired` is never selected (§4.1's selection-time check). A Phase 3 Rete shape may additionally suppress creation at the `TerminalNode`; that is an optimization on top of the selection-time check, never a replacement for it.
- **A key is recorded in `fired` when its activation is consumed**, not when it completes: immediately after `nextToFire()` returns it and before the RHS runs. Recording on *success* only would let a rule whose RHS throws under `SKIP_ACTIVATION` (§4.6) be re-selected on the next cycle and throw again, forever — a retry loop that looks exactly like the non-termination refraction exists to prevent. Recording on consumption also means `ABORT_SESSION` and `RETHROW` leave the key recorded, which is correct: the session is over, and nothing will re-select it anyway.
- **A match becomes eligible again when one of its facts is retracted, or effectively updated on a path *that rule* tests.** The scoping is essential. A type-wide rule — "any tested path, anywhere in the network" — means an update to `Order./total`, tested only by rule B, clears refraction for rule A's match where A tests only `/status`. A would then re-fire on identical data the next time it became dirty — a rule firing twice because an unrelated rule's field changed, which no author can predict from reading their rule. Scope invalidation with `TestedPaths.rulesTesting` (§2.4).
- An update changing no tested path clears nothing, consistent with §3.4 treating it as a non-event.
- **Refraction needs its own handle index**, shown above as `byHandle`. A fired activation has been removed from the conflict set at selection (§4.1), so there is nothing left to scan it out of — §4.3 makes the same point from the other side when it declines to maintain a handle→activation index for *pending* activations. Without a dedicated index, `invalidate` is an O(|fired|) scan per retract — quadratic in a long-lived session, and refraction is a growth surface there in its own right; see below.

**Session growth surfaces, as a set.** `maxFacts` (§4.7) bounds working memory and nothing bounds anything else *directly*. A session accumulates three structures, and only the first is capped:

| Structure | Grows with | Bounded by |
|---|---|---|
| Working memory | facts inserted, less retracted | `maxFacts` |
| Node memories and their indexes (§3.3) | facts in working memory | transitively, by retract — which removes the handle from every memory and index it entered |
| `RefractionMemory.fired` + `byHandle` | every match *ever* fired | retract, and per-rule invalidation — nothing else |

Two structures are deliberately absent. The conflict set is bounded by the matches that currently exist rather than by history, because §4.1 replaces a dirty rule's slice wholesale. The agenda's handle→activation reverse index does not appear because §4.3 declines to maintain one.

> **Amendment (Phase 3).** A **fourth** structure exists once a session selects the Rete shape, and the count above, the two "deliberately absent" sentences, and the "all three" below all need qualifying rather than deleting. `BetaMemory` holds the **materialised complete matches** for every rule, plus a **handle→match reverse index** — which is the index §4.3 declined to maintain, reintroduced for a different purpose. §4.3's refusal was about serving a *debugging question* from a structure the TREAT agenda rebuilds wholesale anyway; this one exists so a retract is proportional to the matches its fact took part in rather than to the whole memory, without which the eviction this section specifies could not be written.
>
> It is bounded by matches currently held, not by history, so a session that retracts what it inserts reaches a steady state — asserted in `BetaMemoryTest`. But "bounded by current matches" is a weaker bound than it sounds: a self-join over N facts holds O(N²) matches, so a streaming session that only inserts grows quadratically while working memory grows linearly. That is the growth surface this shape adds, and it is the one §4.4's eviction has to answer.

For the one-shot/batch sessions v1 targets (§11.1 option A) this is a non-issue: the session is discarded before anything accumulates. It is a real problem for §11.1's option B — a streaming session watching an entity for days, inserting continuously and retracting nothing — where the fired-match memory grows without bound while working memory looks healthy.

**The mechanism that bounds all three — all four under the Phase 3 amendment above, which is keyed on handles like the rest — is fact eviction, because they are all keyed on handles.** Give long-lived sessions a documented eviction policy (TTL, or LRU by `recency`), and specify that evicting a fact runs the **full retract path** — which cascades to refraction, snapshots, and the reverse index for free, and keeps eviction from becoming a fifth place where memories are removed by hand. This is the "time-windowed eviction is a separate concern from temporal operators" claim §1 makes: eviction is a session-lifetime concern that a streaming session needs whether or not `after`/`within` operators ever land. It is Phase 3 work, alongside the streaming session shape it exists to serve, and it should be an exit criterion for it: a streaming session under sustained insert-without-retract load must reach a steady-state heap, not a rising one.

> **Amendment (Phase 3, as built).** Eviction now exists, and three things this section leaves open had to be decided to build it. None of them contradicts the paragraph above; all three are the kind of detail that only appears once the mechanism meets the workload.
>
> **A cap per fact type, not only a total one.** "LRU by `recency`" over the whole session is the wrong bound for the shape this exists to serve. A streaming session holds two populations — reference data loaded once, and a stream flowing past it — and the reference data is inserted *first*, so it holds the lowest recency and a global LRU evicts exactly what the session meant to keep, retaining the stream that was supposed to be bounded. `EvictionPolicy.perType` caps named types individually and leaves the rest unbounded, which is one map and no predicate for "keep the last ten thousand orders and never evict the two hundred customers". The global bound ships too, for the case where every fact is the same kind of thing.
>
> **TTL is a determinism decision, not a policy choice, and is deliberately not shipped.** This section names TTL first, and wall-clock time is not an input §7.3 admits: two runs over identical input evict different facts, and the firing sequence is a contract rather than a preference. Eviction changes which facts exist, so it changes which activations exist, so it reaches that sequence directly. The policies that ship key on `recency`, which is derived from the input itself. A caller who genuinely wants a TTL writes one against the interface with a clock they inject and takes the trade knowingly — a better place for that decision than a factory method that makes it look free. Strict mode calls a policy twice on the same working memory and compares, which catches a clock reliably and a `HashMap`'s iteration order often.
>
> **Eviction runs only at quiescence, and this is the part that would be a defect if got wrong.** This section says eviction runs the full retract path but not *when*, and neither obvious answer is safe. A hook on the session's `insert` never sees a right-hand side's derived facts, which reach working memory through §4.6's staging protocol instead — so the growth an RHS causes would be unbounded. A hook inside working memory's own insert *would* see them, and would fire between staging and commit, where it could retract a fact the firing activation binds and leave a `FireRecord` naming a handle that no longer exists. So the policy is consulted at exactly two points, both of which are between operations rather than inside one: after a caller's insert, and at the top of a fire cycle — before §4.7's `maxFacts` check, so a configured policy prevents that breach rather than racing it. It is never consulted during a right-hand side.
>
> Two consequences worth stating plainly. Facts of any origin are evictable, `DERIVED` included, and that does not re-derive: evicting a derived fact clears refraction for matches *binding* it, while the match that *created* it binds its source facts and stays refracted. And the consultation is gated on an insert having happened since the last one, because a cap can only be exceeded by an insert — a retract cannot put a type over its bound, and neither can an update, which is a retract and an insert of the same handle.

**Refraction and `noLoop` are different things**, and blurring them confuses everybody:

| | Refraction | `noLoop` |
|---|---|---|
| Scope | every rule, always on | opt-in per rule |
| Prevents | the *same* rule re-firing on the *same* facts | a rule's own RHS re-triggering that same match |
| Cleared by | retract, or an update to a path that rule tests | n/a |
| Needed for | termination | avoiding a specific self-loop refraction can't see |

Refraction alone still permits: `R` fires → its RHS updates fact `f` on a path `R` tests → refraction for that match clears → `R` fires again. That is the loop `noLoop` addresses.

### 4.5 `noLoop`, agenda groups, and loop defense

**`noLoop`, defined as a refraction rule.** The propagation-time formulation — "suppress activations produced by this activation's mutations" — is not implementable in the TREAT shape, which rebuilds the conflict set wholesale from index state with no record of which mutation produced which match. The equivalent formulation that works in both shapes:

> While executing activation `A` of rule `R` with `noLoop` set, effective updates performed by `A`'s own RHS do **not** clear refraction for `A.key()`. Other rules' refraction, and `R`'s refraction for *other* bindings, are unaffected.

Same behavior for the case anyone actually means, expressed in a mechanism both agenda shapes already have.

**Be explicit about what `noLoop` does not do:** it is one level deep. `R → S → R` sails through it, as does `R` mutating a fact that re-activates `R` on a different binding. Every engine offering this flag has the same limitation, and pretending otherwise leads people to treat it as a loop guard. **`maxCycles` (§4.7) is the actual loop defense**; `noLoop` avoids the most common accidental self-trigger.

**Agenda groups.** An optional named partition (like Drools' agenda-groups or JESS's modules) letting you stage execution — "validation" fires to completion before "pricing" becomes eligible. Model as multiple `Agenda` instances behind a `GroupedAgenda` with an explicit `setFocus(String)` and a focus stack. A v2 nicety; build the single-agenda case first. It composes with salience rather than replacing it, and in practice it is the better tool: staging by group is visible in the rule file, staging by salience arithmetic is not. Rule sets accumulating hand-tuned salience values are the signal to build this.

Refraction is per-group-agnostic: a match refracted in one group stays refracted when focus returns. Anything else would make focus changes a way to re-fire completed work.

### 4.6 RHS execution semantics

**Actions within one RHS are applied in declaration order, with propagation deferred to the end.**

```
execute activation A:
    staging = new StagingBuffer()
    for each action in A.rule().actions():        # declaration order
        stage the effect into `staging`
    commit(staging)                               # NOW: propagation, indexes, agenda, listeners
```

Propagating after each action would let action 2 observe state action 1 created, making a rule's behavior depend on action ordering in ways invisible in the rule file. Deferred commit gives every action one consistent view, makes the RHS a single unit for error handling, and matches the author's mental model: the rule fires, then the world changes.

The cost: an action cannot read the result of an earlier action in the same RHS. That is the right limitation — needing it means the rule should be two rules.

**Staging rules, in detail**, because the obvious implementation has two sharp edges:

- **Multiple `setField`s on the same handle merge into one `update()`**, applied in declaration order. Naively staging each as an independent update built from the pre-RHS payload means the second overwrites the first and the earlier field change silently vanishes — the single most likely week-one bug in this design. Stage per-handle field *deltas*, not whole payloads, and materialize one payload at commit.

  **Materialize onto a `deepCopy()` of the stored payload, then call `updateOwned`.** Applying the deltas to the stored `ObjectNode` in place is the obvious implementation and it is the §2.2 aliasing bug, arriving through the engine's own RHS path: §3.4.1 step 1 would diff the stored payload against itself, find nothing changed, propagate nothing, and leave every index stale. Copy once per touched handle, apply the deltas to the copy, hand ownership to `updateOwned` — which skips the copy §2.2 would otherwise make, so this costs one `deepCopy()` per mutated fact, not two. Strict mode's aliasing check (§7.5) catches the in-place version, and the engine must not be the thing that trips it.
- **`insertFact` allocates its handle at stage time**, not at commit. A later action in the same RHS may need to reference the newly-inserted fact (a common shape: insert a derived fact, then emit an event naming it). Allocating the handle immediately, while deferring the *propagation*, gives later actions something to name without letting them observe match consequences. The fact becomes visible to matching only at commit.
- **A retract of a handle inserted in the same RHS** cancels both effects at commit rather than propagating an insert and a retract.

**`callFunction` executes at commit, and is not transactional.** This is the honest boundary of the atomicity claim: `callFunction` dispatches to arbitrary host Java, and §11.3's own example is `notifySlack`. A sent Slack message cannot be un-sent. So:

**The guarantee is per-phase, and the two phases differ.** "All-or-nothing if any action throws" is true of staging and false of commit, and a `callFunction` *is* one of the five actions — so stating the rollback rule without splitting the phases makes it false for the one action type that realistically throws.

- **Staging-phase failure — full rollback.** Anything that throws while effects are being staged (a `setField` on a retracted handle, a `$ref` that resolves to no binding, a schema rejection) discards the entire staging buffer. Nothing is applied, nothing is emitted, no handler runs. Working memory never lands half-mutated.
- **Commit-phase failure — no rollback of working memory.** At commit, working-memory effects are applied first, then `callFunction` handlers run in declaration order, then `emit`s are delivered. A handler that throws leaves the working-memory effects applied and earlier handlers' side effects in place; the error is reported against that action. There is no compensating undo, and there cannot be one — §11.3's own example is `notifySlack`, and a sent message cannot be un-sent.
- **`FireRecord` must therefore say what actually landed** (§7.1): which staged effects committed, which handler failed, and which handlers never ran. A record that only names the failing action leaves the partial state undiscoverable.
- Say all of this in the rule-authoring guide, not just here. "The rule is atomic" is a claim authors will over-read, and the half that is false is the half with external consequences.

```java
public interface RhsErrorHandler {
    enum Decision { ABORT_SESSION, SKIP_ACTIVATION, RETHROW }
    Decision onRhsFailure(Activation activation, ActionDefinition failed, Throwable cause);
}
```

- **Default is `RETHROW`**, with the partially-executed activation recorded in the trace (§7.1) and the session marked failed: subsequent operations throw `IllegalStateException`. The original exception propagates to the caller of `fireAllRules`. Silent continuation after an unexpected RHS exception is how a rule engine produces confidently wrong output.
- **`ABORT_SESSION` differs from `RETHROW` in one respect only: it does not propagate.** The session is marked failed identically, but firing stops and `fireAllRules` *returns* a `FireResult` with `TerminationReason.RHS_ERROR`, the records completed so far, and the failure on the trace. **It is not `LIMIT_EXCEEDED`**: no limit was breached, and a caller switching on the reason to decide "retry with a higher `maxCycles`" would be told to do exactly the wrong thing. Choose it when the caller treats a rule failure as a decision outcome to inspect rather than an exception to handle — a batch driver that must report per-item status without a try/catch around every item. Both leave working memory as §4.6's commit phase left it; neither attempts to unwind.
- `SKIP_ACTIVATION` — log, refract it so it cannot retry-loop, continue firing — is right for best-effort batch scoring, where one bad fact shouldn't fail 10,000 good ones. Choose it deliberately.
- **`callFunction` handlers are untrusted for *time*, not just for effects.** A handler that blocks indefinitely stalls the session for as long as it blocks, and there is no fire-loop timeout to rescue it (§4.7). Document the contract (non-blocking, bounded) and enforce a configurable per-call timeout in strict mode.

**`emit` needs a sink:**

```java
public interface EventSink {
    void emit(String eventType, JsonNode payload, EmitContext ctx);
}

/** Everything needed to correlate an emitted event back to the decision that produced it. */
public record EmitContext(
    UUID sessionId,             // §2.1 — with the handles below, globally unique
    String ruleId,
    long[] handles,             // the bound facts, in tuple order
    String ruleSetVersion       // §5.6's content hash — "which rules produced this"
) {}

/** An emission captured rather than delivered: the collecting sink's element type,
 *  and what FireResult.emitted() returns. */
public record EmittedEvent(String eventType, JsonNode payload, EmitContext ctx) {}
```

- Supplied per session via `SessionOptions`; defaults to a collecting sink whose contents are returned in `FireResult`. That default is right for the batch shape: the caller gets emitted events as the *return value* of `fireAllRules()`, with no external side effects, which makes rules testable without mocking anything.
- Emission is staged, so events from a failed RHS are never delivered. Delivery order is rule-firing order.
- A sink performing I/O turns firing into a distributed transaction and reintroduces the blocking problem above. Recommended pattern: collect during firing, publish after `fireAllRules()` returns. Publishing inline is a deliberate at-least-once choice and needs documenting as such.

### 4.7 Firing loop

```java
/** The engine's output. Every fire call returns one, and RuleEngineLimitExceeded carries a
 *  partial one — so no completed work is ever lost to a limit breach (see below). */
public record FireResult(
    List<FireRecord> fired,          // §7.1, in firing order
    List<EmittedEvent> emitted,      // from the collecting sink, in firing order
    TerminationReason why,
    int residualAgendaSize,          // non-zero after HALTED or a limit; 0 after DRAINED
    String ruleSetVersion,           // §5.6 — stamped on every result, not just on traces
    Duration took
) {}

/** Why firing stopped. LIMIT_EXCEEDED appears only on the partial result carried by a
 *  RuleEngineLimitExceeded (§4.7); RHS_ERROR only under an ABORT_SESSION decision (§4.6). */
public enum TerminationReason { DRAINED, HALTED, LIMIT_EXCEEDED, RHS_ERROR }

/** Built, never constructed positionally — the same argument §7.5 makes for SessionOptions,
 *  and it applies here first: the open decision immediately below adds `maxDuration` to this
 *  type, which as a record would break every positional construction in every caller. */
public final class FireOptions {
    private final int maxCycles;     // §4.7 — mandatory, no limit-less fire
    private final int maxFacts;      // §4.7 — pairs with maxCycles

    public static Builder builder() { return new Builder(); }
    public static final class Builder { /* both limits required; build() rejects non-positive */ }
}
```

> **Open decision — a wall-clock bound.** `maxCycles` and `maxFacts` bound *work*, not *time*, and neither is a proxy for latency: §6.4's own example — an unindexed CEL condition over 100,000 facts — is 100,000 evaluations inside a *single* cycle, tripping neither limit. Any caller with a per-decision latency budget therefore has no engine-side enforcement and must run its own watchdog against `halt()`. Adding `maxDuration` to `FireOptions` — a non-breaking addition now that it is builder-backed — (checked in the loop below, inside TREAT recomputation, and inside unindexed scans, with a `DurationLimit` subclass carrying `partialResult()`) would close it. Left unresolved here because it is a scope decision, not an editorial one.

The loop below is what `RuleSession.fireAllRules(FireOptions)` (§5.1) delegates to — `session` is the receiver. It is not a second, free-function API. `partialResult(fired)` and `result(fired)` are session-internal builders, not public API: both assemble a `FireResult` from the records so far plus the collecting sink's events and the session's rule-set version, differing only in `TerminationReason` — `LIMIT_EXCEEDED` and a non-zero `residualAgendaSize` for the former, `DRAINED`, `HALTED`, or `RHS_ERROR` for the latter. Both reach the agenda through the same session-internal accessor `fireLoop` uses below; none of the three is public API (§5.1).

```java
private FireResult fireLoop(RuleSession session, FireOptions opts) {
    var fired = new ArrayList<FireRecord>();
    while (true) {
        if (session.halted()) break;

        // Termination is checked BEFORE the limits, and via peek() so that a limit breach
        // does not consume the activation it is about to report.
        if (session.agenda().isEmpty()) break;

        if (fired.size() >= opts.maxCycles()) {
            throw new RuleEngineLimitExceeded.CycleLimit(
                opts.maxCycles(), session.agenda().peek(), fired, session.partialResult(fired));
        }
        if (session.workingMemory().size() > opts.maxFacts()) {
            throw new RuleEngineLimitExceeded.FactLimit(
                opts.maxFacts(), session.workingMemory().size(), session.partialResult(fired));
        }

        Optional<Activation> next = session.agenda().nextToFire();
        if (next.isEmpty()) break;                    // lost a race with halt(); nothing consumed
        fired.add(executeRhs(next.get(), session));   // §4.6
    }
    return session.result(fired);
}
```

```java
public sealed abstract class RuleEngineLimitExceeded extends RuntimeException {
    public abstract FireResult partialResult();      // never lose completed work — see below
    public static final class CycleLimit extends RuleEngineLimitExceeded { /* ... */ }
    public static final class FactLimit  extends RuleEngineLimitExceeded { /* ... */ }
}
```

Four things this loop gets right that are easy to get wrong:

- **`halted()` is checked before consuming an activation.** Checking after `nextToFire()` silently discards the selected activation, so a halted-and-resumed session skips exactly one firing, non-deterministically.
- **Termination beats the limit check.** Testing `fired >= maxCycles` before discovering the agenda is drained throws on a run that legitimately completed in exactly `maxCycles` firings — a spurious failure at exactly the boundary a well-tuned limit sits on. The tell that this ordering is wrong is that the exception has no offending activation to report.
- **The limit check inspects with `peek()`, never `nextToFire()`.** `nextToFire()` consumes: it removes the selected activation from the conflict set and records it as refracted (§4.4). Selecting an activation and then throwing without executing it destroys work the session can never recover — the same class of bug as checking `halted()` after selection, and easy to reintroduce because "get the next activation, then validate" reads naturally. `isEmpty()` and `peek()` apply the same refraction filtering as `nextToFire()` (§4.1), so this ordering does not change which activation is reported.
- **The exception names the culprit and carries the work.** A limit alone tells you nothing actionable; a runaway loop is almost always one or two rules, and the activation that was next in line plus the last N fire records identifies them immediately. And a batch that fired 9,999 rules and emitted 9,999 events must not lose all of it on the 10,000th — `partialResult()` carries everything completed.
- **`maxFacts` pairs with `maxCycles`.** A rule inserting a fact per firing without retracting OOMs the JVM long before a high `maxCycles` trips, and an OOM says nothing about which rule did it. §11.1's option B flags unretracted growth in long-lived sessions as a known failure mode; this surfaces it.

**`halt()` is the one legal cross-thread call on a session.** A watchdog or timeout thread must be able to stop a running fire loop — the only enforcement available for a latency budget, given the open decision above — so the flag is `volatile`. That is the entire extent of the exception to §5.1's single-writer rule; no other method may be called from another thread, and that belongs on `halt()`'s Javadoc, not only here.

**There is no `fireUntilHalt` in v1.** It is only meaningful with the actor pattern, which §5.4 defers to Phase 3 along with the long-lived session shape it serves. A batch session fires once over a bounded input and returns.

---

## 5. Concurrency Model

This is the part that most directly answers "run many concurrent executions," and where the JDK gives you primitives JESS/ILOG/Drools didn't have. Pinning to **JDK 25 (LTS, September 2025)** rather than 21 matters specifically for §5.4: virtual threads were final in JDK 21 ([JEP 444](https://openjdk.org/jeps/444)), but JDK 25 carries JEP 491 (landed 24) and finalizes Scoped Values (JEP 506). §5.2 covers the one piece still unsettled in 25 — `StructuredTaskScope`.

### 5.1 The core split

```
CompiledRuleSet   — immutable, thread-safe, built once, shared by every caller
     │
     └── newSession() ──► RuleSession — mutable, single-writer, cheap, disposable
```

- `CompiledRuleSet` holds the shared node graph, compiled accessors, the index plan, and rule metadata. Building it is the expensive one-time step (parse → constraint extraction → node sharing → index planning).
- `RuleSession` holds *only* mutable state: working memory, `NodeMemory[]`, agenda, refraction memory, counters. **A session is never shared across threads.** This single-writer rule is what lets you skip locks entirely in the hot path.

```java
public interface CompiledRuleSet {
    RuleSession newSession();
    RuleSession newSession(SessionOptions options);
    int nodeCount();                  // sizes the session's NodeMemory[] (§3.2.3)
    SchemaRegistry schemas();         // frozen at compile time (§2.3)
    CompilerReport report();          // §7.4
    String version();                 // content hash of source + compiler version (§5.6)
}

public interface RuleSession extends AutoCloseable {
    UUID sessionId();                                       // §2.1

    FactHandle insert(String type, JsonNode payload);        // deep-copies (§2.2)
    FactHandle insertOwned(String type, JsonNode payload);   // ownership transfer
    void update(FactHandle handle, JsonNode newPayload);     // deep-copies
    void updateOwned(FactHandle handle, JsonNode newPayload);
    void retract(FactHandle handle);
    Optional<Fact> get(FactHandle handle);

    FireResult fireAllRules();                 // limits from SessionOptions
    FireResult fireAllRules(FireOptions opts); // per-call override

    /** §2.4 — also how callers reach factsOfType. Read access; content changes only
     *  through the insert/update/retract methods above. */
    WorkingMemory workingMemory();
    boolean halted();                // reads the volatile flag halt() sets

    /** The ONLY method callable from another thread. Backed by a volatile flag. §4.7 */
    void halt();

    /** Releases session memories. Does NOT fire pending activations; a non-empty agenda at
     *  close is reported in the final FireResult, not silently drained. */
    @Override void close();
}
```

The fact API is `(String, JsonNode)` throughout — there is one fact representation (§2.2) and no adapter SPI. `fireAllRules` is never limit-less: §4.7 makes `maxCycles`/`maxFacts` mandatory, so they come from `SessionOptions` and can be overridden per call.

**There is deliberately no public `agenda()`.** §4.7's fire loop is a session method and reaches the agenda through a module-internal accessor, as do §7.2's `MatchExplainer` and the testkit's oracle — none of which is a caller outside the engine. Publishing the `Agenda` (§4.3) would put `nextToFire()` in reach of application code, and that method *consumes*: it removes the activation from the conflict set and records it as refracted (§4.4), so an outside call silently deletes a firing that the running loop would otherwise have performed, with no error anywhere. What callers actually want from it is already public and non-consuming — `FireResult.residualAgendaSize` for "what is left," `dryRun` (§7.5) for "what would fire, in what order," and `MatchExplainer` for "why didn't it."

### 5.2 Across-session parallelism (the primary primitive)

Sessions are cheap and `CompiledRuleSet` is immutable and freely shareable, so the natural unit of concurrency is **one virtual thread per session**. Virtual threads make "spin up a session, run it, throw it away" cheap enough to do per request or per batch item at high volume — a very different cost model from pooling a small number of expensive stateful engine instances.

```java
List<FireResult> results = new ArrayList<>();
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<FireResult>> futures = batches.stream()
        .map(batch -> executor.submit(() -> {
            try (RuleSession session = ruleSet.newSession()) {
                batch.forEach(f -> session.insert(f.type(), f.payload()));
                return session.fireAllRules();
            }
        }))
        .toList();
    for (var f : futures) results.add(f.get());
}
```

This is the version to build and ship first: `ExecutorService` + virtual threads is stable, non-preview API.

**Three things this example doesn't show that matter at scale.** *Session creation cost* is your throughput ceiling — measure it (§10), because it is the constant multiplying every task. *Batch granularity* is a real tuning knob: one session per fact wastes setup, one session per 10,000 facts loses parallelism and delays the first result. *Failure isolation* is on you here — one task's exception surfaces at its `f.get()` and the others keep running, which is usually what you want for batch scoring but means you must decide what a partial batch result means before you ship it.

**On `StructuredTaskScope`:** tempting for exactly this shape — fan out sessions, join results, propagate cancellation as a unit — but it remains **preview** in JDK 25 ([JEP 505](https://openjdk.org/jeps/505), its fifth), with a substantially reworked API (static `open()` factories and a `Joiner` interface, replacing the JDK 21–23 `ShutdownOnFailure` subclassing style) and no finalization JEP filed; §12 has the full version trail. Concretely that means `--enable-preview` at compile and run time and willingness to track churn. Treat it as an optional enhancement layered over the `ExecutorService` primitive rather than a v1 dependency. Being on 25 does make deliberate adoption more reasonable — the API is closer to final than it was — so if cancelling a whole fan-out the moment one session fails or a deadline passes is a day-one requirement, this is a defensible place to spend the preview cost. Otherwise build the plain version and revisit.

### 5.3 Within-session parallelism (secondary, Phase 4+)

Don't build this until the across-session model is proven and profiled. If you do need it (very large single-batch insertion):

- **Alpha evaluation is embarrassingly parallel, with one precondition.** `AlphaNode.test` is pure, so partitioning a large insert batch across `ForkJoinPool.commonPool()` is safe *for evaluation*. It is **not** safe for *ordering*: results arrive in completion order, and if they reach alpha memories or the agenda in that order, firing order becomes thread-scheduling-dependent and §7.3's determinism contract is gone. Collect the parallel results and merge them into session state ordered by handle id before anything downstream sees them. That merge is a real serial cost and is much of why this is a "profile first" optimization rather than a free one.
- **Beta/join evaluation is not**, if using persistent Rete-style join memories (per-session mutable state in `NodeMemory`). Either keep join processing single-threaded per session — simplest, and usually fine since across-session parallelism is already absorbing your concurrency — or move to concurrent join memories with per-bucket locking, a real complexity jump that deserves separate justification.

### 5.4 Session-as-actor — deferred to Phase 3

**Not v1.** The actor pattern exists to serve one shape: a long-lived session fed continuously by multiple producers — §11.1's option (B), which §9 schedules for Phase 3. Specifying it here as though it were v1 material put it a phase ahead of the session shape it exists to serve, and §9 already listed it as a Phase 3 deliverable; this section now agrees with the roadmap.

**What v1 ships instead:** §5.2's across-session model. One virtual thread per session, sessions created and discarded per request or per batch item, no shared mutable state and therefore no inbox, no queue, and no cross-thread handoff to get right. That is what makes "high-concurrency evaluation the default" true, and it needs none of the machinery below.

**The shape of the eventual solution**, recorded so Phase 3 doesn't rediscover it: a `SessionActor` owning one `RuleSession` and one worker thread, with a **bounded** inbox carrying command objects rather than bare `Runnable`s — the caller's future must be reachable *from the queue*, or a drain on shutdown cannot fail the futures of commands that will now never run. Producers submit; the worker is the single writer, which is what preserves §5.1's rule without a lock.

Three hazards worth writing down now, because each is a production incident rather than a test failure:

- **Enqueue must not block unboundedly.** A blocking `put` between the "is the actor running" check and the enqueue can block forever if the worker exits in that window with a full inbox — the drain has already run and nothing will poll again. Use an offer with a timeout and a documented rejection policy; never leave an unbounded blocking call in that gap.
- **`close()` must be bounded and must fail what it drains.** Every command still queued gets its future completed exceptionally. Silent dropping turns a shutdown into a set of futures that never complete.
- **`fireUntilHalt` belongs to this pattern and only to it.** The calling thread blocks in the fire loop, so facts must arrive from somewhere else — which under single-writer means the loop and the inserts are the same thread servicing a queue. Calling it on a session you insert into from another thread is a data race, not a supported pattern. It is therefore **not on the v1 `RuleSession`** (§5.1); it arrives with the actor in Phase 3.

Everything here is additive over v1: it wraps a `RuleSession` and adds no requirement to the engine below it.

### 5.5 Why the immutability split matters for scale

- Thousands of concurrent virtual threads reference the same `CompiledRuleSet` with zero contention, because nothing about it mutates after compile.
- Session creation is bounded and small — allocate working memory, `NodeMemory[]`, agenda. No classloading, no XML/KJAR parsing, no per-session network rebuild. This is the direct fix for "engine feels heavy" when that complaint comes from session creation cost.
- Because a session never leaks into another's state, throughput scales linearly with core count for the batch case, limited only by contention on shared read-only structures — cache-line sharing, not locking.

### 5.6 Rule-set versioning and hot reload

**For new sessions: swap the reference.**

```java
public final class RuleSetHolder {
    private volatile CompiledRuleSet current;         // volatile is the entire mechanism
    public RuleSession newSession() { return current.newSession(); }
    public void publish(CompiledRuleSet next) { current = next; }
}
```

A session holds a strong reference to the rule set it was created from, so in-flight sessions finish against the rules they started with — no torn state, no mid-session change, no locking. Old rule sets become garbage once their last session closes. **Compile fully before publishing**: a compilation failure must leave the previous version serving.

**For long-lived sessions there is no safe in-place swap.** A streaming session carries alpha memories, join memories, refraction state, and a populated agenda, all shaped by the old network's node ids. Options, in order of preference:

1. **Drain and restart.** Stop feeding the old session, let it finish, create a new one, replay current facts. Correct, simple, the right default. Its cost is a rebuild proportional to working-memory size, which is why it wants a documented `exportFacts()`/replay path.
2. **Run both, cut over.** Feed both for a window, compare outputs, drop the old one. What you want when a rule change carries business risk; needs no engine support beyond (1), but does need §7's tracing to make the comparison meaningful. `dryRun` (§7.5) is the cheap version of this.
3. **In-place network patching.** Recompute node ids, migrate memories, invalidate affected refraction entries. Genuinely hard, easy to get subtly wrong, worth building only against a requirement (1) and (2) cannot meet.

**Rule-set identity should be explicit.** `CompiledRuleSet.version()` is a content hash of the source rule files plus the compiler version; stamp it into every trace record and emitted event, and you can answer "which rules produced this decision" months later — for anything audited, the question that actually gets asked.

---

## 6. DSL: JSON/YAML

### 6.1 One object model, two serializations

Use [Jackson](https://github.com/FasterXML/jackson) with both `ObjectMapper` (JSON) and `YAMLMapper` (YAML, from the [`jackson-dataformats-text` yaml module](https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/yaml) — the standalone repo is archived and merged there) deserializing into the *same* intermediate POJO tree, which compiles to `RuleDefinition` (§2.5). Don't build two parsers; the difference is one factory choice against an identical target type.

### 6.2 Rule schema

```yaml
id: high-value-order-review
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
      id:       { eq: { $ref: o.customerId } }   # join: cross-fact reference
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

- `where` blocks are **operator maps**. Implicit `AND` across fields within a `where`, implicit `AND` across patterns in `when`. §6.2.1 gives the complete set.
- **`{ $ref: alias.field }` is the join-reference syntax**, resolved against earlier-bound aliases at compile time into a `JoinConstraint`.
- `then` is a small, closed vocabulary — deliberately *not* arbitrary script by default (§11.3). §6.2.2 gives all five verbs.

#### 6.2.1 The complete operator map

Every DSL key, the `Constraint` it compiles to, and whether it is indexable. **DSL keys are camelCase; the corresponding `Operator` constants (§2.5) are SCREAMING_SNAKE** — `notIn` is `NOT_IN`, `hasField` is `HAS_FIELD`. Semantics against absent, null, and wrong-typed values are §2.6.1's table, which is normative; this table is the syntax.

| DSL key | Example | Compiles to | Indexed |
|---|---|---|---|
| `eq` | `status: { eq: "PENDING" }` | `FieldConstraint(EQ)` | hash (§3.3) |
| `ne` | `status: { ne: "CLOSED" }` | `FieldConstraint(NE)` | no — anti-match; see the §2.6.1 trap |
| `gt` `gte` `lt` `lte` | `total: { gt: 10000 }` | `RangeConstraint`, one-sided | sorted |
| `between` | `total: { between: { from: 100, to: 500, fromInclusive: true, toInclusive: false } }` | `RangeConstraint`, two-sided | sorted |
| `in` | `riskTier: { in: ["HIGH","MEDIUM"] }` | `FieldConstraint(IN)` | hash, one entry per element |
| `notIn` | `region: { notIn: ["XX"] }` | `FieldConstraint(NOT_IN)` | no |
| `matches` | `email: { matches: "^[a-z]+@example\\.com$" }` | `FieldConstraint(MATCHES)` | no — RE2, §2.6.3 |
| `hasField` | `couponCode: { hasField: false }` | `FieldConstraint(HAS_FIELD)`, polarity in the literal | no |
| `isNull` | `closedAt: { isNull: true }` | `FieldConstraint(IS_NULL)`, polarity in the literal | no |
| `$ref` (as an operand) | `id: { eq: { $ref: o.customerId } }` | `JoinConstraint` | hash, on the join |

`between`'s bounds are named rather than positional, and both inclusivity flags default to `true`. §2.5 argues the shape: a two-element array cannot express inclusivity without a convention that then has to be documented, validated, and remembered. `from` and `to` are individually optional, so `{ between: { from: 100 } }` and `{ gte: 100 }` compile to the identical `RangeConstraint` — prefer the short form.

#### 6.2.2 The five actions

One block per verb. Each compiles to the `ActionDefinition` subtype of the same name (§2.5); `{ $ref: … }` in a `then` block resolves at **fire time** against the tuple, not at compile time.

```yaml
then:
  # 1. Mutate a bound fact. Routes through update() (§2.2); several setFields on one
  #    target merge into a single update, applied in declaration order (§4.6).
  - action: setField
    target: o                      # an alias bound by `when`
    field: status                  # dotted path; compiles to /status
    value: "REVIEW"                # a literal, or { $ref: c.riskTier }

  # 2. Insert a derived fact. Its handle is allocated at stage time, so later
  #    actions in the same RHS may reference it (§4.6).
  - action: insertFact
    fact: RiskSignal
    as: sig                        # optional; binds the new handle for later actions
    payload:
      orderId:  { $ref: o.id }
      severity: "HIGH"

  # 3. Retract a bound fact. Retracting a fact inserted by this same RHS cancels
  #    both effects at commit rather than propagating an insert and a retract.
  - action: retractFact
    target: sig

  # 4. Emit an event. Staged; delivered at commit, in firing order, to the session's
  #    EventSink — which by default collects into FireResult (§4.6).
  - action: emit
    event: "order.flagged"
    payload:
      orderId: { $ref: o.id }
      reason:  "high value + risk tier"

  # 5. The escape hatch (§11.3). Dispatches by name to a pre-registered Java function.
  #    Runs at commit and is NOT transactional — see §4.6. Arguments are resolved to
  #    values and deep-copied before the handler sees them (§2.5).
  - action: callFunction
    name: notifySlack              # must be registered, or it is a compile error
    args:
      channel: "#risk-review"
      orderId: { $ref: o.id }
```

#### 6.2.3 The rule file

A rule file is a **list** of rules under a `rules:` key, with a file-level header. One file may hold many rules; a rule set is compiled from many files, and `id` must be unique across all of them (§6.5 rejects duplicates).

```yaml
apiVersion: rules.v1               # the rule-file schema version §6.5 validates against
rules:
  - id: high-value-order-review
    salience: 10
    noLoop: true
    agendaGroup: review            # optional; §4.5 defers grouping to v2
    tags: [fraud, manual-review]
    when:  [ ... ]
    then:  [ ... ]
  - id: auto-approve-low-value
    ...
```

`apiVersion` is what lets the rule-file JSON Schema (§8) evolve without silently reinterpreting existing files: a file naming an unknown version is a compile error, not a best-effort parse. Everything else in a rule's body maps one-to-one onto `RuleDefinition` (§2.5) — `id`, `salience`, `noLoop`, `agendaGroup`, `tags`, `when`, `then` — with `salience` defaulting to `0`, `noLoop` to `false`, `agendaGroup` to unset, and `tags` to empty.

**On the reference syntax, and its remaining ambiguity.** A `$`-prefixed *string* sigil (`"$o.customerId"`) overloads the string literal space: a customer id genuinely beginning with `$` is unexpressible, and `{ eq: "$100 tier" }` silently parses as a reference to a nonexistent alias. The structural form removes that.

It does not remove ambiguity *entirely*, and claiming otherwise would be wrong: §2.6.1 defines `EQ` on objects as structural equality, so a literal object that happens to have a `$ref` key is now the unexpressible case instead. That is a far better trade — `$`-prefixed keys are conventionally reserved in JSON tooling, and an object-valued equality against a `$ref`-keyed document is vanishingly rarer than a string beginning with `$` — but it needs an escape rather than a claim of perfection. **A literal object whose first key is `$ref` is written `{ $$ref: … }`**, and the compiler rejects any `$`-prefixed key it does not recognize rather than passing it through. `$ref` also extends cleanly (`{ $ref: …, transform: … }`) where a string sigil could not.

**Interpolation in `then` payloads is the same mechanism, deliberately.** The `emit` payload above uses `{ $ref: o.id }` rather than a string sigil, for consistency and for the same escaping reason. Note the two resolve at different times — a `where` reference resolves at compile time against the join graph, a `then` reference at fire time against the tuple — so they are separate code paths sharing one syntax. Don't unify the implementations.

### 6.3 Why operator maps rather than a general expression language

1. **Indexability.** The engine can only build an index for a constraint it can statically decompose into `(field, operator, literal)`. A free-form boolean expression can express far more, but the engine generally can't extract an index plan without writing a mini-optimizer — so free-form expressions land on the slow, unindexed path. Operator maps keep the fast path the *default*, not something you opt into.
2. **Blast radius of "config that's actually code."** This is much of what makes MVEL/DRL-style engines feel heavy: the DSL stops being reviewable-by-non-engineers config and becomes a programming language with its own footguns — side effects, unbounded loops, unclear per-expression cost. Operator maps are diffable, lintable, and have bounded, predictable evaluation cost.

**One exception to "safe," worth naming rather than glossing:** `matches` embeds a regular expression, and on a backtracking engine that is a denial-of-service vector reviewed as configuration — the "it's just config" property failing exactly where a reviewer is least likely to look. §2.6.3 requires RE2 for rule-authored patterns; with that in place the claim holds, and without it `matches` deserves the same explicit-cost treatment as the escape hatch below.

### 6.4 The escape hatch

For genuinely complex boolean logic (nested AND/OR/NOT, arithmetic across fields) that operator maps make awkward, add an explicit, opt-in expression form rather than inventing one:

```yaml
when:
  - fact: Order
    as: o
    condition: "o.total > 10000 && (o.region in ['US','EU'] || o.priorityFlag)"
```

Use **[CEL (Common Expression Language)](https://cel.dev/)** rather than MVEL/SpEL/Groovy. It is designed for exactly this "safe expressions embedded in config" case (Kubernetes admission policies, IAM conditions), is non-Turing-complete and **guaranteed to terminate**, sandboxed by construction, and has a maintained Java implementation, [`cel-java`](https://github.com/cel-expr/cel-java) (Maven coordinates `dev.cel:cel`).

**Be precise about the guarantee, because the loose version of it is the same class of overclaim §6.3 criticizes.** CEL guarantees termination and non-Turing-completeness — *not* linear time. It has comprehension macros (`exists`, `all`, `map`, `filter`), and nested comprehensions over two lists are O(n·m). This is why `dev.cel` ships a static cost estimator and a runtime cost limit. **Set both**: estimate at compile time, reject expressions over a configured budget, and enforce the runtime limit as a backstop.

Treat `condition:` as an escape hatch that bypasses indexing (falling back to a `postFilter` or unindexed alpha test) — explicit, visible cost, not a hidden default. Two implementation obligations come with it:

- **The paths a condition reads are tested paths (§3.4.1).** A rule whose condition reads `o.total` tests `o.total` as surely as `{ gt: 1000 }` would, and §3.4.1's update gate has to see it — otherwise an update that makes a condition newly true propagates nothing and fires nothing, while the equivalent retract-plus-insert fires, and §9's Phase 1 exit criterion is missed for exactly the rules that reached for this escape hatch. See the amendment below for what that obligation is implemented as, and what it costs an author.

- **Compile once, evaluate many.** CEL programs are compiled in §6.5's pipeline and cached in the `CompiledRuleSet` alongside pointers and regexes. A compiled program is immutable and shareable; the per-evaluation activation is not. Never compile at match time.

> **Amendment (Phase 3).** This obligation was absent from this section and from the implementation until Phase 3, and the omission is instructive rather than embarrassing. It could not be caught by the differential suite the rest of this engine leans on: the gate runs in working memory, upstream of the matcher, so every matcher was identically wrong and `MatcherEquivalence` proved only that they agreed. A differential test establishes that two shapes agree, never that they agree on the right answer.
>
> The fix records **the payload root** for every fact type an alias referenced by the condition binds — not the specific paths. Extracting read paths from the compiled expression is the precise answer, and dev.cel exposes the AST for it, but it makes this compiler permanently responsible for being a superset of what an arbitrary expression reads: under-declare by one path and a firing is lost silently. That is the `dependsOn()` trap §11.2 rejected, and §11.2's own escape from it — declare the root and be "instantly correct-but-conservative" — is the one taken here.
>
> Note "every alias the condition references", not the pattern it hangs off: a condition on an `Order` pattern reading `c.creditLimit` is falsified by an update to the `Customer`, and recording only the `Order` would leave that update un-propagated — the same defect, one alias over.
>
> **What conservatism costs here is not performance, and this is the paragraph to read before writing a `condition:`.** Because the root is a tested path, *any* update to a fact the rule binds counts as a change to a path the rule tests — including a field no rule reads at all. Two consequences follow, and neither is a slow path:
>
> - **The rule re-fires.** §3.4.1 step 5 clears refraction for exactly the rules testing a changed path, so a condition-carrying rule un-refracts on every update to its facts and fires again on data whose condition-relevant content did not change. §4.4's own complaint about type-wide scoping — "a rule firing twice because an unrelated rule's field changed, which no author can predict from reading their rule" — arrives here through a different door, and by a field *no* rule reads. Per-rule scoping does survive: only the condition-carrying rule un-refracts, not its neighbours on the same type.
> - **A rule that terminated can stop terminating.** A right-hand side that mutates a fact the rule binds — `setField(o, "seen", o.seen + 1)`, an ordinary "stamp an attempt counter" shape — fires once without a condition and runs to `maxCycles` with one. **Give such a rule `noLoop`** (§4.5 exists for exactly this); it restores single firing.
>
> This is the one cost the precise alternative would not have paid, which is why it belongs beside the argument for conservatism rather than in a performance note. §3.4.2's fast path is unaffected either way: an identical payload short-circuits before any tested path is consulted, so a producer re-sending an unchanged record still propagates nothing.

And note that a cost limit bounds a *single* evaluation, not how many times the engine runs it. An unindexed CEL condition against 100,000 facts is 100,000 evaluations per fire cycle. Cheap-per-call is not cheap.

Avoid MVEL/SpEL/Groovy for this slot: they are general-purpose scripting languages, not designed for the "fast, safe, compile-once-evaluate-often, no side effects" contract you want, and using them is a good chunk of what makes engines built on them feel heavy.

### 6.5 Compilation pipeline

```
YAML/JSON rule-file text
  → Jackson parse → intermediate POJO tree (§2.5's note on the two Jackson uses)
  → JSON-Schema validation of the RULE FILE (fail fast, before touching the network)
  → build RuleDefinition (constraint AST, join graph, $ref alias resolution)
  → semantic validation
        · every $ref resolves to an EARLIER alias (join graph is a DAG, no forward refs)
        · same-type aliases in one rule get an implicit inequality (§1)
        · fact-payload schema checks where a SchemaRegistry entry exists (§2.3):
          type-incompatible literals are an ERROR; NE/NOT_IN on an optional path is a WARNING
        · duplicate rule ids, unreachable rules, empty when/then
  → literal canonicalization (numerics via stripTrailingZeros; one Java type per class — §2.6.2)
  → node sharing FIRST (structural hash on AlphaNode definitions — two rules with an identical
    single-fact constraint share one AlphaNode; keeps the ALPHA network sublinear in rule count)
  → PatternNode construction (one per pattern, terminating its shared alpha chain — §3.2.4)
  → node id assignment (dense 0..n-1, fixed for the life of the CompiledRuleSet)
  → compile field accessors (JsonPointer), regexes (RE2), CEL programs — all immutable, cached
  → derived compile-time artifacts, all computed ON THE SHARED GRAPH:
        · TestedPaths: per-type union, per-rule sets, inverse index (§2.4) — read straight
          off each rule's own constraint fields; no node-level extraction pass
        · the type→rules index that drives dirty marking (§4.1)
        · the index plan (which paths hash- vs range-indexed, on which node)
        · the tested-path prefix trie (§3.4.2)
  → CompiledRule per rule: terminal node id, compiled actions, per-rule tested paths (§4.2)
  → rule-set version hash (§5.6)
  → emit CompilerReport (§7.4)
  → wrap in immutable CompiledRuleSet
```

**Node sharing comes before dependency and reachability extraction**, not after. Both are per-node properties, and sharing changes which nodes exist — computing them first means computing them for nodes that are about to be merged away, and then either recomputing or, worse, carrying stale sets into the shared graph.

**On "sublinear in rule count":** node sharing is genuinely sublinear for the *alpha* network, where duplicate single-fact constraints across rules are common. The *beta* network is weaker — joins share only when two rules share an identical prefix of patterns and constraints, which is far less frequent — so total network size grows sublinearly in distinct constraints but closer to linearly in distinct join shapes. Both are enormously better than no sharing; don't promise a flat curve and then measure one that isn't.

---

## 7. Observability, Diagnostics & Determinism

"Why did this rule fire?" and "why didn't it?" are the questions a rule engine gets in production, from people who cannot read the network — and the reason engines acquire a reputation for opacity is rarely the algorithm, it's that answering those questions after the fact is hard. Every mechanism here is cheap to design in now and expensive to retrofit, because each needs a hook at a point the optimizer would otherwise be free to elide.

### 7.1 Event listeners and the firing trace

```java
public interface RuleEngineListener {
    default void onInsert(Fact fact) {}
    /** changedTestedPaths: only paths the NETWORK tests — not every path that changed. */
    default void onUpdate(Fact before, Fact after, Set<JsonPointer> changedTestedPaths) {}
    default void onRetract(Fact fact) {}

    default void onActivationCreated(Activation a) {}
    default void onActivationCancelled(Activation a, CancelReason why) {}
    default void onActivationSuppressed(ActivationKey key, SuppressReason why) {}
    default void onBeforeFire(Activation a) {}
    default void onAfterFire(FireRecord record) {}
    default void onRhsError(Activation a, ActionDefinition failed, Throwable cause) {}
    default void onEmit(String eventType, JsonNode payload, EmitContext ctx) {}
}

/** What one firing actually did. This record IS the audit log for anything regulated. */
public record FireRecord(
    ActivationKey key,
    long recency, int salience,                    // the conflict-resolution inputs
    List<ActivationKey> runnersUp,                 // who lost — BOUNDED, and empty by default
    List<StagedEffect> effects,                    // §4.6's staging buffer, committed
    List<EmittedEvent> emitted,
    Duration took
) {}

public sealed interface StagedEffect
    permits FieldSet, FactInserted, FactRetracted, FunctionCalled, EventEmitted {}

/** A pending activation that will not fire because its facts changed under it. */
public enum CancelReason { RETRACTED, UPDATED }
/** A match that will not fire although its facts still satisfy the rule. */
public enum SuppressReason { REFRACTED, NO_LOOP }
```

Registered per session via `SessionOptions`, so a listener is never shared mutable state across sessions and nothing on the path synchronizes.

> **Amendment (Phase 4).** The sentence above is wrong and the implementation does not follow it.
> `SessionOptions` is per *configuration*, not per session: one instance is built once and used to
> create many sessions, which is exactly what `RuleBatches.run(rules, inputs, batch, options)` does,
> N times concurrently. A listener held in options is therefore shared mutable state across
> sessions, and a caller wanting a trace out of a batch run has no other move. **A listener
> implementation must be safe for concurrent use**, and the ones shipped here are: `TracingListener`
> locks around its deque, `JfrListener` holds no instance state. The same correction applies to
> `HostFunction`, which reaches every session through `options.functions()` by the same route.
>
> Listener dispatch happens once per firing rather than once per candidate, so this costs
> approximately what §7.1's own `NoOpListener` argument already accepts. The alternative --
> resolving a listener per session the way `EventSink` is resolved -- is not available, because a
> listener is the caller's object and the caller expects to read it afterwards.

**Two callbacks need their names taken seriously.** `onUpdate`'s path set is only what the *network tests* — a listener used as an audit log would under-report actual changes, so the parameter is named for what it is. And refraction may suppress a match before an `Activation` object exists at all — under Rete it is never created, under TREAT it is created during recomputation and dropped at selection (§4.1, §4.4) — so a callback taking an `Activation` would be unimplementable in one shape and misleading in the other. Suppression gets its own callback taking the `ActivationKey`, which both shapes always have.

**`FireRecord` carrying the staged effects and the runners-up is the load-bearing part.** §4.6 stages an RHS's effects and commits them atomically, so one record answers "what did this firing do" without reconstructing it from a stream of mutations. And "why did B fire before A" is a top-three question that is unanswerable from a record naming only the winner.

**But `runnersUp` must be bounded and off by default, or it silently costs a full conflict-set sort on every firing.** The agenda selects a *maximum* (§4.3's heap gives you the head, not an ordering); producing the complete ranked list of losers means sorting everything eligible, once per fire cycle — which would make the trace the dominant cost of firing and would contradict `NoOpListener` costing nothing. So: `runnersUp` holds at most `runnersUpLimit` entries (a `SessionOptions` field, §7.5, default 3), it is populated only when at least one listener is registered or `dryRun` is set, and it is an empty list otherwise. A bounded top-N answers "why did B fire before A" completely — the question is about the activations that nearly won, never about the four-hundredth-ranked one.

Ship three implementations, because the interface alone is not the feature:

- **`NoOpListener`** — the default, and it must genuinely cost nothing: check for an empty listener list at the call site rather than iterating one per event on the hot path.
- **`TracingListener`** — a bounded ring buffer of the last N `FireRecord`s, exposed on `FireResult` and on `RuleEngineLimitExceeded` (§4.7). A runaway loop is almost always visible in the last dozen firings.
- **`JfrListener`** — JDK Flight Recorder events. Near-zero cost when disabled, always available in production, and it lets a rule-firing timeline be correlated against GC, allocation, and virtual-thread scheduling in one recording — which is how you answer "is the engine slow, or is it waiting?"

### 7.2 Explainability

**"Why did rule R fire?"** is answerable from the trace: `FireRecord` names the rule, the bindings, the conflict-resolution inputs, the runners-up, and every effect.

**"Why did rule R *not* fire?"** is much harder, because a non-firing is the absence of a record. It needs an explicit diagnostic mode:

```java
public interface MatchExplainer {
    Explanation explain(String ruleId);
    Explanation explain(String ruleId, Map<String, FactHandle> proposedBindings);
}

public record Explanation(
    String ruleId,
    List<PatternResult> patterns,   // one per POSITIVE pattern, in declaration order
    List<NegationResult> negations, // one per NOT_EXISTS pattern; see §1's amendment
    Optional<String> verdict,       // "no Customer fact of this type exists"
                                    // "3 Orders matched total>10000; all failed status EQ PENDING"
                                    // "matched, but refracted — already fired at recency 4471"
    boolean complete                // false when a budget stopped the walk: counts are lower bounds
) {}

/** What happened to one pattern: how many facts were considered, how many survived, and
 *  — the part that answers the question — which constraint eliminated the rest.
 *  A negated pattern has neither a candidate population nor survivors, so it gets its own
 *  record whose numbers run the other way: how many facts are PRESENT, and how many complete
 *  matches their presence suppressed. See §1's amendment. */
public record PatternResult(
    String alias,
    String factType,
    int considered,                      // facts of this type in working memory
    List<Long> survivors,                // handles still matching after every constraint
    Optional<ConstraintFailure> firstFailure,
    Optional<String> joinNote            // e.g. "12 survivors, none joined to any o (o./customerId)"
) {}

/** The eliminating constraint, with the value that failed it — "status was SHIPPED,
 *  expected PENDING" is the sentence an author needs, and it needs the actual value. */
public record ConstraintFailure(Constraint constraint, long exampleHandle, JsonNode actualValue, int eliminated) {}
```

**The two overloads answer different questions.** `explain(ruleId)` asks "why did nothing match" and reports per pattern over all candidates — the right tool when a rule is silent. `explain(ruleId, proposedBindings)` asks the sharper and more common question: "I am looking at *these specific facts* and I expected them to match — what stopped them?" With the bindings pinned, every pattern has exactly one candidate, so the answer is a single chain of constraint evaluations ending at the first `false`, including join constraints and any `postFilter`/CEL condition. Unbound aliases in the map are resolved as in the one-argument form. This is the overload rule authors will actually reach for, and it is the one whose output can be a flat list rather than a set of population counts.

This runs **off** the hot path, on demand, against a live or reconstructed session. It deliberately re-evaluates constraints one at a time rather than using the network, because the network is optimized to *not* compute what you want here: indexes skip non-candidates without recording why. Accept the slow path — correctness of the explanation matters, speed does not.

The three verdicts above cover most real cases, and the third — **refraction** — is the one nobody guesses. A rule that "stopped working" has usually already fired on those exact facts. Say so explicitly, with the recency at which it fired.

**Make this reachable without a debugger.** A CLI (`explain --rule high-value-order-review --facts snapshot.json`) plus `dryRun` (§7.5) turns this from an API into something authors use. Rule authors are the audience of §6's whole argument; giving them a declarative language and no way to debug it undoes the point.

### 7.3 The determinism contract

**Same rule set + same facts + same insertion order ⇒ same firing sequence**, on every host and every run. This is invariant 2, and it is not free — it is a property you keep by getting a series of small decisions right and lose permanently by getting any one wrong.

Why it matters: it is what makes rules *testable* (a golden-output test is meaningless otherwise), incidents *reproducible* (replay the stream, get the same decision), and decisions *auditable* months later. An engine that is 99.9% deterministic has none of these properties.

**Scope of the guarantee.** It holds given (a) deterministic `callFunction` handlers and `EventSink`s — these are host Java and the engine cannot constrain them — and (b) no parallel alpha evaluation (§5.3), or a deterministic merge if you enable it. Both exclusions are the caller's to honor, and both are lintable.

| Threat | Mitigation |
|---|---|
| `HashMap`/`HashSet` iteration order reaching the agenda (index buckets, memories, node fan-out) | Ordered structures on every path whose iteration order can affect activation order: `LinkedHashSet` buckets (§3.3), id-sorted fan-out. This is the one that bites, because it usually *looks* stable in testing |
| A `ConflictResolutionStrategy` returning 0 for distinct activations, or non-0 for equal ones | Total order, consistent with `equals`, tie-broken on the key (§4.2). Asserted in strict mode |
| Random or time-derived values in identity | Handles are a session-local counter (§2.1). Nothing on the matching path reads a clock or an RNG |
| Parallel alpha evaluation (§5.3) | Merge results ordered by handle id before anything downstream sees them, or don't parallelize |
| `Instant.now()` in a rule, via `callFunction` or CEL | Inject time as a fact or a bound CEL variable; never read the clock inside evaluation. Then a replay can supply the original timestamp and reproduce the original decision. Lint for it |
| Iteration over `factsOfType` in an RHS | Snapshot semantics, ascending handle id (§2.4) |

**Test it, don't assume it.** A shuffle test — same facts, N different *internal* iteration orders where order shouldn't matter, assert identical firing sequences — belongs in the Phase 0 suite alongside the naive-matcher oracle. It is the only way to catch a `HashSet` that crept in.

### 7.4 The compiler report

The compiler knows things that are painful to discover under load. Emit them as data from `CompiledRuleSet.report()`:

- **Every unindexed constraint**, with rule id and reason (`NE`, `MATCHES`, CEL, no schema). §10's "no unknown unindexed access" is a goal you can only hold yourself to if you can enumerate the violations.
- **Shallow tested paths over large subtrees** (§3.4.2) — a rule constraining `/customer` compares that whole subtree on every update to the type. A performance smell with a usually-easy fix.
- **CEL cost estimates** per expression, against the configured budget (§6.4).
- **Node-sharing statistics**: rule count, distinct alpha nodes, sharing ratio, pattern-node count, beta network size — how you check §6.5's sublinearity claim against your actual rule set instead of trusting it.
- **Warnings** from §2.6.1 (`NE` on an optional path) and §6.5's semantic validation.
- **Rules with no reachable activation path**, e.g. a pattern on a type nothing ever inserts.

```java
/** Produced once by §6.5, frozen into the CompiledRuleSet, reachable via report().
 *  Data, not a printed string — CI asserts on it and the DSL tooling renders it. */
public record CompilerReport(
    String ruleSetVersion,                    // §5.6's content hash
    List<Diagnostic> errors,                  // compilation already failed if non-empty
    List<Diagnostic> warnings,
    List<UnindexedConstraint> unindexed,      // every one, with its reason
    List<CelCost> celCosts,                   // estimate vs. budget, per expression — §6.4
    SharingStats sharing,
    List<String> unreachableRules             // no activation path, e.g. a type nothing inserts
) {}

public record Diagnostic(String ruleId, String code, String message, Optional<String> fieldPath) {}
public record UnindexedConstraint(String ruleId, String alias, String field, Reason reason) {
    public enum Reason { NE, NOT_IN, MATCHES, CEL_EXPRESSION, NO_SCHEMA, RESIDUAL_JOIN_CONDITION }
}
public record CelCost(String ruleId, long estimated, long budget, boolean overBudget) {}
/** How to check §6.5's sublinearity claim against YOUR rule set rather than trusting it. */
public record SharingStats(
    int ruleCount, int distinctAlphaNodes, int patternNodes, int joinNodes, double alphaSharingRatio
) {}
```

Fail the build on errors; surface warnings in CI. A rule set is source code and deserves the same treatment.

### 7.5 Strict mode and dry runs

Several sections above assign obligations to "strict mode." It is one `SessionOptions` flag, defined here so those obligations have one home rather than six independent implementations.

```java
/** Built, never constructed positionally — see the note below. */
public final class SessionOptions {
    private final FireOptions limits;                  // maxCycles, maxFacts (§4.7)
    private final ConflictResolutionStrategy conflict;
    private final EventSink events;                    // §4.6
    private final RhsErrorHandler onRhsError;          // §4.6
    private final List<RuleEngineListener> listeners;  // §7.1
    private final boolean strict;                      // the table below
    private final boolean dryRun;                      // match and explain; execute no RHS
    private final int runnersUpLimit;                  // §7.1 — default 3; 0 disables

    public static Builder builder() { return new Builder(); }
    public static final class Builder { /* defaults per field; build() copies defensively */ }
}
```

**A builder, not a record, and the reason is Phase 3.** Every deferral in this document — the Rete join strategy (§11.5), differential propagation (§11.2), a session eviction policy (§4.4), a wall-clock budget (§4.7) — lands here as a new option when it arrives. Adding a component to a record breaks every positional construction in every caller's code; adding a method to a builder breaks nothing. This is the two-line change that makes §11.1's "additive, not a rework" promise true for the configuration surface as well as for the engine, and it costs nothing to make now.

Note what is *not* here: a `joinStrategy` field. v1 has one join strategy and one agenda shape, so a selector would be dead config — a knob with one position, which readers reasonably assume has two. It arrives with the second shape.

**`strict` turns on every check that is too expensive for production but catches a contract violation deterministically in test:**

| Check | Guards against | Stated in |
|---|---|---|
| `Fact.payload()` returns a `deepCopy()` | A caller mutating an engine-owned payload behind the network's back | §2.2 |
| `update` rejects a payload aliasing or sharing a subtree with the stored one | The `get().payload()` → mutate → `update()` sequence that diffs an object against itself | §2.2 |
| `insertOwned`/`updateOwned` payloads hashed on entry, re-checked on read | A caller violating the ownership-transfer contract | §2.2 |
| No two queued activations compare 0; no two `equals` activations compare non-0 | A `ConflictResolutionStrategy` that is not a total order or not consistent with `equals` | §4.2 |
| Per-`callFunction` wall-clock timeout enforced | A registered function blocking the session indefinitely | §4.6 |

Every one detects a violation of a contract this spec states but cannot enforce at compile time. **Run the full suite under `strict` in CI, and never enable it in production** — several checks are O(payload) or worse per operation. A check cheap enough to leave on always does not belong in this table; make it unconditional instead.

**`dryRun` runs matching and conflict resolution but executes no RHS**, staging effects and discarding them. It answers "what *would* fire, in what order, on these facts" — what an author needs before shipping a rule change, and the mechanism behind §5.6's run-both-and-compare cutover. With `TracingListener` it produces a firing plan the author can diff against the previous rule set's.

---

## 8. Suggested module layout

```
rule-engine-core/          Fact, FactHandle, WorkingMemory, RuleDefinition, network nodes,
                           NodeMemory, Agenda, refraction, RuleSession, SessionActor,
                           listener interfaces
rule-engine-compiler/      RuleDefinition → CompiledRuleSet (node sharing, node ids, index plan,
                           TestedPaths, accessor/regex compilation, CompilerReport)
rule-engine-dsl/           JSON *and* YAML rule files → intermediate POJO → RuleDefinition,
                           plus the rule-file JSON Schema
rule-engine-schema/        optional SchemaRegistry (§2.3) — shares JSON Schema tooling with the dsl module
rule-engine-cel/           optional: `condition:` escape hatch backed by dev.cel
rule-engine-observability/ TracingListener, JfrListener, MatchExplainer, the explain CLI (§7)
rule-engine-testkit/       naive-matcher oracle, differential/property-based harness,
                           shuffle-determinism tests, JMH benchmarks, rule-file fixtures
```

**Two notes on the boundaries.**

*One DSL module, not one per serialization.* §6.1 argues for one object model and one parser, which is precisely why two modules make no sense: the entire difference is `ObjectMapper` vs `YAMLMapper`, one factory choice against an identical target type. Two modules means two build files and two versions to keep in step, to encapsulate one line. If YAML's transitive parser is unwanted in some deployment, make it an `optional` dependency. (Under Jackson 3 that transitive is `org.snakeyaml:snakeyaml-engine`, not Jackson 2's `org.yaml:snakeyaml` — an exclusion written against the old coordinates silently excludes nothing.)

*`rule-engine-testkit` is not optional.* The roadmap leans on it in three places: Phase 0's naive matcher is the correctness oracle for every later phase, §11.2's chosen update semantics have oracle-equivalence as a Phase 1 exit criterion, and §7.3's determinism contract needs shuffle tests. All three are things consumers want too — a rule author testing their own rule set wants the same fixtures and oracle. Left in another module's `src/test`, they are unusable from outside and quietly rot.

Concurrency helpers live in `-core`: `SessionActor` and the virtual-thread wrappers are a few hundred lines with no dependencies beyond the JDK, and a module boundary there buys nothing while making "how do I run this concurrently" an extra artifact to discover.

**On `-core`'s Jackson dependency**, a deliberate change from a POJO-based design: `-core` has a real, load-bearing dependency on `jackson-databind`, because the fact model itself is JSON-native (§2.2) rather than an `Object` that happens to support JSON as one representation. You are betting on Jackson's tree model as the canonical in-memory representation everywhere, not just at the DSL boundary. Given Jackson is mature, ubiquitous, and something you'd pull in for the DSL layer regardless, this is a reasonable bet — but a real one: `-core` is not usable without Jackson on the classpath.

---

## 9. Phased roadmap

| Phase | Deliverable | Exit criteria |
|---|---|---|
| 0 | `Fact`, `FactHandle`, `WorkingMemory`, `RuleDefinition`, comparison semantics (§2.6.1), **refraction** (§4.4), **naive linear matcher** (no network), testkit skeleton | Correctness suite passes and becomes the oracle for every later phase; `fireAllRules` **terminates** on a rule whose RHS mutates nothing; shuffle-determinism test green (§7.3) |
| 1 | `AlphaNode` network + `PatternNode` alpha memories + field indexing (§3.3) + `TestedPaths` + gated retract/reassert `update` (§3.4.1) + `RuleEngineListener`/`TracingListener` | Single-fact rules match via index lookup, not full scan; an update to an untested field is a *measured* no-op — asserted on a counter, not inferred; an update that *does* change a tested path is oracle-equivalent to `retract`+`insert` with the handle preserved; trace answers "why did R fire" |
| 2 | `JoinNode` network, **TREAT-style** + the TREAT agenda and dirty-rule tracking (§4.1) + RHS staging and error handling (§4.6) + `MatchExplainer` (§7.2) — **this is v1** | Multi-fact rules work; one-shot/batch sessions fully supported; results identical to the Phase 0 oracle on the full corpus; explain answers "why did R *not* fire" |
| 3 | Streaming sessions: `SessionOptions`-selectable persistent beta memory (Rete `JoinNode`), the Rete agenda shape and its `activate`/`deactivate` interface (§4.3), differential propagation (§11.2), `fireUntilHalt` + hardened `SessionActor` (§5.4), session fact-eviction (§4.4) | Long-lived session with streaming inserts amortizes join cost; **TREAT and Rete produce identical firing sequences on the same input** (§11.5) — established by differential test against the v1 engine, which by then is a shipped oracle, not a thought experiment; a streaming session under sustained insert-without-retract load reaches a steady-state heap |
| 4 | Concurrency layer: immutability audited, virtual-thread helpers, hot-reload holder (§5.6) | N concurrent sessions on M cores scale near-linearly for the batch case; rule-set swap under load drops nothing and mixes nothing |
| 5 | DSL front-end: JSON/YAML parsing, rule-file schema validation, operator-map compiler, `CompilerReport` (§7.4), optional CEL, optional `SchemaRegistry` | An author writes YAML, never touches Java, gets index-eligible rules by default, and gets a report naming every constraint that isn't |
| 6 (stretch) | Negation/`NOT_EXISTS` ✔, accumulate, truth maintenance, temporal/CEP, distributed sharding | `NOT_EXISTS` is built — see §1's amendment, which also records what it cost to land it without truth maintenance. The rest is out of scope; §1 says what each costs and what the interim answer is |

**Phase 1's `update` criterion has two halves, and they test different things.** The *correctness* half is now nearly structural — `update` on a changed tested path **is** retract+insert (§3.4.1) — so the gate is narrow and specific: the handle survives, refraction is cleared for exactly the rules testing a changed path and no others, and the retract half computes its index keys from the **old** payload. Test update-after-fire explicitly; a rule that fired, then had an untested field change, must not fire again, and a rule that fired, then had a tested field change, must. The *performance* half is the no-op path: assert on a counter that an update touching no tested path performs zero network traversals. Assert it, don't infer it — "no propagation happened" is trivially satisfied by an implementation that never propagates.

**Refraction is a Phase 0 deliverable.** It is not an optimization — without it `fireAllRules` does not terminate on ordinary rule sets (§4.4) — so it belongs with the naive matcher, where it is ten lines and trivially testable, not bolted onto an optimized network later.

**Observability starts in Phase 1.** Listeners are cheap to add while the network is small and painful to thread through afterwards, and you will want the trace to debug Phases 2–3 anyway. Building diagnostic tooling after the thing it diagnoses is how it ends up never built.

**Phase 0 is a real deliverable.** Build and benchmark it: it is the correctness oracle for every later phase and the baseline proving each optimization helped. Phases 0–2 deliver a complete engine for one-shot/batch sessions — the whole v1 target. Phase 3 is the "add streaming later" half of §11.1's plan, not a prerequisite for shipping.

---

## 10. Correctness & performance checklist

**Correctness first — these produce silently wrong output rather than slow output.**

- **Tuples bind handles, never `Fact` objects** (§3.2.2). Audit every time someone adds a node type. It is the difference between differential update being an optimization and being a stale-data bug.
- **The update's retract half runs against the old payload, before the new one is installed** (§3.4.1 steps 3–4), and **retract propagation never re-evaluates a test** (§3.4) — it removes by handle identity. Both, or you get orphaned index entries and permanent phantom matches.
- **The update reuses the handle** (§3.4.1 step 4). This is not cosmetic: refraction is keyed on `(ruleId, handles)`, so handle reuse is what keeps a rule that tests nothing changed from re-firing after its match is destroyed and recreated. Allocate a fresh handle and every rule re-fires on every update.
- **Refraction invalidation is scoped per rule** (§3.4.1 step 5, §4.4), via `TestedPaths.rulesTesting`. Type-wide clearing makes a rule re-fire because an unrelated rule's field changed.
- **RHS `setField` deltas materialize onto a `deepCopy()` of the stored payload, committed via `updateOwned`** (§4.6). Applying them in place makes §3.4.1 step 1 diff an object against itself, propagate nothing, and leave every index stale — §2.2's aliasing bug, arriving through the engine's own RHS path.
- **Dirty-rule tracking is per-rule tested paths, not alpha-memory deltas** (§4.1). The alpha-memory predicate misses join-key-only updates and serves stale joins.
- **Refraction is on, always** (§4.4), checked at *selection* (§4.1), invalidated per-rule (§4.4). Verify termination on a rule whose RHS mutates nothing.
- **Numeric keys canonicalized with `stripTrailingZeros()` before hashing; `compareTo`, never `equals`, for ordering; one Java type per compatibility class** (§2.6.2).
- **Comparison semantics match §2.6.1's table** — absent ≠ null, cross-type is false, `NE` is true for absent fields and false against an explicit null. Table-driven tests, one per cell.
- **The conflict-resolution comparator is a total order and consistent with `Activation.equals`** (§4.2). Assert both in strict mode.
- **`Tuple` and `ActivationKey` hand-write `equals`/`hashCode` over their arrays and copy defensively** (§3.2.1). A record's generated versions are identity-based on arrays, which silently defeats refraction entirely.
- **Determinism holds** (§7.3): ordered structures on every path reaching the agenda; shuffle test in CI.
- **RHS working-memory effects are staged and committed atomically** (§4.6) — but only against *staging*-phase failure. Commit-phase failure does not roll back working memory, and `callFunction` side effects are never undone; `FireRecord` must record what actually landed.
- **`CompiledRuleSet` is fully immutable** — no index contents, no memories, no listeners, no `SchemaRegistry.register`. A "cache" `Map` on a shared node is the classic way this rots.

**Performance.**

- No path-string parsing in the hot path — precompiled, cached `JsonPointer`s, RE2 programs, CEL programs (§2.6, §6.4).
- Index every `EQ`/`IN`/range constraint at compile time with canonicalized keys (§3.3). Everything unindexable is named in the compiler report — the goal is not "zero unindexed constraints," it's "zero *unknown* unindexed constraints."
- Index buckets support O(1) removal and deterministic iteration; internals keyed on raw `long`, not `FactHandle` (§2.1, §3.3).
- Node memories are primitive-keyed maps, never bare arrays indexed by handle id — that leaks under retract churn (§2.1).
- Node sharing keeps the *alpha* network sublinear in rule count; measure the beta network separately (§6.5).
- `TestedPaths`, the type→rules index, and the prefix trie computed once at compile time, never per update (§3.4.2, §4.1).
- The update fast path is guarded: `equals` short-circuit, then the trie with ancestor marking (§3.4.2). Measure the no-change update — the common case in streaming feeds, and it must not be O(rule set size).
- `deepCopy()` on insert *and* update is `O(payload)` and often the dominant per-operation cost — offer the `Owned` variants at ingestion boundaries and measure the difference (§2.2).
- `maxCycles` **and** `maxFacts` on every fire loop, checked after termination, with the offending activation and the partial `FireResult` on the exception (§4.7).
- Session creation cost bounded and measured — it is your concurrency throughput ceiling (§5.5).
- The actor inbox is bounded, `close()` is bounded, and `fireAsync` coalesces (§5.4).
- Listener dispatch costs nothing with no listener registered — check emptiness at the call site (§7.1).
- CEL expressions have a compile-time cost estimate and a runtime cost limit, both configured (§6.4).
- JMH microbenchmarks per node type (`AlphaNode.test`, `JoinNode` probe, the update-diff path, refraction lookup) plus end-to-end throughput (facts/sec, activations/sec, rules-fired/sec) under concurrent multi-session load — in the testkit from Phase 0.

---

## 11. Design decisions (resolved)

Five decisions, each stating the choice up front, pointing at where it lives, and keeping the rejected options as a record — useful when someone reads this later and wonders why the road not taken was rejected.

### 11.1 Session lifetime profile — **Decided: (A) one-shot/batch first, with (C)'s extension point already in the interface**

Build **TREAT-style joins** (§3.1) as the v1 default, targeting one-shot/batch sessions — Phases 0–2 in §9, the complete v1 engine. The `SessionOptions` builder (§7.5) and the per-implementation `JoinNode` design exist so that adding long-lived streaming sessions with persistent beta memory later (Phase 3) is additive, not a rework: the same node types, the same fact model, and the same `Agenda` interface serve both shapes.

| Option | What it looks like | Pros | Cons |
|---|---|---|---|
| **A. One-shot / batch** — create, insert a bounded batch, fire once, discard | An HTTP handler validating an order: `try (var s = ruleSet.newSession()) { … return s.fireAllRules(); }` | Matches TREAT naturally — no persistent beta memory to maintain or get subtly wrong. Trivially parallel: §5.2's virtual-thread-per-session model is a direct fit. Simple to test — a session's whole input and output are visible in one block | Recomputes joins from scratch every fire — fine for small-to-medium working memory, wasteful if a large fact set is re-evaluated repeatedly with small deltas |
| **B. Long-lived streaming** — a session persists across many inserts over time | Fraud detection watching a customer's transaction stream, re-evaluating as each arrives | Amortizes join cost via incremental propagation — only the delta is re-evaluated per insert. Natural fit for `SessionActor` (§5.4): one session per entity, fed by many producers | Persistent beta memory is real mutable state you must get right (leaks if facts are never retracted; incremental join maintenance bugs are notoriously easy to introduce). Harder to parallelize *within* a session — you rely on across-session parallelism, a different scaling axis |
| **C. Hybrid** — support both, chosen per session via `SessionOptions` | A batch-validation service and a fraud monitor share one `CompiledRuleSet` | Doesn't force a premature choice; the `JoinNode` abstraction was designed for it. Real systems often have both shapes | More surface area to build and test from day one — two `JoinNode` implementations, plus coverage proving they agree |

**Why (A) first rather than committing to (C):** (A) is a strict subset of the work (C) needs and gives you the Phase 0 oracle either way. Building the persistent-memory `JoinNode` before you have a concrete streaming workload would be speculative complexity. The interfaces are shaped for (C) from day one so the decision never has to be revisited as a breaking change.

### 11.2 Update semantics — **Decided: (A′) retract + reassert, gated on a tested-path diff**

`update(h, p)` diffs the new payload against the old at the paths the rule set tests; if nothing tested changed it replaces the payload and returns, and otherwise it runs the ordinary retract/assert machinery **reusing the same handle**. §2.4 for the API, §3.4.1 for the algorithm.

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| **A. Retract + reassert** | `update` = `retract` then `insert`, same handle, bumped recency | Simplest to implement and reason about — one propagation path instead of two. No risk of a differential-update bug producing wrong matches | Re-tests *every* constraint on the fact, including ones on fields that didn't change — and re-traverses the network even when the update is a no-op |
| **A′. Gated retract + reassert** (chosen) | (A), plus a diff at the type's tested paths that short-circuits a no-op update entirely | Keeps (A)'s single propagation path and its correctness properties, and keeps the property authors actually notice: an update touching nothing tested is a measured no-op, not a network traversal | Still re-tests every constraint when something *did* change; pays the diff cost on every update |
| **B. Differential field-level propagation** | Diff tested paths; re-propagate only through the affected subgraph; leave the rest untouched | Meaningfully cheaper for high-frequency updates on facts with many unrelated constraints — the market-data case becomes near-free for untested fields | A permanent, network-wide correctness obligation. See below |
| **C. Immutable fact + versioned handle** (copy-on-write) | `update` returns a new handle; the old is implicitly retracted | Facts stay genuinely immutable — attractive for structural sharing and fact-history audit | Callers must track handle churn; a handle held across an update goes stale, which is a footgun in every API that stores one |

**This decision was previously (B), and reversing it is the largest single reduction in this design.** The reversal is worth recording in full, because (B) is genuinely the better mechanism *for the workload it was chosen for* — and that workload is not v1's.

**What (B) cost.** Correctness under (B) depends on a compile-time `dependsOn()` set on every node being a **superset** of what that node actually reads. Over-declare and you lose performance; under-declare and you lose an activation, surfacing hours later as "the rule sometimes doesn't fire." That obligation is not paid once — it binds every contributor who ever adds a node type, forever, and it is invisible in code review. Around it sat: a `Reachability` service with a downstream-closure computation, the affected-subgraph concept, a prefix trie whose own specification warned it reintroduces missed activations, a caller-supplied change-hints API, two strict-mode checks (one of which required instrumenting field accessors), two compiler-report diagnostics, a per-changed-path dirty predicate, and a load-bearing step ordering in the update algorithm.

**What (B) bought.** Its own worked example is the answer: "a price ticking on a `MarketData` fact 1000×/sec." That is a long-lived session absorbing high-frequency partial updates — §11.1's option (B), which §9 schedules for **Phase 3**. v1 is option (A): create a session, insert a bounded batch, fire once, discard. In that shape updates arrive almost entirely from RHS `setField` (§4.6), at a rate bounded by the number of rules that fire. The mechanism's cost landed in Phase 1; its payoff landed in a phase that has not been built.

**What (A′) keeps.** The user-visible half of the feature — "an update touching no tested path is a measured no-op," §9's Phase 1 exit criterion — is step 2 of §3.4.1 and is unchanged. Handle stability is unchanged. Refraction behaves identically, and for a reason worth internalizing: refraction is keyed on `(ruleId, handles)`, the handle survives the reassert, so a match destroyed and recreated by an update lands on the same key and stays suppressed unless step 5 deliberately clears it. The mechanism that made (B) *correct* is the same mechanism that makes (A′) *cheap*.

**What (A′) gives up, and how to reverse it.** Every constraint on a changed fact is re-tested. If profiling on a real rule set shows that cost dominating — the honest test is a hot fact type with many mutually-disjoint rules under a high update rate — differential propagation goes back in, in Phase 3, alongside the streaming session that motivates it. **That re-addition is cleanly additive**, and the reason is `dependsOn()`'s one-directional soundness condition: add `default Set<JsonPointer> dependsOn() { return ROOT; }` to `NetworkNode` and every existing node is instantly correct-but-conservative, then narrow it per node type against a profile. Nothing about (A′) forecloses (B); it declines to pay for it in advance.

**One thing that did *not* change: tuples still bind handles, never facts** (invariant 3, §3.2.2). Under (B) that was a hard structural precondition — leaving a token untouched is the entire point, and a token carrying a payload would serve pre-update data. Under (A′) every token is rebuilt, so the invariant is no longer load-bearing *for update semantics*. Keep it anyway: it costs nothing, it makes "there is exactly one place a payload lives" structurally true, §3.2.2's worked example is a real bug class the moment anyone hand-optimizes a propagation path, and it is the precondition that keeps (B) available in Phase 3. §0's statement of the invariant has been reworded so it stands on its own rather than on this decision.

> **Amendment (Phase 3, as measured).** The condition this section sets for putting (B) back **is met**, on the workload this section nominates. Whether to build it is open; the measurement is not.
>
> `PropagationBenchmarks` is that workload — a hot fact type, up to sixty four mutually-disjoint rules, an update changing exactly one tested path. Update cost is **linear in the number of rules patterning the type**: 227ns, 742ns and 5 911ns per update at 1, 8 and 64 single-pattern rules, and 3 177ns to 259 789ns under the streaming matcher with two-pattern rules. The fact is retracted from and re-asserted into every pattern memory of every rule that patterns its type, whichever field changed — and (B) touches only the patterns reading a changed path, so its floor is the one-rule column. 26x to 82x, depending on arity and matcher. Profiling agrees: 78% of identified work is pattern-memory churn plus alpha re-testing, against 5.1% for the tested-path diff, which (B) needs and keeps.
>
> **Two things this section says are not borne out.** The gate recovers much less than assumed: with state churn present a one-field update costs 0.79-0.95 of a whole-fact one at realistic arity, because the diff decides *whether* to propagate and the churn afterwards is identical either way. And "re-tests every constraint on the fact" understates it — the re-testing is 6.7% of samples, while the memory and index maintenance that accompanies it is 15.6%.
>
> **What has not changed is (B)'s price**, which this section states and no benchmark can: a `dependsOn()` superset obligation on every node, binding every contributor, invisible in code review, where under-declaring loses an activation that surfaces hours later. The measurement says the prize is real and large. It does not say the obligation is affordable, and that is a scope decision rather than a measured one.
>
> **The cheaper partial fix was scoped, and there is no cheap version of it.** 15.6% of samples are `TreeSet` add and remove inside `PatternMemory`, re-inserting a membership that did not change, and skipping that looked like a contained optimisation. It is not, because the churn spans two separate observer callbacks — `factRetracted(before)` then `factInserted(after)` — so nothing in the network knows it is looking at one update. Making it skippable needs either a per-pattern declaration of the paths it depends on, which is (B)'s obligation in miniature and carries (B)'s failure mode with an extra trap of its own (skipping on alpha paths alone leaves the beta memory holding tuples whose *join key* changed, which is silent wrong output rather than a lost firing); or a value-based comparison that evaluates acceptance and index keys against both payloads, which cannot under-declare but costs a second alpha evaluation to save the churn — 6.7% of samples spent against 15.6% saved, so of order 9% net by profile share, and still requires replacing the retract-and-reassert observer pair with a combined update path that the agenda, refraction, eviction and beta maintenance all depend on. Recorded as measured and not built. The 9% is an estimate from profile shares rather than a measurement, and a third of samples were unwalkable.
>
> **Recorded also because of how it was nearly recorded wrongly.** The first version of this benchmark constrained on a value its payloads never carried, so no fact ever entered a pattern memory and every form of churn (B) removes was absent. It measured alpha evaluation and the diff alone, reported that re-testing did not dominate, and called that workload maximally favourable to (B). It was the one shape where (B) has nothing to win. See `docs/benchmarks.md`.

### 11.3 RHS action vocabulary — **Decided: (A) fixed closed set**

| Option | Shape | Pros | Cons |
|---|---|---|---|
| **A. Fixed closed set** (chosen) | Five verbs; `callFunction` dispatches by name to a pre-registered Java function | Maximally safe and reviewable — a non-engineer reads a rule file and knows exactly what categories of side effect are possible. Trivially lintable: enumerate every external call surface by grepping for `callFunction` names | Every new kind of side effect must be modeled as a registered function first — friction if authors need new action shapes often |
| **B. Extensible action registry** | The registry itself is extended per deployment; `callService`, `publishEvent` become first-class verbs backed by host-implemented handlers | Keeps (A)'s safety while giving first-class syntax to common cases instead of routing everything through generic `callFunction` | An `ActionHandler` SPI and registration mechanism is real infrastructure, plus a versioning question: what happens when a rule file references a verb this deployment hasn't registered? |
| **C. Arbitrary code as RHS** | `then: "session.retract(o); externalClient.call(o); …"` | Maximum flexibility, closest to classic JESS/Drools RHS blocks | Exactly the part of MVEL/DRL-style engines §0 calls cumbersome — unreviewable, unlintable, unbounded side effects, blurs config and code. Reintroduces the escape-hatch risk for *writes*, a materially bigger blast radius than for reads |

**Why (A) and not (B) yet:** move to (B) once there are two or three concrete, recurring action shapes that are awkward through generic `callFunction` — a real signal from actual authoring, not a speculative one. (A) is the smallest thing that keeps the DSL's safety properties intact.

Note the boundary §4.6 draws: `callFunction` is the closed set's escape hatch and it is **not transactional**. Working-memory effects roll back; a sent notification does not.

### 11.4 Fact identity — **Decided: session-scoped `long`, with the global id on the session**

`FactHandle` is `record FactHandle(long id)` (§2.1); the session carries a UUIDv7 `sessionId()`, and external identity is the pair.

| Option | Shape | Pros | Cons |
|---|---|---|---|
| **A. Session-scoped `long`** (chosen) | Dense counter on `WorkingMemory`; one UUIDv7 per session | Half the key width on the hottest maps in the engine. One-word `hashCode`. Enables primitive-keyed collections. No third-party dependency on any hot path | Not globally unique alone — every export must carry the session id alongside it |
| **B. UUIDv7 per fact** | Every handle is a 128-bit time-ordered UUID | Globally unique with no context; correlatable across logs and systems with no extra plumbing | 16 bytes and a two-word `hashCode`/`equals` on every probe of the engine's hottest structure; a generator dependency until JDK 26; buys cross-session uniqueness nothing in v1 consumes, given §1 defers distribution |
| **C. `long` + lazily-materialized UUID** | `long` internally, UUID computed on export | Best of both, in principle | The mapping lives somewhere — a per-session map existing solely for a boundary concern. (A) plus the session id gets the same result with no map |

**Why (A):** measuring a cost you can avoid entirely, in exchange for a property you don't yet use, is the wrong order of operations. (A) preserves what the UUID argument actually wanted — `(sessionId, handle)` is globally unique, sorts by session creation time, and is exactly the identity a future sharded deployment would need — at 16 bytes *per session* rather than per fact.

**The second half of this decision:** `recency` is not a handle field. It is mutable (it advances on effective update), so a handle containing it is either stale after an update or unbumpable — the failure mode §11.2 rejected option (C) for. It lives on `Fact`. Relatedly, a `FactHandle implements Comparable` whose `compareTo` used only `recency` while its record `equals` used both fields would be inconsistent with `equals` and would silently corrupt any `TreeSet`/`TreeMap` keyed on handles. Both problems disappear when identity is one immutable `long`.

### 11.5 Agenda construction — **Decided: TREAT-shaped conflict set only in v1; the Rete shape lands in Phase 3**

| Option | Shape | Pros | Cons |
|---|---|---|---|
| **A. Rete-shaped only** | Activations pushed as tokens reach terminals; retract pulls them | Simple, single model; O(1) fire-cycle cost | Requires persistent beta memory, i.e. it *is* the Phase 3 engine — choosing it means abandoning §11.1 |
| **B. TREAT-shaped only in v1** (chosen) | Conflict set recomputed lazily for dirty rules; `Agenda` is three methods | Matches §11.1's v1 exactly; no beta memory; retract needs no agenda surgery; nothing exists whose agreement must be maintained | Recomputation cost per cycle; wrong for long-lived streaming, which is why Phase 3 exists |
| **C. Both from day one, behind one `Agenda`** | Shared `Activation`, comparator, refraction, RHS execution and firing loop | Each session shape gets the model that fits | Two implementations to keep in agreement, one of which has no workload yet — see below |

**This decision was previously (C), and reversing it is the single largest simplification in this document.** The argument for (C) was that "the two shapes must agree" is a testable claim and the only thing making it safe to offer both. That reasoning is sound *once both shapes exist*. What it does not justify is maintaining agreement with an implementation that has not been written, against a workload nobody has profiled, at a cost paid in every phase before the second shape arrives.

**What (C) cost, concretely.** Agreement required three mechanisms, and the design carried all three from Phase 0:

1. **Recency snapshots persisted across recomputations**, keyed by `ActivationKey` — a per-session class with two indexes, a lockstep-invalidation obligation with the refraction memory, a pruning rule on recomputation, and a fourth unbounded growth surface (§4.4). It existed for exactly one reason: TREAT recomputes activations and Rete does not.
2. **A state-derived final tie-break** rather than a creation-order counter (§4.2).
3. **Per-rule refraction invalidation** rather than type-wide (§4.4).

Under (B), mechanism 1 **disappears entirely** — §4.2's recency is `max(Fact.recency)` computed in the constructor, because a rule is dirty only when a fact it patterns changed, which is exactly when its activations' recency should change anyway. Mechanisms 2 and 3 **stay**, because both are independently justified: the tie-break is less plumbing than a counter and is required as the total-order term the determinism contract needs, and per-rule refraction scoping prevents a rule re-firing because an unrelated rule's field changed. So the load-bearing half of the agreement machinery was already free, and the expensive half is the half that is deferrable.

Removing it also removed a defect. Under (C), §3.4.1's update algorithm had to clear refraction *before* re-asserting, or Rete's terminal-side refraction check would suppress an activation that the subsequent invalidation then made eligible — with no further token to recreate it — silently dropping a firing TREAT performs. That hazard is purely a two-shape divergence. With one shape it cannot occur.

**What Phase 3 owes when it lands the second shape.** The exit criterion is unchanged and still non-negotiable: *TREAT and Rete produce identical firing sequences on the same input.* If they can diverge, the choice of session type silently changes business outcomes and every §11.1 argument about picking per workload collapses. What changes is how that claim gets established — by differential-testing a new implementation against a shipped, exercised v1 engine and the Phase 0 oracle, rather than by anticipating in a design document which mechanisms two hypothetical implementations would need to share. That is a better position to prove it from, not a worse one.

**What v1 does to keep the door open**, all of it free: `Agenda` stays an interface (§4.3) rather than a concrete class; the tie-break stays state-derived; refraction invalidation stays per-rule; `SessionOptions` is builder-backed (§7.5) so a `joinStrategy` selector is a non-breaking addition. There is no `JoinStrategy` enum in v1, because a selector with one valid value is dead configuration that readers reasonably assume has two.

---

## 12. References

What to read directly rather than take this spec's summary on faith.

### JDK 25 concurrency (§5)

| Topic | Reference |
|---|---|
| Virtual threads (final in JDK 21, unchanged in 25) | [JEP 444](https://openjdk.org/jeps/444) |
| `Executors.newVirtualThreadPerTaskExecutor()` — the stable primitive §5.2 builds on | [`java.util.concurrent.Executors` (JDK 25)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Executors.html) |
| Structured concurrency — still preview in 25 | [JEP 453 (21, 1st preview)](https://openjdk.org/jeps/453) · [462 (22)](https://openjdk.org/jeps/462) · [480 (23)](https://openjdk.org/jeps/480) · [505 (25, reworked API)](https://openjdk.org/jeps/505) · [525 (26)](https://openjdk.org/jeps/525) · [533 (27, proposed)](https://openjdk.org/jeps/533) |
| `StructuredTaskScope` current API shape | [JDK 25 API (preview)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/StructuredTaskScope.html) |
| Synchronize Virtual Threads without Pinning (landed 24) | [JEP 491](https://openjdk.org/jeps/491) |
| Scoped Values (final in 25) — note the inheritance scope rules in §5.4 | [JEP 506](https://openjdk.org/jeps/506) |

### Identity — session ids (§2.1, §11.4)

Fact handles are session-scoped `long`s and involve none of this; UUIDv7 is used once per *session*.

| Topic | Reference |
|---|---|
| RFC 9562 (defines UUIDv6/v7/v8, supersedes RFC 4122) | [RFC 9562](https://datatracker.ietf.org/doc/rfc9562/) — section 5.7 covers v7 |
| Java UUIDv7 generation (no native generator in JDK 25) | [`com.github.f4b6a3:uuid-creator`](https://github.com/f4b6a3/uuid-creator) — RFC 9562-compliant; §2.1 recommends its non-monotonic v7 mode |
| Native JDK UUIDv7 — lands in **JDK 26**, not 25 | [`UUID.ofEpochMillis(long)`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/util/UUID.html), [JDK-8357251](https://bugs.openjdk.org/browse/JDK-8357251) |
| Primitive-keyed collections, if handle-keyed maps profile hot | [`it.unimi.dsi:fastutil`](https://fastutil.di.unimi.it/) |

### Fact model — JsonNode, JSON Pointer, comparison (§2.2, §2.6)

| Topic | Reference |
|---|---|
| RFC 6901 (JSON Pointer) — the basis for `FieldAccessor`, and note it has no wildcard | [RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) |
| `JsonNode` | [jackson-databind API](https://www.javadoc.io/doc/tools.jackson.core/jackson-databind/latest/tools/jackson/databind/JsonNode.html) |
| `JsonPointer` — immutable and shareable, backing §2.6's caching | [jackson-core API](https://www.javadoc.io/doc/tools.jackson.core/jackson-core/latest/tools/jackson/core/JsonPointer.html) |
| `BigDecimal` scale-sensitive `equals` vs `compareTo` — the §2.6.2 trap, stated in the Javadoc itself | [`java.math.BigDecimal` (JDK 25)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/math/BigDecimal.html) |
| Linear-time regex for rule-authored `matches` (§2.6.3) | [`com.google.re2j:re2j`](https://github.com/google/re2j) · [Russ Cox, "Regular Expression Matching Can Be Simple And Fast"](https://swtch.com/~rsc/regexp/regexp1.html) |

### Matching algorithm (§3)

| Topic | Reference |
|---|---|
| Rete, original paper | C.L. Forgy, *Artificial Intelligence* 19(1), 1982, pp. 17–37. [DOI](https://doi.org/10.1016/0004-3702(82)90020-0) · [PDF mirror](https://www.csl.sri.com/users/mwfong/public_html/Technical/RETE%20Match%20Algorithm%20-%20Forgy%20OCR.pdf) |
| TREAT | D.P. Miranker, "TREAT: A Better Match Algorithm for AI Production Systems," 1987. Not freely hosted — see Doorenbos ch. 2 for a comparison |
| Rete internals, node sharing, indexing | R.B. Doorenbos, ["Production Matching for Large Learning Systems"](https://www.csd.cs.cmu.edu/sites/default/files/phd-thesis/CMU-CS-95-113.pdf), CMU-CS-95-113, 1995 |

### Agenda, conflict resolution & refraction (§4)

| Topic | Reference |
|---|---|
| OPS5 conflict-resolution strategies (LEX/MEA) — the origin of salience, specificity, recency and refraction; §4.2 keeps salience and recency and deliberately drops specificity | C.L. Forgy, ["OPS5 User's Manual"](https://kilthub.cmu.edu/articles/journal_contribution/OPS5_user_s_manual/6608090), CMU-CS-81-135, 1981 |

### DSL & libraries (§6)

| Topic | Reference |
|---|---|
| Jackson | [FasterXML/jackson](https://github.com/FasterXML/jackson) |
| Jackson YAML module | [jackson-dataformats-text, `yaml`](https://github.com/FasterXML/jackson-dataformats-text/tree/3.x/yaml) |
| CEL — spec | [cel.dev](https://cel.dev/) · [cel-expr/cel-spec](https://github.com/cel-expr/cel-spec) |
| CEL — Java implementation, and its cost estimator (§6.4) | [cel-expr/cel-java](https://github.com/cel-expr/cel-java), Maven `dev.cel:cel`. **Not** to be confused with `org.projectnessie.cel`, which is an independent Go→Java port with different semantics and feature coverage — they are separate projects, not old and new coordinates of one |
| JSON Schema | [json-schema.org](https://json-schema.org/) |
| JSON Schema — Java implementation | [`com.networknt:json-schema-validator`](https://github.com/networknt/json-schema-validator) |

### Observability (§7)

| Topic | Reference |
|---|---|
| JFR custom events — the substrate §7.1 recommends | [`jdk.jfr.Event` (JDK 25)](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/jdk/jfr/Event.html) · [JFR API guide](https://docs.oracle.com/en/java/javase/25/jfapi/) |

### For contrast (§0)

| Topic | Reference |
|---|---|
| Drools' Phreak matcher, for comparison against §3's choices (note its three-layered segment memory vs. the simpler node sharing here) | [Drools rule engine docs](https://docs.drools.org/8.38.0.Final/drools-docs/docs-website/drools/rule-engine/index.html) |
| Drools release/scale context | [Drools on Wikipedia](https://en.wikipedia.org/wiki/Drools) |

**Two claims worth re-verifying before you build a dependency plan on them**, since both are recent: `UUID.ofEpochMillis` landing in JDK 26 (JDK-8357251), and the current home and coordinates of the CEL Java implementation.
