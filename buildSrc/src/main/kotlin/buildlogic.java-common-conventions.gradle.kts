plugins {
    java
    idea
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        // docs/rule-engine-spec.md §5 pins JDK 25 (LTS). Virtual threads are final here and
        // Scoped Values are final in 25; StructuredTaskScope is deliberately NOT used (§5.2),
        // so the build needs no --enable-preview.
        languageVersion = JavaLanguageVersion.of(25)
    }
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

/*
 * §7.5 requires the full suite to run under strict mode in CI and forbids strict mode in
 * production. `strictTest` is that run: the same tests, with SessionOptions.strict() defaulted on
 * via a system property the test fixtures read.
 */
val strictTest = tasks.register<Test>("strictTest") {
    description = "Runs the test suite with strict-mode contract checks enabled (spec §7.5)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("rules.strict", "true")
    // Its own result and report dirs, so it does not fight `test` for outputs.
    reports.junitXml.outputLocation = layout.buildDirectory.dir("test-results/strictTest")
    reports.html.outputLocation = layout.buildDirectory.dir("reports/tests/strictTest")
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(strictTest)
}

/*
 * Javadoc warnings fail the build. The spec places load-bearing contracts in Javadoc rather than
 * in enforceable code -- payload ownership (§2.2), factsOfType snapshot semantics (§2.4), halt()
 * being the one legal cross-thread call (§4.7), the consuming behaviour of Agenda.nextToFire()
 * (§5.1). A missing or stale one of those is a defect in the artifact consumers read.
 */
tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("html5", true)
        addBooleanOption("Werror", true)
        addStringOption("Xdoclint:all", "-quiet")
    }
}
