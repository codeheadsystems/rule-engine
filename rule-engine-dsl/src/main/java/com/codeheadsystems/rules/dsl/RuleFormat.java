package com.codeheadsystems.rules.dsl;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.MapperBuilder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;
import java.util.Locale;
import java.util.Optional;

/**
 * Which serialization a rule file is written in (spec §6.1).
 *
 * <p>§6.1: "Don't build two parsers; the difference is one factory choice against an identical
 * target type." This enum is that one factory choice, and it is the whole of the difference. Every
 * validation, every diagnostic and every compiled {@code RuleDefinition} downstream is shared.
 */
public enum RuleFormat {

  /** A JSON rule file, {@code .json}. */
  JSON,

  /** A YAML rule file, {@code .yaml} or {@code .yml}. */
  YAML;

  /**
   * The format a file name implies.
   *
   * @param fileName the file name, with or without directories
   * @return the format, or empty when the extension is not one this DSL recognises
   */
  public static Optional<RuleFormat> forFileName(final String fileName) {
    final String lower = fileName.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".json")) {
      return Optional.of(JSON);
    }
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
      return Optional.of(YAML);
    }
    return Optional.empty();
  }

  /**
   * A mapper for this format.
   *
   * @return the shared, thread-safe mapper
   */
  ObjectMapper mapper() {
    return this == JSON ? Json.MAPPER : Yaml.MAPPER;
  }

  /**
   * A bare factory for this format, for the token walk that builds the source index.
   *
   * @return the shared, thread-safe factory
   */
  TokenStreamFactory factory() {
    return mapper().tokenStreamFactory();
  }

  /**
   * Turns on strict duplicate detection.
   *
   * <p>Both serializations accept a repeated mapping key by default and silently keep the last one.
   * That is the worst failure this DSL has, because of what an author naturally writes:
   *
   * <pre>{@code
   * where:
   *   status: { eq: "PENDING" }
   *   status: { ne: "CLOSED" }     # meant as a second condition on the same field
   * }</pre>
   *
   * <p>§6.2 AND-s the entries of a {@code where} block, so both conditions is the only reasonable
   * reading -- and last-wins gives the author neither an AND nor an error, just the second one and
   * a rule that matches everything the first would have excluded. A rule that quietly matches
   * <em>more</em> than it says is the failure mode §2.6.1's whole design exists to eliminate, and
   * it is invisible in review because the file reads exactly as intended.
   *
   * <p>The author who genuinely wants two operators on one field writes them in one map --
   * {@code status: { hasField: true, ne: "CLOSED" }} -- which is unambiguous and already supported.
   *
   * <p>Set on the builder rather than on the mapper: a Jackson 3 {@code ObjectMapper} is immutable
   * once built, so configuration is a build-time decision. That is an improvement here -- under
   * Jackson 2 this was a mutating {@code enable} call on a shared static, which was safe only
   * because it ran inside the holder's initialiser.
   *
   * @param builder the mapper builder to configure
   * @param <B> the concrete builder type
   * @param <M> the concrete mapper type
   * @return the same builder
   */
  private static <M extends ObjectMapper, B extends MapperBuilder<M, B>> B strict(final B builder) {
    return builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
  }

  /**
   * Holds the JSON mapper, initialised on first use.
   *
   * <p>Both holders exist for the reason the YAML one does; see {@link Yaml}.
   */
  private static final class Json {
    private static final ObjectMapper MAPPER = strict(JsonMapper.builder()).build();

    private Json() {
      throw new UnsupportedOperationException("no instances");
    }
  }

  /**
   * Holds the YAML mapper, initialised on first use.
   *
   * <p><strong>This nesting is load-bearing, not stylistic.</strong> §8 asks that a deployment
   * which does not want YAML be able to exclude its transitive {@code snakeyaml}. A static field on
   * {@link RuleFormat} itself would be initialised when the enum class loads -- that is, when
   * anything so much as names {@code RuleFormat.JSON} -- and a missing {@code snakeyaml} would then
   * be a {@code NoClassDefFoundError} on the JSON path, which has nothing to do with YAML. Holding
   * it in a nested class defers the classload to the first actual YAML parse, so excluding the
   * dependency costs exactly the feature it belongs to and nothing else.
   */
  private static final class Yaml {
    private static final ObjectMapper MAPPER = strict(YAMLMapper.builder()).build();

    private Yaml() {
      throw new UnsupportedOperationException("no instances");
    }
  }
}
