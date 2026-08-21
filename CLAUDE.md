# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## The spec is the source of truth

`docs/rule-engine-spec.md` (1800 lines) specifies this engine in full. The code implements it
section by section, and comments cite section numbers (`§3.4.1`, `section 6.5`) as their
justification. Before changing matching, agenda, update or RHS semantics, read the section that
governs it — the answer to "why is it written this way" is almost always there, along with the
alternatives that were rejected. If the code and the spec disagree, one of them is a defect; decide
which and say so, do not silently pick.

`README.md` documents what is built — Phases 0–2 (= v1) plus Phase 5's DSL front end — and what is
not: streaming sessions and Rete joins (Phase 3), concurrency helpers and hot reload (Phase 4), and
Phase 5's two optional halves, the CEL escape hatch (§6.4) and `SchemaRegistry` (§2.3). §9 holds the
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
below. networknt is pinned to the **2.x** line on purpose: 3.x moved to Jackson 3
(`tools.jackson.*`), a different tree model from the `com.fasterxml` `JsonNode` this engine is built
on. Following it there is gated on the whole project moving to Jackson 3.

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
  `LinkedHashMap`/`LinkedHashSet`/sorted structures on any path to the agenda.

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
`update` that aliases the stored payload, and an assertion that conflict resolution is a total order
consistent with equality. §7.5 requires the full suite under it in CI and forbids it in production.

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
