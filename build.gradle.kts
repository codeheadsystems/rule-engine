/*
 * Root build for the rule engine (docs/rule-engine-spec.md).
 *
 * Publishing is configured in settings.gradle.kts -- the nmcp settings plugin applies the
 * aggregation plugin here, so the whole build uploads as ONE Central Portal deployment. Per-module
 * publication metadata lives in buildlogic.publish-conventions, applied by each library module.
 *
 *   ./gradlew publishAggregationToCentralPortal
 *
 * §0 makes "no mandatory build/packaging layer" a design goal and that is unchanged: a
 * CompiledRuleSet is still just an object you get back from a compile call. What changed is that
 * there are now coordinates to depend on rather than a repository to clone.
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
    jacocoAggregation(project(":rule-engine-cel"))
    jacocoAggregation(project(":rule-engine-schema"))
    jacocoAggregation(project(":rule-engine-observability"))
    jacocoAggregation(project(":rule-engine-testkit"))
    /*
     * rule-engine-example is deliberately NOT here. It is a worked example rather than a library:
     * its coverage number measures how much of a demo the demo runs, and mixing that into the
     * engine's figure moves the number somebody acts on without telling them anything about the
     * engine. Its own tests still run under `check` like every other module's.
     */
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

/*
 * `group` and `version` are NOT set here. They come from gradle.properties, which settings.gradle.kts
 * overrides from a Git tag in `beforeProject` -- and an `allprojects` block runs after that and would
 * silently win, putting the SNAPSHOT coordinates on a tagged release build.
 */
