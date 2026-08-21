plugins {
    id("buildlogic.java-library-conventions")
    alias(libs.plugins.jmh)
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
 * The JMH plugin compiles generated benchmark harness code, which is not ours to keep warning-free.
 * -Werror stays on for the sources we write; it comes off for the ones the annotation processor
 * writes, because failing the build on a warning in generated code is a fight with no winner.
 */
tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.removeAll(listOf("-Werror"))
}

jmh {
    /*
     * Sized so that `./gradlew :rule-engine-testkit:jmh` finishes in a couple of minutes and
     * therefore actually gets run. These are Phase 0 baselines: what they have to support is
     * "Phase 1 made this faster", which is an order-of-magnitude question, not a 2% one. Lengthen
     * the iterations when a change turns out to hinge on a small difference.
     */
    warmupIterations = 3
    iterations = 3
    timeOnIteration = "2s"
    warmup = "2s"
    fork = 1
    resultFormat = "TEXT"
}
