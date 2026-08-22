package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.fact.ExportedFact;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.match.ActivationKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A single-writer evaluation session (spec §5.1).
 *
 * <p>A session holds <em>only</em> mutable state: working memory, the agenda, refraction memory and
 * counters. <strong>It is never shared across threads.</strong> That rule is what lets the hot path
 * skip locks entirely, and it makes sessions cheap enough to create and discard per request or per
 * batch item -- one virtual thread per session, which is a very different cost model from pooling a
 * small number of expensive stateful engine instances.
 *
 * <p>{@link #halt()} is the one exception, and the only method callable from another thread.
 *
 * <p><strong>There is deliberately no way to reach the agenda from here.</strong> Publishing it
 * would put a consuming {@code nextToFire()} in reach of application code, silently deleting a
 * firing the running loop would otherwise have performed, with no error anywhere. What callers
 * actually want from it is already available and non-consuming: {@link FireResult#residualAgendaSize()}
 * for "what is left", and a dry run for "what would fire, in what order".
 */
public interface RuleSession extends AutoCloseable {

  /**
   * This session's globally-unique id.
   *
   * <p>One UUIDv7 per <em>session</em>, not one per fact. Anything leaving the engine is stamped
   * with the pair {@code (sessionId, handle.id())}, which is globally unique, sorts by session
   * creation time, and costs 16 bytes per session rather than per fact.
   *
   * @return the session id
   */
  UUID sessionId();

  /**
   * Inserts a fact, deep-copying the payload.
   *
   * @param type the fact type
   * @param payload the payload; copied
   * @return the new fact's handle
   */
  FactHandle insert(String type, JsonNode payload);

  /**
   * Inserts a fact without copying. Ownership transfers; the caller must never touch the payload
   * again.
   *
   * @param type the fact type
   * @param payload the payload; retained
   * @return the new fact's handle
   */
  FactHandle insertOwned(String type, JsonNode payload);

  /**
   * Replaces a fact's payload, deep-copying it. The handle stays valid and identical.
   *
   * @param handle the fact to update
   * @param newPayload the replacement; copied
   */
  void update(FactHandle handle, JsonNode newPayload);

  /**
   * Replaces a fact's payload without copying. Ownership transfers.
   *
   * @param handle the fact to update
   * @param newPayload the replacement; retained
   */
  void updateOwned(FactHandle handle, JsonNode newPayload);

  /**
   * Removes a fact.
   *
   * @param handle the fact to remove
   */
  void retract(FactHandle handle);

  /**
   * Looks up a fact.
   *
   * @param handle the fact to read
   * @return the fact, or empty if it is not asserted
   */
  Optional<Fact> get(FactHandle handle);

  /**
   * Fires until the agenda drains, using the limits from this session's options.
   *
   * @return what happened
   * @throws RuleEngineLimitExceeded if a work limit is breached; the exception carries the partial
   *     result
   */
  FireResult fireAllRules();

  /**
   * Fires until the agenda drains, overriding the limits for this call.
   *
   * @param options the limits to use
   * @return what happened
   * @throws RuleEngineLimitExceeded if a work limit is breached; the exception carries the partial
   *     result
   */
  FireResult fireAllRules(FireOptions options);

  /**
   * Whether a match has already fired, and at what recency.
   *
   * <p>Exists for §7.2's third verdict, which is the one nobody guesses: a rule that "stopped
   * working" has usually already fired on those exact facts. Saying so explicitly, with the recency
   * it fired at, is the difference between a useful explanation and one that lists constraints the
   * author can already see are satisfied.
   *
   * <p>This is deliberately the <em>only</em> window onto refraction state. §5.1 refuses to publish
   * the agenda because {@code nextToFire()} consumes; this reads and consumes nothing.
   *
   * @param key the match's identity
   * @return the recency it fired at, or empty if it has not fired
   */
  Optional<Long> firedAt(ActivationKey key);

  /**
   * Read access to working memory, and how callers reach {@link WorkingMemory#factsOfType}.
   *
   * <p>Content changes only through the insert, update and retract methods on this session.
   *
   * @return the working memory
   */
  WorkingMemory workingMemory();

  /**
   * This session's externally-inserted facts, ready to replay into another session (§5.6).
   *
   * <p>The drain half of drain-and-restart, which is what a long-lived session does when the rule
   * set changes under it: there is no safe in-place swap for a running session, so the answer is to
   * export, close, and replay into a session on the new rules. See
   * {@link com.codeheadsystems.rules.fact.WorkingMemory#exportFacts()} for what is and is not
   * included, and {@code com.codeheadsystems.rules.concurrent.SessionDrain} for the whole move.
   *
   * @return the facts, ascending by handle id, which is insertion order
   */
  default List<ExportedFact> exportFacts() {
    return workingMemory().exportFacts();
  }

  /**
   * Whether {@link #halt()} has been called.
   *
   * @return the halt flag
   */
  boolean halted();

  /**
   * Whether a rule action has failed this session past the point of use.
   *
   * <p>Distinct from {@link #halted()}, and the difference is whether anything can be done about
   * it. A halted session was stopped deliberately and still holds valid state; a failed one threw
   * out of a right-hand side under the {@code RETHROW} policy (§4.6) and rejects every subsequent
   * operation, permanently. A limit breach is <em>not</em> a failure -- §4.7 keeps those separate
   * on purpose, because the work already done is still good.
   *
   * <p>Exists because a long-lived session can outlive the caller that started it.
   * {@code SessionActor} holds one for as long as the process wants it and has no other way to tell
   * "this batch failed and the next may not" from "this session will never work again" -- and
   * without the distinction a health check reports a running worker on a session that can never
   * fire.
   *
   * <p><strong>Not a cross-thread call.</strong> {@link #halt()} is the only method §5.1 permits
   * from another thread; this one reads ordinary session state and is for whoever owns the session
   * -- which, under {@code SessionActor}, is its worker.
   *
   * <p>Abstract rather than {@code default}, unlike {@link #exportFacts()} above it, and
   * deliberately: a decorator that inherited "not failed" would report a healthy session it knows
   * nothing about, and this is exactly the question whose wrong answer keeps a dead session in
   * service.
   *
   * @return whether a rule action has failed this session; false for a session that is merely
   *     closed, which is unusable for a different reason
   */
  boolean failed();

  /**
   * Stops a running fire loop.
   *
   * <p><strong>This is the only method on a session that may be called from another thread.</strong>
   * It is backed by a {@code volatile} flag, and it exists because a watchdog must be able to stop
   * a running fire loop -- it is the only enforcement available for a latency budget, since
   * {@code maxCycles} and {@code maxFacts} bound work rather than time. No other method may be
   * called from another thread.
   */
  void halt();

  /**
   * Releases the session's memories.
   *
   * <p>Does <strong>not</strong> fire pending activations. A non-empty agenda at close was already
   * reported in the last fire result's residual count rather than being silently drained here.
   */
  @Override
  void close();
}
