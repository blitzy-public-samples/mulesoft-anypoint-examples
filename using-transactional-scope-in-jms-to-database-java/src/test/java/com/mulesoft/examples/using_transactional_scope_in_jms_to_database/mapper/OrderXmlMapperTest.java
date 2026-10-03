package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import javax.xml.stream.XMLStreamException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of {@link OrderXmlMapper#toOrder(String)}, the XML-to-object transform with the element alias
 * {@code order} [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:8-10] (D-418, D-419).
 *
 * <ul>
 *   <li>The committed original message {@code src/test/resources/original/message.xml}, read unchanged, binds to
 *       {@code Order [item_id=1, item_units=2, customer_id=1]}.</li>
 *   <li>A missing or empty child leaves 0, an attribute binds like a child, the namespace of the root element is
 *       ignored, and nothing after the root end tag is read.</li>
 *   <li>A root local name other than {@code order} raises {@link IllegalArgumentException}
 *       {@code Unknown root element: <name>}; text that is not well-formed at or before the root start tag raises
 *       {@link IllegalArgumentException} with the {@link XMLStreamException} as its cause; an unknown child, a value
 *       that does not read as an {@code int} and malformation after the root start tag raise
 *       {@link UncheckedIOException} with the Jackson exception as its cause; {@code null} raises
 *       {@link NullPointerException}.</li>
 * </ul>
 *
 * <p>Each test calls a mapper created with {@code new}; no Spring context starts.
 */
class OrderXmlMapperTest {

    /** Classpath location of the committed original test message. */
    private static final String ORIGINAL_MESSAGE = "/original/message.xml";

    /** Exact text of the committed original test message: tab-indented children, no final line feed. */
    private static final String ORIGINAL_MESSAGE_TEXT =
            "<order>\n\t<itemId>1</itemId>\n\t<itemUnits>2</itemUnits>\n\t<customerId>1</customerId>\n</order>";

    /** Mapper under test. */
    private final OrderXmlMapper mapper = new OrderXmlMapper();

    /**
     * Asserts the committed original message, read from the classpath as UTF-8 without trimming, is the 90-byte
     * document of the original test and binds to item 1, 2 units and customer 1, with the original
     * {@code toString()} text.
     *
     * @throws IOException when the message cannot be read
     */
    @Test
    void toOrderBindsOriginalMessageToItemOneTwoUnitsCustomerOne() throws IOException {
        byte[] bytes;
        try (InputStream in = OrderXmlMapperTest.class.getResourceAsStream(ORIGINAL_MESSAGE)) {
            assertThat(in).as(ORIGINAL_MESSAGE).isNotNull();
            bytes = in.readAllBytes();
        }
        String message = new String(bytes, StandardCharsets.UTF_8);

        Order order = mapper.toOrder(message);

        assertThat(bytes).hasSize(90);
        assertThat(message).isEqualTo(ORIGINAL_MESSAGE_TEXT);
        assertThat(order.getItemId()).isEqualTo(1);
        assertThat(order.getItemUnits()).isEqualTo(2);
        assertThat(order.getCustomerId()).isEqualTo(1);
        assertThat(order).hasToString("Order [item_id=1, item_units=2, customer_id=1]");
    }

    /**
     * Asserts each call returns a new {@link Order}.
     */
    @Test
    void toOrderReturnsNewOrderOnEachCall() {
        Order first = mapper.toOrder(ORIGINAL_MESSAGE_TEXT);
        Order second = mapper.toOrder(ORIGINAL_MESSAGE_TEXT);

        assertThat(first).isNotSameAs(second);
        assertThat(second).hasToString(first.toString());
    }

    /**
     * Asserts an XML declaration, a comment and a processing instruction before the root element are skipped.
     */
    @Test
    void toOrderSkipsDeclarationCommentAndProcessingInstructionBeforeRoot() {
        Order order = mapper.toOrder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- order -->\n<?step one?>\n"
                + "<order><itemId>4</itemId><itemUnits>5</itemUnits><customerId>6</customerId></order>");

        assertThat(order).hasToString("Order [item_id=4, item_units=5, customer_id=6]");
    }

    /**
     * Asserts a child absent from the document leaves its {@code int} at 0.
     */
    @Test
    void toOrderLeavesMissingChildrenAtZero() {
        Order order = mapper.toOrder("<order><itemId>7</itemId></order>");

        assertThat(order).hasToString("Order [item_id=7, item_units=0, customer_id=0]");
    }

    /**
     * Asserts a root element with no children gives 0 for every field.
     *
     * @param xml a root element without content
     */
    @ParameterizedTest
    @ValueSource(strings = {"<order/>", "<order></order>"})
    void toOrderOfEmptyRootLeavesEveryFieldAtZero(String xml) {
        Order order = mapper.toOrder(xml);

        assertThat(order).hasToString("Order [item_id=0, item_units=0, customer_id=0]");
    }

    /**
     * Asserts an empty child element, self-closed or with a start and end tag, gives 0 and leaves the other children
     * bound.
     *
     * @param xml an order whose {@code itemId} is empty
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<order><itemId/><itemUnits>2</itemUnits><customerId>3</customerId></order>",
            "<order><itemId></itemId><itemUnits>2</itemUnits><customerId>3</customerId></order>"})
    void toOrderReadsEmptyChildAsZero(String xml) {
        Order order = mapper.toOrder(xml);

        assertThat(order).hasToString("Order [item_id=0, item_units=2, customer_id=3]");
    }

    /**
     * Asserts an attribute of the root element binds like the child element of the same name.
     */
    @Test
    void toOrderBindsRootAttributeLikeChild() {
        Order order = mapper.toOrder("<order itemId=\"9\"/>");

        assertThat(order).hasToString("Order [item_id=9, item_units=0, customer_id=0]");
    }

    /**
     * Asserts a root element named {@code order} in a prefixed or a default namespace binds, with the namespace
     * ignored.
     *
     * @param xml an order whose root element carries a namespace
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<ns:order xmlns:ns=\"urn:orders\"><ns:itemId>1</ns:itemId><itemUnits>2</itemUnits>"
                    + "<customerId>3</customerId></ns:order>",
            "<order xmlns=\"urn:orders\"><itemId>1</itemId><itemUnits>2</itemUnits><customerId>3</customerId></order>"})
    void toOrderIgnoresNamespaceOfRootElement(String xml) {
        Order order = mapper.toOrder(xml);

        assertThat(order).hasToString("Order [item_id=1, item_units=2, customer_id=3]");
    }

    /**
     * Asserts content after the root end tag, well-formed or not, is not read.
     *
     * @param xml an order followed by further content
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<order><itemId>1</itemId></order><extra/>",
            "<order><itemId>1</itemId></order>trailing<"})
    void toOrderStopsReadingAtRootEndTag(String xml) {
        Order order = mapper.toOrder(xml);

        assertThat(order).hasToString("Order [item_id=1, item_units=0, customer_id=0]");
    }

    /**
     * Returns documents whose root local name is not {@code order}, with the rejection message of each.
     *
     * @return pairs of document and expected message
     */
    static Stream<Arguments> otherRootElements() {
        return Stream.of(
                arguments("<purchase><itemId>1</itemId></purchase>", "Unknown root element: purchase"),
                arguments("<Order><itemId>1</itemId></Order>", "Unknown root element: Order"),
                arguments("<ns:purchase xmlns:ns=\"urn:order\"/>", "Unknown root element: purchase"));
    }

    /**
     * Asserts a root element whose local name is not {@code order}, matched case-sensitively and without its prefix,
     * raises {@link IllegalArgumentException} {@code Unknown root element: <name>} with no cause.
     *
     * @param xml             a document with another root element
     * @param expectedMessage the rejection message
     */
    @ParameterizedTest
    @MethodSource("otherRootElements")
    void toOrderRejectsRootElementOtherThanOrder(String xml, String expectedMessage) {
        IllegalArgumentException rejection =
                catchThrowableOfType(() -> mapper.toOrder(xml), IllegalArgumentException.class);

        assertThat(rejection).hasMessage(expectedMessage).hasNoCause();
    }

    /**
     * Asserts a child element other than {@code itemId}, {@code itemUnits} and {@code customerId} raises
     * {@link UncheckedIOException} whose cause is an {@link UnrecognizedPropertyException} naming the child.
     */
    @Test
    void toOrderRejectsUnknownChildWithUnrecognizedPropertyException() {
        UncheckedIOException rejection = catchThrowableOfType(
                () -> mapper.toOrder("<order><itemId>1</itemId><price>3</price></order>"),
                UncheckedIOException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOfSatisfying(UnrecognizedPropertyException.class,
                cause -> assertThat(cause.getPropertyName()).isEqualTo("price"));
    }

    /**
     * Asserts a child value that is not a decimal integer raises {@link UncheckedIOException} whose cause is an
     * {@link InvalidFormatException} holding the value.
     *
     * @param value the text of {@code itemId}
     */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "0x10", "1.5"})
    void toOrderRejectsNonIntegerChildValue(String value) {
        UncheckedIOException rejection = catchThrowableOfType(
                () -> mapper.toOrder("<order><itemId>" + value + "</itemId></order>"), UncheckedIOException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOfSatisfying(InvalidFormatException.class,
                cause -> assertThat(cause.getValue()).isEqualTo(value));
    }

    /**
     * Asserts a child value outside the {@code int} range raises {@link UncheckedIOException} whose cause is a
     * {@link JsonMappingException} reporting the range.
     */
    @Test
    void toOrderRejectsChildValueOutsideIntRange() {
        UncheckedIOException rejection = catchThrowableOfType(
                () -> mapper.toOrder("<order><itemId>99999999999</itemId></order>"), UncheckedIOException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOf(JsonMappingException.class)
                .hasMessageContaining("out of range of int");
    }

    /**
     * Asserts text that is not well-formed XML at or before the root start tag, the empty string included, raises
     * {@link IllegalArgumentException} carrying the message of its {@link XMLStreamException} cause.
     *
     * @param xml text that fails before or at the root start tag
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not xml", "<order"})
    void toOrderRejectsTextNotWellFormedAtOrBeforeRootStartTag(String xml) {
        IllegalArgumentException rejection =
                catchThrowableOfType(() -> mapper.toOrder(xml), IllegalArgumentException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOf(XMLStreamException.class);
        assertThat(rejection).hasMessage(rejection.getCause().getMessage());
    }

    /**
     * Asserts a {@code DOCTYPE} declaration, with or without an internal subset, fails before the root element with
     * {@link IllegalArgumentException} carrying the message of its {@link XMLStreamException} cause.
     *
     * @param xml an order preceded by a document type declaration
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<!DOCTYPE order><order><itemId>1</itemId></order>",
            "<!DOCTYPE order [<!ENTITY units \"5\">]><order><itemUnits>&units;</itemUnits></order>"})
    void toOrderRejectsDoctypeBeforeRootElement(String xml) {
        IllegalArgumentException rejection =
                catchThrowableOfType(() -> mapper.toOrder(xml), IllegalArgumentException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOf(XMLStreamException.class);
        assertThat(rejection).hasMessage(rejection.getCause().getMessage());
    }

    /**
     * Asserts a document malformed after the root start tag, by a missing or a mismatched end tag, raises
     * {@link UncheckedIOException} whose cause is a Jackson {@link JsonParseException}.
     *
     * @param xml an order malformed inside the root element
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<order><itemId>1</itemId>",
            "<order><itemId>1</itemUnits></order>"})
    void toOrderRejectsDocumentMalformedAfterRootStartTag(String xml) {
        UncheckedIOException rejection = catchThrowableOfType(() -> mapper.toOrder(xml), UncheckedIOException.class);

        assertThat(rejection).isNotNull();
        assertThat(rejection.getCause()).isInstanceOf(JsonParseException.class);
    }

    /**
     * Asserts {@code null} input raises {@link NullPointerException}.
     */
    @Test
    void toOrderRejectsNullWithNullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> mapper.toOrder(null));
    }
}
