# Security policy

## Reporting a vulnerability

Report a vulnerability privately rather than in a public issue:

- GitHub private advisory: https://github.com/codeheadsystems/rule-engine/security/advisories/new
- Email: ned.wolpert@gmail.com

Expect an acknowledgement within a week. The project has one maintainer and no SLA; mark an urgent
report as urgent in its subject line.

## Threat model

Three parts of the rule engine consume input that a threat model should treat as
attacker-influenced, even though rule files are usually written by trusted authors:

- Rule files: `RuleFiles.compile` parses YAML and JSON from wherever the host gives it. A rule file
  is configuration that behaves like code. It is meant to be reviewed like code, and a deployment
  that loads rule files from an untrusted source is outside the design's assumptions.
- Fact payloads: arbitrary JSON, from the host's callers.
- The CEL escape hatch (`rule-engine-cel`, §6.4): expressions are non-Turing-complete and guaranteed
  to terminate, and `CelExpressions` applies a structural cost estimate at compile time plus
  dev.cel's comprehension and parse limits at run time. That bounds a single evaluation. It does not
  bound how many times the engine runs one.

Two risks are already designed against, and knowing them separates a bug from a decision:

- Regular expressions in rules compile with RE2, not `java.util.regex`. A rule-authored pattern like
  `(a+)+$` would pin a carrier thread on a backtracking engine. RE2 is linear in the input and
  cannot backtrack catastrophically (§2.6.3).
- `maxCycles` and `maxFacts` bound the work a fire call may do. They do not bound wall time, which
  is a documented open decision; see
  [`docs/embedding.md`](docs/embedding.md#work-limits-and-wall-clock-time). A fire call on a request
  path needs a watchdog against `halt()`.

## Supported versions

Only the latest published version. There are no maintenance branches; a fix ships as a new patch
release. [`RELEASING.md`](RELEASING.md) has the release process.

## Dependencies

`rule-engine-core` depends on exactly Jackson and RE2/J. `rule-engine-dsl` adds
`jackson-dataformat-yaml` (the YAML parser, which is the most security-relevant thing on the
rule-file path above) and networknt JSON Schema, which `rule-engine-schema` also uses;
`rule-engine-cel` adds dev.cel.

Dependabot raises version-update pull requests for all of them (`.github/dependabot.yml`). There is
currently no automated advisory gate on the build (no dependency-check, no CodeQL, no dependency
submission), so a vulnerable transitive dependency does not fail CI. That is a gap rather than a
decision.
