package com.codeheadsystems.rules.match;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.codeheadsystems.rules.rule.CompiledRule;
import java.util.Objects;

/**
 * A complete match: one rule plus the tuple that satisfied it, eligible to fire (spec §4.2).
 *
 * <p>A class rather than a record for two reasons. Its {@link #key()} is a derived field computed
 * once in the constructor, and a record cannot hold one without giving up the generated
 * constructor -- and that caching matters, because computing the key inside {@code hashCode()}
 * would allocate on every probe of the refraction set, one of the hottest maps in the engine.
 * Identity is the key alone, exactly as {@link Fact}'s identity is the handle alone.
 */
public final class Activation {

  private final CompiledRule rule;
  private final Tuple tuple;
  private final long recency;
  private final ActivationKey key;

  /**
   * Creates an activation, computing its key and its recency once.
   *
   * <p><strong>Recency is the maximum {@link Fact#recency()} across the bound facts.</strong> Two
   * readings were available -- when the match was found, or how fresh its facts are -- and the
   * classic engines use the second. It needs no snapshot store and no "captured when" refinement,
   * because a rule is dirty only when a fact it patterns was inserted, retracted or effectively
   * updated (§4.1), which is exactly the condition under which its activations' recency
   * <em>should</em> change. Recomputation therefore cannot silently re-rank a match on unrelated
   * traffic.
   *
   * @param rule the rule that matched
   * @param tuple the facts it matched
   * @param workingMemory the session's working memory, read to compute recency
   */
  public Activation(final CompiledRule rule, final Tuple tuple,
      final WorkingMemory workingMemory) {
    this.rule = Objects.requireNonNull(rule, "rule");
    this.tuple = Objects.requireNonNull(tuple, "tuple");
    this.key = new ActivationKey(rule.id(), tuple.boundFacts());
    long newest = Long.MIN_VALUE;
    for (int index = 0; index < tuple.size(); index++) {
      final long factRecency = workingMemory.get(new FactHandle(tuple.handleIdAt(index)))
          .map(Fact::recency)
          .orElse(Long.MIN_VALUE);
      newest = Math.max(newest, factRecency);
    }
    this.recency = newest;
  }

  /**
   * This match's identity, used for refraction, deactivation and equality.
   *
   * @return the key, computed once at construction
   */
  public ActivationKey key() {
    return key;
  }

  /**
   * The rule that matched.
   *
   * @return the compiled rule
   */
  public CompiledRule rule() {
    return rule;
  }

  /**
   * The facts that matched.
   *
   * @return the tuple
   */
  public Tuple tuple() {
    return tuple;
  }

  /**
   * How fresh this match's facts are.
   *
   * @return the maximum recency across the bound facts
   */
  public long recency() {
    return recency;
  }

  /**
   * Identity is the key alone.
   *
   * @param other the object to compare against
   * @return whether {@code other} is an activation with an equal key
   */
  @Override
  public boolean equals(final Object other) {
    return other instanceof Activation activation && activation.key.equals(key);
  }

  /**
   * Hashes the key, consistent with {@link #equals(Object)}.
   *
   * @return the key's hash
   */
  @Override
  public int hashCode() {
    return key.hashCode();
  }

  /**
   * A diagnostic rendering.
   *
   * @return the key plus the conflict-resolution inputs
   */
  @Override
  public String toString() {
    return key + "{salience=" + rule.salience() + ", recency=" + recency + "}";
  }
}
