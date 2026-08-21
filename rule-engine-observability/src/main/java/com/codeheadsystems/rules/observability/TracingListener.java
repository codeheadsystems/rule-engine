package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.session.FireRecord;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Keeps the last N firings in a bounded ring buffer (spec §7.1).
 *
 * <p>Two jobs, and the second is the one people discover they needed too late.
 *
 * <p><strong>Diagnosing a runaway loop.</strong> "A runaway loop is almost always visible in the
 * last dozen firings", and the exception a cycle limit throws names only the activation that was
 * next. The dozen before it are what show the cycle.
 *
 * <p><strong>Surviving a rethrow.</strong> Under the default error policy a failing right-hand side
 * propagates the original exception, which -- unlike a limit breach -- cannot carry a partial
 * result, because §4.6 requires the caller to receive the exception the action threw. The firing
 * record is still published to listeners before the throw, so this is where it can be recovered.
 * A commit-phase failure leaves working-memory effects applied; without a listener there is no
 * record that the partial state exists.
 *
 * <p>Bounded on purpose. An unbounded trace is a memory leak with a helpful name, and §4.4 already
 * counts the session's growth surfaces carefully enough not to want another.
 *
 * <p>Per session, like every listener, so nothing here synchronises.
 */
public final class TracingListener implements RuleEngineListener {

  /** Enough to see a loop, small enough that nobody has to think about it. */
  public static final int DEFAULT_CAPACITY = 64;

  private final Deque<FireRecord> recent = new ArrayDeque<>();
  private final int capacity;

  /** Creates a listener retaining {@value #DEFAULT_CAPACITY} firings. */
  public TracingListener() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates a listener retaining a given number of firings.
   *
   * @param capacity how many of the most recent firings to keep; must be positive
   */
  public TracingListener(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive, was " + capacity);
    }
    this.capacity = capacity;
  }

  @Override
  public void onAfterFire(final FireRecord record) {
    recent.addLast(record);
    if (recent.size() > capacity) {
      recent.removeFirst();
    }
  }

  /**
   * The retained firings, oldest first.
   *
   * @return an immutable snapshot
   */
  public List<FireRecord> recent() {
    return List.copyOf(recent);
  }

  /**
   * The most recent firing.
   *
   * @return the last record, or empty if nothing has fired
   */
  public Optional<FireRecord> last() {
    return Optional.ofNullable(recent.peekLast());
  }

  /**
   * The most recent firing that failed.
   *
   * <p>The question after a rethrow is "what landed before it blew up", and this is the shortest
   * path to it.
   *
   * @return the last record carrying a failed action, if one is still retained
   */
  public Optional<FireRecord> lastFailure() {
    FireRecord found = null;
    for (final FireRecord record : recent) {
      if (record.failedAction().isPresent()) {
        found = record;
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * A human-readable rendering of the retained firings, one per line.
   *
   * <p>Written for the person staring at a cycle-limit exception, so it leads with the rule and its
   * bindings -- a repeating pair is the loop.
   *
   * @return the rendering
   */
  public String describe() {
    if (recent.isEmpty()) {
      return "(nothing fired)";
    }
    final StringBuilder text = new StringBuilder();
    for (final FireRecord record : recent) {
      final ActivationKey key = record.key();
      text.append(key)
          .append(record.failedAction().map(action -> "  FAILED at " + action).orElse(""))
          .append(System.lineSeparator());
    }
    return text.toString();
  }
}
