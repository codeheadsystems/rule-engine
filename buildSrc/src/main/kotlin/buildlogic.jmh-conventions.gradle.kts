plugins {
    java
    id("me.champeau.jmh")
}

/*
 * One sizing for every module that benchmarks, because a benchmark suite whose modules disagree
 * about warmup produces columns that cannot be compared -- and comparing columns is the whole
 * point. §9 makes each phase's exit criterion a comparison against the phase before it.
 *
 * Sized so that a full run finishes in a few minutes and therefore actually gets run. What these
 * numbers have to support is "did this phase make it faster", which is an order-of-magnitude
 * question, not a 2% one. Lengthen the iterations before hanging a decision on a small difference,
 * and say in docs/benchmarks.md that you did.
 */
jmh {
    warmupIterations = 3
    iterations = 3
    timeOnIteration = "2s"
    warmup = "2s"
    fork = 1
    resultFormat = "TEXT"
}

/*
 * The JMH plugin compiles generated harness code, which is not ours to keep warning-free. -Werror
 * stays on for the sources we write and comes off for the ones the annotation processor writes,
 * because failing a build on a warning in generated code is a fight with no winner.
 */
tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.removeAll(listOf("-Werror"))
}
