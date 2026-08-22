package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.agenda.Agenda;
import com.codeheadsystems.rules.agenda.RefractionMemory;
import com.codeheadsystems.rules.fact.DefaultWorkingMemory;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.fact.WorkingMemoryObserver;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.listener.SuppressReason;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.match.ActivationKey;
import com.codeheadsystems.rules.naive.NaiveAgenda;
import com.codeheadsystems.rules.network.NetworkAgenda;
import com.codeheadsystems.rules.network.SessionMemories;
import com.codeheadsystems.rules.rete.ReteAgenda;
import com.codeheadsystems.rules.rhs.RhsErrorHandler;
import com.codeheadsystems.rules.rhs.RhsExecutor;
import com.codeheadsystems.rules.rhs.RhsResult;
import com.codeheadsystems.rules.rule.ActionDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * The Phase 0 session: working memory, the naive agenda, refraction, and §4.7's firing loop.
 *
 * <p>Single-writer, and cheap to create: allocate the memories and go. There is no classloading, no
 * packaging to parse and no per-session network rebuild, which is the direct fix for "the engine
 * feels heavy" when that complaint comes from session-creation cost.
 */
public final class DefaultRuleSession implements RuleSession {

  private final CompiledRuleSet ruleSet;
  private final SessionOptions options;
  private final UUID sessionId = SessionIds.newSessionId();
  private final RefractionMemory refraction = new RefractionMemory();
  private final DefaultWorkingMemory workingMemory;
  private final SessionMemories memories;
  private final Agenda agenda;
  private final EventSink events;
  private final RhsExecutor rhs;

  /** Null when no eviction policy is configured, which is the default and the common case. */
  private final SessionEvictor evictor;

  private volatile boolean halted;

  /**
   * Volatile to match {@code halted}, because {@link #failed()} is published on {@code RuleSession}
   * and a published accessor invites a read from a monitoring thread. Every shipped caller is the
   * session's own owner, which also does the write, so this buys nothing today -- and costs nothing
   * either, on a field written once per session at most.
   */
  private volatile boolean failed;
  private boolean closed;

  /**
   * Set for the duration of one firing, so eviction cannot run inside it.
   *
   * <p>§4.4's quiescence rule is that the policy is consulted between operations and never during
   * one, and the two consultation points are chosen so that holds. This makes it hold rather than
   * happen to hold. A listener that captured this session and calls {@code insert} on it reaches
   * the hook in that method from inside a firing, which is precisely the case the consultation
   * points were placed to avoid. No shipped SPI hands a listener the session, so a caller has to go
   * out of their way; this makes going out of their way safe.
   *
   * <p><strong>The whole firing, not just the right-hand side</strong>, and the difference is a
   * window that was open when this flag was first written to cover {@code rhs.execute} alone. An
   * activation is <em>consumed</em> by {@code nextToFire()} before {@code onBeforeFire} is
   * dispatched, so a listener inserting from there evicts under an activation that is already
   * selected and refracted. The two symptoms differ, which is worth knowing when one of them turns
   * up: an eviction during the commit is silent, because {@code RhsExecutor}'s staged update
   * returns quietly when its fact has gone, while one before the right-hand side is loud, because
   * {@code setField} throws at staging and the default error policy then fails the session on input
   * that was valid.
   */
  private boolean firing;

  /**
   * Creates a session over a compiled rule set.
   *
   * @param ruleSet the shared, immutable rule set
   * @param options the session configuration
   */
  public DefaultRuleSession(final CompiledRuleSet ruleSet, final SessionOptions options) {
    this.ruleSet = ruleSet;
    this.options = options;
    this.workingMemory =
        new DefaultWorkingMemory(ruleSet.testedPaths(), ruleSet.factSchemas(),
            new Observer(), options.strict());
    this.memories = new SessionMemories(ruleSet.network());
    this.agenda = switch (options.matching()) {
      case NAIVE -> new NaiveAgenda(ruleSet.rules(), workingMemory, refraction,
          options.conflictResolution(), options.listeners(), options.strict());
      case NETWORK -> new NetworkAgenda(ruleSet.rules(), ruleSet.network(), memories, workingMemory,
          refraction, options.conflictResolution(), options.listeners(), options.strict());
      case RETE -> new ReteAgenda(ruleSet.rules(), ruleSet.network(), memories, workingMemory,
          refraction, options.conflictResolution(), options.listeners(), options.strict());
    };
    // A sink the caller did not supply is resolved HERE, and the default is stateless. Holding a
    // collecting sink in SessionOptions put one unsynchronised ArrayList behind every session built
    // from the same options -- the natural way to use them, and exactly the across-session
    // parallelism §5.2 makes the primary concurrency primitive. §7.1 states the opposite as a
    // design property: nothing reachable from options is shared mutable state across sessions.
    this.events = options.events().orElseGet(EventSink::discarding);
    this.rhs = new RhsExecutor(workingMemory, options.functions(), events,
        options.listeners(), sessionId, ruleSet.version(), options.dryRun());
    this.evictor = options.eviction()
        .map(policy -> new SessionEvictor(policy, workingMemory, options.listeners(),
            options.strict()))
        .orElse(null);
  }

