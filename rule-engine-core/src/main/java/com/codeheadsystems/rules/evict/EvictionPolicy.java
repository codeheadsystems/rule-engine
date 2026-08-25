package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.fact.FactHandle;
import java.util.List;
import java.util.Map;

/**
 * Which facts a long-lived session should let go of (spec §4.4).
 *
 * <p>A policy selects; it never removes. The session retracts what this returns, through the
 * ordinary retract path, so node memories and their indexes, the refraction memory and -- under the
 * streaming shape -- the beta memory are all cascaded by machinery that already exists. §4.4 makes
 * that a rule rather than an implementation note: eviction reaching into the memories by hand would
 * be a fifth place they are removed from, and the one nobody updates when a sixth is added.
 *
 * <h2>The contract, which is stronger than it looks</h2>
 *
 * <p><strong>An implementation must be a pure function of the view.</strong> Same facts in the same
 * order, same victims, on every host and every run. Eviction changes which facts exist, so it
 * changes which activations exist, so it reaches the firing sequence -- and §7.3 makes that
 * sequence a contract rather than a preference. Two things break it in practice and neither
 * announces itself: reading a clock, and iterating a {@code HashMap}. Strict mode (§7.5) calls a
 * policy twice on the same view and compares the answers, which catches the first reliably and the
 * second often.
 *
 * <p><strong>Order matters too.</strong> The returned list is retracted in the order given, and
 * that order is visible to listeners. Returning a {@code Set} with unspecified iteration would
 * satisfy this interface's types and violate its contract.
 *
 * <p>A policy may return handles it has no business returning -- one already retracted, one it
 * invented -- and the session ignores those rather than failing, except in strict mode where it
 * rejects them. Selecting nothing is expressed as an empty list, and is the answer on the
 * overwhelming majority of the calls a capped policy sees.
 *
 * <h2>Why no time-to-live policy ships here</h2>
 *
 * <p>§4.4 names TTL first, and it is deliberately absent. Wall-clock time is not an input the
 * determinism contract admits: two runs over identical input would evict different facts, and §7.3
 * is not a preference. The policies below key on {@code recency}, which is derived from the input
 * itself. A caller who genuinely wants a TTL writes one against this interface with a clock they
 * inject, and takes the trade knowingly -- which is a better place for that decision than a factory
 * method here that makes it look free.
 */
@FunctionalInterface
public interface EvictionPolicy {

  /**
   * Chooses the facts to evict.
   *
   * @param view read-only working memory, as described on {@link EvictionView}
   * @return the handles to retract, in the order to retract them; empty to evict nothing. Must be a
   *     deterministic function of the view -- see the contract on this interface
   */
  List<FactHandle> selectVictims(EvictionView view);

  /**
   * Caps working memory at a total fact count, evicting least-recently-used first.
   *
   * <p>The simple policy, and the wrong one for the shape §4.4 was written for. A streaming session
   * usually holds two populations -- reference data loaded once and a stream flowing past it -- and
   * the reference data is inserted first, so it is exactly what a global least-recently-used bound
   * evicts first. {@link #perType(Map)} is the answer there. This one is right when every fact is
   * the same kind of thing.
   *
   * @param maxFacts the most facts to hold; the excess over this is evicted
   * @return the policy
   * @throws IllegalArgumentException if {@code maxFacts} is not positive
   */
  static EvictionPolicy leastRecentlyUsed(final int maxFacts) {
    return new LeastRecentlyUsed(maxFacts);
  }

  /**
   * Caps named fact types individually, evicting least-recently-used first within each.
   *
   * <p>A type absent from the map is unbounded, which is the point: "keep the last ten thousand
   * orders and never evict the two hundred customers" is one map and no predicate. Types are
   * considered in name order, so the victims are the same list whatever order the map was built in.
   *
   * @param capsByType the most facts to hold per fact type; types not named are unbounded
   * @return the policy
   * @throws IllegalArgumentException if any cap is not positive
   * @throws NullPointerException if the map or any key is null
   */
  static EvictionPolicy perType(final Map<String, Integer> capsByType) {
    return new PerTypeCaps(capsByType);
  }

