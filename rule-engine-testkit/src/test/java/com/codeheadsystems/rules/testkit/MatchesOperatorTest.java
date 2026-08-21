package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.SessionOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code matches} operator (spec section 2.6.3).
 *
 * <p>Patterns are compiled once, at rule-compile time, and they are RE2 rather than
 * {@code java.util.regex}. That is not a stylistic preference: a rule file containing a
 * catastrophically backtracking pattern would take exponential time and pin a carrier thread until
 * it finished -- a rule <em>file</em>, reviewed as configuration, taking down the service. The last
 * test here is the one that matters.
 */
class MatchesOperatorTest {

  private static final RuleDefinition CORPORATE_EMAIL = Rules.rule("corporate-email")
      .when("c", "Customer", pattern -> pattern.matches("email", "^[a-z]+@example\\.com$"))
      .then(actions -> actions.emit("internal", "email", Rules.ref("c.email")))
      .build();

  @Test
  @DisplayName("a matching string fires and a non-matching one does not")
  void matchesFilters() {
    final FireResult result = Engine.result(Engine.compile(CORPORATE_EMAIL),
        SessionOptions.defaults(),
        session -> {
          session.insert("Customer", Facts.obj("email", "alice@example.com"));
          session.insert("Customer", Facts.obj("email", "bob@elsewhere.com"));
          session.insert("Customer", Facts.obj("email", "Carol@example.com"));
        });

    assertThat(result.emitted())
        .extracting(event -> event.payload().get("email").stringValue())
        .containsExactly("alice@example.com");
  }

  @Test
  @DisplayName("matches against absent, null and non-string values is false, per section 2.6.1")
  void nonStringsNeverMatch() {
    final FireResult result = Engine.result(Engine.compile(CORPORATE_EMAIL),
        SessionOptions.defaults(),
        session -> {
          session.insert("Customer", Facts.obj("name", "no email field at all"));
          session.insert("Customer", Facts.obj("email", (Object) null));
          session.insert("Customer", Facts.obj("email", 42));
        });

    assertThat(result.firedCount()).isZero();
  }

  @Test
  @DisplayName("the pattern is anchored as written, not implicitly")
  void anchoringIsTheAuthorsJob() {
    // RE2's find() semantics: an unanchored pattern matches anywhere in the string. The rule above
    // anchors explicitly; this one does not, and the difference is visible.
    final RuleDefinition unanchored = Rules.rule("contains-example")
        .when("c", "Customer", pattern -> pattern.matches("email", "example"))
        .then(actions -> actions.emit("hit", "email", Rules.ref("c.email")))
        .build();

    final FireResult result = Engine.result(Engine.compile(unanchored), SessionOptions.defaults(),
        session -> session.insert("Customer", Facts.obj("email", "bob@not-example-really.org")));

    assertThat(result.firedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("a catastrophically backtracking pattern is linear, not exponential")
  void re2BoundsTheWorstCase() {
    // The spec's own example: (a+)+$ against a long non-matching string. On a backtracking engine
    // this takes exponential time and pins the thread. On RE2 it is linear in the input, which is
    // the entire reason the dependency is here.
    final RuleDefinition evil = Rules.rule("evil-regex")
        .when("c", "Customer", pattern -> pattern.matches("code", "(a+)+$"))
        .then(actions -> actions.emit("hit"))
        .build();

    final String pathological = "a".repeat(60) + "!";

    final long startedAt = System.nanoTime();
    final FireResult result = Engine.result(Engine.compile(evil), SessionOptions.defaults(),
        session -> session.insert("Customer", Facts.obj("code", pathological)));
    final Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(result.firedCount()).isZero();
    // A backtracking engine would still be working on this when the heat death of the universe
    // arrives; 2^60 steps is not a number that finishes. A generous bound still proves the point.
    assertThat(took).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a compiled pattern is shared across sessions and reused, never recompiled")
  void patternsAreCompiledOnce() {
    // Compiled once in the pipeline and cached in the rule set, exactly like a JsonPointer. The
    // observable consequence is that many sessions over one rule set all behave identically and
    // pay nothing extra.
    final var ruleSet = Engine.compile(CORPORATE_EMAIL);

    for (int run = 0; run < 50; run++) {
      assertThat(Engine.result(ruleSet, SessionOptions.defaults(),
          session -> session.insert("Customer", Facts.obj("email", "alice@example.com")))
          .firedCount()).isEqualTo(1);
    }

    assertThat(ruleSet.rules().getFirst().patterns().getFirst().alphaTests())
        .singleElement()
        .isInstanceOf(com.codeheadsystems.rules.rule.RegexTest.class);
  }

  @Test
  @DisplayName("matches is combinable with the other operators on one pattern")
  void combinesWithOtherConstraints() {
    final RuleDefinition rule = Rules.rule("risky-internal")
        .when("c", "Customer", pattern -> pattern
            .matches("email", "@example\\.com$")
            .eq("riskTier", "HIGH"))
        .then(actions -> actions.emit("hit", "email", Rules.ref("c.email")))
        .build();

    final FireResult result = Engine.result(Engine.compile(rule), SessionOptions.defaults(),
        session -> {
          session.insert("Customer", Facts.obj("email", "a@example.com", "riskTier", "HIGH"));
          session.insert("Customer", Facts.obj("email", "b@example.com", "riskTier", "LOW"));
          session.insert("Customer", Facts.obj("email", "c@other.com", "riskTier", "HIGH"));
        });

    assertThat(result.emitted())
        .extracting(event -> event.payload().get("email").stringValue())
        .containsExactly("a@example.com");
  }

  @Test
  @DisplayName("an emitted event's context is value-comparable, so results can be diffed")
  void emitContextValueSemantics() {
    final var ruleSet = Engine.compile(CORPORATE_EMAIL);
    final List<com.codeheadsystems.rules.session.EmitContext> contexts =
        Engine.result(ruleSet, SessionOptions.defaults(),
            session -> session.insert("Customer", Facts.obj("email", "alice@example.com")))
            .emitted().stream().map(event -> event.context()).toList();

    final var context = contexts.getFirst();
    final var same = new com.codeheadsystems.rules.session.EmitContext(
        context.sessionId(), context.ruleId(), context.handles(), context.ruleSetVersion());
    final var different = new com.codeheadsystems.rules.session.EmitContext(
        context.sessionId(), context.ruleId(), new long[] {99L}, context.ruleSetVersion());

    assertThat(same).isEqualTo(context).hasSameHashCodeAs(context);
    assertThat(different).isNotEqualTo(context);
    // The handles are copied on the way in and out, so a caller cannot corrupt a recorded context.
    context.handles()[0] = 12345L;
    assertThat(context).isEqualTo(same);
  }
}
