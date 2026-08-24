plugins {
    id("buildlogic.java-library-conventions")
    /*
     * On top of the library conventions rather than instead of them. `application` is here for one
     * reason -- `./gradlew :rule-engine-example:run` has to work, because an example nobody can
     * start is a listing rather than an application -- and the conventions are what keep this
     * module held to the same bar as the rest of the tree: -Xlint:all -Werror, doclint, and the
     * strictTest run of §7.5. An example that compiles only under laxer settings than the engine
     * teaches the wrong thing twice.
     */
    application
}

description = "Rule engine example: a worked in-process application -- rules, an event feed, and the three deployment shapes"

application {
    mainClass = "com.codeheadsystems.rules.example.Main"
}

dependencies {
    /*
     * -dsl rather than -core, and that is the recommendation this module exists to make. It brings
     * -compiler and -core transitively (both `api`), so this single line is the whole dependency a
     * service that writes its rules in YAML needs.
     */
    implementation(project(":rule-engine-dsl"))

    /*
     * The two optional modules, both wired in through §8's SPI split and both demonstrated here
     * precisely because they are the parts a reader cannot discover from -core's Javadoc:
     * -observability answers "why did my rule not fire", and -cel is §6.4's escape hatch, whose
     * registration is two lines that are not obvious until you have seen them once.
     *
     * -cel is the expensive one -- protobuf, guava and antlr -- and DiagnosticsDemo says so at the
     * point it is registered. A service with no `condition:` in its rule files should not have this
     * line.
     */
    implementation(project(":rule-engine-observability"))
    implementation(project(":rule-engine-cel"))

    /*
     * The testkit is test-scope here on purpose, and the asymmetry is the lesson: `Facts.json` is a
     * fixture, so it belongs in `src/test` and nowhere in `src/main`. The main source builds its
     * payloads through Jackson, which is what a real ingestion path does.
     */
    testImplementation(project(":rule-engine-testkit"))
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/*
 * ReadmeExamplesTest compiles every rule file printed in this module's README, the same contract
 * DocExamplesTest holds the three root documents to. Gradle cannot see a file read at runtime
 * through the classpath, so without this the task goes UP-TO-DATE and the guard checks the previous
 * build's answer -- the failure mode the convention plugin's comment describes. Declared here rather
 * than there because this README is this module's, and a global input would re-run seven other
 * modules' tests when it changes.
 */
tasks.withType<Test>().configureEach {
    inputs.file(layout.projectDirectory.file("README.md"))
        .withPropertyName("exampleReadme")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
