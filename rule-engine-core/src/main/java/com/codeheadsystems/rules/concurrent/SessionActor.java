package com.codeheadsystems.rules.concurrent;

import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.RuleEngineLimitExceeded;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One long-lived session, one worker thread, and a bounded inbox in front of it (spec §5.4).
 *
 * <pre>{@code
 * try (SessionActor actor = SessionActor.start(rules, streaming, options)) {
 *   actor.submit(session -> session.insert("Order", payload)).join();
 * }
 * }</pre>
 *
 * <p>§5.4 defers this to Phase 3 deliberately, and says why: the actor exists to serve §11.1's
 * option (B), a session fed continuously by many producers, and specifying it while the engine only
 * had one-shot sessions would have put it a phase ahead of the shape it exists for. v1 ships §5.2's
 * across-session model instead, which needs no inbox and no cross-thread handoff.
 *
 * <p><strong>The worker is the single writer, and that is the whole design.</strong> §5.1 forbids
 * touching a session from two threads; producers therefore never touch it. They enqueue a command
 * and the worker applies it, which preserves the rule without a lock, exactly as §5.4 describes.
 *
 * <h2>Where {@code fireUntilHalt} went</h2>
 *
 * <p>§5.4 is emphatic that it "belongs to this pattern and only to it", and it is deliberately
 * not a method here either. The reason is worth stating, because a method called
 * {@code fireUntilHalt} is what most callers would look for: if the calling thread blocks in a
 * fire loop, facts have to arrive from another thread -- and under single-writer that is a data
 * race, not a supported pattern. The only safe arrangement is that the loop and the inserts are
 * the same thread servicing a queue, which is precisely this class's worker. Firing until halt
 * is therefore what the actor
 * <em>does</em> rather than something you call: it drains commands, fires to quiescence, reports
 * the result, and repeats until {@link #halt()} or {@link #close()}.
 *
 * <p>Results go to {@link ActorOptions#onFire()} rather than to a caller, because a fire cycle
 * triggered by a drained batch belongs to no single submission.
 *
 * <h2>§5.4's hazards, and one thing §10 asks for</h2>
 *
 * <ul>
 *   <li><strong>Enqueue is bounded.</strong> {@link #submit} offers with a timeout and rejects; it
 *       never blocks indefinitely. §5.4's own scenario is the check-then-enqueue window -- a
 *       producer that has passed "is the actor running" and is blocked putting into a full inbox
 *       that nothing will ever poll again.
 *   <li><strong>{@link #close()} is bounded and fails what it drains.</strong> Every command still
 *       queued is completed exceptionally. Dropping them silently turns a shutdown into futures
 *       that never complete, which is indistinguishable from a hung engine.
 *   <li><strong>Batching is §10's "fireAsync coalesces".</strong> §10's checklist asks that "the
 *       actor inbox is bounded, {@code close()} is bounded, and {@code fireAsync} coalesces", and
 *       {@code fireAsync} is named nowhere else in the spec -- §5.4 never defines it. The
 *       coalescing it asks for is what {@link #submit} plus the worker's drain-then-fire does: a
 *       burst of submissions is applied and then fired once, rather than firing per command. Named
 *       here rather than left as an unmet checklist line.
 *   <li><strong>Commands carry their own futures.</strong> The inbox holds command objects rather
 *       than bare {@code Runnable}s so that the drain can reach each caller's future. §5.4 puts it
 *       as: the future must be reachable <em>from the queue</em>.
 * </ul>
 */
public final class SessionActor implements AutoCloseable {

  /**
   * How long the worker waits on an empty inbox before re-checking whether it should stop.
   *
   * <p>Deliberately not {@link ActorOptions#offerTimeout()}, which an earlier version reused and
   * which is a different question: that one is how long a <em>producer</em> tolerates a full inbox,
   * and it is measured in seconds because rejecting work is disruptive. Reusing it here made
   * {@link #halt()} take up to that long to be noticed, because the worker was parked in a poll
   * that nothing woke. Conflating back-pressure with responsiveness costs nothing until you halt.
   *
   * <p>Short enough that a halt is prompt, and on a virtual thread an idle wake-up every tick is
   * not a cost worth configuring.
   */
  private static final Duration IDLE_TICK = Duration.ofMillis(50);

  private final RuleSession session;
  private final ActorOptions options;
  private final BlockingQueue<Submission<?>> inbox;
  private final Thread worker;

  /**
   * Set before the worker is asked to stop, and checked by {@link #submit} before enqueueing.
   *
   * <p>It does not close the check-then-enqueue window on its own -- nothing can, without a lock
   * the single-writer model exists to avoid -- which is why the enqueue is bounded and why
   * {@link #close()} drains and fails whatever slipped through.
   */
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  /** So concurrent {@link #close()} calls report one failed shutdown rather than one each. */
  private final AtomicBoolean shutdownReported = new AtomicBoolean();

  private SessionActor(final RuleSession session, final ActorOptions options) {
    this.session = session;
    this.options = options;
    this.inbox = new ArrayBlockingQueue<>(options.inboxCapacity());
    this.worker = Thread.ofVirtual().name("rule-session-actor").unstarted(this::run);
  }

  /**
   * Starts an actor over a new session on the given rule set.
   *
   * @param rules the compiled rules
   * @param sessionOptions how the session is configured; {@code MatchingStrategy.RETE} is the
   *     shape this pattern exists for, though nothing here requires it
   * @param actorOptions how the actor behaves at its edges
   * @return a running actor
   */
  public static SessionActor start(final CompiledRuleSet rules,
      final SessionOptions sessionOptions, final ActorOptions actorOptions) {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(sessionOptions, "sessionOptions");
    Objects.requireNonNull(actorOptions, "actorOptions");
    final SessionActor actor =
        new SessionActor(rules.newSession(sessionOptions), actorOptions);
    try {
      actor.worker.start();
    } catch (final RuntimeException | Error failed) {
      // Nothing owns the session yet -- the worker that would have is what failed to start -- so
      // this thread is the only one that can close it, and must.
      actor.session.close();
      throw failed;
    }
    return actor;
  }

  /**
   * Hands work to the worker.
   *
   * <p>The command runs on the worker thread with exclusive access to the session, which is what
   * makes it safe to insert, update, retract or read from it. Do not let the session escape the
   * command: a reference kept and used from another thread is precisely the violation this class
   * exists to prevent.
   *
   * <p><strong>The returned future is completed on the worker thread.</strong> Any stage attached
   * to it before it completes therefore runs there too, and while it runs the worker is not
   * draining the inbox. A dependent stage that submits again is the trap:
   * {@code submit(a).thenRun(() -> actor.submit(b))} runs the inner submit on the only thread that
   * could make room for it, so against a full inbox it cannot succeed -- it blocks the entire actor
   * for the whole {@link ActorOptions#offerTimeout()} and is then rejected. Hand off with
   * {@code thenRunAsync(..., executor)} if a continuation needs to submit.
   *
   * <p><strong>The future completes when the command has been applied, not when the fire cycle it
   * caused has finished.</strong> Those are different moments and the gap is where a caller goes
   * wrong: awaiting an insert and then reading {@link ActorOptions#onFire()}'s results can find
   * nothing there yet, because the worker completes the command, then drains whatever else is
   * waiting, then fires once for the batch. That batching is the point of the pattern -- a burst of
   * inserts costs one fire cycle -- and it is precisely why a command cannot carry its own fire
   * result. Wait on the sink if you need the firing; wait on the future if you need the command.
   *
   * @param <T> what the command produces
   * @param command what to do with the session
   * @return a future completed with the command's result, or completed exceptionally if the command
   *     throws, if the inbox stays full for {@link ActorOptions#offerTimeout()}, or if the actor
   *     stops before the command runs
   */
  public <T> CompletableFuture<T> submit(final SessionCommand<T> command) {
    Objects.requireNonNull(command, "command");
    final Submission<T> submission = new Submission<>(command, new CompletableFuture<>());
    if (!accepting.get()) {
      submission.future.completeExceptionally(
          new IllegalStateException("actor is not accepting work"));
      return submission.future;
    }
    try {
      if (!inbox.offer(submission, options.offerTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
        submission.future.completeExceptionally(new IllegalStateException(
            "actor inbox full for " + options.offerTimeout() + "; work rejected rather than"
                + " blocking indefinitely (§5.4)"));
        return submission.future;
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      submission.future.completeExceptionally(interrupted);
      return submission.future;
    }
    if (!accepting.get()) {
      /*
       * The check-then-enqueue window, closed from this end. A close() that ran between the check
       * above and the offer has already drained, so nothing will poll this submission -- and its
       * future would never complete. Removing it is a no-op if the worker took it first, and
       * completing an already-completed future is a no-op too, so both orders are safe.
       */
      if (removeByIdentity(submission)) {
        submission.future.completeExceptionally(
            new IllegalStateException("actor stopped before this command ran"));
      }
    }
    return submission.future;
  }

  /**
   * Asks the session to stop firing, without closing the actor.
   *
   * <p>{@code halt()} is the one session method §5.1 permits from another thread, and it is
   * terminal: a halted session stays halted, so the worker finishes its current cycle and then
   * stops. <strong>The actor stops accepting work at the same moment</strong>, and anything
   * submitted afterwards is failed rather than queued -- an earlier version left it accepting on a
   * dead worker, so those submissions were never completed by anyone, which is a worse outcome for
   * a caller than a rejection.
   */
  public void halt() {
    /*
     * accepting first, then the session. The Javadoc's claim that work stops being accepted at the
     * same moment was false when this only halted the session: `accepting` was cleared by the
     * worker's finally up to a tick later, and every submission in that window was applied and
     * reported success to its caller -- on a session that fires nothing and is closed milliseconds
     * afterwards. Making the claim true costs one line; softening it to describe the window would
     * have left callers to defend against a command that "succeeded" and did nothing.
     */
    accepting.set(false);
    session.halt();
  }

  /**
   * Stops accepting work, waits for the worker, and fails whatever is still queued.
   *
   * <p><strong>Bounded by twice {@link ActorOptions#closeTimeout()}</strong>, not once: the worker
   * is joined, interrupted if that join expires, then joined again for the same period.
   *
   * <p><strong>If the worker still has not stopped, the session is deliberately left open.</strong>
   * The worker owns it -- closing it from this thread is the §5.1 violation this class exists to
   * prevent -- so what happens instead is that the queue is failed, {@link ActorOptions#onError()}
   * receives a report, and this returns with {@link #running()} still true. That is the signal:
   * a caller who needs to know whether shutdown succeeded should check {@code running()}, because
   * the failure is reported to the error sink rather than thrown, and the sink's owner is usually
   * not whoever called {@code close()}.
   *
   * <p><strong>The in-flight command's future is not completed</strong>, and cannot be. §5.4's
   * requirement that no future dangle is met for everything the shutdown can reach -- the queue --
   * but a command that never returns is the one thing a bounded shutdown cannot bound. Everything
   * queued behind it is failed.
   *
   * <p>Throws if called from inside a command: the worker cannot join itself.
   */
  @Override
  public void close() {
    if (Thread.currentThread() == worker) {
      /*
       * Called from inside a command. Measured rather than assumed, because the obvious guess is
       * wrong: Thread.join(millis) has no self-check, so the first join blocks for the FULL close
       * timeout -- ten seconds by default -- with the actor frozen, since the thread it is waiting
       * for is the one doing the waiting. Only the second join returns at once, and only because
       * the interrupt between them left the flag set. The command then finds itself interrupted,
       * every queued command is failed with the untrue reason "actor stopped before this command
       * ran", and onError is told the session was not closed when it is about to be.
       *
       * No use-after-free and no two threads on the session -- but a close() that freezes the actor
       * and then reports a shutdown failure that did not happen is the same class of untruth as the
       * defect this class was redesigned to fix. It throws rather than degrading.
       */
      throw new IllegalStateException(
          "close() must not be called from a command; the worker cannot join itself");
    }
    accepting.set(false);
    session.halt();
    try {
      worker.join(options.closeTimeout().toMillis());
      if (worker.isAlive()) {
        /*
         * Interrupted only now. Interrupting up front -- which an earlier version did -- killed an
         * in-flight command's interruptible wait while the close timeout had barely begun, and left
         * the flag set for the rest of the batch. The timeout is the grace period; this is what
         * happens when it runs out.
         */
        worker.interrupt();
        worker.join(options.closeTimeout().toMillis());
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) {
      /*
       * Wedged in something neither the halt nor the interrupt could stop. It still owns the
       * session, so this thread must not close it: that is the §5.1 violation this class exists to
       * prevent, and a use-after-free the day RuleSession.close() frees anything. Fail what is
       * queued so nobody holds a future that will never complete, and report rather than return as
       * though the shutdown had succeeded.
       */
      failRemaining(new IllegalStateException("actor closed while a command was still running"));
      if (shutdownReported.compareAndSet(false, true)) {
        // Once per actor, not once per closer: concurrent close() calls otherwise report the same
        // failure N times.
        options.onError().accept(new IllegalStateException(
            "actor worker did not stop within " + options.closeTimeout()
                + "; it still owns the session, which has not been closed"));
      }
    }
    // Otherwise the worker's own finally has already failed the queue and closed the session.
  }

  /**
   * Whether the worker is still running.
   *
   * @return true until the worker has exited
   */
  public boolean running() {
    return worker.isAlive();
  }

  /**
   * The worker loop, and the owner of everything the actor holds.
   *
   * <p><strong>The session is used and closed by this thread and no other.</strong> An earlier
   * version had {@link #close()} call {@code session.close()} on the caller's thread after a
   * timed-out join, which put two threads on one session -- §5.1's single rule, broken by the class
   * that exists to keep it. Shutdown is now a signal this loop acts on.
   */
  private void run() {
    try {
      while (accepting.get() && !session.halted() && !session.failed()) {
        final Submission<?> first;
        try {
          first = inbox.poll(IDLE_TICK.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException interrupted) {
          /*
           * Whose interrupt was it? If shutdown has been signalled it is close()'s, and stopping is
           * right. If not, something else interrupted this thread -- a command that restored an
           * interrupt it observed, which is the idiom every style guide requires, is the realistic
           * case -- and exiting on it would stop a long-lived actor with no diagnostic at all,
           * which is exactly what ActorOptions.onError exists to prevent. Clear it, report, carry
           * on.
           */
          if (!accepting.get()) {
            Thread.currentThread().interrupt();
            break;
          }
          Thread.interrupted();
          report(() -> options.onError().accept(new IllegalStateException(
              "worker was interrupted by something other than close(); continuing", interrupted)));
          continue;
        }
        if (first == null) {
          continue;
        }
        applyBatch(first);
        fireOnce();
      }
      if (session.failed()) {
        // Otherwise the only clue a streaming actor stopped is the RHS exception fireOnce reported
        // some batches ago, and running() simply flips to false.
        report(() -> options.onError().accept(new IllegalStateException(
            "session failed on a rule action and cannot be used further; actor stopping")));
      }
    } finally {
      /*
       * Every exit runs this, an Error out of a command included. An earlier version put it after
       * the loop rather than in a finally, so a StackOverflowError from a deep expression killed
       * the worker with every queued future left dangling -- and `accepting` still true, so every
       * later submission dangled as well.
       */
      /*
       * THE ORDER OF THESE THREE IS LOAD-BEARING. Do not tidy it.
       *
       * `accepting` first, because submit()'s post-offer recheck is what closes the
       * check-then-enqueue window, and it only works if an offer landing after the drain is
       * guaranteed to read false and withdraw itself. Swap the first two -- "fail the queue, then
       * stop accepting", the natural-looking refactor -- and a producer that offers in between
       * keeps a pending future on a worker that is already gone. That window is nanoseconds wide,
       * which is exactly why no test reliably catches it and why this comment exists.
       *
       * `session.close()` last, because a throw from it must not skip the drain.
       */
      accepting.set(false);
      failRemaining(new IllegalStateException("actor stopped before this command ran"));
      session.close();
    }
  }

  /**
   * Applies commands until the inbox is momentarily empty, so a burst costs one fire cycle.
   *
   * <p><strong>One at a time, never into a list.</strong> {@code drainTo} is the obvious way to
   * batch and is wrong here: it removes submissions from the queue before any of them runs, and
   * §5.4 requires a caller's future stay reachable <em>from the queue</em> so a shutdown can fail
   * it. Pre-drained, a worker that wedged on the second command left the rest unreachable --
   * {@link #close()} drained an empty inbox, completed nothing, and those commands then ran after
   * close had returned.
   *
   * <p>Polling one at a time means the only submission outside the queue is the one being applied,
   * and that one's future is completed by {@link Submission#applyTo} whatever it throws.
   *
   * @param first the submission that woke the worker
   */
  private void applyBatch(final Submission<?> first) {
    Submission<?> next = first;
    while (next != null) {
      next.applyTo(session);
      /*
       * Cleared BEFORE `accepting` is read, not after. The flag belongs to this thread rather
       * than to the command that just ran -- a command restoring an interrupt it observed is
       * doing the right thing -- but clearing it after the read would discard a close()
       * escalation that landed in between, and the worker would carry on into the next command
       * with close()'s interrupt silently swallowed. Clearing first means anything close() sends
       * after this point survives to the poll below, and close() sets `accepting` before it
       * interrupts, so the check underneath still catches the ordinary case.
       */
      Thread.interrupted();
      if (!accepting.get()) {
        /*
         * Stop pulling once shutdown has been signalled. Without this the worker kept draining the
         * queue after close() had asked it to stop -- commands applied to a session being torn
         * down, and their futures completing SUCCESSFULLY, which is a worse lie than failing them.
         * What is left stays in the queue, where the finally in run() fails it.
         *
         * `accepting` and NOT the interrupt flag, which an earlier version also checked. The
         * interrupt is the mechanism close() uses; `accepting` is the fact it wants to convey, and
         * it is set before the interrupt. Reading the flag here could not distinguish "close()
         * interrupted me" from "the command I just ran restored an interrupt it observed" -- which
         * is the idiom every style guide requires -- so a well-behaved command silently killed a
         * long-lived actor.
         */
        return;
      }
      next = inbox.poll();
    }
  }

  /**
   * Fires to quiescence and reports.
   *
   * <p>A failure is reported rather than rethrown, so one bad cycle does not kill the worker and
   * strand every future behind it; in a streaming session the next batch may well succeed. What
   * that reasoning does <em>not</em> cover is a session failed permanently by §4.6's
   * {@code RETHROW} policy, which the loop condition checks separately. Without that the actor
   * stayed alive and accepting on a session that could never fire again, reporting the same error
   * once per batch while {@code running()} reported green.
   */
  private void fireOnce() {
    try {
      final FireResult result = session.fireAllRules();
      report(() -> options.onFire().accept(result));
    } catch (final RuleEngineLimitExceeded breach) {
      /*
       * §4.7 keeps the work a limit breach interrupted -- a batch that fired 9,999 rules must not
       * lose all of it -- so the partial result reaches the sink before the breach reaches onError.
       * Reporting only the throwable would drop firings that really happened.
       */
      report(() -> options.onFire().accept(breach.partialResult()));
      report(() -> options.onError().accept(breach));
    } catch (final RuntimeException failure) {
      report(() -> options.onError().accept(failure));
    } catch (final Error error) {
      /*
       * §4.6's RETHROW path propagates an Error unwrapped, so one out of a rule action escaped
       * every catch here, escaped run(), and hit the finally: futures failed, session closed,
       * worker gone, and nothing on any sink. That is the incident ActorOptions.onError exists
       * to prevent -- a streaming session going quiet with no one learning why -- and it is worse
       * than the
       * RuntimeException case because there is no future to carry the cause either.
       *
       * Reported, then rethrown. Continuing on an Error is not defensible; being told about it is
       * not optional.
       */
      report(() -> options.onError().accept(error));
      throw error;
    }
  }

  /**
   * Runs a caller-supplied consumer without letting it take the worker down.
   *
   * <p>{@code onFire} and {@code onError} are the caller's code on the worker thread. A throw from
   * either would otherwise escape the loop -- and from {@code onError} it would do so while the
   * worker was already handling a failure.
   *
   * @param dispatch the consumer invocation
   */
  private void report(final Runnable dispatch) {
    try {
      dispatch.run();
    } catch (final RuntimeException ignored) {
      // Nowhere left to report it: this is the reporting path.
    }
  }

  /**
   * Removes one submission by identity rather than by equality.
   *
   * <p>{@code Queue.remove(Object)} uses {@code equals}, and {@code Submission} is a record whose
   * equality delegates to the caller-supplied {@link SessionCommand}. A command implemented as a
   * class with value-based equality would therefore let one producer's withdrawal remove a
   * different producer's queued submission -- and strand it, since nothing would then complete it.
   * {@code SessionCommand} is public API, so that is a caller's choice to make, not a caller's
   * mistake to be punished for.
   *
   * @param submission the submission to withdraw
   * @return whether it was still queued
   */
  private boolean removeByIdentity(final Submission<?> submission) {
    for (final Iterator<Submission<?>> queued = inbox.iterator(); queued.hasNext();) {
      if (queued.next() == submission) {
        queued.remove();
        return true;
      }
    }
    return false;
  }

  /**
   * Completes every queued command exceptionally.
   *
   * @param cause why they will not run
   */
  private void failRemaining(final RuntimeException cause) {
    final List<Submission<?>> abandoned = new ArrayList<>();
    inbox.drainTo(abandoned);
    final List<Thread> completers = new ArrayList<>(abandoned.size());
    for (final Submission<?> submission : abandoned) {
      completers.add(Thread.ofVirtual().name("rule-session-actor-completer")
          .start(() -> submission.future.completeExceptionally(cause)));
    }
    /*
     * Then awaited, against ONE shared deadline for the whole loop. Dispatching alone made these
     * completions merely likely: a virtual thread is a daemon thread, so `actor.close();
     * System.exit(0)` could leave futures uncompleted where inline completion guaranteed them --
     * and §5.4 states hazard 2 absolutely. Waiting restores the guarantee in the common case, where
     * nothing blocks and every completer finishes immediately, while the shared deadline keeps a
     * blocking continuation from extending close() past its documented bound. Both properties, not
     * one at the cost of the other.
     */
    final long deadline = System.nanoTime() + options.closeTimeout().toNanos();
    for (final Thread completer : completers) {
      final long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      try {
        completer.join(Duration.ofNanos(remaining));
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * A queued command and the future that belongs to it.
   *
   * <p>One object rather than a {@code Runnable} closing over a future, so that a drain can reach
   * the future of a command that will now never run -- §5.4's requirement, and the difference
   * between a bounded shutdown and a set of futures nobody completes.
   *
   * @param <T> what the command produces
   * @param command the work
   * @param future the caller's handle on it
   */
  private record Submission<T>(SessionCommand<T> command, CompletableFuture<T> future) {

    /**
     * Runs the command against the session and completes the future either way.
     *
     * @param session the session, owned by the worker thread
     */
    void applyTo(final RuleSession session) {
      try {
        future.complete(command.apply(session));
      } catch (final InterruptedException interrupted) {
        /*
         * Catching InterruptedException CLEARS the flag, so this restores it -- the contract every
         * caller of a blocking method owes its own caller. Note the direction: restoring is what
         * leaves the flag set for the rest of the batch, which is why applyBatch clears it after
         * each command rather than reading it. An earlier comment here had that backwards, and an
         * earlier applyBatch acted on the misunderstanding and died on well-behaved commands.
         */
        Thread.currentThread().interrupt();
        future.completeExceptionally(interrupted);
      } catch (final Throwable failure) {
        /*
         * Throwable, not Exception. An Error -- a StackOverflowError out of a deep expression being
         * the realistic one here -- escaped the batch loop and killed the worker before its own
         * future was completed, so the caller whose command blew up waited forever, along with
         * everyone queued behind. Completing the future first is what makes the failure visible;
         * rethrowing then lets the worker's finally shut down cleanly rather than looping on a
         * broken session.
         */
        future.completeExceptionally(failure);
        if (failure instanceof Error error) {
          throw error;
        }
      }
    }
  }

  /**
   * What a producer asks the worker to do with the session.
   *
   * <p>Not {@code Function<RuleSession, T>} so that a command may throw: a schema rejection or a
   * host function's checked failure belongs on that command's future, and forcing it to be wrapped
   * at the call site would defeat that.
   *
   * @param <T> what the command produces
   */
  @FunctionalInterface
  public interface SessionCommand<T> {

    /**
     * Runs against the session.
     *
     * @param session the session; do not retain it beyond this call
     * @return the result, delivered to the submitter's future
     * @throws Exception whatever the command throws, delivered to that same future
     */
    T apply(RuleSession session) throws Exception;
  }
}
