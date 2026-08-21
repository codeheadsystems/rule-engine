plugins {
    id("buildlogic.java-library-conventions")
}

description = "Rule engine schema: the optional FactSchemas of §2.3, backed by JSON Schema"

dependencies {
    api(project(":rule-engine-core"))

    /*
     * §8 puts this module next to -dsl rather than next to -core precisely because they share this
     * dependency: the rule-file gate and the fact-payload gate are the same tooling pointed at two
     * different documents. Pinned to the 2.x line for the reason libs.versions.toml gives.
     */
    implementation(libs.json.schema.validator)

    testImplementation(project(":rule-engine-compiler"))
    testImplementation(project(":rule-engine-testkit"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
