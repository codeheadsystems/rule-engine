package com.codeheadsystems.rules.eval;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.AggregateTest;
import com.codeheadsystems.rules.rule.CompiledAccumulate;
import com.codeheadsystems.rules.rule.CompiledPattern;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.value.Comparisons;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Whether a set of handles still constitutes a match of a rule (spec §4.4's amendment).
 *
 * <p><strong>Asked of one tuple rather than derived from a match set, and that is what makes truth
 * maintenance work the same under all three matchers.</strong> The obvious implementation of a
 * justification check is to recompute the rule's matches and see whether the justifying tuple is
 * still among them. That works for the two recomputing shapes and not for the streaming one, whose
 * whole point (§4.3) is that its conflict set holds only what is <em>waiting to fire</em> -- a match
 * that fired is pulled out and would read as gone. Re-asking the question of the tuple directly
 * needs no match set at all, so the answer cannot depend on which matcher produced it.
 *
 * <p>It is the same question the matchers ask, in the same order and through the same code:
 * every handle still present, every alpha test, every join, §1's implicit inequality, then the
 * post-filters {@code RecomputingAgenda} applies -- {@link Negations}, {@link Universals},
 * {@link Accumulators} for a {@code having}, and {@link Conditions}, in that order. Four gates, and
 * all four have to be here: a justification is still valid only if the match still passes
 * everything the agenda would ask of it. Nothing here re-implements a predicate that exists
 * elsewhere, which is the whole reason those classes are public.
 *
 * <p><strong>What it deliberately does not check is refraction.</strong> A justification asks
 * whether the match still <em>holds</em>, not whether it would fire again; a fired match is
 * refracted and still perfectly valid, and treating refraction as invalidity would retract every
 * logically-inserted fact the moment its rule fired.
 */
public final class TupleMatch {

  /** Not instantiable: a predicate with no state of its own. */
  private TupleMatch() {
  }

  /**
   * Whether these handles, in pattern order, still match this rule.
   *
   * @param rule the rule
   * @param bound the handle ids the tuple binds, in pattern order
   * @param memory the working memory to ask
   * @return true when every test the matcher would apply still holds
   */
  public static boolean holds(final CompiledRule rule, final long[] bound,
      final WorkingMemory memory) {
    if (bound.length != rule.patterns().size()) {
      return false;
    }
    final Map<String, JsonNode> byAlias = new LinkedHashMap<>();
    for (int position = 0; position < bound.length; position++) {
      final CompiledPattern pattern = rule.patterns().get(position);
      final Optional<Fact> fact = memory.get(new FactHandle(bound[position]));
      if (fact.isEmpty() || !fact.get().type().equals(pattern.factType())) {
        // Retracted. Handle ids are never reissued within a session, so absence is the only case
        // -- the type check beside it is a cheap guard on a contract rather than a hazard. Treating
        // a gone fact as present would keep a conclusion alive on nothing, which is the exact
        // failure truth maintenance exists to prevent.
        return false;
      }
      byAlias.put(pattern.alias(), fact.get().payload());
    }
    for (int position = 0; position < bound.length; position++) {
      final CompiledPattern pattern = rule.patterns().get(position);
      final JsonNode payload = byAlias.get(pattern.alias());
      if (pattern.conflictsWith(bound, bound[position])
          || !PatternTests.alphasHold(pattern, payload)
          || !PatternTests.joinsHold(pattern, payload, bound, memory)) {
        return false;
      }
    }
    for (final CompiledPattern negation : rule.negations()) {
      if (Negations.witness(negation, bound, memory).isPresent()) {
        return false;
      }
    }
    for (final CompiledPattern universal : rule.universals()) {
      if (Universals.counterexample(universal, bound, memory).isPresent()) {
        return false;
      }
    }
    /*
     * The accumulates last, and they have to be here at all: a conclusion justified by "this
     * order's line items total over 100" must be withdrawn when a line item leaves and the total
     * drops. Leaving them out would make truth maintenance correct for two of the three §2.5
     * quantifiers and silently wrong for the third.
     */
    for (final CompiledAccumulate accumulate : rule.accumulates()) {
      final Optional<AggregateTest> having = accumulate.having();
      if (having.isPresent() && !Comparisons.test(having.get().op(),
          Accumulators.evaluate(accumulate, bound, memory), having.get().literal())) {
        return false;
      }
    }
    return Conditions.holdFor(rule, alias -> {
      final JsonNode payload = byAlias.get(alias);
      if (payload != null) {
        return payload;
      }
      return rule.accumulateNamed(alias)
          .map(accumulate -> Accumulators.evaluate(accumulate, bound, memory))
          .orElseGet(MissingNode::getInstance);
    });
  }
}
