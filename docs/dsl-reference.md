# Rule file reference (`rules.v1`)

The complete, normative surface of the JSON/YAML rule DSL. A walkthrough that starts from a blank
file is in [`dsl-guide.md`](dsl-guide.md).

The specification is authoritative over this page where the two disagree:
[`rule-engine-spec.md`](rule-engine-spec.md) §6 specifies the DSL, and §2.6.1 specifies what every
comparison means against absent, null, and wrong-typed values. This page gives the syntax and
refers to §2.6.1 for the semantics.

Every YAML block below that begins with `apiVersion:` is a fixture in `DocExamplesTest`. Where this
page and the engine disagree, the page is wrong.

## Contents

- [The rule file](#the-rule-file)
- [A rule](#a-rule)
- [`when`: patterns and operator maps](#when-patterns-and-operator-maps)
  - [Negation: `quantifier: notExists`](#negation-quantifier-notexists)
  - [Universals: `quantifier: forAll`](#universals-quantifier-forall)
  - [Aggregates: `quantifier: accumulate`](#aggregates-quantifier-accumulate)
- [The operator table](#the-operator-table)
- [`$ref` and the `$$` escape](#ref-and-the--escape)
- [`then`: the five actions](#then-the-five-actions)
- [Diagnostics](#diagnostics)
- [The compiler report](#the-compiler-report)
- [Not implemented yet](#not-implemented-yet)

## The rule file

A rule file is an object with two keys. Both are required.

```yaml
apiVersion: rules.v1
rules:
  - id: minimal
    when:
      - fact: Order
        as: o
    then:
      - action: emit
        event: saw-an-order
```

`apiVersion` must be exactly `rules.v1`. A file naming any other version is rejected outright
rather than parsed on a best-effort basis, so a later schema change cannot silently reinterpret
files written against an older one.

`rules` must hold at least one rule. A file that loads zero rules is almost always an editing
accident, so it fails rather than letting a service start with no rules and no message.

A rule set is the union of many files. `RuleFiles.compile(...)` takes any number of them, and `id`
must be unique across all of them, which is only checkable when they are compiled together.

```java
CompiledRuleSet rules = RuleFiles.compile(          // handle the IOException
    RuleSource.of(Path.of("orders.yaml")),
    RuleSource.of(Path.of("fraud.yaml")));
```

`RuleSource.of(Path)` picks the format from the extension: `.json`, `.yaml`, or `.yml`. It reads the
file, so it declares `IOException`; an extension it does not recognise is an
`IllegalArgumentException`, because that is a caller error rather than a file that would not open.
`RuleSource.yaml(name, text)` and `RuleSource.json(name, text)` take text that does not come from a
file; they throw neither, and the name is used only to report diagnostics against.

JSON and YAML are the same language here. They parse into one object model and compile to one rule
set; the only difference is which Jackson factory reads the text. Everything on this page applies to
both, and the examples are in YAML for readability.

## A rule

```yaml
apiVersion: rules.v1
rules:
  - id: every-key                   # required, unique across every file
    salience: 10                    # default 0; higher fires first
    noLoop: true                    # default false
    agendaGroup: review             # optional; recorded and ignored in v1
    tags: [fraud, manual-review]    # optional, free-form
    when:                           # required, at least one pattern
      - fact: Order
        as: o
    then:                           # required, at least one action
      - action: emit
        event: e
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | required | Unique across every file in the rule set. Appears in traces, in `FireResult`, and in every diagnostic |
| `salience` | integer | `0` | Conflict-resolution priority; higher fires first (§4.2) |
| `noLoop` | boolean | `false` | Suppresses re-activation of this same match caused by this rule's own RHS (§4.5). One level deep; it is not a loop guard |
| `agendaGroup` | string | unset | §4.5 defers agenda grouping to v2. v1 records the value and ignores it |
| `tags` | list of string | `[]` | Free-form labels, for filtering and reporting |
| `when` | list of patterns | required | The LHS. Patterns are implicitly AND-ed |
| `then` | list of actions | required | The RHS, executed in declaration order |

## `when`: patterns and operator maps

A pattern names a fact type, binds it to an alias, and optionally constrains it.

```yaml
apiVersion: rules.v1
rules:
  - id: pattern-shape
    when:
      - fact: Order            # required: the fact type
        as: o                  # required: the alias, unique within this rule
        quantifier: exists     # 'exists' (default), 'notExists', 'forAll', 'accumulate'
        where:                 # optional: field name -> operator map
          total:  { gt: 10000 }
          status: { eq: "PENDING" }
    then:
      - action: emit
        event: e
```

Constraints combine by implicit AND at three levels, and all three behave the same:

1. Across patterns in `when`: every pattern must match.
2. Across fields in one `where`: every field's constraints must hold.
3. Across operators in one field's map: `{ gt: 100, lt: 500 }` requires both.

There is no `or`. §6.3 explains the trade: operator maps keep the indexable path the default, and a
free-form boolean expression generally cannot be decomposed into an index plan. Alternation takes
either two rules or an `in`.

Field names are dotted paths. `customer.id` reads `/customer/id` from the payload. Array indices
work (`items.0.qty`); wildcards do not exist, which is why collections are flattened at ingestion
(see [the guide](dsl-guide.md#flattening-collections-at-ingestion)).

Distinct aliases bind distinct facts. `Order as o1` and `Order as o2` in one rule find two different
orders; the compiler inserts the inequality. This differs from OPS5.

### Negation: `quantifier: notExists`

A pattern with `quantifier: notExists` asserts that no such fact exists, as in "no `Payment` for
this `Order`":

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
        quantifier: notExists          # no Payment may satisfy the where below
        where:
          orderId: { eq: { $ref: o.id } }
    then:
      - action: emit
        event: order.unpaid
        payload: { orderId: { $ref: o.id } }
```

`quantifier` takes `exists` (the default, and what every pattern without the key means),
`notExists`, [`forAll`](#universals-quantifier-forall), or
[`accumulate`](#aggregates-quantifier-accumulate). `collect` is a §1 deferral and is rejected by the
schema; the interim answer is to gather at ingestion and insert the result as a fact.

A negated pattern binds nothing. Its alias exists so that its own `where` can be written, and
nothing else in the rule may name it: not a `$ref` from another pattern, not a `then` action, and
not an `insertFact` looking for a name to bind its new fact to. All three are compile errors, and
each names the reason rather than claiming the alias does not exist.

A negated pattern may join to any alias the rule binds, in either direction of declaration: the
negated pattern above could equally be written before the `Order` it references. It cannot stand
alone; a rule needs at least one pattern that binds a fact, so a rule that is nothing but negations
is rejected.

Negating a type the rule already binds means "no other one". `Order as o` plus a negated
`Order as other` asks whether a different order exists, by the same implicit inequality that
governs two positive aliases. A fact is never its own counterexample.

A `condition:` on a negated pattern is refused. It would compile and never run, because the §6.4
post-filter walks the patterns that bind, so the negation would silently be broader than written.
The pattern's own `where` is what decides whether the fact exists, and is where such a test goes.

Without [`logical: true`](#conclusions-logical-true), a negation has no truth maintenance: a rule
that fired because something was absent is not undone when that thing arrives. The absence ending
makes the match ineligible from then on, which is not the same as retracting what it did. A logical
insert is the supported way to get the retraction; the alternative is to model the conclusion as a
fact and retract it from a second rule.

A negation must never range over a fact type the session evicts. An evicted fact and an absent fact
are indistinguishable to a negation, so a cap on `Payment` makes the rule above announce that a paid
order is unpaid. Everywhere else eviction can only cost a firing; here it manufactures a false
conclusion. A cap belongs on the types a rule binds, not the ones whose absence it asserts; see
[`SessionOptions.eviction`](embedding.md#long-lived-sessions-and-eviction).

### Universals: `quantifier: forAll`

A pattern with `quantifier: forAll` asserts that every fact **in scope** meets a requirement. The
join constraints choose the scope; the pattern's own constraints are what is asserted about it.

```yaml
apiVersion: rules.v1
rules:
  - id: ready-to-ship
    when:
      - fact: Order
        as: o
        where:
          status: { eq: "PENDING" }
      - fact: LineItem
        as: li
        quantifier: forAll             # every LineItem OF THIS ORDER ...
        where:
          orderId: { eq: { $ref: o.id } }   # <- the join picks the scope
          inStock: { eq: true }             # <- the rest is the requirement
          qty: { gt: 0 }
    then:
      - action: emit
        event: order.ready
        payload: { orderId: { $ref: o.id } }
```

The join defines the scope and is not part of the requirement. Without that split, the pattern
above would assert that every `LineItem` anywhere belongs to this order and is in stock, which is
false the moment a second order exists, so the rule could never fire. With it, a line item belonging
to a different order is outside what this rule speaks about.

Only the join narrows the scope. Everything with a literal value is part of the requirement, so
"every physical line item of this order is in stock" is not expressible: `type: { eq: "PHYSICAL" }`
makes a digital item a counterexample instead of excluding it, and the rule quietly never fires for
an order containing one. A scope narrower than a join expresses takes a fact type of its own:
`PhysicalLineItem` and `DigitalLineItem` are separately quantifiable, and §1's flattening advice
already points that way.

With no join, a `forAll` is a global assertion: `forAll` over `Order` with
`status: { eq: "SHIPPED" }` and nothing else says every `Order` in the session is shipped.

A `forAll` is needed for multi-constraint requirements. A single constraint is already writable as
a negation of its complement: "every `Order` is shipped" is `notExists` an `Order` with
`status: { ne: "SHIPPED" }`. Two constraints are not: the complement of "in stock and qty above
zero" is a disjunction, and no `where` block expresses one.

A `forAll` is vacuously true over an empty scope. The rule above fires for an order with no line
items, because there is nothing to fail the requirement. This is classical logic, and it is the trap
in `forAll`. A positive pattern of the same type says "there are some, and all of them":

```yaml
      - fact: LineItem
        as: some
        where:
          orderId: { eq: { $ref: o.id } }
      - fact: LineItem
        as: li
        quantifier: forAll
        where:
          orderId: { eq: { $ref: o.id } }
          inStock: { eq: true }
```

Everything the negation section says about binding also applies. A `forAll` pattern binds nothing,
nothing may name its alias, it may join in either direction of declaration, it cannot stand alone,
a `condition:` on it is refused, and over a type the rule already binds it means "every other one".
A rule that fired because everything complied is not undone when a counterexample arrives, unless
it concluded with [`logical: true`](#conclusions-logical-true).

A `forAll` must never range over a type the session evicts, and the hazard is sharper than in the
negation case. Evicting facts can only remove counterexamples, so a cap does not weaken the
requirement; it strengthens it, and a cap that empties the scope makes the assertion vacuously true,
which deletes it altogether.

### Time: `after` and `before`

`after` and `before` relate two facts in sequence, within a bound. The operand is always a `$ref`
carrying a `within`: the relation is between two facts, and the bound is required.

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

`after` holds when `other < mine <= other + within`; `before` when `other - within <= mine < other`.
Both are strict on the near side, so two facts with the same timestamp are not "after" each other,
and inclusive on the far side, so "within 24 hours" includes the twenty-fourth hour.

`within` is in the field's own units. The engine does not know whether `placedAt` is epoch
milliseconds, epoch seconds, or a sequence number, and does not guess; `86400000` above is a day
only because that field holds milliseconds. Wrong units make the rule silently wrong, so where there
is any doubt the field name carries the unit: `placedAtMillis`.

The engine reads no clock. Every time it uses comes from a fact, so replaying the same facts gives
the same firings on any machine, in any year. That is [§7.3's determinism
contract](rule-engine-spec.md#73-the-determinism-contract), and a wall clock would end it.

The bound is required and must be positive. An unbounded ordering is already `gt` or `lt` against
the same `$ref`, and a bound of `0` is empty by construction: the near edge is strict, so
`after within 0` is `other < mine <= other`, which nothing satisfies. What `after` adds is the
bounded form, which no pair of comparisons can express.

`after` and `before` are never index-eligible. The bound travels with the constraint, and the engine
does not reverse the relation to probe from the far end, because a reversal that lost the bound
would silently widen the rule. `CompilerReport` names them like any other unindexable constraint.

There is no sliding-window operator. With no engine-owned clock, nothing notices time passing with
no fact arriving; the engine only ever acts when a fact moves. A window is instead two separate
decisions, which have to agree: a bounded temporal join for what a rule matches, and a [retention
window](embedding.md#long-lived-sessions-and-eviction) for what the session keeps. A window that
ends at "now" rather than at an arriving fact takes a clock inserted as a fact, which the host
advances.
Both recipes, including the velocity count they usually serve, are in [the
guide](dsl-guide.md#counting-things-in-a-window).

### Aggregates: `quantifier: accumulate`

A pattern with `quantifier: accumulate` folds its scope into a value and binds it. Every constraint
selects the scope; the `accumulate` block says what to compute and, optionally, what to require of
the answer.

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
          sum: "qty"                        # one of sum, count, min, max, average
          having: { gt: 100 }               # optional test on the answer
        where:
          orderId: { eq: { $ref: o.id } }   # the scope
    then:
      - action: emit
        event: order.bulk
        payload:
          orderId: { $ref: o.id }
          units:   { $ref: units }          # a bare alias: the answer, not a field
```

The alias binds a value, not a fact. A bare `$ref: units` reads it; there is no field, because there
is no fact. An action, a `condition:`, and the `accumulate` block's own `having` may read it. A join
from another pattern may not, because a join compares two facts.

`having` takes only a literal. A `$ref` inside it is a compile error, for the same reason: a join
needs a fact on both sides and an accumulate has none. Comparing the answer against a field on
another fact, such as an account's spend against that account's own limit (the archetypal aggregate
rule), takes a [`condition:`](#the-expression-escape-hatch) on a pattern that binds a fact:

```yaml
apiVersion: rules.v1
rules:
  - id: over-daily-limit
    when:
      - fact: Account
        as: acct
        condition: "total > acct.dailyLimitCents"   # reads `total`, declared below
      - fact: Transaction
        as: total
        quantifier: accumulate
        accumulate: { sum: "amountCents" }
        where: { accountId: { eq: { $ref: acct.id } } }
    then:
      - action: emit
        event: account.overLimit
        payload: { accountId: { $ref: acct.id }, totalCents: { $ref: total } }
```

Two details of that rule matter. The condition goes on the other pattern; a condition on the
accumulate itself is refused, as it is on `notExists` and `forAll`. It also names an alias declared
below it, which a `$ref` may not do: a condition is a post-filter over a complete match rather than
part of the join, so the earlier-alias rule does not apply to it.

The value is recomputed every time it is read, never stored, so it cannot go stale. The cost is that
reading it twice folds it twice.

Every constraint filters the scope, `$ref` joins and literals alike, so `kind: { eq: "PHYSICAL" }`
restricts what is summed. This is where `accumulate` differs from `forAll`, whose literals state a
requirement instead.

There are five functions. `count: true` takes no field and counts the facts in scope. `sum`, `min`,
`max`, and `average` each name a dotted field. A block takes exactly one. `collect` is not
implemented.

A fact whose field is missing or non-numeric is skipped, not folded as zero, which matches how the
rest of the engine treats absence. So an `average` over a scope where some facts lack the field is
the average of the ones that have it.

An empty scope answers differently per function:

| Function | Empty scope |
|---|---|
| `count` | `0` |
| `sum` | `0` |
| `min`, `max`, `average` | absent; a `having` on it does not hold |

The mean of nothing is not zero, and treating it as zero would make `average: { lt: 10 }` true for
an order with no line items.

Over a type the rule already binds, an `accumulate` means "the others". `Order as o` plus an
`accumulate` over `Order` totals every other matching order, by the same implicit inequality that
governs two positive aliases.

An `accumulate` must never range over a type the session evicts (§4.4). Eviction changes the number
rather than costing a firing, and a total that is quietly short by whatever aged out is harder to
notice than a rule that quietly stops firing.

### Two operators on one field

Both of these are legal and mean the same thing:

```yaml
apiVersion: rules.v1
rules:
  - id: two-ways-to-bound
    when:
      - fact: Order
        as: o
        where:
          total: { gt: 100, lt: 500 }        # two one-sided ranges, AND-ed
      - fact: Order
        as: p
        where:
          total: { between: { from: 100, to: 500, fromInclusive: false, toInclusive: false } }
    then:
      - action: emit
        event: e
```

`between` is the preferred form for a two-sided bound. It compiles to a single constraint where the
first form compiles to two, and it can state inclusivity, which `gt`/`lt` can only do by choosing a
different operator.

Both operators go in one map. Writing the field name twice is a duplicate mapping key, and it is
rejected:

```yaml
where:
  status: { hasField: true, ne: "CLOSED" }   # correct
  # status: { hasField: true }               # NOT this --
  # status: { ne: "CLOSED" }                 # -- a repeated key is an error
```

Both JSON and YAML would otherwise accept a repeated key and silently keep the last one, which gives
neither an AND nor an error: a rule matching everything the discarded condition would have
excluded, in a file that reads exactly as intended. The engine turns a repeated key into a
`malformed-document` error naming the key.

## The operator table

The complete set of operators. Semantics against absent, null, and wrong-typed values are in
§2.6.1's table, which is normative; this table gives the syntax.

| Key | Example | Compiles to | As a join |
|---|---|---|---|
| `eq` | `status: { eq: "PENDING" }` | `FieldConstraint(EQ)` | hash index |
| `ne` | `status: { ne: "CLOSED" }` | `FieldConstraint(NE)` | no; an anti-match cannot be narrowed |
| `gt` | `total: { gt: 10000 }` | `RangeConstraint`, one-sided exclusive | sorted index |
| `gte` | `total: { gte: 10000 }` | `RangeConstraint`, one-sided inclusive | sorted index |
| `lt` | `total: { lt: 500 }` | `RangeConstraint`, one-sided exclusive | sorted index |
| `lte` | `total: { lte: 500 }` | `RangeConstraint`, one-sided inclusive | sorted index |
| `between` | `total: { between: { from: 100, to: 500 } }` | `RangeConstraint`, two-sided | sorted index |
| `in` | `riskTier: { in: ["HIGH", "MEDIUM"] }` | `FieldConstraint(IN)` | n/a: takes no `$ref` |
| `notIn` | `region: { notIn: ["XX"] }` | `FieldConstraint(NOT_IN)` | n/a: takes no `$ref` |
| `matches` | `email: { matches: "^[a-z]+@example\\.com$" }` | `FieldConstraint(MATCHES)` | n/a: takes no `$ref` |
| `hasField` | `couponCode: { hasField: false }` | `FieldConstraint(HAS_FIELD)` | n/a: takes no `$ref` |
| `isNull` | `closedAt: { isNull: true }` | `FieldConstraint(IS_NULL)` | n/a: takes no `$ref` |
| `after` | `paidAt: { after: { $ref: o.placedAt, within: 86400000 } }` | `JoinConstraint(AFTER)` | never (see below) |
| `before` | `at: { before: { $ref: p.paidAt, within: 86400000 } }` | `JoinConstraint(BEFORE)` | never (see below) |

DSL keys are camelCase; the `Operator` constants they compile to are SCREAMING_SNAKE. `notIn` is
`NOT_IN`, and `hasField` is `HAS_FIELD`.

The last column describes joins only. In v1 an index is built for a path because a join probes it,
never because a single-fact constraint reads it. There would be nothing for a single-fact index to
do: a pattern's memory already holds exactly the facts that passed its constraints, so
`status: { eq: "PENDING" }` has been applied once, when the fact was inserted, and an index over it
would be a structure with one bucket and no reader.

So a single-fact `eq` is not hash indexed and does not need to be. The column gives what happens
when the same operator is written against a `$ref`: an `eq` join gets a hash probe, an ordered join
gets a sorted one, and an `ne` join gets neither and falls to a linear post-filter, which the
compiler report calls `RESIDUAL_JOIN_CONDITION` and which is the one to avoid.

### `between`

```yaml
total:
  between:
    from: 100            # optional
    to: 500              # optional
    fromInclusive: true  # default true
    toInclusive: false   # default true
```

Bounds are named rather than positional because a two-element array cannot express inclusivity
without a convention that then has to be documented, validated, and remembered.

`from` and `to` are individually optional, but at least one must be present; a `between` bounding
nothing is an `empty-range` error. A one-sided `between` compiles to exactly the same constraint as
the short form, so `{ between: { from: 100 } }` and `{ gte: 100 }` are indistinguishable downstream,
down to the rule-set version hash. The short form is preferred.

### `matches`

Patterns are RE2, not `java.util.regex`, so there are no backreferences and no lookaround. A
backtracking engine would turn a rule file, which is reviewed as configuration by people looking for
business logic, into a denial-of-service vector. RE2 is linear in the input and cannot backtrack
catastrophically.

The pattern is compiled once, at rule-compile time. An invalid pattern is a compile error, not a
failure at fire time.

### `hasField` and `isNull`

Both take a boolean carrying the polarity, rather than existing as two operators each.

`hasField` is about one field on one fact. It has nothing to do with "does any `Payment` exist for
this `Order`", which is quantification over a pattern and is a v1 non-goal.

`isNull: false` is the negation of the predicate ("not explicitly null"), so it matches an absent
field as readily as a present non-null one. "Present and not null" takes both `hasField: true` and
`isNull: false`.

## `$ref` and the `$$` escape

`{ $ref: alias.field }` is a reference to a field of another fact. Where it appears decides when it
resolves, and the two are different mechanisms sharing one syntax:

| Position | Resolves | Becomes |
|---|---|---|
| In a `where` operand | at compile time, against the join graph | a `JoinConstraint` |
| In a `then` value, payload or args | at fire time, against the matched tuple | a `FieldRef` |

```yaml
apiVersion: rules.v1
rules:
  - id: both-kinds-of-ref
    when:
      - fact: Order
        as: o
      - fact: Customer
        as: c
        where:
          id: { eq: { $ref: o.customerId } }     # compile time: a join
    then:
      - action: emit
        event: order.flagged
        payload:
          orderId: { $ref: o.id }                # fire time: read from the tuple
```

A `where` reference must name an earlier alias. That is what keeps the join graph acyclic: `o` is
bound before `c`, so `c` may reference `o` and not the other way round. A forward reference is a
compile error.

Any operator that relates two facts may join, not only `eq`. `total: { gt: { $ref: c.limit } }` is
a valid ordered join, and so is a `between` whose bounds are references:

```yaml
apiVersion: rules.v1
rules:
  - id: within-customer-limits
    when:
      - fact: Customer
        as: c
      - fact: Order
        as: o
        where:
          total:
            between:
              from: { $ref: c.floor }
              to:   { $ref: c.ceiling }
    then:
      - action: emit
        event: within-limits
```

`in`, `notIn`, `matches`, `hasField`, and `isNull` take no reference: they are single-fact tests, so
there is no other fact for a reference to name.

### The escape

A reference is structural (an object with a `$ref` key) rather than a string sigil like
`"$o.customerId"`. A sigil overloads the string space: a customer id genuinely beginning with `$`
becomes unexpressible, and `{ eq: "$100 tier" }` silently parses as a reference to an alias nobody
declared.

The structural form moves the problem rather than removing it: `eq` compares objects structurally,
so a literal object carrying a `$ref` key becomes the unexpressible case. The escape covers it:

```yaml
apiVersion: rules.v1
rules:
  - id: literal-object-with-a-dollar-key
    when:
      - fact: Document
        as: d
        where:
          meta: { eq: { $$ref: "this is a literal string, not a reference" } }
    then:
      - action: emit
        event: e
```

A key beginning with `$$` is a literal key with one `$` removed, at any depth. So `$$ref` writes a
literal `$ref`, and `$$total` writes a literal `$total`.

Any other `$`-prefixed key is rejected, at any depth, rather than passed through as an ordinary
field. `$reff` is an error, not a rule that silently never matches. A bare `$ref` nested inside a
literal is an error too: a reference is only meaningful as a whole operand.

## `then`: the five actions

A closed set of five verbs, not arbitrary script. A fixed vocabulary is diffable, reviewable, and
has bounded cost. `callFunction` is the one escape.

Actions are staged and then committed. Every action sees the same consistent view of working memory,
so an action cannot read what an earlier action in the same RHS wrote. Reading an earlier action's
write takes two rules.

### `setField`

```yaml
- action: setField
  target: o              # an alias bound by `when`
  field: status          # dotted path
  value: "REVIEW"        # a literal, or { $ref: c.riskTier }
```

Routes through `update`, so it is gated on the tested-path diff: changing a field no rule tests
propagates nothing. Several `setField`s on one target merge into a single update, applied in
declaration order.

### `insertFact`

```yaml
- action: insertFact
  fact: RiskSignal
  as: sig                # optional; binds the new handle for later actions in this RHS
  logical: false         # optional; true makes the fact a withdrawable conclusion (see below)
  payload:
    orderId:  { $ref: o.id }
    severity: "HIGH"
```

The handle is allocated at stage time, which is what lets a later action in the same RHS name `sig`.

#### Conclusions: `logical: true`

A **logical insert** is withdrawn when the match that made it stops holding (§4.4's amendment). It
closes the gap [`notExists`](#negation-quantifier-notexists) and
[`forAll`](#universals-quantifier-forall) otherwise leave: a rule that concluded something because
a `Payment` was absent un-concludes it when the payment arrives.

```yaml
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

When the payment is inserted, `OrderUnpaid` goes; when the payment is retracted, `OrderUnpaid` comes
back. The same happens when a bound fact is retracted, when an update stops it matching, or when a
`forAll`'s counterexample turns up.

Withdrawal happens at a cycle boundary, not the instant the reason goes. Right-hand sides are staged
and committed as a unit, so nothing is retracted mid-firing; a conclusion outlives its reason until
the next `fireAllRules` cycle.

Two matches concluding the same thing make two facts. Each carries its own reason and is withdrawn
on its own. There is no deduplication by payload, so two unpaid orders for one customer produce two
`CustomerAtRisk` facts; a single fact takes aggregation at ingestion.

Withdrawal cascades: a conclusion drawn from a conclusion goes when the first one does.

A re-firing replaces its conclusion. An update that keeps the match valid but clears refraction
fires the same activation again; what it concluded before was drawn from the old payload and is
retracted at the next cycle boundary rather than left standing beside the new one.

A rule must never conclude the fact its own `notExists` is about. It concludes, defeats the
negation, withdraws, and concludes again, a livelock that ends at the cycle limit. The same rule
with an ordinary insert settles after one firing.

A type the rules conclude must never be evicted (§4.4). Eviction drops the conclusion while its
justification still holds and the rule is still refracted, so it never returns. This is the third
member of the family alongside "never negate an evicted type" and "never quantify over one".

Without `logical` (the default), the fact stands until something retracts it explicitly.

### `retractFact`

```yaml
- action: retractFact
  target: sig
```

Retracting a fact this same RHS inserted cancels both effects at commit, rather than propagating an
insert and then a retract.

### `emit`

```yaml
- action: emit
  event: "order.flagged"
  payload:
    orderId: { $ref: o.id }
    reason:  "high value + risk tier"
```

Staged, delivered at commit in firing order to the session's `EventSink`. The default sink collects
into `FireResult` rather than performing I/O, which is what makes rules testable without mocking
anything.

### `callFunction`

```yaml
- action: callFunction
  name: notifySlack
  args:
    channel: "#risk-review"
    orderId: { $ref: o.id }
```

Dispatches by name to a pre-registered Java function.

- It runs at commit and is not transactional. If it throws, the working-memory effects that already
  landed stay landed. There is no compensating undo, and there cannot be one: a sent message cannot
  be un-sent.
- Declared names catch typos at compile time. With
  `CompilerOptions.builder().declaredFunctions(Set.of("notifySlack"))`, an unregistered name becomes
  a compile error instead of a fire-time failure on the one path that reaches it.

Arguments are resolved to values and deep-copied before the handler sees them.

### Dotted payload names

In `payload` and `args`, a key like `customer.id` writes to `/customer/id`, exactly as a `where`
field name reads from it.

## Diagnostics

Every problem in every file is reported at once, with a file, line, and column.

```
rule file is not valid:
  - orders.yaml:8:15: [unknown-dollar-key] '$reff' is not a key this DSL recognises, ...
  - orders.yaml:14:7: [empty-range] a between needs a 'from', a 'to', or both; ...
```

Validation happens in three stages, and each stops before the next: a file with structural errors
is not checked for meaning, the same way a compiler does not type-check a file it could not parse.
`schema-violation` errors are therefore the ones to fix first.

| Code | Meaning |
|---|---|
| `malformed-document` | The file is not well-formed JSON or YAML, or repeats a mapping key |
| `unknown-api-version` | `apiVersion` is missing or names a version the engine does not implement |
| `schema-violation` | A missing key, a wrong type, an unknown key, or a key belonging to a different action verb |
| `unknown-operator` | An operator map key that is not in the table above |
| `unknown-dollar-key` | A `$`-prefixed key that is neither `$ref` nor a `$$` escape |
| `malformed-reference` | A `$ref` that is not `alias.field`, carries extra keys, or is nested inside a literal |
| `empty-range` | A `between` with neither `from` nor `to` |
| `malformed-operand` | An operand of the wrong shape for its operator |
| `unknown-action` | A `then` verb outside the five |
| `unknown-quantifier` | A pattern's `quantifier` outside `exists`, `notExists`, `forAll`, and `accumulate` |
| `malformed-accumulate` | An `accumulate` block that does not compute exactly one thing, or a `having` whose operator cannot test a value |
| `malformed-action` | An action names a field path that will not compile, such as `a..b` |
| `semantic` | Everything the compiler checks: forward references, unknown aliases, duplicate ids, duplicate aliases, invalid regexes, malformed `where` and `$ref` field paths, and unregistered function names |

`unknown-operator`, `malformed-operand`, `unknown-action`, and `unknown-quantifier` are stated by
the rule-file schema as well, and the schema runs first, so in practice those problems are reported
as `schema-violation`. They exist as separate codes because the DSL compiler checks them too rather
than assuming the gate ahead of it ran.

## The compiler report

`CompiledRuleSet.report()` returns what the compiler noticed, as data rather than a printed string,
so CI can assert on it.

```java
CompilerReport report = rules.report();
System.out.println(report.describe());

// rule set sha256:4073bf55c15edf78
//   2 rules, 3 distinct alpha nodes from 4 tests (sharing 1.33x), 2 patterns, 1 join edges
//   unindexed: fraud-check: o.region (NOT_IN)
```

| Component | What it holds |
|---|---|
| `ruleSetVersion` | The content hash, so a report can be matched to its rule set in a log |
| `errors` | Always empty; compilation throws if a rule set has errors |
| `warnings` | Things that compiled but merit a look (see the table below) |
| `unindexed` | Every constraint no index can serve, with a reason |
| `celCosts` | One entry per §6.4 expression: its compile-time cost estimate, the budget, and whether it is over. Populated whenever an `ExpressionCompiler` is registered. An expression that grows past its budget is a rule a later compile refuses, which makes this worth asserting on in CI |
| `sharing` | Rule, alpha-node, and pattern counts, and the sharing ratio |
| `unreachableRules` | Rules no fact can activate; empty unless fact types are declared |

`unindexed` is read by reason, not by count, because the reasons cost very different things:

- `RESIDUAL_JOIN_CONDITION` is the expensive one. A join whose operator cannot probe an index is
  re-evaluated against candidates on every fire cycle, so its cost scales with the product of two
  pattern memories. Indexed joins are the single biggest lever for join-heavy rule sets; this is a
  rule that gave the lever up.
- `NE`, `NOT_IN`, and `MATCHES` on a single-fact constraint are cheap. A pattern memory holds
  exactly the facts passing its alpha tests, so such a test runs once per insert and never again.
  They are listed so that an author choosing between `ne` and `hasField` knows which the index can
  use, not because each is a problem to fix.

`unreachableRules` needs input: a compiler cannot know which fact types the host inserts. Given the
declared fact types, it finds rules that can never fire.

```java
CompilerOptions options = CompilerOptions.builder()
    .declaredFactTypes(Set.of("Order", "Customer"))
    .build();
```

Types the rules themselves derive through `insertFact` count as reachable.

### Warnings

| Code | Means | Needs a schema |
|---|---|---|
| `shallow-tested-path` | This rule tests a path that contains a deeper path another constraint tests, so every update to the type compares the whole subtree (§3.4.2) | no |
| `impossible-range` | Bounds on one field that cannot all hold: `{ from: 500, to: 100 }`, the equivalent `{ gt: 500, lt: 100 }` split across two operators, or equal bounds with either end exclusive | no |
| `ne-on-optional-path` | An `ne` or `notIn` against a field the schema says is optional, unguarded by `hasField: true` (see [the trap](dsl-guide.md#ne-on-a-missing-field)) | yes |
| `vacuous-anti-match` | An `ne` or `notIn` whose literal is of a type the field can never hold. §2.6.1 makes that true, so the constraint filters nothing | yes |

## Fact schemas (optional)

Registering a JSON Schema per fact type is opt-in and changes two things.

At compile time, a literal the field could never hold becomes an error instead of a rule that ships
and silently never matches:

```java
FactSchemas schemas = JsonSchemaFactSchemas.builder()
    .register("Order", orderSchema)     // any Jackson JsonNode holding a JSON Schema document
    .build();

CompiledRuleSet rules = RuleFiles.compile(sources,
    CompilerOptions.builder().factSchemas(schemas).build());
```

With `Order.total` declared `"type": "number"`, `total: { gt: "expensive" }` fails to compile. This
is the strongest single reason to register schemas on the fact types that matter.

At insert time, a malformed payload is rejected at the boundary with a `SchemaViolationException`
rather than entering working memory and quietly matching nothing. Updates are validated too.

The registry is frozen into the compiled rule set and shared by every session, so it is immutable by
contract: it is built, handed to the compiler, and changed only by recompiling, which is the same
operation as changing rules.

The compile-time error fires only where §2.6.1 proves the comparison false: `eq`, the four ordered
operators, `matches` against a non-string, and an `in` list where every element is incompatible. It
does not fire on `ne` or `notIn`: those are `!eq` and `!in`, so §2.6.1 makes a wrong-typed literal
come out true, and the rule matches everything rather than nothing. That is still a bug, and it
comes back as a `vacuous-anti-match` warning.

Two further cases are not checked. Presence tests (`hasField`, `isNull`) are never type-checked,
because their literal is a polarity rather than a value of the field's type. An explicit `null` is
accepted against any declared type, because §2.6.1 makes `eq: null` a meaningful test.

The check is about §2.6.1's compatibility classes (`{number}`, `{string}`, `{boolean}`, `{array}`,
`{object}`), not about JSON Schema's types. A field declared `integer` compared against `99.5` is
fine: a value of `100` satisfies it.

Compile-time introspection has one limit. It reads `properties`, `required`, and `type` directly. It
does not resolve `$ref`, and does not reconcile `allOf`/`anyOf`/`oneOf`; a schema node carrying any
of those is reported as unknown, and everything beneath it goes unchecked. Validation has no such
limit; it handles the full specification. An unmade check leaves a rule no worse off than it was
without a schema, where a guessed one would reject a correct rule.

### Rule-set version and source order

`CompiledRuleSet.version()` is a content hash over the compiled rules, and it includes constraint
order, which comes from the order of keys in the `where` blocks and of entries in the `when` and
`then` lists.

Two files that differ only by a cosmetic reordering therefore compile to rule sets with different
versions. The order is preserved because it is mildly observable: it is the order constraints are
evaluated in, and therefore which constraint `MatchExplainer` names as the one that eliminated a
fact. Canonicalising it away would make the hash claim an equivalence the engine does not provide.

It matters in one place: a hot-reload holder that swaps on a version change swaps when somebody
reorders two lines. A host that needs to ignore reordering compares the rule files rather than the
compiled version.

`sharing.joinNodes` is always `0`, and that is not a measurement: v1 uses a TREAT-shaped conflict
set, which has no beta network; join order is chosen fresh each fire cycle rather than materialised
as a graph. `sharing.joinEdges` is the join-complexity figure v1 reports. A real `joinNodes` arrives
with the Rete shape in Phase 3.

## The expression escape hatch

For boolean logic that operator maps make awkward (nested AND/OR/NOT, or arithmetic across
fields) there is an opt-in expression form backed by [CEL](https://cel.dev/). It is not the default:
§6.3 keeps the indexable path the one a rule gets without asking.

It needs the `rule-engine-cel` module and an explicit registration. Without them, a rule using an
expression is a compile error naming what is missing:

```java
CompiledRuleSet rules = RuleFiles.compile(sources,
    CompilerOptions.builder()
        .expressions(CelExpressions.create())
        .expressionBudget(500)              // optional; caps one expression's estimated cost
        .build());
```

### `condition:` on the left

```yaml
apiVersion: rules.v1
rules:
  - id: interesting-order
    when:
      - fact: Order
        as: o
        where:
          region: { eq: "US" }                                   # still indexed
        condition: "o.subtotal > 50 && (o.tier in ['A','B'] || o.priorityFlag)"
    then:
      - action: emit
        event: interesting
```

A condition may read every alias the rule declares, including an `accumulate`'s, which binds a value
rather than a fact, and including one declared later in the `when` block, since a condition is not
part of the join. It may not be written on a quantified pattern (`notExists`, `forAll`,
`accumulate`); [Aggregates](#aggregates-quantifier-accumulate) shows the shape that works. It spans
facts as freely as a `$ref` does. It sits beside operator maps rather than replacing them, because
the operator maps are what still narrow the search.

A condition is an unindexed post-filter. It is evaluated once per candidate match, after the indexed
work has cut the field down. That is the visible cost of the escape hatch, and it is why a condition
appears in the compiler report as `CEL_EXPRESSION`. The indexable constraints do the narrowing, and
the condition expresses only what they cannot.

### `$expr` on the right

```yaml
then:
  - action: setField
    target: o
    field: band
    value: { $expr: "o.subtotal > 50 ? 'HIGH' : 'LOW'" }
  - action: emit
    event: priced
    payload:
      total: { $expr: "o.subtotal + o.tax" }
```

`$expr` joins `$ref` as a recognised `$`-prefixed key, with the same rules: it must be the whole
operand, `$$expr` writes a literal field named `$expr`, and a bare `$expr` nested inside a literal
is an error.

An expression on the right is much cheaper than one on the left: it runs once per firing, not once
per candidate. It also replaces `callFunction` for a pure computation such as `subtotal + tax`.
`callFunction` runs at commit and is not transactional, which makes it the one action that can leave
half-applied state behind.

### Evaluation semantics

An absent field is an error, not a false. This is the sharpest difference between the two halves of
the DSL. An operator map treats absence as a value: §2.6.1 makes `eq` false and `ne` true against
it. CEL treats a missing key as an evaluation error. The guard is CEL's own:

```yaml
condition: "has(o.coupon) && o.coupon != ''"
```

An explicit null is different and does work: it arrives as CEL's `null`, so `o.closedAt == null`
holds for it. Comparing a null with `>` is still an error, as it is in CEL generally.

A condition that fails to evaluate stops the fire cycle. There is no per-match error policy on the
left-hand side: `RhsErrorHandler` governs actions, and a condition runs while the conflict set is
being built. The exception names the rule, the alias, and the expression. This is the strongest
reason to keep conditions simple and to guard what they read.

Comparisons work across integers and decimals; mixed arithmetic does not. `o.price > 100` holds
whether `price` is `150` or `150.5`. But CEL has no `int + double` overload, so `o.subtotal + o.tax`
fails if one is `10` and the other `1.5`; the conversion has to be explicit:
`double(o.subtotal) + o.tax`. §2.6.2 canonicalises through `BigDecimal` and CEL has no such type, so
integral values within `long` range are exact and everything else goes through `double`. Money
arithmetic belongs before the fact reaches the engine.

An expression must produce something JSON can hold. A CEL `null`, number, string, boolean, list, or
map is fine. A `type()`, a `bytes`, or a `duration` is refused with an error rather than written
into the fact as a string, which would otherwise quietly store `"NULL_VALUE"` where a null belonged.

Cost is bounded at both ends, and neither bound is a promise about time. A compile-time estimate is
checked against `expressionBudget`; at run time, comprehension iterations, parse depth, and node
count are capped. CEL guarantees termination, not linear time: nested comprehensions over two lists
are O(n·m), which is what the iteration cap exists for. A per-expression budget also does not bound
how many times the engine runs the expression. An unindexed condition against 100,000 facts is
100,000 evaluations, each within budget.

Expressions cannot read a clock, a random source, or anything outside the facts bound to the rule's
aliases. §7.3's determinism contract depends on that. A rule that needs the time has it inserted as
a fact.

## Not implemented yet

Two features are deferred, each with an interim answer in §1 of the spec: `collect` and backward
chaining. The interim answer for both is the same: compute the result at ingestion and insert it as
a fact.

A lookup operator is not deferred; none is planned. "Is this value in a list my application owns"
is answered by inserting the membership as a fact before the session, with `member: true` or
`false`, and letting a rule that adds to the list flip that field and `emit` the write. The guide
has the complete file: [Checking a list your application
owns](dsl-guide.md#checking-a-list-your-application-owns).
