package com.codeheadsystems.rules.rhs;

import com.codeheadsystems.rules.eval.Accumulators;
import com.codeheadsystems.rules.expr.CompiledExpression;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.match.Activation;
import com.codeheadsystems.rules.rule.ActionDefinition;
import com.codeheadsystems.rules.rule.CallFunction;
import com.codeheadsystems.rules.rule.Emit;
import com.codeheadsystems.rules.rule.ExpressionValue;
import com.codeheadsystems.rules.rule.FieldRef;
import com.codeheadsystems.rules.rule.InsertFact;
import com.codeheadsystems.rules.rule.Literal;
import com.codeheadsystems.rules.rule.PayloadField;
import com.codeheadsystems.rules.rule.RetractFact;
import com.codeheadsystems.rules.rule.SetField;
import com.codeheadsystems.rules.rule.ValueExpr;
import com.codeheadsystems.rules.session.EmitContext;
import com.codeheadsystems.rules.session.EmittedEvent;
import com.codeheadsystems.rules.session.EventSink;
import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executes one right-hand side: stage everything, then commit (spec §4.6).
 *
 * <pre>
 * execute activation A:
 *     staging = new StagingBuffer()
 *     for each action in A.rule().actions():   # declaration order
 *         stage the effect into `staging`
 *     commit(staging)                          # NOW: working memory, functions, events
 * </pre>
 *
 * <p>Propagating after each action would let action 2 observe state action 1 created, making a
 * rule's behaviour depend on action ordering in ways invisible in the rule file. Deferred commit
 * gives every action one consistent view. The cost is that an action cannot read the result of an
 * earlier action in the same right-hand side, which is the right limitation -- needing it means the
 * rule should be two rules.
 *
 * <p><strong>The atomicity guarantee is per-phase, and the two phases differ.</strong>
 * "All-or-nothing if any action throws" is true of staging and false of commit, and a host-function
 * call <em>is</em> one of the five actions -- so stating the rollback rule without splitting the
 * phases makes it false for the one action type that realistically throws.
 *
 * <ul>
 *   <li><strong>Staging-phase failure: full rollback.</strong> Anything that throws while effects
 *       are being staged discards the entire buffer. Nothing is applied, nothing is emitted, no
 *       handler runs. Working memory never lands half-mutated.
 *   <li><strong>Commit-phase failure: no rollback of working memory.</strong> Working-memory
 *       effects are applied first, then handlers run in declaration order, then events are
 *       delivered. A handler that throws leaves the working-memory effects applied and earlier
 *       handlers' side effects in place. There is no compensating undo, and there cannot be one:
 *       a sent message cannot be un-sent.
 * </ul>
 *
 * <p><strong>Every commit-phase failure is reported, not only a handler's.</strong> Applying a
 * working-memory effect can throw too -- a {@code setField} whose path runs through a scalar is
 * enough -- and an unguarded commit would let that escape the firing loop entirely, bypassing the
 * error policy, producing no record of the effects that had already landed, and leaving the session
 * usable with a half-applied right-hand side. Both commit loops are guarded, and both report
 * through the same channel.
 */
public final class RhsExecutor {

  private final WorkingMemory workingMemory;
  private final Map<String, HostFunction> functions;
  private final EventSink events;
  private final List<RuleEngineListener> listeners;
  private final UUID sessionId;
  private final String ruleSetVersion;
  private final boolean dryRun;

  /**
   * Creates an executor bound to one session.
   *
   * @param workingMemory the session's working memory
   * @param functions the registered host functions
   * @param events where emitted events go
   * @param listeners the session's listeners, in registration order
   * @param sessionId the session's id, stamped into every emit context
   * @param ruleSetVersion the rule set's content hash, stamped into every emit context
   * @param dryRun when true, everything is staged and nothing is applied: no working-memory
   *     changes, no handler calls, no deliveries. The staged effects are still reported, which is
   *     what makes a dry run answer "what would this do"
   */
  public RhsExecutor(final WorkingMemory workingMemory, final Map<String, HostFunction> functions,
      final EventSink events, final List<RuleEngineListener> listeners, final UUID sessionId,
      final String ruleSetVersion, final boolean dryRun) {
    this.workingMemory = Objects.requireNonNull(workingMemory, "workingMemory");
    this.functions = Map.copyOf(functions);
    this.events = Objects.requireNonNull(events, "events");
    this.listeners = List.copyOf(listeners);
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.ruleSetVersion = Objects.requireNonNull(ruleSetVersion, "ruleSetVersion");
    this.dryRun = dryRun;
  }

