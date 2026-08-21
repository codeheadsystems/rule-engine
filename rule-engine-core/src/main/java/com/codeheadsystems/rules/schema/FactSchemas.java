package com.codeheadsystems.rules.schema;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;

/**
 * Optional per-fact-type payload schemas (spec §2.3).
 *
 * <p>Two jobs, at two different times, and they are separate methods because they are separate
 * questions:
 *
 * <ul>
 *   <li><strong>At insert and update</strong>, {@link #violations} rejects a malformed fact before
 *       it enters the network. §2.3's argument: a fact that silently fails to match every rule
 *       expecting a field it lacks is a much harder bug to spot than one that fails loudly.
 *   <li><strong>At rule compile</strong>, {@link #typeOf} and {@link #presence} let the compiler
 *       catch a class of authoring mistake that would otherwise surface as a rule that never fires
 *       -- §6.5's type-incompatible literal, and §2.6.1's {@code NE} on an optional path.
 * </ul>
 *
 * <p><strong>Immutable once built, and frozen into the {@code CompiledRuleSet}.</strong> §2.3 is
 * emphatic about this and gives the reason: this object is referenced by every running session, so
 * a mutable {@code register(...)} would be a race between one thread writing and thousands reading,
 * with no barrier and no defined ordering. Changing schemas means recompiling, which is the same
 * operation as changing rules and is handled the same way (§5.6). Implementations must be
 * thread-safe and must not change after construction.
 *
 * <p><strong>On answering "I don't know".</strong> {@link #typeOf} returning empty and
 * {@link #presence} returning {@link Presence#UNKNOWN} are ordinary answers, not failures. A
 * schema may describe a path through {@code $ref}, {@code allOf} or {@code oneOf} composition that
 * an implementation does not attempt to resolve, and saying so costs nothing: the compiler simply
 * checks less, which is exactly the behaviour of registering no schema at all. Guessing instead
 * would turn an unread schema into a wrong compile error, which is much worse than an unchecked
 * one.
 */
public interface FactSchemas {

  /**
   * Validates a payload about to enter working memory.
   *
   * @param factType the fact's type
   * @param payload the payload
   * @return the violations, in a form fit to put in an exception message; empty when the payload is
   *     valid or when no schema is registered for the type
   */
  List<String> violations(String factType, JsonNode payload);

  /**
   * The type a schema declares for a path.
   *
   * @param factType the fact's type
   * @param dottedPath the path in DSL form, e.g. {@code customer.id}
   * @return the declared type, or empty when it is not declared or not readable
   */
  Optional<SchemaType> typeOf(String factType, String dottedPath);

  /**
   * Whether a schema requires a path.
   *
   * @param factType the fact's type
   * @param dottedPath the path in DSL form
   * @return what the schema says, or {@link Presence#UNKNOWN}
   */
  Presence presence(String factType, String dottedPath);

  /**
   * The default: no schemas, no validation, nothing known.
   *
   * <p>This is what §2.3 calls the zero-setup path, and it is what a {@code CompiledRuleSet}
   * carries unless a caller registered something. Returning an object rather than leaving the field
   * null is what keeps every call site free of a null check.
   *
   * @return a registry that validates nothing and knows nothing
   */
  static FactSchemas none() {
    return None.INSTANCE;
  }

  /** Holds the do-nothing instance, which is stateless and therefore shareable. */
  final class None implements FactSchemas {

    private static final None INSTANCE = new None();

    private None() {
      // The one instance is enough; it holds no state.
    }

    @Override
    public List<String> violations(final String factType, final JsonNode payload) {
      return List.of();
    }

    @Override
    public Optional<SchemaType> typeOf(final String factType, final String dottedPath) {
      return Optional.empty();
    }

    @Override
    public Presence presence(final String factType, final String dottedPath) {
      return Presence.UNKNOWN;
    }
  }
}
