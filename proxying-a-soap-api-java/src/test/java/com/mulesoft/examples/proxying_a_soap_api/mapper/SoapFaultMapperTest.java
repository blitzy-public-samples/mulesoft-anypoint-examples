package com.mulesoft.examples.proxying_a_soap_api.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Unit tests of {@link SoapFaultMapper#serverFault(String)}, the {@code soap:Server} fault body of the default
 * branch of flow {@code main} [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:5-12] (D-023, D-245, D-361).
 *
 * <p>Each test calls {@code new SoapFaultMapper()} directly, with no Spring application context and no mocks. It
 * compares the returned bytes with the UTF-8 bytes of {@link #envelope(String)}, the fixed one-line SOAP 1.1
 * envelope around an expected {@code faultstring} content, and, where stated, parses them with a
 * namespace-aware DOM parser. The fault body stays unverified against the original runtime until the Tier 2A
 * fixture {@code proxying-a-soap-api_proxy-soap-fault} exists (D-023). These tests cover every line of
 * {@code SoapFaultMapper} (D-049).
 *
 * <p>Six tests: the plain message, markup escaping and UTF-8 encoding, plus the {@code null} argument, quotes and
 * control characters, and the code points XML 1.0 forbids (D-476). The class and its six test methods are public,
 * and each is a backward row of {@code TRACEABILITY.md} (D-133).
 */
public class SoapFaultMapperTest {

    /** Namespace of the SOAP 1.1 envelope. */
    private static final String SOAP_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** Envelope text up to and including the opening {@code faultstring} tag. */
    private static final String ENVELOPE_PREFIX =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>";

    /** Envelope text from the closing {@code faultstring} tag to the end of the envelope. */
    private static final String ENVELOPE_SUFFIX = "</faultstring></soap:Fault></soap:Body></soap:Envelope>";

    /** Message of the exception raised for text holding a code point XML 1.0 does not allow (D-361). */
    private static final String INVALID_TEXT = "Fault string holds a character that XML 1.0 does not allow";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The mapper under test. */
    private final SoapFaultMapper mapper = new SoapFaultMapper();

    /**
     * Returns the expected envelope text around an already escaped {@code faultstring} content.
     *
     * @param escapedFaultString the {@code faultstring} content as it appears in the envelope
     * @return the fixed prefix, {@code escapedFaultString} and the fixed suffix, concatenated
     */
    static String envelope(String escapedFaultString) {
        return ENVELOPE_PREFIX + escapedFaultString + ENVELOPE_SUFFIX;
    }

    /**
     * Asserts the {@code RequestSendException} message gives the exact envelope bytes, with no XML declaration
     * and no tab, LF or CR, and that the bytes parse to an {@code Envelope} in the SOAP 1.1 envelope namespace
     * whose {@code faultcode} is {@code soap:Server} and whose {@code faultstring} is the message.
     *
     * @throws Exception when the bytes cannot be parsed
     */
    @Test
    public void serverFaultWrapsPlainMessage() throws Exception {
        String message = "Error sending HTTP request.";

        byte[] body = mapper.serverFault(message);

        assertArrayEquals(envelope(message).getBytes(StandardCharsets.UTF_8), body);
        String text = new String(body, StandardCharsets.UTF_8);
        assertFalse(text.startsWith("<?xml"), "XML declaration written");
        assertFalse(text.contains("\n"), "LF written");
        assertFalse(text.contains("\r"), "CR written");
        assertFalse(text.contains("\t"), "tab written");

        Document document = parse(body);
        Element root = document.getDocumentElement();
        assertEquals("Envelope", root.getLocalName());
        assertEquals(SOAP_ENVELOPE_NS, root.getNamespaceURI());
        assertEquals(1, document.getElementsByTagNameNS(SOAP_ENVELOPE_NS, "Body").getLength());
        assertEquals(1, document.getElementsByTagNameNS(SOAP_ENVELOPE_NS, "Fault").getLength());
        assertEquals("soap:Server", single(document, "faultcode").getTextContent());
        assertEquals(message, single(document, "faultstring").getTextContent());
    }

    /**
     * Asserts {@code &}, {@code <} and {@code >} in faultstring are escaped, {@code &} first, and that the parsed
     * {@code faultstring} text equals the raw input.
     *
     * @throws Exception when the bytes cannot be parsed
     */
    @Test
    public void serverFaultEscapesMarkupCharacters() throws Exception {
        String message = "a & b < c > d &amp;";

        byte[] body = mapper.serverFault(message);

        assertArrayEquals(envelope("a &amp; b &lt; c &gt; d &amp;amp;").getBytes(StandardCharsets.UTF_8), body);
        assertEquals(message, single(parse(body), "faultstring").getTextContent());
    }

    /**
     * Asserts non-ASCII text, including the XML 1.0 range limits U+D7FF, U+E000 and U+FFFD and the surrogate pair
     * of U+1F600, is written unchanged as UTF-8, and that the parsed {@code faultstring} text equals the input.
     *
     * @throws Exception when the bytes cannot be parsed
     */
    @Test
    public void serverFaultEncodesUtf8() throws Exception {
        // "Fehler: Größe überschritten"
        String message = "Fehler: Gr\u00f6\u00dfe \u00fcberschritten";

        byte[] body = mapper.serverFault(message);

        assertArrayEquals(envelope(message).getBytes(StandardCharsets.UTF_8), body);
        assertFalse(Arrays.equals(envelope(message).getBytes(StandardCharsets.ISO_8859_1), body),
                "bytes equal the ISO-8859-1 encoding");
        assertEquals(message, single(parse(body), "faultstring").getTextContent());

        String limits = "\ud7ff \ue000 \ufffd \ud83d\ude00";

        byte[] limitsBody = mapper.serverFault(limits);

        assertArrayEquals(envelope(limits).getBytes(StandardCharsets.UTF_8), limitsBody);
        assertEquals(limits, single(parse(limitsBody), "faultstring").getTextContent());
    }

    /**
     * Asserts a {@code null} argument gives the envelope with an empty {@code faultstring} (D-245).
     *
     * @throws Exception when the bytes cannot be parsed
     */
    @Test
    public void serverFaultWritesEmptyFaultStringForNull() throws Exception {
        byte[] body = mapper.serverFault(null);

        assertArrayEquals(envelope("").getBytes(StandardCharsets.UTF_8), body);
        assertEquals("", single(parse(body), "faultstring").getTextContent());
    }

    /**
     * Asserts quotes, apostrophes, tab, LF and CR in faultstring are written unchanged, with no entity or
     * character reference (D-245, D-361).
     */
    @Test
    public void serverFaultKeepsQuotesAndControlCharacters() {
        String message = "He said \"stop\" and 'go'\tnext\nline\rend";

        byte[] body = mapper.serverFault(message);

        assertArrayEquals(envelope(message).getBytes(StandardCharsets.UTF_8), body);
        String text = new String(body, StandardCharsets.UTF_8);
        assertFalse(text.contains("&quot;"), "quote escaped");
        assertFalse(text.contains("&apos;"), "apostrophe escaped");
        assertFalse(text.contains("&#"), "character reference written");
    }

    /**
     * Asserts text holding U+0000, U+0001, U+001F, U+FFFE, U+FFFF, a lone high surrogate, a lone low surrogate or
     * a low surrogate before a high one raises {@link IllegalArgumentException} with the fixed message, which does
     * not repeat the text (D-361).
     */
    @Test
    public void serverFaultRejectsCharactersXmlForbids() {
        List<String> forbidden = List.of(
                "secret\u0000",
                "secret\u0001",
                "secret\u001f",
                "secret\ufffe",
                "secret\uffff",
                "secret\ud800",
                "secret\udc00",
                "secret\ude00\ud83d");

        for (String message : forbidden) {
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> mapper.serverFault(message));
            assertEquals(INVALID_TEXT, thrown.getMessage());
            assertFalse(thrown.getMessage().contains("secret"), "message repeats the text");
            assertNull(thrown.getCause());
        }
    }

    /**
     * Parses envelope bytes with a namespace-aware DOM parser that rejects document type declarations.
     *
     * @param body the envelope bytes
     * @return the parsed document
     * @throws Exception when the bytes are not well-formed XML
     */
    private static Document parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    /**
     * Returns the one unqualified element of the given local name, asserting it occurs once and has no namespace.
     *
     * @param document the parsed envelope
     * @param localName the local name of the element
     * @return the element
     */
    private static Element single(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        assertEquals(1, nodes.getLength(), localName + " count");
        Element element = (Element) nodes.item(0);
        assertNull(element.getNamespaceURI(), localName + " namespace");
        return element;
    }
}
