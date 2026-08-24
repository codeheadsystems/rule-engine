# Security policy

## Reporting a vulnerability

**Please report privately rather than opening an issue.**

- GitHub private advisory: https://github.com/codeheadsystems/rule-engine/security/advisories/new
- Or email: ned.wolpert@gmail.com

Expect an acknowledgement within a week. This is a one-maintainer project with no SLA; if a report
is urgent, say so in the subject line.

## Why this file exists for a rule engine

Three parts of this engine consume input that a threat model should treat as attacker-influenced,
even though rule files are usually written by trusted authors:

- **Rule files.** `RuleFiles.compile` parses YAML and JSON from wherever you give it. A rule file is
  configuration that behaves like code — it is meant to be reviewed like code, and a deployment that
  loads rule files from an untrusted source is outside the design's assumptions.
- **Fact payloads.** Arbitrary JSON, from your callers.
- **The CEL escape hatch** (`rule-engine-cel`, §6.4). Expressions are non-Turing-complete and
  guaranteed to terminate, and `CelExpressions` applies a structural cost estimate at compile time
  plus dev.cel's comprehension and parse limits at run time. That bounds a single evaluation. It
  does **not** bound how many times the engine runs one.

Two things already designed against, and worth knowing so you can tell a bug from a decision:

- **Regular expressions in rules compile with RE2, not `java.util.regex`.** A rule-authored pattern
  like `(a+)+$` would pin a carrier thread on a backtracking engine. RE2 is linear in the input and
  cannot backtrack catastrophically (§2.6.3).
- **`maxCycles` and `maxFacts` bound the work a fire call may do.** They do **not** bound wall time,
  and that is a documented open decision rather than an oversight — see
  [`docs/embedding.md`](docs/embedding.md#limits-and-the-one-the-engine-does-not-enforce). If a fire
  call runs on a request path, run a watchdog against `halt()`.

## Supported versions

Only the latest published version. There are no maintenance branches; a fix ships as a new patch
release. See [`RELEASING.md`](RELEASING.md).

## Dependencies

`-core` depends on exactly Jackson and RE2/J. `-dsl` adds jackson-dataformat-yaml — the YAML parser
is the most security-relevant thing on the rule-file path above — and networknt JSON Schema, which
`-schema` also uses; `-cel` adds dev.cel.

Dependabot raises version-update pull requests for all of them (`.github/dependabot.yml`).
**There is currently no automated advisory gate on the build** — no dependency-check, no CodeQL, no
dependency submission — so a vulnerable transitive dependency will not fail CI. That is a gap rather
than a decision, and it is written down here rather than left to be assumed because a security policy
is the one document where an aspirational control reads as a false claim.
