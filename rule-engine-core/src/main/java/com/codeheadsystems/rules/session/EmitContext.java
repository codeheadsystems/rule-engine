package com.codeheadsystems.rules.session;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything needed to correlate an emitted event back to the decision that produced it
 * (spec §4.6).
 *
 * <p>The session id and the handles together are globally unique: a handle is meaningful only
 * relative to its session, and the pair costs 16 bytes <em>per session</em> rather than per fact
 * (§2.1). The rule-set version answers "which rules produced this decision" months later, which for
 * anything audited is the question that actually gets asked.
 *
 * @param sessionId the session's UUIDv7
 * @param ruleId the rule that fired
 * @param handles the bound facts, in tuple order
 * @param ruleSetVersion the content hash of the rules that produced this (§5.6)
 */
public record EmitContext(UUID sessionId, String ruleId, long[] handles, String ruleSetVersion) {

  /**
   * Canonical constructor. Copies {@code handles} defensively.
   *
   * @param sessionId the session's id
   * @param ruleId the rule that fired
   * @param handles the bound facts
   * @param ruleSetVersion the rule-set content hash
   */
  public EmitContext {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(ruleSetVersion, "ruleSetVersion");
    handles = handles.clone();
  }

  /**
   * The bound facts.
   *
   * @return a copy, in tuple order
   */
  @Override
  public long[] handles() {
    return handles.clone();
  }

  /**
   * Value equality, comparing the handles by content rather than by identity.
   *
   * @param other the object to compare against
   * @return whether the two contexts are equal
   */
  @Override
  public boolean equals(final Object other) {
    return other instanceof EmitContext context
        && sessionId.equals(context.sessionId)
        && ruleId.equals(context.ruleId)
        && ruleSetVersion.equals(context.ruleSetVersion)
        && Arrays.equals(handles, context.handles);
  }

  /**
   * Hashes the components, consistent with {@link #equals(Object)}.
   *
   * @return the hash
   */
  @Override
  public int hashCode() {
    return Objects.hash(sessionId, ruleId, ruleSetVersion, Arrays.hashCode(handles));
  }
}