  /**
   * Runs one activation's right-hand side.
   *
   * @param activation the match to fire
   * @return what actually landed, and what failed
   */
  public RhsResult execute(final Activation activation) {
    final Staging staging = new Staging();
    final List<ActionDefinition> actions = activation.rule().actions();
    for (int index = 0; index < actions.size(); index++) {
      final ActionDefinition action = actions.get(index);
      try {
        stage(action, activation, staging);
      } catch (final RuntimeException failure) {
        // Staging-phase failure: discard everything. Nothing was applied, so everything from the
        // failing action onwards -- and everything before it -- never ran.
        //
        // Reservations must be handed back here explicitly. releaseHandle otherwise lives in
        // PendingInsert.apply, which never runs on this path, so a rule that stages an insert and
        // then fails to stage a later action would leak one handle id per firing -- and under a
        // skip-and-continue error policy that repeats for every match, forever.
        staging.insertsByHandle.values()
            .forEach(insert -> workingMemory.releaseHandle(insert.handle));
        return new RhsResult(List.of(), List.of(),
            Optional.of(new RhsResult.Failure(action, failure, false)),
            withoutIndex(actions, index));
      }
    }
    return commit(activation, staging);
  }

  /**
   * Stages one action's effect.
   *
   * @param action the action
   * @param activation the firing match
   * @param staging the buffer to stage into
   */
  private void stage(final ActionDefinition action, final Activation activation,
      final Staging staging) {
    switch (action) {
      case SetField setField -> stageSetField(setField, activation, staging);
      case InsertFact insertFact -> stageInsert(insertFact, activation, staging);
      case RetractFact retractFact -> stageRetract(retractFact, activation, staging);
      case Emit emit -> staging.emits.add(new PendingEmit(
          emit, emit.eventType(), buildPayload(emit.payload(), activation, staging)));
      case CallFunction call -> stageCall(call, activation, staging);
    }
  }

  /**
   * Stages a field mutation as a <em>delta</em> merged into this handle's pending change.
   *
   * <p>Staging each field set as an independent update built from the pre-firing payload means the
   * second overwrites the first and the earlier field change silently vanishes -- §4.6 calls this
   * the single most likely week-one bug in the design. Deltas are merged and materialised once.
   *
   * <p>A field set on a fact <em>this same right-hand side inserted</em> merges into that insert's
   * payload rather than becoming a separate update. Committing it as insert-then-update would make
   * the fact briefly visible in an intermediate form, mark its rules dirty twice, and bump its
   * recency a second time -- which reorders conflict resolution against every other fact for no
   * reason the rule author could predict.
   *
   * @param action the action
   * @param activation the firing match
   * @param staging the buffer
   */
  private void stageSetField(final SetField action, final Activation activation,
      final Staging staging) {
    final FactHandle handle = targetHandle(action.targetAlias(), activation, staging);
    if (staging.retracted.contains(handle.id())) {
      throw new IllegalStateException(
          "setField on '" + action.targetAlias() + "', which this rule already retracted");
    }
    final JsonNode value = resolve(action.value(), activation, staging);

    final PendingInsert pending = staging.insertsByHandle.get(handle.id());
    if (pending != null) {
      pending.merge(action.path(), value);
      return;
    }
    final Fact fact = workingMemory.get(handle).orElseThrow(() -> new IllegalStateException(
        "setField on '" + action.targetAlias() + "', whose fact is no longer asserted"));
    if (!fact.payload().isObject()) {
      throw new IllegalStateException(
          "setField on '" + action.targetAlias() + "', whose payload is not a JSON object");
    }
    final PendingUpdate update = staging.updatesByHandle.computeIfAbsent(handle.id(), ignored -> {
      final PendingUpdate created = new PendingUpdate(handle);
      staging.operations.add(created);
      return created;
    });
    update.merge(action, action.path(), value);
  }

