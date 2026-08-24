package com.codeheadsystems.rules.runtime;

import com.codeheadsystems.rules.network.Network;
import com.codeheadsystems.rules.report.CompilerReport;
import com.codeheadsystems.rules.rule.CompiledRule;
import com.codeheadsystems.rules.rule.TestedPaths;
import com.codeheadsystems.rules.schema.FactSchemas;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
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
  private final long literalFingerprint;

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
    // Taken here, which is compile time: the compiler is what constructs this.
    this.literalFingerprint = RuleSetFingerprint.of(this.rules);
  }

  @Override
  public RuleSession newSession() {
    return newSession(SessionOptions.defaults());
  }

  @Override
  public RuleSession newSession(final SessionOptions options) {
    Objects.requireNonNull(options, "options");
    if (options.strict()) {
      verifyUnmutated();
    }
    return new DefaultRuleSession(this, options);
  }

  /**
   * Strict-mode check that nobody has mutated a literal since compile (invariant 1, §5.5, §7.5).
   *
   * <p>Checked when a session is created rather than on every read. §7.5's table is checks "too
   * expensive for production but that catch a contract violation deterministically in test", and
   * the read path here is the matching hot path -- a defensive copy or a re-hash per fact per test
   * would cost far more than the thing it guards against. Session creation is the right granularity
   * instead: cheap, once per session, and in the batch model of §5.2 sessions are created
   * constantly, so a mutation is caught by the next one to start.
   *
   * <p>What makes this worth detecting at all rather than documenting: a mutated literal changes
   * which facts match, and {@link #version()} does not move -- so the rule set reports the same
   * identity while behaving differently, and §5.6's "which rules produced this decision" answers
   * wrongly for every decision after the mutation.
   *
   * @throws IllegalStateException if a literal has changed since this rule set was compiled
   */
  private void verifyUnmutated() {
    final long now = RuleSetFingerprint.of(rules);
    if (now != literalFingerprint) {
      throw new IllegalStateException(
          "a literal in rule set " + version + " has been mutated since it was compiled."
              + " Nothing in a CompiledRuleSet may change after compile (spec invariant 1): it is"
              + " read by every session with no synchronisation, and a changed literal changes"
              + " which facts match while version() still reports the original rule set."
              + " A constraint's literal() hands back the live node for speed; treat it as"
              + " read-only, and build a new rule set to change a rule");
    }
  }

  @Override
  public List<CompiledRule> rules() {
    return rules;
  }

  /**
   * The compiled matching network.
   *
   * <p>Shared and immutable, like everything else here. A session allocates its own memories for it
   * (spec §3.2.3); the graph itself holds structure and plans, never data.
   *
   * <p><strong>Not on {@link com.codeheadsystems.rules.session.CompiledRuleSet}, and that is the
   * point of this package.</strong> It used to be, which put the whole node graph on a contract
   * interface a consumer reads -- §8.1 recorded it as the one over-exposure in {@code -core} and as
   * work to do before a first publish, because after one it is a compatibility surface for the life
   * of the artifact. §7.4's {@code CompilerReport} is the supported introspection. This stays public
   * rather than package-private only so the testkit's white-box structural tests can reach it; it is
   * public in an <em>internal</em> package, which is exactly the distinction Java cannot express and
   * {@code ApiSurfaceTest} exists to keep anyway.
   *
   * @return the network
   */
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
