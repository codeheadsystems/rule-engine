package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.TestedPaths;
import java.util.List;
import java.util.Objects;

/**
 * The immutable compiled rule set the compiler produces (spec §5.1).
 *
 * <p>Every field is immutable and every collection is copied on the way in. That is spec invariant
 * 1 -- nothing here mutates after compile -- and it is what makes this object safe to share across
 * thousands of concurrent sessions with no synchronisation and no contention. A "cache" map on a
 * shared object is the classic way this rots (§10).
 */
public final class DefaultCompiledRuleSet implements CompiledRuleSet {

  private final List<CompiledRule> rules;
  private final TestedPaths testedPaths;
  private final String version;

  /**
   * Creates a compiled rule set. Produced by the compiler; not usually constructed by hand.
   *
   * @param rules the compiled rules, in compilation order
   * @param testedPaths which payload paths the rule set reads
   * @param version a content hash of the source rules plus the compiler version (§5.6)
   */
  public DefaultCompiledRuleSet(final List<CompiledRule> rules, final TestedPaths testedPaths,
      final String version) {
    this.rules = List.copyOf(rules);
    this.testedPaths = Objects.requireNonNull(testedPaths, "testedPaths");
    this.version = Objects.requireNonNull(version, "version");
  }

  @Override
  public RuleSession newSession() {
    return newSession(SessionOptions.defaults());
  }

  @Override
  public RuleSession newSession(final SessionOptions options) {
    return new DefaultRuleSession(this, Objects.requireNonNull(options, "options"));
  }

  @Override
  public List<CompiledRule> rules() {
    return rules;
  }

  @Override
  public TestedPaths testedPaths() {
    return testedPaths;
  }

  @Override
  public String version() {
    return version;
  }
}
