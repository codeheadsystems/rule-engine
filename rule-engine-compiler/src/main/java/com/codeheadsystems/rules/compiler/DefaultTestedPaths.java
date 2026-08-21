package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.rule.TestedPaths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * The compiled tested-path artifact (spec §2.4).
 *
 * <p>Built once, frozen, shared. §6.5 is specific about how: it is read straight off each rule's
 * own constraint fields, with no node-level extraction pass -- which is what makes it available in
 * Phase 0, before there is a network to extract anything from.
 *
 * <p>Note that a join constraint contributes <strong>two</strong> paths on two different types: the
 * field on the pattern's own fact, and the field on the referenced alias's fact. Recording only the
 * first would leave an update to the other side of a join looking like a no-op, which is the
 * quietest possible way to lose a firing.
 */
public final class DefaultTestedPaths implements TestedPaths {

  private final Map<String, Set<JsonPointer>> byType;
  private final Map<String, Map<String, Set<JsonPointer>>> byRuleAndType;
  private final Map<String, Map<JsonPointer, Set<String>>> inverse;
  private final Map<String, PathTrie> triesByType;

  /**
   * Creates the artifact from already-collected data. Built by {@link RuleCompiler}.
   *
   * @param byType per fact type, the union of paths every rule reads
   * @param byRuleAndType per rule and fact type, that rule's paths
   * @param inverse per fact type and path, the rules that read it
   */
  DefaultTestedPaths(final Map<String, Set<JsonPointer>> byType,
      final Map<String, Map<String, Set<JsonPointer>>> byRuleAndType,
      final Map<String, Map<JsonPointer, Set<String>>> inverse) {
    this.byType = deepCopyTypes(byType);
    this.byRuleAndType = deepCopyRules(byRuleAndType);
    this.inverse = deepCopyInverse(inverse);
    // Built once, here, and never per update -- §10's "TestedPaths, the type-to-rules index, and
    // the prefix trie computed once at compile time".
    final Map<String, PathTrie> tries = new LinkedHashMap<>();
    this.byType.forEach((factType, paths) -> tries.put(factType, PathTrie.of(paths)));
    this.triesByType = Map.copyOf(tries);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Answered by §3.4.2's prefix trie: both payloads are walked together once, descending only
   * into subtrees the rule set actually reads and stopping wherever two nodes are equal. The
   * interface's default -- comparing every tested path individually -- remains the definition of
   * the answer and the oracle this is tested against.
   */
  @Override
  public Set<JsonPointer> changedPaths(final String factType, final JsonNode oldPayload,
      final JsonNode newPayload) {
    final PathTrie trie = triesByType.get(factType);
    return trie == null ? Set.of() : trie.changed(oldPayload, newPayload);
  }

  /**
   * An artifact that reports nothing as tested.
   *
   * <p>Useful for tests of components that need a working memory but no rules. Note what it means
   * operationally: with no tested paths, every update changes nothing tested, so every update is a
   * no-op that replaces the payload and propagates nothing. That is correct, and it is exactly what
   * a rule set that reads no fields should do.
   *
   * @return an empty artifact
   */
  public static DefaultTestedPaths empty() {
    return new DefaultTestedPaths(Map.of(), Map.of(), Map.of());
  }

  @Override
  public Set<JsonPointer> forType(final String factType) {
    return byType.getOrDefault(factType, Set.of());
  }

  @Override
  public Set<JsonPointer> forRule(final String ruleId, final String factType) {
    return byRuleAndType.getOrDefault(ruleId, Map.of()).getOrDefault(factType, Set.of());
  }

  @Override
  public Set<String> rulesTesting(final String factType, final JsonPointer changed) {
    return inverse.getOrDefault(factType, Map.of()).getOrDefault(changed, Set.of());
  }

  /**
   * Copies a type-keyed map, preserving iteration order.
   *
   * @param source the map to copy
   * @return an unmodifiable copy
   */
  private static Map<String, Set<JsonPointer>> deepCopyTypes(
      final Map<String, Set<JsonPointer>> source) {
    final Map<String, Set<JsonPointer>> copy = new LinkedHashMap<>();
    source.forEach((type, paths) -> copy.put(type, Set.copyOf(paths)));
    return Map.copyOf(copy);
  }

  /**
   * Copies a rule-keyed map of type-keyed maps, preserving iteration order.
   *
   * @param source the map to copy
   * @return an unmodifiable copy
   */
  private static Map<String, Map<String, Set<JsonPointer>>> deepCopyRules(
      final Map<String, Map<String, Set<JsonPointer>>> source) {
    final Map<String, Map<String, Set<JsonPointer>>> copy = new LinkedHashMap<>();
    source.forEach((rule, byFactType) -> copy.put(rule, deepCopyTypes(byFactType)));
    return Map.copyOf(copy);
  }

  /**
   * Copies the inverse index, preserving iteration order.
   *
   * @param source the map to copy
   * @return an unmodifiable copy
   */
  private static Map<String, Map<JsonPointer, Set<String>>> deepCopyInverse(
      final Map<String, Map<JsonPointer, Set<String>>> source) {
    final Map<String, Map<JsonPointer, Set<String>>> copy = new LinkedHashMap<>();
    source.forEach((type, byPath) -> {
      final Map<JsonPointer, Set<String>> paths = new LinkedHashMap<>();
      /*
       * unmodifiableSet over a copy, not the copy itself. Map.copyOf is shallow, so without this
       * the values stay the compiler's live mutable sets and rulesTesting() hands one straight out
       * through the public TestedPaths on a shared CompiledRuleSet. CompiledRule's compact
       * constructor carries a comment about the identical trap; this copier was the one of three
       * here that missed it, while deepCopyTypes and deepCopyRules both used Set.copyOf.
       *
       * The damage is worse than a mutated literal and invisible to RuleSetFingerprint, which walks
       * constraints and action values rather than this index. §3.4.1 step 5 reads this set to decide
       * which rules to un-refract after an update, so clearing it does not change what matches -- it
       * stops rules being un-refracted, and a rule that should re-fire after an update never fires
       * again. No exception, and version() does not move.
       *
       * Not Set.copyOf: the result is addAll'd into a LinkedHashSet that reaches
       * refractionInvalidated and therefore a listener, and Set.copyOf carries a per-JVM iteration
       * salt. §7.3's determinism contract makes ordering on any path to the agenda load-bearing.
       */
      byPath.forEach((path, rules) ->
          paths.put(path, Collections.unmodifiableSet(new LinkedHashSet<>(rules))));
      copy.put(type, Map.copyOf(paths));
    });
    return Map.copyOf(copy);
  }
}
