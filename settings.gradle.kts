pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Automatic download of the JDK 25 toolchain the build requires (docs/rule-engine-spec.md §5).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"

    /*
     * Publishing to Maven Central through the Central Portal. A SETTINGS plugin rather than a
     * project one, because the thing being published is the build as a whole: it applies the
     * aggregation plugin to the root and gives every publishing module one upload, one deployment
     * and one validation result. Seven separate uploads would be seven deployments that can each
     * half-succeed.
     */
    id("com.gradleup.nmcp.settings") version "1.6.2"
}

rootProject.name = "rule-engine"

// §8's module layout. -schema and -cel arrive with the optional halves of Phase 5 (§9): the
// SchemaRegistry of §2.3 and the CEL escape hatch of §6.4. Neither is needed for an author to
// write YAML, which is what Phase 5's exit criterion actually asks for.
include("rule-engine-core")
include("rule-engine-compiler")
// §8: ONE dsl module, not one per serialization. The entire difference between JSON and YAML
// is which Jackson factory reads the text into the identical target type (§6.1).
include("rule-engine-dsl")
// §8: the optional SchemaRegistry of §2.3. Beside -dsl because they share the JSON
// Schema tooling -- one gate points at rule files, the other at fact payloads.
include("rule-engine-schema")
// §8: the CEL escape hatch of §6.4. Its own module because CEL brings protobuf, guava
// and antlr, and none of that belongs near a rule set that never writes an expression.
include("rule-engine-cel")
include("rule-engine-observability")
include("rule-engine-testkit")
/*
 * Not a library. §8's table is the engine; this is the worked example of using it -- one
 * application, one rule file, one feed of events, and the three deployment shapes §5 describes.
 * It is in the build rather than in a gist because a teaching artifact that is not compiled and
 * fired by CI is a teaching artifact that is wrong within two releases.
 */
include("rule-engine-example")

/*
 * Central Portal credentials. These are USER TOKEN credentials generated at
 * https://central.sonatype.com, not portal login credentials, and they are shared across the
 * codeheadsystems namespace -- the same pair hofmann-elimination publishes with. See RELEASING.md.
 *
 * Environment first so CI supplies them without touching a file, Gradle property second so a
 * local publish can put them in ~/.gradle/gradle.properties. Neither is required to build: a
 * missing credential fails the publish task, not the configuration phase.
 */
nmcpSettings {
    centralPortal {
        username = System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: providers.gradleProperty("centralPortalUsername").orNull
        password = System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: providers.gradleProperty("centralPortalPassword").orNull
    }
}

/*
 * The released version comes from the Git tag, and nowhere else.
 *
 * gradle.properties carries the *next* version with a -SNAPSHOT suffix, which is what a working
 * tree builds. A tag of the form vX.Y.Z on HEAD overrides it, so a release is exactly "what was
 * built from this commit" and there is no window where a number in a file and a number in a tag
 * disagree. The release workflow re-checks the two against each other anyway, because a silent
 * mismatch would publish the wrong coordinates permanently.
 *
 * beforeProject rather than in the root build script: every project needs it, and setting it in
 * `allprojects` would run AFTER this and quietly win.
 */
gradle.beforeProject {
    val tag = providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

    if (tag.startsWith("v")) {
        val semver = Regex("^v(\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9.]+)?)$").matchEntire(tag)
        if (semver != null) {
            version = semver.groupValues[1]
            // Once, not once per project: this block runs for all nine and nine identical lines at
            // the top of a release log is noise around the one number worth reading.
            if (this == rootProject) {
                logger.lifecycle("Using version from Git tag: $version")
            }
        } else if (this == rootProject) {
            // Also once. This is the branch that signals a mistake, so it is the one that most needs
            // to be readable rather than repeated nine times.
            logger.warn("Git tag '$tag' is not vX.Y.Z; keeping the version from gradle.properties")
        }
    }
}
