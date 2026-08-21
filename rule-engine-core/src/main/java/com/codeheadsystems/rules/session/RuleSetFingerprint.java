package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.Constraint;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.ExpressionConstraint;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.FieldConstraint;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.JoinConstraint;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.PatternDefinition;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RangeConstraint;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import tools.jackson.databind.JsonNode;
import java.util.List;

/**
 * A structural hash of every mutable value a compiled rule set holds (spec invariant 1, §5.5).
 *
 * <p>Invariant 1 is that nothing in a {@code CompiledRuleSet} mutates after compile, and §5.5 rests
 * the engine's whole scaling story on it: "thousands of concurrent virtual threads reference the
 * same {@code CompiledRuleSet} with zero contention, because nothing about it mutates after
 * compile." Almost everything reachable is a record over strings, enums and primitives, and is
 * immutable by construction.
 *
 * <p><strong>The literals are not.</strong> {@code FieldConstraint}, {@code RangeConstraint} and
 * {@code Literal} hold a {@link JsonNode}, which is mutable, and although each deep-copies on the
 * way in, a record accessor hands back the live node. So a caller who reaches a literal through
 * {@code CompiledRule.source()} can extend an {@code IN} array or edit a bound -- an unsynchronised
 * write racing every session that reads it, and one that genuinely changes which facts match.
 *
 * <p>Copying on the way out is not available: the matching path reads {@code literal()} once per
 * fact per test, and a defensive copy there would be paid on the hottest path in the engine to
 * defend against a caller doing something the contract already forbids.
 *
 * <p>So this is a detector rather than a defence, and it follows the precedent §7.5 sets for exactly
 * this kind of unenforceable contract -- "{@code insertOwned}/{@code updateOwned} payloads hashed on
 * entry, re-checked on read". Hashed at compile, re-checked when a session is created, under strict
 * mode only. Session creation is the right granularity: cheap, once per session, and in the batch
 * model sessions are created constantly. Doing it per read would put it on the hot path, which is
 * the same reason a defensive copy is not available there.
 *
 * <h2>The hash walks the source, the matcher reads the compiled patterns</h2>
 *
 * <p>{@link #of} walks {@code rule.source().when()}; the matcher tests against whatever
 * {@code CompiledPattern} holds. The detector is worth nothing unless those are the same objects,
 * and it is not obvious that they are: {@code RuleCompiler} rewrites an ordered
 * {@code FieldConstraint} into a {@code RangeConstraint} (§6.2.1), and that record's compact
 * constructor deep-copies its bounds.
 *
 * <p>They are the same, for two reasons that both live in other people's code. Jackson's scalar
 * nodes are immutable and return {@code this} from {@code deepCopy()}, so the copy is a no-op; and
 * the compiler rejects a non-scalar bound for an ordered operator before the rewrite happens, so a
 * bound that <em>would</em> really be copied never reaches it. Change either and the network gets a
 * node this hash cannot see, which is a silent hole rather than a failure --
 * {@code ImmutabilityTest.networkSharesTheHashedNode} and
 * {@code orderedOperatorsRejectNonScalarBounds} pin both halves so it fails loudly instead.
 *
 * <h2>The rest of the audit</h2>
 *
 * <p>Invariant 1 covers everything reachable from a {@code CompiledRuleSet}, not only the literals.
 * The rest is enumerated here <strong>under the argument each item actually relies on</strong>,
 * because the two arguments fail differently: an immutable-by-construction item stays safe whatever
 * anyone does to the API around it, while a safe-by-unreachability item stops being safe the moment
 * someone widens a visibility modifier or adds an accessor. Anything added to
 * {@code DefaultCompiledRuleSet} belongs on one of these lists, and knowing which one is the point.
 *
 * <h3>Immutable by construction</h3>
 *
 * <ul>
 *   <li><strong>The rules, network, tested paths, version and report.</strong> Records and lists
 *       over strings, enums, primitives and {@code JsonPointer}s. Every nested container is copied
 *       at every level, which is not automatic: {@code Map.copyOf} and {@code List.copyOf} are
 *       shallow, so an outer copy over live inner collections leaves mutable state inside the shared
 *       rule set. That defect has been found twice here -- {@code CompiledRule}'s compact
 *       constructor carries a comment about it, and {@code DefaultTestedPaths.deepCopyInverse} was
 *       fixed in Phase 4 after review found it handing a live {@code LinkedHashSet} out of
 *       {@code rulesTesting}. A sweep at that point found no third instance.
 *   <li><strong>Compiled regexes</strong> ({@code RegexTest}, re2j {@code Pattern}). Immutable and
 *       thread-safe by design -- re2j puts all mutable matching state in {@code Matcher}, which is
 *       created per evaluation. One of the reasons §6.2's {@code matches} is re2j rather than
 *       {@code java.util.regex}, the other being the catastrophic-backtracking guarantee.
 *   <li><strong>CEL programs</strong> ({@code -cel}). {@code CelRuntime.Program} is documented
 *       thread-safe and holds no per-evaluation state; the evaluation context is built per call and
 *       passed in as a resolver.
 * </ul>
 *
 * <h3>Safe because nothing can reach it</h3>
 *
 * <ul>
 *   <li><strong>{@code PathTrie} and its {@code Node}s</strong> ({@code -compiler}) hold live
 *       {@code LinkedHashMap}s and {@code LinkedHashSet}s. Both types are package-private and
 *       nothing outside the package holds a reference, so there is no caller to defend against --
 *       but that is a statement about visibility, not about the objects.
 *   <li><strong>The registered schema documents</strong> ({@code -schema},
 *       {@code JsonSchemaFactSchemas}). A {@code Map<String, JsonNode>} under a shallow
 *       {@code Map.copyOf}: mutable {@code JsonNode}s living inside a shared
 *       {@code CompiledRuleSet}, and invisible to this hash. Safe for three separate reasons --
 *       {@code Builder.register} deep-copies on the way in so the caller keeps no handle, the field
 *       is private, and the {@code FactSchemas} SPI exposes only {@code violations}/{@code typeOf}/
 *       {@code presence}, none of which hand a document out. <strong>This is the one place a
 *       {@code JsonNode} is kept safe by reachability alone</strong>, so an accessor added there is
 *       an invariant-1 decision, not a convenience.
 * </ul>
 *
 * <h3>A real defence rather than either argument</h3>
 *
 * <ul>
 *   <li><strong>The compiled networknt schemas</strong> ({@code -schema}) are not immutable by
 *       construction: networknt lazily builds and caches its validator tree on first use, which is
 *       an unsynchronised write under concurrent first validation.
 *       {@code JsonSchemaFactSchemas.Builder.register} calls {@code initializeValidators()} eagerly,
 *       before the registry is published, so every session sees a fully-built schema. The reasoning
 *       lives in the comment there, which is where it belongs.
 * </ul>
 */
