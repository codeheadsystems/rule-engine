package com.codeheadsystems.rules.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What {@code -core} promises a consumer, and what it merely exposes to a sibling package.
 *
 * <p><strong>Java has no {@code internal}.</strong> A type one package needs from another has to be
 * {@code public}, and a consumer cannot tell that apart from the API. §2.5's three quantifiers alone
 * made six types and three members public purely so a sibling could reach them --
 * {@code Negations}, {@code Universals}, {@code Accumulators}, {@code Conditions},
 * {@code TupleMatch}, {@code Scan}, plus {@code Canonical.orderable},
 * {@code RefractionMemory.forget} and {@code Agenda.reactivate}. Every one was right locally and
 * none of them is API. Before a first publish that costs nothing; after one, each is a compatibility
 * surface for the life of the artifact.
 *
 * <p>A JPMS {@code module-info} with qualified {@code exports … to} clauses is the mechanism that
 * would say it, and §8.1 records why this project does not write one yet -- <em>not</em> the reading
 * a reader arrives at unaided. re2j can be required: the JDK derives an automatic name from the
 * filename. What blocks it is that a published descriptor must not depend on a name the library
 * never promised, and that dev.cel splits packages across jars, which no naming convention fixes.
 * Read §8.1 before changing anything here; {@link WhyNotJpms} pins the one fact that would reopen
 * the question.
 *
 * <p>So the boundary is enforced here instead, in the idiom this project already uses for the things
 * a compiler cannot check: a test that fails. It is weaker than javac in one specific way -- it
 * binds this repository rather than a consumer's -- and stronger in another: it makes widening the
 * surface a reviewed edit to a named list rather than the invisible side effect of adding
 * {@code public} to reach a sibling.
 */
class ApiSurfaceTest {

  /** Where {@code -core}'s packages live, relative to a module's project directory. */
  private static final Path CORE = Path.of("..", "rule-engine-core", "src", "main", "java",
      "com", "codeheadsystems", "rules");

  /** The prefix every {@code -core} package shares. */
  private static final String ROOT = "com.codeheadsystems.rules.";

  /**
   * Any reference to {@code com.codeheadsystems.rules.<package>.<Type>}, imported or written out.
   *
   * <p>Not anchored to {@code import}, because a fully-qualified reference reaches a package just as
   * effectively and {@code CompiledRuleSet.network()} shows the style is already in use here. An
   * import-only check would have been a boundary anyone could step over by accident. It follows that
   * a {@code {@link com.codeheadsystems.rules.truth.Justifications}} in a sibling's Javadoc counts as
   * reaching {@code truth}, and that is intended: a doc link is a reference a consumer can read and
   * a rename can break, and the answer to one is the same as the answer to an import -- say so in
   * the table, or do not write it.
   *
   * <p>The {@code [A-Z]} is load-bearing: it is what distinguishes a package-then-Type from a
   * package-then-subpackage, so {@code rules.schema.networknt.X} does not read as a reference to a
   * package called {@code schema.networknt}. The rest of the type name is matched so that
   * {@code group()} is the whole reference, which is what an allowance has to name.
   */
  private static final Pattern CORE_REFERENCE =
      Pattern.compile("com\\.codeheadsystems\\.rules\\.(\\w+)\\.[A-Z]\\w*");

  /**
   * The contract: {@code -core} packages a consumer may name.
   *
   * <p>These are what a {@code module-info} would export unqualified -- the fact model, the rule AST
   * an author builds, sessions, §7.1's listeners, §4.4's eviction policy, §7.4's report, §4.6's
   * right-hand-side hooks, §8's concurrency helpers, and the two SPIs an optional module implements.
   */
  private static final Set<String> EXPORTED = Set.of(
      "concurrent", "evict", "expr", "fact", "listener", "report", "rhs", "rule", "schema",
      "session",
      /*
       * These two are contract because an EXPORTED signature names them, which the first draft of
       * this table got wrong -- it listed them internal while a consumer could not implement a
       * listener or walk a compiled rule without them:
       *   access -- FieldAccessor and JsonPointerAccessor are components of the compiled rule AST
       *             (AlphaTest.accessor(), JoinTest, CompiledAccumulate). Paths sits beside them
       *             and is contract for a different reason: it defines the dotted-path-to-pointer
       *             mapping any front end has to reproduce, and -dsl is the standing proof that
       *             front ends exist outside the compiler. Both -compiler and -dsl call it.
       *   match  -- Activation and ActivationKey are named by §7.1's RuleEngineListener, FireRecord
       *             and RhsErrorHandler, and ConflictResolutionStrategy is §4.2's documented plug
       *             point, taken by SessionOptions.conflictResolution
       */
      "access", "match");

