package com.mulesoft.examples.track_a_custom_business_event.service;

import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Records the {@code Price} business event of flow {@code custom-business-eventsFlow1} as one
 * INFO log line with MDC entries (SC-08, D-038).
 *
 * <p>The element {@code tracking:custom-event event-name="Price"} and its three
 * {@code tracking:meta-data} entries {@code item name}, {@code item units} and
 * {@code item price per unit}, each a Groovy read of the parsed request map (SC-08, D-034), become
 * {@link #track(Map)}. The flow's {@code tracking:transaction} id
 * {@code custom-business-event-example-transaction} is the {@link #TRANSACTION_ID} MDC entry of
 * the same log line (D-038).
 *
 * <p>The class holds no state. MDC entries are thread-local, and {@link #track(Map)} removes the
 * five entries it puts before it returns, leaving every other MDC entry of the calling thread in
 * place.
 *
 * <p>The three metadata values are only read from the map. In the log line and in the MDC each
 * ISO control character and each U+2028 and U+2029 separator of a value is written as a
 * backslash, the letter {@code u} and four upper-case hexadecimal digits (D-339).
 *
 * <pre>{@code
 * BusinessEventTracker tracker = new BusinessEventTracker();
 * tracker.track(Map.of("item name", "shoes", "item units", 2, "item price per unit", 10));
 * // INFO: Business event Price: item name=shoes, item units=2, item price per unit=10
 * // MDC during the call: event=Price, transactionId=custom-business-event-example-transaction,
 * //                      item name=shoes, item units=2, item price per unit=10
 * // Item name "shoes" + CR + LF + "ERROR": one INFO line; in it and in the MDC value the CR
 * // and LF appear as backslash-u000D and backslash-u000A
 * }</pre>
 */
@Service
public class BusinessEventTracker {

    /** Logger that writes the business event line. */
    private static final Logger log = LoggerFactory.getLogger(BusinessEventTracker.class);

    /** Name of the business event, the {@code event-name} of {@code tracking:custom-event}. */
    public static final String EVENT_NAME = "Price";

    /** Id of the flow's {@code tracking:transaction} element. */
    public static final String TRANSACTION_ID = "custom-business-event-example-transaction";

    /** Metadata key of the item name, read from the request map and used as MDC key. */
    private static final String ITEM_NAME = "item name";

    /** Metadata key of the item units, read from the request map and used as MDC key. */
    private static final String ITEM_UNITS = "item units";

    /** Metadata key of the item price per unit, read from the request map and used as MDC key. */
    private static final String ITEM_PRICE_PER_UNIT = "item price per unit";

    /** MDC key holding {@link #EVENT_NAME}. */
    private static final String MDC_EVENT = "event";

    /** MDC key holding {@link #TRANSACTION_ID}. */
    private static final String MDC_TRANSACTION_ID = "transactionId";

    /**
     * Implements the {@code tracking:custom-event} {@code Price} of flow
     * {@code custom-business-eventsFlow1} and its {@code tracking:transaction} (SC-08, D-038).
     *
     * <p>Reads the {@code item name}, {@code item units} and {@code item price per unit} values of
     * the parsed request map and renders each with {@link String#valueOf(Object)}; a {@code null}
     * map, a missing key or a {@code null} value renders as {@code "null"}. The logged and MDC
     * forms of the three rendered values have each ISO control character and each U+2028 and
     * U+2029 separator replaced by a backslash, the letter {@code u} and four upper-case
     * hexadecimal digits, a line feed becoming backslash-u000A; values without such characters
     * are logged unchanged (D-339). While the MDC holds {@code event}, {@code transactionId} and
     * the three metadata entries, it logs one INFO line
     * {@code Business event Price: item name=<name>, item units=<units>,
     * item price per unit=<price>}. Those five MDC entries are removed when the method returns.
     *
     * <p>The map is only read, never modified, and the method throws no exception for a
     * {@code null} map or {@code null} values (D-038).
     *
     * @param order the parsed request map of the flow, possibly {@code null}
     */
    public void track(Map<String, ?> order) {
        // tracking:meta-data "item name", "item units" and "item price per unit" (SC-08).
        String name = String.valueOf(order == null ? null : order.get(ITEM_NAME));
        String units = String.valueOf(order == null ? null : order.get(ITEM_UNITS));
        String price = String.valueOf(order == null ? null : order.get(ITEM_PRICE_PER_UNIT));
        // Logged and MDC forms of the three values, control characters escaped (D-339).
        String loggedName = escapeControls(name);
        String loggedUnits = escapeControls(units);
        String loggedPrice = escapeControls(price);

        try {
            // tracking:custom-event event-name="Price" and tracking:transaction id (D-038).
            MDC.put(MDC_EVENT, EVENT_NAME);
            MDC.put(MDC_TRANSACTION_ID, TRANSACTION_ID);
            MDC.put(ITEM_NAME, loggedName);
            MDC.put(ITEM_UNITS, loggedUnits);
            MDC.put(ITEM_PRICE_PER_UNIT, loggedPrice);
            log.info("Business event {}: item name={}, item units={}, item price per unit={}",
                    EVENT_NAME, loggedName, loggedUnits, loggedPrice);
        } finally {
            MDC.remove(MDC_EVENT);
            MDC.remove(MDC_TRANSACTION_ID);
            MDC.remove(ITEM_NAME);
            MDC.remove(ITEM_UNITS);
            MDC.remove(ITEM_PRICE_PER_UNIT);
        }
    }

    /**
     * Returns {@code value} with each ISO control character (U+0000 to U+001F and U+007F to
     * U+009F) and each U+2028 line separator and U+2029 paragraph separator replaced by a
     * six-character escape: a backslash, the letter {@code u} and the character's four upper-case
     * hexadecimal digits; for example, a line feed is written as backslash-u000A. Every other
     * character is kept unchanged (D-339).
     *
     * @param value the rendered value, never {@code null}
     * @return the value with its control and separator characters escaped
     */
    private static String escapeControls(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
                escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
