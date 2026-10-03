package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

import org.springframework.stereotype.Component;

/**
 * Converts an {@code <order>} XML document into an {@link Order}: the XML-to-object transform with the element alias
 * {@code order} of {@code transactionsFlow1}
 * [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:8-10] (D-063, D-418, D-419).
 *
 * <p>Child elements {@code itemId}, {@code itemUnits} and {@code customerId} bind as {@code int}s to the
 * {@link Order} properties of the same names; a missing child leaves 0. Whitespace between elements, an XML
 * declaration, and comments or processing instructions before the root element are ignored. For the tab-indented
 * original test message {@code src/test/resources/original/message.xml}
 *
 * <pre>{@code
 * <order>
 *     <itemId>1</itemId>
 *     <itemUnits>2</itemUnits>
 *     <customerId>1</customerId>
 * </order>
 * }</pre>
 *
 * <p>the result is {@code Order [item_id=1, item_units=2, customer_id=1]}.
 *
 * <p>The document is read through the StAX input factory of the mapper's own {@code XmlFactory}. Instances hold no
 * mutable state; {@link #toOrder(String)} has no side effects and is safe for concurrent use.
 */
@Component
public class OrderXmlMapper {

    /** Local name of the only accepted root element, the alias {@code order} [transactions.xml:9]. */
    private static final String ROOT_ELEMENT = "order";

    /** Jackson XML mapper that fails on a child element {@link Order} has no property for (D-419). */
    private final XmlMapper xmlMapper = XmlMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * Reads one {@code <order>} document into a new {@link Order} (D-419).
     *
     * @param xml the XML document, the text payload of a message on queue {@code in} [transactions.xml:7]
     * @return the order whose {@code itemId}, {@code itemUnits} and {@code customerId} hold the values of the child
     *     elements of the same names, and 0 for each child that is absent
     * @throws NullPointerException if {@code xml} is {@code null}
     * @throws IllegalArgumentException with the message {@code Unknown root element: <name>} if the local name of
     *     the root element is not {@code order}; or, with the StAX {@link XMLStreamException} as its cause, if the
     *     text is not well-formed XML at or before the root start tag, the empty string included
     * @throws UncheckedIOException with the Jackson exception as its cause if the root holds a child element other
     *     than the three above ({@code UnrecognizedPropertyException}), if a child value does not read as an
     *     {@code int}, or if the document is malformed after the root start tag
     */
    public Order toOrder(String xml) {
        try {
            XMLStreamReader reader = xmlMapper.getFactory().getXMLInputFactory()
                    .createXMLStreamReader(new StringReader(xml));
            reader.nextTag();
            String localName = reader.getLocalName();
            if (!ROOT_ELEMENT.equals(localName)) {
                throw new IllegalArgumentException("Unknown root element: " + localName);
            }
            return xmlMapper.readValue(reader, Order.class);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
