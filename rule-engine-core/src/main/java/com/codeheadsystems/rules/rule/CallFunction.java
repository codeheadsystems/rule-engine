package com.codeheadsystems.rules.rule;

import java.util.List;
import java.util.Objects;

/**
 * Dispatches by name to a pre-registered host function -- the closed set's escape hatch
 * (spec §2.5, §11.3).
 *
 * <p>Two contracts a rule author will not read from the syntax, so they are stated here:
 *
 * <ul>
 *   <li><strong>Arguments are resolved to values and deep-copied before the handler sees them.</strong>
 *       Handing a handler the live node from working memory would put a hole the size of the escape
 *       hatch in §2.2's ownership contract: the handler is arbitrary host Java, under no obligation
 *       not to mutate what it is given, and a mutation there bypasses {@code update()} and leaves
 *       every index stale.
 *   <li><strong>It is not transactional.</strong> It runs at commit, after working-memory effects
 *       are applied, and a handler that throws leaves those effects in place. §11.3's own example
 *       is {@code notifySlack}, and a sent message cannot be un-sent.
 * </ul>
 *
 * @param name the registered function name; an unknown name is a compile error
 * @param args the arguments, in declaration order
 */
public record CallFunction(String name, List<PayloadField> args) implements ActionDefinition {

  /**
   * Canonical constructor. Defensively copies {@code args}.
   *
   * @param name the registered function name
   * @param args the arguments
   */
  public CallFunction {
    Objects.requireNonNull(name, "name");
    args = List.copyOf(args);
  }
}