  @Override
  public UUID sessionId() {
    return sessionId;
  }

  /**
   * {@inheritDoc}
   *
   * <p>An insert is one of §4.4's two quiescence points, so a configured eviction policy is
   * consulted here, after the fact has landed. The handle returned is still the caller's -- a
   * policy that immediately evicts what was just inserted is a policy with a cap of nothing, not a
   * case to defend against -- and every other fact the eviction removed is gone by the time this
   * returns, listeners notified.
   */
  @Override
  public FactHandle insert(final String type, final JsonNode payload) {
    requireUsable();
    final FactHandle handle = workingMemory.insert(type, payload);
    evictIfNeeded();
    return handle;
  }

  @Override
  public FactHandle insertOwned(final String type, final JsonNode payload) {
    requireUsable();
    final FactHandle handle = workingMemory.insertOwned(type, payload);
    evictIfNeeded();
    return handle;
  }

  @Override
  public void update(final FactHandle handle, final JsonNode newPayload) {
    requireUsable();
    workingMemory.update(handle, newPayload);
  }

  @Override
  public void updateOwned(final FactHandle handle, final JsonNode newPayload) {
    requireUsable();
    workingMemory.updateOwned(handle, newPayload);
  }

  @Override
  public void retract(final FactHandle handle) {
    requireUsable();
    workingMemory.retract(handle);
  }

  @Override
  public Optional<Fact> get(final FactHandle handle) {
    return workingMemory.get(handle);
  }

  @Override
  public Optional<Long> firedAt(final ActivationKey key) {
    return refraction.firedAt(key);
  }

  @Override
  public WorkingMemory workingMemory() {
    return workingMemory;
  }

  @Override
  public FireResult fireAllRules() {
    return fireAllRules(options.limits());
  }

  /**
   * {@inheritDoc}
   *
   * <p>Four orderings in this loop are easy to get wrong, and each is a separate test:
   *
   * <ul>
   *   <li><strong>{@code halted()} is checked before consuming an activation.</strong> Checking
   *       after selection silently discards the selected activation, so a halted-and-resumed
   *       session skips exactly one firing, non-deterministically.
   *   <li><strong>Termination beats the limit check.</strong> Testing the cycle count first throws
   *       on a run that legitimately completed in exactly {@code maxCycles} firings -- a spurious
   *       failure at exactly the boundary a well-tuned limit sits on. The tell that the other
   *       ordering is wrong is that the exception has no offending activation to report.
   *   <li><strong>The limit check inspects with {@code peek()}, never {@code nextToFire()}.</strong>
   *       Selecting an activation and then throwing without executing it destroys work the session
   *       can never recover. This is easy to reintroduce, because "get the next activation, then
   *       validate" reads naturally.
   *   <li><strong>The exception names the culprit and carries the work.</strong> A batch that fired
   *       9,999 rules must not lose all of it on the 10,000th.
   * </ul>
   *
   * <p>A limit breach does <em>not</em> mark the session failed, unlike a rethrown right-hand-side
   * error. Nothing went wrong with the data or the rules -- a bound was reached -- so re-firing
   * with a higher limit is a reasonable thing for a caller to do, and the activation the exception
   * names is still sitting eligible because it was peeked rather than selected.
   */
  @Override
  public FireResult fireAllRules(final FireOptions limits) {
    requireUsable();
    final long startedAt = System.nanoTime();
    final List<FireRecord> fired = new ArrayList<>();
    TerminationReason why = TerminationReason.DRAINED;

    while (true) {
      if (halted) {
        why = TerminationReason.HALTED;
        break;
      }
      /*
       * §4.4's second quiescence point, and the one that catches derived growth: a right-hand side
       * inserts through RhsExecutor's staging protocol rather than through this class, so the hook
       * on insert above never sees those facts. Here rather than inside the staging protocol
       * because a cycle boundary is the only place in a fire loop where nothing is half-applied --
       * §4.6 stages every effect and then commits it, and an eviction landing in between could
       * retract a fact the firing activation binds.
       *
       * Before the maxFacts check below, so a configured policy prevents that breach rather than
       * racing it.
       */
      evictIfNeeded();
      if (agenda.isEmpty()) {
        break;
      }
      if (fired.size() >= limits.maxCycles()) {
        throw new RuleEngineLimitExceeded.CycleLimit(
            limits.maxCycles(), agenda.peek(), partialResult(fired, startedAt));
      }
      if (workingMemory.size() > limits.maxFacts()) {
        throw new RuleEngineLimitExceeded.FactLimit(
            limits.maxFacts(), workingMemory.size(), partialResult(fired, startedAt));
      }
      final Optional<Activation> next = agenda.nextToFire();
      if (next.isEmpty()) {
        // Lost a race with halt(), or the last candidate was refracted. Nothing was consumed.
        break;
      }
      final Fired outcome = fire(next.get());
      fired.add(outcome.record());
      if (outcome.decision() == RhsErrorHandler.Decision.ABORT_SESSION) {
        failed = true;
        why = TerminationReason.RHS_ERROR;
        break;
      }
    }
    return result(fired, why, agenda.size(), startedAt);
  }

