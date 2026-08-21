plugins {
    id("buildlogic.java-library-conventions")
}

description = "Rule engine observability: tracing and Flight Recorder listeners"

dependencies {
    api(project(":rule-engine-core"))

    testImplementation(project(":rule-engine-compiler"))
    testImplementation(project(":rule-engine-testkit"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
