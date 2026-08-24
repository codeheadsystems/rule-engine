package com.codeheadsystems.rules.example;

import com.codeheadsystems.rules.fact.Fact;
import com.codeheadsystems.rules.fact.FactHandle;
import com.codeheadsystems.rules.session.RuleSession;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns events into facts, for one session.
 *
 * <p><strong>This class is where the example is actually taught.</strong> Everything else is
 * plumbing; the decisions here are the ones a reader has to make on their own data, and two of them
 * are irreversible once rules are written against them.
 *
 * <p><strong>Collections are flattened.</strong> An {@code order.placed} event carries its line
 * items nested inside it, and that is not what goes into working memory: one {@code Order} fact
 * goes in without them, plus one {@code LineItem} fact per element, each carrying the order id. The
 * path syntax is RFC 6901 JSON Pointer and has no wildcard -- {@code items.*.qty} does not exist and
 * is not coming -- so a nested array is a value the engine can store and can never match inside. It
 * is also the only way to get indexing over elements at all: {@code LineItem./orderId} is an index,
 * "somewhere inside this array" is not.
 *
 * <p><strong>Absent fields are normalised away.</strong> {@code priority} is defaulted to
 * {@code false} here rather than left off, because rule 7 reads it from a §6.4 expression and CEL
 * treats an absent field as an <em>error</em> where every operator map in the engine treats it as a
 * value. Normalising at ingestion is the cheap fix; {@code has(o.priority) &amp;&amp; o.priority} in
 * every expression is the expensive one.
 *
 * <p>An instance holds the handles it has issued, so it is per session and single-threaded, exactly
 * like the session it writes to.
 */
public final class Ingest {

  private final RuleSession session;

  /** Customer id to handle, so a second {@code customer.upserted} is an update and not a twin. */
  private final Map<String, FactHandle> customers = new HashMap<>();

  /**
   * Order id to handle. Insertion-ordered rather than a {@code HashMap}: retract order is visible
   * to listeners, and an unordered walk would make a trace differ between hosts (§7.3).
   */
  private final Map<String, FactHandle> orders = new LinkedHashMap<>();

  /**
   * Order id to sku to handle, which is what makes {@code item.restocked} an update.
   *
   * <p>Nested rather than keyed on a joined {@code "orderId sku"} string. A composite string key
   * collides the moment an order id contains the separator, and it turns {@link #retractOrder} into
   * a scan of every line item the session has ever seen.
   */
  private final Map<String, Map<String, FactHandle>> lineItems = new LinkedHashMap<>();

  /** Order id to the payments against it, so {@link #retractOrder} can let go of everything. */
  private final Map<String, List<FactHandle>> payments = new HashMap<>();

  /**
   * Creates an ingestion path for one session.
   *
   * @param session the session to write facts into; not closed by this class
   */
  public Ingest(final RuleSession session) {
    this.session = Objects.requireNonNull(session, "session");
  }

  /**
   * Applies one event.
   *
   * @param event the event
   * @throws IllegalArgumentException if the event type is not one this application knows
   */
  public void apply(final OrderEvent event) {
    switch (event.type()) {
      case "customer.upserted" -> upsertCustomer(event);
      case "order.placed" -> placeOrder(event);
      case "payment.received" -> receivePayment(event);
      case "item.restocked" -> restockItem(event);
      /*
       * Loudly. A feed that grows a new event type and an ingestion path that silently drops it is
       * a rule set that quietly stops firing, and nothing in the engine can tell you about a fact
       * that was never inserted. MatchExplainer answers "which constraint failed", not "which
       * event your code ignored".
       */
      default -> throw new IllegalArgumentException("unknown event type: " + event.type());
    }
  }

  /**
   * Inserts or updates the customer.
   *
   * <p>An upsert has to be an {@code update} on the existing handle rather than a second insert.
   * Two facts of the same type with the same business id are two facts to the engine -- §2.1's
   * identity is the handle, not anything in the payload -- so a rule joining on {@code Customer.id}
   * would match both and fire twice.
   *
   * @param event the event
   */
  private void upsertCustomer(final OrderEvent event) {
    final String id = event.text("id");
    final ObjectNode payload = copyOf(event.payload());
    final FactHandle existing = customers.get(id);
    if (existing == null) {
      customers.put(id, session.insertOwned("Customer", payload));
    } else {
      session.updateOwned(existing, payload);
    }
  }

