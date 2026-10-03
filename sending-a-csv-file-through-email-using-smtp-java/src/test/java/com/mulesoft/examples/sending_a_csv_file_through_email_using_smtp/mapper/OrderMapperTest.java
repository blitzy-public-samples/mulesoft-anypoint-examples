package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrderMapper}, the Java form of DataWeave setter DW-29 of flow
 * {@code csv-to-smtpFlow} [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:8-17]
 * (D-034).
 *
 * <p>Each test calls an {@link OrderMapper} created with {@code new}, with no Spring application context
 * and no mocks. The sample input is the classpath resource {@code /input.csv} and the expected mail body
 * is the classpath resource {@code /email.txt}, the copies of
 * {@code sending-a-csv-file-through-email-using-smtp/src/main/resources/input.csv} and
 * {@code sending-a-csv-file-through-email-using-smtp/src/main/resources/email.txt}. The tests check the
 * DW-29 key order, the Java type and rendering of every number, and each branch of
 * {@link OrderMapper#dwNumber(BigDecimal)}.
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least
 * 0.80 (D-049).
 */
public class OrderMapperTest {

    /** Keys of each DW-29 order map, in the order the script declares them. */
    private static final List<String> DW29_KEYS =
            List.of("name", "orderId", "pricePerUnit", "units", "totalPrice");

    /** Header record of the committed sample {@code input.csv}. */
    private static final String HEADER = "orderId,name,units,pricePerUnit";

    /** Unit under test. */
    private final OrderMapper mapper = new OrderMapper();

    /**
     * Asserts the DW-29 output for the committed sample equals {@code email.txt}: the {@code toString()}
     * of the list mapped from {@code /input.csv} equals the full text of {@code /email.txt}, with no
     * trimming. Also asserts the two resources hold 133 and 57 UTF-8 bytes.
     *
     * @throws IOException if a classpath resource cannot be read
     */
    @Test
    public void toOrdersRendersCommittedInputAsCommittedEmailBody() throws IOException {
        String emailBody = resource("/email.txt");
        String inputCsv = resource("/input.csv");

        assertEquals(133, emailBody.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(57, inputCsv.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(emailBody, mapper.toOrders(inputCsv).toString());
    }

    /**
     * Asserts the committed sample maps to two orders and each order holds exactly the keys
     * {@code name}, {@code orderId}, {@code pricePerUnit}, {@code units} and {@code totalPrice}, in that
     * order.
     *
     * @throws IOException if the classpath resource cannot be read
     */
    @Test
    public void toOrdersKeepsDataWeaveFieldOrder() throws IOException {
        List<Map<String, Object>> orders = mapper.toOrders(resource("/input.csv"));

        assertEquals(2, orders.size());
        for (Map<String, Object> order : orders) {
            assertEquals(DW29_KEYS, new ArrayList<>(order.keySet()));
        }
    }

    /**
     * Asserts the values and Java types of the first sample order ({@code 1,aaa,2.0,10}): {@code name} is
     * {@code "aaa"}, {@code orderId} is the {@code String} {@code "1"}, and {@code units},
     * {@code pricePerUnit} and {@code totalPrice} are the {@code Integer} values 2, 10 and 20.
     *
     * @throws IOException if the classpath resource cannot be read
     */
    @Test
    public void toOrdersMapsFirstRowValuesAndTypes() throws IOException {
        Map<String, Object> order = mapper.toOrders(resource("/input.csv")).get(0);

        assertEquals("aaa", order.get("name"));
        assertEquals("1", assertInstanceOf(String.class, order.get("orderId")));
        assertEquals(Integer.valueOf(2), assertInstanceOf(Integer.class, order.get("units")));
        assertEquals(Integer.valueOf(10), assertInstanceOf(Integer.class, order.get("pricePerUnit")));
        assertEquals(Integer.valueOf(20), assertInstanceOf(Integer.class, order.get("totalPrice")));
    }

    /**
     * Asserts the values and Java types of the second sample order ({@code 2,bbb,4.15,5}):
     * {@code totalPrice} is a {@code BigDecimal} equal in value to {@code 20.75} and written
     * {@code 20.75}, {@code units} is a {@code BigDecimal} written {@code 4.15}, {@code pricePerUnit} is
     * the {@code Integer} 5 and {@code orderId} is the {@code String} {@code "2"}.
     *
     * @throws IOException if the classpath resource cannot be read
     */
    @Test
    public void toOrdersKeepsFractionalTotalPriceAsBigDecimal() throws IOException {
        Map<String, Object> order = mapper.toOrders(resource("/input.csv")).get(1);

        BigDecimal totalPrice = assertInstanceOf(BigDecimal.class, order.get("totalPrice"));
        assertEquals(0, totalPrice.compareTo(new BigDecimal("20.75")));
        assertEquals("20.75", totalPrice.toPlainString());

        BigDecimal units = assertInstanceOf(BigDecimal.class, order.get("units"));
        assertEquals("4.15", units.toPlainString());

        assertEquals(Integer.valueOf(5), assertInstanceOf(Integer.class, order.get("pricePerUnit")));
        assertEquals("2", assertInstanceOf(String.class, order.get("orderId")));
    }

    /**
     * Asserts each branch of {@link OrderMapper#dwNumber(BigDecimal)}: {@code 0.50} gives the
     * {@code BigDecimal} written {@code 0.5}, {@code 10} the {@code Integer} 10, {@code 10000000000} the
     * {@code Long} 10000000000 and {@code 12345678901234567890} the {@code BigInteger} of the same value.
     */
    @Test
    public void dwNumberNarrowsIntegralValuesAndStripsFractionalZeros() {
        BigDecimal fractional = assertInstanceOf(BigDecimal.class,
                OrderMapper.dwNumber(new BigDecimal("0.50")));
        assertEquals("0.5", fractional.toString());

        assertEquals(Integer.valueOf(10),
                assertInstanceOf(Integer.class, OrderMapper.dwNumber(new BigDecimal("10"))));

        assertEquals(Long.valueOf(10000000000L),
                assertInstanceOf(Long.class, OrderMapper.dwNumber(new BigDecimal("10000000000"))));

        assertEquals(new BigInteger("12345678901234567890"),
                assertInstanceOf(BigInteger.class,
                        OrderMapper.dwNumber(new BigDecimal("12345678901234567890"))));
    }

    /**
     * Asserts CSV text holding only the header record maps to an empty list written {@code []}.
     */
    @Test
    public void toOrdersReturnsEmptyListForHeaderOnlyCsv() {
        List<Map<String, Object>> orders = mapper.toOrders(HEADER);

        assertTrue(orders.isEmpty());
        assertEquals("[]", orders.toString());
    }

    /**
     * Asserts a record whose {@code units} cell is not a number ({@code abc}) raises a
     * {@link RuntimeException}.
     */
    @Test
    public void toOrdersRejectsNonNumericUnits() {
        assertThrows(RuntimeException.class, () -> mapper.toOrders(HEADER + "\n1,aaa,abc,10"));
    }

    /**
     * Asserts a header without a {@code name} column maps to one order whose {@code name} key is present
     * with a {@code null} value, and whose keys keep the DW-29 order.
     */
    @Test
    public void toOrdersMapsMissingNameColumnToNull() {
        List<Map<String, Object>> orders = mapper.toOrders("orderId,units,pricePerUnit\n1,2,10");

        assertEquals(1, orders.size());
        Map<String, Object> order = orders.get(0);
        assertTrue(order.containsKey("name"));
        assertNull(order.get("name"));
        assertEquals(DW29_KEYS, new ArrayList<>(order.keySet()));
    }

    /**
     * Reads a classpath resource fully and decodes it as UTF-8, with no trimming and no newline
     * normalisation.
     *
     * @param path the absolute classpath resource path, for example {@code /input.csv}
     * @return the full text of the resource
     * @throws IOException if the resource cannot be read
     */
    private String resource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
