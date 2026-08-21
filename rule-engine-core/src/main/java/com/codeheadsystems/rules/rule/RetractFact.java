package com.codeheadsystems.rules.rule;

import java.util.Objects;

/**
 * Retracts a fact bound by the LHS, or one inserted earlier in this same RHS (spec §2.5, §6.2.2).
 *
 * <p>Retracting a fact this RHS inserted cancels both effects at commit rather than propagating an
 * insert and a retract (§4.6).
 *
 * @param targetAlias the alias of the fact to retract
 */
public record RetractFact(String targetAlias) implements ActionDefinition {

  /**
   * Canonical constructor.
   *
   * @param targetAlias the alias of the fact to retract
   */
  public RetractFact {
    Objects.requireNonNull(targetAlias, "targetAlias");
  }
}
