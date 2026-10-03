package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrderXmlMapper#toOrder(String)}, the {@code order}-alias XML-to-object transform of
 * {@code transactionsFlow1} [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:8-10]
 * (D-419).
 *
 * <ul>
 *   <li>The original test message {@code src/test/resources/original/message.xml}, read as its 90 untrimmed bytes,
 *       binds to {@code Order [item_id=1, item_units=2, customer_id=1]}.</li>
 *   <li>A root element other than {@code order} raises {@link IllegalArgumentException}.</li>
 *   <li>A child element {@link Order} has no property for raises {@link UncheckedIOException}.</li>
 *   <li>A missing child leaves its {@code int} at 0.</li>
 *   <li>Malformed XML raises {@link IllegalArgumentException} or {@link UncheckedIOException}.</li>
 * </ul>
 *
 * <p>Each test calls a mapper created with {@code new}; no Spring context starts. The tests run under surefire in
 * the {@code test} phase and cover the {@code mapper} package for the JaCoCo LINE check (D-049).
 *
 * <p>The class declares exactly these five public {@code @Test} methods, each one backward row of
 * {@code TRACEABILITY.md} mapped to {@code transactions.xml:8-10} and {@code OrderXmlMapper#toOrder}.
 */
public class OrderXmlMapperTest {

    /** Classpath location of the byte-identical copy of the original test message. */
    private static final String ORIGINAL_MESSAGE = "original/message.xml";

    /** Byte length of the original test message: LF line ends, tab-indented children, no final line feed. */
    private static final int ORIGINAL_MESSAGE_LENGTH = 90;

    /** Mapper under test, created without a Spring context. */
    private final OrderXmlMapper mapper = new OrderXmlMapper();

    /**
     * Asserts the original test message binds to item 1, 2 units and customer 1, and that the order's
     * {@code toString()} is the text the flow logs [transactions.xml:11].
     *
     * @throws IOException when the original test message cannot be read
     */
    @Test
    public void toOrderParsesOriginalMessage() throws IOException {
        Order order = mapper.toOrder(originalMessage());

        assertEquals(1, order.getItemId(), "itemId");
        assertEquals(2, order.getItemUnits(), "itemUnits");
        assertEquals(1, order.getCustomerId(), "customerId");
        assertEquals("Order [item_id=1, item_units=2, customer_id=1]", order.toString());
    }

    /**
     * Asserts a root element named {@code orders} raises {@link IllegalArgumentException}
     * {@code Unknown root element: orders}.
     */
    @Test
    public void toOrderRejectsNonOrderRoot() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mapper.toOrder("<orders><itemId>1</itemId></orders>"));

        assertEquals("Unknown root element: orders", e.getMessage());
    }

    /**
     * Asserts a child element {@code colour} raises {@link UncheckedIOException} whose cause is Jackson's
     * {@link UnrecognizedPropertyException} for that child.
     */
    @Test
    public void toOrderRejectsUnknownChild() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> mapper.toOrder("<order><itemId>1</itemId><colour>red</colour></order>"));

        UnrecognizedPropertyException cause = assertInstanceOf(UnrecognizedPropertyException.class, e.getCause());
        assertEquals("colour", cause.getPropertyName());
    }

    /**
     * Asserts an order without a {@code customerId} child binds {@code itemId} and {@code itemUnits} and leaves
     * {@code customerId} at 0.
     */
    @Test
    public void toOrderLeavesMissingChildAtZero() {
        Order order = mapper.toOrder("<order><itemId>1</itemId><itemUnits>2</itemUnits></order>");

        assertEquals(1, order.getItemId(), "itemId");
        assertEquals(2, order.getItemUnits(), "itemUnits");
        assertEquals(0, order.getCustomerId(), "customerId");
    }

    /**
     * Asserts each malformed document, an unterminated root end tag, a mismatched end tag and an unterminated root
     * start tag that fails before the root check, raises {@link IllegalArgumentException} or
     * {@link UncheckedIOException}.
     */
    @Test
    public void toOrderRejectsMalformedXml() {
        for (String input : List.of("<order><itemId>1</itemId></order", "<order><itemId>1</order>", "<order")) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> mapper.toOrder(input), input);

            assertTrue(e instanceof IllegalArgumentException || e instanceof UncheckedIOException,
                    () -> input + " raised " + e.getClass().getName());
        }
    }

    /**
     * Reads the original test message from the test classpath as UTF-8, unchanged.
     *
     * @return the 90-character text of {@code original/message.xml}
     * @throws IOException when the resource cannot be read
     */
    private String originalMessage() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ORIGINAL_MESSAGE)) {
            assertNotNull(in, ORIGINAL_MESSAGE);
            byte[] bytes = in.readAllBytes();
            assertEquals(ORIGINAL_MESSAGE_LENGTH, bytes.length, ORIGINAL_MESSAGE + " byte length");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
