# A worked example

One small application, one rule file, ten events. The whole application runs with one command:

```bash
./gradlew :rule-engine-example:run
```

Everything below is in this module, compiled by CI and executed by its tests. Every YAML block that
begins with `apiVersion:` is a fixture in `ReadmeExamplesTest`; if this page and the engine
disagree, this page is wrong.

The rule-file DSL itself is taught in [`docs/dsl-guide.md`](../docs/dsl-guide.md). What follows
assumes it, and covers the shape of the application around the rules.

## Contents

- [The domain](#the-domain)
- [Three modelling decisions](#three-modelling-decisions)
- [The rules](#the-rules)
- [Four ways to run it](#four-ways-to-run-it)
- [What to assert in CI](#what-to-assert-in-ci)
- [Two defects this example found in itself](#two-defects-this-example-found-in-itself)
- [File map](#file-map)

## The domain

A small merchant's order back office. Four fact types arrive from outside:

| Fact type | Where it comes from |
|---|---|
| `Customer` | reference data, upserted |
| `Order` | one per `order.placed` event |
| `LineItem` | one per element of that event's `items` array |
| `Payment` | one per `payment.received` event |

and two are derived by the rules themselves: `OrderUnpaid`, which is a withdrawable conclusion, and
`Discount`, which is an ordinary fact recording a decision.

The feed is [`feed/orders.jsonl`](src/main/resources/feed/orders.jsonl), ten lines telling one
story: two customers, four orders, three payments, and one restock. Editing it and re-running the
demos is what it is for.

## Three modelling decisions

All three are made before the first rule is written, and all three live in
[`Ingest.java`](src/main/java/com/codeheadsystems/rules/example/Ingest.java). Two of them are
expensive to change once rules exist.

The first is that collections are flattened. An `order.placed` event carries its line items nested
inside it. That is not what goes into working memory: one `Order` without them, plus one `LineItem`
per element, carrying the order id. There is no wildcard in a JSON Pointer (`items.*.qty` does not
exist), so a nested array is a value the engine can store and can never match inside. Flattening is
also the only way to get an index over elements at all: `LineItem./orderId` is indexable, and
"somewhere inside this array" is not.

The second is that an upsert lands on the handle. A second `customer.upserted` for the same id is
an `update` on the existing handle, not a second insert. Fact identity is the handle, not anything
in the payload, so two `Customer` facts with the same `id` are two facts and every join matching one
matches both.

The third is that absent fields read from an expression are normalised at ingestion. `priority` is
defaulted to `false` there. Every operator map in the engine treats an absent field as a value; CEL
treats it as an error. Normalising once at the edge saves writing `has(o.priority) && o.priority` in
every expression.

## The rules

The whole file is [`rules/orders.yaml`](src/main/resources/rules/orders.yaml), and each rule is
there to be the shortest realistic use of one feature.

| # | Rule | Teaches |
|---|---|---|
| 1 | `high-value-order-review` | a join, `salience`, `setField`, and a rule that terminates itself |
| 2 | `unpaid-order` | `notExists`, and `logical: true` (a conclusion that is withdrawn) |
| 3 | `ready-to-ship` | `forAll`, and the companion that closes its vacuity trap |
| 4 | `bulk-order-discount` | `accumulate` (an alias that binds a number, not a fact) |
| 5 | `paid-within-the-hour` | `after` / `within`: ordering two facts with no clock |
| 6 | `repeat-unpaid-customer` | forward chaining, over the facts rule 2 concluded |
| 7 | `expedite-eligible` | §6.4's expression escape hatch, and why it needs `noLoop` |

Two are printed in full here.

### A conclusion that can be withdrawn

```yaml
apiVersion: rules.v1
rules:
  - id: unpaid-order
    salience: 10
    when:
      - fact: Order
        as: o
        where:
          status: { hasField: true, ne: "CANCELLED" }
      - fact: Payment
        as: p
        quantifier: notExists
        where:
          orderId: { eq: { $ref: o.id } }
    then:
      - action: insertFact
        fact: OrderUnpaid
        logical: true
        payload:
          orderId: { $ref: o.id }
          customerId: { $ref: o.customerId }
```

`hasField: true` beside the `ne` is load-bearing: `ne` is defined as `!eq`, so an order with no
`status` at all satisfies `ne: "CANCELLED"`.

`logical: true` is what makes `OrderUnpaid` a belief rather than a record. The streaming output
shows it: the conclusion is drawn when the order arrives and withdrawn at the next cycle boundary
when its payment does. Nothing in the application deletes it; the justification went, so it went.
That is also what cleans up when `Ingest.retractOrder` lets go of an unpaid order.

### The `forAll` companion pattern

```yaml
apiVersion: rules.v1
rules:
  - id: ready-to-ship
    when:
      - fact: Order
        as: o
        where:
          status: { hasField: true, ne: "CANCELLED" }
      - fact: Payment
        as: pay
        where:
          orderId: { eq: { $ref: o.id } }
      - fact: LineItem
        as: some
        quantifier: accumulate
        accumulate:
          count: true
          having: { gte: 1 }
        where:
          orderId: { eq: { $ref: o.id } }
      - fact: LineItem
        as: all
        quantifier: forAll
        where:
          orderId: { eq: { $ref: o.id } }
          inStock: { eq: true }
    then:
      - action: emit
        event: order.readyToShip
        payload:
          orderId: { $ref: o.id }
```

The rule means "there are some, and all of them". The `forAll`'s join picks the scope (this order's
line items), and its other constraints are the requirement. On its own that is vacuously true for
an order with no line items at all, so `some` is there to mean "and there is at least one".

`some` is an `accumulate` rather than the plain positive pattern the DSL guide suggests, and the
difference is cardinality rather than meaning. A plain pattern binds a line item, so a two-item
order produces two matches and fires this rule twice, emitting the same event each time. An
`accumulate` binds a number, so there is one match per order however many items it has. See
[below](#two-defects-this-example-found-in-itself).

## Four ways to run it

The rules never change. What changes is **session scope**, and that decides what a rule can possibly
see.

### 1. A session per order

[`PerOrderDemo`](src/main/java/com/codeheadsystems/rules/example/PerOrderDemo.java) is the shape to
start from. It creates a session, inserts the facts one decision is about, fires once, reads the
result, and closes the session.

```
O-2: 3 rule(s) fired, stopped because DRAINED
    emit  order.flagged            {"orderId":"O-2","customerId":"C-2","riskTier":"HIGH"}
    emit  order.expedited          {"orderId":"O-2"}
    emit  order.readyToShip        {"orderId":"O-2"}
```

The rules are compiled once at startup; the `CompiledRuleSet` is immutable and shared. Sessions are
the cheap per-unit-of-work thing.

Rule 6 never fires in this shape. It counts a customer's unpaid orders, and a session holding one
order can only count to one. Nothing is wrong with the rule; the session cannot see the facts it
needs. Session scope is the first thing to check, before the explainer.

### 2. The same work across virtual threads

[`BatchDemo`](src/main/java/com/codeheadsystems/rules/example/BatchDemo.java). `RuleBatches.run`
gives one virtual thread and one session per input, and hands back a `BatchOutcome` per input
holding either a value or a throwable. A batch that fails does not stop the others, and what a
partial result means is the caller's decision to make.

There is no lock and no pool to size. One `SessionOptions` fans out across every session, though, so
anything mutable it holds becomes shared state: here that is `OpsPager`, which is written for it.

### 3. One long-lived streaming session

[`StreamingDemo`](src/main/java/com/codeheadsystems/rules/example/StreamingDemo.java). Three things
change, and each is a decision rather than a setting.

The matcher becomes `RETE`, which maintains joins as facts arrive instead of recomputing them per
cycle. It is right when tuples survive thousands of cycles, and wrong for a session that fires once.

The owner becomes a `SessionActor`. A session is single-writer and "fire until told to stop" is a
blocking loop, so inserting from a producer thread while it runs is a data race. One worker owns the
session; producers hand it work through a bounded inbox.

The bound has to come from somewhere, because every structure a long-lived session grows is keyed on
handles. One trace shows the whole sequence:

```
    order.placed          <- O-2 arrives
      emit  order.flagged            {"orderId":"O-2","customerId":"C-2","riskTier":"HIGH"}
      emit  order.expedited          {"orderId":"O-2"}
    order.placed          <- O-3 arrives; C-2 now has two unpaid orders
      emit  order.expedited          {"orderId":"O-3"}
      emit  customer.atRisk          {"customerId":"C-2","unpaidOrders":2}
  after the feed: 18 facts, 1 conclusions held, 18 matches materialised
    retract everything about O-1
      let go of 4 facts -> 14 facts, 1 conclusions held
    retract everything about O-3
      let go of 2 facts -> 11 facts, 0 conclusions held
```

#### Eviction

§4.4 gives the engine a fact-eviction policy, and of this rule set's six types exactly one can use
it. The demo prints the analysis, and the analysis belongs on paper before any eviction is
configured. An evicted fact and an absent fact are indistinguishable, so a cap on `Payment` (which
rule 2 negates) does not cost a firing; it asserts that a paid order is unpaid. A cap on `LineItem`
does not weaken rule 3's requirement, it deletes it, and changes rule 4's total without changing
anything a reader would look at.

The exception is `Discount`: rule 4 inserts it, and nothing patterns, negates, folds, or concludes
it. It is also the one type nothing here can let go of, because a rule created it and the
application holds no handle. So in a session that runs for a week it is the type that actually
grows, and `SessionOptions.eviction(EvictionPolicy.perType(Map.of("Discount", n)))` is the right
answer for it.

Four of the remaining five are bounded by the application retracting what it knows is finished,
which is knowledge the engine does not have. The sixth, `Customer`, is bounded by how many customers
the business has; nothing in this session ever lets one go. That is usually acceptable, provided it
is known rather than assumed.

### 4. Explaining a rule that did not fire

[`DiagnosticsDemo`](src/main/java/com/codeheadsystems/rules/example/DiagnosticsDemo.java). A rule
that did not fire leaves nothing in a log, because the fast path is optimised precisely not to
record what it eliminated. `MatchExplainer` re-asks the question one constraint at a time:

```
rule ready-to-ship: 1 combination(s) matched every pattern and join, but the rule asserts that
every LineItem in scope for 'all' matches it and fact #4 does not (§2.5's FOR_ALL)
  o: Order — 1 considered, 1 matched
  pay: Payment — 1 considered, 1 matched
  all all: LineItem — 2 present, suppressed 1 match(es) — e.g. fact #4 is in scope and fails it
  fold some: LineItem — 2 present, suppressed nothing
```

The same class also shows a dry run, which matches and resolves conflicts but executes nothing, so
"what would fire" can be diffed against the current rule set before a change ships. It also shows
`RuleSetHolder`, which swaps a compiled rule set in under load. `publish` takes a compiled rule set,
so a rule file with a typo in it fails at compile time and the engine stays in service on the rules
it has.

## What to assert in CI

[`ExampleRulesTest`](src/test/java/com/codeheadsystems/rules/example/ExampleRulesTest.java) is meant
to be copied. It needs no facts and runs in milliseconds, and it makes three assertions:

| Assertion | What it catches |
|---|---|
| the rule set compiles | syntax; every diagnostic names a file, line, and column |
| `unreachableRules()` is empty | what `declaredFactTypes` buys: a rule patterning `Ordr` instead of `Order` compiles without error and never fires, and nothing at runtime can tell that apart from facts that have not arrived yet |
| the unindexed set is exactly the one somebody accepted | a new join that falls back to a linear scan, named on the build; it is an allowlist rather than `isEmpty()`, because `ne`, a CEL condition, and a temporal join can never be indexed, and the value is in the diff |

[`OrderPipelineTest`](src/test/java/com/codeheadsystems/rules/example/OrderPipelineTest.java) is the
other half: it fires the rules and asserts on the emitted events. Nothing performs I/O by default,
and `FireResult.emitted()` comes back from the firing records, so this needs no mocking at all.

[`MatcherAgreementTest`](src/test/java/com/codeheadsystems/rules/example/MatcherAgreementTest.java)
points the testkit's own harnesses at these rules: `MatcherEquivalence` checks this rule set against
the naive oracle, and `ShuffleHarness` checks that rule declaration order does not reach the firing
sequence. Both are available to any consumer, for their own rules.

## Two defects this example found in itself

Both produce a plausible wrong answer.

`ready-to-ship` fired twice per order. The positive companion pattern that closes the `forAll`
vacuity trap also binds a fact, so a two-item order produced two matches and emitted the same event
twice. The fix is the `accumulate count` above. Nothing about this is visible in a rule review; it
showed up the first time the demo ran against an order with more than one line item.

Rule 1 re-fired rule 2. Rule 1 originally wrote `status`, which rule 2 tests. That cleared rule 2's
refraction, re-fired it, and made it draw its conclusion a second time. The result was correct in
the end, because a re-firing replaces its conclusion, but it confused every reader of the trace.
Rule 1 writes `riskState` now. A field written by one rule and tested by another is a coupling, and
the write belongs in its own field.

## File map

| Path | What it is |
|---|---|
| `src/main/resources/rules/orders.yaml` | the rule set |
| `src/main/resources/feed/orders.jsonl` | the ten events |
| `OrderRules.java` | compile once, with the `CompilerOptions` that turn runtime surprises into compile errors |
| `Ingest.java` | events to facts: the three modelling decisions |
| `OrderEvent.java`, `EventFeed.java` | the application's own envelope and feed reader |
| `OpsPager.java` | the `callFunction` handler, and why it is the wrong default |
| `PerOrderDemo`, `BatchDemo`, `StreamingDemo`, `DiagnosticsDemo` | the four shapes |
| `Main.java` | runs all four |
