package com.mulesoft.examples.legacy_modernization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.xml.SimpleNamespaceContext;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;

/**
 * Tier 1 contract test of the WSDL served at {@code GET /OrderFulfillment?wsdl}, scenario
 * {@code legacy-modernization_wsdl-fulfillment}: asserts the served WSDL equals the committed contract
 * {@code wsdl/IFulfillmentService.wsdl} with the address rewritten (D-028, D-540, D-671).
 *
 * <p>The application runs on a random port with the {@code test} profile, and {@code file.legacy-fulfillment.path}
 * is a JUnit temporary directory. The test method fetches the WSDL over loopback HTTP and reports every deviation
 * from the contract in one {@code assertAll}:
 * <ul>
 *   <li>status {@code 200} and a {@code Content-Type} starting with {@code text/xml};</li>
 *   <li>the names and QNames of the definitions, service, port, binding, portType, operation, messages, parts,
 *       wrapper elements and response type, the {@code soapAction}, the binding and operation styles, the
 *       transport and the body {@code use}, with every QName prefix resolved on the element that carries it;</li>
 *   <li>the per-request {@code soap:address} {@code http://localhost:<port>/OrderFulfillment};</li>
 *   <li>the served text, equal to the committed file with its single {@code http://localhost:1080/OrderFulfillment}
 *       replaced by that address;</li>
 *   <li>the SHA-256 and byte length of the committed file;</li>
 *   <li>the root element {@code {http://schemas.xmlsoap.org/wsdl/}definitions} of the source of the
 *       {@code IFulfillmentService} {@link SimpleWsdl11Definition} bean.</li>
 * </ul>
 *
 * <p>Contract values are literals in this class, independent of the {@code WsConfig} constants (D-671).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WsdlContractTest {

    /** Namespace of the WSDL 1.1 elements. */
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

    /** Namespace of the WSDL 1.1 SOAP binding elements. */
    private static final String SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";

    /** Namespace of the XML Schema elements. */
    private static final String XS_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Target namespace of the {@code IFulfillmentService} contract. */
    private static final String TNS = "http://ordermgmt.org/";

    /** Transport URI of the SOAP binding. */
    private static final String SOAP_HTTP_TRANSPORT = "http://schemas.xmlsoap.org/soap/http";

    /** Classpath location of the committed contract. */
    private static final String COMMITTED_WSDL = "wsdl/IFulfillmentService.wsdl";

    /** {@code soap:address} location in the committed contract. */
    private static final String COMMITTED_ADDRESS = "http://localhost:1080/OrderFulfillment";

    /** SHA-256 of the committed contract, lower-case hexadecimal. */
    private static final String COMMITTED_SHA256 = "cff29af6a058714a28d2df0f3425605b5c809de0a9a2229ba1d1caf1c5111b1f";

    /** Byte length of the committed contract. */
    private static final int COMMITTED_LENGTH = 4739;

    /** Connect timeout of the WSDL request, in milliseconds. */
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** Read timeout of the WSDL request, in milliseconds. */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    /** Root element of the served WSDL. */
    private static final String DEFINITIONS = "/wsdl:definitions";

    /** Service elements. */
    private static final String SERVICE = DEFINITIONS + "/wsdl:service";

    /** Port elements of the service. */
    private static final String PORT = SERVICE + "/wsdl:port";

    /** SOAP address elements of the port. */
    private static final String ADDRESS = PORT + "/soap:address";

    /** Binding elements named {@code IFulfillmentServiceSoapBinding}. */
    private static final String BINDING = DEFINITIONS + "/wsdl:binding[@name='IFulfillmentServiceSoapBinding']";

    /** Operation elements named {@code putShippingOrder} of the binding. */
    private static final String BINDING_OPERATION = BINDING + "/wsdl:operation[@name='putShippingOrder']";

    /** PortType elements named {@code IFulfillment}. */
    private static final String PORT_TYPE = DEFINITIONS + "/wsdl:portType[@name='IFulfillment']";

    /** Operation elements of the portType. */
    private static final String PORT_TYPE_OPERATION = PORT_TYPE + "/wsdl:operation";

    /** Message elements named {@code putShippingOrder}. */
    private static final String REQUEST_MESSAGE = DEFINITIONS + "/wsdl:message[@name='putShippingOrder']";

    /** Message elements named {@code putShippingOrderResponse}. */
    private static final String RESPONSE_MESSAGE = DEFINITIONS + "/wsdl:message[@name='putShippingOrderResponse']";

    /** Inline schema elements. */
    private static final String SCHEMA = DEFINITIONS + "/wsdl:types/xs:schema";

    /** Sequence of the {@code putShippingOrderResponse} complex type. */
    private static final String RESPONSE_TYPE_SEQUENCE =
            SCHEMA + "/xs:complexType[@name='putShippingOrderResponse']/xs:sequence";

    /** Output directory of the shipping-order CSV files, bound to {@code file.legacy-fulfillment.path}. */
    @TempDir
    static Path outputDir;

    /** Port of the embedded server. */
    @LocalServerPort
    int port;

    /** The {@code IFulfillmentService} WSDL definition bean. */
    @Autowired
    @Qualifier("IFulfillmentService")
    SimpleWsdl11Definition definition;

    /** XPath evaluator with the prefixes {@code wsdl}, {@code soap} and {@code xs} bound. */
    private final XPath xpath = newXPath();

    /**
     * Binds {@code file.legacy-fulfillment.path} to {@link #outputDir}.
     *
     * @param registry the dynamic property registry of the test context
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("file.legacy-fulfillment.path", () -> outputDir.toString());
    }

    /**
     * Fetches {@code GET http://localhost:<port>/OrderFulfillment?wsdl} and asserts, in one {@code assertAll}, the
     * status and {@code Content-Type}, the structure of the served WSDL, its text against the committed contract
     * with the address rewritten, the pin of the committed contract and the root element of the
     * {@code IFulfillmentService} definition bean (D-028, D-540).
     *
     * @throws IOException when the request cannot be sent or the committed contract cannot be read
     */
    @Test
    @DisplayName("legacy-modernization_wsdl-fulfillment")
    void wsdlFulfillment() throws IOException {
        String address = "http://localhost:" + port + "/OrderFulfillment";

        // Loopback connection without a proxy, with connect and read timeouts (D-671).
        HttpURLConnection connection =
                (HttpURLConnection) URI.create(address + "?wsdl").toURL().openConnection(Proxy.NO_PROXY);
        int status;
        String contentType;
        byte[] body;
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            status = connection.getResponseCode();
            contentType = connection.getHeaderField("Content-Type");
            body = readBody(connection, status);
        }
        finally {
            connection.disconnect();
        }

        byte[] committedBytes = new ClassPathResource(COMMITTED_WSDL).getContentAsByteArray();
        String committed = new String(committedBytes, StandardCharsets.UTF_8);
        String expected = committed.replace(COMMITTED_ADDRESS, address);
        String served = new String(body, StandardCharsets.UTF_8);
        Document wsdl = parseOrNull(body);

        assertAll("legacy-modernization_wsdl-fulfillment",
                // Status and media type.
                () -> assertThat(status).as("status of GET %s?wsdl", address).isEqualTo(200),
                () -> assertThat(contentType).as("Content-Type of GET %s?wsdl", address)
                        .isNotNull().startsWithIgnoringCase("text/xml"),

                // Served body as namespace-aware XML.
                () -> assertDoesNotThrow(() -> parse(body), "served body is namespace-aware well-formed XML"),

                // Definitions.
                count(wsdl, DEFINITIONS, 1),
                attribute(wsdl, DEFINITIONS, "targetNamespace", TNS),
                attribute(wsdl, DEFINITIONS, "name", "IFulfillmentService"),

                // Service and port.
                count(wsdl, SERVICE, 1),
                attribute(wsdl, SERVICE, "name", "IFulfillmentService"),
                count(wsdl, PORT, 1),
                attribute(wsdl, PORT, "name", "IFulfillmentPort"),
                qname(wsdl, PORT, "binding", new QName(TNS, "IFulfillmentServiceSoapBinding")),

                // Binding.
                count(wsdl, BINDING, 1),
                qname(wsdl, BINDING, "type", new QName(TNS, "IFulfillment")),
                count(wsdl, BINDING + "/soap:binding", 1),
                attribute(wsdl, BINDING + "/soap:binding", "style", "document"),
                attribute(wsdl, BINDING + "/soap:binding", "transport", SOAP_HTTP_TRANSPORT),

                // PortType.
                count(wsdl, PORT_TYPE, 1),
                count(wsdl, PORT_TYPE_OPERATION, 1),
                attribute(wsdl, PORT_TYPE_OPERATION, "name", "putShippingOrder"),
                attribute(wsdl, PORT_TYPE_OPERATION + "/wsdl:input", "name", "putShippingOrder"),
                qname(wsdl, PORT_TYPE_OPERATION + "/wsdl:input", "message", new QName(TNS, "putShippingOrder")),
                attribute(wsdl, PORT_TYPE_OPERATION + "/wsdl:output", "name", "putShippingOrderResponse"),
                qname(wsdl, PORT_TYPE_OPERATION + "/wsdl:output", "message",
                        new QName(TNS, "putShippingOrderResponse")),

                // Binding operation.
                count(wsdl, BINDING_OPERATION, 1),
                () -> assertThat(single(wsdl, BINDING_OPERATION + "/soap:operation").hasAttribute("soapAction"))
                        .as("%s/soap:operation has the attribute soapAction", BINDING_OPERATION).isTrue(),
                attribute(wsdl, BINDING_OPERATION + "/soap:operation", "soapAction", ""),
                attribute(wsdl, BINDING_OPERATION + "/soap:operation", "style", "document"),
                attribute(wsdl, BINDING_OPERATION + "/wsdl:input/soap:body", "use", "literal"),
                attribute(wsdl, BINDING_OPERATION + "/wsdl:output/soap:body", "use", "literal"),

                // Messages and parts.
                count(wsdl, REQUEST_MESSAGE + "/wsdl:part", 1),
                attribute(wsdl, REQUEST_MESSAGE + "/wsdl:part", "name", "parameters"),
                qname(wsdl, REQUEST_MESSAGE + "/wsdl:part", "element", new QName(TNS, "putShippingOrder")),
                count(wsdl, RESPONSE_MESSAGE + "/wsdl:part", 1),
                attribute(wsdl, RESPONSE_MESSAGE + "/wsdl:part", "name", "parameters"),
                qname(wsdl, RESPONSE_MESSAGE + "/wsdl:part", "element", new QName(TNS, "putShippingOrderResponse")),

                // Inline schema, wrapper element declarations of the two parts and the response type.
                count(wsdl, SCHEMA, 1),
                attribute(wsdl, SCHEMA, "targetNamespace", TNS),
                attribute(wsdl, SCHEMA, "elementFormDefault", "unqualified"),
                qname(wsdl, SCHEMA + "/xs:element[@name='putShippingOrder']", "type",
                        new QName(TNS, "putShippingOrder")),
                qname(wsdl, SCHEMA + "/xs:element[@name='putShippingOrderResponse']", "type",
                        new QName(TNS, "putShippingOrderResponse")),
                count(wsdl, RESPONSE_TYPE_SEQUENCE, 1),
                count(wsdl, RESPONSE_TYPE_SEQUENCE + "/xs:element", 1),
                attribute(wsdl, RESPONSE_TYPE_SEQUENCE + "/xs:element", "name", "ShippingOrderConfirmation"),
                qname(wsdl, RESPONSE_TYPE_SEQUENCE + "/xs:element", "type",
                        new QName(TNS, "shippingOrderConfirmation")),

                // Per-request address.
                count(wsdl, ADDRESS, 1),
                attribute(wsdl, ADDRESS, "location", address),

                // Served text against the committed contract.
                () -> assertThat(served).as("served WSDL text").isEqualTo(expected),
                () -> assertThat(occurrences(committed, COMMITTED_ADDRESS))
                        .as("occurrences of %s in %s", COMMITTED_ADDRESS, COMMITTED_WSDL).isEqualTo(1),

                // Pin of the committed contract.
                () -> assertThat(sha256Hex(committedBytes)).as("SHA-256 of %s", COMMITTED_WSDL)
                        .isEqualTo(COMMITTED_SHA256),
                () -> assertThat(committedBytes.length).as("byte length of %s", COMMITTED_WSDL)
                        .isEqualTo(COMMITTED_LENGTH),

                // IFulfillmentService definition bean.
                () -> assertThat(definitionRoot().getNamespaceURI())
                        .as("namespace of the root element of the IFulfillmentService definition source")
                        .isEqualTo(WSDL_NS),
                () -> assertThat(definitionRoot().getLocalName())
                        .as("local name of the root element of the IFulfillmentService definition source")
                        .isEqualTo("definitions"));
    }

    /**
     * Returns a check that {@code location} selects exactly {@code expected} nodes of {@code wsdl}.
     *
     * @param wsdl     the parsed served WSDL, {@code null} when the body is not well-formed XML
     * @param location the XPath location
     * @param expected the expected number of nodes
     * @return the check
     */
    private Executable count(Document wsdl, String location, int expected) {
        return () -> assertThat(nodes(wsdl, location).getLength()).as("number of nodes at %s", location)
                .isEqualTo(expected);
    }

    /**
     * Returns a check that the attribute {@code name} of the single element at {@code location} equals
     * {@code expected}; an absent attribute reads as the empty string.
     *
     * @param wsdl     the parsed served WSDL, {@code null} when the body is not well-formed XML
     * @param location the XPath location of exactly one element
     * @param name     the attribute name
     * @param expected the expected attribute value
     * @return the check
     */
    private Executable attribute(Document wsdl, String location, String name, String expected) {
        return () -> assertThat(single(wsdl, location).getAttribute(name)).as("%s/@%s", location, name)
                .isEqualTo(expected);
    }

    /**
     * Returns a check that the QName-valued attribute {@code name} of the single element at {@code location}
     * resolves to {@code expected}. The prefix is resolved with {@link Element#lookupNamespaceURI(String)} on that
     * element; an unprefixed value takes the element's default namespace.
     *
     * @param wsdl     the parsed served WSDL, {@code null} when the body is not well-formed XML
     * @param location the XPath location of exactly one element
     * @param name     the attribute name
     * @param expected the expected resolved QName
     * @return the check
     */
    private Executable qname(Document wsdl, String location, String name, QName expected) {
        return () -> {
            Element owner = single(wsdl, location);
            String value = owner.getAttribute(name);
            int colon = value.indexOf(':');
            String namespace = owner.lookupNamespaceURI(colon < 0 ? null : value.substring(0, colon));
            QName resolved = new QName(namespace == null ? XMLConstants.NULL_NS_URI : namespace,
                    value.substring(colon + 1));
            assertThat(resolved).as("%s/@%s = '%s' resolved", location, name, value).isEqualTo(expected);
        };
    }

    /**
     * Returns the single element at {@code location}, failing when the location selects any other number of nodes.
     *
     * @param wsdl     the parsed served WSDL, {@code null} when the body is not well-formed XML
     * @param location the XPath location of exactly one element
     * @return the element
     * @throws XPathExpressionException when {@code location} cannot be evaluated
     */
    private Element single(Document wsdl, String location) throws XPathExpressionException {
        NodeList nodes = nodes(wsdl, location);
        assertThat(nodes.getLength()).as("number of nodes at %s", location).isEqualTo(1);
        assertThat(nodes.item(0)).as("node at %s", location).isInstanceOf(Element.class);
        return (Element) nodes.item(0);
    }

    /**
     * Evaluates {@code location} against {@code wsdl}, failing when the served body was not parsed.
     *
     * @param wsdl     the parsed served WSDL, {@code null} when the body is not well-formed XML
     * @param location the XPath location
     * @return the selected nodes
     * @throws XPathExpressionException when {@code location} cannot be evaluated
     */
    private NodeList nodes(Document wsdl, String location) throws XPathExpressionException {
        assertThat(wsdl).as("served WSDL parsed as namespace-aware XML").isNotNull();
        return (NodeList) this.xpath.evaluate(location, wsdl, XPathConstants.NODESET);
    }

    /**
     * Transforms the source of the {@code IFulfillmentService} definition bean into a DOM document with
     * {@code TransformerFactory.newInstance().newTransformer()}.
     *
     * @return the root element of the transformed document
     * @throws TransformerException when the source cannot be transformed
     */
    private Element definitionRoot() throws TransformerException {
        DOMResult result = new DOMResult();
        TransformerFactory.newInstance().newTransformer().transform(this.definition.getSource(), result);
        return ((Document) result.getNode()).getDocumentElement();
    }

    /**
     * Reads the response body: the input stream below status {@code 400}, the error stream otherwise.
     *
     * @param connection the connection after the response code is read
     * @param status     the response status
     * @return the body bytes, empty when the connection has no stream for the status
     * @throws IOException when the stream cannot be read
     */
    private static byte[] readBody(HttpURLConnection connection, int status) throws IOException {
        try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            return in == null ? new byte[0] : in.readAllBytes();
        }
    }

    /**
     * Parses {@code body} with a namespace-aware {@link DocumentBuilderFactory} in secure-processing mode that
     * rejects DOCTYPE declarations.
     *
     * @param body the XML bytes
     * @return the parsed document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException                 when {@code body} is not well-formed XML
     * @throws IOException                  when {@code body} cannot be read
     */
    private static Document parse(byte[] body) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    /**
     * Parses {@code body} as {@link #parse(byte[])} does; the parse failure itself is asserted separately in
     * {@link #wsdlFulfillment()}.
     *
     * @param body the XML bytes
     * @return the parsed document, {@code null} when {@code body} cannot be parsed
     */
    private static Document parseOrNull(byte[] body) {
        try {
            return parse(body);
        }
        catch (ParserConfigurationException | SAXException | IOException ex) {
            return null;
        }
    }

    /**
     * Returns the lower-case hexadecimal SHA-256 digest of {@code bytes}.
     *
     * @param bytes the bytes to digest
     * @return the 64-character digest
     * @throws NoSuchAlgorithmException when the runtime provides no SHA-256 implementation
     */
    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Counts the non-overlapping occurrences of {@code literal} in {@code text}.
     *
     * @param text    the text to search
     * @param literal the non-empty literal to count
     * @return the number of occurrences
     */
    private static int occurrences(String text, String literal) {
        int count = 0;
        for (int from = text.indexOf(literal); from >= 0; from = text.indexOf(literal, from + literal.length())) {
            count++;
        }
        return count;
    }

    /**
     * Creates an XPath evaluator with {@code wsdl} bound to {@code http://schemas.xmlsoap.org/wsdl/}, {@code soap}
     * to {@code http://schemas.xmlsoap.org/wsdl/soap/} and {@code xs} to {@code http://www.w3.org/2001/XMLSchema}.
     *
     * @return the XPath evaluator
     */
    private static XPath newXPath() {
        SimpleNamespaceContext namespaces = new SimpleNamespaceContext();
        namespaces.bindNamespaceUri("wsdl", WSDL_NS);
        namespaces.bindNamespaceUri("soap", SOAP_NS);
        namespaces.bindNamespaceUri("xs", XS_NS);
        XPath evaluator = XPathFactory.newInstance().newXPath();
        evaluator.setNamespaceContext(namespaces);
        return evaluator;
    }
}

