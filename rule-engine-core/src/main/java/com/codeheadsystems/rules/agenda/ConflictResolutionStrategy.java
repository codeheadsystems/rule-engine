package com.codeheadsystems.rules.agenda;

import com.codeheadsystems.rules.match.Activation;
import java.util.Comparator;

/**
 * Chooses which activation fires next (spec §4.2).
 *
 * <p>An implementation carries two obligations, and strict mode (§7.5) asserts both:
 *
 * <ul>
 *   <li><strong>It must be a total order</strong> -- never zero for two distinct activations.
 *   <li><strong>It must be consistent with {@link Activation#equals}</strong> -- zero exactly when
 *       the two activations are equal.
 * </ul>
 *
 * <p>This is not decoration. An agenda holds an ordered structure <em>and</em> a key-indexed one;
 * if the comparator could return zero for distinct activations, ordering would fall to internal
 * accident and break the determinism contract, and if it could return non-zero for equal ones, the
 * two structures would disagree about how many entries exist and the same match could be selected
 * twice.
 */
@FunctionalInterface
public interface ConflictResolutionStrategy extends Comparator<Activation> {

  /**
   * Orders two activations, most-eligible first.
   *
   * @param left the first activation
   * @param right the second activation
   * @return negative when {@code left} should fire first, positive when {@code right} should,
   *     zero only when the two are equal
   */
  @Override
  int compare(Activation left, Activation right);
}
