package com.codeheadsystems.rules.eval;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.AggregateFunction;
import com.codeheadsystems.rules.rule.CompiledAccumulate;
import com.codeheadsystems.rules.value.Canonical;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Iterator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Folds an {@code ACCUMULATE} pattern's scope into a value (spec §2.5's second amendment).
 *
 * <p><strong>Computed on every read and never stored, which is what keeps §3.2.2's invariant
 * intact.</strong> "Tuples bind {@code FactHandle}s, never {@code Fact} objects" exists so that
 * there is exactly one place a payload lives and nothing downstream can serve a stale one -- and an
 * aggregate is the most stale-able value in the engine, since any fact in its scope changing makes
 * it wrong. Binding it into the tuple would have broken the invariant outright under §4.3's shape,
 * where a tuple is materialised and held across cycles. So an accumulate alias binds a <em>name</em>
 * that is resolved here, from working memory, at each read -- the same trick
 * {@code Tuple.payloadOf} already uses for handles.
 *
 * <p>The cost is that a rule reading the same accumulate from two actions folds it twice. That is
 * the honest trade and it is the one this engine keeps making: {@link Negations} and
 * {@link Universals} scan rather than probe, for the same reason -- one implementation the three
 * matchers and §7.2's explainer all share, at a cost the naive matcher already had.
 *
 * <p><strong>Determinism is structural.</strong> The scope is walked in working-memory order --
 * insertion order per type, which an update moves a fact to the end of -- and
 * {@code SUM} and {@code AVERAGE} fold through {@link BigDecimal} rather than {@code double}, so
 * neither the walk order nor floating-point association can move the answer between hosts (§7.3).
 *
 * <p><strong>A fact whose field is absent, non-numeric or non-finite is skipped, not counted as
 * zero.</strong>
 * §2.6.1 is emphatic that absent is not zero and not null, and the arithmetic has to agree with the
 * matching semantics or the same rule set means two things. The consequence is worth stating: the
 * {@code AVERAGE} of a scope where half the facts lack the field is the average of the half that
 * have it, not half the answer. Filter the scope with the pattern's own constraints -- a
 * {@code hasField} is what says "only the ones that have one".
 */
public final class Accumulators {

  /**
   * How {@code AVERAGE} divides.
   *
   * <p>{@code DECIMAL64} rather than exact division, because an exact {@code BigDecimal} divide
   * throws on a non-terminating quotient -- one third of a sum is the ordinary case, not the edge
   * one -- and rather than {@code DECIMAL128}, because sixteen significant digits is past the point
   * where a rule author is reasoning about the number. Fixed rather than configurable so that two
   * hosts agree (§7.3).
   */
  private static final MathContext AVERAGE_PRECISION = MathContext.DECIMAL64;

  /** Not instantiable: a fold with no state of its own. */
  private Accumulators() {
  }

  /**
   * Folds one accumulate pattern over its scope, against a complete binding.
   *
   * @param accumulate the compiled pattern, whose join tests point at positions in {@code bound}
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param memory the working memory to fold over
   * @return the answer, or {@link MissingNode} when the scope is empty and the function has no
   *     identity ({@code MIN}, {@code MAX}, {@code AVERAGE}). {@code COUNT} answers zero and
   *     {@code SUM} answers zero, which are their identities
   */
  public static JsonNode evaluate(final CompiledAccumulate accumulate, final long[] bound,
      final WorkingMemory memory) {
    return evaluate(accumulate, bound,
        () -> memory.factsOfType(accumulate.scope().factType()).iterator(), memory);
  }

