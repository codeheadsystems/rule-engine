# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## The spec is the source of truth

`docs/rule-engine-spec.md` (1800 lines) specifies this engine in full. The code implements it
section by section, and comments cite section numbers (`§3.4.1`, `section 6.5`) as their
justification. Before changing matching, agenda, update or RHS semantics, read the section that
governs it — the answer to "why is it written this way" is almost always there, along with the
alternatives that were rejected. If the code and the spec disagree, one of them is a defect; decide
which and say so, do not silently pick.

`README.md` documents what is built — Phases 0–2 (= v1), Phase 4's concurrency layer, Phase 5's DSL
front end including its two optional halves (the CEL escape hatch §6.4 and `FactSchemas` §2.3), and
the first slice of Phase 3's streaming matcher. §9 holds the
roadmap and each phase's exit criteria.

`docs/dsl-reference.md` and `docs/dsl-guide.md` document the rule-file DSL. Every rule file printed
in either — and in README — is a fixture in `DocExamplesTest`; if a doc and the engine disagree, the
doc is wrong.

## Build and test

Requires a JDK 25 toolchain; Gradle resolves one via the foojay plugin if it is not installed.

```bash
./gradlew build          # compile + test + strictTest (check depends on strictTest)
./gradlew test           # the suite
./gradlew strictTest     # the same suite with -Drules.strict=true (§7.5)
./gradlew javadoc        # doclint:all with -Werror; load-bearing contracts live only in Javadoc
./gradlew testCodeCoverageReport   # aggregated across modules; per-module coverage is misleading
./gradlew :rule-engine-testkit:jmh # benchmarks; see docs/benchmarks.md

# one test class / one method
./gradlew :rule-engine-testkit:test --tests '*JoinAndAliasTest'
./gradlew :rule-engine-testkit:test --tests '*JoinAndAliasTest.someMethod'
./gradlew :rule-engine-testkit:strictTest --tests '*JoinAndAliasTest'
./gradlew :rule-engine-dsl:test --tests '*OperatorMapTest'
```

CI (`.github/workflows/gradle.yml`) runs `./gradlew build javadoc`. Two things fail the build that
usually do not: `-Xlint:all -Werror` on every module, and Javadoc warnings. Every public element
needs a complete Javadoc with `@param`/`@return`, including on records and builders.

Most tests live in `rule-engine-testkit/src/test` even when they exercise `-core`, because they are
end-to-end. That is why coverage is aggregated at the root — a per-module report attributed 26% to
`-core` when the real figure was 91%.

Before running `git commit`, always delegate to the senior-reviewer subagent
to review the staged diff. Do not commit if it flags any Blockers until
they're addressed.

## Modules

| Module | Contents |
|---|---|
| `rule-engine-core` | Fact model, working memory, both matchers, agenda, refraction, RHS execution, sessions |
| `rule-engine-compiler` | `RuleDefinition` → `CompiledRuleSet`: validation, accessor/pattern compilation, `TestedPaths`, network build, version hash, `CompilerReport` |
| `rule-engine-dsl` | JSON/YAML rule files → `RuleDefinition`; the `rules.v1` schema; located diagnostics |
| `rule-engine-schema` | The optional `FactSchemas` of §2.3, backed by networknt JSON Schema |
| `rule-engine-cel` | The optional §6.4 expression escape hatch, backed by dev.cel |
| `rule-engine-observability` | `TracingListener`, `JfrListener`, `MatchExplainer` |
| `rule-engine-testkit` | `Rules` builder, `Engine`/`FiringSequence`, `MatcherEquivalence`, `ShuffleHarness`, JMH benchmarks — **main** source set, not test: consumers use these |

Dependencies are declared in `gradle/libs.versions.toml`. `-core`'s runtime deps are exactly
jackson (the fact model is JSON-native) and re2j (rule-authored `matches` must not backtrack
catastrophically); both are `api` because they appear in public signatures. Adding a `-core`
dependency is a design decision, not a convenience.

