package com.codeheadsystems.rules.concurrent;

import java.util.Objects;
import java.util.Optional;

/**
 * What happened to one batch: a result, or the exception that stopped it (spec §5.2).
 *
 * <p>This type exists because §5.2 refuses to make the decision for you: one task's exception
 * surfaces at its own {@code get()} while the others keep running, and "you must decide what a
 * partial batch result means before you ship it". A helper that threw on the first failure would
 * throw away every sibling's work; one that swallowed failures would report success for a batch that
 * never ran. So both are returned and the caller decides which their domain calls for.
 *
 * @param <T> the result type
 * @param index the batch's position in the submitted list, so a failure can be traced back to its
 *     input -- results come back in submission order, but that is easy to rely on accidentally and
 *     the index makes it explicit
 * @param value the result, or empty if the batch failed
 * @param failure the exception, or empty if the batch succeeded
 */
public record BatchOutcome<T>(int index, Optional<T> value, Optional<Throwable> failure) {

  /**
   * Creates an outcome. Exactly one of {@code value} and {@code failure} must be present.
   *
   * @param index the batch's position
   * @param value the result, if it succeeded
   * @param failure the exception, if it failed
   */
  public BatchOutcome {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(failure, "failure");
    if (value.isPresent() == failure.isPresent()) {
      throw new IllegalArgumentException("exactly one of value and failure must be present");
    }
  }

  /**
   * Whether this batch completed.
   *
   * @return {@code true} if a result is present
   */
  public boolean succeeded() {
    return value.isPresent();
  }
}
