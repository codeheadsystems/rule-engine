package com.codeheadsystems.rules.session;

import com.codeheadsystems.rules.evict.EvictionPolicy;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.ConflictResolutionStrategy;
import com.codeheadsystems.rules.match.DefaultConflictResolution;
import com.codeheadsystems.rules.rhs.HostFunction;
import com.codeheadsystems.rules.rhs.RhsErrorHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a session can be configured with (spec §7.5).
 *
 * <p><strong>A builder, not a record, and the reason is Phase 3.</strong> Every deferral in the
 * spec -- the Rete join strategy, differential propagation, a session eviction policy, a wall-clock
 * budget -- lands here as a new option when it arrives. Adding a component to a record breaks every
 * positional construction in every caller's code; adding a method to a builder breaks nothing. That
 * is the two-line change that makes "additive, not a rework" true for the configuration surface as
 * well as for the engine, and it costs nothing to make now.
 *
 * <p>Note what is deliberately <em>not</em> here: a join-strategy selector. v1 has one join strategy
 * and one agenda shape, so a selector would be dead config -- a knob with one position, which
 * readers reasonably assume has two. It arrives with the second shape.
 *
 * <h2>One options object, many sessions</h2>
 *
 * <p><strong>An instance is per configuration, not per session.</strong> It is built once and used
 * to create sessions repeatedly -- {@code RuleBatches.run(rules, inputs, batch, options)} takes
 * exactly one and creates N concurrent sessions from it. <strong>Everything reachable from an
 * options object is therefore shared by every session created from it, and must tolerate concurrent
 * invocation.</strong> The engine does not synchronize dispatch to any of it.
 *
 * <p>This statement lives on the class rather than only on the individual setters because each new
 * thing reachable from here has had to rediscover it, twice at a cost. {@link Builder#events} was
 * once a collecting sink held in the options, which put one unsynchronised {@code ArrayList} behind
 * every session built from them; that one was fixed by resolving the sink per session, and the
 * default is stateless so that not choosing cannot go wrong. Listeners were added afterwards and
 * inherited none of that reasoning, which is how §7.1 came to claim -- wrongly -- that "a listener
 * is never shared mutable state across sessions and nothing on the path synchronizes". See the
 * amendment at §7.1, {@link RuleEngineListener} and {@link HostFunction}.
 *
 * <p>So: whatever is added to this class next is shared too, and says so here already. A caller who
 * wants per-session state wants a per-session options object, which is cheap -- this is a builder
 * over a handful of fields, not something to pool.
 */
public final class SessionOptions {

  /**
   * The system property that turns strict mode on for a whole test run.
   *
   * <p>§7.5 requires the full suite to run under strict mode in CI and forbids strict mode in
   * production. Defaulting {@link Builder#strict(boolean)} from this property is what lets one CI
   * task re-run the existing suite with the contract checks on, instead of every test having to opt
   * in and one forgetting to.
   */
  public static final String STRICT_PROPERTY = "rules.strict";

  /** The default bound on how many losing activations a fire record names. */
  public static final int DEFAULT_RUNNERS_UP_LIMIT = 3;

  private final FireOptions limits;
  private final ConflictResolutionStrategy conflictResolution;
  private final EventSink events;   // null when the caller supplied none
  private final RhsErrorHandler onRhsError;
  private final List<RuleEngineListener> listeners;
  private final Map<String, HostFunction> functions;
  private final boolean strict;
  private final boolean dryRun;
  private final int runnersUpLimit;
  private final MatchingStrategy matching;
  private final EvictionPolicy eviction;   // null when the caller configured none

  private SessionOptions(final Builder builder) {
    this.limits = builder.limits;
    this.conflictResolution = builder.conflictResolution;
    this.events = builder.events;
    this.onRhsError = builder.onRhsError;
    this.listeners = List.copyOf(builder.listeners);
    this.functions = Map.copyOf(builder.functions);
    this.strict = builder.strict;
    this.dryRun = builder.dryRun;
    this.runnersUpLimit = builder.runnersUpLimit;
    this.matching = builder.matching;
    this.eviction = builder.eviction;
  }

  /**
   * A fresh builder carrying every default.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Options with every default.
   *
   * @return the default options
   */
  public static SessionOptions defaults() {
    return builder().build();
  }

  /**
   * The work limits a bare fire call runs under.
   *
   * @return the limits
   */
  public FireOptions limits() {
    return limits;
  }

  /**
   * How ties between eligible activations are broken.
   *
   * @return the strategy
   */
  public ConflictResolutionStrategy conflictResolution() {
    return conflictResolution;
  }

  /**
   * Where emitted events go, if the caller chose.
   *
   * <p>Empty means "the caller did not choose", and a session then falls back to
   * {@link EventSink#discarding()}. That indirection matters: options are meant to be built once
   * and reused across many sessions -- §5.2 makes one virtual thread per session the primary
   * concurrency primitive -- so a <em>stateful</em> default held here would be one unsynchronised
   * list shared by every session built from these options. §7.1 states the opposite as a design
   * property, that nothing reachable from options is shared mutable state across sessions.
   *
   * <p>Emitted events are still reported: {@link FireResult#emitted()} is sourced from the firing
   * records, not from the sink, so the default discards without losing anything.
   *
   * <p>A sink the caller <em>does</em> supply is shared if they share the options. That is their
   * decision to make, and their responsibility to make thread-safe.
   *
   * @return the configured sink, or empty to let each session collect into its own
   */
  public Optional<EventSink> events() {
    return Optional.ofNullable(events);
  }

  /**
   * What happens when a right-hand side throws.
   *
   * @return the handler
   */
  public RhsErrorHandler onRhsError() {
    return onRhsError;
  }

  /**
   * The registered listeners, in registration order.
   *
   * @return the listeners; empty by default
   */
  public List<RuleEngineListener> listeners() {
    return listeners;
  }

  /**
   * The host functions a {@code callFunction} action may dispatch to.
   *
   * @return the registry, keyed by function name
   */
  public Map<String, HostFunction> functions() {
    return functions;
  }

  /**
   * Whether strict-mode contract checks are on (§7.5).
   *
   * <p>Every check strict mode enables detects a violation of a contract the spec states but cannot
   * enforce at compile time. Run the full suite under it in CI, and never enable it in production:
   * several checks are O(payload) or worse per operation. A check cheap enough to leave on always
   * does not belong behind this flag; it is made unconditional instead.
   *
   * @return whether strict mode is on
   */
  public boolean strict() {
    return strict;
  }

  /**
   * Whether the session matches and resolves conflicts but executes no right-hand side.
   *
   * <p>It answers "what <em>would</em> fire, in what order, on these facts" -- what an author needs
   * before shipping a rule change, and the mechanism behind a run-both-and-compare cutover.
   *
   * @return whether this is a dry run
   */
  public boolean dryRun() {
    return dryRun;
  }

  /**
   * How many losing activations a fire record may name.
   *
   * @return the bound; zero disables the feature
   */
  public int runnersUpLimit() {
    return runnersUpLimit;
  }

  /**
   * Which matcher this session uses.
   *
   * @return the strategy; {@link MatchingStrategy#NETWORK} unless overridden
   */
  public MatchingStrategy matching() {
    return matching;
  }

  /**
   * How this session bounds its own memory, if the caller chose (§4.4).
   *
   * <p>Empty is the default and means no eviction, which is right for the one-shot and batch
   * sessions §11.1 targets: they are discarded before anything accumulates. It is wrong for a
   * long-lived streaming session, where §4.4 names eviction as the one mechanism that bounds every
   * structure a session grows -- they are all keyed on handles, and a retract removes a handle from
   * all of them.
   *
   * <p>Empty rather than a do-nothing policy, so a session that configured none pays a null check
   * per insert rather than a virtual call returning an empty list.
   *
   * <p>A policy the caller supplies is shared if they share the options, like everything else
   * reachable from here. The built-in policies are immutable and stateless, so sharing them is
   * safe; a policy that accumulates state across sessions is the caller's decision to make and to
   * make thread-safe.
   *
   * @return the configured policy, or empty to evict nothing
   */
  public Optional<EvictionPolicy> eviction() {
    return Optional.ofNullable(eviction);
  }

  /**
   * A builder pre-populated with this configuration.
   *
   * <p>For "the same session setup, one thing different", which is otherwise unexpressible once the
   * options are built: every field is final and there is no copy constructor. The case that
   * prompted it is running one configuration under a second matcher --
   * {@code options.toBuilder().matching(RETE).build()} -- which is what lets the determinism
   * harness cover the streaming shape without callers having to pass their setup twice.
   *
   * <p>The event sink is carried across only if one was supplied, and the guard is not optional --
   * {@code Builder#events} rejects null, so copying unconditionally would fail on the common path.
   * It also keeps §7.1's property that an unset sink is resolved per session rather than shared.
   * That second reason buys nothing today, because the resolved default is stateless; it is there
   * for the day the default is stateful again, which is what it was when
   * {@code DefaultRuleSession} acquired the comment about one {@code ArrayList} behind every
   * session.
   *
   * @return a builder holding this configuration
   */
  public Builder toBuilder() {
    final Builder builder = builder()
        .limits(limits)
        .conflictResolution(conflictResolution)
        .onRhsError(onRhsError)
        .strict(strict)
        .dryRun(dryRun)
        .runnersUpLimit(runnersUpLimit)
        .matching(matching);
    if (events != null) {
      builder.events(events);
    }
    if (eviction != null) {
      builder.eviction(eviction);
    }
    listeners.forEach(builder::listener);
    functions.forEach(builder::function);
    return builder;
  }

  /**
   * Whether the runners-up list should be populated at all.
   *
   * <p>Computing it means ranking the eligible activations rather than selecting the maximum, so it
   * is paid for only when something will read it.
   *
   * @return whether to rank losers
   */
  public boolean collectRunnersUp() {
    return runnersUpLimit > 0 && (dryRun || !listeners.isEmpty());
  }

  /** Builds {@link SessionOptions}. */
  public static final class Builder {

    private FireOptions limits = FireOptions.defaults();
    private ConflictResolutionStrategy conflictResolution = new DefaultConflictResolution();
    private EventSink events;
    private RhsErrorHandler onRhsError = RhsErrorHandler.rethrow();
    private final List<RuleEngineListener> listeners = new ArrayList<>();
    private final Map<String, HostFunction> functions = new LinkedHashMap<>();
    private boolean strict = Boolean.getBoolean(STRICT_PROPERTY);
    private boolean dryRun;
    private int runnersUpLimit = DEFAULT_RUNNERS_UP_LIMIT;
    private MatchingStrategy matching = MatchingStrategy.NETWORK;
    private EvictionPolicy eviction;

    /** Creates a builder carrying the defaults. */
    private Builder() {
      // Defaults are assigned inline.
    }

    /**
     * Sets the work limits for bare fire calls.
     *
     * @param value the limits
     * @return this builder
     */
    public Builder limits(final FireOptions value) {
      this.limits = Objects.requireNonNull(value, "limits");
      return this;
    }

    /**
     * Sets the conflict-resolution strategy.
     *
     * @param value the strategy. It must be a total order consistent with activation equality;
     *     strict mode asserts both
     * @return this builder
     */
    public Builder conflictResolution(final ConflictResolutionStrategy value) {
      this.conflictResolution = Objects.requireNonNull(value, "conflictResolution");
      return this;
    }

    /**
     * Sets the event sink, replacing the per-session collecting default.
     *
     * <p>If these options are reused to create more than one session, this instance is shared by all
     * of them and must tolerate concurrent invocation -- see the class documentation. That is the
     * caller's decision to make; the default is resolved per session and is stateless, precisely so
     * that not making it cannot go wrong.
     *
     * @param value the sink
     * @return this builder
     */
    public Builder events(final EventSink value) {
      this.events = Objects.requireNonNull(value, "events");
      return this;
    }

    /**
     * Sets the right-hand-side error policy.
     *
     * @param value the handler
     * @return this builder
     */
    public Builder onRhsError(final RhsErrorHandler value) {
      this.onRhsError = Objects.requireNonNull(value, "onRhsError");
      return this;
    }

    /**
     * Registers a listener.
     *
     * <p>If these options are reused to create more than one session, this instance is shared by all
     * of them and must tolerate concurrent invocation -- see the class documentation. That is the
     * caller's decision, and it is the natural one to make when collecting a trace across a batch
     * run. {@code TracingListener} is safe to share; note that sharing also interleaves sessions
     * into one buffer, which its own documentation warns costs you loop diagnosis.
     *
     * @param value the listener
     * @return this builder
     */
    public Builder listener(final RuleEngineListener value) {
      this.listeners.add(Objects.requireNonNull(value, "listener"));
      return this;
    }

    /**
     * Registers a host function a {@code callFunction} action may dispatch to.
     *
     * <p>If these options are reused to create more than one session, this instance is shared by all
     * of them and must tolerate concurrent invocation -- see the class documentation. A stateless
     * handler, which is most of them, needs nothing.
     *
     * @param name the name rules refer to it by
     * @param function the implementation
     * @return this builder
     */
    public Builder function(final String name, final HostFunction function) {
      this.functions.put(
          Objects.requireNonNull(name, "name"), Objects.requireNonNull(function, "function"));
      return this;
    }

    /**
     * Turns strict-mode contract checks on or off, overriding the
     * {@value #STRICT_PROPERTY} system property.
     *
     * @param value whether to enable strict mode
     * @return this builder
     */
    public Builder strict(final boolean value) {
      this.strict = value;
      return this;
    }

    /**
     * Makes this a dry run: match and rank, execute nothing.
     *
     * @param value whether to suppress right-hand-side execution
     * @return this builder
     */
    public Builder dryRun(final boolean value) {
      this.dryRun = value;
      return this;
    }

    /**
     * Bounds how many losing activations a fire record names.
     *
     * @param value the bound; zero disables the feature
     * @return this builder
     */
    public Builder runnersUpLimit(final int value) {
      this.runnersUpLimit = value;
      return this;
    }

    /**
     * Selects the matcher.
     *
     * @param value the strategy. {@link MatchingStrategy#NAIVE} is the oracle and is far slower;
     *     {@link MatchingStrategy#RETE} materialises joins for streaming sessions
     * @return this builder
     */
    public Builder matching(final MatchingStrategy value) {
      this.matching = Objects.requireNonNull(value, "matching");
      return this;
    }

    /**
     * Bounds the session's memory with an eviction policy (§4.4).
     *
     * <p>Evicting a fact runs the full retract path, so this bounds working memory, the node
     * memories and their indexes, the refraction memory and the streaming matcher's beta memory
     * together. Not setting it is right for a session that is created, filled, fired and discarded;
     * setting it is what makes a session that runs for days survive.
     *
     * <p>The policy must be a deterministic function of what it is shown -- see
     * {@link EvictionPolicy}, which explains why, and strict mode, which checks.
     *
     * @param value the policy
     * @return this builder
     */
    public Builder eviction(final EvictionPolicy value) {
      this.eviction = Objects.requireNonNull(value, "eviction");
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     * @throws IllegalArgumentException if the runners-up limit is negative
     */
    public SessionOptions build() {
      if (runnersUpLimit < 0) {
        throw new IllegalArgumentException("runnersUpLimit must not be negative");
      }
      return new SessionOptions(this);
    }
  }
}
