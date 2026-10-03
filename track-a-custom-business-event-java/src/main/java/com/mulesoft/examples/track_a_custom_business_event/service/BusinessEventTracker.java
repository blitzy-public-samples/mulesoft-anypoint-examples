package com.mulesoft.examples.track_a_custom_business_event.service;

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
 * <pre>{@code
 * BusinessEventTracker tracker = new BusinessEventTracker();
 * tracker.track(Map.of("item name", "shoes", "item units", 2, "item price per unit", 10));
 * // INFO: Business event Price: item name=shoes, item units=2, item price per unit=10
 * // MDC during the call: event=Price, transactionId=custom-business-event-example-transaction,
 * //                      item name=shoes, item units=2, item price per unit=10
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
     * map, a missing key or a {@code null} value renders as {@code "null"}. While the MDC holds
     * {@code event}, {@code transactionId} and the three metadata entries, it logs one INFO line
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

        try {
            // tracking:custom-event event-name="Price" and tracking:transaction id (D-038).
            MDC.put(MDC_EVENT, EVENT_NAME);
            MDC.put(MDC_TRANSACTION_ID, TRANSACTION_ID);
            MDC.put(ITEM_NAME, name);
            MDC.put(ITEM_UNITS, units);
            MDC.put(ITEM_PRICE_PER_UNIT, price);
            log.info("Business event {}: item name={}, item units={}, item price per unit={}",
                    EVENT_NAME, name, units, price);
        } finally {
            MDC.remove(MDC_EVENT);
            MDC.remove(MDC_TRANSACTION_ID);
            MDC.remove(ITEM_NAME);
            MDC.remove(ITEM_UNITS);
            MDC.remove(ITEM_PRICE_PER_UNIT);
        }
    }
}
