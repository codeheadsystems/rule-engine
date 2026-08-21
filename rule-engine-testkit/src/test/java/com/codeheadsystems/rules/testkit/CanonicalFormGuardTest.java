package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RuleDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A structural guard on the rule-set version hash.
 *
 * <p>The hash is a content hash of the rule definitions, and it is stamped into every fire result
 * and every emitted event so that "which rules produced this decision" is answerable months later.
 * It broke once already, in a way that took a six-JVM harness to see: {@code Set.copyOf} randomises
 * iteration order with a per-process salt, so a rule with two or more tags hashed differently on
 * every run of identical rules.
 *
 * <p>That was fixed at the field, and {@code RuleCompiler.canonicalise} carries a comment saying
 * anything added to {@link RuleDefinition} must be added there too. A comment is not a guard, and
 * <strong>no same-JVM test can catch a recurrence by comparing hashes</strong> -- within one process
 * the salt is fixed, so a randomised set hashes consistently all day. So this asserts the property
 * the hash depends on rather than the hash itself: every collection reachable from a rule definition
 * iterates in a defined order.
 *
 * <p>If this fails, someone added an unordered collection somewhere in the definition model and the
 * version hash has quietly stopped being reproducible across JVMs.
 */
class CanonicalFormGuardTest {

  @Test
  @DisplayName("no collection reachable from a rule definition iterates in an undefined order")
  void definitionModelIsFullyOrdered() {
    final List<String> offenders = new ArrayList<>();
    walk(fullyPopulatedRule(), "RuleDefinition", offenders, new IdentityHashMap<>());

    assertThat(offenders)
        .describedAs("an unordered collection here makes the rule-set version hash differ between "
            + "JVM runs of identical rules, which no same-JVM test can detect")
        .isEmpty();
  }

  @Test
  @DisplayName("the guard covers every constraint and action type, not just the ones in use")
  void everySealedSubtypeIsExercised() {
    // A guard that only walks the types a convenience builder happens to produce would miss the
    // next one added. Assert the fixture covers the sealed hierarchies exhaustively.
    final Set<Class<?>> seen = new LinkedHashSet<>();
    collectTypes(fullyPopulatedRule(), seen, new IdentityHashMap<>());

    assertThat(seen).containsAll(List.of(Constraint.class.getPermittedSubclasses()));
    assertThat(seen).containsAll(List.of(ActionDefinition.class.getPermittedSubclasses()));
  }

  /**
   * A rule definition using every constraint type and every action type.
   *
   * @return the fixture
   */
  private static RuleDefinition fullyPopulatedRule() {
    final RuleDefinition built = Rules.rule("everything")
        .salience(5)
        .noLoop()
        .agendaGroup("review")
        .tag("beta").tag("alpha")
        .when("o", "Order", pattern -> pattern
            .eq("status", "PENDING")
            .between("total", 1, 100)
            .matches("code", "^A")
            .in("region", "US", "EU")
            .hasField("coupon", false)
            .constraint(new ExpressionConstraint("o.total > 1", Set.of("o", "c"))))
        .when("c", "Customer", pattern -> pattern.ref("id", "o.customerId"))
        .then(actions -> actions
            .setField("o", "status", "REVIEW")
            .insertFactAs("RiskSignal", "sig", "orderId", Rules.ref("o.id"))
            .retractFact("sig")
            .emit("order.flagged", "orderId", Rules.ref("o.id"))
            .callFunction("notify", "channel", "#risk"))
        .build();
    // A one-sided range too, since it is a distinct shape of the same record.
    final List<PatternDefinition> patterns = new ArrayList<>(built.when());
    patterns.add(PatternDefinition.of("x", "Extra",
        List.of(RangeConstraint.of("n", Operator.GTE, Facts.obj("v", 1).get("v")))));
    return new RuleDefinition(built.id(), built.salience(), patterns, built.then(),
        built.noLoop(), built.agendaGroup(), built.tags());
  }

  /**
   * Walks an object graph, recording every collection whose iteration order is undefined.
   *
   * @param value the value to inspect
   * @param path where it sits, for the failure message
   * @param offenders the list to record problems into
   * @param seen identity set guarding against cycles and repeated work
   */
  private static void walk(final Object value, final String path, final List<String> offenders,
      final Map<Object, Boolean> seen) {
    if (value == null || value instanceof String || value instanceof Number
        || value instanceof Boolean || value instanceof Enum<?>) {
      return;
    }
    // JsonNode is Jackson's own tree: ObjectNode preserves insertion order and ArrayNode is a list.
    // Descending into it would report Jackson's internals rather than ours.
    if (value instanceof JsonNode) {
      return;
    }
    if (seen.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof Optional<?> optional) {
      optional.ifPresent(inner -> walk(inner, path, offenders, seen));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      if (!(value instanceof SortedMap) && !isOrdered(value)) {
        offenders.add(path + " is a " + value.getClass().getSimpleName());
      }
      map.forEach((key, entry) -> {
        walk(key, path + "{key}", offenders, seen);
        walk(entry, path + "{value}", offenders, seen);
      });
      return;
    }
    if (value instanceof Set<?> set) {
      if (!(value instanceof SortedSet) && !isOrdered(value)) {
        offenders.add(path + " is a " + value.getClass().getSimpleName());
      }
      set.forEach(element -> walk(element, path + "[]", offenders, seen));
      return;
    }
    if (value instanceof Collection<?> collection) {
      collection.forEach(element -> walk(element, path + "[]", offenders, seen));
      return;
    }
    if (value.getClass().isRecord()) {
      for (final RecordComponent component : value.getClass().getRecordComponents()) {
        walk(read(value, component), path + "." + component.getName(), offenders, seen);
      }
    }
  }

  /**
   * Whether a collection's iteration order is defined by insertion.
   *
   * <p>{@code List.of} and {@code Set.of} both produce immutable collections, but only the list has
   * a defined order -- the set is the one that randomises. Java's small immutable sets are the trap
   * this whole test exists for, so they are deliberately not treated as ordered.
   *
   * @param value the collection
   * @return whether iteration order is stable across JVM runs
   */
  private static boolean isOrdered(final Object value) {
    final String name = value.getClass().getName();
    return name.startsWith("java.util.LinkedHash")
        || name.startsWith("java.util.Collections$UnmodifiableSortedSet")
        || name.startsWith("java.util.Collections$UnmodifiableSortedMap");
  }

  /**
   * Collects the concrete types reachable from a value.
   *
   * @param value the value to inspect
   * @param into the set to fill
   * @param seen identity set guarding against cycles
   */
  private static void collectTypes(final Object value, final Set<Class<?>> into,
      final Map<Object, Boolean> seen) {
    if (value == null || value instanceof JsonNode || seen.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof Optional<?> optional) {
      optional.ifPresent(inner -> collectTypes(inner, into, seen));
      return;
    }
    if (value instanceof Collection<?> collection) {
      collection.forEach(element -> collectTypes(element, into, seen));
      return;
    }
    if (value.getClass().isRecord()) {
      into.add(value.getClass());
      for (final RecordComponent component : value.getClass().getRecordComponents()) {
        collectTypes(read(value, component), into, seen);
      }
    }
  }

  /**
   * Reads one record component.
   *
   * @param owner the record instance
   * @param component the component to read
   * @return its value
   */
  private static Object read(final Object owner, final RecordComponent component) {
    try {
      return component.getAccessor().invoke(owner);
    } catch (final ReflectiveOperationException failed) {
      throw new IllegalStateException("cannot read " + component, failed);
    }
  }
}
