package com.codeheadsystems.rules.dsl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One rule file: its text, its format, and a name to report diagnostics against.
 *
 * <p>Text rather than a stream, and deliberately so. A rule file is configuration -- kilobytes, read
 * once at startup -- and holding the whole of it lets the parse happen twice: once to bind the
 * document and once to index every element's {@linkplain SourceLocation source location}. Streaming
 * would save nothing measurable and would cost every diagnostic its line number.
 *
 * @param name the file name to report diagnostics against. Any label will do; it is never resolved
 *     or opened
 * @param text the file's contents
 * @param format which serialization {@code text} is written in
 */
public record RuleSource(String name, String text, RuleFormat format) {

  /**
   * Canonical constructor.
   *
   * @param name the diagnostic name
   * @param text the file contents
   * @param format the serialization
   */
  public RuleSource {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(format, "format");
  }

  /**
   * A YAML source.
   *
   * @param name the diagnostic name
   * @param text the file contents
   * @return the source
   */
  public static RuleSource yaml(final String name, final String text) {
    return new RuleSource(name, text, RuleFormat.YAML);
  }

  /**
   * A JSON source.
   *
   * @param name the diagnostic name
   * @param text the file contents
   * @return the source
   */
  public static RuleSource json(final String name, final String text) {
    return new RuleSource(name, text, RuleFormat.JSON);
  }

  /**
   * Reads a rule file, taking its format from its extension.
   *
   * @param path the file to read, ending in {@code .json}, {@code .yaml} or {@code .yml}
   * @return the source, named by the path's file name
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the extension is not one this DSL recognises. A checked
   *     exception is for a file that could not be read; an unrecognised extension is a call the
   *     caller got wrong, and there is nothing to recover from
   */
  public static RuleSource of(final Path path) throws IOException {
    final String fileName = path.getFileName().toString();
    final RuleFormat format = RuleFormat.forFileName(fileName)
        .orElseThrow(() -> new IllegalArgumentException(
            "cannot tell the format of '" + fileName
                + "' from its extension; expected .json, .yaml or .yml"));
    return new RuleSource(fileName, Files.readString(path, StandardCharsets.UTF_8), format);
  }
}