`-dsl` adds jackson-dataformat-yaml and networknt json-schema-validator; `-schema` adds networknt
too. Both are `implementation` and neither reaches `-core`, which is the point of the SPI split
below.

**This engine is on Jackson 3 (`tools.jackson.*`), not Jackson 2.** networknt tracks it on the 3.x
line; the projects maintain 2.x and 3.x in parallel, so the pin follows our tree model rather than
their support window. The move was made while the project was still unreleased and deliberately so:
`JsonNode` appears in ~60 public signatures and `-core` declares jackson `api`, so after a first
publish this would break every consumer at once with no gradual path.

**The trap when touching Jackson code here.** Jackson 3 made the typed accessors *strict*.
`stringValue()`, `intValue()`, `longValue()`, `doubleValue()`, `booleanValue()` and
`decimalValue()` now **throw** on a type mismatch where Jackson 2's `textValue()`/`intValue()`
returned `null`/`0`/`false` — and so does the coercing `asX()` family (`asString()`, `asBoolean()`,
`asInt()`, `asDouble()`, `asDecimal()`), which is easy to miss because coercion sounds total.
`Comparisons` calls `asBoolean()` on the matching hot path; it is safe only because the compiler
rejects a non-boolean `HAS_FIELD`/`IS_NULL` literal *and* forbids both as join operators. Most of those kept their names, so the compiler says nothing — a
missing type guard is a runtime throw on the matching path, not a wrong answer. Every call site in
main source is guarded (`isString()`, `isNumber()`, or a compile-time rejection), and it must stay
that way. Where Jackson 2's null-returning behaviour is what you want, the one-argument form
(`stringValue(null)`) is the equivalent. `asString()` throws on objects and arrays too, where
`asText()` returned `""` — that one bit `RuleFileReader`'s `apiVersion` diagnostic, on untrusted
input; see `RuleFilesTest.ApiVersionShape`.

**Container `EQ` no longer delegates to `JsonNode.equals`** — see the §2.6.1 amendment. Jackson 3's
`DecimalNode` equality is scale-sensitive where Jackson 2's was not, which made `100.00` and `100.0`
unequal *inside* a container while `Canonical` kept them equal as scalars. `Comparisons` walks
containers itself now, comparing numbers through `Canonical` at every depth.

**Rule-set version hashes did not move.** `RuleCompiler.version()` hashes a canonical string built
from `rule.when()`/`rule.then()`, whose records render their `JsonNode`s via `toString()` — and
Jackson 3's `toString()` is byte-identical to Jackson 2's for every node type this engine produces
(objects, arrays, all scalars, and `BigDecimal` trailing zeros). Verified directly against both jars
before the migration was committed, because §5.6's hot reload, refraction and `RuleSetFingerprint`
all key on that identity.

## Architecture

### The two-tier split

`CompiledRuleSet` is immutable, thread-safe, and shared by everything. `RuleSession` is
single-writer, cheap to allocate, and never shared across threads — one virtual thread per session
is the concurrency primitive (§5.2). `halt()` is the only method legal to call from another thread.

The shared graph holds structure and plans; everything it *stores* lives in the session's
`SessionMemories`, a `NodeMemory[]` indexed by node id. Nothing may be added to `CompiledRuleSet`,
`Network`, or a node that mutates after compile.

### Two matchers, held to agreement

Both are subclasses of `RecomputingAgenda`, which owns everything that decides *which* activation
fires — dirty tracking, the recomputation loop, refraction at selection, the conflict-resolution
comparator, strict-mode checks. Subclasses supply only `matchesOf(rule, ...)`: how matches are
found. Keeping the divergence-capable code in one place is what makes the two matchers agree.

- **`NaiveAgenda`** (`naive/`, Phase 0) — no network, no indexes, `O(rules × facts^arity)`. It is
  the **correctness oracle** and is deliberately still shipped. Selected with
  `SessionOptions.matching(MatchingStrategy.NAIVE)`. Never in production.
