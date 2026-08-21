plugins {
    // Convention plugins under src/main/kotlin become plugin ids in the main build.
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

dependencies {
    /*
     * A convention script can only apply a plugin that is on buildSrc's own classpath, so the JMH
     * plugin is declared here as a dependency on its marker artifact. The version is duplicated
     * from gradle/libs.versions.toml because the version catalog is not visible to buildSrc's own
     * build script; if one moves, move both.
     */
    implementation("me.champeau.jmh:jmh-gradle-plugin:0.7.3")
}
