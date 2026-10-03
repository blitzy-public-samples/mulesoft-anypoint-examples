package com.mulesoft.examples.track_a_custom_business_event.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Business logic of flow {@code custom-business-eventsFlow1}
 * [track-a-custom-business-event/src/main/app/custom-business-events.xml:4-25]: parses the request
 * map, records the {@code Price} event, applies the item discount and returns the discounted price
 * per unit as text (SC-07, D-034).
 *
 * <p>The processors after the flow's HTTP listener (:5) map to
 * {@link #customBusinessEventsFlow1(byte[])} in source order:
 * <ul>
 *   <li>{@code json:json-to-object-transformer returnClass="java.util.HashMap"} (:6): the body is
 *       read into a {@link HashMap} by the injected {@link ObjectMapper};</li>
 *   <li>{@code tracking:custom-event} {@code Price} (:7-11, SC-08):
 *       {@link BusinessEventTracker#track(java.util.Map)} receives the parsed map before any
 *       discount is applied (D-038);</li>
 *   <li>JRuby {@code scripting:transformer} (:12-22, SC-07): the discount is 0.15 for
 *       {@code shoes}, 0.2 for {@code jeans} and 0.3 for {@code jackets}, matched by exact,
 *       case-sensitive string equality on {@code item name}; the result is
 *       {@code item price per unit * (1 - discount)} in {@code double} arithmetic (D-034);</li>
 *   <li>{@code object-to-string-transformer} (:23): the result is returned as
 *       {@link Double#toString(double)} text, for example {@code 8.5}; the parsed map is not
 *       returned and not modified;</li>
 *   <li>{@code tracking:transaction} (:24): carried by {@link BusinessEventTracker} as its
 *       {@link BusinessEventTracker#TRANSACTION_ID} entry (D-038).</li>
 * </ul>
 *
 * <p><b>Discount state (D-071).</b> The bean keeps the last discount set by any call. An
 * {@code item name} other than the three listed ones, including a missing, {@code null} or
 * non-string value, leaves it unchanged and is priced with it; while no call has set a discount,
 * such an item raises {@link IllegalStateException}. The state lives as long as the bean and is
 * shared by all requests; concurrent calls read whichever discount was set last when their price
 * is computed.
 *
 * <p><b>Price value.</b> A JSON number is read as a {@code double}. A JSON string is repeated
 * {@code (1 - discount)} times with the count truncated toward zero, which yields the empty string
 * for every listed discount (D-479). Any other value raises an unchecked exception: a missing or
 * {@code null} price {@link NullPointerException}, a boolean, array or object
 * {@link ClassCastException}.
 *
 * <p><b>Errors.</b> No exception is caught: parse failures ({@link IOException} subtypes, an empty
 * body included), a JSON {@code null} document ({@link NullPointerException}, after the event is
 * recorded) and the exceptions above propagate unchanged to
 * {@code controller.BusinessEventController}, and from there to the project's
 * {@code GlobalExceptionHandler}, which answers HTTP 500.
 *
 * <pre>{@code
 * PriceDiscountService service =
 *         new PriceDiscountService(new ObjectMapper(), new BusinessEventTracker());
 * service.customBusinessEventsFlow1("{\"item name\":\"socks\",\"item price per unit\":10}".getBytes(UTF_8));
 * // throws IllegalStateException: no discount set yet (D-071)
 * service.customBusinessEventsFlow1(
 *         "{\"item name\":\"shoes\",\"item units\":2,\"item price per unit\":10}".getBytes(UTF_8));
 * // returns "8.5" and logs the Price event
 * service.customBusinessEventsFlow1("{\"item name\":\"socks\",\"item price per unit\":10}".getBytes(UTF_8));
 * // returns "8.5": the shoes discount is reused (D-071)
 * service.customBusinessEventsFlow1("{\"item name\":\"jeans\",\"item price per unit\":\"10\"}".getBytes(UTF_8));
 * // returns "" (D-479)
 * }</pre>
 */
@Service
public class PriceDiscountService {

    /** Target type of {@code json:json-to-object-transformer returnClass="java.util.HashMap"}. */
    private static final TypeReference<HashMap<String, Object>> ORDER_TYPE =
            new TypeReference<HashMap<String, Object>>() { };

    /** Request map key read by the JRuby {@code case} (:13). */
    private static final String ITEM_NAME = "item name";

    /** Request map key of the price the JRuby script discounts (:21). */
    private static final String ITEM_PRICE_PER_UNIT = "item price per unit";

    /** First {@code when} value of the JRuby {@code case} (:14). */
    private static final String SHOES = "shoes";

    /** Discount set for {@link #SHOES} (:15). */
    private static final double SHOES_DISCOUNT = 0.15;

    /** Second {@code when} value of the JRuby {@code case} (:16). */
    private static final String JEANS = "jeans";

    /** Discount set for {@link #JEANS} (:17). */
    private static final double JEANS_DISCOUNT = 0.2;

    /** Third {@code when} value of the JRuby {@code case} (:18). */
    private static final String JACKETS = "jackets";

    /** Discount set for {@link #JACKETS} (:19). */
    private static final double JACKETS_DISCOUNT = 0.3;

    /** Reads the request body into the order map (:6). */
    private final ObjectMapper objectMapper;

    /** Records the {@code Price} business event (SC-08, D-038). */
    private final BusinessEventTracker tracker;

    /** Last discount set by any request; an unlisted item reuses it (D-071). */
    private final AtomicReference<Double> lastDiscount = new AtomicReference<>();

    /**
     * Creates the service with no discount set.
     *
     * @param objectMapper JSON reader of the request body, Spring Boot's auto-configured mapper in
     *     the application
     * @param tracker recorder of the {@code Price} business event
     * @throws NullPointerException when either argument is {@code null}
     */
    public PriceDiscountService(ObjectMapper objectMapper, BusinessEventTracker tracker) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /**
     * Runs flow {@code custom-business-eventsFlow1} on a request body and returns the discounted
     * {@code item price per unit} as text (SC-07, D-034).
     *
     * <p>Steps, in the flow's element order:
     * <ol>
     *   <li>the body is parsed into a {@code HashMap<String, Object>} (:6); {@code null} is read
     *       as an empty body;</li>
     *   <li>the map is passed to {@link BusinessEventTracker#track(java.util.Map)} (:7-11,
     *       SC-08);</li>
     *   <li>{@code item name} {@code shoes}, {@code jeans} or {@code jackets} sets the last
     *       discount to 0.15, 0.2 or 0.3; any other name keeps it, and with none set the call
     *       throws {@link IllegalStateException} (:13-20, D-071);</li>
     *   <li>a numeric {@code item price per unit} becomes {@code price * (1 - discount)} and is
     *       returned as {@link Double#toString(double)} text (:21, :23); a string price returns
     *       the string repeated {@code (int) (1 - discount)} times, the empty string (D-479).</li>
     * </ol>
     *
     * <p>With a fresh bean and a price of {@code 10}: {@code shoes} returns {@code "8.5"},
     * {@code jeans} {@code "8.0"} and {@code jackets} {@code "7.0"}; an unlisted item first throws
     * {@link IllegalStateException}, and after {@code jeans} it returns {@code "8.0"}.
     *
     * @param body the raw request body, possibly {@code null} or empty
     * @return the discounted price per unit as text
     * @throws IOException when the body is empty or not a JSON object; Jackson's exception is
     *     propagated unchanged
     * @throws IllegalStateException when the item name is unlisted and no discount was ever set
     *     (D-071)
     * @throws NullPointerException when the body is the JSON literal {@code null}, or the price is
     *     missing or {@code null}
     * @throws ClassCastException when the price is a boolean, array or object
     */
    public String customBusinessEventsFlow1(byte[] body) throws IOException {
        // json:json-to-object-transformer returnClass="java.util.HashMap" (:6).
        HashMap<String, Object> order =
                objectMapper.readValue(body == null ? new byte[0] : body, ORDER_TYPE);

        // tracking:custom-event "Price" (:7-11, SC-08).
        tracker.track(order);

        // scripting:transformer SC-07, case on item name (:13-20); other names keep it (D-071).
        Object itemName = order.get(ITEM_NAME);
        if (SHOES.equals(itemName)) {
            lastDiscount.set(SHOES_DISCOUNT);
        } else if (JEANS.equals(itemName)) {
            lastDiscount.set(JEANS_DISCOUNT);
        } else if (JACKETS.equals(itemName)) {
            lastDiscount.set(JACKETS_DISCOUNT);
        }
        Double discount = lastDiscount.get();
        if (discount == null) {
            throw new IllegalStateException("No discount set for item name " + itemName);
        }

        // scripting:transformer SC-07, price * (1 - discount) (:21).
        Object pricePerUnit = order.get(ITEM_PRICE_PER_UNIT);
        if (pricePerUnit instanceof String text) {
            // A string price is repeated (1 - discount) times, the count truncated toward zero (D-479).
            return text.repeat((int) (1 - discount));
        }
        double price = ((Number) pricePerUnit).doubleValue();
        double discounted = price * (1 - discount);

        // object-to-string-transformer (:23).
        return Double.toString(discounted);
    }
}
