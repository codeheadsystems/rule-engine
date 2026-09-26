# Releasing

How a version of the rule engine gets to Maven Central. The mechanism is the same one
[`hofmann-elimination`](https://github.com/codeheadsystems/hofmann-elimination) uses, and the two
projects share the `com.codeheadsystems` namespace and its credentials.

## Contents

- [Published artifacts](#published-artifacts)
- [The release process](#the-release-process)
- [One-time setup](#one-time-setup)
- [Verification without publishing](#verification-without-publishing)
- [Versioning](#versioning)
- [Troubleshooting](#troubleshooting)

Two documents are part of releasing, and nothing fails without them:
[`CHANGELOG.md`](CHANGELOG.md), which is the only thing telling a consumer whether to upgrade, and
[`SECURITY.md`](SECURITY.md), which is where a vulnerability report goes.

## Published artifacts

Eight artifacts, all under `com.codeheadsystems`, all at the same version:

| Artifact | Contents |
|---|---|
| `rule-engine-core` | fact model, working memory, all three matchers, agenda, sessions |
| `rule-engine-compiler` | rule definitions to an immutable compiled rule set |
| `rule-engine-dsl` | YAML and JSON rule files; the one most consumers need |
| `rule-engine-schema` | optional: fact schemas (§2.3) |
| `rule-engine-cel` | optional: the expression escape hatch (§6.4) |
| `rule-engine-observability` | tracing, Flight Recorder, and the match explainer |
| `rule-engine-testkit` | the naive oracle and the equivalence and shuffle harnesses |
| `rule-engine-bom` | a POM only: the seven above, plus the Jackson version they are built against |

`rule-engine-example` is not published. It is a worked application. An artifact on Central is a
promise to keep something compiling for whoever depends on it, and nobody should be depending on the
example; it exists to be read and run in the repository.

A module publishes because it applies `buildlogic.publish-conventions`. That is the only switch;
adding a module to the build does not publish it. `PublishedModulesTest` holds the list, and the
release workflow's `EXPECTED` count has to move with it. The two are independent checks, so a new
module means editing both.

`rule-engine-bom` takes its Jackson version from the catalog's `jackson` entry, the same one the
modules compile against, so a Jackson bump moves the BOM with it and there is nothing to edit for it
at release time.

## The release process

The tag drives everything. `settings.gradle.kts` asks `git describe --tags --exact-match HEAD` on
every build: if HEAD carries a `vX.Y.Z` tag, that becomes the version for every module. Otherwise
the `-SNAPSHOT` version in `gradle.properties` stands. So there is no commit that "sets the release
version" and no window where a file and a tag disagree.

### The normal path

```bash
git checkout main && git pull
./gradlew clean build javadoc          # what CI runs; strictTest included

# Write the entry BEFORE tagging: the tag is what publishes, and a version that ships without a
# changelog entry is one nobody can decide whether to upgrade to.
$EDITOR CHANGELOG.md

git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0
```

Pushing the tag starts [`.github/workflows/release.yml`](.github/workflows/release.yml), which:

1. refuses to go on unless the tagged commit is an ancestor of `origin/main`
2. checks the tag is really `vX.Y.Z`, and that Gradle resolved the same version from it
3. builds and tests, including `strictTest` (§7.5) and Javadoc, which is a published artifact
4. imports the GPG key and proves it can sign before relying on it
5. asserts all eight modules are about to publish, signed, at that version, then uploads one
   aggregated deployment
6. pushes the tag, once the artifacts are actually on Central
7. shreds the signing key and the passphrase file from the runner (the Central token is only ever an
   environment variable on the publish step, and never reaches disk)
8. creates the GitHub release

The workflow takes fifteen minutes or so. Artifacts appear on Central 15 minutes to 2 hours after
that.

The deployment publishes automatically once Central validates it, which is nmcp's default. For a
release a human should confirm, `publishingType = "USER_MANAGED"` on the `centralPortal` block in
`settings.gradle.kts` makes the deployment wait in the portal's Deployments view until somebody
clicks publish.

### Releasing from the Actions tab

The same workflow has a `workflow_dispatch` trigger. It takes a version, or a blank field for the
next patch after the newest tag. It creates the tag itself: locally first, so the build sees it, and
pushed only after the artifacts are on Central. Tests passing is not shipping: a tag on
the remote with no release behind it costs somebody an investigation, and a missing tag costs
`git tag && git push`.

There is one workflow rather than a separate manual-release file. A tag pushed with `GITHUB_TOKEN`
does not trigger another workflow, so two entry points would mean two copies of the
signing-and-publishing sequence, and two places to get a credential-handling change only half
right.

### Version numbers after a release

Four files carry a version number, and they move in opposite directions:

- `gradle.properties` moves forward, to the next `-SNAPSHOT`: after releasing 1.0.0,
  `1.0.1-SNAPSHOT`.
- `README.md`, `docs/embedding.md`, and `site/index.html` hold dependency snippets that name the
  version just released, not the next one.

Nothing enforces any of them. The first ensures that a build from `main` is never mistakable for a
build of the version that just shipped. The snippets are what a reader copies, and the Maven Central
badge above the snippet reads the real latest version, so a stale number is visibly stale rather
than quietly wrong.

## One-time setup

This is already done for this repository if `hofmann-elimination` can release: the credentials are
organisation-level and shared. The list below serves a rotation or a new project.

### Five GitHub secrets

| Secret | What it is |
|---|---|
| `CENTRAL_PORTAL_USERNAME` | user-token username from https://central.sonatype.com, not the portal login |
| `CENTRAL_PORTAL_PASSWORD` | user-token password, not the portal login |
| `GPG_PRIVATE_KEY` | the signing key, exported and base64-encoded |
| `GPG_PASSPHRASE` | that key's passphrase |
| `GPG_KEY_ID` | the short key id |

The Central Portal token is generated at https://central.sonatype.com under Account → Generate User
Token. It is not the login password, and using the login credentials produces a 401 that reads like
a wrong password.

### The namespace

`com.codeheadsystems` is verified on the Central Portal. Nothing needs doing per project: a verified
namespace covers every artifact under it.

### The signing key

```bash
# --full-generate-key, not --gen-key: the short form does not offer a key type or a size
gpg --full-generate-key                         # RSA and RSA, 4096
gpg --list-secret-keys --keyid-format=long      # the id is after the slash

gpg --export-secret-keys YOUR_KEY_ID | base64 -w 0 > private-key.txt   # -> GPG_PRIVATE_KEY

# Central verifies signatures against the public keyservers, so the public half must be there
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

`private-key.txt` is deleted once it is in the secret, and is never committed anywhere.

## Verification without publishing

Everything below is safe and touches nothing outside the local machine.

### Publish configuration

```bash
./gradlew verifyPublishConfig
# com.codeheadsystems:rule-engine-core:1.0.0-SNAPSHOT  snapshot=true signed=false
# ... one line per publishing module
```

`signed=false` on a SNAPSHOT is correct: signing is required only for a release, so a developer with
no GPG key can still build and publish locally. The `Sign` tasks are skipped, not merely allowed to
fail. The distinction matters: with `useGpgCmd()`, Gradle's `isRequired = false` still runs `gpg`
and dies on "No secret key", so the convention plugin gates the tasks with `onlyIf` as well.

### Tag substitution

```bash
git tag -a v0.0.1-test -m "temporary"
./gradlew properties -q | grep "^version:"     # version: 0.0.1-test
git tag -d v0.0.1-test
```

### Artifact completeness

Central rejects a deployment missing a sources jar, a javadoc jar, or any of
name/description/url/license/developer/scm on the POM:

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/codeheadsystems/rule-engine-core/*-SNAPSHOT/
```

The directory holds the jar, `-sources.jar`, `-javadoc.jar`, `.pom`, and `.module`, plus `.asc` for
each when a GPG key is configured. `rule-engine-bom` has only the `.pom` and `.module`: it is pom
packaging, for which Central requires no jars. Its `.pom` should import
`tools.jackson:jackson-bom` in `dependencyManagement` and manage the seven library modules at this
version.

### Central bundle completeness

This checks the bundle without uploading it:

```bash
./gradlew nmcpZipAggregation nmcpCheckAggregationFiles
unzip -l build/nmcp/zip/aggregation.zip
```

### Signing

This check needs a GPG key. A passphrase in `~/.gradle/gradle.properties` sits on disk in plaintext,
so the file is restricted with `chmod`, and the passphrase is better left out so that the gpg agent
prompts for it:

```bash
touch ~/.gradle/gradle.properties && chmod 600 ~/.gradle/gradle.properties
cat >> ~/.gradle/gradle.properties << 'EOF'
signing.gnupg.keyName=YOUR_KEY_ID
EOF

./gradlew signMavenJavaPublication
find . -name '*.asc' | head
```

A backslash in a `.properties` value is an escape character, so a passphrase containing one has to
be doubled. The release workflow does that automatically; a hand-edited file does not.

## Versioning

[Semantic versioning](https://semver.org/), and all eight artifacts move together.

- Major: a breaking change to anything in the API surface `ApiSurfaceTest` calls exported.
- Minor: new capability; existing code keeps compiling.
- Patch: fixes.

Two project decisions bear on major versions, both recorded in `CLAUDE.md`:

- `JsonNode` is in roughly sixty public signatures and `rule-engine-core` declares Jackson `api`. A
  Jackson major upgrade is a major version here, and there is no gradual path, which is why the move
  to Jackson 3 was made before the first publish rather than after it.
- The API boundary is a test, not a `module-info` (§8.1). `ApiSurfaceTest` names the exported
  packages; widening that list is an explicit edit, and after 1.0.0 it is a compatibility decision
  as well.

Pre-release tags work: `v1.1.0-rc.1` publishes and is marked as a prerelease on GitHub.

## Troubleshooting

### `Gradle resolved version X, the tag says Y`

The tag is not on `HEAD`, or the working tree is not at the tagged commit. This shows the tag on
`HEAD`, if there is one:

```bash
git describe --tags --exact-match HEAD
```

### 401 from the Central Portal

`CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD` hold portal login credentials rather than a
generated user token. The fix is a new token from https://central.sonatype.com → Account → Generate
User Token.

### `gpg: signing failed: No secret key`

`GPG_KEY_ID` does not match the key inside `GPG_PRIVATE_KEY`, or the key never imported. The
workflow's import step test-signs specifically so this fails on its own step, with a message that
says so, rather than surfacing from inside the publish task. It cannot fail fast: the key must not
be on disk while the test suite runs, so the import sits after the build.

### Failed deployment validation

https://central.sonatype.com → Deployments gives the reason. In practice it is one of: a missing
sources or javadoc jar, an incomplete POM, or a signature Central cannot verify because the public
key was never sent to a keyserver.

Nothing is published when validation fails, so the version is still free. The recovery is to fix the
problem, delete the tag, and tag again. The remote deletion applies only to a tag pushed by hand,
because on the Actions-tab path the tag is pushed after the upload and so never reached the remote:

```bash
git tag -d v1.0.0
git push --delete origin v1.0.0   # only if you pushed it
```

### A defective published release

Maven Central does not allow unpublishing. A released version is permanent and its bytes cannot be
replaced. The remedy is a patch release; for something serious, the GitHub release is also marked as
a prerelease, with the reason in its notes.

That permanence is why the workflow checks the version twice, refuses to publish from a commit that
is not on `main`, asserts that all eight modules are signed and at the right version before
uploading anything, and pushes the tag only once the artifacts are on Central.
