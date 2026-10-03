package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.codehaus.stax2.XMLOutputFactory2;
import org.springframework.stereotype.Component;

import com.ctc.wstx.stax.WstxOutputFactory;

/**
 * Maps the CSV attachment of {@code pop-to-xmlFlow1} to the {@code orders} XML document of DW-14
 * (pop-to-xml.xml:13-25), re-implemented by hand without a DataWeave runtime (D-034). The layout
 * and reader settings below are the DW-14 layout row of {@code DECISIONS.md} (D-225).
 *
 * <p><b>Input.</b> The bytes of the first attachment, decoded as UTF-8 and read as CSV:
 *
 * <ul>
 *   <li>the first record is the header; it names the columns and is never written as an order;</li>
 *   <li>separator {@code ,}, quote {@code "}, escape {@code \}; header names are matched
 *       case-sensitively;</li>
 *   <li>LF, CRLF and CR all end a record; empty lines are skipped;</li>
 *   <li>values are taken unchanged: no trimming and no number conversion, so {@code 2.0} stays
 *       {@code 2.0};</li>
 *   <li>columns other than {@code orderId}, {@code name}, {@code units} and {@code pricePerUnit}
 *       are ignored, and the header's column order does not change the output order.</li>
 * </ul>
 *
 * <p><b>Output.</b> An XML document with the declaration {@code <?xml version='1.0'
 * encoding='UTF-8'?>}, LF line feeds, two spaces of indentation per level and no trailing line feed.
 * The root {@code orders} holds one {@code order} per data record, in record order, and each
 * {@code order} holds {@code orderId}, {@code name}, {@code units} and {@code pricePerUnit}, in
 * that order. For the sample {@code input.csv} ({@code orderId,name,units,pricePerUnit},
 * {@code 1,aaa,2.0,10}, {@code 2,bbb,4.15,5}) the result is:
 *
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <orders>
 *   <order>
 *     <orderId>1</orderId>
 *     <name>aaa</name>
 *     <units>2.0</units>
 *     <pricePerUnit>10</pricePerUnit>
 *   </order>
 *   <order>
 *     <orderId>2</orderId>
 *     <name>bbb</name>
 *     <units>4.15</units>
 *     <pricePerUnit>5</pricePerUnit>
 *   </order>
 * </orders>
 * }</pre>
 *
 * <p>Input with no data record (zero bytes, a header only, or only empty lines) gives
 * {@code <?xml version='1.0' encoding='UTF-8'?>} followed by a line feed and {@code <orders/>}.
 * The document is written by the Woodstox StAX writer, instantiated directly, with no namespaces,
 * attributes or CDATA sections.
 *
 * <p>Instances hold no mutable state; {@link #toOrdersXml(byte[])} is side-effect free and safe for
 * concurrent use.
 */
@Component
public class OrdersXmlMapper {

    /**
     * The child elements of each {@code order}, in output order; each is read from the column of the
     * same name.
     */
    private static final List<String> ORDER_FIELDS = List.of("orderId", "name", "units", "pricePerUnit");

    /**
     * Separator {@code ,}, quote {@code "}, escape {@code \}, header taken from the first record and
     * skipped, empty lines ignored, values neither trimmed nor stripped of surrounding spaces.
     * {@code Builder.get()} returns the same format as the deprecated {@code Builder.build()} (D-225).
     */
    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDelimiter(',')
            .setQuote('"')
            .setEscape('\\')
            .setIgnoreEmptyLines(true)
            .setTrim(false)
            .setIgnoreSurroundingSpaces(false)
            .get();

    private static final String XML_ENCODING = "UTF-8";
    private static final String XML_VERSION = "1.0";
    private static final String ORDERS_ELEMENT = "orders";
    private static final String ORDER_ELEMENT = "order";

    /** Whitespace written after the declaration and before the closing {@code </orders>} tag. */
    private static final String LINE_FEED = "\n";

    /** Whitespace written before each {@code <order>} and {@code </order>} tag. */
    private static final String ORDER_INDENT = "\n  ";

    /** Whitespace written before each field element of an {@code order}. */
    private static final String FIELD_INDENT = "\n    ";

    /**
     * Woodstox output factory with automatic empty elements disabled: a start tag followed by an end
     * tag is written as {@code <x></x>}, and only an explicit empty element as {@code <x/>}.
     */
    private final XMLOutputFactory xmlOutputFactory;