  /**
   * The same fold, against a population the caller already holds.
   *
   * <p>Exists for the reason {@link Negations#scan} does: §7.2's explainer asks once per complete
   * tuple it examines and must not re-snapshot working memory each time.
   *
   * @param accumulate the compiled pattern
   * @param bound the handle ids the positive tuple binds, in pattern order
   * @param candidates every fact of the accumulated type
   * @param memory the working memory the join tests dereference their other side from
   * @return the answer, as {@link #evaluate(CompiledAccumulate, long[], WorkingMemory)} defines it
   */
  public static JsonNode evaluate(final CompiledAccumulate accumulate, final long[] bound,
      final Iterable<Fact> candidates, final WorkingMemory memory) {
    long count = 0;
    BigDecimal total = BigDecimal.ZERO;
    JsonNode extreme = MissingNode.getInstance();
    final Iterator<Fact> iterator = candidates.iterator();
    while (iterator.hasNext()) {
      final Fact candidate = iterator.next();
      if (!inScope(accumulate, candidate, bound, memory)) {
        continue;
      }
      if (accumulate.function() == AggregateFunction.COUNT) {
        count++;
        continue;
      }
      final JsonNode value = accumulate.field().orElseThrow().get(candidate.payload());
      switch (accumulate.function()) {
        case SUM, AVERAGE -> {
          final BigDecimal number = decimalOf(value);
          if (number != null) {
            total = total.add(number);
            count++;
          }
        }
        case MIN, MAX -> extreme = pick(accumulate.function(), extreme, value);
        case COUNT -> throw new IllegalStateException("COUNT handled above");
      }
    }
    return answer(accumulate.function(), count, total, extreme);
  }

  /**
   * Whether a candidate is one the fold is about.
   *
   * <p>Every constraint filters, joins and literals alike -- see {@link CompiledAccumulate} for why
   * an accumulate does not split them the way a {@link Universals universal} must.
   *
   * @param accumulate the compiled pattern
   * @param candidate the fact being considered
   * @param bound the handle ids the positive tuple binds
   * @param memory the working memory the join tests dereference their other side from
   * @return whether the candidate contributes
   */
  private static boolean inScope(final CompiledAccumulate accumulate, final Fact candidate,
      final long[] bound, final WorkingMemory memory) {
    if (accumulate.scope().conflictsWith(bound, candidate.handle().id())) {
      // §1's implicit inequality: an accumulate over a type the rule already binds is about the
      // OTHER facts of that type. "The total of this customer's other orders" is what it means.
      return false;
    }
    return PatternTests.alphasHold(accumulate.scope(), candidate.payload())
        && PatternTests.joinsHold(accumulate.scope(), candidate.payload(), bound, memory);
  }

  /**
   * Turns the running state into the answer.
   *
   * @param function what was being computed
   * @param count how many facts contributed
   * @param total the running sum, for {@code SUM} and {@code AVERAGE}
   * @param extreme the running extreme, for {@code MIN} and {@code MAX}
   * @return the answer
   */
  private static JsonNode answer(final AggregateFunction function, final long count,
      final BigDecimal total, final JsonNode extreme) {
    return switch (function) {
      case COUNT -> LongNode.valueOf(count);
      case SUM -> DecimalNode.valueOf(total);
      case MIN, MAX -> extreme;
      // Empty rather than zero, and the reason is in AggregateFunction: the mean of nothing is not
      // a number, and answering zero makes "average below 10" true for a scope with nothing in it.
      case AVERAGE -> count == 0
          ? MissingNode.getInstance()
          : DecimalNode.valueOf(total.divide(BigDecimal.valueOf(count), AVERAGE_PRECISION));
    };
  }

  /**
   * Keeps whichever of two values the function wants.
   *
   * @param function {@code MIN} or {@code MAX}
   * @param running the extreme so far, possibly missing
   * @param value the candidate's value
   * @return the new extreme
   */
  private static JsonNode pick(final AggregateFunction function, final JsonNode running,
      final JsonNode value) {
    if (value.isMissingNode() || value.isNull()) {
      return running;
    }
    if (running.isMissingNode()) {
      return value;
    }
    // Through Canonical, so 10 and 10.0 compare equal and a string extreme is ordered as §2.6.1
    // orders strings. A pair Canonical cannot compare -- a string against a number -- leaves the
    // running extreme alone rather than imposing an order the rest of the engine does not have.
    return Canonical.compare(value, running)
        .stream()
        .anyMatch(order -> function == AggregateFunction.MIN ? order < 0 : order > 0)
        ? value
        : running;
  }

  /**
   * A value's decimal form, or null when this engine will not order it.
   *
   * <p>{@link Canonical#orderable} rather than a hand-copy of it, which is what this was: the same
   * {@code isNumber} plus finite check, written "letter for letter" from a private method beside
   * it, in one of three places that had their own. A fourth call site was then added without the
   * guard and threw on a string -- agreement by copy is agreement until somebody edits one.
   *
   * @param value the field's value
   * @return the decimal, or null when the field was absent, null, non-numeric or non-finite
   */
  private static BigDecimal decimalOf(final JsonNode value) {
    return Canonical.orderable(value).orElse(null);
  }
}