  /**
   * Keeps a window of one fact type, measured by a time field on the facts themselves.
   *
   * <p>The bound a streaming rule set actually wants. "The last ten minutes of
   * {@code LoginFailure}" is a window in the data, where {@link #perType(Map)}'s "the last ten
   * thousand of them" is a window in the arrival count -- and the two differ by exactly the
   * traffic spike the rule set exists to notice.
   *
   * <pre>{@code
   * SessionOptions.builder()
   *     .eviction(EvictionPolicy.window("LoginFailure", "at", 600_000))
   *     .build();
   * }</pre>
   *
   * <p><strong>The span is in the field's own units</strong>, exactly as a temporal join's
   * {@code within} is, and for the same reason: only the author knows whether {@code at} holds
   * epoch millis, epoch seconds or a sequence number, and guessing would be guessing wrong for a
   * large share of rule sets, silently. Keep the unit in the field name if there is any doubt.
   *
   * <p><strong>The window's far edge is the newest value of that field the type currently
   * holds</strong>, minus the span -- a watermark taken from the facts, never a clock. That is what
   * makes this policy legal where §4.4's TTL is not: two runs over the same facts in the same order
   * evict the same facts, on every host and in every year, so §7.3 survives. Time advances when a
   * fact carrying a later time arrives, and in a session where nothing arrives, nothing ages: this
   * engine still owns no clock, and "no fact moved" remains the one input it never receives.
   * (Retracting the newest fact moves the watermark back and widens retention, which is harmless
   * and is still a function of the view alone.)
   *
   * <p>Four things to know before configuring one, each of which is silent when it bites:
   *
   * <ol>
   *   <li><strong>Retention is per type and per session; a window in a rule is per rule.</strong>
   *       Two rules wanting ten minutes and twenty-four hours of one type are served by retaining
   *       twenty-four hours, with the ten-minute rule keeping its own {@code within} to narrow what
   *       it matches. Retention must be at least as wide as the widest window written against the
   *       type, or the rule loses matches its author wrote and nothing says so.
   *   <li><strong>A negation or a universal over a windowed type changes meaning.</strong>
   *       {@code notExists LoginFailure} stops saying "there has never been one" and starts saying
   *       "there has not been one lately" -- which is the useful reading and is a false conclusion
   *       for any rule that meant the first. §4.4 calls this the sharpest hazard in the engine: an
   *       evicted fact and an absent fact are indistinguishable, so the rule cannot tell you. An
   *       {@code accumulate} over a windowed type is the case where the interaction is the
   *       <em>point</em> -- "how many failures in the window" -- and is still governed by (1).
   *   <li><strong>A fact with no usable time is never evicted.</strong> Absent, non-numeric and
   *       non-finite all decline rather than guess, because eviction is the destructive act. The
   *       consequence is that this policy cannot bound a type whose facts do not all carry the
   *       field; compose it with {@link #perType(Map)} if that is a risk rather than an invariant.
   *   <li><strong>A fact that arrives already outside the window is evicted on arrival</strong>,
   *       inside the {@code insert} call that added it -- so {@code insert} can hand back a handle
   *       whose fact is already gone. That is correct (a fact older than the window is a fact no
   *       windowed rule can match) and it is the ordinary out-of-order case rather than an exotic
   *       one: any watermark-based window over a real stream sees late arrivals. It is the one
   *       eviction that costs a firing the author might have expected, so watch for it. A
   *       listener's {@code onEvicted} is the only thing that says it happened; the alternative --
   *       refusing the insert -- would make the policy decide what working memory accepts, which is
   *       not what a selection function is for.
   * </ol>
   *
   * <p>It costs one walk of the retained facts of that type per consultation. In steady state that
   * population <em>is</em> the window, which is the point, but it is a walk rather than a
   * comparison: {@link #perType(Map)} declines in one integer comparison where this cannot, and a
   * session holding an enormous window should cap it as well as window it. Under strict mode (§7.5)
   * the walk is dearer than it looks -- every fact it examines is a payload deep copy, and strict
   * consults the policy twice -- so a large windowed type is felt in {@code strictTest} before it
   * is felt in production.
   *
   * @param factType the fact type to bound; other types are untouched
   * @param timeField the dotted path to the time field, e.g. {@code at} or {@code event.at}
   * @param span how much of that field's own range to keep, ending at the newest value held
   * @return the policy
   * @throws IllegalArgumentException if {@code timeField} is empty or malformed, or {@code span} is
   *     not positive
   * @throws NullPointerException if either name is null
   */
  static EvictionPolicy window(final String factType, final String timeField, final long span) {
    return new TimeWindow(factType, timeField, span);
  }
}
