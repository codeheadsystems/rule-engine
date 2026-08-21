package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.match.Activation;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/**
 * A work limit was breached (spec §4.7).
 *
 * <p>Two properties of this exception are load-bearing, and both exist because a limit alone tells
 * you nothing actionable.
 *
 * <p><strong>It names the culprit.</strong> A runaway loop is almost always one or two rules, and
 * the activation that was next in line identifies them immediately. Note the activation is
 * <em>peeked</em>, never selected: selecting it would record it as refracted and remove it from the
 * conflict set, destroying a firing that the session can never recover.
 *
 * <p><strong>It carries the work.</strong> A batch that fired 9,999 rules and emitted 9,999 events
 * must not lose all of it on the 10,000th.
 */
public abstract sealed class RuleEngineLimitExceeded extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient FireResult partialResult;

  /**
   * Creates the exception.
   *
   * @param message what was breached
   * @param partialResult everything completed before the breach
   */
  RuleEngineLimitExceeded(final String message, final FireResult partialResult) {
    super(message);
    this.partialResult = Objects.requireNonNull(partialResult, "partialResult");
  }

  /**
   * Everything the fire call completed before the limit was breached.
   *
   * @return the partial result, whose termination reason is
   *     {@link TerminationReason#LIMIT_EXCEEDED}
   */
  public FireResult partialResult() {
    return partialResult;
  }

  /** Too many firings in one fire call. */
  public static final class CycleLimit extends RuleEngineLimitExceeded {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The configured maximum number of firings. */
    private final int limit;

    private final transient Activation next;

    /**
     * Creates the exception.
     *
     * @param limit the cycle limit that was reached
     * @param next the activation that would have fired next, peeked and not consumed
     * @param partialResult everything completed before the breach
     */
    public CycleLimit(final int limit, final Optional<Activation> next,
        final FireResult partialResult) {
      super("cycle limit of " + limit + " reached; next would have been "
          + next.map(Object::toString).orElse("(nothing)"), partialResult);
      this.limit = limit;
      this.next = next.orElse(null);
    }

    /**
     * The limit that was reached.
     *
     * @return the configured maximum number of firings
     */
    public int limit() {
      return limit;
    }

    /**
     * The activation that was next in line.
     *
     * @return the offending activation, if the agenda still had one
     */
    public Optional<Activation> next() {
      return Optional.ofNullable(next);
    }
  }

  /** Working memory grew past its bound. */
  public static final class FactLimit extends RuleEngineLimitExceeded {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The configured maximum number of facts. */
    private final int limit;

    /** The working-memory size that exceeded the limit. */
    private final int actual;

    /**
     * Creates the exception.
     *
     * @param limit the fact limit that was exceeded
     * @param actual the working-memory size that exceeded it
     * @param partialResult everything completed before the breach
     */
    public FactLimit(final int limit, final int actual, final FireResult partialResult) {
      super("fact limit of " + limit + " exceeded; working memory holds " + actual, partialResult);
      this.limit = limit;
      this.actual = actual;
    }

    /**
     * The limit that was exceeded.
     *
     * @return the configured maximum number of facts
     */
    public int limit() {
      return limit;
    }

    /**
     * The size that exceeded it.
     *
     * @return the working-memory size
     */
    public int actual() {
      return actual;
    }
  }
}