  /**
   * Stages a derived-fact insert, allocating its handle immediately.
   *
   * @param action the action
   * @param activation the firing match
   * @param staging the buffer
   */
  private void stageInsert(final InsertFact action, final Activation activation,
      final Staging staging) {
    final FactHandle handle = workingMemory.reserveHandle();
    final PendingInsert insert = new PendingInsert(action, handle, action.factType(),
        buildPayload(action.payload(), activation, staging));
    staging.operations.add(insert);
    staging.insertsByHandle.put(handle.id(), insert);
    action.alias().ifPresent(alias -> staging.insertsByAlias.put(alias, insert));
  }

  /**
   * Stages a retract, cancelling anything this same right-hand side staged for that handle.
   *
   * <p>Three cancellations, all of them §4.6's rule that an insert and a retract of the same fact in
   * one firing cancel rather than propagate:
   *
   * <ul>
   *   <li>A pending insert is cancelled outright, and no retract is staged.
   *   <li>A pending update is dropped. Propagating it would run a full retract-and-reassert --
   *       recency bump, per-rule refraction invalidation, a listener burst -- on a fact that is
   *       deleted microseconds later in the same commit.
   *   <li>A second retract of the same handle is ignored, rather than recording a second
   *       {@code FactRetracted} effect for something that did not happen. The firing record is the
   *       audit log; it must not claim work the engine did not do.
   * </ul>
   *
   * @param action the action
   * @param activation the firing match
   * @param staging the buffer
   */
  private void stageRetract(final RetractFact action, final Activation activation,
      final Staging staging) {
    final FactHandle handle = targetHandle(action.targetAlias(), activation, staging);
    if (!staging.retracted.add(handle.id())) {
      return;
    }
    final PendingUpdate update = staging.updatesByHandle.remove(handle.id());
    if (update != null) {
      staging.operations.remove(update);
    }
    final PendingInsert pending = staging.insertsByHandle.get(handle.id());
    if (pending == null) {
      staging.operations.add(new PendingRetract(action, handle));
    } else {
      pending.cancelled = true;
    }
  }

  /**
   * Stages a host-function call, resolving and copying its arguments.
   *
   * @param action the action
   * @param activation the firing match
   * @param staging the buffer
   */
  private void stageCall(final CallFunction action, final Activation activation,
      final Staging staging) {
    if (!functions.containsKey(action.name())) {
      throw new IllegalStateException("no host function registered under '" + action.name() + "'");
    }
    staging.calls.add(new PendingCall(
        action, action.name(), buildPayload(action.args(), activation, staging)));
  }

  /**
   * Applies the staged buffer (spec §4.6's commit ordering: working memory, then handlers, then
   * events).
   *
   * @param activation the firing match
   * @param staging the buffer
   * @return what landed
   */
  private RhsResult commit(final Activation activation, final Staging staging) {
    final List<StagedEffect> effects = new ArrayList<>();
    final List<EmittedEvent> emitted = new ArrayList<>();

    for (int index = 0; index < staging.operations.size(); index++) {
      try {
        staging.operations.get(index).apply(effects);
      } catch (final CommitFailure failure) {
        return failedAtCommit(effects, emitted, failure.action, failure.getCause(),
            unappliedFrom(staging, index, failure.action), staging);
      }
    }

    for (int index = 0; index < staging.calls.size(); index++) {
      final PendingCall call = staging.calls.get(index);
      if (!dryRun) {
        try {
          functions.get(call.name).call(call.arguments);
        } catch (final RuntimeException failure) {
          effects.add(new StagedEffect.FunctionCalled(call.name, call.arguments, false));
          return failedAtCommit(effects, emitted, call.action, failure,
              actionsOf(staging.calls.subList(index + 1, staging.calls.size()),
                  PendingCall::action),
              staging);
        }
      }
      effects.add(new StagedEffect.FunctionCalled(call.name, call.arguments, !dryRun));
    }

    for (int index = 0; index < staging.emits.size(); index++) {
      final PendingEmit emit = staging.emits.get(index);
      final EmitContext context = new EmitContext(
          sessionId, activation.rule().id(), activation.tuple().boundFacts(), ruleSetVersion);
      if (!dryRun) {
        try {
          events.emit(emit.eventType, emit.payload, context);
        } catch (final RuntimeException failure) {
          return failedAtCommit(effects, emitted, emit.action, failure,
              actionsOf(staging.emits.subList(index + 1, staging.emits.size()),
                  PendingEmit::action),
              staging);
        }
        for (final RuleEngineListener listener : listeners) {
          listener.onEmit(emit.eventType, emit.payload, context);
        }
      }
      emitted.add(new EmittedEvent(emit.eventType, emit.payload, context));
      effects.add(new StagedEffect.EventEmitted(emit.eventType, emit.payload));
    }
    return RhsResult.succeeded(effects, emitted);
  }

