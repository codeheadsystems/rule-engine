/*
 * Root build for the rule engine (docs/rule-engine-spec.md).
 *
 * There is deliberately no publishing configuration yet: §0 makes "no mandatory build/packaging
 * layer" a design goal, and a CompiledRuleSet is just an object you get back from a compile call.
 * Publishing is a decision for whenever v1 (§9 Phase 2) is actually ready to ship.
 */
plugins {
    /*
     * Per-module coverage is actively misleading here. Most of -core's behaviour is exercised by
     * the end-to-end tests in -testkit, and a per-module report attributes none of that back --
     * it reported -core at 26% while the real figure was three times higher. A number that wrong
     * is worse than no number, because somebody eventually acts on it.
     *
     * Run: ./gradlew testCodeCoverageReport
     * Read: build/reports/jacoco/testCodeCoverageReport/html/index.html
     */
    id("jacoco-report-aggregation")
}

// The aggregating project resolves the modules' runtime classpaths to find their class files.
repositories {
    mavenCentral()
}

dependencies {
    jacocoAggregation(project(":rule-engine-core"))
    jacocoAggregation(project(":rule-engine-compiler"))
    jacocoAggregation(project(":rule-engine-dsl"))
    jacocoAggregation(project(":rule-engine-observability"))
    jacocoAggregation(project(":rule-engine-testkit"))
}

/*
 * The aggregating project applies no JVM plugin, so the report has to be declared rather than
 * inferred from a test suite.
 */
reporting {
    reports {
        create<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

allprojects {
    group = "com.codeheadsystems"
    version = "0.1.0-SNAPSHOT"
}
