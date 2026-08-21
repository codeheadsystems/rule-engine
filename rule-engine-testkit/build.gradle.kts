plugins {
    id("buildlogic.java-library-conventions")
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