  /**
   * Assembles the result of a commit-phase failure.
   *
   * <p>Working-memory effects are <em>not</em> rolled back, so {@code effects} is kept as-is: it is
   * the record of what actually landed, and §4.6 requires that record to exist precisely because
   * the partial state is otherwise undiscoverable.
   *
   * @param effects what landed before the failure
   * @param emitted what was delivered before the failure
   * @param action the action that threw
   * @param cause the exception
   * @param remainingInPhase the actions of the failing phase that never ran
   * @param staging the buffer, read for the phases that never started
   * @return the failed result
   */
  private static RhsResult failedAtCommit(final List<StagedEffect> effects,
      final List<EmittedEvent> emitted, final ActionDefinition action, final Throwable cause,
      final List<ActionDefinition> remainingInPhase, final Staging staging) {
    final List<ActionDefinition> notRun = new ArrayList<>(remainingInPhase);
    // Commit runs working memory, then handlers, then emissions. Whichever phase failed, every
    // later phase never started at all.
    if (!(action instanceof CallFunction) && !(action instanceof Emit)) {
      staging.calls.forEach(call -> notRun.add(call.action()));
    }
    if (!(action instanceof Emit)) {
      staging.emits.forEach(emit -> notRun.add(emit.action()));
    }
    return new RhsResult(effects, emitted,
        Optional.of(new RhsResult.Failure(action, cause, true)), notRun);
  }

  /**
   * The actions behind every working-memory effect that did not land.
   *
   * <p>That includes the failing operation's <em>siblings</em>, not only the operations after it.
   * Several field sets on one handle merge into a single update that is materialised onto a copy
   * and applied in one call, so when one of them throws none of them land -- and a record that
   * named only the thrower would tell a reader the others succeeded. The failing action itself is
   * excluded because it is reported separately as the failure.
   *
   * @param staging the buffer
   * @param failedIndex the index of the operation that threw
   * @param failedAction the action that threw, excluded from the result
   * @return the actions that produced no effect, in staging order
   */
  private static List<ActionDefinition> unappliedFrom(final Staging staging, final int failedIndex,
      final ActionDefinition failedAction) {
    final List<ActionDefinition> unapplied = new ArrayList<>();
    for (final ActionDefinition sibling : staging.operations.get(failedIndex).actions()) {
      if (sibling != failedAction) {
        unapplied.add(sibling);
      }
    }
    for (int index = failedIndex + 1; index < staging.operations.size(); index++) {
      unapplied.addAll(staging.operations.get(index).actions());
    }
    return unapplied;
  }

  /**
   * Maps a list of staged items to their originating actions.
   *
   * @param items the staged items
   * @param extractor how to read an item's action
   * @param <T> the staged item type
   * @return the actions, in order
   */
  private static <T> List<ActionDefinition> actionsOf(final List<T> items,
      final java.util.function.Function<T, ActionDefinition> extractor) {
    return items.stream().map(extractor).toList();
  }

  /**
   * Every action except the one at a given index.
   *
   * @param actions the rule's actions
   * @param failedIndex the index of the action that threw
   * @return the others, in declaration order
   */
  private static List<ActionDefinition> withoutIndex(final List<ActionDefinition> actions,
      final int failedIndex) {
    final List<ActionDefinition> others = new ArrayList<>(actions.size() - 1);
    for (int index = 0; index < actions.size(); index++) {
      if (index != failedIndex) {
        others.add(actions.get(index));
      }
    }
    return others;
  }