  @Override
  public SessionStats stats() {
    return new SessionStats(workingMemory.size(), refraction.size(),
        agenda.materialisedMatchCount(), agenda.materialisedHandleCount(),
        agenda.pendingMatchCount(),
        evictor == null ? 0L : evictor.evictedCount(),
        evictor == null ? Map.of() : evictor.evictedByType());
  }

  @Override
  public boolean failed() {
    return failed;
  }

  @Override
  public boolean halted() {
    return halted;
  }

  @Override
  public void halt() {
    this.halted = true;
  }

  @Override
  public void close() {
    closed = true;
  }

  /**
   * Runs the eviction policy, if one is configured.
   *
   * <p>A null check on the overwhelming majority of sessions, which configure none.
   */
  private void evictIfNeeded() {
    if (evictor != null && !firing) {
      evictor.evictIfNeeded();
    }
  }

  /**
   * Executes one activation's right-hand side and turns the outcome into a record.
   *
   * @param activation the match to fire
   * @return the record, and what the error policy decided if it failed
   */
  private Fired fire(final Activation activation) {
    firing = true;
    try {
      return fireSelected(activation);
    } finally {
      firing = false;
    }
  }

  /**
   * The body of one firing, run with eviction suppressed.
   *
   * @param activation the match to fire
   * @return the record, and what the error policy decided if it failed
   */
  private Fired fireSelected(final Activation activation) {
    final long startedAt = System.nanoTime();
    for (final RuleEngineListener listener : options.listeners()) {
      listener.onBeforeFire(activation);
    }
    final List<ActivationKey> runnersUp = options.collectRunnersUp()
        ? agenda.rankEligible(options.runnersUpLimit()).stream().map(Activation::key).toList()
        : List.of();

    if (activation.rule().noLoop()) {
      refraction.guardNoLoop(activation.key());
    }
    final RhsResult outcome;
    try {
      outcome = rhs.execute(activation);
    } finally {
      refraction.guardNoLoop(null);
    }

    RhsErrorHandler.Decision decision = null;
    Optional<ActionDefinition> failedAction = Optional.empty();
    if (outcome.failure().isPresent()) {
      final RhsResult.Failure failure = outcome.failure().get();
      failedAction = Optional.of(failure.action());
      for (final RuleEngineListener listener : options.listeners()) {
        listener.onRhsError(activation, failure.action(), failure.cause());
      }
      decision = options.onRhsError()
          .onRhsFailure(activation, failure.action(), failure.cause());
    }

    // The record is built and published BEFORE any rethrow. §4.6 requires the partially-executed
    // activation to be recorded in the trace, and a commit-phase failure leaves working-memory
    // effects applied -- so a rethrow that skipped this would destroy the only record that the
    // partial state exists. Listeners are the trace (§7.1), and they are how a caller recovers this
    // firing and every earlier one, since the contract is that the ORIGINAL exception propagates
    // and it therefore cannot carry a partial result the way a limit breach does.
    final FireRecord record = new FireRecord(
        activation.key(), activation.recency(), activation.rule().salience(), runnersUp,
        outcome.effects(), outcome.emitted(), failedAction, outcome.notRun(),
        Duration.ofNanos(System.nanoTime() - startedAt));
    for (final RuleEngineListener listener : options.listeners()) {
      listener.onAfterFire(record);
    }

    if (decision == RhsErrorHandler.Decision.RETHROW) {
      failed = true;
      throw rethrow(outcome.failure().orElseThrow().cause());
    }
    return new Fired(record, decision);
  }

