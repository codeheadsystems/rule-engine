package com.codeheadsystems.rules.rule;

import com.codeheadsystems.rules.access.FieldAccessor;
import tools.jackson.databind.JsonNode;
import com.google.re2j.Pattern;
import java.util.Objects;

/**
 * A compiled {@link Operator#MATCHES} constraint (spec §2.6.3).
 *
 * <p>The pattern is compiled once, in the compiler pipeline, and cached here exactly like a
 * {@code JsonPointer} -- never per fact, per cycle. RE2 {@code Pattern} is immutable and
 * shareable; its {@code Matcher} is not, so one is allocated per evaluation and never cached.
 *
 * <p><strong>Substring semantics, not full-match.</strong> The pattern matches if it is found
 * <em>anywhere</em> in the value, so {@code { matches: "example" }} matches
 * {@code "not-example-really"}. The spec does not state which reading it intends -- §6.2.1's own
 * example is explicitly anchored ({@code ^...$}), which is consistent with either -- and other
 * engines differ, so it is pinned here rather than left for an author to discover. Anchor the
 * pattern when you mean the whole value.
 *
 * <p>A non-textual value never matches, which is §2.6.1's cross-type rule: {@code matches} against
 * a number is false, not a coercion.
 *
 * @param source the constraint this was compiled from
 * @param accessor the precompiled accessor for the constraint's field
 * @param pattern the precompiled RE2 program
 */
public record RegexTest(FieldConstraint source, FieldAccessor accessor, Pattern pattern)
    implements AlphaTest {

  /**
   * Canonical constructor.
   *
   * @param source the constraint
   * @param accessor the precompiled accessor
   * @param pattern the precompiled RE2 program
   */
  public RegexTest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(accessor, "accessor");
    Objects.requireNonNull(pattern, "pattern");
  }

  @Override
  public Constraint constraint() {
    return source;
  }

  @Override
  public boolean test(final JsonNode payload) {
    final JsonNode value = accessor.get(payload);
    return value.isString() && pattern.matcher(value.stringValue()).find();
  }
}
