package com.codeheadsystems.rules.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The events this example processes, read from {@code feed/orders.jsonl} on the classpath.
 *
 * <p>Ten lines telling one story: two customers, four orders, three payments and a restock. It is a
 * file rather than a literal so that a reader can change it and re-run, which is the only way
 * anybody ever really learns what a rule set does.
 */
public final class EventFeed {

  /** Where the feed lives on the classpath. */
  private static final String RESOURCE = "feed/orders.jsonl";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private EventFeed() {
    throw new AssertionError("static helper");
  }

  /**
   * Reads the whole feed.
   *
   * <p>Order matters and is preserved. §7.3 states the determinism contract in terms of insertion
   * order, so the sequence in that file <em>is</em> part of what this example asserts: the same
   * lines in the same order produce the same firings, on any host and in any year.
   *
   * @return every event, in feed order
   * @throws UncheckedIOException if the resource is missing or unreadable
   */
  public static List<OrderEvent> load() {
    final List<OrderEvent> events = new ArrayList<>();
    try (InputStream stream =
             EventFeed.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("classpath resource missing: " + RESOURCE);
      }
      try (BufferedReader reader =
               new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.isBlank()) {
            final JsonNode node = MAPPER.readTree(line);
            // Guarded, like every other read of this file: a feed line with a numeric `type` is a
            // strict-accessor throw rather than a diagnosable message otherwise.
            events.add(new OrderEvent(
                OrderEvent.requiredText(node, "type", "feed line"), node.get("payload")));
          }
        }
      }
    } catch (final IOException failed) {
      throw new UncheckedIOException("cannot read " + RESOURCE, failed);
    }
    return List.copyOf(events);
  }

  /**
   * The slice of a feed that one order's session needs.
   *
   * <p><strong>Session scope is a host decision, and it decides what your rules can possibly
   * see.</strong> Everything reachable from the order goes in -- the order, its line items, its
   * payment -- plus every customer, because reference data is what joins are for. Nothing else does,
   * so a rule that spans two orders cannot fire in a session built this way no matter how it is
   * written. That is not a defect to work around; it is the trade a request-scoped session makes,
   * and {@link StreamingDemo} is what you do when you need the other side of it.
   *
   * @param events the whole feed
   * @param orderId the order this session is about
   * @return the events to apply, in feed order
   */
  public static List<OrderEvent> forOrder(final List<OrderEvent> events, final String orderId) {
    return events.stream().filter(event -> switch (event.type()) {
      case "customer.upserted" -> true;
      case "order.placed" -> orderId.equals(event.text("id"));
      default -> orderId.equals(event.text("orderId"));
    }).toList();
  }
}
