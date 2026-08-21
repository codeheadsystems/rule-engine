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

// §8's module layout. -schema and -cel arrive with the optional halves of Phase 5 (§9): the
// SchemaRegistry of §2.3 and the CEL escape hatch of §6.4. Neither is needed for an author to
// write YAML, which is what Phase 5's exit criterion actually asks for.
include("rule-engine-core")
include("rule-engine-compiler")
// §8: ONE dsl module, not one per serialization. The entire difference between JSON and YAML
// is which Jackson factory reads the text into the identical target type (§6.1).
include("rule-engine-dsl")
include("rule-engine-observability")
include("rule-engine-testkit")
