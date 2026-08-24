package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.rhs.HostFunction;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;

/**
 * What {@code callFunction: alertOps} dispatches to: the side effect that leaves the engine.
 *
 * <p><strong>Thread-safe on purpose.</strong> A {@code HostFunction} reaches a session through
 * {@link com.codeheadsystems.rules.session.SessionOptions}, and options are per <em>configuration</em>
 * rather than per session -- {@link BatchDemo} builds one options object and hands it to a dozen
 * concurrent sessions, so this one instance serves all of them. Most handlers are stateless and need
 * nothing; this one keeps a record, so it uses a concurrent list rather than a comment saying it
 * should have.
 *
 * <p><strong>And it is the wrong tool most of the time.</strong> A {@code callFunction} runs at
 * commit, outside the staging that makes §4.6 atomic: if it throws, the working-memory changes that
 * already landed stay landed, and a page that has been sent cannot be un-sent. Prefer {@code emit},
 * which comes back on {@link com.codeheadsystems.rules.session.FireResult#emitted()} and lets the
 * caller decide what to do after the fire call has returned. This class exists so the example can
 * show the difference rather than assert it.
 */
public final class OpsPager implements HostFunction {

  private final List<String> paged = new CopyOnWriteArrayList<>();

  /** Creates a pager that records rather than sending anything. */
  public OpsPager() {
    // Nothing to initialise; the list is final and created above.
  }

  @Override
  public void call(final JsonNode arguments) {
    /*
     * The arguments are already deep-copied before they arrive, so keeping or mutating them is
     * safe -- handing over the live node from working memory would put a hole the size of the
     * escape hatch in §2.2's payload-ownership contract.
     */
    paged.add(arguments.toString());
  }

  /**
   * Every page this handler was asked to send.
   *
   * @return the argument payloads, as JSON text, in the order they arrived
   */
  public List<String> paged() {
    return List.copyOf(paged);
  }
}
