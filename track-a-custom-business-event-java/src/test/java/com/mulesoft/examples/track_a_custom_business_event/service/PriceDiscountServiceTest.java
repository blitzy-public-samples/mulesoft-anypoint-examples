package com.mulesoft.examples.track_a_custom_business_event.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link PriceDiscountService#customBusinessEventsFlow1(byte[])}, the port of flow
 * {@code custom-business-eventsFlow1} [track-a-custom-business-event/src/main/app/custom-business-events.xml:4-25]
 * and its JRuby discount script [:12-22] (SC-07, D-034), with JUnit 5 and Mockito and no Spring
 * application context.
 *
 * <p>Each test builds a new service on a plain {@link ObjectMapper} and a Mockito mock of
 * {@link BusinessEventTracker}, so every test starts with no discount set (D-071). The mock copies the
 * map of each {@link BusinessEventTracker#track(Map)} call into {@link #snapshot}. Request bodies carry
 * the {@code message.json} fields [track-a-custom-business-event/src/test/resources/message.json] with
 * the item name and price per unit under test. The tests assert the discounted price text of the three
 * listed items, the reuse of the last discount by an unlisted item and the exception raised while none
 * is set (D-071), the string price read (D-479), the exceptions of malformed, empty, {@code null} and
 * incomplete bodies, and the tracker calls of each case. These tests cover the {@code service} package
 * under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public; no test sets a {@code @DisplayName}.
 */
public class PriceDiscountServiceTest {

    /** Request map key of the item name [custom-business-events.xml:13]. */
    private static final String ITEM_NAME = "item name";

    /** Request map key of the item units [custom-business-events.xml:9]. */
    private static final String ITEM_UNITS = "item units";

    /** Request map key of the item price per unit [custom-business-events.xml:21]. */
    private static final String ITEM_PRICE_PER_UNIT = "item price per unit";

    /** An item name the JRuby {@code case} does not list [custom-business-events.xml:13-20]. */
    private static final String UNLISTED_ITEM = "hats";

    /** Mocked recorder of the {@code Price} business event (SC-08). */
    private BusinessEventTracker tracker;

    /** Service under test, created with no discount set. */
    private PriceDiscountService service;

    /** Copy of the map passed to the most recent tracker call, {@code null} for a {@code null} map. */
    private Map<String, Object> snapshot;

    /**
     * Creates the tracker mock, whose {@code track} copies its map argument into {@link #snapshot}, and a
     * new service on a plain {@link ObjectMapper} and that mock.
     */
    @BeforeEach
    void setUp() {
        tracker = mock(BusinessEventTracker.class);
        snapshot = null;
        doAnswer(invocation -> {
            Map<String, ?> order = invocation.getArgument(0);
            snapshot = order == null ? null : new HashMap<String, Object>(order);
            return null;
        }).when(tracker).track(any());
        service = new PriceDiscountService(new ObjectMapper(), tracker);
    }

    /** Asserts {@code shoes} at price 10 returns {@code 8.5} and is tracked once (SC-07, D-034). */
    @Test
    public void shoesAtTenReturns8Point5() throws IOException {
        assertThat(service.customBusinessEventsFlow1(body("shoes", "10"))).isEqualTo("8.5");

        verify(tracker, times(1)).track(any());
    }

    /** Asserts {@code jeans} at price 10 returns {@code 8.0} and is tracked once (SC-07, D-034). */
    @Test
    public void jeansAtTenReturns8Point0() throws IOException {
        assertThat(service.customBusinessEventsFlow1(body("jeans", "10"))).isEqualTo("8.0");

        verify(tracker, times(1)).track(any());
    }

    /** Asserts {@code jackets} at price 10 returns {@code 7.0} and is tracked once (SC-07, D-034). */
    @Test
    public void jacketsAtTenReturns7Point0() throws IOException {
        assertThat(service.customBusinessEventsFlow1(body("jackets", "10"))).isEqualTo("7.0");

        verify(tracker, times(1)).track(any());
    }

    /**
     * Asserts {@code shoes} at price 9.99 returns the {@link Double#toString(double)} text of the
     * {@code double} product {@code 9.99 * (1 - 0.15)} (SC-07, D-034).
     */
    @Test
    public void shoesDiscountUsesDoubleArithmetic() throws IOException {
        assertThat(service.customBusinessEventsFlow1(body("shoes", "9.99")))
                .isEqualTo(Double.toString(9.99 * (1 - 0.15)));
    }

    /**
     * Asserts an unlisted item after {@code jeans} is priced with the {@code jeans} discount and returns
     * {@code 8.0}, and that both calls are tracked (D-071).
     */
    @Test
    public void unlistedItemReusesLastDiscount() throws IOException {
        assertThat(service.customBusinessEventsFlow1(body("jeans", "10"))).isEqualTo("8.0");

        assertThat(service.customBusinessEventsFlow1(body(UNLISTED_ITEM, "10"))).isEqualTo("8.0");
        verify(tracker, times(2)).track(any());
    }

    /**
     * Asserts an unlisted item after {@code shoes} and then {@code jackets} is priced with the
     * {@code jackets} discount, the one set most recently, and returns {@code 7.0} (D-071).
     */
    @Test
    public void unlistedItemUsesMostRecentDiscount() throws IOException {
        service.customBusinessEventsFlow1(body("shoes", "10"));
        service.customBusinessEventsFlow1(body("jackets", "10"));

        assertThat(service.customBusinessEventsFlow1(body(UNLISTED_ITEM, "10"))).isEqualTo("7.0");
        verify(tracker, times(3)).track(any());
    }

    /**
     * Asserts an unlisted item on a service with no discount set throws {@link IllegalStateException}
     * after the tracker received the request map with price 10 once (D-071).
     */
    @Test
    public void unlistedItemFirstThrowsIllegalState() {
        assertThrows(IllegalStateException.class,
                () -> service.customBusinessEventsFlow1(body(UNLISTED_ITEM, "10")));

        verify(tracker, times(1)).track(any());
        assertThat(snapshot)
                .containsEntry(ITEM_NAME, UNLISTED_ITEM)
                .containsEntry(ITEM_PRICE_PER_UNIT, 10);
    }

    /**
     * Asserts the tracker receives, once per call, the parsed request map with all five fields and the
     * undiscounted price 10 (SC-07, SC-08).
     */
    @Test
    public void trackerReceivesUndiscountedMap() throws IOException {
        service.customBusinessEventsFlow1(body("shoes", "10"));

        verify(tracker, times(1)).track(any());
        assertThat(snapshot).isEqualTo(Map.of(
                "email", "aaa@abc.sk",
                ITEM_NAME, "shoes",
                ITEM_UNITS, 2,
                ITEM_PRICE_PER_UNIT, 10,
                "membership", "free"));

        service.customBusinessEventsFlow1(body("jeans", "10"));

        verify(tracker, times(2)).track(any());
        assertThat(snapshot)
                .containsEntry(ITEM_NAME, "jeans")
                .containsEntry(ITEM_UNITS, 2)
                .containsEntry(ITEM_PRICE_PER_UNIT, 10);
    }

    /** Asserts a malformed JSON body throws {@link IOException} and the tracker is never called. */
    @Test
    public void malformedJsonThrowsWithoutTracking() {
        assertThrows(IOException.class, () -> service.customBusinessEventsFlow1("{".getBytes(UTF_8)));

        verifyNoInteractions(tracker);
    }

    /**
     * Asserts a {@code null} body, read as an empty body, throws {@link IOException} and the tracker is
     * never called.
     */
    @Test
    public void nullBodyThrowsWithoutTracking() {
        assertThrows(IOException.class, () -> service.customBusinessEventsFlow1(null));

        verifyNoInteractions(tracker);
    }

    /**
     * Asserts the JSON document {@code null} is tracked once as a {@code null} map and then throws
     * {@link NullPointerException}.
     */
    @Test
    public void jsonNullDocumentThrowsNullPointerAfterTracking() {
        assertThrows(NullPointerException.class,
                () -> service.customBusinessEventsFlow1("null".getBytes(UTF_8)));

        verify(tracker, times(1)).track(isNull());
        assertThat(snapshot).isNull();
    }

    /**
     * Asserts a listed item without {@code item price per unit} throws {@link NullPointerException} after
     * one tracker call.
     */
    @Test
    public void missingPriceThrowsNullPointer() {
        assertThrows(NullPointerException.class,
                () -> service.customBusinessEventsFlow1(bodyWithoutPrice("shoes")));

        verify(tracker, times(1)).track(any());
        assertThat(snapshot).doesNotContainKey(ITEM_PRICE_PER_UNIT);
    }

    /**
     * Asserts a JSON-string price returns the empty string for each listed item, the string repeated
     * {@code (int) (1 - discount)} times (D-479).
     */
    @Test
    public void stringPriceReturnsEmptyString() throws IOException {
        for (String item : new String[] {"shoes", "jeans", "jackets"}) {
            assertThat(service.customBusinessEventsFlow1(body(item, "\"10\""))).as(item).isEmpty();
        }

        verify(tracker, times(3)).track(any());
    }

    /**
     * Asserts a boolean, array or object price throws {@link ClassCastException} after one tracker call
     * each (D-479).
     */
    @Test
    public void nonNumericNonStringPriceThrowsClassCast() {
        String[] prices = {"true", "[10]", "{\"amount\":10}"};
        for (String price : prices) {
            assertThrows(ClassCastException.class,
                    () -> service.customBusinessEventsFlow1(body("shoes", price)), price);
        }

        verify(tracker, times(prices.length)).track(any());
    }

    /**
     * Returns the UTF-8 bytes of a {@code message.json} request with the given item name and the given
     * JSON text as {@code item price per unit}.
     *
     * @param item the {@code item name} value
     * @param priceJson the JSON text of the {@code item price per unit} value
     * @return the request body
     */
    private static byte[] body(String item, String priceJson) {
        return ("{\"email\":\"aaa@abc.sk\",\"item name\":\"" + item + "\",\"item units\":2,"
                + "\"item price per unit\":" + priceJson + ",\"membership\":\"free\"}").getBytes(UTF_8);
    }

    /**
     * Returns the UTF-8 bytes of a {@code message.json} request with the given item name and no
     * {@code item price per unit} key.
     *
     * @param item the {@code item name} value
     * @return the request body
     */
    private static byte[] bodyWithoutPrice(String item) {
        return ("{\"email\":\"aaa@abc.sk\",\"item name\":\"" + item + "\",\"item units\":2,"
                + "\"membership\":\"free\"}").getBytes(UTF_8);
    }
}