  /**
   * Which internal packages each module's <em>main</em> source may reach into.
   *
   * <p>This is the {@code exports … to} table, and every entry is a claim that the package is
   * public only because this module needs it. Adding one is a design decision; the point of the
   * test is that it cannot happen by accident.
   */
  private static final Map<String, Set<String>> INTERNAL_ACCESS = Map.of(
      "rule-engine-compiler", Set.of("network", "value"),
      "rule-engine-dsl", Set.of(),
      "rule-engine-observability", Set.of("eval", "value"),
      // The testkit reaches for nothing internal, which is worth noticing rather than assuming:
      // it is the module a consumer is most likely to copy from, so what it needs is a fair
      // proxy for what the contract has to cover.
      "rule-engine-testkit", Set.of(),
      "rule-engine-cel", Set.of(),
      "rule-engine-schema", Set.of());

  /**
   * Internal packages no other module reaches at all: {@code -core} talking to itself.
   *
   * <p>Listed rather than inferred, and an earlier version inferred it. "No sibling imports this"
   * was treated as proof a package is internal, which made the classification test unable to fail:
   * a brand-new {@code -core} package sailed through it unclassified, and so would
   * {@code truth}, which is exactly the kind this list is for.
   *
   * <p>{@code agenda} is here because the second draft of the table exported it whole, to reach the
   * one plug point it held. That published {@code Agenda}'s twelve members and
   * {@code RefractionMemory}'s mutating session state -- including {@code Agenda.reactivate} and
   * {@code RefractionMemory.forget}, two of the nine things §8.1 names as <em>not</em> API. The
   * granularity is the package, so the answer was to move
   * {@code ConflictResolutionStrategy} beside the {@code Activation} it compares, the same split
   * {@code eval} got.
   */
  private static final Set<String> INTERNAL_ONLY = Set.of("agenda", "naive", "rete", "truth");

  /**
   * Signatures allowed to name an internal package: a debt rather than a decision.
   *
   * <p>Three entries, one debt -- §8.1 records it in prose: {@code Network} reaches the contract
   * through {@code CompiledRuleSet}, so it appears on the interface method, on the implementation's
   * override, and on the constructor {@code -compiler} calls to hand the graph over. Nothing outside
   * this repository needs any of them; §7.4's {@code CompilerReport} is the supported introspection,
   * and the only callers are {@code -core} and the testkit's white-box structural tests.
   *
   * <p><strong>The allowance names the offending type, not merely the member, and the first version
   * did not.</strong> Keyed as {@code fully.qualified.Owner.member} alone, it waved through
   * <em>anything</em> that member went on to name: a review demonstrated it by adding a
   * {@code truth.Justifications} parameter to a method called {@code network} and watching the suite
   * stay green. The six-parameter constructor was the sharp case -- a blanket suppression of an
   * entire public signature. An allowance whose scope is wider than the debt it records is the
   * shape this whole pass exists to correct, so it is a pair: this member may name these types, and
   * nothing else. A set rather than a single name because a member could need two, and finding that
   * out later would mean changing the shape rather than adding a line.
   */
  private static final Map<String, Set<String>> ALLOWED_LEAKS = Map.of(
      "com.codeheadsystems.rules.session.CompiledRuleSet.network",
      Set.of("com.codeheadsystems.rules.network.Network"),
      "com.codeheadsystems.rules.session.DefaultCompiledRuleSet.network",
      Set.of("com.codeheadsystems.rules.network.Network"),
      "com.codeheadsystems.rules.session.DefaultCompiledRuleSet.<init>",
      Set.of("com.codeheadsystems.rules.network.Network"));

