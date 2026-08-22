package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.codeheadsystems.rules.compiler.RuleCompiler;
import com.codeheadsystems.rules.concurrent.ActorOptions;
import com.codeheadsystems.rules.concurrent.SessionActor;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.SessionOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The actor of §5.4, and specifically the three hazards that section names.
 *
 * <p>§5.4 calls each of them "a production incident rather than a test failure", which is the
 * argument for testing them rather than the usual happy path: an unbounded enqueue, a shutdown that
 * drops futures, and a fire loop on the wrong thread all behave perfectly until the day they
 * do not.
 */
class SessionActorTest {

  /**
   * How many futures the stranding probe will retain before it stops on its own.
   *
   * <p>Comfortably above what the probe needs -- the actor it waits on fails on its first fire
   * cycle, so a healthy run issues a few hundred -- and far below what exhausts a test worker's
   * default heap. See the loop that reads it.
   */
  private static final int STRANDING_PROBE_CEILING = 50_000;

  private static CompiledRuleSet rules() {
    return RuleCompiler.compile(List.of(Rules.rule("seen")
        .noLoop()
        .when("o", "Order", pattern -> pattern.gt("total", 0))
        .then(actions -> actions.emit("seen", "id", Rules.ref("o.id")))
        .build()));
  }

  /**
   * Waits for the fire sink to report a total, rather than assuming it already has.
   *
   * @param fired the sink
   * @param expected the firing count to wait for
   * @return the total observed, which the caller asserts on
   */
  private static int awaitFirings(final ConcurrentLinkedQueue<FireResult> fired,
      final int expected) throws InterruptedException {
    for (int attempt = 0; attempt < 200; attempt++) {
      final int total = fired.stream().mapToInt(FireResult::firedCount).sum();
      if (total >= expected) {
        return total;
      }
      Thread.sleep(50);
    }
    return fired.stream().mapToInt(FireResult::firedCount).sum();
  }

  /** Streaming is what this pattern exists for, so the tests use that shape. */
  private static SessionOptions streaming() {
    return SessionOptions.builder().matching(MatchingStrategy.RETE).build();
  }

  @Nested
  @DisplayName("the loop and the inserts are the same thread")
  class SingleWriter {

