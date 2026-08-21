plugins {
    id("buildlogic.java-library-conventions")
}

description = "Rule engine CEL: the §6.4 expression escape hatch, backed by dev.cel"

dependencies {
    api(project(":rule-engine-core"))

    /*
     * §6.4 chose CEL over MVEL/SpEL/Groovy deliberately: it is designed for safe expressions
     * embedded in config, is non-Turing-complete and guaranteed to terminate, and is sandboxed by
     * construction. The cost is a real dependency footprint -- protobuf, guava, antlr -- which is
     * exactly why this is its own module and why §6.4 calls the escape hatch an explicit, visible
     * cost rather than a default.
     *
     * Two notes on what resolves here. re2j lands on 1.8, the version -core already pins, which is
     * not a coincidence: CEL chose RE2 for the reason §2.6.3 did. snakeyaml moves 2.5 -> 2.6 where
     * this module shares a classpath with -dsl.
     */
    implementation(libs.cel)

    testImplementation(project(":rule-engine-compiler"))
    testImplementation(project(":rule-engine-testkit"))
    // MatchExplainer must account for §6.4 conditions, so the regression suite drives it.
    testImplementation(project(":rule-engine-observability"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
