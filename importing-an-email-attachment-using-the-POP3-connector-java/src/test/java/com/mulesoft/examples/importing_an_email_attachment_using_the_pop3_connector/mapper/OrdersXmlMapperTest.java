package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Unit tests of {@link OrdersXmlMapper#toOrdersXml(byte[])}, the DW-14 re-implementation
 * [importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:13-25] (D-034).
 *
 * <p>Each test calls an {@link OrdersXmlMapper} created with {@code new}, with no Spring application
 * context and no mocks. The expected document of the sample attachment is held inline in
 * {@link #EXPECTED_XML}; the only resources read are the classpath copies {@code /input.csv} and
 * {@code /original/orders.xsd}. The layout, the field forms for missing and empty cells and the
 * escaping of {@code &}, {@code <} and {@code >} asserted here are the forms of D-225.
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at
 * least 0.80 (D-049).
 */
public class OrdersXmlMapperTest {

    /**
     * The document for the sample {@code input.csv}: the declaration line, then the {@code orders}
     * element with two {@code order} elements, LF line feeds, two-space indentation and no trailing
     * line feed.
     */
    private static final String EXPECTED_XML =
          "<?xml version='1.0' encoding='UTF-8'?>\n"
        + "<orders>\n"
        + "  <order>\n"
        + "    <orderId>1</orderId>\n"
        + "    <name>aaa</name>\n"
        + "    <units>2.0</units>\n"
        + "    <pricePerUnit>10</pricePerUnit>\n"
        + "  </order>\n"
        + "  <order>\n"
        + "    <orderId>2</orderId>\n"
        + "    <name>bbb</name>\n"
        + "    <units>4.15</units>\n"
        + "    <pricePerUnit>5</pricePerUnit>\n"
        + "  </order>\n"
        + "</orders>";

    /** The header record of the sample {@code input.csv}. */
    private static final String HEADER = "orderId,name,units,pricePerUnit";

    /** The declaration line that starts every document the mapper returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** The size in bytes of the classpath copy of the sample {@code input.csv}. */
    private static final int INPUT_CSV_LENGTH = 57;

    private final OrdersXmlMapper mapper = new OrdersXmlMapper();

    @Test
    @DisplayName("DW-14 sample input.csv renders the two-order orders document byte for byte")
    public void sampleCsvRendersOrdersDocument() throws IOException {
        byte[] csv = classpathInputCsv();
        assertThat(csv).hasSize(INPUT_CSV_LENGTH);

        String xml = mapper.toOrdersXml(csv);

        assertThat(xml).isEqualTo(EXPECTED_XML);
        assertThat(xml.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(EXPECTED_XML.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("DW-14 header-only CSV renders an empty orders element")
    public void headerOnlyCsvRendersEmptyOrders() {
        String xml = mapper.toOrdersXml(utf8(HEADER));

        assertThat(xml).isEqualTo(DECLARATION + "\n<orders/>");
    }

    @Test
    @DisplayName("DW-14 ampersand, less-than and greater-than in a name are written as escaped text")
    public void markupCharactersInNameAreEscaped() throws Exception {
        String xml = mapper.toOrdersXml(utf8(HEADER + "\n1,a&b<c>d,2.0,10"));

        assertThat(xml)
                .contains("<name>a&amp;b&lt;c>d</name>")
                .doesNotContain("&b")
                .doesNotContain("<c");
        assertThat(firstElementText(xml, "name")).isEqualTo("a&b<c>d");
    }

    @Test
    @DisplayName("DW-14 quoted CSV field holding a comma is one name value")
    public void quotedFieldWithCommaIsOneValue() {
        String xml = mapper.toOrdersXml(utf8(HEADER + "\n1,\"x,y\",2.0,10"));

        assertThat(xml)
                .contains("<name>x,y</name>")
                .contains("<units>2.0</units>")
                .contains("<pricePerUnit>10</pricePerUnit>")
                .containsOnlyOnce("<order>");
    }

    @Test
    @DisplayName("DW-14 CRLF and CR-only record separators render the same document as LF")
    public void crlfAndCrOnlyInputMatchLfOutput() {
        String lfCsv = HEADER + "\n1,aaa,2.0,10\n2,bbb,4.15,5";
        String crlfCsv = lfCsv.replace("\n", "\r\n");
        String crCsv = lfCsv.replace("\n", "\r");

        String lfXml = mapper.toOrdersXml(utf8(lfCsv));
        String crlfXml = mapper.toOrdersXml(utf8(crlfCsv));
        String crXml = mapper.toOrdersXml(utf8(crCsv));

        assertThat(lfXml).isEqualTo(EXPECTED_XML);
        assertThat(crlfXml).isEqualTo(lfXml);
        assertThat(crXml).isEqualTo(lfXml);
        assertThat(lfXml).doesNotContain("\r");
        assertThat(crlfXml).doesNotContain("\r");
        assertThat(crXml).doesNotContain("\r");
    }

    @Test
    @DisplayName("DW-14 missing last cell renders a self-closed element and an empty cell a start-end pair")
    public void shortRecordAndEmptyValueRenderAsEmptyElements() {
        String shortRecordXml = mapper.toOrdersXml(utf8(HEADER + "\n1,aaa,2.0"));
        String emptyValueXml = mapper.toOrdersXml(utf8(HEADER + "\n1,aaa,2.0,"));

        assertThat(shortRecordXml)
                .contains("<units>2.0</units>")
                .contains("<pricePerUnit/>")
                .doesNotContain("<pricePerUnit></pricePerUnit>");
        assertThat(emptyValueXml)
                .contains("<units>2.0</units>")
                .contains("<pricePerUnit></pricePerUnit>")
                .doesNotContain("<pricePerUnit/>");
    }

    @Test
    @DisplayName("DW-14 one-row output is valid against the original orders.xsd")
    public void oneRowOutputValidatesAgainstOriginalXsd() throws Exception {
        URL xsd = getClass().getResource("/original/orders.xsd");
        assertThat(xsd).isNotNull();
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd);

        String xml = mapper.toOrdersXml(utf8(HEADER + "\n1,aaa,2.0,10"));

        assertThatCode(() -> schema.newValidator().validate(new StreamSource(new StringReader(xml))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DW-14 unterminated quoted value in the header throws UncheckedIOException")
    public void unterminatedQuotedHeaderThrowsUncheckedIoException() {
        byte[] csv = utf8("orderId,\"name,units,pricePerUnit\n1,aaa,2.0,10");

        assertThatThrownBy(() -> mapper.toOrdersXml(csv))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("DW-14 control character forbidden by XML 1.0 in a value throws IllegalStateException")
    public void forbiddenControlCharacterThrowsIllegalStateException() {
        byte[] csv = utf8(HEADER + "\n1,a\u0001b,2.0,10");

        assertThatThrownBy(() -> mapper.toOrdersXml(csv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot write the orders XML document")
                .hasCauseInstanceOf(XMLStreamException.class);
    }

    /** Returns the UTF-8 bytes of {@code s}. */
    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Returns the bytes of the classpath resource {@code /input.csv}. */
    private byte[] classpathInputCsv() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/input.csv")) {
            assertThat(in).as("classpath resource /input.csv").isNotNull();
            return in.readAllBytes();
        }
    }

    /** Parses {@code xml} with a DOM parser and returns the text of the first element named {@code tag}. */
    private static String firstElementText(String xml, String tag) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return document.getElementsByTagName(tag).item(0).getTextContent();
    }
}
