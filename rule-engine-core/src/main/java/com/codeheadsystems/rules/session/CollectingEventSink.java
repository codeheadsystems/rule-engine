package com.codeheadsystems.rules.session;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The default sink: collects emissions so they come back as the <em>return value</em> of a fire
 * call (spec §4.6).
 *
 * <p>That default is right for the batch shape v1 targets, and it is what makes rules testable
 * without mocking anything: assert on the events a fire call returned, with no external side
 * effects to intercept.
 *
 * <p>Single-writer, like everything else on a session: not thread-safe, and it does not need to be.
 */
public final class CollectingEventSink implements EventSink {

  private final List<EmittedEvent> collected = new ArrayList<>();

  /** Creates an empty sink. */
  public CollectingEventSink() {
    // Collected list is initialised inline.
  }

  @Override
  public void emit(final String eventType, final JsonNode payload, final EmitContext context) {
    collected.add(new EmittedEvent(eventType, payload, context));
  }

  /**
   * Everything emitted so far, in firing order.
   *
   * @return an immutable snapshot
   */
  public List<EmittedEvent> collected() {
    return List.copyOf(collected);
  }

  /** Discards everything collected, so one session can report per fire call. */
  public void clear() {
    collected.clear();
  }
}
