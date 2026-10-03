package com.mulesoft.examples.track_a_custom_business_event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.HashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Unit tests of {@link BusinessEventTracker}, the {@code tracking:custom-event} {@code Price} with the
 * metadata {@code item name}, {@code item units} and {@code item price per unit}
 * [track-a-custom-business-event/src/main/app/custom-business-events.xml:7-11] and the
 * {@code tracking:transaction} id [:24] (SC-08, D-038); coverage floor D-049; test design D-449.
 *
 * <p>The tracker is instantiated directly, with no Spring context and no mocks. For the duration of each
 * test a started {@link ListAppender} is attached to the tracker's logger, whose level is set to INFO. The
 * appender fixes each event's MDC map and formatted message when the event is appended, and records the
 * event. The tests assert the events' count, level, logger name, text and MDC entries, and the MDC of the
 * calling thread after {@link BusinessEventTracker#track(Map)} returns. Beside the Price event of
 * {@code message.json}, they cover a {@code null} order, the escaping of control characters (D-339), an
 * MDC entry of the caller and the unchanged order map, as the tracker declares them (D-449).
 */
public class BusinessEventTrackerTest {

    /** MDC key holding the event name. */
    private static final String MDC_EVENT = "event";

    /** MDC key holding the transaction id. */
    private static final String MDC_TRANSACTION_ID = "transactionId";

    /** Metadata and MDC key of the item name [custom-business-events.xml:8]. */
    private static final String ITEM_NAME = "item name";

    /** Metadata and MDC key of the item units [custom-business-events.xml:9]. */
    private static final String ITEM_UNITS = "item units";

    /** Metadata and MDC key of the item price per unit [custom-business-events.xml:10]. */
    private static final String ITEM_PRICE_PER_UNIT = "item price per unit";

    /** The five MDC keys {@link BusinessEventTracker#track(Map)} puts and removes. */
    private static final String[] TRACKER_MDC_KEYS = {
        MDC_EVENT, MDC_TRANSACTION_ID, ITEM_NAME, ITEM_UNITS, ITEM_PRICE_PER_UNIT
    };

    /** Item values of {@code message.json} [track-a-custom-business-event/src/test/resources/message.json]. */
    private static final Map<String, Object> MESSAGE_ITEM =
            Map.of(ITEM_NAME, "shoes", ITEM_UNITS, 2, ITEM_PRICE_PER_UNIT, 10);

    private BusinessEventTracker tracker;

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    private Level previousLevel;

    /**
     * Sets the tracker's logger to INFO, keeping its previous level ({@code null} when inherited), attaches
     * a started appender that fixes each event's MDC map and formatted message when the event is appended,
     * creates a tracker and clears the MDC of the test thread.
     */
    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(BusinessEventTracker.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent e) {
                e.prepareForDeferredProcessing();
                super.append(e);
            }
        };
        appender.start();
        logger.addAppender(appender);
        tracker = new BusinessEventTracker();
        MDC.clear();
    }

    /** Detaches and stops the appender, restores the logger's previous level and clears the MDC. */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        MDC.clear();
    }

    /**
     * Asserts the event name equals the {@code event-name} of {@code tracking:custom-event}
     * [custom-business-events.xml:7] and the transaction id equals the {@code id} of
     * {@code tracking:transaction} [:24] (SC-08, D-038).
     */
    @Test
    public void constantsMatchOriginalEventAndTransaction() {
        assertThat(BusinessEventTracker.EVENT_NAME).isEqualTo("Price");
        assertThat(BusinessEventTracker.TRANSACTION_ID).isEqualTo("custom-business-event-example-transaction");
    }

    /**
     * Asserts one call with the {@code message.json} item values writes exactly one INFO event to the
     * tracker's logger (SC-08, D-038).
     */
    @Test
    public void trackLogsOneInfoEvent() {
        tracker.track(MESSAGE_ITEM);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getLoggerName()).isEqualTo(BusinessEventTracker.class.getName());
    }

    /**
     * Asserts the Price event's MDC holds exactly the event name, the transaction id and the three
     * metadata values as strings (SC-08, D-038).
     */
    @Test
    public void trackPutsPriceEventMdc() {
        tracker.track(MESSAGE_ITEM);

        Map<String, String> mdc = singleInfoEvent().getMDCPropertyMap();
        assertThat(mdc)
                .containsEntry(MDC_EVENT, "Price")
                .containsEntry(MDC_TRANSACTION_ID, "custom-business-event-example-transaction")
                .containsEntry(ITEM_NAME, "shoes")
                .containsEntry(ITEM_UNITS, "2")
                .containsEntry(ITEM_PRICE_PER_UNIT, "10")
                .hasSize(5);
    }

    /**
     * Asserts the Price event's text names the event and lists the item name, item units and item price
     * per unit in that order, and equals the full business event line (SC-08, D-038).
     */
    @Test
    public void trackLogsPriceEventMessage() {
        tracker.track(MESSAGE_ITEM);

        String message = singleInfoEvent().getFormattedMessage();
        assertThat(message).contains("Price");
        int namePosition = message.indexOf("item name=shoes");
        int unitsPosition = message.indexOf("item units=2");
        int pricePosition = message.indexOf("item price per unit=10");
        assertThat(namePosition).isGreaterThanOrEqualTo(0);
        assertThat(unitsPosition).isGreaterThan(namePosition);
        assertThat(pricePosition).isGreaterThan(unitsPosition);
        assertThat(message)
                .isEqualTo("Business event Price: item name=shoes, item units=2, item price per unit=10");
    }

    /** Asserts none of the five tracker MDC keys remains set after the call returns (D-038). */
    @Test
    public void trackClearsMdcAfterCall() {
        tracker.track(MESSAGE_ITEM);

        assertTrackerMdcKeysRemoved();
    }

    /**
     * Asserts an order without the three item keys is tracked without an exception as one INFO event
     * whose metadata values read {@code null}, with the event name and transaction id in its MDC, and that
     * the five tracker MDC keys are removed afterwards (SC-08, D-038).
     */
    @Test
    public void trackToleratesMissingItemKeys() {
        Map<String, Object> order = new HashMap<String, Object>();
        order.put("email", "aaa@abc.sk");

        assertDoesNotThrow(() -> tracker.track(order));

        ILoggingEvent event = singleInfoEvent();
        assertThat(event.getFormattedMessage())
                .contains("item name=null")
                .contains("item units=null")
                .contains("item price per unit=null");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(MDC_EVENT, "Price")
                .containsEntry(MDC_TRANSACTION_ID, "custom-business-event-example-transaction");
        assertTrackerMdcKeysRemoved();
    }

    /**
     * Asserts a {@code null} order is tracked without an exception as the full business event line with
     * {@code null} metadata values, which the MDC also holds as {@code "null"}, and that the five tracker
     * MDC keys are removed afterwards (D-038).
     */
    @Test
    public void trackToleratesNullOrder() {
        assertDoesNotThrow(() -> tracker.track(null));

        ILoggingEvent event = singleInfoEvent();
        assertThat(event.getFormattedMessage())
                .isEqualTo("Business event Price: item name=null, item units=null, item price per unit=null");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(ITEM_NAME, "null")
                .containsEntry(ITEM_UNITS, "null")
                .containsEntry(ITEM_PRICE_PER_UNIT, "null");
        assertTrackerMdcKeysRemoved();
    }

    /**
     * Asserts each ISO control character and each U+2028 and U+2029 separator of the three metadata values
     * is written as a backslash, the letter {@code u} and four upper-case hexadecimal digits in the event's
     * text and MDC, other characters unchanged, and that the text is one line (D-339).
     */
    @Test
    public void trackEscapesControlCharactersInValues() {
        Map<String, Object> order = new HashMap<String, Object>();
        order.put(ITEM_NAME, "shoes\r\nERROR" + (char) 0x1B);
        order.put(ITEM_UNITS, "2" + (char) 0x2028);
        order.put(ITEM_PRICE_PER_UNIT, "10\t" + (char) 0x7F + (char) 0x2029 + (char) 0xE9);
        String escapedName = "shoes\\u000D\\u000AERROR\\u001B";
        String escapedUnits = "2\\u2028";
        String escapedPrice = "10\\u0009\\u007F\\u2029" + (char) 0xE9;

        tracker.track(order);

        ILoggingEvent event = singleInfoEvent();
        assertThat(event.getFormattedMessage())
                .isEqualTo("Business event Price: item name=" + escapedName + ", item units=" + escapedUnits
                        + ", item price per unit=" + escapedPrice)
                .doesNotContain("\r", "\n");
        assertThat(event.getMDCPropertyMap())
                .containsEntry(ITEM_NAME, escapedName)
                .containsEntry(ITEM_UNITS, escapedUnits)
                .containsEntry(ITEM_PRICE_PER_UNIT, escapedPrice);
        assertTrackerMdcKeysRemoved();
    }

    /**
     * Asserts an MDC entry the calling thread set before the call is present in the event's MDC beside the
     * five tracker entries and is still set after the call, while the five tracker keys are removed
     * (D-038).
     */
    @Test
    public void trackKeepsOtherMdcEntries() {
        MDC.put("requestId", "r-1");

        tracker.track(MESSAGE_ITEM);

        assertThat(singleInfoEvent().getMDCPropertyMap())
                .containsEntry("requestId", "r-1")
                .containsEntry(MDC_EVENT, "Price")
                .hasSize(6);
        assertThat(MDC.get("requestId")).isEqualTo("r-1");
        assertTrackerMdcKeysRemoved();
    }

    /**
     * Asserts the order map holding all {@code message.json} fields keeps its entries and value types after
     * the call (D-038).
     */
    @Test
    public void trackLeavesOrderUnchanged() {
        Map<String, Object> order = new HashMap<String, Object>();
        order.put("email", "aaa@abc.sk");
        order.put(ITEM_NAME, "shoes");
        order.put(ITEM_UNITS, 2);
        order.put(ITEM_PRICE_PER_UNIT, 10);
        order.put("membership", "free");
        Map<String, Object> before = new HashMap<String, Object>(order);

        tracker.track(order);

        assertThat(order).isEqualTo(before);
        assertThat(order.get(ITEM_UNITS)).isInstanceOf(Integer.class);
        assertThat(order.get(ITEM_PRICE_PER_UNIT)).isInstanceOf(Integer.class);
    }

    /**
     * Returns the only event the appender recorded, asserting there is exactly one and that it is INFO.
     *
     * @return the recorded event
     */
    private ILoggingEvent singleInfoEvent() {
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        return event;
    }

    /** Asserts each of the five tracker MDC keys is unset on the test thread. */
    private void assertTrackerMdcKeysRemoved() {
        for (String key : TRACKER_MDC_KEYS) {
            assertThat(MDC.get(key)).as("MDC key '%s'", key).isNull();
        }
    }
}

