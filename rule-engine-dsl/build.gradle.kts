plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Rule engine DSL: JSON and YAML rule files and fact documents, plus the rule-file schema"

dependencies {
    /*
     * `api`, not `implementation`: RuleFiles hands back a CompiledRuleSet, and the caller of a
     * parse-and-compile method needs the compiler's types -- CompilerOptions going in,
     * RuleCompilationException coming out -- without declaring a second dependency to get them.
     */
    api(project(":rule-engine-compiler"))

    /*
     * §6.1's "one object model, two serializations". This is the second factory and nothing else;
     * both mappers deserialize into the identical POJO tree.
     *
     * §8 asks that a deployment which does not want YAML's transitive snakeyaml be able to exclude
     * it. That is a constraint on the CODE as much as on this line: the YAMLFactory reference is
     * isolated in RuleFormat so that parsing JSON never triggers its classload. Keep it that way.
     */
    implementation(libs.jackson.dataformat.yaml)

    /*
     * §6.5's "JSON-Schema validation of the RULE FILE (fail fast, before touching the network)".
     *
     * Pinned to the 2.x line deliberately -- see the note in libs.versions.toml. It is also the
     * tooling §2.3's SchemaRegistry reuses when the optional half of Phase 5 lands, which is why
     * §8 puts -schema next to -dsl rather than next to -core.
     */
    implementation(libs.json.schema.validator)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