  /**
   * Resolves the handle an alias names, whether bound by the left-hand side or created by this same
   * right-hand side.
   *
   * @param alias the alias
   * @param activation the firing match
   * @param staging the buffer
   * @return the handle
   * @throws IllegalStateException if the alias is bound by neither
   */
  private FactHandle targetHandle(final String alias, final Activation activation,
      final Staging staging) {
    if (activation.tuple().aliases().contains(alias)) {
      return activation.tuple().handleOf(alias);
    }
    final PendingInsert insert = staging.insertsByAlias.get(alias);
    if (insert == null) {
      throw new IllegalStateException("action names unbound alias '" + alias + "'");
    }
    return insert.handle;
  }

  /**
   * Builds a fresh payload from an ordered list of fields.
   *
   * @param fields the payload fields, in declaration order
   * @param activation the firing match
   * @param staging the buffer
   * @return a new object node
   */
  private ObjectNode buildPayload(final List<PayloadField> fields, final Activation activation,
      final Staging staging) {
    final ObjectNode payload = JsonNodeFactory.instance.objectNode();
    for (final PayloadField field : fields) {
      JsonWriter.set(payload, field.path(), resolve(field.value(), activation, staging));
    }
    return payload;
  }

  /**
   * Resolves a value expression to an owned node.
   *
   * <p>Everything returned here is a copy. A reference resolved straight out of working memory
   * would splice engine-owned nodes into a new fact's payload or into a handler's arguments, and a
   * mutation through either would bypass {@code update()} and leave the engine's view stale.
   *
   * <p>A reference to an <em>absent</em> field resolves to JSON null rather than failing. §4.6's
   * staging failure is a reference to an unbound <em>alias</em>, which is an authoring error the
   * compiler can and does catch; an absent field is ordinary sparse data, and §2.6.1's whole
   * position is that absent values are a normal thing to have opinions about rather than an error.
   *
   * @param expr the expression
   * @param activation the firing match
   * @param staging the buffer
   * @return a fresh node
   */
  private JsonNode resolve(final ValueExpr expr, final Activation activation,
      final Staging staging) {
    final JsonNode resolved = switch (expr) {
      case Literal literal -> literal.value();
      case FieldRef ref -> payloadOf(ref.alias(), activation, staging)
          .orElseThrow(() -> new IllegalStateException(
              "$ref names unbound alias '" + ref.alias() + "'"))
          .at(ref.path());
      case ExpressionValue expression -> evaluate(expression, activation, staging);
    };
    return resolved.isMissingNode()
        ? JsonNodeFactory.instance.nullNode()
        : resolved.deepCopy();
  }

  /**
   * The payload a reference reads from.
   *
   * @param ref the reference
   * @param activation the firing match
   * @param staging the buffer
   * @return the payload of the fact the alias names, the staged payload of a fact this same
   *     right-hand side is inserting, or the folded answer of an accumulate; empty when the alias
   *     names none of the three
   */
  private Optional<JsonNode> payloadOf(final String alias, final Activation activation,
      final Staging staging) {
    if (activation.tuple().aliases().contains(alias)) {
      return Optional.of(activation.tuple().payloadOf(alias, workingMemory));
    }
    final PendingInsert insert = staging.insertsByAlias.get(alias);
    if (insert != null) {
      return Optional.of(insert.payload);
    }
    /*
     * An accumulate alias, folded HERE rather than carried in the tuple (§2.5's second amendment).
     * The value is computed from working memory at the moment it is read, which is the same thing
     * payloadOf does above for a handle and is what keeps §3.2.2's invariant intact -- an aggregate
     * stored in a materialised tuple would be stale the instant any fact in its scope moved.
     *
     * Read at staging time, so it sees working memory as it was before this right-hand side
     * committed anything. That is the same instant every other $ref reads at, which is the property
     * §4.6 wants: every value in one firing comes from one consistent view.
     */
    return activation.rule().accumulateNamed(alias)
        .map(accumulate -> Accumulators.evaluate(
            accumulate, activation.tuple().boundFacts(), workingMemory));
  }

