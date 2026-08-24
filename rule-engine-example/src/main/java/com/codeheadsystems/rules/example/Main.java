package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.session.CompiledRuleSet;

/**
 * Runs the whole example: {@code ./gradlew :rule-engine-example:run}.
 *
 * <p>Four demos over one rule set and one feed of ten events, in the order they are worth reading:
 * the request-scoped session, the same thing across virtual threads, the long-lived streaming
 * session, and the tools for finding out why something did not fire.
 */
public final class Main {

  private Main() {
    throw new AssertionError("entry point");
  }

  /**
   * Entry point.
   *
   * @param args ignored
   */
  public static void main(final String[] args) {
    /*
     * Compiled once, before anything else, and shared by every session in every demo below --
     * including the ones on other threads. That is the two-tier split of §5.5 in one line: this
     * object is immutable and thread-safe, and a session is the cheap mutable thing you make per
     * unit of work.
     */
    final CompiledRuleSet rules = OrderRules.compile();
    System.out.printf("compiled %d rules, version %s%n%n",
        rules.rules().size(), rules.version());

    section("1. A session per order (the default shape)");
    PerOrderDemo.run(rules, new OpsPager());

    section("2. The same work across virtual threads");
    BatchDemo.run(rules);

    section("3. One long-lived streaming session");
    StreamingDemo.run(rules);

    section("4. Why did that not fire?");
    DiagnosticsDemo.run(rules);
  }

  /**
   * Prints a section heading.
   *
   * @param title the heading
   */
  private static void section(final String title) {
    System.out.printf("%n%s%n%s%n", title, "-".repeat(title.length()));
  }
}
