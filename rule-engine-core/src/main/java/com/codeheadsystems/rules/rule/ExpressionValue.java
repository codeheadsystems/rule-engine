package com.codeheadsystems.rules.rule;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * An expression used as a value in a {@code then} block (spec §6.4, extending §6.2.2).
 *
 * <p>The right-hand-side counterpart of {@link ExpressionConstraint}, and a deliberate extension
 * beyond §11.3's closed verb set. The argument for it is that the alternative is worse: computing
 * {@code subtotal + tax} today needs {@code callFunction}, which §4.6 runs at commit and explicitly
 * does <em>not</em> make transactional -- so a pure arithmetic expression currently has to be
 * bought with the one action that can leave half-applied state behind. An expression has no side
 * effects and cannot.
 *
 * <p>It is also the cheaper half of §6.4. A condition is evaluated once per candidate; a value is
 * evaluated once per <em>firing</em>. §6.4's warning that "an unindexed CEL condition against
 * 100 000 facts is 100 000 evaluations per cycle" is about the left-hand side and does not apply
 * here.
 *
 * <p>Holds source text only, like {@link ExpressionConstraint}. The compiled program lives on
 * {@code CompiledRule}, because this record is part of {@code RuleDefinition} and therefore of
 * §5.6's content hash -- a hash over a compiled artifact would change with the expression compiler
 * rather than with the rule.
 *
 * @param expression the expression source text
 * @param referencedAliases the aliases the expression reads, in a sorted immutable set so that
 *     iteration order is stable across JVM runs -- see {@link RuleDefinition#tags()} for what that
 *     protects.
 *     <p><strong>Optional, and advisory</strong>, exactly as {@link ExpressionConstraint}'s is. The
 *     expression language checks its own variables against the ones the rule declares, whether this
 *     set is populated or not, so the DSL leaves it <strong>empty</strong>. What it adds is one
 *     extra compile-time check for a Java-authored rule.
 *     <p>It reaches §5.6's content hash, so a rule written in YAML and the same rule built in Java
 *     carry different rule-set versions unless the Java author also passes an empty set. Prefer
 *     empty
 */
public record ExpressionValue(String expression, Set<String> referencedAliases)
    implements ValueExpr {

  /**
   * Canonical constructor. Defensively copies {@code referencedAliases}.
   *
   * @param expression the source text
   * @param referencedAliases the aliases the expression reads
   */
  public ExpressionValue {
    Objects.requireNonNull(expression, "expression");
    referencedAliases = Collections.unmodifiableSortedSet(new TreeSet<>(referencedAliases));
  }
}
