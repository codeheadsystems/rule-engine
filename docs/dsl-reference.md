# Rule file reference (`rules.v1`)

The complete, normative surface of the JSON/YAML rule DSL. This is the "what"; for a walkthrough
that starts from a blank file, read [`dsl-guide.md`](dsl-guide.md).

Two documents remain authoritative over this one, and where they disagree with it they win:
[`rule-engine-spec.md`](rule-engine-spec.md) §6 specifies the DSL, and §2.6.1 specifies what every
comparison *means* against absent, null and wrong-typed values. This page gives the syntax and
points at §2.6.1 for the semantics rather than restating them, because a second copy of a
comparison table is a second copy to get out of step.

Every YAML block below that begins with `apiVersion:` is a fixture in `DocExamplesTest`. If this
page and the engine disagree, this page is wrong.

## Contents

- [The rule file](#the-rule-file)
- [A rule](#a-rule)
- [`when`: patterns and operator maps](#when-patterns-and-operator-maps)
- [The operator table](#the-operator-table)
- [`$ref`, and the `$$` escape](#ref-and-the--escape)
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
rather than parsed on a best-effort basis — that is what lets the schema change later without
silently reinterpreting files written against an older one.

`rules` must hold at least one rule. A file that loads zero rules is almost always an editing
accident, and one that fails loudly is better than a service that starts with no rules and says
nothing.

**A rule set is the union of many files.** `RuleFiles.compile(...)` takes as many as you like, and
`id` must be unique across all of them — which is only checkable if they are compiled together.

```java
CompiledRuleSet rules = RuleFiles.compile(          // handle the IOException
    RuleSource.of(Path.of("orders.yaml")),
    RuleSource.of(Path.of("fraud.yaml")));
```

`RuleSource.of(Path)` picks the format from the extension: `.json`, `.yaml` or `.yml`. It reads
the file, so it declares `IOException`; an extension it does not recognise is an
`IllegalArgumentException`, because that is a call you got wrong rather than a file that would
not open. Use `RuleSource.yaml(name, text)` or `RuleSource.json(name, text)` when the text does
not come from a file — those throw neither, and the name is only ever used to report
diagnostics against.

**JSON and YAML are the same language.** They parse into one object model and compile to one rule
set; the whole difference is which Jackson factory reads the text. Everything in this page applies
to both, and the examples are in YAML only because it is easier to read.

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
| `id` | string | *required* | Unique across every file in the rule set. Appears in traces, in `FireResult`, and in every diagnostic |
| `salience` | integer | `0` | Conflict-resolution priority; higher fires first (§4.2) |
| `noLoop` | boolean | `false` | Suppresses re-activation of *this same match* caused by this rule's own RHS (§4.5). One level deep; it is not a loop guard |
| `agendaGroup` | string | unset | §4.5 defers agenda grouping to v2. v1 records the value and ignores it |
| `tags` | list of string | `[]` | Free-form labels, for filtering and reporting |
| `when` | list of patterns | *required* | The LHS. Patterns are implicitly AND-ed |
| `then` | list of actions | *required* | The RHS, executed in declaration order |

## `when`: patterns and operator maps

A pattern names a fact type, binds it to an alias, and optionally constrains it.

```yaml
apiVersion: rules.v1
rules:
  - id: pattern-shape
    when:
      - fact: Order            # required: the fact type
        as: o                  # required: the alias, unique within this rule
        where:                 # optional: field name -> operator map
          total:  { gt: 10000 }
          status: { eq: "PENDING" }
    then:
      - action: emit
        event: e
```

**Three levels of implicit AND**, and it is worth knowing all three are the same:

1. Across patterns in `when` — every pattern must match.
2. Across fields in one `where` — every field's constraints must hold.
3. Across operators in one field's map — `{ gt: 100, lt: 500 }` requires both.

There is no `or`. §6.3 explains the trade: operator maps keep the indexable path the default, and a
free-form boolean expression generally cannot be decomposed into an index plan. Express alternation
either as two rules, or with `in`.

**Field names are dotted paths.** `customer.id` reads `/customer/id` from the payload. Array
indices work (`items.0.qty`); wildcards do not exist, which is why collections are flattened at
ingestion — see [the guide](dsl-guide.md#flatten-collections-at-ingestion).

**Distinct aliases bind distinct facts.** `Order as o1` and `Order as o2` in one rule find two
*different* orders; the compiler inserts the inequality for you. This differs from OPS5, and it is
the reading rule authors expect.

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

Prefer `between` for a two-sided bound. It compiles to a single constraint where the first form
compiles to two, and it can state inclusivity, which `gt`/`lt` can only do by choosing a different
operator.

**Both operators go in one map.** Writing the field name twice is a duplicate mapping key, and it is
rejected:

```yaml
where:
  status: { hasField: true, ne: "CLOSED" }   # correct
  # status: { hasField: true }               # NOT this --
  # status: { ne: "CLOSED" }                 # -- a repeated key is an error
```

Both JSON and YAML would otherwise accept a repeated key and silently keep the last one, which would
give you neither an AND nor an error — just a rule matching everything the discarded condition would
have excluded, in a file that reads exactly as intended. This engine turns that into a
`malformed-document` error naming the key.

## The operator table

The complete set. Semantics against absent, null and wrong-typed values are §2.6.1's table, which
is normative; this one is the syntax.

| Key | Example | Compiles to | As a join |
|---|---|---|---|
| `eq` | `status: { eq: "PENDING" }` | `FieldConstraint(EQ)` | hash index |
| `ne` | `status: { ne: "CLOSED" }` | `FieldConstraint(NE)` | no — an anti-match cannot be narrowed |
| `gt` | `total: { gt: 10000 }` | `RangeConstraint`, one-sided exclusive | sorted index |
| `gte` | `total: { gte: 10000 }` | `RangeConstraint`, one-sided inclusive | sorted index |
| `lt` | `total: { lt: 500 }` | `RangeConstraint`, one-sided exclusive | sorted index |
| `lte` | `total: { lte: 500 }` | `RangeConstraint`, one-sided inclusive | sorted index |
| `between` | `total: { between: { from: 100, to: 500 } }` | `RangeConstraint`, two-sided | sorted index |
| `in` | `riskTier: { in: ["HIGH", "MEDIUM"] }` | `FieldConstraint(IN)` | n/a — takes no `$ref` |
| `notIn` | `region: { notIn: ["XX"] }` | `FieldConstraint(NOT_IN)` | n/a — takes no `$ref` |
| `matches` | `email: { matches: "^[a-z]+@example\\.com$" }` | `FieldConstraint(MATCHES)` | n/a — takes no `$ref` |
| `hasField` | `couponCode: { hasField: false }` | `FieldConstraint(HAS_FIELD)` | n/a — takes no `$ref` |
| `isNull` | `closedAt: { isNull: true }` | `FieldConstraint(IS_NULL)` | n/a — takes no `$ref` |

DSL keys are camelCase; the `Operator` constants they compile to are SCREAMING_SNAKE. `notIn` is
`NOT_IN`, `hasField` is `HAS_FIELD`.

**The last column is about joins, and only about joins — that is not a simplification.** In v1 an
index is built for a path *because a join probes it*, never because a single-fact constraint reads
it. There would be nothing for a single-fact index to do: a pattern's memory already holds exactly
the facts that passed its constraints, so `status: { eq: "PENDING" }` has been applied once, when
the fact was inserted, and an index over it would be a structure with one bucket and no reader.

So a single-fact `eq` is not "hash indexed" and does not need to be. What the column tells you is
what happens when you write the same operator against a `$ref`: an `eq` join gets a hash probe, an
ordered join gets a sorted one, and an `ne` join gets neither and falls to a linear post-filter —
which is the one the compiler report calls `RESIDUAL_JOIN_CONDITION` and the one worth avoiding.

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
without a convention that then has to be documented, validated and remembered.

`from` and `to` are individually optional, but at least one must be present — a `between` bounding
nothing is an `empty-range` error. A one-sided `between` compiles to *exactly* the same constraint
as the short form, so `{ between: { from: 100 } }` and `{ gte: 100 }` are indistinguishable
downstream, down to the rule-set version hash. Prefer the short form.

### `matches`

Patterns are RE2, not `java.util.regex`. That means no backreferences and no lookaround, and it is
deliberate: a backtracking engine turns a rule file — reviewed as configuration, by people looking
for business logic — into a denial-of-service vector. RE2 is linear in the input and cannot
backtrack catastrophically.

The pattern is compiled once, at rule-compile time. An invalid pattern is a compile error, not a
surprise at fire time.

### `hasField` and `isNull`

Both take a boolean carrying the polarity, rather than existing as two operators each.

`hasField` is about one field on one fact. It has nothing to do with "does any `Payment` exist for
this `Order`", which is quantification over a *pattern* and is a v1 non-goal.

`isNull: false` is the negation of the predicate — "not explicitly null" — so it matches an absent
field as readily as a present non-null one. If you mean "present and not null", write both
`hasField: true` and `isNull: false`.

## `$ref`, and the `$$` escape

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

**A `where` reference must name an *earlier* alias.** That is what keeps the join graph acyclic:
`o` is bound before `c`, so `c` may reference `o` and not the other way round. A forward reference
is a compile error.

**Any operator that relates two facts may join**, not only `eq`. `total: { gt: { $ref: c.limit } }`
is a valid ordered join, and so is a `between` whose bounds are references:

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

`in`, `notIn`, `matches`, `hasField` and `isNull` take no reference: they are single-fact tests, so
there is no other fact for a reference to name.

### The escape

A reference is structural — an object with a `$ref` key — rather than a string sigil like
`"$o.customerId"`. A sigil overloads the string space: a customer id genuinely beginning with `$`
becomes unexpressible, and `{ eq: "$100 tier" }` silently parses as a reference to an alias nobody
declared.

The structural form moves the problem rather than removing it: `eq` compares objects structurally,
so now a *literal object carrying a `$ref` key* is the unexpressible case. Hence the escape:

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

**Any other `$`-prefixed key is rejected**, at any depth, rather than passed through as an ordinary
field. `$reff` is an error, not a rule that silently never matches. A bare `$ref` nested inside a
literal is an error too — a reference is only meaningful as a whole operand.

## `then`: the five actions

A closed set of five verbs. Deliberately not arbitrary script: config that is really code is much of
what makes rule engines feel heavy, and a fixed vocabulary is diffable, reviewable and has bounded
cost. `callFunction` is the one escape.

Actions are **staged and then committed**. Every action sees the same consistent view of working
memory, so an action cannot read what an earlier action in the same RHS wrote. That is the right
limitation: needing it means you wanted two rules.

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
  payload:
    orderId:  { $ref: o.id }
    severity: "HIGH"
```

The handle is allocated at stage time, which is what lets a later action in the same RHS name `sig`.

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

Dispatches by name to a pre-registered Java function. Two things to know before reaching for it:

- **It runs at commit and is not transactional.** If it throws, the working-memory effects that
  already landed stay landed. There is no compensating undo, and there cannot be one — a sent
  message cannot be un-sent.
- **Declare the names to catch typos at compile time.** Pass
  `CompilerOptions.builder().declaredFunctions(Set.of("notifySlack"))` and an unregistered name
  becomes a compile error instead of a fire-time failure on the one path that reaches it.

Arguments are resolved to values and deep-copied before the handler sees them.

### Payload names are dotted paths

In `payload` and `args`, a key like `customer.id` writes to `/customer/id`, exactly as a `where`
field name reads from it.

## Diagnostics

Every problem in every file is reported at once, with a file, line and column.

```
rule file is not valid:
  - orders.yaml:8:15: [unknown-dollar-key] '$reff' is not a key this DSL recognises, ...
  - orders.yaml:14:7: [empty-range] a between needs a 'from', a 'to', or both; ...
```

Validation happens in three stages, and each stops before the next: a file with structural errors
is not checked for meaning, the same way a compiler does not type-check a file it could not parse.
Fix `schema-violation` errors first.

| Code | Meaning |
|---|---|
| `malformed-document` | The file is not well-formed JSON or YAML, or repeats a mapping key |
| `unknown-api-version` | `apiVersion` is missing or names a version this engine does not implement |
| `schema-violation` | A missing key, a wrong type, an unknown key, or a key belonging to a different action verb |
| `unknown-operator` | An operator map key that is not in the table above |
| `unknown-dollar-key` | A `$`-prefixed key that is neither `$ref` nor a `$$` escape |
| `malformed-reference` | A `$ref` that is not `alias.field`, carries extra keys, or is nested inside a literal |
| `empty-range` | A `between` with neither `from` nor `to` |
| `malformed-operand` | An operand of the wrong shape for its operator |
| `unknown-action` | A `then` verb outside the five |
| `malformed-action` | An action names a field path that will not compile, such as `a..b` |
| `condition-not-implemented` | The CEL escape hatch; see below |
| `semantic` | Everything the compiler checks: forward references, unknown aliases, duplicate ids, duplicate aliases, invalid regexes, malformed `where` and `$ref` field paths, unregistered function names |

`unknown-operator`, `malformed-operand` and `unknown-action` are stated by the rule-file schema as
well, and the schema runs first — so in practice you will see `schema-violation` for those. They
exist as separate codes because the DSL compiler checks them too rather than assuming the gate ahead
of it ran.

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
| `errors` | Always empty — compilation throws if a rule set has errors |
| `warnings` | Things that compiled but are worth a look — see the table below |
| `unindexed` | Every constraint no index can serve, with a reason |
| `celCosts` | Empty until the CEL module lands |
| `sharing` | Rule, alpha-node and pattern counts, and the sharing ratio |
| `unreachableRules` | Rules no fact can activate — empty unless you declare your fact types |

**Read `unindexed` by reason, not by count.** The reasons cost very different things:

- `RESIDUAL_JOIN_CONDITION` is the expensive one. A join whose operator cannot probe an index is
  re-evaluated against candidates on every fire cycle, so its cost scales with the product of two
  pattern memories. Indexed joins are the single biggest lever for join-heavy rule sets; this is a
  rule that gave the lever up.
- `NE`, `NOT_IN` and `MATCHES` on a single-fact constraint are cheap. A pattern memory holds
  exactly the facts passing its alpha tests, so such a test runs **once per insert** and never
  again. They are listed so that an author choosing between `ne` and `hasField` knows which the
  index can use — not because each is a problem to go and fix.

`unreachableRules` needs help: a compiler cannot know which fact types your host will insert. Tell
it, and it will find rules that can never fire.

```java
CompilerOptions options = CompilerOptions.builder()
    .declaredFactTypes(Set.of("Order", "Customer"))
    .build();
```

Types your own rules derive through `insertFact` count as reachable.

### Warnings

| Code | Means | Needs a schema |
|---|---|---|
| `shallow-tested-path` | This rule tests a path that contains a deeper path another constraint tests, so every update to the type compares the whole subtree (§3.4.2) | no |
| `impossible-range` | Bounds on one field that cannot all hold — `{ from: 500, to: 100 }`, the equivalent `{ gt: 500, lt: 100 }` split across two operators, or equal bounds with either end exclusive | no |
| `ne-on-optional-path` | An `ne` or `notIn` against a field the schema says is optional, unguarded by `hasField: true` — see [the trap](dsl-guide.md#ne-is-true-for-a-missing-field) | **yes** |
| `vacuous-anti-match` | An `ne` or `notIn` whose literal is of a type the field can never hold. §2.6.1 makes that **true**, so the constraint filters nothing | **yes** |

## Fact schemas (optional)

Registering a JSON Schema per fact type is opt-in and changes two things.

**At compile time**, a literal the field could never hold becomes an error instead of a rule that
ships and silently never matches:

```java
FactSchemas schemas = JsonSchemaFactSchemas.builder()
    .register("Order", orderSchema)     // any Jackson JsonNode holding a JSON Schema document
    .build();

CompiledRuleSet rules = RuleFiles.compile(sources,
    CompilerOptions.builder().factSchemas(schemas).build());
```

With `Order.total` declared `"type": "number"`, `total: { gt: "expensive" }` now fails to compile.
This is the strongest single reason to register schemas on the fact types that matter.

**At insert time**, a malformed payload is rejected at the boundary with a `SchemaViolationException`
rather than entering working memory and quietly matching nothing. Updates are validated too.

The registry is frozen into the compiled rule set and shared by every session, so it is immutable by
contract — build it, hand it to the compiler, and recompile to change it. That is the same operation
as changing rules.

**What it checks, and what it deliberately does not.** The error fires only where §2.6.1 proves the
comparison is *false* — `eq`, the four ordered operators, `matches` against a non-string, and an
`in` list where **every** element is incompatible. It does not fire on `ne` or `notIn`: those are
`!eq` and `!in`, so §2.6.1 makes a wrong-typed literal come out **true**, and the rule matches
everything rather than nothing. That is still a bug, and it comes back as a `vacuous-anti-match`
warning.

Two further silences. Presence tests (`hasField`, `isNull`) are never type-checked, because their
literal is a polarity rather than a value of the field's type. And an explicit `null` is accepted
against any declared type, because §2.6.1 makes `eq: null` a meaningful test.

Note that the check is about §2.6.1's **compatibility classes** — `{number}`, `{string}`,
`{boolean}`, `{array}`, `{object}` — not about JSON Schema's types. A field declared `integer`
compared against `99.5` is fine: a value of `100` satisfies it.

**One limit worth knowing.** Compile-time introspection reads `properties`, `required` and `type`
directly. It does not resolve `$ref`, and does not reconcile `allOf`/`anyOf`/`oneOf` — a schema node
carrying any of those is reported as unknown, and everything beneath it goes unchecked. Validation
has no such limit; it handles the full specification. The asymmetry is deliberate: an unmade check
costs you what you had before registering a schema, where a guessed one would reject a correct rule.

### The rule-set version is sensitive to the order you write things in

`CompiledRuleSet.version()` is a content hash over the compiled rules, and it includes **constraint
order** — which comes from the order of keys in your `where` blocks and of entries in your `when`
and `then` lists.

So two files that differ only by a cosmetic reordering compile to rule sets with *different*
versions. That is deliberate rather than an oversight: the order is preserved because it is mildly
observable — it is the order constraints are evaluated in, and therefore which constraint
`MatchExplainer` names as the one that eliminated a fact — and canonicalising it away would make the
hash claim an equivalence the engine does not actually provide.

It matters in one place: a hot-reload holder that swaps on a version change will swap when somebody
reorders two lines. If that is a problem for you, compare the rule *files* rather than the compiled
version.

`sharing.joinNodes` is always `0`, and that is not a measurement: v1 uses a TREAT-shaped conflict
set, which has no beta network — join order is chosen fresh each fire cycle rather than materialised
as a graph. `sharing.joinEdges` is what v1 can honestly say about join complexity. A real
`joinNodes` arrives with the Rete shape in Phase 3.

## The expression escape hatch

For boolean logic operator maps make awkward — nested AND/OR/NOT, arithmetic across fields — there
is an opt-in expression form backed by [CEL](https://cel.dev/). It is deliberately *not* the default:
§6.3 keeps the indexable path the one you get without asking.

**It needs the `rule-engine-cel` module and an explicit registration.** Without them, a rule using an
expression is a compile error naming what is missing:

```java
CompiledRuleSet rules = RuleFiles.compile(sources,
    CompilerOptions.builder()
        .expressions(CelExpressions.create())
        .expressionBudget(500)              // optional; caps one expression's estimated cost
        .build());
```

### `condition:` — on the left

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

A condition may read any alias the rule binds, so it spans facts as freely as a `$ref` does. It sits
beside operator maps rather than replacing them — and it should, because the operator maps are what
still narrow the search.

**A condition is an unindexed post-filter.** It is evaluated once per candidate match, after the
indexed work has cut the field down. That is the visible cost the escape hatch is meant to carry, and
it is why it appears in the compiler report as `CEL_EXPRESSION`. Keep the indexable constraints doing
the narrowing and let the condition express only what they cannot.

### `$expr` — on the right

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
operand, `$$expr` writes a literal field named `$expr`, and a bare `$expr` nested inside a literal is
an error.

**An expression on the right is much cheaper than one on the left** — it runs once per *firing*, not
once per candidate. It is also the reason to reach for `callFunction` less: computing
`subtotal + tax` used to need it, and `callFunction` runs at commit and is explicitly not
transactional, so a pure computation had to be bought with the one action that can leave half-applied
state behind.

### Three things to know

**An absent field is an error, not a false.** This is the sharpest difference between the two halves
of this DSL. An operator map treats absence as a value — §2.6.1 makes `eq` false and `ne` true
against it. CEL treats a missing key as an evaluation error. Guard it the way CEL does:

```yaml
condition: "has(o.coupon) && o.coupon != ''"
```

An *explicit* null is different and does work: it arrives as CEL's `null`, so `o.closedAt == null`
behaves as you would expect. Comparing a null with `>` is still an error, as it is in CEL generally.

**A condition that fails to evaluate stops the fire cycle.** There is no per-match error policy on
the left-hand side — `RhsErrorHandler` governs actions, and a condition runs while the conflict set
is being built. The exception names the rule, the alias and the expression. This is the strongest
reason to keep conditions simple and guard what they read.

**Comparisons work across integers and decimals; mixed *arithmetic* does not.** `o.price > 100`
holds whether `price` is `150` or `150.5`. But CEL has no `int + double` overload, so
`o.subtotal + o.tax` fails if one is `10` and the other `1.5` — convert explicitly:
`double(o.subtotal) + o.tax`. §2.6.2 canonicalises through `BigDecimal` and CEL has no such type, so
integral values within `long` range are exact and everything else goes through `double`. Compute
money before the fact reaches the engine.

**An expression must produce something JSON can hold.** A CEL `null`, number, string, boolean, list
or map is fine. A `type()`, a `bytes` or a `duration` is refused with an error rather than written
into the fact as a string — quietly storing `"NULL_VALUE"` where a null belonged is the failure that
rule is there to prevent.

**Cost is bounded at both ends, and neither bound is a promise about time.** A compile-time estimate
is checked against `expressionBudget`; at run time, comprehension iterations, parse depth and node
count are capped. CEL guarantees *termination*, not linear time — nested comprehensions over two
lists are O(n·m), which is what the iteration cap exists for. And note what a per-expression budget
does not bound: how many times the engine runs it. An unindexed condition against 100,000 facts is
100,000 evaluations, each within budget.

Expressions cannot read a clock, a random source, or anything outside the facts bound to the rule's
aliases. That is not incidental — §7.3's determinism contract depends on it. If a rule needs the
time, insert it as a fact.

## Not implemented yet

Deferred, each with an interim answer in §1 of the spec: negation (`NOT_EXISTS`), accumulation,
backward chaining, truth maintenance, and temporal operators. The short version of all of them is the
same: compute it at ingestion and insert the answer as a fact.
