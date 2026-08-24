package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.concurrent.ActorOptions;
import com.codeheadsystems.rules.concurrent.SessionActor;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.EmittedEvent;
import com.codeheadsystems.rules.session.FireResult;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.RuleSession;
import com.codeheadsystems.rules.session.SessionStats;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One session, held open, fed the whole feed: the shape for a stream rather than a request.
 *
 * <p>Three things change from {@link PerOrderDemo}, and each is a decision rather than a setting.
 *
 * <p><strong>The matcher.</strong> {@code RETE} materialises joins as facts arrive instead of
 * recomputing them per fire cycle. That is a bad trade for a session that fires once and closes --
 * there is no second cycle to amortise it across -- and the right one here, where the same tuples
 * survive thousands of cycles.
 *
 * <p><strong>The owner.</strong> A session is single-writer, and "fire until told to stop" is a
 * blocking loop, so inserting from a producer thread while that loop runs is a data race and not a
 * clever optimisation. {@link SessionActor} is the answer §5.4 gives: one worker owns the session,
 * producers hand it work through a bounded inbox, and a burst of inserts costs one fire cycle rather
 * than one each. {@code halt()} is the only session method that may be called from another thread.
 *
 * <p><strong>The bound.</strong> A long-lived session grows -- working memory, the node memories and
 * their indexes, the refraction memory, the beta memory -- and every one of those is keyed on
 * handles, so letting go of facts bounds all of them at once. §4.4's {@code SessionOptions.eviction}
 * is the engine's mechanism for that, and <strong>this rule set cannot use it</strong>. The analysis
 * is printed by {@link #evictionAnalysis()} and it is worth doing on paper before writing the
 * config: an evicted fact and an absent fact are indistinguishable, so a cap on a type a rule
 * negates does not cost a firing, it asserts a false conclusion. What bounds this session instead is
 * {@link Ingest#retractOrder(String)} -- the application letting go of an order it knows is
 * finished, which is knowledge the engine does not have.
 */
public final class StreamingDemo {

  /** How long to wait for a fire cycle before deciding the actor is wedged. */
  private static final Duration FIRE_TIMEOUT = Duration.ofSeconds(10);

  private StreamingDemo() {
    throw new AssertionError("static helper");
  }

  /**
   * What one run of the streaming session produced.
   *
   * <p>The counts are returned rather than only printed so that {@code OrderPipelineTest} can assert
   * on them. That is not decoration: the submit-and-await-a-fire pairing below is the kind of thing
   * that fails one run in forty, and a demo whose only output is {@code System.out} has no way to
   * notice.
   *
   * @param emitted every event the session emitted, in firing order
   * @param finalStats the session's counts after the last retract
   */
  public record Outcome(List<EmittedEvent> emitted, SessionStats finalStats) {

    /**
     * Canonical constructor.
     *
     * @param emitted the emitted events
     * @param finalStats the closing counts
     */
    public Outcome {
      emitted = List.copyOf(emitted);
    }
  }

  /**
   * Feeds every event to one long-lived session and prints what fired as it goes.
   *
   * @param rules the compiled rule set
   * @return what the run emitted, and the counts it closed on
   */
  public static Outcome run(final CompiledRuleSet rules) {
    final List<OrderEvent> feed = EventFeed.load();
    final OpsPager pager = new OpsPager();
    /*
     * onFire runs on the worker thread, not this one. A concurrent list rather than an ArrayList
     * because close() is documented to return with the worker still alive if shutdown times out --
     * so "everything is joined by the time I read it" is not a guarantee this code has. Treating an
     * actor callback as if it ran on the submitting thread is the mistake this type exists to
     * prevent.
     */
    final List<EmittedEvent> emitted = new CopyOnWriteArrayList<>();

    /*
     * Released once per fire cycle, which is what makes this demo's trace deterministic. A command's
     * future completes when the command has been APPLIED, not when the cycle it caused has finished
     * -- those are different moments, and a caller who awaits the insert and then reads the sink
     * finds nothing there yet. Awaiting the cycle is also what stops the actor batching the whole
     * feed into one fire, which would be correct and would hide the story: `unpaid-order` concludes
     * when an order arrives, and that conclusion is withdrawn when its payment does. Batched, only
     * the last state is ever visible.
     */
    final Semaphore fired = new Semaphore(0);
    final ActorOptions actorOptions = ActorOptions.builder()
        .onFire(result -> {
          report(result, emitted);
          fired.release();
        })
        .onError(failure -> System.out.printf("  actor error: %s%n", failure))
        .build();

    /*
     * Ingest holds the handles it has issued, so it is session state and must only ever be touched
     * on the worker thread. It is constructed inside the first command for exactly that reason --
     * and every later command reaches it through the same actor, so nothing else ever does.
     */
    final List<Ingest> owner = new ArrayList<>(1);
    final SessionStats finalStats;
    try (SessionActor actor = SessionActor.start(rules,
        OrderRules.options(pager, MatchingStrategy.RETE), actorOptions)) {
      /*
       * EVERY submit in this class goes through submitAndFire, including the one that only builds
       * the Ingest and the ones that only read stats. That is not tidiness: the actor fires after
       * every batch, so a submit whose permit nobody consumes leaves the semaphore one ahead
       * permanently, and from then on each wait returns on the PREVIOUS command's cycle. This demo
       * has had that bug twice. The first time it printed the trace one step late, which is
       * obvious; the second time it left the two stats reads unpaired, and a stats read that
       * returns before its own fire cycle can observe a conclusion that truth maintenance is about
       * to withdraw -- withdrawal happens inside fireAllRules, not inside retract. That failed
       * about one run in forty and reported "1 conclusions held" where the comment below promises
       * zero.
       */
      submitAndFire(actor, fired, session -> owner.add(new Ingest(session)));

      for (final OrderEvent event : feed) {
        System.out.printf("    %s%n", event.type());
        submitAndFire(actor, fired, session -> {
          owner.get(0).apply(event);
          return null;
        });
      }

      final SessionStats before = submitAndFire(actor, fired, RuleSession::stats);
      System.out.printf("  after the feed: %d facts, %d conclusions held, %d matches"
              + " materialised%n",
          before.factCount(), before.concludedFactCount(), before.materialisedMatchCount());

      /*
       * The bound. O-1 is paid, in stock and shipped as far as this application is concerned, so
       * everything about it can go -- and the conclusion rule 2 drew about it goes with it, because
       * its justification is gone rather than because anything here remembered to delete it.
       */
      SessionStats closing = before;
      for (final String finished : List.of("O-1", "O-3")) {
        System.out.printf("    retract everything about %s%n", finished);
        final int retracted =
            submitAndFire(actor, fired, session -> owner.get(0).retractOrder(finished));
        closing = submitAndFire(actor, fired, RuleSession::stats);
        System.out.printf("      let go of %d facts -> %d facts, %d conclusions held%n",
            retracted, closing.factCount(), closing.concludedFactCount());
      }
      finalStats = closing;
      /*
       * O-1 was paid, so rule 2's conclusion about it was already withdrawn and the count does not
       * move. O-3 never was, so retracting it takes the conclusion with it -- nothing here deleted
       * the OrderUnpaid fact, and nothing had to know it existed. Its justification went, so it
       * went.
       */

      System.out.print(evictionAnalysis());
      /*
       * close() signals the worker, which finishes its current cycle, fails anything still queued
       * and closes the session -- on that thread and no other. Two threads on one session is the
       * single rule §5.1 has, and this class exists to keep it.
       */
    }
    System.out.printf("  alertOps was called %d time(s)%n", pager.paged().size());
    return new Outcome(emitted, finalStats);
  }

  /**
   * Submits one command and waits for the fire cycle it causes.
   *
   * <p>The pairing is the contract: one submit, one permit. See the comment at the call site for
   * what breaks when a submit skips it.
   *
   * @param <T> what the command produces
   * @param actor the actor
   * @param fired the permit released once per fire cycle
   * @param command what to do with the session
   * @return whatever the command returned
   */
  private static <T> T submitAndFire(final SessionActor actor, final Semaphore fired,
      final SessionActor.SessionCommand<T> command) {
    final T result = actor.submit(command).join();
    try {
      if (!fired.tryAcquire(FIRE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("the actor did not fire within " + FIRE_TIMEOUT);
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for a fire cycle", interrupted);
    }
    return result;
  }

  /**
   * Why {@code orders.yaml} configures no eviction policy.
   *
   * <p>Written out as text rather than left as a comment because it is the analysis every long-lived
   * session needs and almost nobody does. §4.4 gives four hazards, one per Phase 6 feature, and this
   * rule set manages to hit all four.
   *
   * @return the analysis, ready to print
   */
  public static String evictionAnalysis() {
    return """
          which types could this session cap? (§4.4)
            Payment    no -- rule 2 negates it. An evicted payment is an absent payment, so the
                             rule would conclude that a paid order is unpaid. Not a lost firing:
                             a false one.
            LineItem   no -- rule 3 quantifies over it and rule 4 accumulates it. Eviction only
                             ever removes counterexamples, so a cap does not weaken "every item is
                             in stock", it deletes the requirement -- and it changes rule 4's total
                             without changing anything a reader would look at.
            OrderUnpaid no -- rule 2 concludes it and rule 6 counts it. Evicting a conclusion drops
                             it while its reason still holds, and the rule stays refracted, so it
                             never comes back.
            Order      no -- bound by six of the seven rules and joined to by five. Capping it
                             costs firings rather than truth, which is the mild hazard, but a cap
                             chosen by recency is not a cap chosen by "this order is finished".
            Customer   no -- reference data, and nothing here retracts one either. A cap silently
                             stops joins matching, so this type is bounded by how many customers
                             the business has rather than by anything in this session. That is
                             usually fine and it is worth knowing rather than assuming.
            Discount   YES - the one type that qualifies: rule 4 inserts it, nothing patterns it,
                             negates it, folds it or concludes it. It is also the one type nothing
                             here can let go of -- a rule created it and the application holds no
                             handle -- so in a session that runs for a week it is the type that
                             actually grows. The line is
                             SessionOptions.eviction(EvictionPolicy.perType(Map.of("Discount", n))),
                             and this demo omits it only because it creates exactly one.
          so: one type is cappable, four are bounded by the application retracting what it knows is
              complete, and Customer is bounded by how many customers there are. Not one of the six
              is bounded by the engine on its own.
        """;
  }

  /**
   * Prints one fire cycle and records its events.
   *
   * @param result the cycle's outcome
   * @param emitted where to record the events
   */
  private static void report(final FireResult result, final List<EmittedEvent> emitted) {
    emitted.addAll(result.emitted());
    for (final EmittedEvent event : result.emitted()) {
      System.out.printf("      emit  %-24s %s%n", event.eventType(), event.payload());
    }
  }
}
