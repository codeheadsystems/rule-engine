package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.compiler.RuleCompilationException;
import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.Quantifier;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Compile-time validation (spec section 6.5).
 *
 * <p>A rule set is source code and deserves the same treatment: a mistake that can be caught at
 * compile time should never be discovered as a rule that silently never matches.
 */
class CompilerValidationTest {

  @Test
  @DisplayName("a $ref to a later alias is rejected, which is what keeps the join graph acyclic")
  void forwardReferencesRejected() {
    final RuleDefinition rule = Rules.rule("forward")
        .when("o", "Order", pattern -> pattern.ref("customerId", "c.id"))
        .when("c", "Customer")
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("bound later");
  }

  @Test
  @DisplayName("a $ref to an alias no pattern binds is rejected")
  void unknownAliasRejected() {
    final RuleDefinition rule = Rules.rule("unknown")
        .when("o", "Order", pattern -> pattern.ref("customerId", "nope.id"))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("not bound by this rule");
  }

  @Test
  @DisplayName("an action naming an unbound alias is rejected")
  void unboundActionTargetRejected() {
    final RuleDefinition rule = Rules.rule("bad-target")
        .when("o", "Order")
        .then(actions -> actions.setField("nope", "status", "X"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("not bound by 'when' or by an earlier insertFact");
  }

  @Test
  @DisplayName("an action naming a fact a LATER action inserts is rejected")
  void forwardActionReferenceRejected() {
    final RuleDefinition rule = Rules.rule("premature")
        .when("o", "Order")
        .then(actions -> actions
            .emit("out", "sev", Rules.ref("sig.severity"))
            .insertFactAs("RiskSignal", "sig", "severity", "HIGH"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("$ref names alias 'sig'");
  }

  @Test
  @DisplayName("duplicate rule ids are rejected across the whole rule set")
  void duplicateIdsRejected() {
    final RuleDefinition first = Rules.rule("same")
        .when("o", "Order").then(actions -> actions.emit("a")).build();
    final RuleDefinition second = Rules.rule("same")
        .when("o", "Order").then(actions -> actions.emit("b")).build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(first, second)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("duplicate rule id 'same'");
  }

  @Test
  @DisplayName("an empty when or then is rejected")
  void emptyRulesRejected() {
    final RuleDefinition noWhen = new RuleDefinition("no-when", 0, List.of(),
        Rules.rule("x").when("o", "Order").then(actions -> actions.emit("a")).build().then(),
        false, Optional.empty(), Set.of());

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(noWhen)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("at least one pattern");
  }

  @Test
  @DisplayName("a duplicate alias in one rule is rejected")
  void duplicateAliasRejected() {
    final RuleDefinition rule = Rules.rule("twice")
        .when("o", "Order")
        .when("o", "Customer")
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("is bound twice");
  }

  @Test
  @DisplayName("a literal whose type the operator cannot use is rejected")
  void literalTypeMismatchRejected() {
    final RuleDefinition inWithoutArray = Rules.rule("bad-in")
        .when("o", "Order", pattern ->
            pattern.op("status", Operator.IN, "PENDING"))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(inWithoutArray)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("expects an array literal");

    final RuleDefinition hasFieldWithoutBoolean = Rules.rule("bad-hasfield")
        .when("o", "Order", pattern ->
            pattern.op("status", Operator.HAS_FIELD, "yes"))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(hasFieldWithoutBoolean)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("boolean literal");
  }

  @Test
  @DisplayName("an invalid regular expression is a compile error, not a runtime surprise")
  void invalidRegexRejected() {
    final RuleDefinition rule = Rules.rule("bad-regex")
        .when("o", "Order", pattern -> pattern.matches("email", "((("))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("invalid regular expression");
  }

  @Test
  @DisplayName("a backreference is rejected, because RE2 has none")
  void re2LimitationsAreExplained() {
    // The trade section 2.6.3 makes explicit: RE2 gives up backreferences and lookaround in
    // exchange for a bounded worst case. The diagnostic has to say so, or the author is left
    // staring at a pattern that works in every other Java tool they own.
    final RuleDefinition rule = Rules.rule("backref")
        .when("o", "Order", pattern -> pattern.matches("code", "(a)\\1"))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("RE2");
  }

  @Test
  @DisplayName("a deferred quantifier is rejected with a pointer to the interim answer")
  void deferredQuantifiersRejected() {
    final RuleDefinition rule = new RuleDefinition("negated", 0,
        List.of(new PatternDefinition("p", "Payment", Quantifier.NOT_EXISTS, List.<Constraint>of())),
        Rules.rule("x").when("o", "Order").then(actions -> actions.emit("a")).build().then(),
        false, Optional.empty(), Set.of());

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("not implemented in v1");
  }

  @Test
  @DisplayName("an expression with no compiler registered names the module that would accept it")
  void expressionsNeedACompiler() {
    // §6.4 makes the escape hatch an explicit, opt-in cost -- of a dependency as much as of
    // evaluation -- so its absence is a compile error rather than a NoClassDefFoundError later.
    final RuleDefinition rule = Rules.rule("cel")
        .when("o", "Order", pattern -> pattern.constraint(
            new ExpressionConstraint("o.total > 10000", Set.of("o"))))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("rule-engine-cel");
  }

  @Test
  @DisplayName("an expression reading an alias the rule does not bind is rejected")
  void expressionAliasMustBeBound() {
    final RuleDefinition rule = Rules.rule("unbound")
        .when("o", "Order", pattern -> pattern.constraint(
            new ExpressionConstraint("c.tier == 'HIGH'", Set.of("c"))))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("does not bind");
  }

  @Test
  @DisplayName("an unknown callFunction name is a compile error when functions are declared")
  void unknownFunctionRejected() {
    final RuleDefinition rule = Rules.rule("call")
        .when("o", "Order")
        .then(actions -> actions.callFunction("notifySlack"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule),
        CompilerOptions.builder().declaredFunctions(Set.of("notifyEmail")).build()))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("which is not registered")
        .hasMessageContaining("notifyEmail");

    // Without a declaration the check is off, and an unknown name becomes a fire-time failure.
    assertThat(RuleCompiler.compile(List.of(rule)).rules()).hasSize(1);
  }

  @Test
  @DisplayName("matches cannot be a join operator, because the pattern would come from a fact")
  void matchesRejectedOnAJoin() {
    // Left to compile, this throws from inside the matcher at fire time -- a compile-time-detectable
    // authoring error escaping to production.
    final RuleDefinition rule = Rules.rule("regex-join")
        .when("a", "A", pattern -> pattern.hasField("k", true))
        .when("b", "B", pattern -> pattern.ref("code", "a.k", Operator.MATCHES))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("matches cannot be used as a join operator");
  }

  @Test
  @DisplayName("hasField and isNull cannot be join operators either")
  void singleFactTestsRejectedOnAJoin() {
    /*
     * Both read their polarity from a boolean literal, which a $ref cannot supply -- a join hands
     * the OTHER FACT'S VALUE to Comparisons.test in the literal position, so the polarity would be
     * whatever data happened to be in that field.
     *
     * Under Jackson 2 that evaluated nonsense quietly, which this comment used to say was worse
     * than throwing. Jackson 3 changed the stakes rather than the verdict: asBoolean() now throws
     * on a string or object node, so without this gate an ordinary string-valued field would raise
     * an exception from inside the fire loop. The gate went from preventing a wrong answer to
     * preventing a crash; see the comment on Comparisons.test's HAS_FIELD case.
     */
    for (final Operator operator : List.of(Operator.HAS_FIELD, Operator.IS_NULL)) {
      final RuleDefinition rule = Rules.rule("bad-join-" + operator)
          .when("a", "A", pattern -> pattern.hasField("k", true))
          .when("b", "B", pattern -> pattern.ref("k", "a.k", operator))
          .then(actions -> actions.emit("out"))
          .build();

      assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
          .describedAs("%s on a join", operator)
          .isInstanceOf(RuleCompilationException.class)
          .hasMessageContaining("cannot be used as a join operator");
    }
  }

  @Test
  @DisplayName("in and notIn ARE legal joins: a scalar against another fact's array is meaningful")
  void membershipJoinsStayLegal() {
    final RuleDefinition rule = Rules.rule("membership-join")
        .when("a", "A", pattern -> pattern.hasField("allowed", true))
        .when("b", "B", pattern -> pattern.ref("tier", "a.allowed", Operator.IN))
        .then(actions -> actions.emit("out"))
        .build();

    assertThat(RuleCompiler.compile(List.of(rule)).rules()).hasSize(1);
  }

  @Test
  @DisplayName("every diagnostic is reported, not just the first")
  void allDiagnosticsReported() {
    final RuleDefinition rule = Rules.rule("many-problems")
        .when("o", "Order", pattern -> pattern.ref("customerId", "nope.id"))
        .then(actions -> actions.setField("also-nope", "status", "X"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .satisfies(thrown -> assertThat(((RuleCompilationException) thrown).diagnostics())
            .hasSizeGreaterThanOrEqualTo(2));
  }

  @Test
  @DisplayName("both sides of a join are recorded as tested paths")
  void joinsRecordBothSides() {
    // Recording only the pattern's own side would make an update to the other side of a join look
    // like a no-op -- the quietest possible way to lose a firing.
    final RuleDefinition rule = Rules.rule("join")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("out"))
        .build();

    final var compiled = RuleCompiler.compile(List.of(rule));

    assertThat(compiled.testedPaths().forType("Order"))
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("/status", "/customerId");
    assertThat(compiled.testedPaths().forType("Customer"))
        .extracting(Object::toString)
        .containsExactly("/id");
  }

  @Test
  @DisplayName("the version hash has a fixed value, not merely a stable one")
  void versionHashIsPinned() {
    /*
     * Everything else about the version hash is asserted relatively -- same rules give the same
     * string, different rules give a different one -- and DslEquivalenceTest compares a rule file's
     * hash against the builder's. All of that stays green if the hash function moves wholesale.
     *
     * That is a real gap rather than a theoretical one. §5.6's hot reload, §2.5's refraction and the
     * run-both-and-compare cutover all key on rule-set identity, and the Jackson 3 migration could
     * have moved every hash silently: RuleCompiler.version hashes a string built from the when/then
     * record toStrings, which render their JsonNodes. It happened not to -- Jackson 3's toString is
     * byte-identical to Jackson 2's for every node kind this engine produces -- but nothing in the
     * suite would have said so.
     *
     * So one fixed rule set is pinned to one literal hash. If this fails, rule-set identity moved,
     * and that is a decision to be made deliberately (bump COMPILER_VERSION and say why in the
     * commit) rather than discovered by a consumer whose stored version stopped matching.
     */
    /*
     * The fixture is chosen for what could MOVE, not for readability. A pin over strings and ints
     * would have stayed green through exactly the rendering changes it exists to catch, so it
     * carries: a BigDecimal with trailing zeros and a fractional double (scale and exponent form
     * are where Jackson's number rendering could plausibly change); a string needing escaping and
     * one outside ASCII; an object and an array literal (container rendering, and key order); a
     * two-sided range; and two tags, because canonicalise sorts tags() through a TreeSet
     * specifically to keep Set.copyOf's per-JVM iteration salt out of the hash -- the one
     * canonicalisation bug this project has actually shipped, and the fixture had no tags at all.
     */
    final CompiledRuleSet pinned = RuleCompiler.compile(List.of(Rules.rule("pinned")
        .salience(5)
        .tag("zebra")
        .tag("alpha")
        .when("o", "Order", pattern -> pattern
            .eq("status", "PENDING\t\"quoted\"\n")
            .eq("note", "sale ends soon \u2014 \u00e9t\u00e9")
            .eq("breakdown", Facts.obj("net", new java.math.BigDecimal("100.00")))
            .eq("codes", Facts.array(1, 2.50d))
            .gt("total", 10000)
            .between("weight", 0.5d, 99.750d)
            .in("region", "EU", "US"))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions.emit("out", "id", Rules.ref("o.id")))
        .build()));

    assertThat(pinned.version())
        .describedAs("rule-set identity is a compatibility surface; see this test's comment")
        .isEqualTo("sha256:8049b5f6bd96b20d");
  }

  @Test
  @DisplayName("the version hash is stable for the same rules and changes when they change")
  void versionHash() {
    final RuleDefinition rule = Rules.rule("v")
        .when("o", "Order", pattern -> pattern.eq("status", "PENDING"))
        .then(actions -> actions.emit("out"))
        .build();
    final RuleDefinition changed = Rules.rule("v")
        .when("o", "Order", pattern -> pattern.eq("status", "SHIPPED"))
        .then(actions -> actions.emit("out"))
        .build();

    assertThat(RuleCompiler.compile(List.of(rule)).version())
        .isEqualTo(RuleCompiler.compile(List.of(rule)).version())
        .isNotEqualTo(RuleCompiler.compile(List.of(changed)).version())
        .startsWith("sha256:");
  }

  @Test
  @DisplayName("gt compiles into the same range structure the two-sided form produces")
  void oneSidedRangesUnify() {
    // One ordering code path rather than two that can disagree.
    final RuleDefinition rule = Rules.rule("range")
        .when("o", "Order", pattern -> pattern.constraint(
            new FieldConstraint("total", Operator.GTE, Facts.obj("v", 100).get("v"))))
        .then(actions -> actions.emit("out"))
        .build();

    assertThat(RuleCompiler.compile(List.of(rule)).rules().getFirst()
        .patterns().getFirst().alphaTests().getFirst())
        .isInstanceOf(com.codeheadsystems.rules.rule.RangeTest.class);
  }
}