  /**
   * Rethrows an RHS failure to the caller of the fire call.
   *
   * @param cause the exception the action threw
   * @return never returns; declared so callers can write {@code throw rethrow(cause)}
   */
  private static RuntimeException rethrow(final Throwable cause) {
    if (cause instanceof RuntimeException runtime) {
      return runtime;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    return new IllegalStateException("rule action failed", cause);
  }

  /**
   * Builds the result carried by a limit breach.
   *
   * @param fired what completed
   * @param startedAt the fire call's start, in nanoseconds
   * @return a partial result whose termination reason is a limit breach
   */
  private FireResult partialResult(final List<FireRecord> fired, final long startedAt) {
    return result(fired, TerminationReason.LIMIT_EXCEEDED, agenda.size(), startedAt);
  }

  /**
   * Assembles a fire result.
   *
   * <p><strong>Emitted events come from the firing records, not from the sink.</strong> §4.6
   * describes the default collecting sink's contents as what a result returns, and for the default
   * that is the same list -- but sourcing it from the records instead means a caller who installs a
   * custom sink still gets an accurate {@code emitted()} rather than an empty one, and a dry run
   * reports what it <em>would</em> have emitted without anything having been delivered.
   *
   * @param fired what fired
   * @param why why firing stopped
   * @param residual how many activations remain eligible
   * @param startedAt the fire call's start, in nanoseconds
   * @return the result
   */
  private FireResult result(final List<FireRecord> fired, final TerminationReason why,
      final int residual, final long startedAt) {
    final List<EmittedEvent> emitted = fired.stream()
        .flatMap(record -> record.emitted().stream())
        .toList();
    return new FireResult(fired, emitted, why, residual, ruleSet.version(),
        Duration.ofNanos(System.nanoTime() - startedAt));
  }

  /**
   * Rejects use of a session that has failed or been closed.
   *
   * <p>After a rethrown RHS failure the session is marked failed and subsequent operations throw:
   * silent continuation after an unexpected exception is how a rule engine produces confidently
   * wrong output.
   */
  private void requireUsable() {
    if (closed) {
      throw new IllegalStateException("session is closed");
    }
    if (failed) {
      throw new IllegalStateException(
          "session failed on a rule action and cannot be used further");
    }
  }

  /**
   * One firing's outcome.
   *
   * @param record what it did
   * @param decision what the error policy decided, or null when it succeeded
   */
  private record Fired(FireRecord record, RhsErrorHandler.Decision decision) {}

  /** Wires working-memory changes into the agenda, refraction and the listeners. */
  private final class Observer implements WorkingMemoryObserver {

    @Override
    public void factInserted(final Fact fact) {
      if (evictor != null) {
        // Every insert, not just the caller's: this is where a right-hand side's derived facts and
        // an update's reassert become visible, and the recency order eviction selects over has to
        // account for all three. See SessionEvictor for why the order is kept here rather than in
        // working memory.
        evictor.factInserted(fact);
      }
      ruleSet.network().insert(fact.type(), fact.handle().id(), fact.payload(), memories);
      // After the alpha network, before markDirty: the Rete shape walks the join for this fact and
      // needs the pattern memberships to already include it (§4.3's activate half). The recomputing
      // shapes ignore this call entirely.
      agenda.factInserted(fact);
      agenda.markDirty(fact.type());
      for (final RuleEngineListener listener : options.listeners()) {
        listener.onInsert(fact);
      }
    }

    @Override
    public void factRetracted(final Fact fact) {
      if (evictor != null) {
        evictor.factRetracted(fact);
      }
      // Against the payload the fact had when it was asserted, which is what `fact` carries here.
      // Computing removal keys from anything else leaves orphaned index entries (§3.4.1 step 3).
      ruleSet.network().retract(fact.type(), fact.handle().id(), fact.payload(), memories);
      agenda.factRetracted(fact);
      agenda.markDirty(fact.type());
      for (final RuleEngineListener listener : options.listeners()) {
        listener.onRetract(fact);
      }
    }

    @Override
    public void refractionInvalidatedAll(final FactHandle handle) {
      refraction.invalidateAll(handle.id());
    }

    @Override
    public void refractionInvalidated(final FactHandle handle, final Set<String> ruleIds) {
      for (final ActivationKey suppressed : refraction.invalidateFor(handle.id(), ruleIds)) {
        for (final RuleEngineListener listener : options.listeners()) {
          listener.onActivationSuppressed(suppressed, SuppressReason.NO_LOOP);
        }
      }
    }

    @Override
    public void updatePropagated(final Fact before, final Fact after,
        final Set<JsonPointer> changedTestedPaths) {
      for (final RuleEngineListener listener : options.listeners()) {
        listener.onUpdate(before, after, changedTestedPaths);
      }
    }
  }
}
