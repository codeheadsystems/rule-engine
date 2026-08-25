package com.codeheadsystems.rules.cel;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFileException;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a {@code condition:} may be written, and what it can see from there.
 *
 * <p><strong>This pins the one thing a first-time integrator had to guess.</strong> Asked to write
 * the archetypal aggregate rule -- total this account's transactions, compare against the limit held
 * on the account -- they hit two diagnostics that pointed at each other: {@code having} refuses a
 * {@code $ref} and said "use a condition", and a condition on that accumulate pattern is refused
 * with "express it with the pattern's own constraints", which is the {@code having} that was just
 * rejected. They escaped by inferring, from a phrase in the documentation about a condition running
 * "after them, once per surviving candidate" (now {@code dsl-guide.md}, then README), that a
 * condition might be a whole-tuple filter
 * rather than a per-pattern one -- and so wrote it on an <em>earlier</em> pattern, naming an alias
 * declared <em>below</em> it.
 *
 * <p>That inference was right, and nothing in the documentation said so. Worse, the documentation
 * said something that reads like the opposite: a {@code $ref} in a {@code where} must name an
 * earlier alias, and a forward reference is a compile error. A condition is exempt, because it is
 * evaluated against a complete match rather than while the join is being built.
 *
 * <p>So the exemption is asserted here rather than described somewhere deletable. The message in
 * {@code Accumulates} now names this route explicitly; if the route ever stops working, the message
 * becomes a lie and this test is what says so.
 */
class CelConditionPlacementTest {

  /** The rule set with the comparison written where the diagnostic now sends you. */
  private static final String ON_AN_EARLIER_PATTERN = """
      apiVersion: rules.v1
      rules:
        - id: over-daily-limit
          when:
            - fact: Account
              as: acct
              condition: "total > acct.dailyLimitCents"
            - fact: Transaction
              as: total
              quantifier: accumulate
              accumulate:
                sum: "amountCents"
              where:
                accountId: { eq: { $ref: acct.id } }
          then: [{ action: emit, event: over }]
      """;

  /**
   * Compiles a rule file with the expression compiler registered.
   *
   * @param yaml the rule file
   */
  private static void compile(final String yaml) {
    RuleFiles.compile(List.of(RuleSource.yaml("placement.yaml", yaml)),
        CompilerOptions.builder().expressions(CelExpressions.create()).build());
  }

  @Test
  @DisplayName("a condition may sit on an earlier pattern and read an alias bound below it")
  void aConditionIsExemptFromTheEarlierAliasRule() {
    /*
     * The whole point. `$ref` may only name an earlier alias -- that is what keeps the join graph
     * acyclic -- and a condition is not a join: it is a post-filter over a complete match, so every
     * alias the rule binds is available to it wherever it is written. Without this, the aggregate
     * rule below cannot be expressed at all, because `having` takes only literals.
     */
    assertThatCode(() -> compile(ON_AN_EARLIER_PATTERN))
        .describedAs("this is the route the malformed-accumulate diagnostic recommends; if it stops"
            + " compiling, that diagnostic is sending people into a wall again")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("but not on the quantified pattern itself, which is the loop that trapped somebody")
  void aConditionOnTheAccumulateIsStillRejected() {
    /*
     * Asserted beside its counterpart on purpose. These two facts are only confusing together --
     * "conditions can read anything" and "not from here" -- and a reader who meets the second
     * without the first concludes the rule is inexpressible. Keeping the pair in one file is what
     * makes the constraint legible rather than arbitrary.
     */
    /*
     * Written out rather than derived from the fixture above by string surgery. The first version
     * of this test did that, and Java text blocks strip the common leading indent -- so the replace
     * targets, written with the indentation as it appears in the source, matched nothing, both
     * edits silently did nothing, and the test compiled the WORKING rule while asserting a failure.
     * It failed loudly, which is the only reason it was caught; had the assertion been the other way
     * round it would have passed forever.
     */
    final String onTheAccumulate = """
        apiVersion: rules.v1
        rules:
          - id: over-daily-limit
            when:
              - fact: Account
                as: acct
              - fact: Transaction
                as: total
                quantifier: accumulate
                condition: "total > acct.dailyLimitCents"
                accumulate:
                  sum: "amountCents"
                where:
                  accountId: { eq: { $ref: acct.id } }
            then: [{ action: emit, event: over }]
        """;

    assertThatThrownBy(() -> compile(onTheAccumulate))
        .isInstanceOf(RuleFileException.class)
        .hasMessageContaining("condition on a")
        .hasMessageContaining("is not supported");
  }
}
