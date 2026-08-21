pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Automatic download of the JDK 25 toolchain the build requires (docs/rule-engine-spec.md §5).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rule-engine"

// §8's module layout. -dsl, -schema, -cel and -observability arrive with the phases that need
// them (§9: Phase 5 for the DSL front-end, Phase 1 for the tracing listeners).
include("rule-engine-core")
include("rule-engine-compiler")
include("rule-engine-testkit")