  /**
   * Evaluates a §6.4 expression against the firing match.
   *
   * <p>Once per firing, not once per candidate -- which is what makes an expression on this side
   * cheap where §6.4 warns at length about one on the other.
   *
   * <p>The bindings see exactly what a {@code $ref} sees: the tuple's aliases, plus any alias bound
   * by an {@code insertFact} already staged in this same right-hand side. An alias that is bound by
   * neither reads as missing rather than throwing, because {@code ExpressionBindings} promises a
   * value -- and an expression naming an alias the rule does not bind was rejected at compile time,
   * so reaching that here would mean the compiler let something through.
   *
   * @param expression the expression to evaluate
   * @param activation the firing match
   * @param staging the buffer, for aliases this right-hand side has already inserted
   * @return the value it produced
   */
  private JsonNode evaluate(final ExpressionValue expression, final Activation activation,
      final Staging staging) {
    final CompiledExpression program =
        activation.rule().valueExpressions().get(expression.expression());
    if (program == null) {
      throw new IllegalStateException(
          "no compiled program for expression '" + expression.expression()
              + "'; the rule set was built without one");
    }
    return program.evaluate(alias -> payloadOf(alias, activation, staging)
        .orElseGet(JsonNodeFactory.instance::missingNode));
  }

  /** The staging buffer for one right-hand side. */
  private static final class Staging {

    private final List<PendingOperation> operations = new ArrayList<>();
    private final Map<Long, PendingUpdate> updatesByHandle = new LinkedHashMap<>();
    private final Map<Long, PendingInsert> insertsByHandle = new LinkedHashMap<>();
    private final Map<String, PendingInsert> insertsByAlias = new LinkedHashMap<>();
    private final Set<Long> retracted = new LinkedHashSet<>();
    private final List<PendingCall> calls = new ArrayList<>();
    private final List<PendingEmit> emits = new ArrayList<>();
  }

  /**
   * A commit-phase failure, tagged with the action responsible.
   *
   * <p>Applying a working-memory effect can throw for reasons the staging checks cannot anticipate,
   * and the failure has to reach the error policy carrying the action that caused it. Wrapping is
   * how the action travels; {@link #getCause()} is the original exception, which is what the policy
   * and the caller see.
   */
  private static final class CommitFailure extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The action whose application threw. */
    private final transient ActionDefinition action;

