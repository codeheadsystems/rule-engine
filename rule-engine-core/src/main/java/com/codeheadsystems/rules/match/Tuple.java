package com.codeheadsystems.rules.match;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.fact.WorkingMemory;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * One partial or complete match: an ordered binding of pattern aliases to fact <em>handles</em>
 * (spec §3.2.1).
 *
 * <p><strong>Never to {@code Fact} objects.</strong> An update replaces a fact's payload, so a
 * tuple holding {@code Fact} references would still point at the pre-update snapshot: an RHS
 * reading a field no rule tests would read the old value, a re-evaluated filter would read the old
 * value, and two tuples created either side of an update would disagree about the fact's contents.
 * Binding handles and dereferencing at read time makes that structurally impossible. The cost is
 * one lookup per field read at fire time, which is small next to a whole category of silent
 * staleness bugs.
 *
 * <p>{@code aliases} is shared per rule -- it is a property of the rule, not of the match -- so the
 * per-tuple cost is one {@code long[]}.
 */
public final class Tuple {

  private final long[] boundFacts;
  private final List<String> aliases;
  private final int hash;

  /**
   * Creates a tuple.
   *
   * @param boundFacts the bound handle ids, in pattern order. Copied defensively: this value is a
   *     hash key, and a mutable hash key something else can modify is a defect waiting for a
   *     long-running session
   * @param aliases the alias names, in pattern order. Shared, not copied: it belongs to the rule
   * @throws IllegalArgumentException if the two have different lengths
   */
  public Tuple(final long[] boundFacts, final List<String> aliases) {
    Objects.requireNonNull(boundFacts, "boundFacts");
    Objects.requireNonNull(aliases, "aliases");
    if (boundFacts.length != aliases.size()) {
      throw new IllegalArgumentException(
          "tuple binds " + boundFacts.length + " facts to " + aliases.size() + " aliases");
    }
    this.boundFacts = boundFacts.clone();
    this.aliases = aliases;
    this.hash = Arrays.hashCode(this.boundFacts);
  }

  /**
   * The bound handle ids.
   *
   * @return a copy, in pattern order
   */
  public long[] boundFacts() {
    return boundFacts.clone();
  }

  /**
   * The alias names.
   *
   * @return the aliases, in pattern order
   */
  public List<String> aliases() {
    return aliases;
  }

  /**
   * How many facts this tuple binds.
   *
   * @return the arity
   */
  public int size() {
    return boundFacts.length;
  }

  /**
   * The handle id bound at one position.
   *
   * @param index the pattern position
   * @return the handle id
   */
  public long handleIdAt(final int index) {
    return boundFacts[index];
  }

  /**
   * The handle bound to an alias.
   *
   * @param alias the alias name
   * @return the handle
   * @throws NoSuchElementException if the alias is not bound by this tuple
   */
  public FactHandle handleOf(final String alias) {
    final int index = aliases.indexOf(alias);
    if (index < 0) {
      throw new NoSuchElementException("alias '" + alias + "' is not bound by this tuple");
    }
    return new FactHandle(boundFacts[index]);
  }

  /**
   * Dereferences one binding through working memory.
   *
   * <p>This is where invariant 3 is cashed in: the payload comes from the one place it lives, at
   * the moment it is read.
   *
   * @param alias the alias name
   * @param workingMemory the session's working memory
   * @return the payload of the fact bound to {@code alias}
   * @throws NoSuchElementException if the alias is not bound, or its fact is no longer asserted
   */
  public JsonNode payloadOf(final String alias, final WorkingMemory workingMemory) {
    final FactHandle handle = handleOf(alias);
    return workingMemory.get(handle)
        .map(Fact::payload)
        .orElseThrow(() -> new NoSuchElementException(
            "alias '" + alias + "' binds handle " + handle.id() + ", which is no longer asserted"));
  }

  /**
   * Value equality over the bound handles.
   *
   * @param other the object to compare against
   * @return whether {@code other} is a tuple binding the same handles in the same order
   */
  @Override
  public boolean equals(final Object other) {
    return other instanceof Tuple tuple && Arrays.equals(boundFacts, tuple.boundFacts);
  }

  /**
   * Hashes the bound handles, consistent with {@link #equals(Object)}.
   *
   * @return the hash
   */
  @Override
  public int hashCode() {
    return hash;
  }

  /**
   * A diagnostic rendering pairing each alias with its handle id.
   *
   * @return e.g. {@code [o=#3, c=#7]}
   */
  @Override
  public String toString() {
    final StringBuilder text = new StringBuilder("[");
    for (int index = 0; index < boundFacts.length; index++) {
      if (index > 0) {
        text.append(", ");
      }
      text.append(aliases.get(index)).append("=#").append(boundFacts[index]);
    }
    return text.append(']').toString();
  }
}
