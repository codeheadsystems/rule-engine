package com.codeheadsystems.rules.dsl;

import java.util.List;

/**
 * Collects diagnostics, putting a location on each without every caller having to.
 *
 * <p>Threaded through the DSL compiler rather than returned from it, because the alternative --
 * every method returning either a result or an error -- makes "keep going and report everything"
 * the hard path instead of the default. Reporting every problem in one pass is a contract this
 * module inherits from {@code RuleCompilationException}, and it survives only if the plumbing
 * makes it easy.
 */
final class Diagnostics {

  private final SourceIndex index;
  private final List<DslDiagnostic> sink;
  private String ruleId;

  /**
   * Creates a collector over one file.
   *
   * @param index where everything in the file is
   * @param sink the list to append to
   */
  Diagnostics(final SourceIndex index, final List<DslDiagnostic> sink) {
    this.index = index;
    this.sink = sink;
  }

  /**
   * Sets the rule subsequent diagnostics belong to.
   *
   * @param id the rule id, or null for a file-level context
   */
  void inRule(final String id) {
    this.ruleId = id;
  }

  /**
   * Records a problem at a point in the document.
   *
   * @param error what kind of problem it is
   * @param pointer the JSON Pointer of the offending element
   * @param message what is wrong, naming the offending value
   */
  void error(final DslError error, final String pointer, final String message) {
    sink.add(DslDiagnostic.at(error, index.nearest(pointer), ruleId, message));
  }

  /**
   * How many problems have been recorded.
   *
   * @return the count, which a caller compares before and after to learn whether its own subtree
   *     produced anything
   */
  int count() {
    return sink.size();
  }
}
