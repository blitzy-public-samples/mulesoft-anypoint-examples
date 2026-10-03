package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Unit tests of {@link ReportMapper#toReportCsv(Document)}, the DW-24 report of {@code OrderFlow}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:30-37] (D-034): item count, price
 * total and average price over every {@code item} of the orders document, with no year filter, written as
 * CSV.
 *
 * <p>Each test calls the mapper directly, with no Spring application context, on a document parsed from
 * the committed {@code input/orders.xml} or from synthetic XML, and asserts
 * <ul>
 *   <li>the report of the committed sample equals the committed {@code original/report.csv};</li>
 *   <li>the count, the plain total and the {@link MathContext#DECIMAL128} average of synthetic items, with
 *       {@code \n} line endings;</li>
 *   <li>the {@link IllegalArgumentException} for a document without items (D-164);</li>
 *   <li>the count of an item without a {@code price} child and the total and average of the prices
 *       read (D-164);</li>
 *   <li>the {@link IllegalArgumentException} for a document element other than {@code orders}
 *       (D-164).</li>
 * </ul>
 * These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class ReportMapperTest {

    /** Header record of the DW-24 CSV, without its line ending. */
    private static final String HEADER = "totalNumberOfItems,totalAmount,averageSellingPrice";

    /** Report of the committed {@code input/orders.xml}. */
    private static final String COMMITTED_REPORT = HEADER + "\n4,149.93,37.4825\n";

    /** Byte length of the committed {@code original/report.csv}. */
    private static final int COMMITTED_REPORT_BYTES = 68;

    /** Parser feature that rejects a document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";

    /** The mapper under test. */
    private final ReportMapper mapper = new ReportMapper();

    /**
     * Asserts the report of the committed {@code input/orders.xml} (prices 30, 29.99, 49.99 and 39.95,
     * the two 2003 items included) is exactly {@code 4,149.93,37.4825} under the header, and equals the
     * 68 bytes of the committed {@code original/report.csv}.
     *
     * @throws Exception when a classpath resource cannot be read or parsed
     */
    @Test
    public void committedSampleMatchesOriginalReportCsv() throws Exception {
        Document orders;
        try (InputStream in = open("input/orders.xml")) {
            orders = parse(in);
        }

        String csv = mapper.toReportCsv(orders);

        assertEquals(COMMITTED_REPORT, csv, "report.csv text for the committed sample");
        String original = resource("original/report.csv");
        assertEquals(COMMITTED_REPORT_BYTES, original.getBytes(StandardCharsets.UTF_8).length,
                "byte length of the committed original/report.csv");
        assertEquals(original, csv, "report.csv text equals the committed original/report.csv");
    }

    /**
     * Asserts three items priced 3, 3 and 4, from the years 2003, 2004 and 2005, give the count 3, the
     * total 10 and the {@link MathContext#DECIMAL128} quotient of 10 by 3 in plain notation, each record
     * ended by {@code \n} and no {@code \r} in the text.
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void threeItemsGiveCountSumAndDecimal128Average() throws Exception {
        Document orders = parse(orders(
                item("First", "Author One", "3", "2003"),
                item("Second", "Author Two", "3", "2004"),
                item("Third", "Author Three", "4", "2005")));

        String csv = mapper.toReportCsv(orders);

        String average = new BigDecimal(10).divide(new BigDecimal(3), MathContext.DECIMAL128).toPlainString();
        assertEquals(HEADER + "\n3,10," + average + "\n", csv,
                "report.csv text for three items priced 3, 3 and 4");
        assertFalse(csv.contains("\r"), "report.csv text has LF line endings only");
    }

    /**
     * Asserts an {@code orders} document without items raises {@link IllegalArgumentException} with the
     * message {@code No items to average}. Zero-item report per D-164.
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void zeroItemsThrowIllegalArgumentException() throws Exception {
        Document orders = parse("<orders/>");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> mapper.toReportCsv(orders),
                        "report of an orders document without items");
        assertEquals("No items to average", thrown.getMessage(), "message of the zero-item exception");
    }

    /**
     * Asserts an item without a {@code price} child is counted in {@code totalNumberOfItems} and adds no
     * price: items priced 3 and 4 plus one unpriced item give the count 3, the total 7 and the
     * {@link MathContext#DECIMAL128} quotient of 7 by the 2 prices read. Unpriced item per D-164. This
     * test drives the unpriced-item path of {@link ReportMapper#toReportCsv(Document)} under the mapper
     * coverage floor (D-049).
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void itemWithoutPriceIsCountedAndAddsNoPrice() throws Exception {
        Document orders = parse(orders(
                item("First", "Author One", "3", "2003"),
                item("Unpriced", "Author Two", null, "2004"),
                item("Third", "Author Three", "4", "2005")));

        String csv = mapper.toReportCsv(orders);

        String average = new BigDecimal(7).divide(new BigDecimal(2), MathContext.DECIMAL128).toPlainString();
        assertEquals(HEADER + "\n3,7," + average + "\n", csv,
                "report.csv text for items priced 3 and 4 and one item without a price");
    }

    /**
     * Asserts a document whose document element is not {@code orders} has no items and raises
     * {@link IllegalArgumentException} with the message {@code No items to average}, although it holds
     * a priced {@code item}. Document element name per D-164. This test drives the other-document-element
     * path of {@link ReportMapper#toReportCsv(Document)} under the mapper coverage floor (D-049).
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void documentElementOtherThanOrdersThrowsIllegalArgumentException() throws Exception {
        Document orders = parse("<order>" + item("First", "Author One", "3", "2005") + "</order>");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> mapper.toReportCsv(orders),
                        "report of a document whose document element is order");
        assertEquals("No items to average", thrown.getMessage(),
                "message of the exception for a document element other than orders");
    }

    /**
     * Opens the classpath resource {@code path}.
     *
     * @param path the resource path relative to the classpath root
     * @return the open stream, which the caller closes
     */
    private static InputStream open(String path) {
        InputStream in = ReportMapperTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, "classpath resource " + path);
        return in;
    }

    /**
     * Reads the classpath resource {@code path} as UTF-8 text, untrimmed.
     *
     * @param path the resource path relative to the classpath root
     * @return the full text of the resource
     * @throws IOException when the resource cannot be read
     */
    private static String resource(String path) throws IOException {
        try (InputStream in = open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses {@code in} with a namespace-aware parser that rejects a document type declaration.
     *
     * @param in the XML bytes
     * @return the parsed document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the bytes are not well-formed XML
     * @throws IOException when the bytes cannot be read
     */
    private static Document parse(InputStream in)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder().parse(in);
    }

    /**
     * Parses the XML text {@code xml}, encoded as UTF-8, with {@link #parse(InputStream)}.
     *
     * @param xml the XML text
     * @return the parsed document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed XML
     * @throws IOException when the text cannot be read
     */
    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Wraps {@code items} in an {@code orders} document element.
     *
     * @param items the {@code item} elements, in document order
     * @return the XML text of the orders document
     */
    private static String orders(String... items) {
        return "<orders>" + String.join("", items) + "</orders>";
    }

    /**
     * Builds one {@code item} element of the {@code orders.xml} shape.
     *
     * @param title the {@code properties/title} text
     * @param author the one {@code properties/authors/author} text
     * @param price the {@code price} text, or {@code null} for an item without a {@code price} element
     * @param year the {@code properties/year} text
     * @return the XML text of the item
     */
    private static String item(String title, String author, String price, String year) {
        String priceElement = price == null ? "" : "<price>" + price + "</price>";
        return "<item><type>book</type>" + priceElement + "<properties><title>" + title
                + "</title><authors><author>" + author + "</author></authors><year>" + year
                + "</year></properties></item>";
    }
}
