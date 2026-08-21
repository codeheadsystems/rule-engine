plugins {
    id("buildlogic.java-library-conventions")
}

description = "Rule engine compiler: RuleDefinition -> CompiledRuleSet"

dependencies {
    api(project(":rule-engine-core"))

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
