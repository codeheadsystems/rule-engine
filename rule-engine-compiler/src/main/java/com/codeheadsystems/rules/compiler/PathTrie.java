package com.codeheadsystems.rules.compiler;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A prefix trie over one fact type's tested paths (spec §3.4.2).
 *
 * <p>The problem it solves: the straightforward diff walks every tested path on every update, so a
 * 300-rule set touching 200 distinct paths on {@code Order} pays 200 traversals and 200 subtree
 * comparisons on <em>every</em> update, including ones that change nothing. That cost grows with
 * the rule set rather than with the update.
 *
 * <p>This walks both payloads together <strong>once</strong>, descending only into subtrees the
 * trie marks interesting and stopping wherever the two nodes are equal. Cost becomes proportional
 * to the size of the change.
 *
 * <p><strong>The trie marks ancestors, not just the deepest match.</strong> §3.4.2 calls this out
 * because it is the easy thing to get wrong: if {@code /customer} and {@code /customer/email} are
 * both tested and only the email changes, <em>both</em> belong in the changed set, because the rule
 * testing {@code /customer} observes that change too. Here it falls out of the structure rather
 * than needing a special case -- a path is reported when the walk reaches its node with the two
 * subtrees differing, and reaching {@code /customer/email} means {@code /customer} differed.
 *
 * <p>Getting it wrong is quiet. Under-reporting no longer costs a missed activation, because
 * §3.4.1 step 6 re-asserts unconditionally, but it costs a missed refraction clear in step 5 --
 * a rule that should have re-fired and did not, discovered much later. That is why
 * {@link com.codeheadsystems.rules.rule.TestedPaths#changedPaths} keeps the probe loop as its
 * default: it is the oracle this is differentially tested against.
 */
final class PathTrie {

  private final Node root;

  private PathTrie(final Node root) {
    this.root = root;
  }

  /**
   * Builds a trie over a set of tested paths.
   *
   * @param paths the paths the rule set reads on one fact type
   * @return the trie
   */
  static PathTrie of(final Set<JsonPointer> paths) {
    final Node root = new Node();
    for (final JsonPointer path : paths) {
      Node current = root;
      for (final String segment : segments(path)) {
        current = current.children.computeIfAbsent(segment, ignored -> new Node());
      }
      current.terminates.add(path);
    }
    return new PathTrie(root);
  }

  /**
   * The tested paths whose values differ between two payloads.
   *
   * @param oldPayload the payload as stored
   * @param newPayload the replacement payload
   * @return the changed paths
   */
  Set<JsonPointer> changed(final JsonNode oldPayload, final JsonNode newPayload) {
    final Set<JsonPointer> changed = new LinkedHashSet<>();
    walk(root, oldPayload, newPayload, changed);
    return changed;
  }

  /**
   * Walks both payloads together, descending only where the trie has something to say.
   *
   * @param node the trie node for the current position
   * @param oldValue the old payload at this position
   * @param newValue the new payload at this position
   * @param changed the set to collect into
   */
  private static void walk(final Node node, final JsonNode oldValue, final JsonNode newValue,
      final Set<JsonPointer> changed) {
    if (oldValue.equals(newValue)) {
      // The whole subtree is identical, so nothing at or below this position can have changed.
      // This is the short-circuit the entire structure exists for.
      return;
    }
    // Reaching here means the subtrees differ, so every path terminating at this node differs too.
    // Ancestors are handled by this line and nothing else: the walk cannot reach a child without
    // having passed through the parent with the parent's subtrees differing.
    changed.addAll(node.terminates);
    for (final Map.Entry<String, Node> child : node.children.entrySet()) {
      walk(child.getValue(),
          descend(oldValue, child.getKey()),
          descend(newValue, child.getKey()),
          changed);
    }
  }

  /**
   * Reads one segment, handling array indices as well as object fields.
   *
   * <p>{@code JsonNode.path(String)} returns missing for an array, so a numeric segment against an
   * array has to go through the integer overload. Without this, a tested path through an array
   * index would report "unchanged" for every update -- which is a missed refraction clear, silently.
   *
   * @param value the node to descend from
   * @param segment the reference token
   * @return the child, or a missing node
   */
  private static JsonNode descend(final JsonNode value, final String segment) {
    if (value.isArray()) {
      try {
        return value.path(Integer.parseInt(segment));
      } catch (final NumberFormatException notAnIndex) {
        return value.path(segment);
      }
    }
    return value.path(segment);
  }

  /**
   * Splits a pointer into its reference tokens, undoing RFC 6901 escaping.
   *
   * @param path the pointer
   * @return its segments, outermost first
   */
  private static List<String> segments(final JsonPointer path) {
    final List<String> segments = new ArrayList<>();
    for (JsonPointer current = path; current != null && !current.matches();
        current = current.tail()) {
      segments.add(current.getMatchingProperty());
    }
    return segments;
  }

  /** One position in the trie. */
  private static final class Node {

    /** Children by reference token, insertion-ordered so the walk is reproducible. */
    private final Map<String, Node> children = new LinkedHashMap<>();

    /** The tested paths that end exactly here. */
    private final Set<JsonPointer> terminates = new LinkedHashSet<>();
  }
}
