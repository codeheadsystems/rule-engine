plugins {
    id("buildlogic.java-library-conventions")
    id("buildlogic.publish-conventions")
}

description = "Rule engine core: fact model, working memory, matching primitives, agenda, sessions"

dependencies {
    /*
     * §8's closing note: -core has a real, load-bearing dependency on jackson-databind, because
     * the fact model itself is JSON-native (§2.2) rather than an Object that happens to support
     * JSON as one representation. This is `api`, not `implementation`: JsonNode appears in the
     * public signatures of WorkingMemory, RuleSession and EventSink.
     */
    api(libs.jackson.databind)
    api(libs.jackson.core)

    /*
     * §2.6.3: rule-authored `matches` patterns compile with RE2, not java.util.regex. A rule file
     * containing `(a+)+$` matched against a moderately long non-matching string takes exponential
     * time on a backtracking engine and pins a carrier thread until it finishes -- a rule file,
     * reviewed as config, taking down the service. RE2 is linear in the input and cannot backtrack
     * catastrophically. The trade is real: no backreferences, no lookaround, and a slightly slower
     * average case. That is the same trade §6.4 makes choosing CEL over MVEL, for the same reason.
     *
     * `api`, not `implementation`: RegexTest carries a compiled com.google.re2j.Pattern as a
     * record component, because section 2.6.3 requires patterns to be compiled once at rule-compile
     * time rather than per fact per cycle -- so the compiler module has to be able to construct one.
     */
    api(libs.re2j)

    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
