# Writing rules

A guide for somebody who has a rule to write and would rather not read a specification first. It
assumes you know your data and nothing about this engine. For the complete surface — every operator,
every action, every error code — see [`dsl-reference.md`](dsl-reference.md).

Every YAML block here that begins with `apiVersion:` is a fixture in `DocExamplesTest`. If this page
and the engine disagree, this page is wrong.

## Contents

- [Three things to know before you start](#three-things-to-know-before-you-start)
- [Your first rule](#your-first-rule)
- [Matching two facts together](#matching-two-facts-together)
- [Asking that a fact not exist](#asking-that-a-fact-not-exist)
- [Doing something](#doing-something)
- [Checking a list your application owns](#checking-a-list-your-application-owns)
- [Running it](#running-it)
- [When it does not fire](#when-it-does-not-fire)
- [Checking your rules in CI](#checking-your-rules-in-ci)

## Three things to know before you start

These three surprise nearly everybody, they are all deliberate, and two of them shape how you model
your data on day one. Read them now; they cost five minutes here and a rewrite later.

### Absent and null are different

A field that is missing and a field explicitly set to `null` are **not** the same value.

```yaml
closedAt: { eq: null }        # matches {"closedAt": null}, NOT {}
closedAt: { hasField: false } # matches {}, NOT {"closedAt": null}
closedAt: { isNull: true }    # matches {"closedAt": null}
```

JSON can express both, so the engine distinguishes both. Collapsing them would mean picking one and
silently mismatching the other.

### `ne` is true for a missing field

`status: { ne: "CLOSED" }` matches an order **with no `status` at all**, because `ne` is defined as
"not `eq`", and an absent field is not equal to `"CLOSED"`.

This is a genuine trap and it is accepted on purpose, because the alternative is three-valued logic
everywhere. When you mean "present, and not closed", say so:

```yaml
apiVersion: rules.v1
rules:
  - id: open-orders
    when:
      - fact: Order
        as: o
        where:
          status: { hasField: true, ne: "CLOSED" }
    then:
      - action: emit
        event: still-open
```

The same applies to `notIn`, for the same reason.

If you register a schema (see [the reference](dsl-reference.md#fact-schemas-optional)), the compiler
will find these for you: an `ne` against a field the schema calls optional, with no `hasField: true`
guarding it, comes back as an `ne-on-optional-path` warning naming the fix.

### Flatten collections at ingestion

**You cannot match inside an array.** There is no wildcard: `items.*.qty` does not exist and will
not be added, because the path syntax (RFC 6901 JSON Pointer) has no such thing.

So an order with line items does not become one fact:

```json
{"id": 1, "items": [{"sku": "A", "qty": 20}, {"sku": "B", "qty": 2}]}
```

It becomes one `Order` fact plus one `LineItem` fact per element, each carrying the order id:

```json
{"id": 1}
{"orderId": 1, "sku": "A", "qty": 20}
{"orderId": 1, "sku": "B", "qty": 2}
```

Then "any line item with `qty > 10`" is an ordinary join:

```yaml
apiVersion: rules.v1
rules:
  - id: bulk-line-item
    when:
      - fact: Order
        as: o
      - fact: LineItem
        as: li
        where:
          orderId: { eq: { $ref: o.id } }
          qty:     { gt: 10 }
    then:
      - action: emit
        event: bulk-item
        payload:
          orderId: { $ref: o.id }
          sku:     { $ref: li.sku }
```

This is not a workaround. Flattening is *how you get indexing and incremental matching over
collection elements at all* — the engine can index `LineItem./orderId` and cannot index "somewhere
inside this array". Decide it now: retrofitting means rewriting every rule that touches a
collection.

## Your first rule

A rule file needs a version and a list of rules. Each rule needs an id, a `when`, and a `then`.

```yaml
apiVersion: rules.v1
rules:
  - id: large-order
    when:
      - fact: Order          # the fact type, as your host inserts it
        as: o                # a short name you will use to refer to it
        where:
          total: { gt: 10000 }
    then:
      - action: emit
        event: large-order-seen
        payload:
          orderId: { $ref: o.id }
```

`where` maps a **field name** to an **operator map**. Everything inside is AND-ed, and so is
everything across fields, and so is everything across patterns. There is no `or` — write two rules,
or use `in`.

Field names are dotted: `customer.tier` reads `/customer/tier`.

To put two conditions on one field, put both operators in the **same** map — `{ hasField: true, ne:
"CLOSED" }`. Writing the field name on two lines is a duplicate key, and the engine rejects it
rather than silently keeping only the second.

## Matching two facts together

Name the other fact's field with `{ $ref: alias.field }`. This is a join.

```yaml
apiVersion: rules.v1
rules:
  - id: high-value-order-review
    salience: 10
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
        event: order.flagged
        payload:
          orderId: { $ref: o.id }
          reason:  "high value + risk tier"
```

**Reference an alias declared earlier in the same `when`.** `c` may name `o` because `o` comes
first. The reverse is an error — that rule keeps joins acyclic and is not a limitation you will feel
in practice, because you can always reorder the patterns.

**You are not limited to equality.** Any comparison that relates two facts works:

```yaml
total: { gt: { $ref: c.creditLimit } }
```

Do not worry about which pattern to write first for speed. The engine picks the binding order fresh
on every fire cycle, smallest set first — the order you write is for readability.

## Asking that a fact not exist

Add `quantifier: notExists` to a pattern and it asserts an absence instead of a match. The rule
below fires for every pending order with no payment against it:

```yaml
apiVersion: rules.v1
rules:
  - id: unpaid-order
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "PENDING" }
      - fact: Payment
        as: p
        quantifier: notExists
        where:
          orderId: { eq: { $ref: o.id } }
    then:
      - action: emit
        event: order.unpaid
        payload: { orderId: { $ref: o.id } }
```

**The negated pattern binds nothing.** `p` is a name for the fact being looked for, so its `where`
can be written; nothing else may use it. A `then` action naming `p` is a compile error, because
there is no such fact to act on — that is the point of the rule.

**Two things to know before you rely on it**, both of which are about facts arriving later.

*A rule that fired because something was absent does not un-fire when it turns up* — unless you ask
it to. Add `logical: true` to the `insertFact` and the conclusion is withdrawn when the payment
arrives, and drawn again if the payment is retracted. See [Withdrawing a
conclusion](#withdrawing-a-conclusion) below. Without it the fact stands, which is what every rule
written before that key existed does.

*Never negate a fact type your session evicts.* Eviction bounds a long-lived session by dropping
facts, and a dropped fact is indistinguishable from one that was never there — so a cap on
`Payment` makes this rule say a paid order is unpaid. Everywhere else in the engine eviction costs
you a firing at worst. Cap the types you bind.

## Asking that every fact meet a requirement

`quantifier: forAll` is the other half. It asserts that every fact **in scope** satisfies a
requirement — and the join is what picks the scope:

```yaml
apiVersion: rules.v1
rules:
  - id: order-ready
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "PENDING" }
      - fact: LineItem
        as: li
        quantifier: forAll
        where:
          orderId: { eq: { $ref: o.id } }
          inStock: { eq: true }
          qty: { gt: 0 }
    then:
      - action: emit
        event: order.ready
        payload: { orderId: { $ref: o.id } }
```

Read it as two halves. `orderId: { eq: { $ref: o.id } }` is the join, and it says *which* line items
this is about: the ones belonging to this order. Everything else is what must be true of them. A
line item on somebody else's order being out of stock does not stop this rule firing, because the
rule never claimed anything about it.

**Only the join picks the scope.** Anything with a literal value is part of the requirement. So
there is no way to say "every *physical* line item" — a `type: { eq: "PHYSICAL" }` line would make
your digital items counterexamples, and the rule would quietly never fire. When you need a narrower
scope, split the fact type at ingestion.

**Why not just write a `notExists`?** For one constraint you can — "every order is shipped" is
`notExists` an order with `status: { ne: "SHIPPED" }`. For two you cannot: the opposite of "in stock
*and* qty above zero" is an *or*, and a `where` block has no `or`.

**The trap: an empty scope is `true`.** The rule above fires for an order with no line items at all,
because there is nothing to fail the requirement. That is how "for all" works everywhere, and it is
still going to surprise you at 3am. If you mean "there are some, and all of them", say that there is
at least one:

```yaml
      - fact: LineItem
        as: some
        quantifier: accumulate
        accumulate:
          count: true
          having: { gte: 1 }
        where:
          orderId: { eq: { $ref: o.id } }
```

**Count them; do not bind one.** A plain positive pattern says the same thing and costs you
cardinality: it *binds* a line item, so a three-item order produces three matches and fires the rule
three times, once per item. An `accumulate` binds a number, so there is one match per order however
many items it has. Write the plain pattern only when you want the per-item firing.

Everything below is about the `forAll` pattern, not about the `accumulate` companion — a fold *does*
bind a name you can read from `then`, from a `condition:` and from its own `having`, which is the
one thing the three quantifiers do not share.

Everything the negation section says otherwise applies to a `forAll` as well — the pattern binds
nothing, no `then` action may name its alias, a firing is not undone when a counterexample arrives
unless it concluded logically (see [Withdrawing a conclusion](#withdrawing-a-conclusion)), and you
must not quantify over a type your session evicts. That last one bites harder here: eviction only
ever removes counterexamples, so a cap makes the requirement *easier* to satisfy, and a cap that
empties the scope deletes it.

## Putting two facts in order

`after` and `before` relate two facts by a time field, within a bound:

```yaml
apiVersion: rules.v1
rules:
  - id: quick-payment
    when:
      - fact: Order
        as: o
      - fact: Payment
        as: p
        where:
          orderId: { eq: { $ref: o.id } }
          paidAt:  { after: { $ref: o.placedAt, within: 86400000 } }
    then:
      - action: emit
        event: order.paid.quickly
```

"Paid after it was placed, and within a day of it."

Three things to know:

*The engine has no clock.* Every time it uses is a field on a fact you inserted. That is deliberate —
it means replaying the same facts gives you the same firings, today or next year, on your laptop or
in CI. It also means the engine cannot help you with "nothing has happened for an hour": no fact
arriving is the one thing it never hears about.

*`within` is in whatever units your field is in.* `86400000` is a day only because `placedAt` holds
epoch milliseconds. If it held seconds, a day is `86400`. Nothing checks this, so if there is any
chance of confusion put the unit in the field name.

*The bound is not optional, and must be more than zero.* If you just want "later than", write
`gt: { $ref: o.placedAt }` — that already works. `after` exists for the bounded case, which `gt`
cannot express. A bound of `0` would match nothing at all, so it is refused rather than compiled.

## Adding things up

`quantifier: accumulate` folds a set of facts into one value and hands it to you.

```yaml
apiVersion: rules.v1
rules:
  - id: bulk-order
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "OPEN" }
      - fact: LineItem
        as: units
        quantifier: accumulate
        accumulate:
          sum: "qty"
          having: { gt: 100 }
        where:
          orderId: { eq: { $ref: o.id } }
    then:
      - action: emit
        event: order.bulk
        payload:
          orderId: { $ref: o.id }
          units:   { $ref: units }
```

Read it in three parts. The `where` says *which* line items — this order's. The `sum` says what to
do with them. The `having` says the rule only fires when the answer clears 100. And `units` is now a
name you can use in `then`, written as a bare `$ref: units` because it is a number, not a fact.

Five functions: `sum`, `count`, `min`, `max`, `average`. `count: true` takes no field; the rest name
one. One per block.

Three things that will catch you out:

*An empty set is not zero for everything.* `count` and `sum` of nothing are `0`. `min`, `max` and
`average` of nothing are *absent*, so a `having` on them does not hold. That is on purpose — the
average of no orders is not zero, and pretending otherwise makes "average under 10" true for a
customer who has never bought anything.

*A missing field is skipped, not zero.* Average three line items where one has no price and you get
the average of two. If you want the ones without a price excluded from the count as well, say so in
the `where` with `hasField`.

*You cannot join to it.* `$ref: units` works in `then` and in a `condition:`. It does not work in
another pattern's `where`, because a join matches two facts and `units` is a number.

And the same eviction warning as the other quantifiers, for a worse reason: if you cap `LineItem`,
your totals go quietly wrong rather than your rule going quiet.

## Counting things in a window

Everything in the last two sections composes, and the composition is the rule most people come here
for: *how many of these, for one subject, inside a window*. Five failed logins for one user in ten
minutes:

```yaml
apiVersion: rules.v1
rules:
  - id: login-velocity
    when:
      - fact: LoginFailure
        as: trigger
      - fact: LoginFailure
        as: recent
        quantifier: accumulate
        accumulate:
          count: true
          having: { gte: 4 }
        where:
          user: { eq: { $ref: trigger.user } }
          at:   { before: { $ref: trigger.at, within: 600000 } }
    then:
      - action: emit
        event: account.locked
        payload:
          user:          { $ref: trigger.user }
          priorFailures: { $ref: recent }
```

The `trigger` is the failure that just arrived, and it anchors the window: `before … within 600000`
selects the failures in the ten minutes leading up to it. No clock is involved anywhere — the window
is measured between two facts, exactly as [putting two facts in
order](#putting-two-facts-in-order) does.

**`gte: 4`, for five failures.** Two rules combine to make the count exclude the trigger: `before` is
strict on the near side, and an `accumulate` over a type the rule already binds is about the *other*
facts of that type. So the count is the failures *preceding* the trigger, and the trigger is the
fifth. Write the number you mean and then subtract one, or you will ship a rule that fires one event
late.

**A window in the rule does not bound memory.** The rule above matches ten minutes; the session still
holds every `LoginFailure` ever inserted, and the accumulate walks all of them on every candidate.
For a long-lived session, the host pairs the rule with a retention window:

```java
SessionOptions.builder()
    .eviction(EvictionPolicy.window("LoginFailure", "at", 600_000))
    .build();
```

That drops facts older than the newest `at` this type currently holds, minus the span — a watermark
taken from the data, never a clock, so replaying the same stream evicts the same facts. A failure
that arrives *already* older than that is dropped on arrival, which is the honest cost of a window
with no clock behind it. Two things to
get right, both covered in [`embedding.md`](embedding.md#long-lived-sessions-and-eviction):
**retention must be at least as wide as the widest window any rule writes against that type**, and
**a `notExists` over a windowed type changes meaning** — it stops saying "never" and starts saying
"not lately".

There is a bonus in the combination. If the rule concludes with `logical: true`, the conclusion is
withdrawn when its failures age out of the retention window, because eviction is an ordinary retract
and [truth maintenance](#withdrawing-a-conclusion) re-asks the match. A lock that expires by itself,
in an engine with no clock.

### "As of now", and "nothing has happened"

An anchored window needs a fact to anchor it. When you want the window to end at *now* rather than at
an arriving fact — "this account has had no failure in the last ten minutes" — insert the clock as a
fact and let your application advance it:

```yaml
apiVersion: rules.v1
rules:
  - id: quiet-account
    when:
      - fact: Clock
        as: now
      - fact: Account
        as: a
      - fact: LoginFailure
        as: f
        quantifier: notExists
        where:
          user: { eq: { $ref: a.user } }
          at:   { before: { $ref: now.at, within: 600000 } }
    then:
      - action: emit
        event: account.quiet
        payload:
          user: { $ref: a.user }
```

The host inserts one `Clock` fact and updates it — `session.update(clock, …)` — whenever it wants the
rules re-evaluated against a later time. The update clears refraction for the rules that read it, so
they get another look; nothing else in the session has to change.

**Which means these rules fire again on every tick they still hold for.** A rule reading the `Clock`
re-fires each time the clock moves, for as long as its condition is true — a one-second tick is a
firing a second per matching account. That is what you want for "act while this is true" and not for
"tell me once": pair it with a `logical: true` conclusion and pattern the *conclusion*, so the tick
maintains a fact and your alerting rule fires on the fact appearing rather than on every tick.

This is the honest shape of "nothing happened for an hour" in an engine that only ever acts when a
fact moves. The engine cannot notice that an hour passed, because nothing arrived to tell it; your
scheduler can, and a `Clock` fact is how it says so. Because the time came in as a fact, a replay of
the same stream — clock ticks included — reproduces the same firings, which is what a wall clock
inside the engine would have cost you.

## Withdrawing a conclusion

A rule that concludes something because a fact was *absent* has a problem the moment that fact turns
up: the conclusion is still sitting there. Add `logical: true` and the engine takes it back.

```yaml
apiVersion: rules.v1
rules:
  - id: unpaid-order
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "PENDING" }
      - fact: Payment
        as: p
        quantifier: notExists
        where:
          orderId: { eq: { $ref: o.id } }
    then:
      - action: insertFact
        fact: OrderUnpaid
        logical: true
        payload: { orderId: { $ref: o.id } }
```

Insert the payment, fire, and `OrderUnpaid` is gone. Retract the payment, fire, and it is back. The
same applies to any reason the match stops holding — a bound fact retracted, an update that breaks a
constraint, a `forAll` counterexample arriving.

Three things to know:

*It happens on the next fire, not instantly.* Right-hand sides are applied as a unit, so nothing is
retracted halfway through one. Your conclusion outlives its reason until the next cycle.

*Two matches concluding the same thing give you two facts.* Each is withdrawn on its own reason.
There is no dedup by payload; if you need one fact, aggregate at ingestion.

*It cascades.* A conclusion drawn from a conclusion goes when the first one does.

*Do not conclude the very thing your `notExists` is about.* This looks like the "do it once" idiom
and is a livelock:

```yaml
# WRONG with logical: true
- fact: Alert
  as: a
  quantifier: notExists
  where: { orderId: { eq: { $ref: o.id } } }
# then: insertFact Alert, logical: true
```

Conclude, the Alert defeats the `notExists`, withdraw, conclude again — forever, until the cycle
limit stops it. Without `logical` the same rule settles after one firing. If you want "alert once
and leave it", that is an ordinary insert.

*Do not evict a type your rules conclude.* Eviction drops the conclusion while its reason still
holds, and the rule is still refracted, so it never comes back. This is the third member of the same
family as "never negate an evicted type" and "never quantify over one".

Leave the key off and nothing changes: the fact stands until something retracts it. That is still
the right choice when the conclusion is a record of something that happened rather than a statement
about how things currently are.

## Doing something

Five actions, and no more. This is on purpose: a rule file stays something a non-programmer can read
in review.

```yaml
then:
  - action: setField                 # change a matched fact
    target: o
    field: status
    value: "REVIEW"

  - action: insertFact               # derive a new fact
    fact: RiskSignal
    as: sig                          # optional; lets later actions here name it
    payload:
      orderId:  { $ref: o.id }
      severity: "HIGH"

  - action: retractFact              # remove a matched fact
    target: sig

  - action: emit                     # tell the outside world
    event: order.flagged
    payload:
      orderId: { $ref: o.id }

  - action: callFunction             # the escape hatch
    name: notifySlack
    args:
      channel: "#risk-review"
```

Two things worth internalising:

**Actions do not see each other's work.** All five are staged and then committed together, so an
action cannot read a field an earlier action just wrote. If you need that, you want two rules.

**`emit` is how you talk to the outside world, and `callFunction` is a last resort.** An emitted
event comes back as the return value of the fire call, so a rule is testable with no mocking at all.
A `callFunction` runs real code at commit time, is not transactional, and if it throws, the changes
that already landed stay landed.

## Checking a list your application owns

Blocklists, allowlists, watchlists. A rule wants to ask "is this card on the blocklist", and the list
belongs to the application: it changes on its own cadence, a rule's own decision may add to it, and
it may live in a store shared by every process running this engine. There is no operator that reaches
out and asks. There is something better placed: **the answer is a fact.**

Look the membership up *before* the session, once per entity the event names, and insert what you
found. Then the rules read it the way they read everything else:

```yaml
apiVersion: rules.v1
rules:
  - id: decline-blocklisted-card
    when:
      - fact: Payment
        as: p
      - fact: ListMembership
        as: m
        where:
          list:     { eq: "card-blocklist" }
          entityId: { eq: { $ref: p.cardId } }
          member:   { eq: true }
    then:
      - action: setField
        target: p
        field: decision
        value: "DECLINE"
      - action: emit
        event: payment.declined
        payload:
          paymentId: { $ref: p.id }
          reason: "card-blocklist"

  - id: blocklist-card-after-third-failure
    when:
      - fact: Payment
        as: p
        where:
          failureCount: { gte: 3 }
      - fact: ListMembership
        as: m
        where:
          list:     { eq: "card-blocklist" }
          entityId: { eq: { $ref: p.cardId } }
          member:   { eq: false }
    then:
      - action: setField            # the first rule sees this in the next cycle of THIS session
        target: m
        field: member
        value: true
      - action: emit                # your application writes this to the shared store afterwards
        event: list.entry.add
        payload:
          list:     { $ref: m.list }
          entityId: { $ref: m.entityId }

  - id: review-when-the-list-could-not-be-checked
    when:
      - fact: Payment
        as: p
      - fact: ListMembership       # no answer at all: the lookup failed, so fail closed
        as: m
        quantifier: notExists
        where:
          list:     { eq: "card-blocklist" }
          entityId: { eq: { $ref: p.cardId } }
    then:
      - action: setField
        target: p
        field: decision
        value: "REVIEW"
```

One `ListMembership` fact per (list, entity) the event names, with `member` true **or false**. That
second half matters: the fact saying "not on the list" is what the second rule matches, and it is also
what lets you tell an outage from a known non-membership. If the lookup fails, insert nothing. With no
fact to bind, neither of the first two rules can fire, so a store that is down declines nobody and
blocklists nobody; the third rule is the one that sees the gap, because `notExists` over the
membership is true exactly when nothing answered. Whether "could not check" means review, decline or
approve is a decision the rule file should state, and that third rule is where it says it.

Three things are going on in that file, and each is a decision.

**Why a fact rather than a callback.** Everything that decides which activation fires assumes that
during one session a match's answer can only change because a fact moved: refraction is cleared for
the rules testing a changed path, the streaming matcher drops a rejected match knowing an update will
bring it back, and truth maintenance re-asks a tuple expecting the same answer. A list consulted live
would change its answer with nothing moving, and every one of those mechanisms would be blind to it.
The same reasoning is why the engine owns no clock and time arrives as a
[`Clock` fact](#as-of-now-and-nothing-has-happened). A fact is constant until you update it, and
updating it is how the change is announced.

**Reading your own write.** The second rule does two things. `setField` flips `member` on the fact,
which is a change to a path the first rule tests, so the first rule gets another look in the same
session and declines the payment in the next cycle. `emit` carries the addition to the outside
world, where your application writes it to the store after `fireAllRules()` returns. The next
evaluation, in this process or any other, looks the card up and finds it. The membership fact is a
snapshot that lives exactly as long as the session, which is why this shape wants one short session
per event: the engine keeps no longer-lived copy, so across a cluster there is nothing to invalidate
and the store is the only durable one. Two writes are involved, the decision and the list, and a
crash between them loses the addition after the decision has been acted on. Record the emitted event
durably before acting on the decision, or accept that loss knowingly.

**What the engine cannot order for you.** Two evaluations for the same card running at the same
time each look the card up before either has written. Both see `member: false`, both decide, both
add. Adding to a set twice is harmless; a list write that is not idempotent is not, and the fix is
outside the engine: route events for one key to one lane, so they run in sequence.

When the list *is* the stream, because entries arrive and expire continuously and the session runs
for days, model each entry as its own fact in a long-lived session and ask with `notExists` instead.
[`embedding.md`](embedding.md#host-owned-lists-and-reference-data) sets the two shapes side by side
and says when each is the right one.

## When operator maps aren't enough

There is an escape hatch, and it is deliberately a little inconvenient to reach: it needs an extra
module and an explicit registration, because it gives up the indexed fast path.

```yaml
apiVersion: rules.v1
rules:
  - id: interesting-order
    noLoop: true                                     # required: see the fourth bullet below
    when:
      - fact: Order
        as: o
        where:
          region: { eq: "US" }                       # keep this: it still narrows the search
        condition: "o.subtotal > 50 && (o.tier in ['A','B'] || o.priorityFlag)"
    then:
      - action: setField
        target: o
        field: band
        value: { $expr: "o.subtotal > 500 ? 'HIGH' : 'LOW'" }
```

Use it for the two things operator maps genuinely cannot say: nested `OR`/`NOT`, and arithmetic
across fields. Keep your indexable constraints in `where` — the condition runs *after* them, once per
surviving candidate, so what `where` removes is work the condition never does.

`$expr` on the right is the cheap one: it runs once per firing. It is also the better answer to
"I need to compute a value" than `callFunction`, which runs at commit time and is not transactional.

Four things will bite you if nobody says them:

- **An absent field is an error here, not a false.** Everything else in this guide treats absence as
  a value; CEL does not. Write `has(o.coupon) && o.coupon != ''`.
- **Comparing a decimal against a whole number works; adding them does not.** `o.subtotal > 50` is
  fine whatever the subtotal is, but `o.subtotal + o.tax` needs `double(o.subtotal) + o.tax` when the
  two are different kinds of number. Do money arithmetic before the fact reaches the engine.
- **A condition that fails to evaluate stops the whole fire cycle** — there is no per-match error
  policy on this side. Guard what you read.
- **You cannot read a clock.** That is on purpose — the engine promises the same facts produce the
  same firings, and a rule that can read the time breaks it. Insert the time as a fact.
- **A condition makes every field of the fact "read", so a rule that writes to its own fact needs
  `noLoop`.** A condition can read anything on the aliases it binds, and the compiler will not try to
  work out which paths — under-declaring by one path loses a firing silently, so it declares the
  whole payload. The consequence is that *any* update to that fact counts as a change the rule cares
  about, including to a field nothing reads. The example above sets `band` on the very order it
  matched, so without `noLoop` it un-refracts itself and fires again. That is why it is there.

See [the reference](dsl-reference.md#the-expression-escape-hatch) for registration and cost limits.

## Running it

```java
ObjectMapper json = new ObjectMapper();

// RuleSource.of(Path) reads the file, so this sits inside something handling IOException.
CompiledRuleSet rules = RuleFiles.compile(RuleSource.of(Path.of("orders.yaml")));

try (RuleSession session = rules.newSession()) {
    session.insert("Order", json.readTree("""
        {"id": 1, "total": 25000, "status": "PENDING", "customerId": 7}"""));
    session.insert("Customer", json.readTree("""
        {"id": 7, "riskTier": "HIGH"}"""));

    FireResult result = session.fireAllRules();
    result.emitted();   // order.flagged, with the payload you declared
}
```

(`insert` takes any Jackson `JsonNode`. In tests, `Facts.json(...)` from `rule-engine-testkit`
is the shorter way to write the same thing — but it is a test fixture, so it does not belong in
code that runs in production.)

Compile once, at startup; the result is immutable and shared. Create a session per unit of work —
they are cheap, and each is single-threaded. One virtual thread per session is the intended shape
for concurrency.

## When it does not fire

This is the question a rule engine actually gets, and a rule that did not fire leaves nothing in a
log to look at. So ask the engine:

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted -- already fired at recency 4
//   o: Order -- 1 considered, 1 matched
//   c: Customer -- 1 considered, 1 matched
```

It re-evaluates your constraints one at a time against working memory — slower than matching, and
the only way to learn *which* constraint eliminated everything, because the fast path is optimised
precisely not to record that.

Three answers cover most cases, and the third is the one nobody guesses:

1. No fact of some type exists at all — usually a fact type spelled differently from how the host
   inserts it.
2. N facts were considered and all failed a named constraint, and it tells you the value that
   failed it.
3. **The rule already fired on those exact facts.** That is refraction, and it is what stops rules
   firing forever.

Before that, check the three traps at the top of this page. A rule that "matches nothing" is very
often an `eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often
a bare `ne`.

**It answers for quantified patterns too**, and names the fact standing in the way — the `Payment`
that defeats a `notExists`, or the `LineItem` that fails a `forAll`. It used to walk only the
patterns that bind facts and report eligible matches for a rule a negation was suppressing, which
was the opposite of the truth; that gap is closed.

**The one thing it cannot see is eviction.** It re-asks the same question of the same working
memory the engine does, so over an evicted type it is fooled identically. What it does is warn: a
rule that matched while a type it quantifies over was being evicted gets the count in its verdict.

## Checking your rules in CI

A rule set is source code. Treat it like source code.

```java
CompiledRuleSet rules = RuleFiles.compile(          // handle the IOException
    List.of(RuleSource.of(Path.of("orders.yaml"))),
    CompilerOptions.builder()
        .declaredFunctions(Set.of("notifySlack"))       // a typo becomes a compile error
        .declaredFactTypes(Set.of("Order", "Customer")) // finds rules nothing can activate
        .build());

CompilerReport report = rules.report();
assertThat(report.unreachableRules()).isEmpty();
assertThat(report.unindexed())
    .filteredOn(c -> c.reason() == RESIDUAL_JOIN_CONDITION)
    .isEmpty();
```

`RuleFiles.compile` throwing is your syntax check, and it reports every problem in every file at
once with a line number.

Add `.factSchemas(...)` to those options and the compiler gets sharper still: a literal that the
field's declared type could never hold stops being a rule that silently never matches and becomes an
error, and the `ne` trap above turns into a warning that names itself.

The report is the part worth wiring into a build. Assert that no join fell to a residual condition
and you will notice the day somebody writes a `ne` join that quietly turns a hash probe into a
linear scan — which is exactly the kind of thing that is invisible in review and obvious in
production.

Two more habits worth having:

- **Test your rules by firing them.** Insert facts, fire, assert on the emitted events. The default
  event sink collects rather than performing I/O, so this needs no mocking.
- **Use a dry run before shipping a change.** `SessionOptions.builder().dryRun(true)` matches and
  resolves conflicts but executes no actions, answering "what *would* fire, in what order, on these
  facts" — which you can diff against the previous rule set's answer.
