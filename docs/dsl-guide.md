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

*A rule that fired because something was absent does not un-fire when it turns up.* There is no
truth maintenance here. If the payment arriving has to undo the conclusion, make the conclusion a
fact — `action: insertFact` with `fact: OrderUnpaid` — and write a second rule that retracts it when
the payment appears.

*Never negate a fact type your session evicts.* Eviction bounds a long-lived session by dropping
facts, and a dropped fact is indistinguishable from one that was never there — so a cap on
`Payment` makes this rule say a paid order is unpaid. Everywhere else in the engine eviction costs
you a firing at worst. Cap the types you bind.

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
