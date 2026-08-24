package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.cel.CelExpressions;
import com.codeheadsystems.rules.compiler.CompilerOptions;
import com.codeheadsystems.rules.dsl.RuleFiles;
import com.codeheadsystems.rules.dsl.RuleSource;
import com.codeheadsystems.rules.session.CompiledRuleSet;
import com.codeheadsystems.rules.session.MatchingStrategy;
import com.codeheadsystems.rules.session.SessionOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles the rule set. Once, at startup.
 *
 * <p>A {@link CompiledRuleSet} is immutable, thread-safe and shared by everything (§5.5) -- so
 * compiling per request is not merely wasteful, it throws away the whole two-tier design. Sessions
 * are the cheap per-unit-of-work thing; this is not.
 *
 * <p>The interesting part is {@link CompilerOptions}: all three settings below exist to turn a
 * runtime surprise into a compile error.
 *
 * <ul>
 *   <li>{@code declaredFunctions} makes a typo in a {@code callFunction} name a compile failure
 *       rather than an exception on the one code path that reaches it, months later.
 *   <li>{@code declaredFactTypes} finds rules nothing can ever activate -- almost always a fact type
 *       spelled one way in the rule file and another way in {@link Ingest}.
 *   <li>{@code expressions} registers §6.4's escape hatch. Without it, rule 7's {@code condition:}
 *       is a compile error rather than a silently skipped constraint, which is the right way round.
 * </ul>
 */
public final class OrderRules {

  /** The rule file, on the classpath. */
  public static final String RESOURCE = "rules/orders.yaml";

  /**
   * Every fact type this application inserts, whether from {@link Ingest} or from a rule's
   * {@code insertFact}.
   *
   * <p>Declaring the derived ones matters as much as the ingested ones: {@code OrderUnpaid} is
   * concluded by rule 2 and patterned by rule 6, and a rule set where those two spellings drift
   * apart compiles cleanly and fires nothing.
   */
  public static final Set<String> FACT_TYPES =
      Set.of("Customer", "Order", "LineItem", "Payment", "OrderUnpaid", "Discount");

  /** Every name a {@code callFunction} may use. */
  public static final Set<String> FUNCTIONS = Set.of("alertOps");

  private OrderRules() {
    throw new AssertionError("static helper");
  }

  /**
   * Reads and compiles the rule file.
   *
   * @return the compiled rule set, ready to be shared across every session and every thread
   * @throws com.codeheadsystems.rules.dsl.RuleFileException if the file will not parse
   * @throws com.codeheadsystems.rules.compiler.RuleCompilationException if it parses but does not
   *     compile; every diagnostic carries the file, line and column that caused it
   */
  public static CompiledRuleSet compile() {
    return RuleFiles.compile(List.of(RuleSource.yaml(RESOURCE, read(RESOURCE))),
        CompilerOptions.builder()
            .declaredFunctions(FUNCTIONS)
            .declaredFactTypes(FACT_TYPES)
            /*
             * §6.4's escape hatch, and the one dependency in this example a real service should
             * think twice about: -cel brings protobuf, guava and antlr. Registering it is two lines
             * precisely because the cost is in the build file rather than here -- a rule set with no
             * `condition:` and no `$expr` should not have either.
             */
            .expressions(CelExpressions.create())
            .build());
  }

  /**
   * The session configuration this application uses.
   *
   * <p>One options object is reused across sessions, which is supported and is what
   * {@code RuleBatches} does -- with the obligation that anything mutable it holds is then shared.
   * {@code alertOps} is the mutable thing here, and {@link OpsPager} meets the obligation rather
   * than documenting it away.
   *
   * @param pager the handler {@code callFunction: alertOps} dispatches to
   * @param matching which matcher to use. {@code NETWORK} is the default and the right answer for a
   *     session that is created, filled, fired and closed; {@code RETE} is for the long-lived
   *     streaming session, where the join is worth maintaining across cycles
   * @return the options
   */
  public static SessionOptions options(final OpsPager pager, final MatchingStrategy matching) {
    Objects.requireNonNull(pager, "pager");
    Objects.requireNonNull(matching, "matching");
    return SessionOptions.builder()
        .function("alertOps", pager)
        .matching(matching)
        .build();
  }

  /**
   * Reads a classpath resource as text.
   *
   * @param resource the resource path
   * @return its contents
   * @throws UncheckedIOException if it is missing or unreadable
   */
  static String read(final String resource) {
    try (InputStream stream = OrderRules.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IllegalStateException("classpath resource missing: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read " + resource, failed);
    }
  }
}
