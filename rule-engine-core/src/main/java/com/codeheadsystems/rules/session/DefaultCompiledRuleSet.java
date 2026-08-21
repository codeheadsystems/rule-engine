package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.schema.FactSchemas;
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
  private final Network network;
  private final TestedPaths testedPaths;
  private final String version;
  private final CompilerReport report;
  private final FactSchemas factSchemas;

  /**
   * Creates a compiled rule set. Produced by the compiler; not usually constructed by hand.
   *
   * @param rules the compiled rules, in compilation order
   * @param network the compiled matching network
   * @param testedPaths which payload paths the rule set reads
   * @param version a content hash of the source rules plus the compiler version (§5.6)
   * @param report what the compiler noticed while building it (§7.4)
   * @param factSchemas the optional fact-payload schemas it was compiled against (§2.3)
   */
  public DefaultCompiledRuleSet(final List<CompiledRule> rules, final Network network,
      final TestedPaths testedPaths, final String version, final CompilerReport report,
      final FactSchemas factSchemas) {
    this.rules = List.copyOf(rules);
    this.network = Objects.requireNonNull(network, "network");
    this.testedPaths = Objects.requireNonNull(testedPaths, "testedPaths");
    this.version = Objects.requireNonNull(version, "version");
    this.report = Objects.requireNonNull(report, "report");
    this.factSchemas = Objects.requireNonNull(factSchemas, "factSchemas");
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
  public Network network() {
    return network;
  }

  @Override
  public TestedPaths testedPaths() {
    return testedPaths;
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public CompilerReport report() {
    return report;
  }

  @Override
  public FactSchemas factSchemas() {
    return factSchemas;
  }
}
