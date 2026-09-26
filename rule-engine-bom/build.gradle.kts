/*
 * The engine's bill of materials: its own modules, and the Jackson it was built and tested against.
 *
 * Why Jackson belongs in it at all: `JsonNode` is the fact type (-core declares jackson `api`), so a
 * consumer's JsonNode goes into RuleSession.insert() unconverted and the two sides have to agree on
 * the version. A consumer copying that version by hand is a second place it is written, and the one
 * that goes stale. Importing this makes the engine the only source of it, including for a consumer
 * module that depends on no engine module and so would never see -core's transitive jackson-databind.
 *
 * Nothing else goes in it, beyond what jackson-bom itself manages -- which includes one com.fasterxml
 * coordinate, jackson-annotations, because Jackson 3 uses Jackson 2's annotations (embedding.md says
 * so). slf4j, dev.cel and networknt are implementation details of the modules that use them, and
 * pinning them here would turn every consumer's resolution of those libraries into this project's
 * decision -- which is a compatibility promise nobody made.
 *
 * The version appears once: this reads the catalog's `jackson` ref, the same one the modules compile
 * against, so the BOM cannot name a Jackson the build did not test.
 */
plugins {
    `java-platform`
    id("buildlogic.publish-conventions")
}

description = "Rule engine BOM: aligns the engine's modules and the Jackson 3 version they are built against"

/*
 * Not for the BOM's own dependencies, which nothing here resolves. nmcp resolves its task jar from
 * each publishing project's repositories, and the library modules get theirs from the java
 * conventions this module does not apply. Without it the aggregated Central upload fails on this
 * module alone ("no repositories are defined") -- which is to say at release time, since nothing
 * else in the build touches nmcp. Found by `nmcpZipAggregation`, the dry run RELEASING.md describes.
 */
repositories {
    mavenCentral()
}

javaPlatform {
    // Required for the platform() import below; without it java-platform accepts constraints only.
    allowDependencies()
}

dependencies {
    // A POM import in the published .pom, and a platform dependency (org.gradle.category=platform)
    // in the .module -- which is how both Maven and Gradle consumers inherit jackson-bom's whole set,
    // com.fasterxml's jackson-annotations included, rather than the two artifacts the engine names.
    api(platform(libs.jackson.bom))

    constraints {
        // Every module PublishedModulesTest calls published, except this one. Not rule-engine-example:
        // it is not on Central, so a constraint on it would name coordinates that do not resolve.
        api(project(":rule-engine-core"))
        api(project(":rule-engine-compiler"))
        api(project(":rule-engine-dsl"))
        api(project(":rule-engine-schema"))
        api(project(":rule-engine-cel"))
        api(project(":rule-engine-observability"))
        api(project(":rule-engine-testkit"))
    }
}