  /**
   * Every sibling module, read off {@code settings.gradle.kts}.
   *
   * <p>The table's own keys are not a safe source: a module missing from it, or misspelled in it,
   * was simply never read -- {@code coreImportsUnder} answers empty for a directory that does not
   * exist, so every assertion passed. Renaming one key to {@code rule-engine-celTYPO} left the
   * suite green while that module went unchecked. The build file rather than a directory listing
   * because that is what actually defines the set; a stale {@code rule-engine-something.bak}
   * checkout is not a module and should not fail a test about the API.
   *
   * @return the module directory names, in a stable order
   */
  private static Set<String> modules() {
    // Anchored to the line start, so an include named inside one of that file's comments -- and it
    // is a heavily commented file -- is not read as a module.
    final Matcher matcher = Pattern.compile("(?m)^include\\(\"(rule-engine-[\\w-]+)\"\\)")
        .matcher(read(Path.of("..", "settings.gradle.kts")));
    final Set<String> found = new TreeSet<>();
    while (matcher.find()) {
      if (!matcher.group(1).equals("rule-engine-core")) {
        found.add(matcher.group(1));
      }
    }
    return found;
  }

  /** Every {@code -core} package, read off the source tree rather than listed twice. */
  private static Set<String> corePackages() {
    try (Stream<Path> entries = Files.list(CORE)) {
      return entries.filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .collect(Collectors.toCollection(TreeSet::new));
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read -core's package list", failed);
    }
  }

