package com.codeheadsystems.rules.compiler;

import com.codeheadsystems.rules.schema.FactSchemas;
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
  private final Set<String> declaredFactTypes;
  private final boolean checkFactTypes;
  private final FactSchemas factSchemas;

  private CompilerOptions(final Builder builder) {
    this.declaredFunctions = Set.copyOf(builder.declaredFunctions);
    this.checkFunctionNames = builder.checkFunctionNames;
    this.declaredFactTypes = Set.copyOf(builder.declaredFactTypes);
    this.checkFactTypes = builder.checkFactTypes;
    this.factSchemas = builder.factSchemas;
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

  /**
   * The fact types the host will insert, if the caller declared them.
   *
   * @return the declared types, or empty when the caller did not say
   */
  public Optional<Set<String>> declaredFactTypes() {
    return checkFactTypes ? Optional.of(declaredFactTypes) : Optional.empty();
  }

  /**
   * The fact-payload schemas to compile against (§2.3).
   *
   * @return the schemas, or {@link FactSchemas#none()} when the caller registered none
   */
  public FactSchemas factSchemas() {
    return factSchemas;
  }

  /** Builds {@link CompilerOptions}. */
  public static final class Builder {

    private final Set<String> declaredFunctions = new LinkedHashSet<>();
    private boolean checkFunctionNames;
    private final Set<String> declaredFactTypes = new LinkedHashSet<>();
    private boolean checkFactTypes;
    private FactSchemas factSchemas = FactSchemas.none();

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
     * Declares the fact types the host will insert, letting the compiler find unreachable rules.
     *
     * <p>§7.4 wants "rules with no reachable activation path, e.g. a pattern on a type nothing ever
     * inserts" in the report, and a compiler cannot know that on its own: which types arrive is a
     * property of the host, not of the rule set. Declaring them is what makes the question
     * answerable, and calling this method at all is what opts in -- exactly as
     * {@link #declaredFunctions(Set)} works, and for the same reason. Without it,
     * {@code report().unreachableRules()} is empty rather than guessed at.
     *
     * <p>Types a rule's own {@code insertFact} produces count as declared, since a rule set that
     * derives {@code RiskSignal} makes a rule matching {@code RiskSignal} reachable. Note the
     * consequence: an unreachable rule is a <em>warning</em> in the report, never an error, because
     * a host that inserts a type it forgot to declare would otherwise fail to compile.
     *
     * @param types the fact types the host inserts
     * @return this builder
     */
    public Builder declaredFactTypes(final Set<String> types) {
      this.declaredFactTypes.addAll(Objects.requireNonNull(types, "types"));
      this.checkFactTypes = true;
      return this;
    }

    /**
     * Registers fact-payload schemas, turning on §2.3's checks at both ends.
     *
     * <p>At compile time this makes a type-incompatible literal an error --
     * {@code { gt: "expensive" }} against a numeric field is a rule that could never match, and
     * §2.3 calls catching it "the single strongest argument for registering schemas on your
     * important fact types". At run time the same registry is frozen into the compiled rule set and
     * rejects a malformed payload at {@code insert} before it reaches the network.
     *
     * <p>The registry must be immutable and thread-safe; it is shared by every session the rule set
     * produces. See {@link FactSchemas} for why that is not negotiable.
     *
     * @param schemas the schemas
     * @return this builder
     */
    public Builder factSchemas(final FactSchemas schemas) {
      this.factSchemas = Objects.requireNonNull(schemas, "schemas");
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
