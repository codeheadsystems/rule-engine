# Embedding the rule engine

Everything the host application does that is not writing a rule: creating sessions, configuring
them, reading results, bounding a long-lived one, and finding out what happened when something went
wrong at 3am.

Writing rules is covered in [`dsl-guide.md`](dsl-guide.md) and
[`dsl-reference.md`](dsl-reference.md). [`rule-engine-example`](../rule-engine-example/README.md) is
a complete, runnable application that does all of this.

## Contents

- [Platform requirements](#platform-requirements)
- [The two tiers](#the-two-tiers)
- [Getting facts in](#getting-facts-in)
- [Building rules in Java](#building-rules-in-java)
- [`SessionOptions`](#sessionoptions)
- [Work limits and wall-clock time](#work-limits-and-wall-clock-time)
- [Host functions](#host-functions)
- [Host-owned lists and reference data](#host-owned-lists-and-reference-data)
- [Choosing a matcher](#choosing-a-matcher)
- [Concurrency](#concurrency)
- [Long-lived sessions and eviction](#long-lived-sessions-and-eviction)
- [Swapping rules while running](#swapping-rules-while-running)
- [Action failures](#action-failures)
- [Observability and audit](#observability-and-audit)
- [Diagnosing production](#diagnosing-production)
- [Getting out](#getting-out)

## Platform requirements

The rule engine requires Java 25 at runtime, not just at build time. The published jars are
class-file major version 69 with no multi-release fallback, so they do not load on 17 or 21; the
failure is `UnsupportedClassVersionError` at class load. Spec §5 has the reasoning: JEP 491 lands in
24 and Scoped Values are final in 25, and the concurrency model rests on virtual threads not
pinning.

Jackson 3 appears in about sixty public signatures. `rule-engine-core` declares
`tools.jackson.core:jackson-databind` as `api`, because the fact model is JSON-native rather than an
object that happens to serialise. A service on Jackson 2 (`com.fasterxml.jackson`) can run both:
they coexist (different group, different package, no classpath conflict), but the service carries a
second Jackson and converts at the boundary. A Jackson major upgrade is a major version of the
engine, and there is no gradual path.

The Jackson version comes from `rule-engine-bom`, not from a copied number. A `JsonNode` the host
builds goes into `RuleSession.insert()` as it is, so the host's Jackson and the engine's are one
library on one classpath. The BOM imports `tools.jackson:jackson-bom` at the version the engine was
built and tested against, and manages every engine module at its own version:

```gradle
implementation(platform("com.codeheadsystems:rule-engine-bom:1.1.0"))
implementation("com.codeheadsystems:rule-engine-dsl")
implementation("tools.jackson.core:jackson-databind")   // no version: the BOM supplies it
```

The BOM belongs in every module that uses Jackson 3, including one that depends on no engine module
and so never sees `rule-engine-core`'s transitive `jackson-databind`. Three resolution details
decide the version that actually resolves:

- Gradle's `platform()` recommends; it does not force. If something else in the graph asks for a
  newer Jackson 3, Gradle takes the newer one for everything, the engine included. Switching to
  `enforcedPlatform()` does not change that for Jackson: it forces the constraints the BOM declares
  itself, which are the engine modules, and not the ones in the `jackson-bom` it imports. Holding
  Jackson down means enforcing `tools.jackson:jackson-bom` at the engine's version directly, which
  puts back the copied number the BOM exists to remove. Most services should let the newer Jackson 3
  win and check the resolved version with `dependencyInsight`.
- Maven takes the first import that manages an artifact. `rule-engine-bom` has to be declared before
  any other BOM that manages `tools.jackson` artifacts, or that one sets the version.
- In Maven, a managed version that is not an import beats every import, whatever the order. That
  means an entry in the project's own `<dependencyManagement>`, one inherited from a parent POM, or
  an explicit `<version>` on the dependency. An application on a framework's parent POM that manages
  Jackson 3 (Spring Boot's does) gets the framework's version no matter where this BOM is declared.

The BOM also manages one `com.fasterxml` artifact. Jackson 3 has no annotations module of its own
and uses Jackson 2's `jackson-annotations`, so `jackson-bom` pins that too. In a service that also
runs Jackson 2, it can set that stack's annotations version under the same rules as above: in Maven
unless something beats the import, in Gradle unless something asks for a newer one. The annotations
are backward compatible, and the engine already pulls that version in through `jackson-databind`.

## The two tiers

A `CompiledRuleSet` is immutable, thread-safe, and shared by everything. A `RuleSession` holds all
the mutable state, is single-writer, and is never shared across threads.

```java
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));  // once, at startup

try (RuleSession session = rules.newSession(options)) {
    session.insert("Order", payload);
    FireResult result = session.fireAllRules();
}
```

A rule set is compiled once, and a session is created per unit of work (a request, a message, a
batch). Sessions are cheap to allocate; the rule set is not. `halt()` is the only method legal to
call on a session from another thread.

## Getting facts in

A fact is a type name and a JSON object, and the primary way in is the session:

```java
session.insert("Order", payload);        // payload copied on the way in (§2.2)
session.insertOwned("Order", payload);   // you promise not to touch it again; no copy
```

An application whose facts arrive as events writes that ingestion path itself. Deciding fact
identity, flattening collections (JSON Pointer has no wildcard, so a nested array can be stored and
never matched inside), and normalising absent fields are modelling decisions the rules are then
written against. `rule-engine-example`'s `Ingest` is forty lines, and every one of them is a choice
no generic reader can make on the host's behalf.

For facts that are not a stream (a fixture, a seed, a captured session) there is a reader, in either
serialisation:

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

`FactFiles.read` stops at `List<ExportedFact>` for a caller that wants the facts without a session.
That is the same type `exportFacts()` hands back and `SessionDrain.replay` consumes, so a document
and a drained session are interchangeable inputs. `FactFiles.payload` reads a document holding one
bare payload, for a caller that already knows the type. JSON and YAML are one language here exactly
as they are for rule files: the same document written both ways produces the same facts, and
`FactFilesTest.Equivalence` asserts it.

The reader makes five decisions a caller may be relying on:

- Document order is insertion order. §7.3 states the determinism contract over the same facts in
  the same insertion order, so two documents holding the same facts in different orders are two
  different inputs. Reordering a fact document is editing it.
- A document either loads or leaves nothing behind. Every problem in the document is reported at
  once, located to a line and column where the parser gives one, and an insert that throws part-way
  (a §2.3 schema violation on the fourth fact of ten) is unwound by retracting what already landed.
  Half a fixture is a different input, not a failed one. Three things the unwind does not claim: a
  fact eviction removed while the load ran does not come back; listeners saw the inserts and also
  see the retracts; and an insert that throws after the fact reached working memory (from a
  listener, or from an eviction policy) leaves that one fact, because its handle was never handed
  back.
- Every fact is `ASSERTED`. A document states what is true; what a rule set concludes from it is the
  session's to derive. That is why `exportFacts()` filters `DERIVED` facts out: replaying one would
  double-count it against the rule that concluded it.
- A repeated key is an error, not last-wins. `{ total: 1, total: 2 }` reads correctly in review and
  makes the rule under test look wrong; both serialisations accept it by default and this reader
  does not, which is the same call the rule-file parser makes.
- One document per file. A second YAML document after a `---` is refused by name rather than as
  Jackson's "trailing token".

Three YAML details apply to fixtures written in it, all pinned by `FactFilesTest.YamlScalars` so
that a Jackson upgrade cannot change them quietly:

- `v:` with nothing after it is an explicit null, not an absent field. §2.6.1 makes those different:
  `eq: null` matches the first and never the second, and `hasField: false` is the test for absence.
  Leaving the field out is what absent means.
- Scalar resolution is Jackson's, not YAML 1.1's, which is the friendlier of the two: `no`, `yes`,
  `on`, and `NO` stay strings, so a country code does not become a boolean. Quoting them is still
  safer where the fixture is shared with another YAML tool.
- An anchor and its alias do not survive: `b: *x` arrives as the string `"x"`, not as the value the
  anchor held. Jackson's YAML parser has flattened it before the token stream is readable, so this
  cannot be rejected the way a repeated key is. It is the one thing in a fact document that is
  silently a wrong value rather than a differently-typed one, so fact documents should not use
  anchors.

## Building rules in Java

A rule file is not the only front end. `Rules` in `rule-engine-testkit` builds the same constraint
AST the DSL produces, which is useful when rules are generated rather than authored (from a database
table, a UI, or a test fixture):

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

This is the same rule README prints as YAML, and `DslEquivalence` holds the two to producing an
identical rule set, down to the version hash. That equivalence is the DSL's oracle test, and it is
the strong one: it caught both defects the DSL module surfaced, because a hash comparison notices a
normalisation difference that a firing-sequence comparison would step straight over.

JSON and YAML are one language here: both parse into the same object model and compile to the same
rule set, and the entire difference is which Jackson factory reads the text.

## `SessionOptions`

`SessionOptions.builder()`, and everything it takes:

| Setting | Default | What it does |
|---|---|---|
| `limits(FireOptions)` | 10,000 cycles / 1,000,000 facts | Bounds the work one fire call may do; see below |
| `matching(MatchingStrategy)` | `NETWORK` | Which matcher; see below |
| `function(String, HostFunction)` | none | Registers a `callFunction` handler; see below |
| `events(EventSink)` | discarding | Where `emit` goes. The default performs no I/O; `FireResult.emitted()` is sourced from the firing records regardless |
| `listener(RuleEngineListener)` | none | §7.1's trace hooks: insert, update, retract, activation, fire, emit, and error |
| `onRhsError(RhsErrorHandler)` | `RETHROW` | What happens when an action throws; see below |
| `conflictResolution(...)` | salience, then recency | §4.2's ordering. A total order, asserted as one under strict mode |
| `eviction(EvictionPolicy)` | none | §4.4's fact eviction; the hazard below applies |
| `dryRun(boolean)` | `false` | Match and resolve conflicts, execute nothing |
| `strict(boolean)` | `-Drules.strict` | Contract checks too expensive for production (§7.5) |
| `runnersUpLimit(int)` | 3 | How many losing activations a `FireRecord` records; it collects none at all unless `dryRun` is on or a listener is registered |

One options object may serve many sessions, and `RuleBatches` fans one across all of them, so
anything mutable it holds becomes shared state. Listeners and host functions are the two that
matter; both document the obligation, and `TracingListener` meets it.

## Work limits and wall-clock time

`FireOptions` bounds a fire call:

| Limit | Default | On breach |
|---|---|---|
| `maxCycles` | 10,000 | `RuleEngineLimitExceeded.CycleLimit`, carrying the partial `FireResult` |
| `maxFacts` | 1,000,000 | `RuleEngineLimitExceeded.FactLimit`, likewise |

A breach never discards completed work. The exception carries `partialResult()`, because a batch
that fired 9,999 rules must not lose all of it. `FireResult.why()` is a `TerminationReason`:
`DRAINED` (nothing left eligible, the normal case), `HALTED`, `LIMIT_EXCEEDED`, or `RHS_ERROR`.

> ### No wall-clock bound
>
> `maxCycles` and `maxFacts` bound work, not time, and neither is a proxy for latency. §6.4's own
> example (an unindexed CEL condition against 100,000 facts) is 100,000 evaluations inside a single
> cycle, tripping neither limit.
>
> The engine does not enforce a per-decision latency budget. A host with one runs a watchdog on
> another thread that calls `session.halt()`, which is the one cross-thread call §5.1 permits and is
> terminal: a halted session finishes its current cycle and stops. Spec §4.7 records the missing
> bound as an open decision.

## Host functions

`callFunction` in a rule dispatches by name to a handler the host registers. The rule file is only
half of it: the reference documents the verb and `declaredFunctions`, and the host side is this:

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
The engine states three obligations it cannot enforce. A host function must be deterministic
(reading a clock in one is the classic way to lose §7.3; time belongs in a fact instead),
non-blocking and bounded (there is no fire-loop timeout to rescue it), and safe for concurrent use
if one options object serves many sessions.

`callFunction` is the wrong default. It runs at commit, outside the staging that makes §4.6 atomic,
and cannot be withdrawn. `emit` is preferable, with the host acting on `FireResult.emitted()` after
the call returns.

## Host-owned lists and reference data

A common question is whether a rule can check that a value is in a list the application owns. The
list is usually mutable, often written by the rules' own decisions, and in a cluster it lives in a
store every node reads. The engine has no lookup operator, no SPI that consults a host structure
during matching, and no CEL binding that reaches outside the tuple. The reason is the one contract
everything else here rests on: during one session, a match's answer changes only because a fact
moved. Refraction, the streaming matcher's conflict set, truth maintenance, and replay all assume
it. A structure that changed underneath a running session would
break all four at once, whatever thread-safety it had.

So the list enters as a fact, and there are two shapes for that.

| Shape | Fits when | How |
|---|---|---|
| Read-through per session | one session per event; the list is large, shared, and lives in a store | before the session, the host looks up each entity the event names and inserts one membership fact per (list, entity), `member: true` or `false`. Writes leave as emitted events and the host applies them after the fire call |
| Entries as facts in a long-lived session | the list is the stream: entries arrive and expire continuously, and one session runs for days | each entry is inserted as its own fact, `notExists` asks the question, and a rule's `insertFact` adds one. The host retracts an entry when it expires, and that retract is the bound on the session's growth: eviction is not available here, because a `notExists` over an evicted type manufactures matches |

[The guide](dsl-guide.md#checking-a-list-your-application-owns) has the rule-file half of the first
shape, including how a rule that adds to the list makes the addition visible to the rest of the same
session. The host half is a lookup before `newSession()` and a write after `fireAllRules()`:

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
yet heard; a crash between the two loses the addition while the decision stands. The host either
writes the emitted event to something durable before acting on the decision, or accepts the loss and
documents it where the next reader will find it.

A membership fact, as a fact document, so a fixture can say what the store would have said:

```yaml
# memberships.yaml: one fact per (list, entity) the event names; member is true OR false
- type: ListMembership
  payload: { list: "card-blocklist", entityId: "4111000000001111", member: false, asOfEpochMs: 1756800000000 }
```

Four properties follow:

- A failed lookup is an absent fact, never `member: false`. With `member` true or false on every
  fact the store answered for, a missing fact means one thing only: the store did not answer.
  Insert `false` on an outage and every `member: { eq: false }` fires against a card nobody checked;
  insert nothing and neither `eq: true` nor `eq: false` has a fact to bind, while a `notExists` over
  the membership sees the gap and can fail closed. It is the absence-versus-value distinction §2.6.1
  draws for a field, applied one level up, to the fact. The entries-as-facts shape has the mirror
  hazard: a load that fails part-way leaves an empty list, indistinguishable from a list with no
  entries, so every `notExists` over it fires. There, a failed load must fail the session rather
  than leave it half-filled.
- Cluster propagation is the store's job, and the engine keeps no copy. Every node reads through
  at session start, so a write from any node is visible to the next session anywhere as soon as the
  store has it. A per-node cache would reintroduce the problem this design removes.
- Determinism holds per session, and ordering across sessions is the host's. Two sessions for the
  same key running concurrently each read before either writes. The host makes list writes
  idempotent, or routes events for one key to one lane so they run in sequence. The engine cannot
  order sessions it did not start.
- Which lists a rule set reads is derivable from the compiled rules, without parsing anything: a
  walk of `CompiledRule.source().when()` for patterns on the membership type reads the `list`
  literal. That walk reads and never mutates: the constraint records deep-copy a literal on the way
  in but hand back the live node, so an edit there changes what every session matches (§5.5's
  invariant 1, `ImmutabilityTest`). An explicit declaration beside the rule file is the better audit
  record; the walk is what checks that the declaration is complete.

`callFunction` is not the door for either half. It is `void`, so it cannot bring a value back; it
runs at commit, inside the fire loop; and its own contract asks handlers to be deterministic and
non-blocking, which a store round trip is not.

## Choosing a matcher

There are three matchers, held to producing identical firing sequences. Everything deciding which
activation fires lives in one shared base, so they can differ only in how matches are found.

| `matching(...)` | Fits when |
|---|---|
| `NETWORK` (default) | A session is created, filled, fired, closed. Joins recomputed per cycle from indexed pattern memories |
| `RETE` | A session is long-lived and fires thousands of times. Joins materialised as facts arrive; the conflict set is pushed and pulled rather than rebuilt |
| `NAIVE` | Never in production. No network, no indexes, `O(rules × facts^arity)`. The correctness oracle, shipped so a host can test its own rules against it |

`MatcherEquivalence` and `ShuffleHarness` in `rule-engine-testkit` point that oracle at a host's
rule set; see
[`MatcherAgreementTest`](../rule-engine-example/src/test/java/com/codeheadsystems/rules/example/MatcherAgreementTest.java).
Curves are in [`benchmarks.md`](benchmarks.md).

## Concurrency

One virtual thread per session (§5.2). No locks, no pool to size.

```java
List<BatchOutcome<FireResult>> outcomes = RuleBatches.run(rules, batches, (session, batch) -> {
    batch.forEach(f -> session.insert(f.type(), f.payload()));
    return session.fireAllRules();
});
```

Every input comes back as a `BatchOutcome` holding either a value or a throwable. A batch that fails
does not stop the others, because §5.2 refuses to decide for the caller what a partial batch means.

The scaling figures, the shared-nothing control they are measured against, and what they do not show
are in [`benchmarks.md`](benchmarks.md).

A stream rather than a batch uses `SessionActor`. "Fire until told to stop" is a blocking loop,
so inserting from a producer thread while it runs is a data race. One worker owns the session,
producers feed a bounded inbox, and a burst of inserts costs one fire cycle rather than one each.

## Long-lived sessions and eviction

Everything a long-lived session grows (working memory, node memories and their indexes, the
refraction memory, the beta memory) is keyed on handles, so letting go of facts bounds all of them
at once. `eviction(EvictionPolicy)` takes a total cap, a per-type cap, or a time window, and
evicting runs the full retract path.

```java
EvictionPolicy.leastRecentlyUsed(10_000);                        // a cap on the session
EvictionPolicy.perType(Map.of("Order", 10_000));                 // a cap per type
EvictionPolicy.window("LoginFailure", "at", 600_000);            // ten minutes of one type
```

The window is the one a streaming rule set usually wants: `perType` bounds the arrival count, and
the two differ by exactly the traffic spike the rules exist to notice. Its far edge is the newest
value of `at` that type currently holds, minus the span (a watermark taken from the data, never a
clock), so two runs over the same stream evict the same facts and §7.3 survives. It also means time
advances only when a fact carrying a later time arrives: a session that goes quiet holds what it
held, because "nothing arrived" remains the one input the engine never receives. The span is in the
field's own units, exactly as a rule's `within` is.

Four points apply to windowing a type:

1. Retention is per type and per session; a window in a rule is per rule. Two rules wanting ten
   minutes and twenty-four hours of one type are served by retaining twenty-four hours and letting
   the ten-minute rule narrow its own match with `within`. Retain less than the widest rule window
   and the rule silently loses matches its author wrote.
2. A negation or a universal over a windowed type changes meaning; see the warning below. An
   `accumulate` over one is the case where that interaction is the point ("how many in the window"),
   and is still governed by (1).
3. A fact with no usable time is never evicted, because a policy that cannot prove a fact is old
   must not guess. A type whose facts do not all carry the field needs a `perType` cap as well.
4. A fact that arrives already outside the window is evicted on arrival, inside the `insert` call
   that added it, so `insert` can hand back a handle whose fact is already gone. That is correct (a
   fact older than the window is one no windowed rule can match), and it is the ordinary
   out-of-order case rather than an exotic one, so a stream with late arrivals drops them silently.
   A listener's `onEvicted` is the only thing that says so.

The rule-author half of this (velocity counts, and the `Clock` fact for "as of now") is in
[`dsl-guide.md`](dsl-guide.md#counting-things-in-a-window).

> ### The eviction hazard
>
> A type that the rules negate, quantify over, fold, or conclude must never be capped. An evicted
> fact is indistinguishable from one that was never there, and that collides with each of those four
> features differently: evicting a negated type manufactures a false conclusion; a quantified type
> has its requirement deleted rather than weakened; an accumulated type quietly changes a number; a
> concluded type loses a belief nothing can redraw.
>
> `MatchExplainer` warns for all four and can detect none of them: it re-asks the same question of
> the same working memory and is fooled identically.
>
> A windowed type is the one case where two of those are intended rather than a hazard: an
> `accumulate` over a window is the count the rule asks for, and a `notExists` over one reads as
> "not lately" rather than "never". That reading is useful, and it is a false conclusion for any
> rule on that type that meant "never". The rule set has to agree with the retention, because
> nothing reports that it does not.
>
> For most rule sets the answer is not a policy but explicit retraction from the application, which
> knows the thing the engine cannot: that this unit of work is finished. The example works the
> analysis through a real rule set and finds exactly one of six types safe to cap; see
> [`StreamingDemo`](../rule-engine-example/src/main/java/com/codeheadsystems/rules/example/StreamingDemo.java).

## Swapping rules while running

`RuleSetHolder` is §5.6's hot reload: one volatile field, no locks, and two contracts. `publish`
takes a compiled rule set, so a broken rule file leaves the previous version serving, and a swap
affects new sessions only.

```java
RuleSetHolder rules = new RuleSetHolder(RuleFiles.compile(source));
rules.publish(RuleFiles.compile(newSource));   // compile first: a failure here changes nothing
```

There is no safe in-place swap for a session already running: its memories, refraction state, and
agenda are shaped by the old network's node ids. `SessionDrain.restart` exports the facts, creates
and loads the new session, and closes the old one only once that has succeeded, so a failed replay
leaves the caller holding a working session rather than none. Derived facts are not replayed: the
new session re-derives them, and replaying would double-count.

## Action failures

Atomicity is per-phase (§4.6). A staging failure applies nothing. A commit failure (a
`callFunction`, an `EventSink`, a `setField` whose path runs through a scalar) leaves what already
landed. There is no compensating undo and there cannot be one: a sent message cannot be un-sent.

`FireRecord` carries what committed, which action threw, and which never ran.

Under the default `RETHROW`, only a registered listener receives that record. The original exception
propagates, so unlike a limit breach it cannot carry a partial result; `onAfterFire` is published
for the failed firing before the rethrow. With no listener, the record of that firing and
every firing before it in the call is gone with the stack unwind. `onRhsError` takes an
`RhsErrorHandler`, whose `Decision` may also be `ABORT_SESSION` (return the partial `FireResult`
rather than throw) or `SKIP_ACTIVATION`.

A listener must not throw and must not call back into the session; neither is enforced.

## Observability and audit

`session.stats()` returns a `SessionStats`, which is the dashboard:

| Field | What a rising value means |
|---|---|
| `factCount` | working memory; what `maxFacts` is checked against |
| `refractedMatchCount` | matches remembered as fired; bounded only by retract and eviction |
| `materialisedMatchCount` | complete matches held by the streaming matcher; zero under the recomputing ones |
| `materialisedHandleCount` | handles its reverse index still tracks; a leak hides here, not above |
| `pendingMatchCount` | matches waiting to fire; what a streaming fire cycle costs |
| `concludedFactCount` | live logical conclusions. Flat facts + climbing conclusions = rules concluding faster than their reasons expire |
| `evictedCount` / `evictedByType` | split, because "a rule stopped matching because its facts were let go" looks exactly like "it never matched" |

`rule-engine-observability` ships `TracingListener` (a bounded ring buffer of recent `FireRecord`s)
and `JfrListener` (Flight Recorder events per firing). One `TracingListener` may be shared across a
batch run and locks correctly for it, at the cost of interleaving every session into one buffer.
That suits an aggregate trace and not the diagnosis of a single loop.

Audit correlation is built in. Every emitted event carries an `EmitContext` of
`(sessionId, ruleId, handles, ruleSetVersion)`. That last field is the content hash of the rules
that produced the decision, so "which rules made this call, six months ago" is answerable from the
event alone.

## Diagnosing production

`MatchExplainer` answers "why did rule R not fire", and its constructor needs a live session holding
the facts, which at 3am no longer exists. The capture path therefore has to be built in advance:

1. Capture the facts with a `RuleEngineListener` recording inserts, or with `session.exportFacts()`
   before close. It returns `List<ExportedFact>` ordered by handle id, which is the order §7.3
   states its guarantee in. Key the capture by `EmitContext.ruleSetVersion`.
2. Replay them: compile that exact rule-set version and `SessionDrain.replay` the facts into a
   session.
3. Explain with `new MatchExplainer(rules, session).explain("rule-id")`, or with the pinned form
   taking `Map<String, FactHandle>` when the question is about specific facts.

Because firing is a pure function of the facts and their order, a replay reproduces the original
decision exactly. That is the purpose of the determinism contract, and it is what makes this
procedure work.

## Getting out

Rules, facts, and outputs are portable. Rules are YAML or JSON against a published `rules.v1`
schema: text the host owns, not an opaque binary. Facts are the host's own JSON, and `exportFacts()`
hands them back. Outputs are emitted events the host already consumes.

The fact model is not portable. The flattened fact model (one fact per collection element, joined by
id) is a modelling decision that shapes the host's ingestion path, and it does not unwind for free.
Neither does the absent-versus-null distinction, which most alternatives collapse.

If the project stalls, the licence is Apache 2.0, sources and javadoc jars are published, the design
is recorded in a 2,000-line specification that names its rejected alternatives, and the test suite
includes a naive correctness oracle any change can be checked against. Forking is a real option
rather than a formality.
