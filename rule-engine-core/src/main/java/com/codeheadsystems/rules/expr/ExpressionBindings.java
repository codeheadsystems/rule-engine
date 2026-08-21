package com.codeheadsystems.rules.expr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What an expression can see: the facts bound to a rule's aliases (spec §6.4).
 *
 * <p>Deliberately the whole payload per alias, and deliberately nothing else. An expression reads
 * {@code o.total} by reading the {@code Order} bound to {@code o}, so what it needs is the same
 * view a {@code $ref} gets. What it must <em>not</em> get is a way to reach anything outside the
 * tuple -- working memory, the session, a clock -- because §7.3's determinism contract holds only
 * while a rule's outcome is a function of its inputs.
 */
@FunctionalInterface
public interface ExpressionBindings {

  /**
   * The payload bound to an alias.
   *
   * @param alias the alias, as the rule's {@code when} declared it
   * @return that fact's payload, or a missing node when the alias is not bound. Never null: an
   *     expression asking about an unbound alias should see the same absent value §2.6.1 defines
   *     for an absent field, not a crash
   */
  JsonNode get(String alias);
}