    /** Creates a mapper with its own Woodstox output factory (D-225). */
    public OrdersXmlMapper() {
        XMLOutputFactory factory = new WstxOutputFactory();
        factory.setProperty(XMLOutputFactory2.P_AUTOMATIC_EMPTY_ELEMENTS, Boolean.FALSE);
        this.xmlOutputFactory = factory;
    }

    /**
     * Maps the CSV attachment content to the DW-14 {@code orders} XML document described on this
     * class.
     *
     * <p>Field forms within each {@code order}:
     *
     * <ul>
     *   <li>a column the header does not name, or a cell missing from a record shorter than the
     *       header, gives an empty element, for example {@code <pricePerUnit/>};</li>
     *   <li>an empty cell gives a start and an end tag with no text, for example
     *       {@code <name></name>};</li>
     *   <li>any other cell gives its text unchanged between start and end tags, with {@code &}
     *       written as {@code &amp;}, {@code <} as {@code &lt;} and a carriage return as
     *       {@code &#xd;}; a {@code >} is written as {@code &gt;} at the start of a value or after
     *       {@code ]}, and unchanged elsewhere; quotes, apostrophes and tabs are written
     *       unchanged.</li>
     * </ul>
     *
     * @param csv the attachment content, UTF-8 encoded CSV whose first record is the header
     * @return the XML document text: the declaration line, then the {@code orders} element, joined
     *     with LF line feeds and without a trailing line feed
     * @throws NullPointerException if {@code csv} is {@code null}
     * @throws UncheckedIOException if the CSV is malformed, for example a quoted value that is never
     *     closed
     * @throws IllegalArgumentException if the header is rejected by the CSV reader, for example a
     *     header with an empty column name
     * @throws IllegalStateException if the XML writer rejects the output, for example a value holding
     *     a control character that XML 1.0 does not allow
     */
    public String toOrdersXml(byte[] csv) {
        Objects.requireNonNull(csv, "csv");
        String text = new String(csv, StandardCharsets.UTF_8);
        StringWriter output = new StringWriter();
        try (CSVParser parser = CSVParser.parse(text, CSV_FORMAT)) {
            XMLStreamWriter writer = xmlOutputFactory.createXMLStreamWriter(output);
            writeDocument(writer, parser);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot write the orders XML document", e);
        }
        return output.toString();
    }

    /**
     * Writes the declaration, a line feed and the {@code orders} element holding one {@code order}
     * per record of {@code records}, then ends, flushes and closes {@code writer}.
     */
    private static void writeDocument(XMLStreamWriter writer, Iterable<CSVRecord> records)
            throws XMLStreamException {
        writer.writeStartDocument(XML_ENCODING, XML_VERSION);
        writer.writeCharacters(LINE_FEED);
        boolean ordersOpen = false;
        for (CSVRecord record : records) {
            if (!ordersOpen) {
                writer.writeStartElement(ORDERS_ELEMENT);
                ordersOpen = true;
            }
            writeOrder(writer, record);
        }
        if (ordersOpen) {
            writer.writeCharacters(LINE_FEED);
            writer.writeEndElement();
        } else {
            writer.writeEmptyElement(ORDERS_ELEMENT);
        }
        writer.writeEndDocument();
        writer.flush();
        writer.close();
    }

    /** Writes one indented {@code order} element holding the {@link #ORDER_FIELDS} of {@code record}. */
    private static void writeOrder(XMLStreamWriter writer, CSVRecord record) throws XMLStreamException {
        writer.writeCharacters(ORDER_INDENT);
        writer.writeStartElement(ORDER_ELEMENT);
        for (String field : ORDER_FIELDS) {
            writer.writeCharacters(FIELD_INDENT);
            writeField(writer, record, field);
        }
        writer.writeCharacters(ORDER_INDENT);
        writer.writeEndElement();
    }

    /**
     * Writes {@code <field/>} when {@code record} has no value for column {@code field}, and
     * otherwise {@code <field>}, the value as text, and {@code </field>}.
     */
    private static void writeField(XMLStreamWriter writer, CSVRecord record, String field)
            throws XMLStreamException {
        if (!record.isSet(field)) {
            writer.writeEmptyElement(field);
            return;
        }
        writer.writeStartElement(field);
        writer.writeCharacters(record.get(field));
        writer.writeEndElement();
    }
}
