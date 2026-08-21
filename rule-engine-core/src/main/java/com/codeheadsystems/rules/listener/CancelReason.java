package com.codeheadsystems.rules.listener;

/**
 * Why a pending activation will not fire although it was created (spec §7.1).
 *
 * <p>Distinct from {@link SuppressReason}: these are matches whose <em>facts changed under them</em>,
 * so they no longer satisfy the rule.
 */
public enum CancelReason {

  /** One of the bound facts was retracted. */
  RETRACTED,

  /** One of the bound facts was effectively updated, so the match was rebuilt or lost. */
  UPDATED
}