  /**
   * Inserts the order and one fact per line item.
   *
   * @param event the event
   */
  private void placeOrder(final OrderEvent event) {
    final String orderId = event.text("id");
    final ObjectNode order = copyOf(event.payload());
    /*
     * The nested array is removed rather than left alongside the flattened facts. Leaving it would
     * cost nothing at match time -- no rule can read into it -- and would cost the truth: a second
     * copy of the line items that updates independently of the LineItem facts and disagrees with
     * them the first time one is restocked.
     */
    order.remove("items");
    order.put("status", "PENDING");
    if (!order.has("priority")) {
      order.put("priority", false);
    }
    orders.put(orderId, session.insertOwned("Order", order));

    final JsonNode items = event.payload().get("items");
    if (items != null) {
      for (final JsonNode item : items) {
        final ObjectNode lineItem = copyOf(item);
        lineItem.put("orderId", orderId);
        final String sku = OrderEvent.requiredText(lineItem, "sku", "line item of " + orderId);
        lineItems.computeIfAbsent(orderId, key -> new LinkedHashMap<>())
            .put(sku, session.insertOwned("LineItem", lineItem));
      }
    }
  }

  /**
   * Inserts the payment.
   *
   * @param event the event
   */
  private void receivePayment(final OrderEvent event) {
    final String orderId = event.text("orderId");
    payments.computeIfAbsent(orderId, key -> new ArrayList<>())
        .add(session.insertOwned("Payment", copyOf(event.payload())));
  }

  /**
   * Lets go of an order and everything that came in with it.
   *
   * <p><strong>This is what bounds the facts the application inserted, because §4.4's eviction
   * cannot.</strong> Eviction drops facts on a policy the engine applies; it is safe only for a type
   * no rule negates, quantifies over, accumulates or concludes -- and of the five types ingested
   * here, every one is at least one of those. An evicted {@code Payment} makes rule 2 declare a paid
   * order unpaid. An evicted {@code LineItem} deletes rule 3's requirement rather than weakening it,
   * and quietly changes rule 4's total. So that bound has to come from the application, which knows
   * the thing the engine cannot: that this order is finished.
   *
   * <p><strong>It does not reach what the rules themselves inserted non-logically.</strong> Rule 4's
   * {@code Discount} has no handle in this class and nothing patterns it, so nothing here can let it
   * go -- which is exactly why it is the one type in this rule set that a §4.4 cap would be safe on.
   * {@link StreamingDemo#evictionAnalysis()} says so and gives the line.
   *
   * <p>Retracting the {@code Order} also withdraws whatever rule 2 concluded about it -- the
   * justification is gone, so the conclusion goes at the next cycle boundary. That is truth
   * maintenance doing the cleanup that a hand-rolled "delete the derived facts too" would have to
   * get right by hand.
   *
   * @param orderId the order to let go of
   * @return how many facts were retracted
   */
  public int retractOrder(final String orderId) {
    int retracted = 0;
    final FactHandle order = orders.remove(orderId);
    if (order != null) {
      session.retract(order);
      retracted++;
    }
    for (final FactHandle payment : payments.getOrDefault(orderId, List.of())) {
      session.retract(payment);
      retracted++;
    }
    payments.remove(orderId);
    for (final FactHandle item : lineItems.getOrDefault(orderId, Map.of()).values()) {
      session.retract(item);
      retracted++;
    }
    lineItems.remove(orderId);
    return retracted;
  }

  /**
   * Marks a line item back in stock.
   *
   * <p>An {@code update}, not a retract-and-reinsert, and the difference is visible: an update keeps
   * the handle, so §4.5's refraction is cleared only for the rules that test a path which actually
   * changed. That is what lets {@code ready-to-ship} fire for this order now that nothing is out of
   * stock, without re-firing every other rule that had already matched it.
   *
   * @param event the event
   * @throws IllegalArgumentException if the line item is unknown
   */
  private void restockItem(final OrderEvent event) {
    final String orderId = event.text("orderId");
    final String sku = event.text("sku");
    final FactHandle handle = lineItems.getOrDefault(orderId, Map.of()).get(sku);
    if (handle == null) {
      throw new IllegalArgumentException("restock for an unknown line item: " + orderId + "/" + sku);
    }
    final Fact fact = session.get(handle)
        .orElseThrow(() -> new IllegalStateException("line item retracted: " + orderId + "/" + sku));
    /*
     * deepCopy before mutating, and then updateOwned. Handing back a node that aliases the stored
     * payload is the §2.2 violation strict mode exists to catch -- and outside strict mode it is
     * worse than an error, because a retract computes its index-removal keys from the payload the
     * fact had when it was asserted. Mutate that in place and the entry it would have removed is
     * already unreachable.
     */
    final ObjectNode updated = (ObjectNode) fact.payload().deepCopy();
    updated.put("inStock", true);
    session.updateOwned(handle, updated);
  }

  /**
   * Copies a node into a fresh object node this class owns.
   *
   * @param node the source, expected to be a JSON object
   * @return an independent copy
   * @throws IllegalArgumentException if the node is not an object
   */
  private static ObjectNode copyOf(final JsonNode node) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("expected a JSON object, got: " + node);
    }
    return ((ObjectNode) node).deepCopy();
  }
}
