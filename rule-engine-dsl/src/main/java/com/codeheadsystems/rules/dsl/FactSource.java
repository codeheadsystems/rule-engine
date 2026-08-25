package com.codeheadsystems.rules.dsl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One fact document: its text, its format, and a name to report problems against.
 *
 * <p>The fact-side twin of {@link RuleSource}, and deliberately the same shape. §6.1 settles the
 * serialization question once for the whole project -- "don't build two parsers; the difference is
 * one factory choice against an identical target type" -- and a fact document is the same argument
 * with a different target type. Rules are written in YAML by people who do not open a Java file;
 * the facts those rules are tried against are written by the same people, in the same editor, and
 * having one of the two be JSON-only is an accident of which one was built first.
 *
 * <p><strong>The format is a {@link RuleFormat}</strong>, which reads oddly beside a fact and is
 * still the right type. There is exactly one such enum in this project on purpose: it is where the
 * Jackson mappers are configured, where strict duplicate detection is turned on, and where the
 * YAML factory's classload is deferred so that a
 * deployment excluding snakeyaml pays for it in YAML alone. A second enum naming the same two
 * serializations would be a second place to configure a mapper and a second place to add the third
 * format.
 *
 * <p>Text rather than a stream, for {@link RuleSource}'s reason: the whole document is held so that
 * it can be parsed twice, once to bind it and once to index where everything is, which is what puts
 * a line number on a problem. A fact document is a fixture, a seed or a captured session -- not a
 * feed. Something producing millions of facts a second should call {@code insert} directly rather
 * than render text for this to re-parse.
 *
 * @param name the name to report problems against. Any label will do; it is never resolved or
 *     opened
 * @param text the document's contents
 * @param format which serialization {@code text} is written in
 */
public record FactSource(String name, String text, RuleFormat format) {

  /**
   * Canonical constructor.
   *
   * @param name the diagnostic name
   * @param text the document contents
   * @param format the serialization
   */
  public FactSource {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(format, "format");
  }

  /**
   * A YAML document.
   *
   * @param name the diagnostic name
   * @param text the document contents
   * @return the source
   */
  public static FactSource yaml(final String name, final String text) {
    return new FactSource(name, text, RuleFormat.YAML);
  }

  /**
   * A JSON document.
   *
   * @param name the diagnostic name
   * @param text the document contents
   * @return the source
   */
  public static FactSource json(final String name, final String text) {
    return new FactSource(name, text, RuleFormat.JSON);
  }

  /**
   * Reads a fact document, taking its format from its extension.
   *
   * @param path the file to read, ending in {@code .json}, {@code .yaml} or {@code .yml}
   * @return the source, named by the path's file name
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the extension is not one this DSL recognises. A checked
   *     exception is for a file that could not be read; an unrecognised extension is a call the
   *     caller got wrong, and there is nothing to recover from
   */
  public static FactSource of(final Path path) throws IOException {
    final String fileName = path.getFileName().toString();
    final RuleFormat format = RuleFormat.forFileName(fileName)
        .orElseThrow(() -> new IllegalArgumentException(
            "cannot tell the format of '" + fileName
                + "' from its extension; expected .json, .yaml or .yml"));
    return new FactSource(fileName, Files.readString(path, StandardCharsets.UTF_8), format);
  }
}
