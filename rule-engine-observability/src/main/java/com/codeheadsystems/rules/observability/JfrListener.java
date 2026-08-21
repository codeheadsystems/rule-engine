package com.codeheadsystems.rules.observability;

import com.codeheadsystems.rules.listener.RuleEngineListener;
import com.codeheadsystems.rules.session.FireRecord;
import java.util.Arrays;

/**
 * Emits JDK Flight Recorder events as rules fire (spec §7.1).
 *
 * <p>Chosen over a logging listener for one reason worth stating: it lets a rule-firing timeline be
 * correlated against GC, allocation and virtual-thread scheduling <em>in one recording</em>. That is
 * how you answer "is the engine slow, or is it waiting?" -- a question a log of firings cannot
 * answer at all, and the question that actually comes up when across-session parallelism (§5.2) is
 * carrying real load.
 *
 * <p>Near-zero cost when recording is off: {@code Event.isEnabled()} is a constant the JIT folds
 * away, so a disabled recording leaves the branch and nothing else. That is what makes it
 * reasonable to leave registered in production, which a tracing listener is not.
 */
public final class JfrListener implements RuleEngineListener {

  /** Creates a listener. It holds no state; every event is self-contained. */
  public JfrListener() {
    // Nothing to initialise.
  }

  @Override
  public void onAfterFire(final FireRecord record) {
    final RuleFiredEvent event = new RuleFiredEvent();
    if (!event.isEnabled()) {
      // No recording is running. Populating the event would be pure waste, and this check is the
      // whole reason this listener is cheap enough to leave on.
      return;
    }
    event.ruleId = record.key().ruleId();
    event.handles = Arrays.toString(record.key().handles());
    event.salience = record.salience();
    event.recency = record.recency();
    event.effectCount = record.effects().size();
    event.failed = record.failedAction().isPresent();
    event.commit();
  }
}
