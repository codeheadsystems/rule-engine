package com.codeheadsystems.rules.rhs;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A host-supplied function a rule may call by name -- the closed action set's escape hatch
 * (spec §11.3).
 *
 * <p>Three contracts the engine states but cannot enforce:
 *
 * <ul>
 *   <li><strong>Deterministic.</strong> §7.3's guarantee -- same rule set, same facts, same
 *       insertion order, same firing sequence -- holds <em>given</em> deterministic handlers.
 *       Reading a clock inside one is the classic way to lose it; inject time as a fact instead, so
 *       a replay can supply the original timestamp and reproduce the original decision.
 *   <li><strong>Non-blocking and bounded.</strong> Handlers are untrusted for <em>time</em>, not
 *       just for effects. One that blocks indefinitely stalls the session for as long as it blocks,
 *       and there is no fire-loop timeout to rescue it.
 *   <li><strong>Safe for concurrent use, when the caller shares an options object.</strong>
 *       Functions reach a session through {@code SessionOptions.functions()}, and options are per
 *       <em>configuration</em> rather than per session -- {@code RuleBatches.run(rules, inputs,
 *       batch, options)} builds N concurrent sessions from one options object, so one handler
 *       instance serves all of them. A handler holding mutable state has to guard it; a stateless
 *       one, which is most of them, needs nothing. Added in Phase 4 alongside the same correction to
 *       {@link com.codeheadsystems.rules.listener.RuleEngineListener}; §7.1's claim that nothing
 *       reachable from options is shared across sessions is annotated there as a defect.
 * </ul>
 */
@FunctionalInterface
public interface HostFunction {

  /**
   * Invokes the function.
   *
   * @param arguments the resolved arguments, as an object node. Already deep-copied, so the handler
   *     may keep or mutate it freely -- handing over the live node from working memory would put a
   *     hole the size of the escape hatch in the payload ownership contract
   */
  void call(JsonNode arguments);
}