final class RuleSetFingerprint {

  private RuleSetFingerprint() {
    throw new UnsupportedOperationException("no instances");
  }

  /**
   * Hashes every mutable value the rule set holds.
   *
   * <p>Order matters and is included: a hash that ignored position could not tell two literals
   * apart from the same two literals swapped, and constraint order is observable (§7.3).
   *
   * @param rules the compiled rules, in compilation order
   * @return a hash that changes if any literal is mutated
   */
  static long of(final List<CompiledRule> rules) {
    long hash = 17L;
    for (final CompiledRule rule : rules) {
      hash = mix(hash, rule.id().hashCode());
      for (final PatternDefinition pattern : rule.source().when()) {
        for (final Constraint constraint : pattern.constraints()) {
          hash = mix(hash, constraintHash(constraint));
        }
      }
      for (final ActionDefinition action : rule.source().then()) {
        for (final ValueExpr value : valuesOf(action)) {
          hash = mix(hash, valueHash(value));
        }
      }
    }
    return hash;
  }

  /**
   * Hashes one constraint's mutable content.
   *
   * @param constraint the constraint
   * @return its hash
   */
  private static int constraintHash(final Constraint constraint) {
    return switch (constraint) {
      // JsonNode.hashCode is structural, so a mutated array or object hashes differently.
      case FieldConstraint field -> nodeHash(field.literal());
      case RangeConstraint range -> 31 * range.lower().map(RuleSetFingerprint::nodeHash).orElse(0)
          + range.upper().map(RuleSetFingerprint::nodeHash).orElse(0);
      // Both hold only strings and enums, which cannot be mutated in place.
      case JoinConstraint join -> join.hashCode();
      case ExpressionConstraint expression -> expression.hashCode();
    };
  }

  /**
   * Hashes one right-hand-side value's mutable content.
   *
   * @param value the value expression
   * @return its hash
   */
  private static int valueHash(final ValueExpr value) {
    return switch (value) {
      case Literal literal -> nodeHash(literal.value());
      // A path and an alias; a compiled JsonPointer is immutable.
      case FieldRef ref -> ref.hashCode();
      case ExpressionValue expression -> expression.hashCode();
    };
  }

  /**
   * Every value expression one action carries.
   *
   * @param action the action
   * @return its values, in declaration order
   */
  private static List<ValueExpr> valuesOf(final ActionDefinition action) {
    return switch (action) {
      case SetField set -> List.of(set.value());
      case InsertFact insert -> insert.payload().stream().map(PayloadField::value).toList();
      case Emit emit -> emit.payload().stream().map(PayloadField::value).toList();
      case CallFunction call -> call.args().stream().map(PayloadField::value).toList();
      case RetractFact ignored -> List.of();
    };
  }

  /**
   * Hashes a node, tolerating null.
   *
   * @param node the node
   * @return its structural hash
   */
  private static int nodeHash(final JsonNode node) {
    return node == null ? 0 : node.hashCode();
  }

  /**
   * Folds one component into the running hash.
   *
   * @param hash the running hash
   * @param component the component
   * @return the combined hash
   */
  private static long mix(final long hash, final int component) {
    return hash * 31L + component;
  }
}
