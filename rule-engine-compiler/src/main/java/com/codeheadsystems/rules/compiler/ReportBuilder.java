package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.report.CelCost;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.report.Diagnostic;
import com.codeheadsystems.rules.report.SharingStats;
import com.codeheadsystems.rules.report.UnindexedConstraint;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.Operator;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.fasterxml.jackson.core.JsonPointer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds §7.4's compiler report, on the shared graph and the rules that produced it.
 *
 * <p>Runs after node sharing, because two of the three things it reports are properties of the
 * shared graph rather than of the rules -- §6.5 is explicit that computing per-node facts before
 * sharing means computing them for nodes about to be merged away.
 */
final class ReportBuilder {

  private ReportBuilder() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Builds the report.
   *
   * @param rules the compiled rules, in compilation order
   * @param network the shared graph they compiled to
   * @param version the rule set's content hash
   * @param options what the caller declared, which decides what can be checked
   * @return the report
   */
  static CompilerReport build(final List<CompiledRule> rules, final Network network,
      final String version, final CompilerOptions options) {
    return new CompilerReport(
        version,
        List.of(),
        warnings(rules),
        unindexed(rules),
        List.<CelCost>of(),
        sharing(rules, network),
        unreachable(rules, options));
  }

  /**
   * Enumerates every constraint no index can serve (§3.3, §7.4).
   *
   * <p><strong>Only joins are listed as index failures, and that is the accurate reading rather
   * than a partial one.</strong> {@code NetworkBuilder.plansFor} builds an {@code IndexPlan}
   * exclusively from join constraints, so in v1 a single-fact constraint is never index-backed --
   * there would be nothing for such an index to do, since a pattern's memory already holds exactly
   * the facts that passed its alpha tests. A single-fact {@code NE} is therefore not a lost index;
   * it is one comparison per insert, which is why {@link UnindexedConstraint}'s note is careful to
   * separate the two costs rather than let a count imply one.
   *
   * <p>The join half restates {@code NetworkBuilder}'s rule -- indexable on {@code EQ} or on one of
   * the four ordering operators, and on nothing else -- rather than asking the network, because the
   * network records which paths <em>are</em> indexed and a path no plan mentions is
   * indistinguishable there from one no rule constrains. {@code CompilerReportTest} pins the two
   * against each other.
   *
   * @param rules the compiled rules
   * @return the unindexed constraints, in rule and declaration order
   */
  private static List<UnindexedConstraint> unindexed(final List<CompiledRule> rules) {
    final List<UnindexedConstraint> found = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      for (final PatternDefinition pattern : rule.source().when()) {
        for (final Constraint constraint : pattern.constraints()) {
          reasonFor(constraint).ifPresent(reason -> found.add(new UnindexedConstraint(
              rule.id(), pattern.alias(), constraint.field(), reason)));
        }
      }
    }
    return found;
  }

  /**
   * Why one constraint cannot be indexed, if it cannot.
   *
   * @param constraint the constraint
   * @return the reason, or empty when an index can serve it
   */
  private static Optional<UnindexedConstraint.Reason> reasonFor(final Constraint constraint) {
    return switch (constraint) {
      case JoinConstraint join -> indexableJoin(join.op())
          ? Optional.empty()
          : Optional.of(UnindexedConstraint.Reason.RESIDUAL_JOIN_CONDITION);
      case FieldConstraint field -> switch (field.op()) {
        case NE -> Optional.of(UnindexedConstraint.Reason.NE);
        case NOT_IN -> Optional.of(UnindexedConstraint.Reason.NOT_IN);
        case MATCHES -> Optional.of(UnindexedConstraint.Reason.MATCHES);
        /*
         * Everything else is either indexable or a presence test whose cost is a field lookup.
         * Listing HAS_FIELD and IS_NULL as "unindexed" would be true and useless: there is no
         * indexed alternative to compare them against, so the entry could only ever be noise.
         */
        default -> Optional.empty();
      };
      case ExpressionConstraint ignored ->
          Optional.of(UnindexedConstraint.Reason.CEL_EXPRESSION);
      case RangeConstraint ignored -> Optional.empty();
    };
  }

  /**
   * Whether a join operator can probe an index.
   *
   * @param operator the join's operator
   * @return true for equality and the four ordering comparisons
   */
  private static boolean indexableJoin(final Operator operator) {
    return operator == Operator.EQ || operator == Operator.GT || operator == Operator.GTE
        || operator == Operator.LT || operator == Operator.LTE;
  }

  /**
   * Finds tested paths that contain other tested paths of the same type (§3.4.2, §7.4).
   *
   * <p>§7.4 calls this "a performance smell with a usually-easy fix". A rule constraining
   * {@code /customer} when another constrains {@code /customer/id} compares the whole subtree on
   * every update to the type, where the deeper path compares one scalar. Detectable without any
   * data, because containment is a property of the paths alone.
   *
   * @param rules the compiled rules
   * @return one warning per rule holding such a path
   */
  private static List<Diagnostic> warnings(final List<CompiledRule> rules) {
    final Map<String, Set<JsonPointer>> byType = new LinkedHashMap<>();
    for (final CompiledRule rule : rules) {
      rule.testedPaths().forEach((type, paths) ->
          byType.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).addAll(paths));
    }
    final List<Diagnostic> warnings = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      /*
       * Sorted, because CompiledRule.testedPaths() freezes its sets with Set.copyOf, whose
       * iteration order carries a per-JVM salt -- the same hazard §7.3 names, arriving by a route
       * that cannot reach the agenda but can certainly reach a CI assertion. §7.4 wants this report
       * asserted on in a build, and a list whose order changes between runs of identical rules is
       * not something anybody can assert on. Rule order is the outer key because that is the order
       * an author reads their file in.
       */
      rule.testedPaths().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> entry.getValue().stream()
              .filter(path -> byType.getOrDefault(entry.getKey(), Set.of()).stream()
                  .anyMatch(other -> !other.equals(path) && contains(path, other)))
              .sorted(Comparator.comparing(JsonPointer::toString))
              .forEach(path -> warnings.add(new Diagnostic(rule.id(),
                  CompilerReport.SHALLOW_TESTED_PATH,
                  "this rule tests '" + render(path) + "' on " + entry.getKey() + ", which"
                      + " contains a deeper path another constraint tests. Every update to "
                      + entry.getKey() + " compares that whole subtree (§3.4.2); constraining the"
                      + " deeper path costs one scalar compare",
                  Optional.of(path.toString())))));
    }
    return warnings;
  }

  /**
   * Renders a pointer for a diagnostic.
   *
   * <p>The root pointer renders as the empty string, which reads as a missing value rather than as
   * a path. It is also the <em>worst</em> shallow tested path there is -- a constraint on the whole
   * payload compares every byte of it on every update -- so it is the last one that should look
   * like a formatting slip.
   *
   * @param path the pointer
   * @return its text, or a readable stand-in for the root
   */
  private static String render(final JsonPointer path) {
    return path.toString().isEmpty() ? "(the whole payload)" : path.toString();
  }

  /**
   * Whether one pointer addresses a subtree containing another.
   *
   * @param outer the shallower pointer
   * @param inner the candidate descendant
   * @return true when {@code inner} sits strictly beneath {@code outer}
   */
  private static boolean contains(final JsonPointer outer, final JsonPointer inner) {
    final String outerText = outer.toString();
    final String innerText = inner.toString();
    return innerText.length() > outerText.length() && innerText.startsWith(outerText + "/");
  }

  /**
   * Counts what sharing achieved (§6.5, §7.4).
   *
   * @param rules the compiled rules
   * @param network the shared graph
   * @return the statistics
   */
  private static SharingStats sharing(final List<CompiledRule> rules, final Network network) {
    int occurrences = 0;
    int joinEdges = 0;
    for (final CompiledRule rule : rules) {
      for (final var pattern : rule.patterns()) {
        occurrences += pattern.alphaTests().size();
        joinEdges += pattern.joinTests().size();
      }
    }
    /*
     * The node count is asked of the graph rather than re-derived by deduplicating constraints
     * here. Both answers agree today, and that is exactly the problem with computing it twice: this
     * record exists to describe the shared graph, so a second implementation of "how many distinct
     * alpha nodes are there" could only ever drift away from the one that built them.
     */
    return SharingStats.of(rules.size(), network.alphaNodeCount(), occurrences,
        network.patternNodes().size(), joinEdges);
  }

  /**
   * Finds rules nothing can activate (§7.4).
   *
   * <p>Only answerable when the caller declared its fact types; see
   * {@link CompilerOptions.Builder#declaredFactTypes(java.util.Set)}. Types the rule set derives
   * through {@code insertFact} join the declared ones, since a rule set that produces
   * {@code RiskSignal} makes a rule matching {@code RiskSignal} reachable.
   *
   * @param rules the compiled rules
   * @param options what the caller declared
   * @return the ids of rules no fact can reach, or empty when the caller did not declare
   */
  private static List<String> unreachable(final List<CompiledRule> rules,
      final CompilerOptions options) {
    return options.declaredFactTypes().map(declared -> {
      /*
       * A least fixpoint, not one pass. A rule's insertFact types only make other rules reachable
       * if that rule can itself fire, and taking every insertFact unconditionally lets an
       * unreachable rule vouch for its own consumers:
       *
       *   ghost-derives    (when: Ghost,   then: insertFact Derived)   <- can never fire
       *   consumes-derived (when: Derived, then: ...)                  <- therefore never either
       *
       * One pass reports only ghost-derives, which is worse than reporting nothing: the guide
       * recommends asserting this list is empty in CI, so an under-report is a build that passes
       * while a rule sits dead.
       */
      final Set<String> reachable = new LinkedHashSet<>(declared);
      boolean grew = true;
      while (grew) {
        grew = false;
        for (final CompiledRule rule : rules) {
          if (!reachable.containsAll(rule.factTypes())) {
            continue;
          }
          for (final ActionDefinition action : rule.source().then()) {
            if (action instanceof InsertFact insert && reachable.add(insert.factType())) {
              grew = true;
            }
          }
        }
      }
      return rules.stream()
          .filter(rule -> !reachable.containsAll(rule.factTypes()))
          .map(CompiledRule::id)
          .toList();
    }).orElseGet(List::of);
  }
}
