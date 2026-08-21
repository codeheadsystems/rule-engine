package com.codeheadsystems.rules.session;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where {@code emit} actions go (spec §4.6).
 *
 * <p>Supplied per session, and defaulting to {@link #discarding()}. Use
 * {@link CollectingEventSink} when you want the events intercepted rather than read off
 * {@link FireResult#emitted()}.
 *
 * <p><strong>A sink performing I/O turns firing into a distributed transaction.</strong> It also
 * reintroduces the blocking problem host functions have: the fire loop waits on it, and there is
 * no timeout to rescue it. The recommended pattern is to collect during firing and publish after
 * the fire call returns. Publishing inline is a deliberate at-least-once choice and needs
 * documenting as such wherever you do it.
 */
@FunctionalInterface
public interface EventSink {

  /**
   * A stateless sink that discards everything. The default.
   *
   * <p>§4.6 describes the default as a <em>collecting</em> sink "whose contents are returned in
   * FireResult". This engine returns {@link FireResult#emitted()} from the firing records instead,
   * so that a caller who installs their own sink still gets an accurate list and a dry run can
   * report what it <em>would</em> have emitted. Given that, a collecting default would collect into
   * a void: nothing in the engine ever reads it, and it would retain every event a session ever
   * emitted for the life of that session -- an unbounded growth surface in the long-lived streaming
   * session Phase 3 targets, and one §4.4's table does not list.
   *
   * <p>Being stateless, it is also safe to share across every session, which removes a whole
   * category of mistake rather than documenting it.
   *
   * @return a sink that does nothing
   */
  static EventSink discarding() {
    return Discarding.INSTANCE;
  }

  /**
   * Delivers one event.
   *
   * @param eventType the event name
   * @param payload the event payload, owned by the sink from this point
   * @param context the correlation context
   */
  void emit(String eventType, JsonNode payload, EmitContext context);

  /** Holder for the stateless discarding sink. */
  final class Discarding implements EventSink {

    private static final EventSink INSTANCE = new Discarding();

    private Discarding() {
      // Singleton; it has no state to initialise.
    }

    @Override
    public void emit(final String eventType, final JsonNode payload, final EmitContext context) {
      // Deliberately nothing. FireResult.emitted() is sourced from the firing records.
    }
  }
}
