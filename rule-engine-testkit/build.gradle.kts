plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
    id("buildlogic.jmh-conventions")
}

/*
 * §8: "rule-engine-testkit is not optional." Phase 0's naive matcher is the correctness oracle
 * for every later phase, §11.2's update semantics have oracle-equivalence as a Phase 1 exit
 * criterion, and §7.3's determinism contract needs shuffle tests. All three are things consumers
 * want too, so they are main-source-set API, not another module's src/test.
 */
description = "Rule engine testkit: fixtures, the firing-sequence oracle, shuffle-determinism harness, JMH benchmarks"

dependencies {
    api(project(":rule-engine-core"))
    api(project(":rule-engine-compiler"))
    /*
     * For DslEquivalence, which is the DSL's analogue of MatcherEquivalence: a rule file and the
     * same rule built in Java must be indistinguishable downstream. §8's "the testkit is not
     * optional" argument covers it -- an author testing their own rule files wants exactly this
     * harness, and left in another module's src/test it would be unusable from outside.
     *
     * One consequence worth naming: this puts snakeyaml and the schema validator on the compile
     * classpath of everything that uses the testkit, which is in tension with §8's note that a
     * deployment not wanting YAML should be able to exclude snakeyaml. It is a test-scope
     * dependency for those consumers, so it does not reach production classpaths -- but a consumer
     * who genuinely wants neither should depend on -core and -compiler directly.
     */
    api(project(":rule-engine-dsl"))
    // Assertions are part of the testkit's public surface: consumers assert on scenario outcomes.
    api(libs.assertj)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/*
 * ApiSurfaceTest reads every module's main source tree to check what each one reaches into, and
 * Gradle cannot see that through the classpath: without these the task goes UP-TO-DATE and the
 * boundary is checked against the previous build's answer. Found by mutating a sibling module and
 * watching the guard not run.
 *
 * Declared here rather than in the convention plugin, where the doc fixtures live. As a global
 * input this would make a comment edit in -cel re-run `test` and `strictTest` in six other modules
 * and miss their build-cache entries, for a guard that exists in exactly one place. The cost of
 * scoping it is that a second source-reading suite elsewhere would have to remember this; the
 * comment in the convention plugin says so.
 */
tasks.withType<Test>().configureEach {
    rootProject.subprojects.forEach { module ->
        val sources = module.layout.projectDirectory.dir("src/main/java")
        if (sources.asFile.isDirectory) {
            inputs.dir(sources)
                .withPropertyName("sourceOf-${module.name}")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    // settings.gradle.kts is where those tests read the module list from, so it is an input too.
    inputs.file(rootProject.layout.projectDirectory.file("settings.gradle.kts"))
        .withPropertyName("moduleList")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    /*
     * And every module's build file, for PublishedModulesTest -- which decides whether a module is
     * published by looking for the convention plugin in it. Same hole as the source trees above:
     * undeclared, adding `buildlogic.publish-conventions` to a module would not re-run the guard
     * that exists to notice it.
     */
    rootProject.subprojects.forEach { module ->
        val buildFile = module.layout.projectDirectory.file("build.gradle.kts")
        if (buildFile.asFile.isFile) {
            inputs.file(buildFile)
                .withPropertyName("buildFileOf-${module.name}")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
}
