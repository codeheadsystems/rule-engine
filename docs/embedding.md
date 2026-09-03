# Embedding the engine

Everything the host application does that is not writing a rule: creating sessions, configuring
them, reading results, bounding a long-lived one, and finding out what happened when something went
wrong at 3am.

For writing rules, see [`dsl-guide.md`](dsl-guide.md) and [`dsl-reference.md`](dsl-reference.md).
For a complete application that does all of this, read
[`rule-engine-example`](../rule-engine-example/README.md) and then run it.

## Contents

- [Platform requirements](#platform-requirements)
- [The two tiers](#the-two-tiers)
- [Getting facts in](#getting-facts-in)
- [Building rules in Java](#building-rules-in-java)
- [`SessionOptions`](#sessionoptions)
- [Limits, and the one the engine does not enforce](#limits-and-the-one-the-engine-does-not-enforce)
- [Host functions](#host-functions)
- [Host-owned lists and reference data](#host-owned-lists-and-reference-data)
- [Choosing a matcher](#choosing-a-matcher)
- [Concurrency](#concurrency)
- [Long-lived sessions and eviction](#long-lived-sessions-and-eviction)
- [Swapping rules while running](#swapping-rules-while-running)
- [When a rule action throws](#when-a-rule-action-throws)
- [Operating it: metrics, tracing, audit](#operating-it-metrics-tracing-audit)
- [Diagnosing production](#diagnosing-production)
- [Getting out](#getting-out)

## Platform requirements

**Java 25 at runtime, not just at build time.** The published jars are class-file major version 69
with no multi-release fallback, so they will not load on 17 or 21 — the failure is
`UnsupportedClassVersionError` at class load. Spec §5 has the reasoning: JEP 491 lands in 24 and Scoped
Values are final in 25, and the concurrency model rests on virtual threads not pinning.

**Jackson 3, in about sixty public signatures.** `-core` declares `tools.jackson.core:jackson-databind`
as `api`, because the fact model is JSON-native rather than an object that happens to serialise. If
your service is on Jackson 2 (`com.fasterxml.jackson`) the two coexist — different group, different
package, no classpath conflict — but you are carrying a second Jackson and converting at the
boundary. A Jackson major upgrade is a major version of this engine, and there is no gradual path.

## The two tiers

A `CompiledRuleSet` is immutable, thread-safe and shared by everything. A `RuleSession` holds all
the mutable state, is single-writer, and is never shared across threads.

```java
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));  // once, at startup

try (RuleSession session = rules.newSession(options)) {
    session.insert("Order", payload);
    FireResult result = session.fireAllRules();
}
```

Compile once. Create a session per unit of work — a request, a message, a batch. Sessions are cheap
to allocate; the rule set is not. `halt()` is the **only** method legal to call on a session from
another thread.

## Getting facts in

A fact is a type name and a JSON object, and the primary way in is the session:

```java
session.insert("Order", payload);        // payload copied on the way in (§2.2)
session.insertOwned("Order", payload);   // you promise not to touch it again; no copy
```

**An application whose facts arrive as events should write that path itself.** Deciding fact
identity, flattening collections (JSON Pointer has no wildcard, so a nested array can be stored and
never matched inside) and normalising absent fields are modelling decisions the rules are then
written against — `rule-engine-example`'s `Ingest` is forty lines and every one of them is a choice
no generic reader can make for you.

For the facts that are *not* a stream — a fixture, a seed, a captured session — there is a reader,
in either serialization:

```yaml
# facts.yaml
- type: Customer
  payload: { id: "c1", riskTier: "HIGH" }
- type: Order
  payload:
    id: "o1"
    customerId: "c1"
    total: 12000
```

```java
try (RuleSession session = rules.newSession()) {
    List<FactHandle> loaded = FactFiles.insertInto(session, FactSource.of(Path.of("facts.yaml")));
    FireResult result = session.fireAllRules();
}
```

`FactFiles.read` stops at `List<ExportedFact>` if you want the facts without a session — the same
type `exportFacts()` hands back and `SessionDrain.replay` consumes, so a document and a drained
session are interchangeable inputs. `FactFiles.payload` reads a document holding one bare payload,
for a caller that already knows the type. JSON and YAML are one language here exactly as they are
for rule files: the same document written both ways produces the same facts, and the test asserting
that is `FactFilesTest.Equivalence`.

Five things it does, each of which is a decision you may be relying on:

- **Document order is insertion order.** §7.3 states the determinism contract over the same facts in
  the same insertion order, so two documents holding the same facts in different orders are two
  different inputs. Reordering a fact document is editing it.
- **A document either loads or leaves nothing behind.** Every problem in the document is reported at
  once, located to a line and column where the parser gives one — and an insert that throws part-way
  (a §2.3 schema violation on the fourth fact of ten) is unwound by retracting what already landed.
  Half a fixture is a different input, not a failed one. Three things the unwind does not claim: a
  fact *eviction* removed while the load ran does not come back; listeners saw the inserts and will
  see the retracts; and an insert that throws *after* the fact reached working memory — from a
  listener, or from an eviction policy — leaves that one fact, because its handle was never handed
  back.
- **Every fact is `ASSERTED`.** A document states what is true; what a rule set concludes from it is
  the session's to derive. That is why `exportFacts()` filters `DERIVED` facts out — replaying one
  would double-count it against the rule that concluded it.
- **A repeated key is an error**, not last-wins. `{ total: 1, total: 2 }` reads correctly in review
  and makes the rule under test look wrong; both serializations accept it by default and this
  reader does not, which is the same call the rule-file parser makes.
- **One document per file.** A second YAML document after a `---` is refused by name rather than as
  Jackson's "trailing token".

Three YAML details worth knowing before you write fixtures in it, all pinned by
`FactFilesTest.YamlScalars` so that a Jackson upgrade cannot change them quietly:

- `v:` with nothing after it is an **explicit null**, not an absent field. §2.6.1 makes those
  different: `eq: null` matches the first and never the second, and `hasField: false` is how you say
  absent. Leaving the field out is what absent means.
- Scalar resolution is Jackson's, not YAML 1.1's, which is the friendlier of the two: `no`, `yes`,
  `on` and `NO` stay **strings**, so a country code does not become a boolean. Quote them anyway if
  the fixture is shared with another YAML tool.
- An anchor and its alias do not survive: `b: *x` arrives as the **string** `"x"`, not as the value
  the anchor held. Jackson's YAML parser has flattened it before the token stream is readable, so
  this cannot be rejected the way a repeated key is — it is the one thing in a fact document that is
  silently a wrong *value* rather than a differently-typed one. Write fact documents without
  anchors.

## Building rules in Java

A rule file is not the only front end. `Rules` in `-testkit` builds the same constraint AST the DSL
produces, which is useful when rules are generated rather than authored — from a database table, a
UI, or a test fixture:

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
```

This is the same rule README prints as YAML, and the two are held to producing the **identical** rule
set — down to the version hash — by `DslEquivalence`. That equivalence is the DSL's oracle test, and
it is the strong one: it caught both defects the DSL module surfaced, because a hash comparison
notices a normalisation difference that a firing-sequence comparison would step straight over.

JSON and YAML are one language here: both parse into the same object model and compile to the same
rule set, and the entire difference is which Jackson factory reads the text.

## `SessionOptions`

`SessionOptions.builder()`, and everything it takes:

| Setting | Default | What it does |
|---|---|---|
| `limits(FireOptions)` | 10,000 cycles / 1,000,000 facts | Bounds the work one fire call may do — see below |
| `matching(MatchingStrategy)` | `NETWORK` | Which matcher — see below |
| `function(String, HostFunction)` | none | Registers a `callFunction` handler — see below |
| `events(EventSink)` | discarding | Where `emit` goes. The default performs no I/O; `FireResult.emitted()` is sourced from the firing records regardless |
| `listener(RuleEngineListener)` | none | §7.1's trace hooks — insert, update, retract, activation, fire, emit, error |
| `onRhsError(RhsErrorHandler)` | `RETHROW` | What happens when an action throws — see below |
| `conflictResolution(...)` | salience, then recency | §4.2's ordering. A total order, asserted as one under strict mode |
| `eviction(EvictionPolicy)` | none | §4.4's fact eviction — read the hazard below before setting it |
| `dryRun(boolean)` | false | Match and resolve conflicts, execute nothing |
| `strict(boolean)` | `-Drules.strict` | Contract checks too expensive for production (§7.5) |
| `runnersUpLimit(int)` | 3 | How many losing activations a `FireRecord` records — and it collects none at all unless `dryRun` is on or a listener is registered |

**One options object may serve many sessions**, and `RuleBatches` fans one across all of them — so
anything mutable it holds becomes shared state. Listeners and host functions are the two that
matter; both document the obligation, and `TracingListener` meets it.

## Limits, and the one the engine does not enforce

`FireOptions` bounds a fire call:

| | Default | On breach |
|---|---|---|
| `maxCycles` | 10,000 | `RuleEngineLimitExceeded.CycleLimit`, carrying the partial `FireResult` |
| `maxFacts` | 1,000,000 | `RuleEngineLimitExceeded.FactLimit`, likewise |

A breach never discards completed work — the exception carries `partialResult()`, because a batch
that fired 9,999 rules must not lose all of it. `FireResult.why()` is a `TerminationReason`:
`DRAINED` (nothing left eligible — the normal case), `HALTED`, `LIMIT_EXCEEDED`, `RHS_ERROR`.

> ### There is no wall-clock bound. Bring your own watchdog.
>
> `maxCycles` and `maxFacts` bound **work, not time**, and neither is a proxy for latency. §6.4's own
> example — an unindexed CEL condition against 100,000 facts — is 100,000 evaluations inside a
> *single* cycle, tripping neither limit.
>
> **If you have a per-decision latency budget, the engine will not enforce it for you.** Run a
> watchdog on another thread and call `session.halt()`, which is the one cross-thread call §5.1
> permits and is terminal: a halted session finishes its current cycle and stops. Spec §4.7 records this as an
> open decision rather than an oversight; it is repeated here because it is the thing an operator
> most needs and was hardest to find.

## Host functions

`callFunction` in a rule dispatches by name to a handler you register. **The rule file half is only
half** — the reference documents the verb and `declaredFunctions`, and this is the other side:

```java
SessionOptions.builder()
    .function("notifySlack", args -> {
      // Guarded, not args.get("channel").stringValue(). Jackson 3's typed accessors are strict:
      // get() returns null for an absent key and stringValue() throws on a type mismatch, so an
      // unguarded read here is a runtime throw inside the commit phase.
      JsonNode channel = args.path("channel");
      slack.post(channel.isString() ? channel.stringValue() : "#default", args.toString());
    })
    .build();

// and, so a typo is a compile error rather than a fire-time failure on one path:
CompilerOptions.builder().declaredFunctions(Set.of("notifySlack")).build();
```

A `HostFunction` receives the resolved arguments already deep-copied, so it may keep or mutate them.
Three obligations the engine states and cannot enforce: be **deterministic** (reading a clock in one
is the classic way to lose §7.3 — insert time as a fact instead), be **non-blocking and bounded**
(there is no fire-loop timeout to rescue you), and be **safe for concurrent use** if one options
object serves many sessions.

**`callFunction` is the wrong default.** It runs at commit, outside the staging that makes §4.6
atomic, and cannot be withdrawn. Prefer `emit` and act on `FireResult.emitted()` after the call
returns.

## Host-owned lists and reference data

The question arrives as "can a rule check whether this value is in a list my application owns", and
the list is usually mutable, often written by the rules' own decisions, and in a cluster it lives in
a store every node reads. The engine has no lookup operator, no SPI that consults a host structure
during matching, and no CEL binding that reaches outside the tuple. That is deliberate, and the
reason is the one contract everything else here rests on: **during one session, a match's answer
changes only because a fact moved.** Refraction, the streaming matcher's conflict set, truth
maintenance and replay all assume it. A structure that changed underneath a running session would
break all four at once, whatever thread-safety it had.

So the list enters as a fact, and there are two shapes for that.

| Shape | Use it when | How |
|---|---|---|
| **Read-through per session** | one session per event; the list is large, shared, and lives in a store | before the session, look up each entity the event names and insert one membership fact per (list, entity), `member: true` or `false`. Writes leave as emitted events and the host applies them after the fire call |
| **Entries as facts in a long-lived session** | the list *is* the stream: entries arrive and expire continuously, and one session runs for days | insert each entry as its own fact, ask with `notExists`, let a rule's `insertFact` add one. The host retracts an entry when it expires, and that retract is the bound on the session's growth: eviction is not available here, because a `notExists` over an evicted type manufactures matches |

The guide has the rule-file half of the first shape, including how a rule that adds to the list
makes the addition visible to the rest of the same session:
[Checking a list your application owns](dsl-guide.md#checking-a-list-your-application-owns). The
host half is a lookup before `newSession()` and a write after `fireAllRules()`:

```java
List<ExportedFact> memberships = lookups.membershipsFor(event);   // your store, your client
try (RuleSession session = rules.newSession(options)) {
    session.insert("Payment", payment);
    memberships.forEach(m -> session.insert(m.type(), m.payload()));
    FireResult result;
    try {
        result = session.fireAllRules();
    } catch (RuleEngineLimitExceeded breach) {
        result = breach.partialResult();       // completed work is never discarded; decide what it means
    }
    for (EmittedEvent e : result.emitted()) {   // AFTER the decision, never during it
        if (e.eventType().equals("list.entry.add")) {
            // The one-argument form: path() gives a MissingNode for an absent key, and Jackson 3's
            // stringValue() THROWS on it where the null-returning read is what a payload check wants.
            String list = e.payload().path("list").stringValue(null);
            String entityId = e.payload().path("entityId").stringValue(null);
            if (list != null && entityId != null) {
                outbox.record(list, entityId);      // durable before the decision is acted on
            }
        }
    }
}
```

That last line is the dual-write problem in one word. The session has decided and the store has not
yet heard; a crash between the two loses the addition while the decision stands. Write the emitted
event to something durable before acting on the decision, or accept the loss and say so where the
next reader will find it.

A membership fact, as a fact document, so a fixture can say what the store would have said:

```yaml
# memberships.yaml: one fact per (list, entity) the event names; member is true OR false
- type: ListMembership
  payload: { list: "card-blocklist", entityId: "4111000000001111", member: false, asOfEpochMs: 1756800000000 }
```

Four things to hold onto:

- **A failed lookup is an absent fact, never `member: false`.** With `member` true *or* false on
  every fact the store answered for, a missing fact means one thing only: the store did not answer.
  Insert `false` on an outage and every `member: { eq: false }` fires against a card nobody checked;
  insert nothing and neither `eq: true` nor `eq: false` has a fact to bind, while a `notExists` over
  the membership sees the gap and can fail closed. It is the absence-versus-value distinction §2.6.1
  draws for a field, applied one level up, to the fact. The entries-as-facts shape has the mirror
  hazard: a load that fails part-way leaves an *empty list*, indistinguishable from a list with no
  entries, so every `notExists` over it fires. There, a failed load must fail the session rather
  than leave it half-filled.
- **Cluster propagation is the store's job, and the engine keeps no copy.** Every node reads through
  at session start, so a write from any node is visible to the next session anywhere as soon as the
  store has it. A per-node cache would reintroduce the problem this design removes.
- **Determinism holds per session, and ordering across sessions is yours.** Two sessions for the same
  key running concurrently each read before either writes. Make list writes idempotent, or route
  events for one key to one lane so they run in sequence. The engine cannot order sessions it did not
  start.
- **Which lists a rule set reads is derivable from the compiled rules**, without parsing anything:
  walk `CompiledRule.source().when()` for patterns on the membership type and read the `list`
  literal. **Read it and never mutate it**: the constraint records deep-copy a literal on the way in
  but hand back the live node, so an edit there changes what every session matches (§5.5's
  invariant 1, `ImmutabilityTest`). An explicit declaration beside the rule file is the better audit
  record; the walk is what checks that the declaration is complete.

`callFunction` is not the door for either half. It is `void`, so it cannot bring a value back; it
runs at commit, inside the fire loop; and its own contract asks handlers to be deterministic and
non-blocking, which a store round trip is not.

## Choosing a matcher

Three matchers, held to producing **identical firing sequences**. Everything deciding *which*
activation fires lives in one shared base, so they can only differ in how matches are found.

| `matching(...)` | Use it when |
|---|---|
| `NETWORK` (default) | A session is created, filled, fired, closed. Joins recomputed per cycle from indexed pattern memories |
| `RETE` | A session is long-lived and fires thousands of times. Joins materialised as facts arrive; the conflict set is pushed and pulled rather than rebuilt |
| `NAIVE` | Never in production. No network, no indexes, `O(rules × facts^arity)` — the **correctness oracle**, shipped so you can test your own rules against it |

`MatcherEquivalence` and `ShuffleHarness` in `rule-engine-testkit` point that oracle at your rule set;
see [`MatcherAgreementTest`](../rule-engine-example/src/test/java/com/codeheadsystems/rules/example/MatcherAgreementTest.java).
Curves are in [`benchmarks.md`](benchmarks.md).

## Concurrency

One virtual thread per session (§5.2). No locks, no pool to size.

```java
List<BatchOutcome<FireResult>> outcomes = RuleBatches.run(rules, batches, (session, batch) -> {
    batch.forEach(f -> session.insert(f.type(), f.payload()));
    return session.fireAllRules();
});
```

Every input comes back as a `BatchOutcome` holding **either** a value or a throwable — a batch that
fails does not stop the others, because §5.2 refuses to decide for you what a partial batch means.

The scaling figures, the shared-nothing control they are measured against, and what they do not show
are in [`benchmarks.md`](benchmarks.md) — not restated here, because a number copied into a third
document is a number that will disagree with the run that produced it.

**For a stream rather than a batch, `SessionActor`.** "Fire until told to stop" is a blocking loop,
so inserting from a producer thread while it runs is a data race. One worker owns the session,
producers feed a bounded inbox, and a burst of inserts costs one fire cycle rather than one each.

## Long-lived sessions and eviction

Everything a long-lived session grows — working memory, node memories and their indexes, the
refraction memory, the beta memory — is keyed on handles, so letting go of facts bounds all of them
at once. `eviction(EvictionPolicy)` takes a total cap, a per-type cap or a **time window**, and
evicting runs the full retract path.

```java
EvictionPolicy.leastRecentlyUsed(10_000);                        // a cap on the session
EvictionPolicy.perType(Map.of("Order", 10_000));                 // a cap per type
EvictionPolicy.window("LoginFailure", "at", 600_000);            // ten minutes of one type
```

The window is the one a streaming rule set usually wants: `perType` bounds the *arrival count*, and
the two differ by exactly the traffic spike the rules exist to notice. Its far edge is the newest
value of `at` that type currently holds, minus the span — **a watermark taken from the data, never a
clock** — so two runs over the same stream evict the same facts and §7.3 survives. Which also means
time advances only when a fact carrying a later time arrives: a session that goes quiet holds what it
held, because "nothing arrived" remains the one input this engine never receives. The span is in the
field's own units, exactly as a rule's `within` is.

Four things to get right when you window a type:

1. **Retention is per type and per session; a window in a rule is per rule.** Two rules wanting ten
   minutes and twenty-four hours of one type are served by retaining twenty-four hours and letting
   the ten-minute rule narrow its own match with `within`. Retain less than the widest rule window
   and the rule silently loses matches its author wrote.
2. **A negation or a universal over a windowed type changes meaning** — see the warning below. An
   `accumulate` over one is the case where that interaction is the point ("how many in the window"),
   and is still governed by (1).
3. **A fact with no usable time is never evicted**, because a policy that cannot prove a fact is old
   must not guess. A type whose facts do not all carry the field needs a `perType` cap as well.
4. **A fact that arrives already outside the window is evicted on arrival**, inside the `insert` call
   that added it — so `insert` can hand back a handle whose fact is already gone. That is correct (a
   fact older than the window is one no windowed rule can match) and it is the ordinary out-of-order
   case rather than an exotic one, so a stream with late arrivals drops them silently. A listener's
   `onEvicted` is the only thing that says so.

The rule-author half of this — velocity counts, and the `Clock` fact for "as of now" — is in
[`dsl-guide.md`](dsl-guide.md#counting-things-in-a-window).

> ### Never cap a type your rules negate, quantify over, fold, or conclude.
>
> **An evicted fact is indistinguishable from one that was never there**, and that collides with each
> Phase 6 feature differently: evicting a **negated** type manufactures a false conclusion; a
> **quantified** type has its requirement deleted rather than weakened; an **accumulated** type
> quietly changes a number; a **concluded** type loses a belief nothing can redraw.
>
> `MatchExplainer` warns for all four and can **detect** none of them — it re-asks the same question
> of the same working memory and is fooled identically.
>
> A **windowed** type is the one case where two of those are deliberate rather than a hazard: an
> `accumulate` over a window is the count you asked for, and a `notExists` over one reads as "not
> lately" rather than "never" — which is useful, and is a false conclusion for any rule on that type
> that meant "never". The rule set has to agree with the retention, because nothing can tell you it
> does not.
>
> For most rule sets the answer is not a policy but explicit retraction from the application, which
> knows the thing the engine cannot: that this unit of work is finished. The example works the
> analysis through a real rule set and finds exactly one of six types safe to cap; see
> [`StreamingDemo`](../rule-engine-example/src/main/java/com/codeheadsystems/rules/example/StreamingDemo.java).

## Swapping rules while running

`RuleSetHolder` is §5.6's hot reload — one volatile field, no locks, two contracts. Publish a
*compiled* rule set, so a broken rule file leaves the previous version serving; and a swap affects
new sessions only.

```java
RuleSetHolder rules = new RuleSetHolder(RuleFiles.compile(source));
rules.publish(RuleFiles.compile(newSource));   // compile first: a failure here changes nothing
```

There is no safe in-place swap for a session already running — its memories, refraction state and
agenda are shaped by the old network's node ids. `SessionDrain.restart` exports the facts, **creates
and loads the new session, and closes the old one only once that has succeeded** — so a failed replay
leaves you holding a working session rather than none. Derived facts are deliberately not replayed:
the new session re-derives them, and replaying would double-count.

## When a rule action throws

Atomicity is **per-phase** (§4.6). A staging failure applies nothing. A commit failure — a
`callFunction`, an `EventSink`, a `setField` whose path runs through a scalar — leaves what already
landed. There is no compensating undo and there cannot be one: a sent message cannot be un-sent.

`FireRecord` carries what committed, which action threw, and which never ran.

**Under the default `RETHROW`, register a listener or you will not get that record.** The original
exception propagates, so unlike a limit breach it cannot carry a partial result; `onAfterFire` is
published for the failed firing *before* the rethrow. With no listener, the record of that firing and
every firing before it in the call is gone with the stack unwind. `onRhsError` takes an
`RhsErrorHandler`, whose `Decision` may also be `ABORT_SESSION` (return the partial `FireResult`
rather than throw) or `SKIP_ACTIVATION`.

A listener must not throw and must not call back into the session; neither is enforced.

## Operating it: metrics, tracing, audit

**`session.stats()`** returns a `SessionStats` — this is the dashboard:

| Field | What a rising value means |
|---|---|
| `factCount` | working memory; what `maxFacts` is checked against |
| `refractedMatchCount` | matches remembered as fired; bounded only by retract and eviction |
| `materialisedMatchCount` | complete matches held by the streaming matcher; zero under the recomputing ones |
| `materialisedHandleCount` | handles its reverse index still tracks — a leak hides here, not above |
| `pendingMatchCount` | matches waiting to fire; what a streaming fire cycle costs |
| `concludedFactCount` | live logical conclusions. Flat facts + climbing conclusions = rules concluding faster than their reasons expire |
| `evictedCount` / `evictedByType` | split, because "a rule stopped matching because its facts were let go" looks exactly like "it never matched" |

**`rule-engine-observability`** ships `TracingListener` (a bounded ring buffer of recent
`FireRecord`s) and `JfrListener` (Flight Recorder events per firing). One `TracingListener` may be
shared across a batch run and locks correctly for it — at the cost of interleaving every session into
one buffer, which is what you want for an aggregate trace and not for diagnosing a single loop.

**Audit correlation is already there and is easy to miss.** Every emitted event carries an
`EmitContext` of `(sessionId, ruleId, handles, ruleSetVersion)`. That last field is the content hash
of the rules that produced the decision — so "which rules made this call, six months ago" is
answerable from the event alone.

## Diagnosing production

`MatchExplainer` answers "why did rule R not fire", and its constructor needs a **live session
holding the facts** — which at 3am you no longer have. Build the capture path before you need it:

1. **Capture.** A `RuleEngineListener` recording inserts, or `session.exportFacts()` before close —
   it returns `List<ExportedFact>` ordered by handle id, which is the order §7.3 states its guarantee
   in. Key it by `EmitContext.ruleSetVersion`.
2. **Replay.** Compile that exact rule-set version, `SessionDrain.replay` the facts into a session.
3. **Explain.** `new MatchExplainer(rules, session).explain("rule-id")`, or the pinned form taking
   `Map<String, FactHandle>` when you know which facts you are asking about.

Because firing is a pure function of the facts and their order, a replay reproduces the original
decision exactly — that is what the determinism contract is *for*, and it is the whole reason this
works.

## Getting out

Worth knowing before you adopt rather than after.

**What is portable.** Your rules are YAML or JSON against a published `rules.v1` schema — text you
own, not an opaque binary. Your facts are your own JSON, and `exportFacts()` hands them back. Your
outputs are emitted events you already consume.

**What is not.** The flattened fact model — one fact per collection element, joined by id — is a
modelling decision that will have shaped your ingestion path, and it does not unwind for free. So
will the absent-versus-null distinction, which most alternatives collapse.

**If the project stalls.** Apache 2.0, sources and javadoc jars published, the design recorded in a
2,000-line specification that names its rejected alternatives, and a test suite that includes a naive
correctness oracle you can check any change against. Forking is a real option rather than a
formality.
