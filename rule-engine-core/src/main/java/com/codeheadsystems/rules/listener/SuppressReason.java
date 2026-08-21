package com.codeheadsystems.rules.listener;

/**
 * Why a match will not fire although its facts still satisfy the rule (spec §7.1).
 */
public enum SuppressReason {

  /** The rule has already fired on these exact facts. */
  REFRACTED,

  /** The rule's own right-hand side produced this match and the rule sets {@code noLoop}. */
  NO_LOOP
}