    private CommitFailure(final ActionDefinition action, final RuntimeException cause) {
      super(cause);
      this.action = action;
    }
  }

  /** One staged working-memory change, applied at commit in declaration order. */
  private interface PendingOperation {

    /**
     * Applies this change.
     *
     * @param effects the list to record what landed into
     * @throws CommitFailure if applying it throws, tagged with the responsible action
     */
    void apply(List<StagedEffect> effects);

    /**
     * The actions this operation represents.
     *
     * @return the actions, so a failure can report what never ran
     */
    List<ActionDefinition> actions();
  }

  /** A staged insert. Its handle already exists; only the propagation is deferred. */
  private final class PendingInsert implements PendingOperation {

    private final InsertFact action;
    private final FactHandle handle;
    private final String factType;
    private final ObjectNode payload;
    private boolean cancelled;

    private PendingInsert(final InsertFact action, final FactHandle handle, final String factType,
        final ObjectNode payload) {
      this.action = action;
      this.handle = handle;
      this.factType = factType;
      this.payload = payload;
    }

    /**
     * Folds a later field set into this insert's payload.
     *
     * <p>No separate effect is recorded. The effects list is the record of what <em>landed</em>
     * (§4.6), and what lands here is one insert whose payload already carries the merged value;
     * a {@code FieldSet} alongside it would read as though the field were written twice.
     *
     * @param path where to write
     * @param value what to write
     */
    private void merge(final JsonPointer path, final JsonNode value) {
      JsonWriter.set(payload, path, value);
    }

    @Override
    public List<ActionDefinition> actions() {
      return List.of(action);
    }

    @Override
    public void apply(final List<StagedEffect> effects) {
      if (cancelled) {
        // The handle was reserved and will never be used. Give it back, so a long-lived session
        // does not accumulate reservations for facts that were never inserted.
        workingMemory.releaseHandle(handle);
        return;
      }
      if (dryRun) {
        workingMemory.releaseHandle(handle);
      } else {
        try {
          workingMemory.insertReserved(handle, factType, payload);
        } catch (final RuntimeException failure) {
          throw new CommitFailure(action, failure);
        }
      }
      effects.add(new StagedEffect.FactInserted(handle, factType, payload, action.logical()));
    }
  }

  /** Merged field deltas for one handle, materialised once at commit. */
  private final class PendingUpdate implements PendingOperation {

    private final FactHandle handle;
    private final Map<JsonPointer, JsonNode> deltas = new LinkedHashMap<>();
    private final Map<JsonPointer, ActionDefinition> sources = new LinkedHashMap<>();

    private PendingUpdate(final FactHandle handle) {
      this.handle = handle;
    }

    /**
     * Records one field set, overwriting an earlier write to the same path.
     *
     * @param action the action responsible, kept so a commit failure can name it
     * @param path where to write
     * @param value what to write
     */
    private void merge(final ActionDefinition action, final JsonPointer path,
        final JsonNode value) {
      deltas.put(path, value);
      sources.put(path, action);
    }

    @Override
    public List<ActionDefinition> actions() {
      return List.copyOf(sources.values());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The deltas are materialised onto a <strong>deep copy</strong> of the stored payload and
     * handed to {@code updateOwned}. Applying them to the stored node in place is the aliasing bug
     * of §2.2, arriving through the engine's own right-hand-side path: the update diff would
     * compare an object against itself, find nothing changed, propagate nothing, and leave every
     * index stale. Copying here and skipping the copy {@code update} would otherwise make costs one
     * deep copy per mutated fact, not two.
     */
    @Override
    public void apply(final List<StagedEffect> effects) {
      final Optional<Fact> current = workingMemory.get(handle);
      if (current.isEmpty()) {
        return;
      }
      final ObjectNode mutated = (ObjectNode) current.get().payload().deepCopy();
      final List<StagedEffect> applied = new ArrayList<>(deltas.size());
      for (final Map.Entry<JsonPointer, JsonNode> delta : deltas.entrySet()) {
        try {
          JsonWriter.set(mutated, delta.getKey(), delta.getValue());
        } catch (final RuntimeException failure) {
          throw new CommitFailure(sources.get(delta.getKey()), failure);
        }
        applied.add(new StagedEffect.FieldSet(handle, delta.getKey(), delta.getValue()));
      }
      if (!dryRun) {
        try {
          workingMemory.updateOwned(handle, mutated);
        } catch (final RuntimeException failure) {
          throw new CommitFailure(sources.values().iterator().next(), failure);
        }
      }
      effects.addAll(applied);
    }
  }

  /** A staged retract. */
  private final class PendingRetract implements PendingOperation {

    private final RetractFact action;
    private final FactHandle handle;

    private PendingRetract(final RetractFact action, final FactHandle handle) {
      this.action = action;
      this.handle = handle;
    }

    @Override
    public List<ActionDefinition> actions() {
      return List.of(action);
    }

    @Override
    public void apply(final List<StagedEffect> effects) {
      if (!dryRun) {
        try {
          workingMemory.retract(handle);
        } catch (final RuntimeException failure) {
          throw new CommitFailure(action, failure);
        }
      }
      effects.add(new StagedEffect.FactRetracted(handle));
    }
  }

  /**
   * A staged host-function call.
   *
   * @param action the action, kept so a commit-phase failure can name it
   * @param name the function name
   * @param arguments the resolved, copied arguments
   */
  private record PendingCall(CallFunction action, String name, ObjectNode arguments) {}

  /**
   * A staged emission.
   *
   * @param action the action, kept so a commit-phase failure can name it
   * @param eventType the event name
   * @param payload the resolved payload
   */
  private record PendingEmit(Emit action, String eventType, ObjectNode payload) {}
}
