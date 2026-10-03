package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.StringJoiner;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Maps the CSV attachment text to the orders XML document of DW-13
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:17-29]
 * (D-034, D-063, D-215, D-216).
 *
 * <p>Input: CSV text whose first record is the header {@code orderId,name,units,pricePerUnit}.
 * Output: one {@code <order>} element per CSV record, in input order, inside a single
 * {@code <orders>} root. For the sample attachment {@code src/main/resources/input.csv}
 * the result is exactly:
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
 * <p>Instances hold no state; {@link #toOrdersXml(String)} is side-effect free and safe for
 * concurrent use.
 */
@Component
public class OrdersXmlMapper {

    /** XML declaration written as the first line of every document. */
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** Columns read from each CSV record, in the order their elements are written. */
    private static final List<String> FIELDS = List.of("orderId", "name", "units", "pricePerUnit");

    /**
     * CSV reading rules: separator {@code ,}, quote {@code "}, escape {@code \}, the first
     * record is the header and is not mapped, empty lines are skipped, values are not trimmed,
     * and CR, LF and CRLF each end a record (D-215).
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setEscape('\\')
            .get();

    /** Separator between output lines. */
    private static final String NEWLINE = "\n";

    /** Indent of an {@code <order>} element. */
    private static final String ORDER_INDENT = "  ";

    /** Indent of a field element inside an {@code <order>}. */
    private static final String FIELD_INDENT = "    ";

    /**
     * Maps CSV order records to the orders XML document of DW-13
     * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:17-29],
     * re-implemented by hand (D-034). The CSV text is read with commons-csv (D-063, D-215) and
     * the document is written in the layout of D-216.
     *
     * <p>Each CSV record becomes one {@code <order>} element holding {@code orderId},
     * {@code name}, {@code units} and {@code pricePerUnit}, in that order, each on its own line.
     * Values are copied as the text read from the CSV, with no number conversion: {@code 2.0}
     * stays {@code 2.0}. Text has {@code &}, {@code <} and {@code >} escaped as {@code &amp;},
     * {@code &lt;} and {@code &gt;}; quotes are written unchanged. An empty value, a column the
     * header lacks and a column a short record lacks are each written as a self-closed element
     * such as {@code <units/>}. Columns other than the four are not written.
     *
     * <p>The document starts with {@code <?xml version='1.0' encoding='UTF-8'?>}, indents two
     * spaces per level, joins lines with {@code \n} and has no trailing newline. Text with no
     * record (an empty string or a header only) yields exactly
     * {@code <?xml version='1.0' encoding='UTF-8'?>\n<orders/>}. A newline after the last
     * record, and CR or CRLF line endings, yield the same document as the LF-separated text.
     *
     * @param csv the CSV attachment text; its first record is the header
     * @return the orders XML document
     * @throws NullPointerException if {@code csv} is {@code null}
     * @throws IllegalArgumentException if the header has an empty column name
     * @throws UncheckedIOException if the CSV text is malformed, such as a quoted value that is
     *     never closed
     */
    public String toOrdersXml(String csv) {
        List<CSVRecord> records = readRecords(csv);
        StringJoiner xml = new StringJoiner(NEWLINE);
        xml.add(XML_DECLARATION);
        if (records.isEmpty()) {
            xml.add("<orders/>");
            return xml.toString();
        }
        xml.add("<orders>");
        for (CSVRecord record : records) {
            addOrder(xml, record);
        }
        xml.add("</orders>");
        return xml.toString();
    }

    /**
     * Parses the CSV text into its data records, in input order.
     *
     * @param csv the CSV text
     * @return the records after the header
     */
    private static List<CSVRecord> readRecords(String csv) {
        try (CSVParser parser = CSVParser.parse(new StringReader(csv), FORMAT)) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Adds the lines of one {@code <order>} element.
     *
     * @param xml the document lines
     * @param record the CSV record of the order
     */
    private static void addOrder(StringJoiner xml, CSVRecord record) {
        xml.add(ORDER_INDENT + "<order>");
        for (String field : FIELDS) {
            xml.add(FIELD_INDENT + element(field, value(record, field)));
        }
        xml.add(ORDER_INDENT + "</order>");
    }

    /**
     * Returns the value of a column, or an empty string when the record has no such column.
     *
     * @param record the CSV record
     * @param column the column name
     * @return the value as read, or {@code ""}
     */
    private static String value(CSVRecord record, String column) {
        return record.isSet(column) ? record.get(column) : "";
    }

    /**
     * Renders one field element: {@code <name>text</name>}, or {@code <name/>} for an empty value,
     * the same form as for a missing column (D-216).
     *
     * @param name the element name
     * @param value the element text
     * @return the element markup
     */
    private static String element(String name, String value) {
        if (value.isEmpty()) {
            return "<" + name + "/>";
        }
        return "<" + name + ">" + escapeText(value) + "</" + name + ">";
    }

    /**
     * Escapes {@code &}, {@code <} and {@code >} in element text.
     *
     * @param text the raw text
     * @return the escaped text
     */
    private static String escapeText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