- **`ReteAgenda`** (`rete/`, Phase 3, in progress) — joins materialised as facts arrive, in
  `BetaMemory`, instead of recomputed per fire. Selected with `MatchingStrategy.RETE`, for
  long-lived streaming sessions. Shares the join walk with `NetworkAgenda` via `JoinEnumerator` —
  a pinned position makes the incremental result a subset of the full one *by construction*, which
  is what §9's "TREAT and Rete produce identical firing sequences" rests on. Its conflict set is
  **pushed and pulled** rather than rebuilt (§4.3): a match enters when derived, leaves when it
  fires, and a fire cycle ranks what is waiting rather than everything held. That is what makes it a
  better curve and not merely a constant — the fire cycle stopped growing with the working set — and
  it is why `pendingByRule` must never be allowed to hold a match that has fired. A §6.4 `condition`
  is the exception: rejected matches are never fired, so they are never pulled, and the set drifts
  toward the join memory. See `docs/benchmarks.md`.
- **`NetworkAgenda`** (`network/`, Phases 1–2) — the default. `EntryNode` (per fact type) →
  shared `AlphaNode`s (one per *distinct* constraint) → `PatternNode` + its `PatternMemory` →
  indexed joins ordered per fire cycle by `JoinPlan` (smallest memory first, connected before
  disconnected).

Any change to matching must keep the two identical. `MatcherEquivalence.assertEquivalent` compares
whole firing *sequences* — which rule, on which facts, in what order, with what effects and events.
Use it for new matching behaviour; `ShuffleHarness` covers §7.3's determinism contract.

### Invariants that produce silently-wrong output when broken

- **Tuples bind `FactHandle`s, never `Fact` objects.** Payloads are dereferenced from working
  memory at read time, so nothing downstream can serve a stale one. Audit this whenever a node type
  is added.
- **Insert evaluates tests; retract never does.** A retract removes by handle identity and computes
  its index-removal keys from the payload the fact had *when asserted*. Re-deriving membership from
  current data leaves entries behind and produces phantom matches forever.
- **The index is a pure optimisation.** Probe results are intersected with actual pattern
  membership and every join is re-evaluated, so a too-wide index is slow. A too-narrow one is a lost
  firing — which is why a probe that cannot prove itself safe must decline (`no index usable`)
  rather than return zero candidates.
- **Determinism.** Same rule set, same facts, same insertion order → same firing sequence, on every
  host and run. The threat that actually bites is hash iteration order reaching the agenda; prefer
  `LinkedHashMap`/`LinkedHashSet`/sorted structures on any path to the agenda. This is also what
  makes `exportFacts()`/`SessionDrain.replay` order by handle id rather than however a map iterates.
- **Nothing in a `CompiledRuleSet` mutates after compile** (§5.5, invariant 1). Every scaling claim
  rests on it, and it is *not* free: `FieldConstraint`, `RangeConstraint` and `Literal` deep-copy
  their `JsonNode` on the way in but hand back the live node, so a caller reaching a literal through
  `CompiledRule.source()` can mutate a node every session reads. Copying on the way out is not
  available — the matching path calls `literal()` per fact per test. `RuleSetFingerprint` hashes
  every mutable value at compile time and `newSession(strict)` re-verifies, so violators fail in
  test; outside strict mode it stays a caller-facing contract. See `ImmutabilityTest`.

### The DSL front end

Rule-file text → POJO tree → `RuleDefinition` → the existing `RuleCompiler`. The DSL builds no
network and re-implements no semantic validation. Three gates, and **duplicating one in another is
how they drift apart**:

1. **`rules.v1.json`** (in `-dsl` resources) — structure only: required keys, value types, unknown
   keys, which keys each action verb accepts. Runs first and hard-stops.
2. **`OperatorMaps` / `Actions` / `References`** — what a schema cannot say: `$ref` vs the `$$`
   escape vs a rejected `$`-key, a `between` with no bound, a malformed `alias.field`.
3. **`RuleCompiler`, unchanged** — meaning: forward refs, unknown aliases, duplicate ids, regexes,
   function names.

