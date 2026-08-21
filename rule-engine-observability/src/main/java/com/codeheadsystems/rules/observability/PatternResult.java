package com.codeheadsystems.rules.observability;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What happened to one pattern of a rule (spec §7.2).
 *
 * <p>How many facts were considered, how many survived, and — the part that answers the question —
 * which constraint eliminated the rest.
 *
 * @param alias the pattern's binding name
 * @param factType the type it matches
 * @param considered how many facts of that type were in working memory
 * @param survivors the handles still matching after every single-fact constraint
 * @param firstFailure the constraint that eliminated the most candidates, if any were eliminated
 * @param joinNote what the cross-fact constraints did to the survivors, when there were any
 * @param note anything else that went wrong with this pattern -- a pinned fact that is no longer
 *     asserted, or one of the wrong type. §7.2's record has no place for these, and reporting them
 *     through {@code joinNote} would misdescribe them as a join outcome, which is a different and
 *     much more confusing failure
 */
public record PatternResult(String alias, String factType, int considered, List<Long> survivors,
    Optional<ConstraintFailure> firstFailure, Optional<String> joinNote, Optional<String> note) {

  /**
   * Canonical constructor. Defensively copies the survivor list.
   *
   * @param alias the binding name
   * @param factType the fact type
   * @param considered the population size
   * @param survivors the surviving handles
   * @param firstFailure the eliminating constraint, if any
   * @param joinNote the join outcome, if relevant
   * @param note anything else that went wrong
   */
  public PatternResult {
    Objects.requireNonNull(alias, "alias");
    Objects.requireNonNull(factType, "factType");
    Objects.requireNonNull(firstFailure, "firstFailure");
    Objects.requireNonNull(joinNote, "joinNote");
    Objects.requireNonNull(note, "note");
    survivors = List.copyOf(survivors);
  }

  /**
   * A one-line rendering.
   *
   * @return the pattern's outcome, in a form meant to be read top to bottom with its siblings
   */
  public String describe() {
    final StringBuilder text = new StringBuilder()
        .append(alias).append(": ").append(factType).append(" — ")
        .append(considered).append(" considered, ")
        .append(survivors.size()).append(" matched");
    firstFailure.ifPresent(failure -> text.append("; ").append(failure.describe()));
    joinNote.ifPresent(join -> text.append("; ").append(join));
    note.ifPresent(other -> text.append("; ").append(other));
    return text.toString();
  }
}
