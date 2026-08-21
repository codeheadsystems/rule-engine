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
  @DisplayName("the CEL escape hatch is rejected with the phase it arrives in")
  void celIsDeferred() {
    final RuleDefinition rule = Rules.rule("cel")
        .when("o", "Order", pattern -> pattern.constraint(
            new ExpressionConstraint("o.total > 10000", Set.of("o"))))
        .then(actions -> actions.emit("out"))
        .build();

    assertThatThrownBy(() -> RuleCompiler.compile(List.of(rule)))
        .isInstanceOf(RuleCompilationException.class)
        .hasMessageContaining("Phase 5");
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
    // Both read their polarity from a boolean literal, which a $ref cannot supply. They do not
    // throw; they evaluate nonsense quietly, which is worse.
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
