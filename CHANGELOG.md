# Changelog

What changed between releases, for somebody deciding whether to upgrade. The mechanism of a release
is [`RELEASING.md`](RELEASING.md); this file is the communication half.

Versions follow [semantic versioning](https://semver.org/). All seven published artifacts move
together — a release tags one version and publishes them in a single deployment. What a major
version protects is the API surface `ApiSurfaceTest` calls exported.

## 1.0.0 — 2026-08-24

First published release. Everything below is what shipped in it rather than what changed, since
there is no previous version to compare against.

### The engine

- Forward-chaining rule engine over JSON-native facts, with an immutable `CompiledRuleSet` shared
  across cheap single-writer sessions.
- **Three matchers** — `NAIVE` (the correctness oracle, shipped so consumers can test their own
  rules against it), `NETWORK` (the default), and `RETE` (for long-lived streaming sessions) — held
  to producing identical firing sequences.
- The comparison surface of §2.6.1, indexed joins with per-fire-cycle binding order, refraction,
  salience and recency conflict resolution, and the gated retract-and-reassert `update`.
- **Quantifiers**: `notExists`, `forAll`, and `accumulate` with `sum`/`count`/`min`/`max`/`average`
  and an optional `having`.
- **Bounded temporal sequencing**: `after` and `before` within a required bound, reading time from
  facts because the engine owns no clock.
- **Truth maintenance**: `insertFact` with `logical: true` produces a conclusion withdrawn when the
  match that made it stops holding.
- **Concurrency**: one virtual thread per session, `RuleBatches`, `SessionActor`, `RuleSetHolder`
  hot reload, `SessionDrain`, and §4.4 fact eviction.

### Authoring and tooling

- YAML and JSON rule files against a published `rules.v1` schema, with every diagnostic naming a
  file, line and column.
- `CompilerReport` for build-time assertions; `MatchExplainer` for "why did this not fire";
  `TracingListener` and `JfrListener`.
- `rule-engine-testkit` with `MatcherEquivalence` and `ShuffleHarness`, for testing *your* rules.
- Optional `rule-engine-schema` (fact schemas) and `rule-engine-cel` (the expression escape hatch).

### Known limitations

Documented rather than hidden — see [what it deliberately does not
do](README.md#what-it-deliberately-does-not-do) and [Before you
adopt](README.md#before-you-adopt):

- **Java 25 at runtime**; the jars will not load on 17 or 21.
- **Jackson 3** (`tools.jackson`), declared `api`.
- **No wall-clock bound** on a fire call. Bring your own watchdog against `halt()`.
- **Never evict a type your rules negate, quantify over, fold, or conclude** — an evicted fact and an
  absent one are indistinguishable, and over a negated type that manufactures a false conclusion.
- Not built: `collect`, sliding windows, backward chaining, distributed evaluation.
