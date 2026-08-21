package com.codeheadsystems.rules.observability;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One rule firing, as a JDK Flight Recorder event (spec §7.1).
 *
 * <p>Stack traces are off. The interesting call stack for a firing is always the same -- the fire
 * loop -- so recording it would multiply the event size for no information.
 */
@Name("com.codeheadsystems.rules.RuleFired")
@Label("Rule Fired")
@Category({"Rule Engine"})
@Description("A rule matched and its right-hand side was executed")
@StackTrace(false)
public final class RuleFiredEvent extends Event {

  /** The rule that fired. */
  @Label("Rule")
  String ruleId;

  /** The facts it matched, rendered as their handle ids. */
  @Label("Bound Facts")
  String handles;

  /** The rule's author-assigned priority, one of the two conflict-resolution inputs. */
  @Label("Salience")
  int salience;

  /** How fresh the matched facts were, the other conflict-resolution input. */
  @Label("Recency")
  long recency;

  /** How many working-memory and external effects the firing committed. */
  @Label("Effects")
  int effectCount;

  /** Whether an action threw. */
  @Label("Failed")
  boolean failed;

  /** Creates an empty event; fields are populated by the listener. */
  RuleFiredEvent() {
    // JFR requires a no-argument constructor.
  }
}
