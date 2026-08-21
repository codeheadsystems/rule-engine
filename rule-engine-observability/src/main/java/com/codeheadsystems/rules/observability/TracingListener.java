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
 * <p><strong>Safe for concurrent sessions, and it has to be.</strong> §7.1 says a listener is
 * "registered per session via {@code SessionOptions}, so a listener is never shared mutable state
 * across sessions and nothing on the path synchronizes", and that premise is wrong:
 * {@code SessionOptions} is per <em>configuration</em>, and one instance is deliberately reused for
 * many sessions -- {@code RuleBatches.run(rules, inputs, batch, options)} takes exactly one and
 * builds N concurrent sessions from it. A caller wanting a trace out of a batch run has no other
 * move, so this class locks rather than assuming isolation it does not have. Found in review of
 * Phase 4; the spec sentence is the defect, and {@code docs/rule-engine-spec.md} §7.1 is annotated
 * to say so.
 *
 * <p>The lock is uncontended in the single-session case and covers a deque push, which §7.1's own
 * argument for {@code NoOpListener} already accepts is not on the hot path -- listener dispatch
 * happens once per firing, not once per candidate.
 *
 * <p><strong>Safe to share is not the same as useful to share, and this class leads with the use
 * case that suffers.</strong> One instance across concurrent sessions interleaves them into a single
 * bounded buffer, and {@link FireRecord} carries no session id -- so {@link #describe()} renders an
 * {@link com.codeheadsystems.rules.match.ActivationKey}, which is a rule id plus bound handle ids,
 * and handle ids restart at zero in every session. Forty sessions each firing {@code tag} on handle
 * 1 render as forty identical lines: indistinguishable from the runaway loop the first paragraph
 * above says this class is for. <strong>Diagnosing a loop wants one listener per session</strong>,
 * which means one {@code SessionOptions} per session. Sharing is for the case where an aggregate
 * count or a failed action is what you are after.
 */
public final class TracingListener implements RuleEngineListener {

  /** Enough to see a loop, small enough that nobody has to think about it. */
  public static final int DEFAULT_CAPACITY = 64;

  private final Deque<FireRecord> recent = new ArrayDeque<>();
  private final Object lock = new Object();
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
    synchronized (lock) {
      recent.addLast(record);
      if (recent.size() > capacity) {
        recent.removeFirst();
      }
    }
  }

  /**
   * The retained firings, oldest first.
   *
   * @return an immutable snapshot
   */
  public List<FireRecord> recent() {
    synchronized (lock) {
      // Inside the lock: List.copyOf on a deque being resized by another session's firing can read
      // a null slot and throw, which is how this defect showed up as an exception from inside
      // fireAllRules() rather than as a merely inaccurate trace.
      return List.copyOf(recent);
    }
  }

  /**
   * The most recent firing.
   *
   * @return the last record, or empty if nothing has fired
   */
  public Optional<FireRecord> last() {
    synchronized (lock) {
      return Optional.ofNullable(recent.peekLast());
    }
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
    // Over a snapshot, not the live deque. Three methods touch `recent` directly and all three hold
    // the lock -- onAfterFire, recent() and last(). Everything else, including describe(), reads a
    // snapshot instead, so a fourth reader added later needs no new locking as long as it does the
    // same. describe() did iterate the live deque before Phase 4 and was a second instance of the
    // same race inside this one class.
    FireRecord found = null;
    for (final FireRecord record : recent()) {
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
    final List<FireRecord> snapshot = recent();
    if (snapshot.isEmpty()) {
      return "(nothing fired)";
    }
    final StringBuilder text = new StringBuilder();
    for (final FireRecord record : snapshot) {
      final ActivationKey key = record.key();
      text.append(key)
          .append(record.failedAction().map(action -> "  FAILED at " + action).orElse(""))
          .append(System.lineSeparator());
    }
    return text.toString();
  }
}
