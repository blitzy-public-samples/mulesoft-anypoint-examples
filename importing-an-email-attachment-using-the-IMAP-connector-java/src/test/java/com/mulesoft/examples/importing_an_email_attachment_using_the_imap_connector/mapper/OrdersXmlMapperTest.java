package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrdersXmlMapper#toOrdersXml(String)} (DW-13)
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:17-29].
 *
 * <p>Each test calls a {@link OrdersXmlMapper} created with {@code new}, with no Spring application context and no
 * mocks. The checked document layout is the one of D-216: the declaration
 * {@code <?xml version='1.0' encoding='UTF-8'?>}, lines joined with {@code \n}, two spaces of indentation per level,
 * no trailing newline, {@code <x/>} for an empty or missing value and {@code <orders/>} for zero records. The sample
 * input is the classpath resource {@code /input.csv}, the copy of
 * {@code importing-an-email-attachment-using-the-IMAP-connector/src/main/resources/input.csv}. The exceptions of
 * D-215 and D-360 and {@code null} input are not exercised by this class (D-423).
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
public class OrdersXmlMapperTest {

    /** CSV header record of the sample attachment. */
    private static final String HEADER = "orderId,name,units,pricePerUnit";

    /** Orders document for the two records of {@code /input.csv}, with no trailing newline. */
    private static final String EXPECTED = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<orders>",
            "  <order>",
            "    <orderId>1</orderId>",
            "    <name>aaa</name>",
            "    <units>2.0</units>",
            "    <pricePerUnit>10</pricePerUnit>",
            "  </order>",
            "  <order>",
            "    <orderId>2</orderId>",
            "    <name>bbb</name>",
            "    <units>4.15</units>",
            "    <pricePerUnit>5</pricePerUnit>",
            "  </order>",
            "</orders>");

    /** Unit under test. */
    private final OrdersXmlMapper mapper = new OrdersXmlMapper();

    /**
     * Reads the classpath resource {@code /input.csv} as UTF-8 text.
     *
     * @return the sample CSV attachment text
     * @throws IOException if the resource cannot be read
     */
    private String sampleCsv() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/input.csv")) {
            assertNotNull(in, "classpath resource /input.csv");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("maps the committed input.csv sample to the orders document")
    public void mapsClasspathSample() throws IOException {
        String result = mapper.toOrdersXml(sampleCsv());

        assertEquals(EXPECTED, result);
    }

    @Test
    @DisplayName("header-only CSV yields an empty orders element")
    public void headerOnlyYieldsEmptyOrders() {
        String result = mapper.toOrdersXml(HEADER);

        assertEquals("<?xml version='1.0' encoding='UTF-8'?>\n<orders/>", result);
    }

    @Test
    @DisplayName("escapes ampersand and less-than in values")
    public void escapesXmlSpecialCharacters() {
        String result = mapper.toOrdersXml(HEADER + "\n1,a&b<c,2,3");

        assertTrue(result.contains("<name>a&amp;b&lt;c</name>"), result);
    }

    @Test
    @DisplayName("reads a quoted field containing a comma")
    public void readsQuotedField() {
        String result = mapper.toOrdersXml(HEADER + "\n1,\"a,b\",2,3");

        assertTrue(result.contains("<name>a,b</name>"), result);
        assertTrue(result.contains("<units>2</units>"), result);
    }

    @Test
    @DisplayName("accepts CR-only line endings")
    public void acceptsCrOnlyLineEndings() throws IOException {
        String result = mapper.toOrdersXml(sampleCsv().replace("\n", "\r"));

        assertEquals(EXPECTED, result);
    }

    @Test
    @DisplayName("accepts CRLF line endings")
    public void acceptsCrlfLineEndings() throws IOException {
        String result = mapper.toOrdersXml(sampleCsv().replace("\n", "\r\n"));

        assertEquals(EXPECTED, result);
    }

    @Test
    @DisplayName("self-closes a column missing from a short record")
    public void selfClosesMissingLastColumn() {
        String result = mapper.toOrdersXml(HEADER + "\n1,aaa,2.0");

        assertTrue(result.contains("<pricePerUnit/>"), result);
        assertTrue(result.contains("<orderId>1</orderId>"), result);
        assertTrue(result.contains("<name>aaa</name>"), result);
        assertTrue(result.contains("<units>2.0</units>"), result);
    }

    @Test
    @DisplayName("self-closes an empty field")
    public void selfClosesEmptyField() {
        String result = mapper.toOrdersXml(HEADER + "\n1,,2.0,10");

        assertTrue(result.contains("<name/>"), result);
    }

    @Test
    @DisplayName("a newline after the last record yields the same orders document")
    public void trailingNewlineYieldsSameDocument() throws IOException {
        String result = mapper.toOrdersXml(sampleCsv() + "\n");

        assertEquals(EXPECTED, result);
    }
}
