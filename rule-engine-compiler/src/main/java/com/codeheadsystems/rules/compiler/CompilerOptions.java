package com.codeheadsystems.rules.compiler;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How the compiler should behave.
 *
 * <p>A builder for the same reason session options are one: the Phase 5 additions -- a fact-payload
 * schema registry, a CEL cost budget, a rule-file API version -- land here, and adding a builder
 * method breaks nothing.
 */
public final class CompilerOptions {

  private final Set<String> declaredFunctions;
  private final boolean checkFunctionNames;

  private CompilerOptions(final Builder builder) {
    this.declaredFunctions = Set.copyOf(builder.declaredFunctions);
    this.checkFunctionNames = builder.checkFunctionNames;
  }

  /**
   * A fresh builder.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Options with every default.
   *
   * @return the default options
   */
  public static CompilerOptions defaults() {
    return builder().build();
  }

  /**
   * The host-function names rules may call, if the caller declared them.
   *
   * @return the declared names, or empty when name checking is off
   */
  public Optional<Set<String>> declaredFunctions() {
    return checkFunctionNames ? Optional.of(declaredFunctions) : Optional.empty();
  }

  /** Builds {@link CompilerOptions}. */
  public static final class Builder {

    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private boolean checkFunctionNames;

    /** Creates a builder carrying the defaults. */
    private Builder() {
      // Defaults are assigned inline.
    }

    /**
     * Declares the host-function names rules may call, making an unknown name a compile error.
     *
     * <p>§11.3 wants this: an unknown {@code callFunction} name should fail at compile time, not at
     * fire time, because a rule that calls a function nobody registered is a rule that will fail in
     * production on the one path that reaches it. Declaring the names is what makes the check
     * possible, and calling this method at all is what opts in -- without it, a name is resolved
     * when the action fires and an unknown one is a staging-phase failure.
     *
     * @param names the registered function names
     * @return this builder
     */
    public Builder declaredFunctions(final Set<String> names) {
      this.declaredFunctions.addAll(Objects.requireNonNull(names, "names"));
      this.checkFunctionNames = true;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public CompilerOptions build() {
      return new CompilerOptions(this);
    }
  }
}
