package com.codeheadsystems.rules.testkit;

import com.codeheadsystems.rules.session.FireRecord;
import com.codeheadsystems.rules.session.FireResult;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A canonical, comparable record of what a session did.
 *
 * <p>This is the unit of the correctness oracle. Spec §9 makes Phase 0's naive matcher the oracle
 * for every later phase, and both the Phase 2 and Phase 3 exit criteria are the same shape:
 * <em>identical firing sequences on the same input</em>. Reducing a fire result to this makes that
 * claim a value comparison rather than a bespoke assertion written per phase.
 *
 * <p><strong>What it deliberately excludes.</strong> Durations, because they vary; and the rule-set
 * version, because a shuffle test compiles the same rules in different orders and gets a different
 * content hash while expecting the same behaviour. Everything else -- which rule fired, on which
 * handles, in what order, with what effects and what events -- is included, because all of it is
 * observable and all of it is supposed to be reproducible.
 *
 * @param steps what fired, in firing order
 */
public record FiringSequence(List<Step> steps) {

  /**
   * Canonical constructor. Defensively copies the steps.
   *
   * @param steps the firing steps
   */
  public FiringSequence {
    steps = List.copyOf(steps);
  }

  /**
   * Captures a fire result.
   *
   * @param result the result to reduce
   * @return the canonical sequence
   */
  public static FiringSequence of(final FireResult result) {
    return new FiringSequence(result.fired().stream().map(Step::of).toList());
  }

  /**
   * A human-readable rendering, one firing per line.
   *
   * <p>Assertion failures on a firing sequence are read by a person trying to work out which of two
   * engines is wrong, so the failure message has to be diffable by eye.
   *
   * @return the rendering
   */
  public String describe() {
    return steps.isEmpty()
        ? "(nothing fired)"
        : steps.stream().map(Step::describe).collect(Collectors.joining(System.lineSeparator()));
  }

  /**
   * One firing.
   *
   * @param ruleId the rule that fired
   * @param handles the facts it matched, in tuple order
   * @param effects what it did, rendered
   * @param emitted the events it produced, rendered
   */
  public record Step(String ruleId, List<Long> handles, List<String> effects,
      List<String> emitted) {

    /**
     * Canonical constructor. Defensively copies the collection components.
     *
     * @param ruleId the rule
     * @param handles the matched facts
     * @param effects the rendered effects
     * @param emitted the rendered events
     */
    public Step {
      Objects.requireNonNull(ruleId, "ruleId");
      handles = List.copyOf(handles);
      effects = List.copyOf(effects);
      emitted = List.copyOf(emitted);
    }

    /**
     * Captures one firing record.
     *
     * @param record the record
     * @return the step
     */
    static Step of(final FireRecord record) {
      return new Step(
          record.key().ruleId(),
          Arrays.stream(record.key().handles()).boxed().toList(),
          record.effects().stream().map(Object::toString).toList(),
          record.emitted().stream()
              .map(event -> event.eventType() + event.payload()).toList());
    }

    /**
     * A one-line rendering.
     *
     * @return the rendering
     */
    String describe() {
      return ruleId + handles + " -> " + effects
          + (emitted.isEmpty() ? "" : " emitted " + emitted);
    }
  }
}
