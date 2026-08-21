package com.codeheadsystems.rules.listener;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.session.EmitContext;
import com.codeheadsystems.rules.session.FireRecord;
import java.util.Set;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * Observes what a session does (spec §7.1).
 *
 * <p>Two callback names are chosen carefully rather than for symmetry, and the reasons are worth
 * knowing before you implement them.
 *
 * <p>{@link #onUpdate}'s path set is <strong>only what the network tests</strong>, not every path
 * that changed. A listener used as a general audit log would under-report actual changes, so the
 * parameter is named for what it is.
 *
 * <p>{@link #onActivationSuppressed} takes an {@link ActivationKey} rather than an
 * {@link Activation}, because refraction may suppress a match before an activation object exists
 * at all -- under a Rete shape it is never created, and under the TREAT shape it is created during
 * recomputation and dropped at selection. A callback taking an activation would be unimplementable
 * in one shape and misleading in the other. Both shapes always have the key.
 *
 * <p><strong>If one {@code SessionOptions} is used to create sessions on more than one thread, its
 * listeners are shared and must tolerate concurrent invocation.</strong> The engine does not
 * synchronize dispatch. Note what turns the obligation on: it is the caller's own decision to reuse
 * an options object across threads, not anything the engine does -- a listener registered on options
 * used for a single session, or for one session at a time, needs nothing.
 *
 * <p>§7.1 states the opposite as a design property -- "registered per session via
 * {@code SessionOptions}, so a listener is never shared mutable state across sessions and nothing on
 * the path synchronizes" -- and its premise does not hold: {@code SessionOptions} is per
 * <em>configuration</em>, and {@code RuleBatches.run(rules, inputs, batch, options)} builds N
 * concurrent sessions from one options object, which is the natural way to collect a trace across a
 * batch run. The spec sentence is annotated as a defect rather than worked around silently; see the
 * amendment at §7.1.
 *
 * <p>The obligation is cheap to meet, which is why the answer is a contract rather than a reshaped
 * API: dispatch happens once per <em>firing</em>, not once per candidate, so a lock around whatever
 * the listener collects is not on a hot path. {@code TracingListener} takes one unconditionally,
 * because a library class cannot know how it will be used.
 *
 * <p>Every method defaults to doing nothing. Dispatch must cost nothing when no listener is
 * registered: the session checks for an empty listener list at the call site rather than iterating
 * one no-op per event on the hot path.
 *
 * <p><strong>A listener must not throw, and must not call back into the session.</strong> Neither
 * is enforced, and the engine deliberately does not contain a listener failure the way it contains
 * an {@link com.codeheadsystems.rules.session.EventSink} failure. The two look similar -- both are
 * host code registered through session options -- but they are not the same kind of thing. An
 * {@code emit} is one of the five actions: a delivery failure is a <em>rule action</em> failing, so
 * it belongs to the error policy and the firing record. A listener is instrumentation observing a
 * decision it plays no part in; swallowing its exceptions would hide a defect in the caller's own
 * diagnostic code, and routing them through the error policy would let instrumentation change which
 * rules fire. So a throwing listener propagates to the caller of the fire call, uncontained.
 */
public interface RuleEngineListener {

  /**
   * A fact was inserted.
   *
   * @param fact the new fact
   */
  default void onInsert(Fact fact) {
    // no-op by default
  }

  /**
   * A fact was effectively updated -- at least one tested path changed.
   *
   * @param before the fact as it was
   * @param after the fact as it now is, with the same handle
   * @param changedTestedPaths only the paths the network tests; see the class documentation
   */
  default void onUpdate(Fact before, Fact after, Set<JsonPointer> changedTestedPaths) {
    // no-op by default
  }

  /**
   * A fact was retracted.
   *
   * @param fact the fact as it was when removed
   */
  default void onRetract(Fact fact) {
    // no-op by default
  }

  /**
   * A complete match was found and became eligible.
   *
   * @param activation the match
   */
  default void onActivationCreated(Activation activation) {
    // no-op by default
  }

  /**
   * A pending activation will not fire because its facts changed under it.
   *
   * <p><strong>Never dispatched by the v1 engine, and it cannot be.</strong> Cancellation is a
   * Rete-shaped event: it presupposes an activation that was materialised when its facts arrived
   * and is later withdrawn. The TREAT shape (§4.1) materialises nothing between fire cycles -- a
   * retracted fact's matches disappear because the next recomputation simply does not produce them
   * -- so there is no moment at which a pending activation is cancelled. The callback is declared
   * because §7.1 specifies it and the Phase 3 agenda will dispatch it; until then, use
   * {@link #onRetract} and {@link #onUpdate} to observe the same underlying events.
   *
   * @param activation the match
   * @param why what changed
   */
  default void onActivationCancelled(Activation activation, CancelReason why) {
    // no-op by default
  }

  /**
   * A match will not fire although its facts still satisfy the rule.
   *
   * @param key the match's identity
   * @param why refraction or {@code noLoop}
   */
  default void onActivationSuppressed(ActivationKey key, SuppressReason why) {
    // no-op by default
  }

  /**
   * An activation is about to fire.
   *
   * @param activation the match
   */
  default void onBeforeFire(Activation activation) {
    // no-op by default
  }

  /**
   * An activation finished firing.
   *
   * @param record what it did
   */
  default void onAfterFire(FireRecord record) {
    // no-op by default
  }

  /**
   * A right-hand side threw.
   *
   * @param activation the match that was firing
   * @param failed the action that threw
   * @param cause the exception
   */
  default void onRhsError(Activation activation, ActionDefinition failed, Throwable cause) {
    // no-op by default
  }

  /**
   * An event was delivered to the sink.
   *
   * @param eventType the event name
   * @param payload the payload
   * @param context the correlation context
   */
  default void onEmit(String eventType, JsonNode payload, EmitContext context) {
    // no-op by default
  }
}
