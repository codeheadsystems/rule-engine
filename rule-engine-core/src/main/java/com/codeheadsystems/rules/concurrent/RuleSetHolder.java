package com.codeheadsystems.rules.concurrent;

import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.util.Objects;

/**
 * Holds the rule set new sessions are created from, so it can be replaced while the engine runs
 * (spec §5.6).
 *
 * <pre>{@code
 * RuleSetHolder rules = new RuleSetHolder(RuleFiles.compile(source));
 * ...
 * rules.publish(RuleFiles.compile(newSource));   // compile first; see below
 * }</pre>
 *
 * <p><strong>The volatile field is the entire mechanism</strong>, as §5.6 says, and the reason it is
 * enough is §5.1's split rather than anything clever here. A {@code CompiledRuleSet} never mutates,
 * so there is no torn state for a reader to see; a session holds a strong reference to the rule set
 * it was created from, so it finishes against the rules it started with whatever happens here; and
 * an old rule set becomes garbage when its last session closes. No locking, no reference counting,
 * no quiescing.
 *
 * <p>Two contracts come with it, and they are the part worth stating because neither is visible in
 * the code:
 *
 * <ul>
 *   <li><strong>Compile fully before publishing.</strong> {@link #publish} takes a
 *       {@code CompiledRuleSet}, not rule text, and that signature is the point: a compilation
 *       failure must leave the previous version serving. Compiling inside this class would mean a
 *       bad rule file could take the engine out of service, which is exactly the failure a hot
 *       reload exists to avoid.
 *   <li><strong>A swap affects new sessions only.</strong> There is no safe in-place swap for a
 *       session that is already running -- §5.6 is explicit, because a live session's memories,
 *       refraction state and agenda are all shaped by the old network's node ids. For a long-lived
 *       session the answer is to drain and restart it; {@code RuleSession.exportFacts()} is the
 *       supported path.
 * </ul>
 *
 * <p>What this class deliberately does not do is decide <em>when</em> to publish. Watching a
 * directory, polling a config service, or reacting to a deployment are all somebody else's job, and
 * §0's "no mandatory build/packaging layer" applies here too.
 */
public final class RuleSetHolder {

  /**
   * The rule set new sessions come from.
   *
   * <p>Volatile and nothing more. A reader either sees the old reference or the new one, both of
   * which are fully constructed and immutable, so there is no state in which a session could be
   * created from a half-published rule set.
   */
  private volatile CompiledRuleSet current;

  /**
   * Creates a holder serving an initial rule set.
   *
   * @param initial the rule set to serve until something is published over it
   */
  public RuleSetHolder(final CompiledRuleSet initial) {
    this.current = Objects.requireNonNull(initial, "initial");
  }

  /**
   * The rule set new sessions are currently created from.
   *
   * <p>Useful for stamping {@code version()} into a log, and for the run-both-and-compare cutover
   * §5.6 describes. Note that the answer can be stale the instant it is returned; that is inherent,
   * not a defect, and it does not matter because whatever it hands back is a complete rule set.
   *
   * @return the current rule set
   */
  public CompiledRuleSet current() {
    return current;
  }

  /**
   * Opens a session on the current rule set, with default options.
   *
   * @return the session
   */
  public RuleSession newSession() {
    return current.newSession();
  }

  /**
   * Opens a session on the current rule set.
   *
   * <p>There is no need to pair this with {@link #current()} to learn which rule set a session got:
   * {@code FireResult.ruleSetVersion()} is stamped from the session's own rule set, so it cannot
   * disagree with the rules that ran however many publishes land in between.
   *
   * @param options the session configuration
   * @return the session
   */
  public RuleSession newSession(final SessionOptions options) {
    return current.newSession(Objects.requireNonNull(options, "options"));
  }

  /**
   * Replaces the rule set new sessions will be created from.
   *
   * <p>Sessions already running are unaffected and finish against their original rules.
   *
   * @param next the already-compiled replacement
   */
  public void publish(final CompiledRuleSet next) {
    this.current = Objects.requireNonNull(next, "next");
  }
}
