package com.codeheadsystems.rules.evict;

import com.codeheadsystems.rules.access.Paths;
import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.value.Canonical;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;

/**
 * A retention window over one fact type, measured by a field on the facts themselves (spec §4.4).
 *
 * @see EvictionPolicy#window(String, String, long)
 */
final class TimeWindow implements EvictionPolicy {

  private final String factType;
  private final String timeField;
  private final JsonPointer path;
  private final BigDecimal span;

  /**
   * Creates the policy.
   *
   * @param factType the type to bound
   * @param timeField the dotted path to the time field
   * @param span how much of the field's own range to keep
   */
  TimeWindow(final String factType, final String timeField, final long span) {
    this.factType = Objects.requireNonNull(factType, "factType");
    this.timeField = Objects.requireNonNull(timeField, "timeField");
    if (timeField.isEmpty()) {
      // Paths.compile answers the payload root for an empty path, and a fact's root is never a
      // number -- so this would compile to a policy that can never find a time and never evicts.
      throw new IllegalArgumentException("timeField must name a field, e.g. 'at' or 'event.at'");
    }
    this.path = Paths.compile(timeField);
    if (span <= 0) {
      /*
       * Positive, not merely non-negative, which is the same call §2.5's third amendment makes for
       * a temporal join's `within` and for the same reason: a span of zero keeps only the facts
       * sharing the newest timestamp exactly, and there is no reading under which somebody meant
       * that. A caller who wants to keep nothing is not asking for a window.
       */
      throw new IllegalArgumentException("span must be positive, was " + span);
    }
    this.span = BigDecimal.valueOf(span);
  }

  @Override
  public List<FactHandle> selectVictims(final EvictionView view) {
    /*
     * One pass, materialised, rather than two lazy ones -- and the reason is what a walk costs
     * here rather than tidiness. SessionEvictor dereferences each fact through working memory, and
     * under strict mode (§7.5) that is a payload deep copy; strict also calls this method twice and
     * compares. A second walk would make that four full copies of the retained population per
     * insert, which is the cost SessionEvictor's own per-type split exists to avoid paying.
     *
     * The pairs hold the time as well as the fact, so `timeOf` -- a pointer walk plus a
     * canonicalisation -- also runs once per fact rather than twice.
     */
    final List<Timed> timed = view.oldestOfType(factType)
        .map(fact -> new Timed(fact.handle(), timeOf(fact)))
        .toList();
    /*
     * The watermark is the newest time this type currently HOLDS, not the current instant. It is
     * derived from the view rather than from a clock, which is the whole of why this policy is
     * legal under §7.3: two runs over the same facts in the same order evict the same facts, on
     * every host and in every year. A TTL against System.currentTimeMillis() cannot say that, which
     * is why §4.4 names TTL first and this package ships none.
     *
     * "Currently holds" rather than "has ever been shown", and the difference is real: retracting
     * the newest fact of the type moves the watermark BACK, and retention widens. That is harmless
     * -- widening only keeps facts a rule might still match -- and it stays a pure function of the
     * view, which is the property that has to hold.
     */
    final BigDecimal watermark = timed.stream()
        .map(Timed::time)
        .flatMap(Optional::stream)
        .reduce(BigDecimal::max)
        .orElse(null);
    if (watermark == null) {
      // No fact of this type carries a usable time, so nothing here can be called old.
      return List.of();
    }
    final BigDecimal edge = watermark.subtract(span);
    /*
     * Strictly before the edge, so a fact sitting exactly on it survives. That matches the far edge
     * of a temporal join -- `before` holds when `other - within <= mine < other`, inclusive on the
     * far side -- and the direction of the mismatch is what matters: retention must be at least as
     * wide as the rule window it feeds, or the rule loses a match the author wrote.
     *
     * The order is `oldestOfType`'s, so the victims come back in recency order and the retract
     * order is the same on every host (§7.3). It cannot stop early: recency order is not time order
     * once a fact arrives late or is updated, so a fact inside the window says nothing about the
     * ones behind it -- and a fact that arrives ALREADY outside the window is selected on the
     * consultation it arrives in, which is the ordinary out-of-order case rather than an edge one.
     * See the fourth note on EvictionPolicy.window.
     */
    return timed.stream()
        .filter(entry -> entry.time().map(time -> time.compareTo(edge) < 0).orElse(false))
        .map(Timed::handle)
        .toList();
  }

  /**
   * A fact's handle beside the time this policy reads from it.
   *
   * @param handle the fact's handle
   * @param time its time, or empty when it carries none this policy will order
   */
  private record Timed(FactHandle handle, Optional<BigDecimal> time) {
  }

  /**
   * A fact's time, when it has one this policy will order.
   *
   * <p>A fact whose field is absent, non-numeric or non-finite is <strong>never evicted</strong>,
   * and is not allowed to move the watermark either. Declining is the safe direction: eviction is
   * the destructive act, and a policy that cannot prove a fact is old must not guess -- the same
   * shape as §3.3's rule that an index probe unable to prove itself safe declines rather than
   * returning too few candidates. The cost is a leak this policy will not plug: a stream of facts
   * of this type with no time field grows without bound, so pair it with
   * {@link EvictionPolicy#perType(java.util.Map)} where that is possible rather than a bug.
   *
   * @param fact the fact
   * @return its time as a decimal, or empty when it has none this policy can order
   */
  private Optional<BigDecimal> timeOf(final Fact fact) {
    final JsonNode value = fact.payload().at(path);
    // Through Canonical, so that 1000 and 1000.0 are one time and a NaN built in Java is none --
    // the same table the matching path orders timestamps with, rather than a second opinion here.
    return Canonical.orderable(value);
  }

  @Override
  public String toString() {
    return "window(" + factType + "." + timeField + ", " + span.toPlainString() + ")";
  }
}
