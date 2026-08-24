package com.codeheadsystems.rules.dsl;

import com.codeheadsystems.rules.rule.Accumulate;
import com.codeheadsystems.rules.rule.AggregateFunction;
import com.codeheadsystems.rules.rule.AggregateTest;
import com.codeheadsystems.rules.rule.Operator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A pattern's {@code accumulate} block, read (spec §2.5's second amendment).
 *
 * <p>Gate two of the three §8 describes, and it does what a schema cannot: the schema can say every
 * key is a string or a boolean and that {@code having} is an object, but not that <em>exactly one</em>
 * function key is present, nor that the operator inside {@code having} is one this engine has. Both
 * are checked here, in the engine's own vocabulary, and both stay here even where the schema
 * happens to catch them first -- "the gate ahead of me guarantees this" is how a loosened schema
 * becomes a silently dropped constraint.
 *
 * <p>The {@code having} operator goes through {@link OperatorMaps}' own table rather than a second
 * copy, so {@code gt} means beside an accumulate exactly what it means beside a field.
 */
final class Accumulates {

  private Accumulates() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Reads a pattern's accumulate block.
   *
   * @param node the block, or null when the pattern has none
   * @param pointer the block's JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the compiled spec, empty when there is no block or when it was rejected
   */
  static Optional<Accumulate> of(final AccumulateNode node, final String pointer,
      final Diagnostics diagnostics) {
    if (node == null) {
      return Optional.empty();
    }
    /*
     * A LinkedHashMap rather than Map.of, because Map.of rejects a null value -- and every one of
     * these is null in the ordinary case, since a block names one function and omits four. The
     * order is the order the diagnostic lists them in, which is why it is kept.
     */
    final Map<AggregateFunction, String> written = new LinkedHashMap<>();
    written.put(AggregateFunction.SUM, node.sum());
    written.put(AggregateFunction.MIN, node.min());
    written.put(AggregateFunction.MAX, node.max());
    written.put(AggregateFunction.AVERAGE, node.average());
    final List<String> named = new ArrayList<>();
    AggregateFunction function = null;
    Optional<String> field = Optional.empty();
    for (final Map.Entry<AggregateFunction, String> candidate : written.entrySet()) {
      if (candidate.getValue() != null) {
        named.add(candidate.getKey().name().toLowerCase(Locale.ROOT));
        function = candidate.getKey();
        field = Optional.of(candidate.getValue());
      }
    }
    if (Boolean.TRUE.equals(node.count())) {
      named.add("count");
      function = AggregateFunction.COUNT;
      field = Optional.empty();
    }
    if (named.size() != 1) {
      /*
       * Sorted, because the map above is iterated for convenience and §7.3 covers any diagnostic a
       * build asserts on as surely as it covers the agenda -- a message naming two functions in a
       * different order on a different host is a message somebody eventually distrusts.
       */
      Collections.sort(named);
      diagnostics.error(DslError.MALFORMED_ACCUMULATE, pointer,
          named.isEmpty()
              ? "an 'accumulate' block needs exactly one of sum, count, min, max or average"
              : "an 'accumulate' block names " + named.size() + " functions (" + named
                  + "); it computes one value, so it takes one");
      return Optional.empty();
    }
    final Optional<String> folded = field;
    return having(node.having(), pointer + "/having", diagnostics)
        .map(test -> new Accumulate(function(named.getFirst()), folded, test));
  }

  /**
   * Resolves a function name to its constant.
   *
   * @param written the lower-case key that was present
   * @return the function
   */
  private static AggregateFunction function(final String written) {
    return AggregateFunction.valueOf(written.toUpperCase(Locale.ROOT));
  }

  /**
   * Reads the optional test on the answer.
   *
   * @param node the operator map, or null when there is none
   * @param pointer its JSON Pointer, for diagnostics
   * @param diagnostics collects problems
   * @return the test wrapped in a present optional, or empty when the block was rejected
   */
  private static Optional<Optional<AggregateTest>> having(final JsonNode node, final String pointer,
      final Diagnostics diagnostics) {
    if (node == null) {
      return Optional.of(Optional.empty());
    }
    if (!node.isObject() || node.size() != 1) {
      diagnostics.error(DslError.MALFORMED_ACCUMULATE, pointer,
          "'having' takes exactly one operator, as a field's 'where' entry does");
      return Optional.empty();
    }
    final String written = node.propertyNames().iterator().next();
    final Optional<Operator> op = OperatorMaps.comparisonOf(written);
    if (op.isEmpty()) {
      diagnostics.error(DslError.MALFORMED_ACCUMULATE, pointer + "/" + written,
          "'" + written + "' cannot test an aggregate; it compares a value with no field behind"
              + " it, so only eq, ne, gt, gte, lt and lte apply");
      return Optional.empty();
    }
    final String at = pointer + "/" + written;
    final JsonNode operand = node.get(written);
    /*
     * A $ref here is the obvious next thought -- compare this total against that order's cap -- and
     * it is exactly what an accumulate cannot do, because a join needs a fact on both sides. Taken
     * as a literal it would compile into a rule that never matches, silently, which is the failure
     * §6.2.3 exists to prevent. Named rather than swallowed, with the route that does work.
     *
     * THE MESSAGE MUST SAY WHERE THE CONDITION GOES, and an earlier version did not. It said only
     * "use a 'condition' expression", and a condition written on THIS pattern is rejected by
     * RuleCompiler -- "a condition on a ACCUMULATE pattern is not supported. Express it with the
     * pattern's own constraints" -- which is the `having` this branch just refused. Two diagnostics
     * pointing at each other is worse than one that merely says no: a reader who follows them
     * literally ends up back where they started and concludes the rule cannot be written. Found by
     * somebody integrating from the documentation alone, who lost roughly half their time to it and
     * escaped by guessing the answer below.
     */
    if (References.isRef(operand)) {
      diagnostics.error(DslError.MALFORMED_ACCUMULATE, at,
          "'having' takes a literal; a $ref would compare the answer against a fact, and there is"
              + " no fact on the accumulate's side. Put the comparison in a 'condition' on a"
              + " pattern that BINDS a fact -- typically the one holding the limit -- where it can"
              + " read that fact and this accumulate's alias together, as in"
              + " `condition: \"total > acct.dailyLimitCents\"` on the pattern bound as 'acct'."
              + " It cannot go on this pattern: a condition on an accumulate is itself rejected");
      return Optional.empty();
    }
    /*
     * Through readLiteral, so §6.2.3's $-key rules apply at any depth here as they do everywhere
     * else. Skipping it left `{ $$escaped: 1 }` unresolved inside a having and nowhere else.
     */
    return References.readLiteral(operand, at, diagnostics)
        .map(literal -> Optional.of(new AggregateTest(op.get(), literal)));
  }
}
