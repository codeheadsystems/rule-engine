package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.List;
import java.util.function.Consumer;

/**
 * Asserts that a rule file and the same rule written in Java are the same rule.
 *
 * <p>This is {@link MatcherEquivalence}'s argument applied one layer up. There, two matchers must
 * produce identical firing sequences or "the choice of session type silently changes business
 * outcomes". Here, two <em>front ends</em> must produce identical rules, or the choice of how a
 * rule was authored does -- and that is worse, because a rule set is normally migrated from one to
 * the other, and a divergence shows up as behaviour that changed during a migration nobody thought
 * could change behaviour.
 *
 * <p>The comparison is made three ways, because each catches what the others cannot.
 *
 * <p><strong>The compiled rule definitions.</strong> This is the strong one: {@code RuleDefinition}
 * and everything under it are records over strings, enums and {@code JsonNode}s, so {@code equals}
 * really does mean "every constraint, in the same order, with equal literals". It is what would
 * have caught {@code RangeConstraint}'s un-normalised inclusivity, where {@code { gte: 100 }} and
 * {@code { between: { from: 100 } }} behaved identically and compared unequal.
 *
 * <p><strong>The rule-set version hash.</strong> Weaker than it looks, and checked anyway because
 * it is what §5.6 uses as rule-set identity. {@code RuleCompiler.version()} digests a
 * {@code toString()} rendering, and two constraints that are <em>not</em> equal can render
 * identically -- {@code DoubleNode(25000.0)} and {@code DecimalNode(25000.0)} both print
 * {@code 25000.0}. So an equal hash does not prove equal structure; it proves the two would be
 * treated as the same rule set by hot reload, which is a different and also useful thing. The
 * definition comparison above is what makes the structural claim.
 *
 * <p><strong>The firing sequence.</strong> Structural equality is necessary and not sufficient: it
 * says nothing about whether the structure means what the author intended. Running a scenario
 * through both is what checks that the DSL wired the constraint to the field the author named,
 * rather than to a different field with an equally valid shape.
 */
public final class DslEquivalence {

  private DslEquivalence() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Asserts that a YAML rule file and a hand-built rule set are indistinguishable.
   *
   * @param yaml the rule file
   * @param handBuilt the same rules, built against the constraint AST
   * @param scenario what to do to the session before firing
   * @return the firing sequence both produced
   */
  public static FiringSequence assertEquivalent(final String yaml,
      final List<RuleDefinition> handBuilt, final Consumer<RuleSession> scenario) {
    return assertEquivalent(RuleSource.yaml("equivalence.yaml", yaml), handBuilt, scenario);
  }

  /**
   * Asserts that a rule file and a hand-built rule set are indistinguishable.
   *
   * @param source the rule file, in either serialization
   * @param handBuilt the same rules, built against the constraint AST
   * @param scenario what to do to the session before firing
   * @return the firing sequence both produced
   */
  public static FiringSequence assertEquivalent(final RuleSource source,
      final List<RuleDefinition> handBuilt, final Consumer<RuleSession> scenario) {
    final CompiledRuleSet fromFile = RuleFiles.compile(source);
    final CompiledRuleSet fromJava = RuleCompiler.compile(handBuilt);

    assertThat(fromFile.rules().stream().map(CompiledRule::source).toList())
        .describedAs("""
            The rule file and the hand-built rules compiled to different RULE DEFINITIONS.

            This is the structural comparison, and it is the one that means what it says: these are \
            records over strings, enums and JsonNodes, so a difference here is a real difference in \
            what was compiled -- a constraint in a different order, an operator mapped to the wrong \
            constant, or a literal of a different JsonNode type. Suspect the DSL first: the \
            hand-built form is the definition here, the way the naive matcher is for \
            MatcherEquivalence.""")
        .isEqualTo(handBuilt);

    assertThat(fromFile.version())
        .describedAs("""
            The rule file and the hand-built rules carry different rule-set VERSIONS.

            The definitions above compared equal, so this is stranger than a plain divergence: \
            equal definitions should digest identically. Suspect RuleCompiler.canonicalise -- most \
            likely a RuleDefinition component that was added without being rendered there.

            From the file: %s
            From Java:     %s""", fromFile.version(), fromJava.version())
        .isEqualTo(fromJava.version());

    final FiringSequence viaFile =
        Engine.run(fromFile, SessionOptions.defaults(), scenario);
    final FiringSequence viaJava =
        Engine.run(fromJava, SessionOptions.defaults(), scenario);

    assertThat(viaFile)
        .describedAs("""
            The rule file and the hand-built rules fired differently on the same facts.

            The version hashes agreed, so the compiled structures are identical and the difference \
            is downstream of compilation -- which should be impossible, and is worth understanding \
            before it is fixed.

            From the file:
            %s

            From Java:
            %s""", viaFile.describe(), viaJava.describe())
        .isEqualTo(viaJava);
    return viaFile;
  }
}