`DslError.shieldedBySchema()` marks the codes gate 1 catches first. Those checks stay in gate 2
anyway — "the gate ahead of me guarantees this" is how a loosened schema becomes a silently dropped
constraint — and `DslDiagnosticsTest` asserts both that they are unreachable end-to-end and that
their components still raise them.

`RuleFiles` re-decorates `RuleCompilationException`'s diagnostics with file/line/column by matching
the prefixes the compiler writes (`"<ruleId>: <alias>.<field>: …"`). That coupling is deliberate —
it keeps the cost on the side that wants the feature — and `DslDiagnosticsTest` is what notices if
the compiler rewords itself.

**`DslEquivalence` (in `-testkit`) is the DSL's oracle test**, mirroring `MatcherEquivalence`: a
rule file and the same rule built with `Rules` must produce an identical rule-set *version hash* and
an identical firing sequence. The hash half is the strong one — it caught both defects this module
surfaced (`RangeConstraint`'s un-normalised inclusivity, and the testkit builder emitting
`FieldConstraint(GT)` where §6.2.1 says `RangeConstraint`).

### Optional modules plug in through a `-core` SPI

`-schema` and `-cel` follow the pattern `TestedPaths`, `HostFunction` and `EventSink` already use: **`-core` declares an interface, an optional module implements it, and it
is wired in through `CompilerOptions` and frozen into the `CompiledRuleSet`.** `-core` gains no
dependency either time.

`FactSchemas` (§2.3) is a **documented deviation** from the spec's sketch, which returns networknt's
`JsonSchema` and would put that library on every consumer's classpath. It answers in this engine's
own vocabulary instead, and answers `UNKNOWN`/empty wherever schema introspection stops being simple
(`$ref`, `allOf`, `oneOf`) — an unmade check costs what you had before registering a schema, where a
guessed one would reject a correct rule. Validation has no such limit.

