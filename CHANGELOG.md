# Changelog

What changed between releases, for somebody deciding whether to upgrade.
[`RELEASING.md`](RELEASING.md) covers the mechanism of a release.

Versions follow [semantic versioning](https://semver.org/). All published artifacts move together:
a release tags one version and publishes them in a single deployment. What a major version protects
is the API surface `ApiSurfaceTest` calls exported.

## 1.1.0 (2026-09-26)

### Added

- `rule-engine-bom`, a new published artifact: a POM with no code that manages every engine module
  at its own version and imports `tools.jackson:jackson-bom` at the version the engine was built and
  tested against. A build imports it as `platform(...)` in Gradle or as a `dependencyManagement`
  import in Maven and no longer writes a Jackson version by hand. That matters more here than for
  most libraries, because a fact is a `JsonNode` handed to `RuleSession.insert()` unconverted, so
  the host's Jackson and the engine's are the same library. It works in a module that depends on no
  engine module too, which relying on `rule-engine-core`'s transitive `jackson-databind` does not.
  [`embedding.md`](docs/embedding.md#platform-requirements) has the three resolution details that
  decide which version actually resolves.
- Fact documents: `FactFiles` and `FactSource` in `rule-engine-dsl` read a list of typed facts from
  YAML or JSON and insert it into a session, for the facts that are not a stream (a fixture, a seed,
  a captured session). §6.1's answer for rule files (one object model, two serialisations, one
  factory choice) now covers facts too, which had been JSON-only for no better reason than that the
  rule files were built first. `docs/embedding.md` has the format, the guarantees (document order is
  insertion order, all-or-nothing loading, every fact `ASSERTED`, a repeated key is an error), and
  the three YAML details that apply to a fixture written in it.
- `Facts.yaml(...)` in `rule-engine-testkit`, beside `Facts.json(...)`.
- `EvictionPolicy.window(factType, timeField, span)`: a retention window over one fact type,
  measured by a time field on the facts themselves. It is the bound a streaming rule set usually
  wants: `perType` caps the arrival count, and the two differ by exactly the traffic spike the rules
  exist to notice. It is not the TTL §4.4 refuses: its far edge is the newest value that type
  currently holds, minus the span, so it is derived from the input and the determinism contract
  survives. The engine still owns no clock: time advances when a fact carrying a later time arrives.
- Documentation for the windowing this completes: velocity counts and the caller-advanced `Clock`
  fact in [`dsl-guide.md`](docs/dsl-guide.md#counting-things-in-a-window), and the retention half
  and the two ways to get it wrong in
  [`embedding.md`](docs/embedding.md#long-lived-sessions-and-eviction). A window in a rule and a
  window in the session are separate decisions that have to agree, and nothing checks that they do.
- Documentation for host-owned lists and reference data, answering a question that previously had no
  written answer: "can a rule check whether a value is in a list my application owns, when a rule's
  own decision may add to it and every node in a cluster must see the addition". The answer is a
  fact, looked up before the session with `member: true` or `false` so that an outage is an absence
  and not a false, flipped by `setField` so the same session sees the change, and carried out by
  `emit` for the host to persist.
  [`dsl-guide.md`](docs/dsl-guide.md#checking-a-list-your-application-owns) has the compiled recipe,
  [`embedding.md`](docs/embedding.md#host-owned-lists-and-reference-data) the host half and the
  cluster note, and the spec records in §1 why a lookup during matching is structurally off the
  table rather than deferred. Nothing in the engine changed to support it.
- [`docs/style.md`](docs/style.md), the documentation style guide: third person and present tense,
  bold only for a defined term, no em dashes, headings that are noun phrases, British spelling, and
  a terminology table. Its scope table names what it covers (the READMEs, `docs/` except the
  specification, `RELEASING.md`, `SECURITY.md`, this file from 1.1.0, and the project site) and what
  is exempt.

### Changed

- Jackson 3.2.2 → 3.2.3, a patch release on the same line. The pinned rule-set version hash
  (`CompilerValidationTest.versionHashIsPinned`) did not move, so compiled rule sets keep the
  identity hot reload and refraction key on.
- Each published POM now carries its module's own `<description>`. In 1.0.0 they all carried the
  project's generic one, because the build read the description before the module had set it.
  Metadata only; no code or dependency changed.
- The documentation and the project site are copy-edited to `docs/style.md`. The technical claims,
  benchmark figures, and rule-file examples are unchanged, and every rule file printed in the
  documentation is still compiled by the test suite. A few passages that narrated the documents' own
  history are gone, and several count mismatches are corrected.
- Headings across the documentation now label their sections, so many anchors changed; a bookmark
  to an old anchor lands at the top of the page. Every link inside the repository and on the site
  was updated. The one anchor the 1.0.0 notes link,
  `docs/choosing-this-engine.md#what-it-deliberately-does-not-do`, still resolves.
- `Facts.json(...)` now parses through the same reader as everything else, so a repeated key in a
  fixture is rejected rather than silently taking the last one. It still throws
  `IllegalArgumentException`; the message now names a line and column. A fixture that this newly
  rejects held two values for one field and was using the second.

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
do](docs/choosing-this-engine.md#what-it-deliberately-does-not-do):

- **Java 25 at runtime**; the jars will not load on 17 or 21.
- **Jackson 3** (`tools.jackson`), declared `api`.
- **No wall-clock bound** on a fire call. Bring your own watchdog against `halt()`.
- **Never evict a type your rules negate, quantify over, fold, or conclude** — an evicted fact and an
  absent one are indistinguishable, and over a negated type that manufactures a false conclusion.
- Not built: `collect`, sliding windows, backward chaining, distributed evaluation.
