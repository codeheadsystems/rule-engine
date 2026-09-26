# Writing rules

A tutorial for a rule author with a rule to write. It assumes knowledge of the data the rules match
and none of the rule engine. The complete surface, with every operator, every action, and every
error code, is in [`dsl-reference.md`](dsl-reference.md).

Every YAML block here that begins with `apiVersion:` is a fixture in `DocExamplesTest`. Where this
page and the engine disagree, the page is wrong.

## Contents

- [Absence, null, and collections](#absence-null-and-collections)
- [Your first rule](#your-first-rule)
- [Matching two facts together](#matching-two-facts-together)
- [Asking that a fact not exist](#asking-that-a-fact-not-exist)
- [Doing something](#doing-something)
- [Checking a list your application owns](#checking-a-list-your-application-owns)
- [Running it](#running-it)
- [Diagnosing a rule that does not fire](#diagnosing-a-rule-that-does-not-fire)
- [Checking your rules in CI](#checking-your-rules-in-ci)

## Absence, null, and collections

Two of these three decide how the data is modelled, and changing the model after rules are written
against it means rewriting those rules.

### Absent and null

A field that is missing and a field explicitly set to `null` are different values.

```yaml
closedAt: { eq: null }        # matches {"closedAt": null}, NOT {}
closedAt: { hasField: false } # matches {}, NOT {"closedAt": null}
closedAt: { isNull: true }    # matches {"closedAt": null}
```

JSON can express both, so the engine distinguishes both. Collapsing them would mean picking one and
silently mismatching the other.

### `ne` on a missing field

`status: { ne: "CLOSED" }` matches an order with no `status` at all, because `ne` is defined as "not
`eq`", and an absent field is not equal to `"CLOSED"`.

Any other definition would need three-valued logic everywhere. To mean "present, and not closed",
say both:

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

With a schema registered (see [the reference](dsl-reference.md#fact-schemas-optional)), the compiler
finds these: an `ne` against a field the schema calls optional, with no `hasField: true` guarding
it, comes back as an `ne-on-optional-path` warning naming the fix.

### Flattening collections at ingestion

A pattern cannot match inside an array. There is no wildcard: `items.*.qty` does not exist and will
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

Flattening is what gives collection elements indexing and incremental matching at all: the engine
can index `LineItem./orderId` and cannot index "somewhere inside this array". Decide it before
writing rules, because retrofitting it means rewriting every rule that touches a collection.

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

`where` maps a field name to an **operator map**. Everything inside is AND-ed, and so is everything
across fields, and so is everything across patterns. There is no `or`; write two rules, or use `in`.

Field names are dotted: `customer.tier` reads `/customer/tier`.

To put two conditions on one field, put both operators in one map:
`{ hasField: true, ne: "CLOSED" }`. Writing the field name on two lines is a duplicate key, and the
engine rejects it rather than silently keeping only the second.

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

Reference only an alias declared earlier in the same `when`: `c` may name `o` because `o` comes
first. The reverse is an error, which keeps joins acyclic, and reordering the patterns always
resolves it.

A join is not limited to equality. Any comparison that relates two facts works:

```yaml
total: { gt: { $ref: c.creditLimit } }
```

Which pattern comes first is not a performance decision. The engine picks the binding order fresh on
every fire cycle, smallest set first, so the written order is for readability.

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

The negated pattern binds nothing. `p` names the fact being looked for, so that its `where` can be
written; nothing else may use it. A `then` action naming `p` is a compile error, because the rule
matches only when there is no such fact to act on.

Two further properties concern facts that arrive later.

A rule that fired because something was absent does not un-fire when it turns up, unless it
concludes logically. Add `logical: true` to the `insertFact` and the conclusion is withdrawn when
the payment arrives, and drawn again if the payment is retracted; [Withdrawing a
conclusion](#withdrawing-a-conclusion) covers it. Without the key the fact stands, which is the
default.

Never negate a fact type the session evicts. Eviction bounds a long-lived session by dropping facts,
and a dropped fact is indistinguishable from one that was never there, so a cap on `Payment` makes
this rule say a paid order is unpaid. Everywhere else in the engine eviction costs a firing at
worst. Cap the types the rule binds.

## Asking that every fact meet a requirement

`quantifier: forAll` is the other half. It asserts that every fact in scope satisfies a requirement,
and the join is what picks the scope:

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

Read it as two halves. `orderId: { eq: { $ref: o.id } }` is the join, and it says which line items
this is about: the ones belonging to this order. Everything else is what must be true of them. A
line item on somebody else's order being out of stock does not stop this rule firing, because the
rule never claimed anything about it.

Only the join picks the scope. Anything with a literal value is part of the requirement, so there is
no way to say "every physical line item": a `type: { eq: "PHYSICAL" }` line would make the digital
items counterexamples, and the rule would quietly never fire. For a narrower scope, split the fact
type at ingestion.

A `notExists` covers a single-constraint requirement: "every order is shipped" is `notExists` an
order with `status: { ne: "SHIPPED" }`. It cannot cover two, because the opposite of "in stock and
qty above zero" is a disjunction, and a `where` block has no `or`.

An empty scope makes a `forAll` true. The rule above fires for an order with no line items at all,
because there is nothing to fail the requirement. That is how "for all" works everywhere. To mean
"there are some, and all of them", say that there is at least one:

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

Count them rather than binding one. A plain positive pattern says the same thing at a cost in
cardinality: it binds a line item, so a three-item order produces three matches and fires the rule
three times, once per item. An `accumulate` binds a number, so there is one match per order however
many items it has. Write the plain pattern only when you want the per-item firing.

What follows is about the `forAll` pattern, not the `accumulate` companion. A fold does bind a name,
readable from `then`, from a `condition:`, and from its own `having`, which is the one thing the
three quantifiers do not share.

Everything the negation section says otherwise applies to a `forAll` as well: the pattern binds
nothing, no `then` action may name its alias, a firing is not undone when a counterexample arrives
unless it concluded logically (see [Withdrawing a conclusion](#withdrawing-a-conclusion)), and a
`forAll` must not range over a type the session evicts. The last is sharper here: eviction only ever
removes counterexamples, so a cap makes the requirement easier to satisfy, and a cap that empties
the scope deletes it.

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

The engine has no clock. Every time it uses is a field on a fact the host inserted, so replaying the
same facts gives the same firings, today or next year, on a laptop or in CI. It also means the
engine cannot on its own notice "nothing has happened for an hour": no fact arriving is the one
thing it never hears about.

`within` is in the units of the time field. `86400000` is a day only because `placedAt` holds epoch
milliseconds; if it held seconds, a day would be `86400`. Nothing checks this, so where there is any
chance of confusion, put the unit in the field name.

The bound is required and must be more than zero. For plain "later than", write
`gt: { $ref: o.placedAt }`; `after` exists for the bounded case, which `gt` cannot express. A bound
of `0` would match nothing at all, so it is refused at compile time.

## Adding things up

`quantifier: accumulate` folds a set of facts into one value and hands it to the rule.

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

Read it in three parts. The `where` says which line items: this order's. The `sum` says what to do
with them. The `having` says the rule fires only when the answer clears 100. `units` is then a name
usable in `then`, written as a bare `$ref: units` because it is a number, not a fact.

There are five functions: `sum`, `count`, `min`, `max`, and `average`. `count: true` takes no field;
the rest name one. Each block takes one.

An empty set is not zero for every function. `count` and `sum` of nothing are `0`. `min`, `max`, and
`average` of nothing are absent, so a `having` on them does not hold. The average of no orders is
not zero, and treating it as zero would make "average under 10" true for a customer who has never
bought anything.

A missing field is skipped, not treated as zero. The average of three line items where one has no
price is the average of two. To exclude the ones without a price from the count as well, say so in
the `where` with `hasField`.

Nothing can join to it. `$ref: units` works in `then` and in a `condition:`. It does not work in
another pattern's `where`, because a join matches two facts and `units` is a number.

The eviction warning for the other quantifiers applies here too, for a worse reason: a cap on
`LineItem` makes the totals quietly wrong rather than making the rule go quiet.

## Counting things in a window

The last two sections compose into the velocity rule: how many of these, for one subject, inside a
window. Five failed logins for one user in ten minutes:

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
selects the failures in the ten minutes leading up to it. No clock is involved anywhere: the window
is measured between two facts, exactly as in [Putting two facts in
order](#putting-two-facts-in-order).

The threshold is `gte: 4` for five failures. Two rules combine to make the count exclude the
trigger: `before` is strict on the near side, and an `accumulate` over a type the rule already binds
is about the other facts of that type. So the count is the failures preceding the trigger, and the
trigger is the fifth. Write the intended number minus one; a rule written with the intended number
fires one event late.

A window in the rule does not bound memory. The rule above matches ten minutes; the session still
holds every `LoginFailure` ever inserted, and the accumulate walks all of them on every candidate.
For a long-lived session, the host pairs the rule with a retention window:

```java
SessionOptions.builder()
    .eviction(EvictionPolicy.window("LoginFailure", "at", 600_000))
    .build();
```

That policy drops facts older than the newest `at` this type currently holds, minus the span. The
watermark is taken from the data, never a clock, so replaying the same stream evicts the same facts.
A failure that arrives already older than the watermark is dropped on arrival; that is the cost of a
window with no clock behind it. Two constraints apply, both covered in
[`embedding.md`](embedding.md#long-lived-sessions-and-eviction). Retention must be at least as wide
as the widest window any rule writes against that type. A `notExists` over a windowed type changes
meaning: it stops saying "never" and starts saying "not lately".

If the rule concludes with `logical: true`, the conclusion is withdrawn when its failures age out of
the retention window, because eviction is an ordinary retract and [truth
maintenance](#withdrawing-a-conclusion) re-asks the match. The result is a lock that expires by
itself, with no clock in the engine.

### The `Clock` fact

An anchored window needs a fact to anchor it. For a window that ends at the present rather than at
an arriving fact, such as "this account has had no failure in the last ten minutes", insert the
clock as a fact and have the application advance it:

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

The host inserts one `Clock` fact and updates it, with `session.update(clock, …)`, whenever it wants
the rules re-evaluated against a later time. The update clears refraction for the rules that read
it, so they get another look; nothing else in the session has to change.

These rules therefore fire again on every tick for which they still hold. A rule reading the `Clock`
re-fires each time the clock moves, for as long as its condition is true: a one-second tick is one
firing a second per matching account. That suits "act while this is true" and not "tell me once".
For the second, pair the rule with a `logical: true` conclusion and pattern the conclusion, so the
tick maintains a fact and the alerting rule fires on the fact appearing rather than on every tick.

This is how "nothing happened for an hour" is expressed in an engine that only ever acts when a fact
moves. The engine cannot notice that an hour passed, because nothing arrived to tell it; the host's
scheduler can, and a `Clock` fact is how it says so. Because the time arrives as a fact, a replay of
the same stream, clock ticks included, reproduces the same firings. A wall clock inside the engine
would lose that.

## Withdrawing a conclusion

A rule that concludes something because a fact was absent leaves the conclusion standing when that
fact turns up. Add `logical: true` and the engine takes it back.

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
same applies to any reason the match stops holding: a bound fact retracted, an update that breaks a
constraint, or a `forAll` counterexample arriving.

Withdrawal happens on the next fire, not instantly. Right-hand sides are applied as a unit, so
nothing is retracted halfway through one, and a conclusion outlives its reason until the next cycle.

Two matches concluding the same thing produce two facts, each withdrawn on its own reason. There is
no deduplication by payload; for one fact, aggregate at ingestion.

Withdrawal cascades: a conclusion drawn from a conclusion goes when the first one does.

Do not conclude the very thing the rule's `notExists` is about. It looks like the "do it once" idiom
and is a livelock:

```yaml
# WRONG with logical: true
- fact: Alert
  as: a
  quantifier: notExists
  where: { orderId: { eq: { $ref: o.id } } }
# then: insertFact Alert, logical: true
```

The rule concludes, the `Alert` defeats the `notExists`, the conclusion is withdrawn, and the rule
concludes again, repeating until the cycle limit stops it. Without `logical` the same rule settles
after one firing. "Alert once and leave it" is an ordinary insert.

Do not evict a type the rules conclude. Eviction drops the conclusion while its reason still holds,
and the rule is still refracted, so it never comes back. This is the third member of the same family
as "never negate an evicted type" and "never quantify over one".

Leave the key off and the fact stands until something retracts it. That is the right choice when the
conclusion is a record of something that happened rather than a statement about how things
currently are.

## Doing something

There are five actions. The set is closed so that a rule file stays readable in review by a
non-programmer.

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

Actions do not see each other's work. All of a rule's actions are staged and then committed
together, so an action cannot read a field an earlier action just wrote. That needs two rules.

`emit` is how a rule talks to the outside world, and `callFunction` is a last resort. An emitted
event comes back as the return value of the fire call, so a rule is testable with no mocking at all.
A `callFunction` runs real code at commit time, is not transactional, and if it throws, the changes
that already landed stay landed.

## Checking a list your application owns

Blocklists, allowlists, watchlists. A rule wants to ask "is this card on the blocklist", and the
list belongs to the application: it changes on its own cadence, a rule's own decision may add to
it, and it may live in a store shared by every process running the engine. No operator reaches out
and asks. The answer is a fact.

Look the membership up before the session, once per entity the event names, and insert what you
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

Insert one `ListMembership` fact per (list, entity) the event names, with `member` true or false.
The fact saying "not on the list" is what the second rule matches, and it is also what distinguishes
an outage from a known non-membership. If the lookup fails, insert nothing. With no fact to bind,
neither of the first two rules can fire, so a store that is down declines nobody and blocklists
nobody; the third rule is the one that sees the gap, because `notExists` over the membership is true
exactly when nothing answered. Whether "could not check" means review, decline, or approve is a
decision for the rule file to state, and the third rule is where it states it.

The file makes three decisions.

The membership is a fact rather than a callback because everything that decides which activation
fires assumes that, during one session, a match's answer can only change because a fact moved:
refraction is cleared for the rules testing a changed path, the streaming matcher drops a rejected
match knowing an update will bring it back, and truth maintenance re-asks a tuple expecting the same
answer. A list consulted live would change its answer with nothing moving, and every one of those
mechanisms would be blind to it. The same reasoning is why the engine owns no clock and time arrives
as a [`Clock` fact](#the-clock-fact). A fact is constant until it is updated, and updating it is how
the change is announced.

The second rule reads its own write. It does two things. `setField` flips `member` on the fact,
which is a change to a path the first rule tests, so the first rule gets another look in the same
session and declines the payment in the next cycle. `emit` carries the addition to the outside
world, where the application writes it to the store after `fireAllRules()` returns. The next
evaluation, in this process or any other, looks the card up and finds it. The membership fact is a
snapshot that lives exactly as long as the session, which is why this shape wants one short session
per event: the engine keeps no longer-lived copy, so across a cluster there is nothing to invalidate
and the store is the only durable one. Two writes are involved, the decision and the list, and a
crash between them loses the addition after the decision has been acted on. Record the emitted event
durably before acting on the decision, or accept that loss knowingly.

The engine cannot order concurrent evaluations. Two evaluations for the same card running at the
same time each look the card up before either has written. Both see `member: false`, both decide,
and both add. Adding to a set twice is harmless; a list write that is not idempotent is not, and the
fix is outside the engine: route events for one key to one lane, so they run in sequence.

When the list is itself the stream, because entries arrive and expire continuously and the session
runs for days, model each entry as its own fact in a long-lived session and ask with `notExists`
instead. [`embedding.md`](embedding.md#host-owned-lists-and-reference-data) sets the two shapes side
by side and says when each is the right one.

## The expression escape hatch

The escape hatch needs an extra module and an explicit registration, because it gives up the
indexed fast path.

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

Use it for the two things operator maps cannot say: nested `OR`/`NOT`, and arithmetic across
fields. Keep indexable constraints in `where`: the condition runs after them, once per surviving
candidate, so what `where` removes is work the condition never does.

`$expr` on the right is the cheap one: it runs once per firing. It is also a better way to compute a
value than `callFunction`, which runs at commit time and is not transactional.

Five properties of the escape hatch:

- An absent field is an error here, not a false. Everything else in this guide treats absence as a
  value; CEL does not. Write `has(o.coupon) && o.coupon != ''`.
- Comparing a decimal against a whole number works; adding them does not. `o.subtotal > 50` is
  fine whatever the subtotal is, but `o.subtotal + o.tax` needs `double(o.subtotal) + o.tax` when
  the two are different kinds of number. Do money arithmetic before the fact reaches the engine.
- A condition that fails to evaluate stops the whole fire cycle; there is no per-match error policy
  on this side. Guard what the condition reads.
- An expression cannot read a clock. The engine promises that the same facts produce the same
  firings, and a rule that could read the time would break that. Insert the time as a fact.
- A condition makes every field of the fact read, so a rule that writes to its own fact needs
  `noLoop`. A condition can read anything on the aliases it binds, and the compiler does not try to
  work out which paths; under-declaring by one path loses a firing silently, so it declares the
  whole payload. As a result any update to that fact counts as a change the rule cares about,
  including one to a field nothing reads. The example above sets `band` on the very order it
  matched, so without `noLoop` it un-refracts itself and fires again; that is what its
  `noLoop: true` is for.

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

(`insert` takes any Jackson `JsonNode`. In tests, `Facts.json(...)` from `rule-engine-testkit` is a
shorter way to write the same thing; it is a test fixture, so it does not belong in code that runs
in production.)

Compile once, at startup; the result is immutable and shared. Create a session per unit of work;
sessions are cheap, and each is single-threaded. One virtual thread per session is the intended
shape for concurrency.

## Diagnosing a rule that does not fire

A rule that did not fire leaves nothing in a log to look at. Ask the engine instead:

```java
Explanation why = new MatchExplainer(rules, session).explain("high-value-order-review");
System.out.println(why.describe());

// rule high-value-order-review: matched, but refracted -- already fired at recency 4
//   o: Order -- 1 considered, 1 matched
//   c: Customer -- 1 considered, 1 matched
```

`MatchExplainer` re-evaluates the rule's constraints one at a time against working memory. That is
slower than matching, and it is the only way to learn which constraint eliminated everything,
because the fast path is optimised not to record that.

Three answers cover most cases:

1. No fact of some type exists at all, usually because a fact type is spelled differently from how
   the host inserts it.
2. N facts were considered and all failed a named constraint, and the explanation gives the value
   that failed it.
3. The rule already fired on those exact facts. That is refraction, and it is what stops rules
   firing forever.

Before that, check the three traps in [Absence, null, and
collections](#absence-null-and-collections). A rule that "matches nothing" is very often an
`eq: null` that meant `hasField: false`, and a rule that "matches everything" is very often a bare
`ne`.

The explainer answers for quantified patterns too, and names the fact standing in the way: the
`Payment` that defeats a `notExists`, or the `LineItem` that fails a `forAll`.

The one thing it cannot see is eviction. It re-asks the same question of the same working memory
the engine does, so over an evicted type it is fooled identically. What it does is warn: a rule that
matched while a type it quantifies over was being evicted gets the count in its verdict.

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

A throw from `RuleFiles.compile` is the syntax check, and it reports every problem in every file at
once, with a line number.

Add `.factSchemas(...)` to those options and the compiler checks more: a literal that the field's
declared type could never hold becomes an error instead of a rule that silently never matches, and
the `ne` trap above becomes a warning that names itself.

Wire the report into the build. An assertion that no join fell to a residual condition catches the
day somebody writes an `ne` join that turns a hash probe into a linear scan, a change that is
invisible in review and obvious in production.

Two further practices:

- Test rules by firing them: insert facts, fire, and assert on the emitted events. The default event
  sink collects rather than performing I/O, so this needs no mocking.
- Use a dry run before shipping a change. `SessionOptions.builder().dryRun(true)` matches and
  resolves conflicts but executes no actions, answering "what would fire, in what order, on these
  facts"; that answer can be diffed against the previous rule set's.
