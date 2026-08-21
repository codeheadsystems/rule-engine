package com.codeheadsystems.rules.rhs;

import com.codeheadsystems.rules.fact.FactHandle;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * One effect a firing produced, as committed (spec §7.1).
 *
 * <p>These are what make a fire record answer "what did this firing do" without reconstructing it
 * from a stream of mutations -- and, because commit-phase failure does not roll back working
 * memory (§4.6), what makes the partial state after a failed firing discoverable rather than
 * invisible.
 */
public sealed interface StagedEffect {

  /**
   * A field of an existing fact was set.
   *
   * @param handle the fact that was mutated
   * @param path the path that was written
   * @param value the value written
   */
  record FieldSet(FactHandle handle, JsonPointer path, JsonNode value) implements StagedEffect {

    /**
     * Canonical constructor.
     *
     * @param handle the mutated fact
     * @param path the written path
     * @param value the written value
     */
    public FieldSet {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A derived fact was inserted.
   *
   * @param handle the new fact's handle, allocated at stage time so later actions could name it
   * @param factType the new fact's type
   * @param payload the payload as inserted
   */
  record FactInserted(FactHandle handle, String factType, JsonNode payload) implements StagedEffect {

    /**
     * Canonical constructor.
     *
     * @param handle the new handle
     * @param factType the new fact's type
     * @param payload the payload
     */
    public FactInserted {
      Objects.requireNonNull(handle, "handle");
      Objects.requireNonNull(factType, "factType");
      Objects.requireNonNull(payload, "payload");
    }
  }

  /**
   * A fact was retracted.
   *
   * @param handle the retracted fact
   */
  record FactRetracted(FactHandle handle) implements StagedEffect {

    /**
     * Canonical constructor.
     *
     * @param handle the retracted fact
     */
    public FactRetracted {
      Objects.requireNonNull(handle, "handle");
    }
  }

  /**
   * A host function was called.
   *
   * @param name the registered function name
   * @param arguments the resolved, deep-copied arguments the handler received
   * @param succeeded whether the handler returned normally. A firing that failed at commit records
   *     the failing call as {@code false} and never records the handlers after it, which is how
   *     §4.6 requires partial commits to stay discoverable
   */
  record FunctionCalled(String name, JsonNode arguments, boolean succeeded) implements StagedEffect {

    /**
     * Canonical constructor.
     *
     * @param name the function name
     * @param arguments the resolved arguments
     * @param succeeded whether the handler returned normally
     */
    public FunctionCalled {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(arguments, "arguments");
    }
  }

  /**
   * An event was emitted.
   *
   * @param eventType the event name
   * @param payload the event payload
   */
  record EventEmitted(String eventType, JsonNode payload) implements StagedEffect {

    /**
     * Canonical constructor.
     *
     * @param eventType the event name
     * @param payload the payload
     */
    public EventEmitted {
      Objects.requireNonNull(eventType, "eventType");
      Objects.requireNonNull(payload, "payload");
    }
  }

}
