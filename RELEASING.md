# Releasing

How a version of this engine gets to Maven Central. The mechanism is the same one
[`hofmann-elimination`](https://github.com/codeheadsystems/hofmann-elimination) uses, and the two
projects share the `com.codeheadsystems` namespace and its credentials.

## Contents

- [What gets published](#what-gets-published)
- [How a release happens](#how-a-release-happens)
- [One-time setup](#one-time-setup)
- [Trying it without publishing](#trying-it-without-publishing)
- [Versioning](#versioning)
- [When it goes wrong](#when-it-goes-wrong)

Two documents are part of releasing and are easy to forget because nothing fails without them:
[`CHANGELOG.md`](CHANGELOG.md), which is the only thing telling a consumer whether to upgrade, and
[`SECURITY.md`](SECURITY.md), which is where a vulnerability report goes.

## What gets published

Seven artifacts, all under `com.codeheadsystems`, all at the same version:

| Artifact | |
|---|---|
| `rule-engine-core` | fact model, working memory, all three matchers, agenda, sessions |
| `rule-engine-compiler` | rule definitions to an immutable compiled rule set |
| `rule-engine-dsl` | YAML and JSON rule files — the one most consumers need |
| `rule-engine-schema` | optional: fact schemas (§2.3) |
| `rule-engine-cel` | optional: the expression escape hatch (§6.4) |
| `rule-engine-observability` | tracing, Flight Recorder, and the match explainer |
| `rule-engine-testkit` | the naive oracle and the equivalence and shuffle harnesses |

**`rule-engine-example` is not published, and that is deliberate.** It is a worked application. An
artifact on Central is a promise to keep something compiling for whoever depends on it, and nobody
should be depending on the example — it exists to be read and run in the repository.

A module publishes because it applies `buildlogic.publish-conventions`. That is the only switch;
adding a module to the build does not publish it.

## How a release happens

**Everything is driven by the tag.** `settings.gradle.kts` asks `git describe --tags --exact-match
HEAD` on every build: if HEAD carries a `vX.Y.Z` tag, that becomes the version for every module.
Otherwise the `-SNAPSHOT` version in `gradle.properties` stands. So there is no commit that "sets the
release version" and no window where a file and a tag disagree.

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
3. builds and tests — including `strictTest` (§7.5) and Javadoc, which is a published artifact
4. imports the GPG key and proves it can sign before relying on it
5. asserts all seven modules are about to publish, signed, at that version — then uploads **one
   aggregated deployment**
6. pushes the tag, once the artifacts are actually on Central
7. shreds the signing key and the passphrase file from the runner (the Central token is only ever an
   environment variable on the publish step, and never reaches disk)
8. creates the GitHub release

Fifteen minutes or so. Artifacts appear on Central 15 minutes to 2 hours after that.

The deployment publishes automatically once Central validates it, which is nmcp's default. If you
would rather have a human confirm a particular release, set `publishingType = "USER_MANAGED"` on the
`centralPortal` block in `settings.gradle.kts`; the deployment then waits in the portal's Deployments
view until somebody clicks publish.

### From the Actions tab

The same workflow has a `workflow_dispatch` trigger. Give it a version, or leave the field blank to
take the next patch after the newest tag. It creates the tag itself — locally first, so the build
sees it, and pushed only after the artifacts are on Central. Tests passing is not shipping: a tag on
the remote with no release behind it costs somebody an investigation, and a missing tag costs
`git tag && git push`.

There is deliberately **one** workflow rather than a separate manual-release file. A tag pushed with
`GITHUB_TOKEN` does not trigger another workflow, so two entry points would mean two copies of the
signing-and-publishing sequence — and two places to get a credential-handling change only half
right.

### Afterwards

Two files carry a version number, and they move in opposite directions:

- `gradle.properties` — forward, to the next `-SNAPSHOT`: after releasing 1.0.0, `1.0.1-SNAPSHOT`
- `README.md` — the dependency snippets should name the version you *just released*, not the next one

Nothing enforces either. The first buys that a build from `main` is never mistakable for a build of
the version that just shipped. The second is what a reader copies, and the Maven Central badge above
the snippet reads the real latest version, so a stale number is visibly stale rather than quietly
wrong.

## One-time setup

Already done for this repository if `hofmann-elimination` can release — the credentials are
organization-level and shared. What follows is what they are, so that a rotation or a new project has
the list.

### Five GitHub secrets

| Secret | What it is |
|---|---|
| `CENTRAL_PORTAL_USERNAME` | user-token username from https://central.sonatype.com — **not** the portal login |
| `CENTRAL_PORTAL_PASSWORD` | user-token password — **not** the portal login |
| `GPG_PRIVATE_KEY` | the signing key, exported and base64-encoded |
| `GPG_PASSPHRASE` | that key's passphrase |
| `GPG_KEY_ID` | the short key id |

The Central Portal token is generated at https://central.sonatype.com under Account → Generate User
Token. It is not the login password, and using the login credentials produces a 401 that reads like
a wrong password.

### The namespace

`com.codeheadsystems` is verified on the Central Portal. Nothing to do per project — a verified
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

Delete `private-key.txt` once it is in the secret. Do not commit it anywhere, ever.

## Trying it without publishing

Everything below is safe and touches nothing outside your machine.

**What would be published, and as what:**

```bash
./gradlew verifyPublishConfig
# com.codeheadsystems:rule-engine-core:1.0.0-SNAPSHOT  snapshot=true signed=false
# ... one line per publishing module
```

`signed=false` on a SNAPSHOT is correct: signing is required only for a release, so a developer with
no GPG key can still build and publish locally — the `Sign` tasks are *skipped*, not merely allowed
to fail. That distinction is load-bearing and this project got it wrong once: with `useGpgCmd()`,
Gradle's `isRequired = false` still runs `gpg` and dies on "No secret key", so the convention plugin
gates the tasks with `onlyIf` as well.

**That the tag substitution works:**

```bash
git tag -a v0.0.1-test -m "temporary"
./gradlew properties -q | grep "^version:"     # version: 0.0.1-test
git tag -d v0.0.1-test
```

**That the artifacts are complete** — Central rejects a deployment missing a sources jar, a javadoc
jar, or any of name/description/url/license/developer/scm on the POM:

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/com/codeheadsystems/rule-engine-core/1.0.0-SNAPSHOT/
```

You should see the jar, `-sources.jar`, `-javadoc.jar`, `.pom` and `.module` — plus `.asc` for each
if you have a GPG key configured.

**That signing works**, if you have a key. Note that this puts a passphrase on disk in plaintext —
`chmod` it, and prefer leaving the passphrase out and letting the gpg agent prompt you:

```bash
touch ~/.gradle/gradle.properties && chmod 600 ~/.gradle/gradle.properties
cat >> ~/.gradle/gradle.properties << 'EOF'
signing.gnupg.keyName=YOUR_KEY_ID
EOF

./gradlew signMavenJavaPublication
find . -name '*.asc' | head
```

A backslash in a `.properties` value is an escape character, so a passphrase containing one has to be
doubled. The release workflow does that for you; a hand-edited file does not.

## Versioning

[Semantic versioning](https://semver.org/), and all seven artifacts move together.

- **major** — a breaking change to anything in the API surface `ApiSurfaceTest` calls exported
- **minor** — new capability, existing code keeps compiling
- **patch** — fixes

Two things this project has already decided that bear on major versions, both recorded in
`CLAUDE.md`:

- **`JsonNode` is in roughly sixty public signatures and `-core` declares Jackson `api`.** A Jackson
  major upgrade is a major version here, and there is no gradual path — which is exactly why the
  move to Jackson 3 was made before the first publish rather than after it.
- **The API boundary is a test, not a `module-info`** (§8.1). `ApiSurfaceTest` names the exported
  packages; widening that list is a deliberate edit, and after 1.0.0 it is a compatibility decision
  as well.

Pre-release tags work: `v1.1.0-rc.1` publishes and is marked as a prerelease on GitHub.

## When it goes wrong

### "Gradle resolved version X, the tag says Y"

The tag is not on `HEAD`, or the working tree is not at the tagged commit. Check:

```bash
git describe --tags --exact-match HEAD
```

### 401 from the Central Portal

`CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_PASSWORD` hold portal *login* credentials rather than a
generated **user token**. Regenerate the token at https://central.sonatype.com → Account → Generate
User Token.

### "gpg: signing failed: No secret key"

`GPG_KEY_ID` does not match the key inside `GPG_PRIVATE_KEY`, or the key never imported. The
workflow's import step test-signs specifically so this fails on its own step, with a message that
says so, rather than surfacing from inside the publish task. It cannot fail *fast* — the key must not
be on disk while the test suite runs, so the import sits after the build on purpose.

### The deployment fails validation

Check https://central.sonatype.com → Deployments for the reason. In practice it is one of: a missing
sources or javadoc jar, an incomplete POM, or a signature Central cannot verify because the public
key was never sent to a keyserver.

Nothing is published when validation fails, so the version is still free. Fix, delete the tag, and
tag again — the remote half only applies if you pushed the tag yourself, because on the Actions-tab
path the tag is pushed after the upload and so never got there:

```bash
git tag -d v1.0.0
git push --delete origin v1.0.0   # only if you pushed it
```

### It published and it was wrong

**You cannot unpublish from Maven Central.** A released version is permanent and its bytes cannot be
replaced. Release a patch version; for something serious, mark the GitHub release as a prerelease and
say why in its notes.

This is the whole reason the workflow checks the version twice, refuses to publish from a commit that
is not on `main`, asserts that all seven modules are signed and at the right version before uploading
anything, and pushes the tag only once the artifacts are on Central.
