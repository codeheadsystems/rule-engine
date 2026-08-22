package com.codeheadsystems.rules.concurrent;

import com.codeheadsystems.rules.session.FireResult;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * How a {@link SessionActor} behaves at its edges (spec §5.4).
 *
 * <p>Every field here exists because §5.4 names a hazard that has no safe default. An unbounded
 * inbox, an unbounded enqueue, or a silent error sink each turn a shutdown or a slow consumer into
 * a production incident rather than a test failure, so none of the three is left implicit.
 */
public final class ActorOptions {

  private final int inboxCapacity;
  private final Duration offerTimeout;
  private final Duration closeTimeout;
  private final Consumer<FireResult> onFire;
  private final Consumer<Throwable> onError;

  private ActorOptions(final Builder builder) {
    this.inboxCapacity = builder.inboxCapacity;
    this.offerTimeout = builder.offerTimeout;
    this.closeTimeout = builder.closeTimeout;
    this.onFire = builder.onFire;
    this.onError = builder.onError;
  }

  /**
   * A builder with defaults that are safe rather than generous.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Defaults.
   *
   * @return options with a bounded inbox and bounded waits
   */
  public static ActorOptions defaults() {
    return builder().build();
  }

  /**
   * How many commands may be queued before producers are made to wait.
   *
   * @return the inbox capacity
   */
  public int inboxCapacity() {
    return inboxCapacity;
  }

  /**
   * How long a producer waits for space before its submission is rejected.
   *
   * @return the offer timeout
   */
  public Duration offerTimeout() {
    return offerTimeout;
  }

  /**
   * How long {@link SessionActor#close()} waits for the worker on each of its two joins.
   *
   * <p>A close is bounded by twice this: once before interrupting the worker, once after.
   *
   * @return the close timeout
   */
  public Duration closeTimeout() {
    return closeTimeout;
  }

  /**
   * Where fire results go.
   *
   * @return the consumer, called on the worker thread
   */
  public Consumer<FireResult> onFire() {
    return onFire;
  }

  /**
   * Where a failure that belongs to no single command goes.
   *
   * @return the consumer, called on the worker thread
   */
  public Consumer<Throwable> onError() {
    return onError;
  }

  /** Builds {@link ActorOptions}. */
  public static final class Builder {

    private int inboxCapacity = 1024;
    private Duration offerTimeout = Duration.ofSeconds(10);
    private Duration closeTimeout = Duration.ofSeconds(10);
    private Consumer<FireResult> onFire = result -> { };
    private Consumer<Throwable> onError = failure -> { };

    private Builder() {
    }

    /**
     * Sets the inbox capacity.
     *
     * <p>Bounded on purpose. §5.4 wants a bounded inbox because an unbounded one converts
     * back-pressure into heap: a producer faster than the rule set never blocks, and the queue
     * grows until the process dies with a queue full of work nobody asked to be buffered.
     *
     * @param value the capacity; must be positive
     * @return this builder
     */
    public Builder inboxCapacity(final int value) {
      if (value <= 0) {
        throw new IllegalArgumentException("inbox capacity must be positive, got " + value);
      }
      this.inboxCapacity = value;
      return this;
    }

    /**
     * Sets how long a producer waits for inbox space.
     *
     * <p>§5.4's first hazard: "a blocking put between the 'is the actor running' check and the
     * enqueue can block forever if the worker exits in that window with a full inbox -- the drain
     * has already run and nothing will poll again". A timeout is what makes that window survivable,
     * so this is bounded and there is no way to ask for an unbounded wait.
     *
     * @param value how long to wait; must be positive
     * @return this builder
     */
    public Builder offerTimeout(final Duration value) {
      this.offerTimeout = requirePositive(value, "offerTimeout");
      return this;
    }

    /**
     * Sets how long {@link SessionActor#close()} waits for the worker on each join.
     *
     * <p>A close waits up to twice this in total: the first join is the grace period, and the
     * second follows the interrupt that the first one expiring triggers.
     *
     * @param value how long to wait; must be positive
     * @return this builder
     */
    public Builder closeTimeout(final Duration value) {
      this.closeTimeout = requirePositive(value, "closeTimeout");
      return this;
    }

    /**
     * Sets where fire results are delivered.
     *
     * <p>Called on the worker thread, so a slow consumer is back-pressure on the rule engine. That
     * is the honest arrangement -- handing results to another queue would need a second bound and a
     * second rejection policy -- but it means the consumer should hand off rather than work.
     *
     * @param value the consumer
     * @return this builder
     */
    public Builder onFire(final Consumer<FireResult> value) {
      this.onFire = Objects.requireNonNull(value, "onFire");
      return this;
    }

    /**
     * Sets where a failure with no owning command is reported.
     *
     * <p>A command's own failure completes that command's future; this is for what is left. The
     * fire loop runs on the actor's schedule rather than any caller's, so a rule set that throws
     * during a fire has no future to fail -- without this it would be swallowed, which is how a
     * streaming session goes quiet without anyone learning why.
     *
     * @param value the consumer
     * @return this builder
     */
    public Builder onError(final Consumer<Throwable> value) {
      this.onError = Objects.requireNonNull(value, "onError");
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public ActorOptions build() {
      return new ActorOptions(this);
    }

    /**
     * Rejects a null, zero or negative duration.
     *
     * @param value the duration
     * @param name what to call it in the message
     * @return the duration
     */
    private static Duration requirePositive(final Duration value, final String name) {
      Objects.requireNonNull(value, name);
      if (value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive, got " + value);
      }
      return value;
    }
  }
}