    @Test
    @DisplayName("many producers feed one session and every command runs on the worker")
    void producersNeverTouchTheSession() throws Exception {
      /*
       * §5.1's rule is that a session is never touched by two threads, and §5.4's answer is that
       * producers enqueue rather than touch. Asserted by recording which thread each command ran
       * on: one name, whatever the producer count.
       */
      final ConcurrentLinkedQueue<Long> threads = new ConcurrentLinkedQueue<>();
      final ConcurrentLinkedQueue<FireResult> fired = new ConcurrentLinkedQueue<>();
      final int producers = 16;
      final int perProducer = 25;

      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().onFire(fired::add).build())) {
        final CountDownLatch go = new CountDownLatch(1);
        final List<CompletableFuture<Integer>> submitted = new java.util.ArrayList<>();
        try (ExecutorService producersPool = Executors.newVirtualThreadPerTaskExecutor()) {
          for (int producer = 0; producer < producers; producer++) {
            final int id = producer;
            producersPool.submit(() -> {
              go.await();
              for (int index = 0; index < perProducer; index++) {
                final int order = id * perProducer + index;
                synchronized (submitted) {
                  submitted.add(actor.submit(session -> {
                    // threadId, not getName: every thread this actor could spawn carries the
                    // same literal name, so asserting on names passes against a
                    // thread-per-command implementation -- maximally in violation of the very
                    // property this test exists to check.
                    threads.add(Thread.currentThread().threadId());
                    session.insert("Order", Facts.obj("id", order, "total", 10));
                    return order;
                  }));
                }
              }
              return null;
            });
          }
          go.countDown();
        }
        for (final CompletableFuture<Integer> future : submitted) {
          future.get(30, TimeUnit.SECONDS);
        }

        assertThat(threads).hasSize(producers * perProducer);
        assertThat(java.util.Set.copyOf(threads))
            .describedAs("every command must run on the one worker thread")
            .hasSize(1);
      }
    }

    @Test
    @DisplayName("a burst of commands costs one fire cycle, not one per command")
    void burstsAreBatched() throws Exception {
      // The actor's performance argument over submit-and-fire: it drains what is already waiting
      // before firing. Not asserted as an exact number -- how many batches a burst lands in is a
      // scheduling detail -- only that it is fewer than the command count, which is the claim.
      final AtomicInteger cycles = new AtomicInteger();
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().onFire(result -> cycles.incrementAndGet()).build())) {
        final List<CompletableFuture<Void>> submitted = new java.util.ArrayList<>();
        for (int order = 0; order < 200; order++) {
          final int id = order;
          submitted.add(actor.submit(session -> {
            session.insert("Order", Facts.obj("id", id, "total", 10));
            return null;
          }));
        }
        for (final CompletableFuture<Void> future : submitted) {
          future.get(30, TimeUnit.SECONDS);
        }
        // Waits for the sink, for the third time in this file and the same reason each time: a
        // submission's future completes when the command is applied, and the fire cycle it feeds
        // happens after the batch. Reading the counter here without waiting sees zero.
        for (int attempt = 0; attempt < 200 && cycles.get() == 0; attempt++) {
          Thread.sleep(50);
        }
        assertThat(cycles.get()).isPositive().isLessThan(200);
      }
    }
  }

  @Nested
  @DisplayName("§5.4's three hazards")
  class Hazards {

    @Test
    @DisplayName("a full inbox rejects rather than blocking forever")
    void enqueueIsBounded() throws Exception {
      /*
       * §5.4's first hazard. A capacity of one and a worker wedged inside a command means the
       * inbox fills immediately; the submission that finds it full must come back rejected within
       * the offer timeout rather than parking on a queue nothing will drain.
       */
      final CountDownLatch wedged = new CountDownLatch(1);
      final CountDownLatch release = new CountDownLatch(1);
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().inboxCapacity(1)
              .offerTimeout(Duration.ofMillis(200)).build())) {
        actor.submit(session -> {
          wedged.countDown();
          release.await();
          return null;
        });
        assertThat(wedged.await(10, TimeUnit.SECONDS)).isTrue();

        actor.submit(session -> null);   // fills the one slot
        final long startedAt = System.nanoTime();
        final CompletableFuture<Object> rejected = actor.submit(session -> null);

        assertThat(rejected)
            .describedAs("the third submission must be rejected, not parked")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
            .describedAs("and must give up near the offer timeout rather than hang")
            .isLessThan(Duration.ofSeconds(5));
        release.countDown();
      }
    }

    @Test
    @DisplayName("close fails every command it drains rather than dropping it")
    void closeFailsWhatItDrains() throws Exception {
      /*
       * §5.4's second hazard: "silent dropping turns a shutdown into a set of futures that never
       * complete". A worker wedged in one command, a queue behind it, then close -- every one of
       * those futures must complete, exceptionally, and none may be left dangling.
       */
      final CountDownLatch wedged = new CountDownLatch(1);
      final AtomicBoolean release = new AtomicBoolean();
      final List<CompletableFuture<Object>> queued = new java.util.ArrayList<>();

      final List<Throwable> errors = java.util.Collections.synchronizedList(
          new java.util.ArrayList<>());
      final SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().inboxCapacity(64).onError(errors::add)
              .closeTimeout(Duration.ofMillis(300)).build());
      actor.submit(session -> {
        wedged.countDown();
        /*
         * A NON-interruptible wedge, deliberately. An earlier version waited on a latch, which
         * close()'s interrupt released -- so the close-timeout path, which is the one that can
         * strand futures, was never reached and the test passed without exercising what it named.
         */
        while (!release.get()) {
          LockSupport.parkNanos(1_000_000L);
        }
        return null;
      });
      assertThat(wedged.await(10, TimeUnit.SECONDS)).isTrue();
      for (int index = 0; index < 10; index++) {
        queued.add(actor.submit(session -> null));
      }

      actor.close();

      // Asserted BEFORE releasing the wedge, so this checks the timeout branch it is named for
      // rather than a clean shutdown that happened to fail the same futures. Without these the test
      // passes whether or not close() ever timed out.
      assertThat(actor.running())
          .describedAs("close must have given up on a worker it could not stop").isTrue();
      assertThat(errors)
          .describedAs("and must have said so on the error sink").isNotEmpty();

      release.set(true);

      for (final CompletableFuture<Object> future : queued) {
        // failsWithin, not isCompletedExceptionally: close() dispatches these completions rather
        // than running them inline, so that a caller's blocking continuation cannot extend the
        // bound §5.4 requires of it. They complete promptly; they are not guaranteed complete the
        // instant close() returns.
        assertThat(future)
            .describedAs("every queued command's future must be completed by close")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
      }
    }

    @Test
    @DisplayName("halt stops accepting too, so nothing submitted afterwards is stranded")
    void haltDoesNotStrandSubmissions() throws Exception {
      /*
       * §5.4's hazard 1 reached through halt() instead of close(). The worker exits on a halted
       * session; if that path does not also stop accepting, submit() passes its check, offers into
       * a queue with room, and nobody ever polls it -- the future completes never, not even
       * exceptionally. That is worse than rejection, because a caller waiting on it has no signal
       * at all.
       */
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.defaults())) {
        actor.halt();
        for (int attempt = 0; attempt < 100 && actor.running(); attempt++) {
          Thread.sleep(50);
        }
        assertThat(actor.running()).isFalse();

        assertThat(actor.submit(session -> 1))
            .describedAs("a submission after halt must be failed, not left hanging")
            .failsWithin(Duration.ofSeconds(5))
            .withThrowableOfType(ExecutionException.class);
      }
    }

    @Test
    @DisplayName("an Error out of a command fails its own future and everything queued behind it")
    void errorDoesNotStrandFutures() throws Exception {
      /*
       * applyTo caught Exception, so an Error escaped the loop and killed the worker before
       * completing anything -- the failing command's own future included. A StackOverflowError out
       * of a deep expression is the realistic way to reach this in a rule engine.
       */
      final CompletableFuture<Object> boom;
      final CompletableFuture<Object> behind;
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.defaults())) {
        boom = actor.submit(session -> {
          throw new StackOverflowError("simulated");
        });
        behind = actor.submit(session -> null);
        assertThat(boom)
            .describedAs("the command that threw must still complete its own future")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
        assertThat(behind)
            .describedAs("and so must the one queued behind it")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
      }
    }

    @Test
    @DisplayName("a permanently failed session stops the actor rather than looping on it")
    void failedSessionStopsTheActor() throws Exception {
      /*
       * §4.6's RETHROW policy marks a session failed, after which every operation throws forever.
       * The actor used to stay alive on one -- accepting work, failing each command, handing the
       * same error to onError once per batch, with running() reporting green on a session that
       * could never fire again. A health check that cannot see that is worse than none.
       */
      final CompiledRuleSet exploding = RuleCompiler.compile(List.of(Rules.rule("boom")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.callFunction("explode"))
          .build()));

      try (SessionActor actor = SessionActor.start(exploding,
          SessionOptions.builder().matching(MatchingStrategy.RETE)
              .function("explode", args -> {
                throw new IllegalStateException("blew up");
              }).build(),
          ActorOptions.defaults())) {
        actor.submit(session -> {
          session.insert("Order", Facts.obj("total", 10));
          return null;
        }).get(30, TimeUnit.SECONDS);

        for (int attempt = 0; attempt < 100 && actor.running(); attempt++) {
          Thread.sleep(50);
        }
        assertThat(actor.running())
            .describedAs("a session that can never fire again must not leave a worker running")
            .isFalse();
      }
    }

    @Test
    @DisplayName("an Error from a fire cycle is reported before it takes the actor down")
    void errorFromFiringIsReported() throws Exception {
      /*
       * §4.6's RETHROW path propagates an Error unwrapped, so one out of a rule action escaped
       * every catch in fireOnce, escaped run(), and hit the finally: futures failed, session
       * closed, worker gone, nothing on any sink. Dying is defensible -- continuing after an
       * Error is not -- but dying in silence is the §5.4-class incident: a streaming session
       * going quiet with nobody learning why, which is what ActorOptions.onError exists to prevent.
       */
      final List<Throwable> errors = java.util.Collections.synchronizedList(
          new java.util.ArrayList<>());
      final CompiledRuleSet exploding = RuleCompiler.compile(List.of(Rules.rule("boom")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.callFunction("explode"))
          .build()));

      try (SessionActor actor = SessionActor.start(exploding,
          SessionOptions.builder().matching(MatchingStrategy.RETE)
              .function("explode", args -> {
                throw new StackOverflowError("simulated");
              }).build(),
          ActorOptions.builder().onError(errors::add).build())) {
        actor.submit(session -> {
          session.insert("Order", Facts.obj("total", 10));
          return null;
        }).get(30, TimeUnit.SECONDS);

        for (int attempt = 0; attempt < 100 && errors.isEmpty(); attempt++) {
          Thread.sleep(50);
        }
        assertThat(errors)
            .describedAs("an Error must reach the sink before the worker goes")
            .isNotEmpty();
      }
    }

    @Test
    @DisplayName("a command that restores its own interrupt flag does not kill the actor")
    void aRestoredInterruptFlagDoesNotStopTheWorker() throws Exception {
      /*
       * Restoring an interrupt you observed is the idiom every style guide requires, and it used to
       * be fatal here: applyBatch read the flag to decide whether shutdown had been signalled, so a
       * well-behaved command left it set, the next poll threw immediately, and a long-lived actor
       * stopped -- reporting nothing, because that exit path was silent. `accepting` is the fact
       * shutdown wants to convey; the interrupt is only the mechanism it uses.
       */
      final List<Throwable> errors = java.util.Collections.synchronizedList(
          new java.util.ArrayList<>());
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().onError(errors::add).build())) {
        assertThat(actor.submit(session -> {
          Thread.currentThread().interrupt();
          return "ok";
        }).get(30, TimeUnit.SECONDS)).isEqualTo("ok");

        Thread.sleep(400);

        assertThat(actor.running())
            .describedAs("a correct command must not stop the actor").isTrue();
        assertThat(actor.submit(session -> "later").get(30, TimeUnit.SECONDS))
            .describedAs("and the actor must still take work").isEqualTo("later");
        // And it must not merely survive noisily. If applyBatch reads the interrupt flag it
        // early-returns on this command and the next poll throws, which the recovery path catches
        // and REPORTS -- so the actor lives, and every well-behaved command costs a spurious error.
        // Without this assertion the surviving-actor check passes against that.
        assertThat(errors)
            .describedAs("restoring an interrupt flag is correct Java, not an actor error")
            .isEmpty();
      }
    }

    @Test
    @DisplayName("a batch caught mid-apply by close leaves nothing unreachable")
    void closeReachesCommandsAlreadyPulledIntoABatch() throws Exception {
      /*
       * The regression test for the blocker whose fix nothing pinned. `drainTo` moved the whole
       * queue into a local list before running any of it, so submissions stopped being reachable
       * from the queue -- §5.4's stated requirement -- and a worker wedged partway left close()
       * draining an empty inbox while the rest ran after close had returned.
       *
       * The recipe is what makes it bite: wedge the worker, queue a SECOND wedge plus more behind
       * it, then release the first so the worker takes them all in one batch. Under the old code
       * those futures completed successfully, after close.
       */
      final CountDownLatch firstWedged = new CountDownLatch(1);
      final AtomicBoolean releaseFirst = new AtomicBoolean();
      final AtomicBoolean releaseSecond = new AtomicBoolean();
      final List<CompletableFuture<Object>> behind = new java.util.ArrayList<>();
      final ConcurrentLinkedQueue<String> ranAfterClose = new ConcurrentLinkedQueue<>();
      final AtomicBoolean closed = new AtomicBoolean();

      final SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().inboxCapacity(64)
              .closeTimeout(Duration.ofMillis(300)).build());
      actor.submit(session -> {
        firstWedged.countDown();
        while (!releaseFirst.get()) {
          LockSupport.parkNanos(1_000_000L);
        }
        return null;
      });
      assertThat(firstWedged.await(10, TimeUnit.SECONDS)).isTrue();

      actor.submit(session -> {
        while (!releaseSecond.get()) {
          LockSupport.parkNanos(1_000_000L);
        }
        return null;
      });
      for (int index = 0; index < 5; index++) {
        final int id = index;
        behind.add(actor.submit(session -> {
          if (closed.get()) {
            ranAfterClose.add("command " + id);
          }
          return null;
        }));
      }

      releaseFirst.set(true);          // the worker now pulls the second wedge and stalls there
      Thread.sleep(300);
      actor.close();
      closed.set(true);
      releaseSecond.set(true);
      Thread.sleep(500);

      for (final CompletableFuture<Object> future : behind) {
        // failsWithin + withThrowableOfType, not isCompletedExceptionally after a sleep: these are
        // completed by dispatched threads now, so a fixed sleep is a timing dependency where it
        // used to be a guarantee. The throwable chain is what makes this able to see a strand --
        // failsWithin alone treats a future that never completes as a pass.
        assertThat(future)
            .describedAs("every command behind the wedge must be completed by close")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
      }
      assertThat(ranAfterClose)
          .describedAs("and none of them may run after close returned").isEmpty();
    }

    @Test
    @DisplayName("close from inside a command is refused rather than quietly doing nothing")
    void closeFromTheWorkerIsRefused() throws Exception {
      // join() on self returns immediately, so the old behaviour was to interrupt itself, wait out
      // both timeouts, and report a shutdown failure that had not happened -- while the worker's
      // own finally closed the session anyway. A close() that returns having closed nothing is the
      // same class of untruth as the defect this class was redesigned to fix.
      final SessionActor actor =
          SessionActor.start(rules(), streaming(), ActorOptions.defaults());
      try {
        // Not try-with-resources: -Werror rejects an explicit close() on a managed resource, and
        // calling close() from inside the command is the whole point of this test.
        assertThat(actor.submit(session -> {
          actor.close();
          return "unreachable";
        }))
            .describedAs("close() from a command must throw, not degrade")
            .failsWithin(Duration.ofSeconds(10))
            .withThrowableOfType(ExecutionException.class);
      } finally {
        actor.close();
      }
    }

    @Test
    @DisplayName("halt stops accepting immediately, as its documentation claims")
    void haltStopsAcceptingAtOnce() throws Exception {
      // The Javadoc said work stopped being accepted "at the same moment" while halt() only halted
      // the session -- so for up to a tick afterwards submissions were queued, applied, and
      // reported success to their callers on a session that fires nothing and closes milliseconds
      // later. Asserted with no sleep at all: the rejection has to be immediate.
      final AtomicBoolean ran = new AtomicBoolean();
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.defaults())) {
        /*
         * One command first, awaited, so the worker is demonstrably running and parked in its poll
         * before anything is halted. Without it the worker may not have been scheduled at all yet,
         * and it then exits on the loop condition rather than ever seeing the submission below --
         * which makes this test pass whether or not halt() clears `accepting`, i.e. pins nothing.
         */
        actor.submit(session -> "warmup").get(30, TimeUnit.SECONDS);
        // And parked. The future completes when the command is applied, so without this pause the
        // worker may still be inside the fire cycle that follows -- in which case it re-checks the
        // loop condition, sees a halted session and exits, and the submission below is failed by
        // the shutdown drain rather than ever being offered to a running worker. The test then
        // passes whether or not halt() clears `accepting`.
        Thread.sleep(300);

        actor.halt();
        final CompletableFuture<Object> rejected = actor.submit(session -> {
          ran.set(true);
          return "should not run";
        });

        assertThat(rejected)
            .describedAs("a submission straight after halt must be failed, not applied")
            .failsWithin(Duration.ofSeconds(5))
            .withThrowableOfType(ExecutionException.class);
        // The assertion that discriminates. WHO fails the future -- submit's own check, or the
        // worker's shutdown drain -- is a race; whether the command ever executed is not. Without
        // halt() clearing `accepting`, the submission is queued and the worker applies it before
        // noticing the session is halted.
        assertThat(ran)
            .describedAs("nothing may be applied to a session that has been halted").isFalse();
      }
    }

    @Test
    @DisplayName("a caller's blocking continuation cannot stall close or strand the rest")
    void blockingContinuationDoesNotStallClose() throws Exception {
      /*
       * §5.4 requires close() to be bounded AND to fail what it drains. Completing the drained
       * futures inline gave up the first to keep the second: CompletableFuture runs dependent
       * stages on the completing thread, so one caller's `whenComplete` -- an ordinary reactive
       * handoff, not the re-entrant submit that submit() already warns about -- stopped the drain
       * dead. Everything after it was unreachable, having already left the queue, and close()
       * blocked inside that caller's code: measured at 1510ms against a 600ms bound.
       *
       * EVERY WAIT HERE IS SELF-LIMITING, and that is not incidental. An earlier version wedged the
       * worker on an unbounded spin, so when the assertion failed the spinning virtual thread kept
       * the test JVM alive and the whole build hung instead of reporting a failure -- twice. A test
       * for a bounded-shutdown contract must not be able to hang; a broken implementation has to
       * come back as a red test.
       */
      final CountDownLatch wedged = new CountDownLatch(1);
      final CountDownLatch releaseWorker = new CountDownLatch(1);
      final CountDownLatch releaseStage = new CountDownLatch(1);
      final List<CompletableFuture<Object>> queued = new java.util.ArrayList<>();

      // Not try-with-resources: -Werror rejects an explicit close() on a managed resource, and
      // timing that close() is the whole test.
      final SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().inboxCapacity(64)
              .closeTimeout(Duration.ofMillis(300)).build());
      try {
        actor.submit(session -> {
          wedged.countDown();
          /*
           * Survives interruption but is still bounded, and it has to be both. Interruptible (an
           * await) and close()'s escalation frees the worker, so close() never reaches the drain
           * and the test cannot see the defect at all. Unbounded and a failed assertion leaves this
           * spinning forever on a virtual thread, so the build hangs instead of going red.
           */
          final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
          while (releaseWorker.getCount() > 0 && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
          }
          return null;
        });
        assertThat(wedged.await(10, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 20; index++) {
          queued.add(actor.submit(session -> null));
        }
        queued.getFirst().whenComplete((value, failure) -> {
          try {
            // Bounded: if close() completes this inline and then waits on it, the wait ends and the
            // assertion below reports a slow close rather than the build never finishing.
            releaseStage.await(15, TimeUnit.SECONDS);
          } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });

        final long startedAt = System.nanoTime();
        actor.close();
        final Duration closeTook = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(closeTook)
            .describedAs("close must not wait on a caller's continuation")
            .isLessThan(Duration.ofSeconds(3));
        // Asserted BEFORE the strand is released, or the defect is undone before anything looks at
        // it. Dispatching the whole drain to ONE thread keeps close() bounded and strands the rest;
        // this loop is what tells the two apart.
        for (final CompletableFuture<Object> future : queued) {
          assertThat(future)
              .describedAs("and every queued future must still be completed")
              .failsWithin(Duration.ofSeconds(10))
              .withThrowableOfType(ExecutionException.class);
        }
        releaseStage.countDown();
        releaseWorker.countDown();
      } finally {
        releaseStage.countDown();
        releaseWorker.countDown();
      }
    }

    @Test
    @DisplayName("no future is stranded when the worker stops on its own, under load")
    void nothingIsStrandedWhenTheWorkerStopsByItself() throws Exception {
      /*
       * This pins an ORDERING, and it is the only test that can. Inside run()'s finally,
       * `accepting.set(false)` must come BEFORE `failRemaining(...)`: that is what makes submit()'s
       * post-offer recheck close the check-then-enqueue window, because an offer landing after the
       * drain is then guaranteed to read false and withdraw itself. Swapping those two lines --
       * "fail the queue, then stop accepting", the most natural refactor imaginable -- reopens a
       * stranding path that every other test in this file passes straight through.
       *
       * It is a stress detector rather than a deterministic one -- the window between the drain
       * and the flag is nanoseconds wide -- so it earns its place as the file's general "no future
       * is ever stranded under load" regression, and catches the ordering swap only sometimes. The
       * ordering itself is called out at the finally, which is the durable protection.
       *
       * It has to be an INTERNAL exit to reach that finally with `accepting` still true, since
       * halt() and close() both clear it themselves now. A session failed by §4.6's RETHROW policy
       * is that exit. Producers spam submit throughout, the inbox is large enough that the drain
       * loop is a real window, and every future ever handed out is checked.
       */
      final CompiledRuleSet exploding = RuleCompiler.compile(List.of(Rules.rule("boom")
          .noLoop()
          .when("o", "Order", pattern -> pattern.gt("total", 0))
          .then(actions -> actions.callFunction("explode"))
          .build()));
      final SessionOptions options = SessionOptions.builder()
          .matching(MatchingStrategy.RETE)
          .function("explode", args -> {
            throw new IllegalStateException("blew up");
          }).build();

      for (int round = 0; round < 6; round++) {
        final List<CompletableFuture<Object>> issued =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        try (SessionActor actor = SessionActor.start(exploding, options,
            ActorOptions.builder().inboxCapacity(256)
                .offerTimeout(Duration.ofMillis(200)).build())) {
          try (ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int producer = 0; producer < 6; producer++) {
              producers.submit(() -> {
                // Until the actor dies, not a fixed count: offers have to keep landing throughout
                // the shutdown drain, which is the window being probed. A fixed count finishes
                // early and mostly misses it.
                //
                // The ceiling is a safety valve, not part of the probe. This loop retains a future
                // per iteration and exits only on an exit the actor performs for itself, so a
                // liveness regression in the worker -- the fire loop starving, which is what
                // sustainedSubmissionCannotStarveTheFireLoop now guards -- does not surface here as
                // a failure. It surfaces as this list growing at fourteen thousand futures a second
                // until the heap is gone, twelve minutes later, with no test named. Stopping lets
                // the assertion below run and say something.
                while (actor.running() && issued.size() < STRANDING_PROBE_CEILING) {
                  issued.add(actor.submit(session -> {
                    session.insert("Order", Facts.obj("total", 10));
                    return null;
                  }));
                }
                return null;
              });
            }
          }
        }

        final List<CompletableFuture<Object>> stranded = new java.util.ArrayList<>();
        for (final CompletableFuture<Object> future : issued) {
          try {
            future.get(10, TimeUnit.SECONDS);
          } catch (final TimeoutException timedOut) {
            stranded.add(future);
          } catch (final Exception expected) {
            // Applied-then-failed, or rejected: both are completions, which is all this asserts.
          }
        }
        assertThat(stranded)
            .describedAs("round %d: every future must be completed by someone", round)
            .isEmpty();
      }
    }

    @Test
    @DisplayName("producers that never stop submitting cannot starve the fire loop")
    void sustainedSubmissionCannotStarveTheFireLoop() throws Exception {
      /*
       * The fourth hazard, found by the third one hanging. applyBatch drained "until the inbox is
       * momentarily empty", and six producers submitting in a loop never leave it momentarily
       * empty: the worker applied 41,925 commands and reached fireOnce ONCE in three seconds.
       *
       * Both consequences are silent. A streaming session accumulates facts and fires nothing --
       * and maxFacts (§4.7) cannot catch it, because that bound is checked inside a fire call that
       * never happens. And run()'s loop condition re-reads session.failed() only after a fire, so
       * an actor whose session died on a rule action reports running() green forever. That second
       * one is what hung nothingIsStrandedWhenTheWorkerStopsByItself above: it waits for exactly
       * that internal exit, so its producers looped until the heap was gone rather than failing.
       *
       * Asserted as a ratio rather than a count, because the bound is one inbox-full per fire
       * cycle and the absolute numbers are a scheduling detail. Halved as slack for the partial
       * batches at either end. Before the bound this was 1, which no slack reaches.
       */
      final AtomicInteger fires = new AtomicInteger();
      final AtomicInteger applied = new AtomicInteger();
      final AtomicBoolean keepGoing = new AtomicBoolean(true);
      final int inboxCapacity = 256;

      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder()
              .inboxCapacity(inboxCapacity)
              .offerTimeout(Duration.ofMillis(200))
              .onFire(result -> fires.incrementAndGet())
              .build())) {
        try (ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor()) {
          for (int producer = 0; producer < 6; producer++) {
            producers.submit(() -> {
              while (keepGoing.get() && actor.running()) {
                // The future is deliberately dropped. Retaining one per iteration is what turned
                // the starvation into an out-of-memory error rather than a failed assertion.
                actor.submit(session -> {
                  session.insert("Order", Facts.obj("id", applied.incrementAndGet(), "total", 10));
                  return null;
                });
              }
              return null;
            });
          }
          Thread.sleep(2_000);
          keepGoing.set(false);
        }
      }

      assertThat(fires.get())
          .describedAs("applied %d commands in one fire cycle's worth of batches",
              applied.get())
          .isGreaterThan(applied.get() / (2 * inboxCapacity));
    }

    @Test
    @DisplayName("submitting after close is rejected rather than silently accepted")
    void submitAfterCloseIsRejected() {
      final SessionActor actor =
          SessionActor.start(rules(), streaming(), ActorOptions.defaults());
      actor.close();

      assertThat(actor.submit(session -> null))
          .describedAs("a closed actor must not accept work it will never run")
          .isCompletedExceptionally();
      assertThat(actor.running()).isFalse();
    }
  }

  @Nested
  @DisplayName("firing until halt is what the actor does, not something you call")
  class FiringUntilHalt {

    @Test
    @DisplayName("results arrive without anyone asking for them")
    void firesOnItsOwnSchedule() throws Exception {
      // §5.4 keeps fireUntilHalt off RuleSession because a blocking fire loop plus inserts from
      // another thread is a data race. Here the loop IS the worker: the caller submits facts and
      // fire results arrive on the sink, with no fire call anywhere in the test.
      final ConcurrentLinkedQueue<FireResult> fired = new ConcurrentLinkedQueue<>();
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().onFire(fired::add).build())) {
        for (int order = 0; order < 5; order++) {
          final int id = order;
          actor.submit(session -> {
            session.insert("Order", Facts.obj("id", id, "total", 10));
            return null;
          }).get(30, TimeUnit.SECONDS);
        }

        // Waits on the sink rather than on the submissions, for the reason submit() documents: a
        // command's future completes when the command is applied, and the fire cycle follows. The
        // first version asserted immediately after the last get() and passed in isolation while
        // failing under parallel load -- a race, not a flake.
        assertThat(awaitFirings(fired, 5))
            .describedAs("five orders, one rule, noLoop").isEqualTo(5);
      }
    }

    @Test
    @DisplayName("halt stops the firing and the actor stops on its own")
    void haltStopsTheLoop() throws Exception {
      final ConcurrentLinkedQueue<FireResult> fired = new ConcurrentLinkedQueue<>();
      try (SessionActor actor = SessionActor.start(rules(), streaming(),
          ActorOptions.builder().onFire(fired::add).build())) {
        actor.submit(session -> {
          session.insert("Order", Facts.obj("id", 1, "total", 10));
          return null;
        }).get(30, TimeUnit.SECONDS);

        // Waiting for the SINK, not the future. A submission completes when the command has been
        // applied; the fire cycle it causes happens afterwards, once the worker has drained
        // whatever else was waiting. Reading fired.size() straight after the get() finds nothing --
        // which is what the first version of this test did, and what a caller would do.
        for (int attempt = 0; attempt < 100 && fired.isEmpty(); attempt++) {
          Thread.sleep(50);
        }
        final int before = fired.size();

        actor.halt();

        // halt() is terminal on a session (§4.7), so the worker finds it halted and exits rather
        // than spinning. Waiting on the thread is the assertion: an actor that kept looping would
        // never satisfy it.
        for (int attempt = 0; attempt < 100 && actor.running(); attempt++) {
          Thread.sleep(50);
        }
        assertThat(actor.running())
            .describedAs("a halted session gives the worker nothing further to do").isFalse();
        assertThat(before).isPositive();
      }
    }
  }
}