**A `condition:` makes the paths it reads tested paths (§3.4.1), and the cost is behavioural.**
`RuleCompiler.compileCondition` records the payload *root* for every fact type an alias the condition
references binds — conservative on purpose, because extracting exact read paths from the CEL AST
would make the compiler responsible for being a superset of what an arbitrary expression reads, and
under-declaring loses a firing silently (§11.2's rejected `dependsOn()` trap).

Because the root is tested, **any** update to a fact the rule binds un-refracts it, including a field
no rule reads: the rule re-fires, and a rule whose RHS mutates its own facts goes from firing once to
hitting `maxCycles`. `noLoop` restores it. That is a real semantics consequence, not a slow path, and
it is why the §6.4 amendment states it beside the argument rather than as a performance note.

Until Phase 3 nothing was recorded at all, so an update that made a condition newly true fired
nothing. **No differential test could catch that**: the update gate is upstream of the matcher, so
every matcher was identically wrong and `MatcherEquivalence` only proved they agreed. Worth
remembering whenever equivalence testing is the argument for correctness here.

**CEL (§6.4) evaluates in two places, and one of them is a structural decision.** A pattern
`condition:` is a post-filter applied in **`RecomputingAgenda`, the shared base — not in either
matcher**. Everything that decides which activation fires already lives there so the two matchers
cannot diverge, and an expression is exactly what would drift if written twice; this way
`MatcherEquivalence` holds by construction. An `$expr` value resolves in `RhsExecutor.resolve`, once
per firing rather than once per candidate.

Note where reality departs from §6.4: it says dev.cel "ships a static cost estimator and a runtime
cost limit — set both". As of 0.14.0 it ships neither. `CelExpressions` uses its own structural
estimate at compile time and dev.cel's `comprehensionMaxIterations`/parse limits at run time, and
says so. Determinism is a property of that environment: CEL's standard set has no clock, and
`CelExpressions` binds only the tuple's aliases — adding a binding there is a §7.3 decision.

### Update, refraction, RHS

`update` is retract + reassert on the same handle, **gated on a tested-path diff** (§3.4.1): if no
path any rule tests changed, it propagates nothing, and `DefaultWorkingMemory` exposes counters that
tests assert on. Refraction is cleared for exactly the rules testing a changed path.

RHS execution is stage-everything-then-commit (§4.6), five verbs only (`setField`, `insert`,
`retract`, `emit`, `callFunction`). Atomicity is **per-phase**: a staging failure applies nothing;
a commit failure leaves what already landed, and `FireRecord` is how that partial state is
discoverable. Under the default `RETHROW` policy the record only reaches a registered listener.

### Strict mode

`-Drules.strict=true` (or `SessionOptions.strict(true)`) turns on checks too expensive for
production that fail deterministically in test: payload copies on the way out, rejection of an
`update` that aliases the stored payload, an assertion that conflict resolution is a total order
consistent with equality, and a re-check of the compiled rule set's literal fingerprint at session
creation. §7.5 requires the full suite under it in CI and forbids it in production.

### Concurrency and hot reload (`-core`, `concurrent/`)

In `-core` rather than its own module because §8 says so directly: a few hundred lines with no
dependencies beyond the JDK, where a module boundary buys nothing and makes "how do I run this
concurrently" an extra artifact to discover.

- **`RuleBatches`** — one virtual thread and one session per batch. Returns a `BatchOutcome` per
  batch carrying *either* a result or a failure, because §5.2 refuses to decide for you what a
  partial batch result means. Sessions are created inside the task and closed in try-with-resources;
  one escaping to the caller would break the single-writer model.
- **`RuleSetHolder`** — §5.6's hot reload. One volatile field, no locks. Two contracts worth knowing
  before changing it: `publish` takes a *compiled* rule set so a bad rule file cannot take the engine
  out of service, and a swap affects new sessions only.
- **`SessionEvictor`** (in `session/`, with the policy SPI in `evict/`) — §4.4's fact eviction, which
  bounds every structure a long-lived session grows because they are all keyed on handles. Two things
  to know before touching it: an eviction is an ordinary `retract` and must stay one — reaching into
  the memories by hand makes it a fifth place they are removed from — and **it may only run at
  quiescence**. The policy is consulted after a caller's insert and at the top of a fire cycle, never
  between §4.6's staging and commit, where it could retract a fact the firing activation binds. A
  policy must also be a pure function of what it is shown; strict mode calls it twice and compares,
  because a clock or a `HashMap` in there is a §7.3 violation that only shows on another host.
- **`SessionDrain`** — drain-and-restart for a session already running when the rules changed. Two
  things it must keep doing: replay in handle-id order (§7.3's guarantee is stated in terms of
  insertion order) and skip `Origin.DERIVED` facts (the new session re-derives them; replaying would
  double-count). Refraction state is deliberately *not* carried over — the handles are new.

## Semantics that surprise people (all deliberate)

- **Absent ≠ null.** `{ eq: null }` matches an explicit JSON null, never an absent field; use
  `hasField: false` for absence.
- **`ne` is true for an absent field**, because `ne` is defined as `!eq`. Pair with `hasField: true`.
- **`in` is `eq` against each element** (§2.6.1).
- **Distinct aliases in one rule bind distinct facts.** The compiler inserts an implicit inequality
  between same-type aliases — and `JoinPlan` symmetrises it, because either end may be bound first.
- **Collections are flattened at ingestion**, not matched inside a fact. JSON Pointer has no
  wildcard.

## Conventions

- Java 25, `final` on parameters and fields, records for the rule/constraint AST, package-info.java
  in every package.
- Comments explain *why*, cite the spec section, and name the alternative that was rejected. Match
  that density; a comment restating the code is not the house style.
- Defects found by review get a reproducing test in `ReviewRegressionTest` before the fix.
- Doc examples are fixtures, not prose. A rule file in a `.md` gets compiled by `DocExamplesTest`.
- Commit messages: an imperative subject, then prose paragraphs explaining the defect, why it
  happened, and what the fix chose — not bullet lists of files touched.
