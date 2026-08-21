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
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.schema.Presence;
import com.codeheadsystems.rules.schema.SchemaType;
import com.codeheadsystems.rules.value.Canonical;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
        warnings(rules, options.factSchemas()),
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
   * Everything §7.4 calls a warning: compiled, and worth a second look.
   *
   * <p>Four kinds, gathered here so that a build has one list to surface:
   *
   * <ul>
   *   <li>a range whose own bounds exclude every value;
   *   <li>an anti-match a schema proves is always true, and therefore filters nothing;
   *   <li>§2.6.1's {@code NE}-on-an-optional-path trap;
   *   <li>a tested path that contains a deeper tested path of the same type -- §7.4 calls this "a
   *       performance smell with a usually-easy fix", because a rule constraining {@code /customer}
   *       compares that whole subtree on every update to the type, where the deeper path compares
   *       one scalar. Detectable without any data, since containment is a property of the paths.
   * </ul>
   *
   * <p>The last three need a registered schema for part or all of their work and stay silent
   * without one; the first needs none.
   *
   * @param rules the compiled rules
   * @param schemas what is known about the payloads
   * @return every warning, in rule and declaration order within each kind
   */
  private static List<Diagnostic> warnings(final List<CompiledRule> rules,
      final FactSchemas schemas) {
    final Map<String, Set<JsonPointer>> byType = new LinkedHashMap<>();
    for (final CompiledRule rule : rules) {
      rule.testedPaths().forEach((type, paths) ->
          byType.computeIfAbsent(type, ignored -> new LinkedHashSet<>()).addAll(paths));
    }
    final List<Diagnostic> warnings = new ArrayList<>(impossibleRanges(rules));
    warnings.addAll(antiMatchesOnOptionalPaths(rules, schemas));
    warnings.addAll(vacuousAntiMatches(rules, schemas));
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
    /*
     * Sorted by the rule they belong to. An author reads their file top to bottom, and the four
     * checks above each sweep every rule in turn -- unsorted, one rule's problems arrive in four
     * separate places in the list. A stable sort, so each check's own declaration order survives
     * inside a rule, and compilation order is what orders the groups.
     */
    final Map<String, Integer> ruleOrder = new LinkedHashMap<>();
    rules.forEach(rule -> ruleOrder.put(rule.id(), ruleOrder.size()));
    warnings.sort(Comparator.comparingInt(warning -> ruleOrder.get(warning.ruleId())));
    return warnings;
  }

  /**
   * Finds anti-matches a schema proves are always true (§2.6.1, §7.4 warnings).
   *
   * <p>The mirror of the compiler's cross-type <em>error</em>, and it exists because §2.6.1 makes
   * the two cases opposite rather than alike. Against a wrong-typed literal, {@code EQ} is false --
   * so the rule can never match, and the compiler rejects it. But {@code NE} is {@code !EQ} and so
   * comes out <strong>true</strong>: {@code status: { ne: 5 }} on a string field does not fail, it
   * stops constraining anything, and the rule quietly matches every fact it was meant to filter.
   *
   * <p>That is the "matches more than its author wrote" failure this codebase treats as the worst
   * kind, so it is worth reporting -- as a warning, because unlike the {@code EQ} case the rule
   * still does something, and a build should not fail on a constraint that is merely useless.
   *
   * @param rules the compiled rules
   * @param schemas what is known about the payloads
   * @return one warning per vacuous anti-match
   */
  private static List<Diagnostic> vacuousAntiMatches(final List<CompiledRule> rules,
      final FactSchemas schemas) {
    final List<Diagnostic> warnings = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      for (final PatternDefinition pattern : rule.source().when()) {
        for (final Constraint constraint : pattern.constraints()) {
          if (!(constraint instanceof FieldConstraint field)
              || (field.op() != Operator.NE && field.op() != Operator.NOT_IN)) {
            continue;
          }
          schemas.typeOf(pattern.factType(), field.field())
              .filter(type -> vacuous(type, field))
              .ifPresent(type -> warnings.add(new Diagnostic(rule.id(),
                  CompilerReport.VACUOUS_ANTI_MATCH,
                  pattern.alias() + "." + field.field() + " is declared "
                      + type.name().toLowerCase(Locale.ROOT) + ", and "
                      + field.op().name().toLowerCase(Locale.ROOT)
                      + " is the negation of equality -- so §2.6.1 makes this constraint always"
                      + " true, and it filters nothing. Did you mean a literal of type "
                      + type.compatibilityClass() + "?",
                  Optional.of(field.field()))));
        }
      }
    }
    return warnings;
  }

  /**
   * Whether an anti-match can never be false.
   *
   * @param type the declared type
   * @param field the constraint
   * @return true when no candidate shares the field's compatibility class
   */
  private static boolean vacuous(final SchemaType type, final FieldConstraint field) {
    if (field.op() == Operator.NOT_IN) {
      if (!field.literal().isArray() || field.literal().isEmpty()) {
        return false;
      }
      for (final JsonNode candidate : field.literal()) {
        if (type.comparableWith(candidate)) {
          return false;
        }
      }
      return true;
    }
    return !type.comparableWith(field.literal());
  }

  /**
   * Finds §2.6.1's {@code NE}-on-an-optional-path trap (§7.4 warnings).
   *
   * <p>The trap: {@code ne} is defined as {@code !eq}, so {@code status: { ne: "CLOSED" }} is
   * <strong>true for a fact with no status at all</strong>. §2.6.1 accepts that deliberately rather
   * than moving to three-valued logic, and every document in this repository warns about it -- but
   * a warning in prose only helps the author who already suspected. This one names the rule.
   *
   * <p><strong>It needs a schema, and stays silent without one.</strong> "Optional" is a claim about
   * the data, not about the rule, so only a registered schema can make it. Warning whenever a path
   * is not known to be required would fire on every {@code ne} in every rule set with no schema,
   * which is how a warning channel stops being read at all -- and §7.4's warnings are meant to be
   * surfaced in CI.
   *
   * <p>A pattern that also constrains the same path with {@code hasField: true} is doing exactly
   * what the fix asks for, so it is not warned about.
   *
   * @param rules the compiled rules
   * @param schemas what is known about the payloads
   * @return one warning per unguarded anti-match on an optional path
   */
  private static List<Diagnostic> antiMatchesOnOptionalPaths(final List<CompiledRule> rules,
      final FactSchemas schemas) {
    final List<Diagnostic> warnings = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      for (final PatternDefinition pattern : rule.source().when()) {
        for (final Constraint constraint : pattern.constraints()) {
          if (!(constraint instanceof FieldConstraint field)
              || (field.op() != Operator.NE && field.op() != Operator.NOT_IN)
              || schemas.presence(pattern.factType(), field.field()) != Presence.OPTIONAL
              || guardedByPresenceTest(pattern, field.field())
              // A vacuous anti-match already matches everything, absent field or not, and that
              // warning says so more usefully. Two reports of one mistake on one line is noise.
              || schemas.typeOf(pattern.factType(), field.field())
                  .filter(type -> vacuous(type, field)).isPresent()) {
            continue;
          }
          warnings.add(new Diagnostic(rule.id(), CompilerReport.NE_ON_OPTIONAL_PATH,
              pattern.alias() + "." + field.field() + " is optional on "
                  + pattern.factType() + ", and " + field.op().name().toLowerCase(Locale.ROOT)
                  + " is defined as the negation of equality -- so this matches a fact with no "
                  + field.field() + " at all (§2.6.1). Pair it with hasField: true if you meant"
                  + " \"present, and not that\"",
              Optional.of(field.field())));
        }
      }
    }
    return warnings;
  }

  /**
   * Whether a pattern already requires the field to be present.
   *
   * @param pattern the pattern
   * @param field the dotted field path
   * @return true when a {@code hasField: true} on that path guards the anti-match
   */
  private static boolean guardedByPresenceTest(final PatternDefinition pattern,
      final String field) {
    return pattern.constraints().stream()
        .anyMatch(other -> other instanceof FieldConstraint guard
            && guard.op() == Operator.HAS_FIELD
            && guard.field().equals(field)
            && guard.literal().booleanValue());
  }

  /**
   * Finds ranges whose own bounds exclude every value (§7.4 warnings).
   *
   * <p>{@code { between: { from: 500, to: 100 } }} compiles, matches nothing, and says nothing --
   * which is the same category as a rule on a fact type nobody inserts, and belongs in the same
   * channel. A warning rather than an error, deliberately: the compiler's errors are for rules it
   * cannot build, and it can build this one perfectly well.
   *
   * <p>Only comparable bounds are checked. §2.6.1 orders numbers and strings and nothing else, so
   * {@link Canonical#compare} returning empty means "these two cannot be ordered", which is a
   * different problem and not this one's to report. A range with only one bound has nothing to
   * contradict -- and note that a {@code $ref} bound never reaches here as a bound at all, since
   * {@code OperatorMaps.between} routes a referencing bound into its own join constraint and leaves
   * that end of the range empty.
   *
   * @param rules the compiled rules
   * @return one warning per impossible range, in rule and declaration order
   */
  private static List<Diagnostic> impossibleRanges(final List<CompiledRule> rules) {
    final List<Diagnostic> warnings = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      for (final PatternDefinition pattern : rule.source().when()) {
        /*
         * Grouped by field rather than checked one constraint at a time, because the same mistake
         * has two spellings and only one of them is a single constraint. §6.2.1 documents both
         * `{ between: { from: 500, to: 100 } }` and `{ gt: 500, lt: 100 }`, and the second compiles
         * into two one-sided ranges that are individually satisfiable and jointly impossible.
         * Constraints in a pattern are AND-ed, so any lower bound above any upper bound on the same
         * field settles it.
         */
        final Map<String, List<RangeConstraint>> byField = new LinkedHashMap<>();
        for (final Constraint constraint : pattern.constraints()) {
          if (constraint instanceof RangeConstraint range) {
            byField.computeIfAbsent(range.field(), ignored -> new ArrayList<>()).add(range);
          }
        }
        byField.forEach((field, ranges) -> impossibleAcross(ranges).ifPresent(why ->
            warnings.add(new Diagnostic(rule.id(), CompilerReport.IMPOSSIBLE_RANGE,
                "these bounds on " + pattern.alias() + "." + field
                    + " can never all hold: " + why,
                Optional.of(field)))));
      }
    }
    return warnings;
  }

  /**
   * Why the conjunction of one field's ranges excludes everything, if it does.
   *
   * @param ranges every range constraint on one field of one pattern
   * @return the explanation of the first contradiction found, or empty when they can all hold
   */
  private static Optional<String> impossibleAcross(final List<RangeConstraint> ranges) {
    for (final RangeConstraint lower : ranges) {
      if (lower.lower().isEmpty()) {
        continue;
      }
      for (final RangeConstraint upper : ranges) {
        if (upper.upper().isEmpty()) {
          continue;
        }
        final Optional<String> why = contradicts(
            lower.lower().get(), lower.lowerInclusive(),
            upper.upper().get(), upper.upperInclusive());
        if (why.isPresent()) {
          return why;
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Whether one lower bound and one upper bound leave no value between them.
   *
   * <p>Only comparable bounds are judged. §2.6.1 orders numbers and strings and nothing else, so
   * {@link Canonical#compare} returning empty means "these two cannot be ordered", which is a
   * different problem and not this one's to report. A {@code $ref} bound never reaches here at all:
   * {@code OperatorMaps.between} routes a referencing bound into its own join constraint and leaves
   * that end of the range empty.
   *
   * @param lower the lower bound
   * @param lowerInclusive whether the lower bound itself matches
   * @param upper the upper bound
   * @param upperInclusive whether the upper bound itself matches
   * @return the explanation, or empty when some value satisfies both
   */
  private static Optional<String> contradicts(final JsonNode lower, final boolean lowerInclusive,
      final JsonNode upper, final boolean upperInclusive) {
    final OptionalInt sign = Canonical.compare(lower, upper);
    if (sign.isEmpty()) {
      return Optional.empty();
    }
    if (sign.getAsInt() > 0) {
      return Optional.of("the lower bound " + lower + " is above the upper bound " + upper);
    }
    if (sign.getAsInt() == 0 && !(lowerInclusive && upperInclusive)) {
      return Optional.of(
          "the bounds are both " + lower + " and at least one of them is exclusive");
    }
    return Optional.empty();
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