  /**
   * The {@code -core} packages one source tree imports, by package name.
   *
   * <p>Filtered against the real package list, because {@code com.codeheadsystems.rules.compiler}
   * and {@code .dsl} are sibling <em>modules</em> whose names sit in the same namespace as
   * {@code -core}'s packages. Matching on the prefix alone reported a module importing itself.
   */
  private static Set<String> coreImportsUnder(final Path directory) {
    final Set<String> packages = corePackages();
    final Set<String> found = new TreeSet<>();
    if (!Files.isDirectory(directory)) {
      return found;
    }
    try (Stream<Path> files = Files.walk(directory)) {
      files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
        final Matcher matcher = CORE_REFERENCE.matcher(read(path));
        while (matcher.find()) {
          if (packages.contains(matcher.group(1))) {
            found.add(matcher.group(1));
          }
        }
      });
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot walk " + directory, failed);
    }
    return found;
  }

  /**
   * Reads a file.
   *
   * @param path the file
   * @return its text
   */
  private static String read(final Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read " + path, failed);
    }
  }

  @Nested
  @DisplayName("the exported surface")
  class Exported {

    @Test
    @DisplayName("names every -core package exactly once")
    void everyPackageIsClassified() {
      /*
       * Read off the tree and checked against the union of the three lists, so a NEW package is
       * unclassified until somebody says which it is. An earlier version accepted "no sibling
       * imports it" as proof of internality, which meant it could not fail: a fresh package passed,
       * and so would `truth` have. The point of a classification test is that adding a package is a
       * decision, and a decision is something a person makes.
       */
      final Set<String> classified = new TreeSet<>(EXPORTED);
      INTERNAL_ACCESS.values().forEach(classified::addAll);
      classified.addAll(INTERNAL_ONLY);

      assertThat(corePackages())
          .describedAs("a -core package this test does not classify is one nobody decided about."
              + " EXPORTED if a consumer should name it; INTERNAL_ACCESS if a sibling module needs"
              + " it; INTERNAL_ONLY if it is -core talking to itself -- and if none of the three"
              + " fits, its types may not need to be public at all")
          .allSatisfy(pkg -> assertThat(classified)
              .describedAs("package '%s'", pkg)
              .contains(pkg));
    }

    @Test
    @DisplayName("says one thing about each: no package is both exported and internal")
    void nothingIsBothExportedAndInternal() {
      // Three-way, and it was two-way first: `truth` could sit in EXPORTED and INTERNAL_ONLY at
      // once with every assertion still green, because the classification test only asks whether a
      // package appears SOMEWHERE. A table that says two things about one package is the failure
      // this exists to catch.
      final Set<String> internal = new TreeSet<>(INTERNAL_ONLY);
      INTERNAL_ACCESS.values().forEach(internal::addAll);

      assertThat(internal)
          .describedAs("a package on both lists. Decide which it is")
          .doesNotContainAnyElementsOf(EXPORTED);
    }

    @Test
    @DisplayName("does not leak an internal type through an exported signature")
    void noExportedSignatureNamesAnInternalType() {
      /*
       * The mechanism, rather than the answer. Everything else here asks whether the TABLE is
       * consistent; this asks whether it is TRUE, and it is the check whose absence let the first
       * draft classify three packages wrongly -- `match` was called internal while
       * RuleEngineListener named Activation, and no test could see it because nothing read -core's
       * own signatures.
       *
       * Reflection rather than the regex above, deliberately: this is a question about declared
       * types, and Javadoc, comments and method bodies are all noise to it. `Comparator<Activation>`
       * and `Optional<Tuple>` are found because it reads the GENERIC type name, which is the form a
       * consumer's compiler sees.
       */
      final Map<String, Set<String>> leaks = leaksByMember();
      ALLOWED_LEAKS.forEach((member, allowed) -> {
        final Set<String> named = leaks.get(member);
        if (named != null && named.removeAll(allowed) && named.isEmpty()) {
          leaks.remove(member);
        }
      });

      assertThat(leaks)
          .describedAs("an exported signature names a type from a package the contract does not"
              + " include, so a consumer cannot use it without reaching somewhere this table calls"
              + " internal. Either the named type belongs in the contract -- move it to an EXPORTED"
              + " package, as ConflictResolutionStrategy was -- or the signature should not be"
              + " public. Adding it to ALLOWED_LEAKS is the third answer and it is a debt")
          .isEmpty();
    }

    @Test
    @DisplayName("carries no allowance for a signature that has stopped leaking")
    void everyAllowanceIsStillNeeded() {
      /*
       * The reverse assertion, which the module table has had all along and this list did not: a
       * grant left behind after the code that needed it moved reads as a boundary somebody chose,
       * when nobody did. A review demonstrated the gap by adding an allowance for a type and member
       * that do not exist and watching the suite stay green.
       *
       * It is not hypothetical here. §8.1 states the plan -- removing `network()` from the contract
       * is pre-publish work this pass did not do -- so all three entries are expected to go stale
       * together, at a known future moment. When they do, this fails and the paragraph in §8.1 goes
       * with them.
       */
      final Map<String, Set<String>> leaks = leaksByMember();
      final Map<String, Set<String>> stale = new TreeMap<>();
      ALLOWED_LEAKS.forEach((member, allowed) -> {
        final Set<String> unmatched = new TreeSet<>(allowed);
        unmatched.removeAll(leaks.getOrDefault(member, Set.of()));
        if (!unmatched.isEmpty()) {
          stale.put(member, unmatched);
        }
      });

      assertThat(stale)
          .describedAs("an allowance for a signature that no longer names that type. Delete it --"
              + " and if it was the last one, the paragraph in §8.1 that records the debt goes with"
              + " it, because the debt is paid")
          .isEmpty();
    }

    /**
     * Every internal type named by an exported signature, before allowances are applied.
     *
     * @return fully-qualified {@code Owner.member} to the internal types it names
     */
    private Map<String, Set<String>> leaksByMember() {
      final Map<String, Set<String>> leaks = new TreeMap<>();
      for (final String pkg : EXPORTED) {
        for (final Class<?> type : publicTypesIn(pkg)) {
          collectLeaks(type, leaks);
        }
      }
      return leaks;
    }

    @Test
    @DisplayName("reads every exported type, so the previous test means something")
    void theSignatureScanActuallyLoadsTypes() {
      /*
       * The scan resolves class names built from file names. A rename of the source layout, or a
       * classpath without -core, would leave it silently finding nothing and passing -- the exact
       * shape of vacuity this pass has now found three times.
       *
       * Counted against the source tree rather than against a number somebody wrote down. A
       * threshold ages: `> 50` was comfortable against roughly a hundred types and would have sat
       * next to its own limit the day a package moved. This cannot drift -- every file declaring a
       * public top-level type must have produced at least one loaded class, and nested types only
       * add.
       */
      final int declared = EXPORTED.stream().mapToInt(this::publicSourceFilesIn).sum();
      final int scanned = EXPORTED.stream().mapToInt(pkg -> publicTypesIn(pkg).size()).sum();

      assertThat(scanned)
          .describedAs("every exported source file declaring a public top-level type should have"
              + " resolved to a class; finding fewer means the scan is not reading what it thinks"
              + " it is, and the leak check above was that much less true")
          .isGreaterThanOrEqualTo(declared);
    }

    /**
     * How many source files in a package declare a public top-level type.
     *
     * @param pkg the simple package name
     * @return the count
     */
    private int publicSourceFilesIn(final String pkg) {
      final Pattern declaration = Pattern.compile(
          "(?m)^public (?:\\w+ )*(?:class|interface|record|enum) ");
      try (Stream<Path> files = Files.list(CORE.resolve(pkg))) {
        return (int) files
            .filter(path -> path.getFileName().toString().endsWith(".java"))
            .filter(path -> declaration.matcher(read(path)).find())
            .count();
      } catch (final IOException failed) {
        throw new UncheckedIOException("cannot list package " + pkg, failed);
      }
    }

    /**
     * The public types a {@code -core} package declares, nested ones included.
     *
     * @param pkg the simple package name under {@code com.codeheadsystems.rules}
     * @return every public class, interface, record or enum it declares
     */
    private List<Class<?>> publicTypesIn(final String pkg) {
      final List<Class<?>> found = new ArrayList<>();
      try (Stream<Path> files = Files.list(CORE.resolve(pkg))) {
        files.map(path -> path.getFileName().toString())
            .filter(name -> name.endsWith(".java") && !name.equals("package-info.java"))
            .sorted()
            .forEach(name -> {
              final Class<?> type = load(ROOT + pkg + "." + name.substring(0, name.length() - 5));
              if (type != null && Modifier.isPublic(type.getModifiers())) {
                collectNested(type, found);
              }
            });
      } catch (final IOException failed) {
        throw new UncheckedIOException("cannot list package " + pkg, failed);
      }
      return found;
    }

    /**
     * Adds a type and every public or protected type nested inside it.
     *
     * @param type the outer type
     * @param into the list to add to
     */
    private void collectNested(final Class<?> type, final List<Class<?>> into) {
      into.add(type);
      for (final Class<?> nested : type.getDeclaredClasses()) {
        final int modifiers = nested.getModifiers();
        if (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) {
          collectNested(nested, into);
        }
      }
    }

    /**
     * Loads a class without initialising it.
     *
     * @param name the binary name
     * @return the class, or null when the file does not declare a top-level type of that name
     */
    private Class<?> load(final String name) {
      try {
        return Class.forName(name, false, ApiSurfaceTest.class.getClassLoader());
      } catch (final ClassNotFoundException absent) {
        return null;
      }
    }

    /**
     * Records every internal {@code -core} type this type names in a public or protected signature.
     *
     * <p><strong>Where this stops, stated so it is not over-trusted.</strong> It reads supertypes,
     * record components, fields, methods, constructors, and their generic parameter, return,
     * exception and type-variable-bound types. It does not read annotations or permits clauses --
     * the second for a reason given at the point it would have gone -- and
     * it scans exported packages only -- a granted internal package whose public signature names an
     * ungranted one would push a module somewhere the table does not send it, with no import for
     * {@code coreImportsUnder} to see. Narrow today, {@code network} and {@code value} being the
     * only grants, and the same argument would apply one tier down.
     *
     * @param type the exported type
     * @param into the map of fully-qualified {@code Owner.member} to the internal types it names
     */
    private void collectLeaks(final Class<?> type, final Map<String, Set<String>> into) {
      final String owner = type.getName();
      check(owner, "extends/implements", type.getGenericSuperclass(), into);
      for (final Type each : type.getGenericInterfaces()) {
        check(owner, "extends/implements", each, into);
      }
      /*
       * A permits clause is NOT read, and the reason is worth writing down because the opposite
       * looks obviously right: a permitted subtype is reachable through none of the other routes,
       * and a consumer must be able to name one to write an exhaustive switch. Six exported types
       * here are sealed. But JLS 8.1.6 closes it -- a sealed type in the unnamed module may only
       * permit subtypes in its OWN package, and javac says so directly ("class Constraint in
       * unnamed module cannot extend a sealed class in a different package"). A cross-package
       * permits becomes expressible the day -core carries a module-info, and on that day this class
       * is deleted in favour of the module-info (§8.1). Reading it now would be a check nothing
       * could ever trip -- which is the failure mode this file has already been through three
       * times.
       */
      checkTypeParameters(owner, "<T>", type.getTypeParameters(), into);
      if (type.isRecord()) {
        for (final RecordComponent component : type.getRecordComponents()) {
          check(owner, component.getName(), component.getGenericType(), into);
        }
      }
      for (final Field field : type.getDeclaredFields()) {
        if (visible(field.getModifiers())) {
          check(owner, field.getName(), field.getGenericType(), into);
        }
      }
      for (final Method method : type.getDeclaredMethods()) {
        if (visible(method.getModifiers()) && !method.isSynthetic()) {
          check(owner, method.getName(), method.getGenericReturnType(), into);
          checkParameters(owner, method.getName(), method, into);
        }
      }
      for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
        if (visible(constructor.getModifiers())) {
          checkParameters(owner, "<init>", constructor, into);
        }
      }
    }

    /**
     * Checks the bounds of a type variable list.
     *
     * <p>{@code <T extends Tuple>} names an internal type as surely as a parameter does, and a
     * consumer has to satisfy the bound to call the method. Cheap to read and easy to forget, so
     * it is read.
     *
     * @param owner the declaring type's simple name
     * @param member the member the variables belong to
     * @param variables the type variables
     * @param into the map to record into
     */
    private void checkTypeParameters(final String owner, final String member,
        final TypeVariable<?>[] variables, final Map<String, Set<String>> into) {
      for (final TypeVariable<?> variable : variables) {
        for (final Type bound : variable.getBounds()) {
          check(owner, member, bound, into);
        }
      }
    }

    /**
     * Checks a method or constructor's parameters and declared exceptions.
     *
     * @param owner the declaring type's simple name
     * @param member the member's name
     * @param executable the method or constructor
     * @param into the map to record into
     */
    private void checkParameters(final String owner, final String member,
        final Executable executable, final Map<String, Set<String>> into) {
      for (final Type each : executable.getGenericParameterTypes()) {
        check(owner, member, each, into);
      }
      for (final Type each : executable.getGenericExceptionTypes()) {
        check(owner, member, each, into);
      }
      checkTypeParameters(owner, member, executable.getTypeParameters(), into);
    }

    /**
     * Whether a member is part of the surface a consumer sees.
     *
     * @param modifiers the member's modifiers
     * @return true for public and protected members; protected counts because a consumer can
     *     subclass an exported type and see it
     */
    private boolean visible(final int modifiers) {
      return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    /**
     * Records the reference if it names a {@code -core} package the contract does not include.
     *
     * @param owner the declaring type's simple name
     * @param member the member the type appears in
     * @param referenced the type as declared, generics included
     * @param into the map to record into
     */
    private void check(final String owner, final String member, final Type referenced,
        final Map<String, Set<String>> into) {
      if (referenced == null) {
        return;
      }
      // Every named type, not merely the last: a member can name two, and overwriting meant an
      // allowance for one of them suppressed the other.
      final Matcher matcher = CORE_REFERENCE.matcher(referenced.getTypeName());
      while (matcher.find()) {
        if (!EXPORTED.contains(matcher.group(1))) {
          into.computeIfAbsent(owner + "." + member, key -> new TreeSet<>()).add(matcher.group());
        }
      }
    }
  }

  @Nested
  @DisplayName("each module's reach into -core")
  class Reach {

    @Test
    @DisplayName("checks every module there is, not merely every module the table names")
    void theTableCoversEveryModule() {
      // coreImportsUnder answers empty for a directory that does not exist, so a module missing
      // from the table -- or misspelled in it -- was never read and every assertion passed.
      assertThat(INTERNAL_ACCESS.keySet())
          .describedAs("the table must name every sibling module, or the ones it misses go"
              + " unchecked while the suite stays green")
          .isEqualTo(modules());
    }

    @Test
    @DisplayName("leaves the internal-only packages alone, which is why that list can be trusted")
    void internalOnlyIsActuallyInternal() {
      // The other half of that list, and the reason it is a claim rather than an assertion.
      final Set<String> reached = new TreeSet<>();
      modules().forEach(module ->
          reached.addAll(coreImportsUnder(Path.of("..", module, "src", "main", "java"))));

      assertThat(INTERNAL_ONLY)
          .describedAs("a package listed as -core-internal that a module's main source reaches."
              + " It belongs in that module's INTERNAL_ACCESS entry instead")
          .doesNotContainAnyElementsOf(reached);
    }

    @Test
    @DisplayName("stays inside what this table allows")
    void noModuleReachesFurtherThanDeclared() {
      /*
       * Main source only. A module's TESTS legitimately reach further -- the testkit's suites drive
       * the naive, network, rete, agenda and truth internals precisely because that is what an
       * end-to-end test of this engine has to do, and none of that reaches a consumer.
       */
      final Map<String, Set<String>> offLimits = new TreeMap<>();
      modules().forEach(module -> {
        final Set<String> allowed = INTERNAL_ACCESS.getOrDefault(module, Set.of());
        final Set<String> used = coreImportsUnder(
            Path.of("..", module, "src", "main", "java"));
        used.removeAll(EXPORTED);
        used.removeAll(allowed);
        if (!used.isEmpty()) {
          offLimits.put(module, used);
        }
      });

      assertThat(offLimits)
          .describedAs("a module's main source reached a -core package the table does not grant it."
              + " Either the package belongs in that module's INTERNAL_ACCESS entry -- a design"
              + " decision, made here where it is visible -- or the code should not be reaching for"
              + " it")
          .isEmpty();
    }

    @Test
    @DisplayName("is not wider than it needs to be")
    void everyGrantIsUsed() {
      // The other direction, and the one that decays silently: a grant left behind after the code
      // that needed it moved reads as a boundary somebody chose, when nobody did.
      final Map<String, Set<String>> unused = new TreeMap<>();
      INTERNAL_ACCESS.forEach((module, allowed) -> {
        final Set<String> used = coreImportsUnder(
            Path.of("..", module, "src", "main", "java"));
        final Set<String> stale = new TreeSet<>(allowed);
        stale.removeAll(used);
        if (!stale.isEmpty()) {
          unused.put(module, stale);
        }
      });

      assertThat(unused)
          .describedAs("a grant nothing uses. Remove it, so the table keeps meaning what it says")
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("the record of why this is a test and not a module-info")
  class WhyNotJpms {

    @Test
    @DisplayName("re2j still ships no module descriptor and no Automatic-Module-Name")
    void re2jStillDeclaresNoModuleName() {
      /*
       * Interrogates the ARTIFACT, and the first version of this test did not -- it asked
       * `Pattern.class.getModule().isNamed()`, which reports where the jar was PLACED rather than
       * how it is packaged. Everything on the classpath is in the unnamed module, so that assertion
       * was green for jackson too, which ships a real module-info. A pin that cannot fire is worse
       * than no pin: it is a claim in §8.1 with a passing test behind it.
       *
       * Through ModuleFinder rather than by reading jar entries, because that is the resolution the
       * JDK itself performs: a descriptor at META-INF/versions/N/module-info.class in a
       * multi-release jar counts, and reading the root entry alone would have gone quietly vacuous
       * again in a new way. isAutomatic() is then exactly the condition §8.1 argues about -- a
       * module whose name came from the manifest or, failing that, from the filename.
       *
       * What actually blocks JPMS is narrower than "re2j has no name": `requires re2j` compiles
       * fine against the module path. The objection is to PUBLISHING a descriptor that requires a
       * filename-derived automatic module -- the name is not the library's to promise, jlink
       * refuses it, and every consumer inherits the problem. So what is worth watching is whether
       * re2j ever declares a name of its own, which is this.
       */
      final Path jar = jarContaining(com.google.re2j.Pattern.class);
      assertThat(jar).describedAs("re2j should resolve to a jar on this test's classpath").isNotNull();

      final List<String> named = ModuleFinder.of(jar).findAll().stream()
          .map(reference -> reference.descriptor())
          .filter(descriptor -> !descriptor.isAutomatic())
          .map(descriptor -> descriptor.name())
          .toList();

      assertThat(named)
          .describedAs("%s now declares a module name of its own. If this fails, re2j can be"
              + " required from a published module descriptor and the right response is to delete"
              + " this class and write the module-info §8.1 describes", jar.getFileName())
          .isEmpty();
    }

    /**
     * The jar a class was loaded from, or null when it did not come from one.
     *
     * @param type any class on the test classpath
     * @return the jar's path
     */
    private Path jarContaining(final Class<?> type) {
      final CodeSource source = type.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      try {
        final Path path = Path.of(source.getLocation().toURI());
        return Files.isRegularFile(path) ? path : null;
      } catch (final URISyntaxException failed) {
        return null;
      }
    }
  }
}
