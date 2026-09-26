/*
 * Maven Central publication metadata, for the modules that are libraries -- and for the BOM that
 * aligns them.
 *
 * Applied per module rather than to `allprojects`, because publishing is a decision about an
 * artifact and not a property of being in this build. `rule-engine-example` does not apply it: it
 * is a worked application, and an artifact on Central is a promise to keep something compiling for
 * consumers who depend on it. Nobody should depend on the example.
 *
 * The upload itself is not here. The nmcp settings plugin (settings.gradle.kts) applies the
 * aggregation plugin to the root, so every module publishing under this convention is collected
 * into ONE Central Portal deployment with one validation result -- rather than eight deployments
 * that can each half-succeed and leave the namespace holding four of eight modules at a version.
 */
plugins {
    `maven-publish`
    signing
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            // The component is chosen below, by which plugin the module applied -- see there.
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            pom {
                /*
                 * The module's own `description`, which every module in this build sets. Central
                 * requires name, description and url on every POM, so a module that forgets its
                 * description fails validation -- hence the fallback rather than a null.
                 *
                 * A provider, not the value. This block runs when the plugin is applied, which is
                 * before the build file's `description = ...` line, so reading it eagerly found null
                 * every time: 1.0.0 shipped all seven POMs with the fallback, and nothing failed,
                 * because the fallback exists precisely so that nothing does.
                 */
                name.set(project.name)
                description.set(provider {
                    project.description
                        ?: "An in-process forward-chaining production rule engine for the JVM"
                })
                url.set("https://github.com/codeheadsystems/rule-engine")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("wolpert")
                        name.set("Ned Wolpert")
                        email.set("ned.wolpert@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/codeheadsystems/rule-engine.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com:codeheadsystems/rule-engine.git")
                    url.set("https://github.com/codeheadsystems/rule-engine")
                }
            }
        }
    }
    // No `repositories` block: nmcp owns the upload to the Central Portal.
}

/*
 * One publication, two shapes of module, and the plugin the module applied decides which.
 *
 * A library applies `java` (through the java-library conventions, which call withJavadocJar() and
 * withSourcesJar()) and publishes the `java` component: a jar, its sources and javadoc jars, a POM
 * and a .module. Central REQUIRES the two extra jars for jar packaging -- a deployment missing either
 * is rejected at validation, after upload.
 *
 * rule-engine-bom applies `java-platform` and publishes `javaPlatform`: a POM with pom packaging and a
 * .module, no jars at all, and Central requires none for pom packaging. Everything else in this file --
 * the POM metadata, signing, verifyPublishConfig -- is about the publication rather than its contents,
 * so it covers both without a second copy.
 *
 * withPlugin rather than hasPlugin, so the order a build file lists its plugins in does not matter.
 * Gradle refuses to apply `java` and `java-platform` to one project, and refuses a second from() on
 * one publication, so "both" cannot happen quietly. "Neither" could, and is checked below.
 */
val publication = publishing.publications.named<MavenPublication>("mavenJava")
pluginManager.withPlugin("java") { publication.configure { from(components["java"]) } }
pluginManager.withPlugin("java-platform") {
    publication.configure { from(components["javaPlatform"]) }
}

afterEvaluate {
    /*
     * A publication with no component still publishes: a POM naming no dependencies and no artifact,
     * signed and accepted by Central under a permanent version. A module applying this convention
     * before deciding what it is would ship exactly that, so it fails the configuration instead.
     */
    check(pluginManager.hasPlugin("java") || pluginManager.hasPlugin("java-platform")) {
        "${project.path} applies buildlogic.publish-conventions but neither `java` nor " +
            "`java-platform`, so it has nothing to publish"
    }
}

signing {
    /*
     * Signing is required for a real release and must NOT be required for an ordinary build. A
     * developer with no GPG key still has to be able to run `publishToMavenLocal` to try a change
     * against a downstream project, and making signing unconditional turns that into a setup
     * problem before it is a build.
     *
     * Two conditions, and both matter. Credentials present, so a local build without a key skips
     * signing rather than failing it; and not a SNAPSHOT, so the only artifacts that can leave here
     * unsigned are ones Central would never accept anyway. A release build with no key configured
     * therefore fails at signing rather than at Central's validation -- which is the right end of
     * the pipeline to find out.
     */
    val credentialsPresent = project.hasProperty("signing.gnupg.keyName")
        || System.getenv("GPG_KEY_ID") != null

    isRequired = credentialsPresent && !version.toString().endsWith("SNAPSHOT")

    // The gpg command line rather than Gradle's in-process signer: it reads the agent, the
    // keyring and the loopback pinentry configuration that CI sets up, so the same command works
    // on a developer's machine and in the release job.
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}

/*
 * `isRequired = false` does NOT mean "do not sign", and the difference is the whole of the paragraph
 * above being true or false.
 *
 * With useGpgCmd() the Sign task always has a signatory, so `isRequired` only governs whether a
 * FAILURE is fatal -- the task still runs `gpg`, and on a machine with no secret key it dies with
 * "gpg: signing failed: No secret key". Which means the documented promise, that a developer without
 * a key can still run publishToMavenLocal, was not true as written. Found by a reviewer running it
 * against an empty keyring; the local build had a key, so nothing here could notice.
 *
 * Skipping the task, rather than tolerating its failure, is what makes the promise real.
 */
tasks.withType<Sign>().configureEach {
    onlyIf { signing.isRequired }
}

/*
 * What is about to be published, before anything is. `./gradlew verifyPublishConfig` answers the
 * question a release checklist actually asks -- which coordinates, at which version, signed or not
 * -- without uploading, and it is the cheapest way to catch the failure mode that matters: a tag
 * that did not reach the version, so the build would publish SNAPSHOT coordinates or, worse,
 * overwrite a released version's number with different bytes.
 */
tasks.register("verifyPublishConfig") {
    val coordinates = "${project.group}:${project.name}:${project.version}"
    val snapshot = project.version.toString().endsWith("SNAPSHOT")
    val signed = signing.isRequired
    doLast {
        println("$coordinates  snapshot=$snapshot signed=$signed")
    }
}
