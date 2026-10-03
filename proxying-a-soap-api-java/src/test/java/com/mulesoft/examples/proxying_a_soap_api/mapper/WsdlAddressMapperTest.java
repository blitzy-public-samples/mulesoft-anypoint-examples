package com.mulesoft.examples.proxying_a_soap_api.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Unit tests of {@link WsdlAddressMapper#rewriteAddress(byte[], String, String, String, String)}, the
 * {@code ?wsdl} address rewrite of the {@code cxf:proxy-service} of flow {@code main}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:7] (D-059).
 *
 * <p>Each test calls the mapper directly, with no Spring application context, with the namespace
 * {@code http://predic8.com/wsdl/shop/1/}, the service {@code ShopService} and the port
 * {@code ShopServicePTPort} of that {@code cxf:proxy-service}. The inputs are inline WSDL 1.1 documents
 * whose {@code wsdl:service} holds that port and a second port {@code ShopServiceOtherPort}. The returned
 * bytes are parsed namespace-aware and read through {@code getElementsByTagNameNS}.
 *
 * <ul>
 *   <li>The {@code location} of the named port's {@code soap:address} or {@code soap12:address} is set to
 *       the proxy address; the other port and the root {@code targetNamespace} keep their values.</li>
 *   <li>When the service name, the port name or the {@code targetNamespace} does not match, the input array
 *       is returned with its bytes unchanged.</li>
 *   <li>A document that is not well-formed, and a well-formed document that carries a document type
 *       declaration, raise {@link IllegalStateException} with the parser exception as its cause (D-251).</li>
 * </ul>
 *
 * <p>The tests run the mapper's one public method through its rewrite, its unchanged-input return and its
 * exception translation, and count toward the LINE coverage floor of the {@code mapper} package (D-049).
 * The class and its test methods are public (D-133).
 */
public class WsdlAddressMapperTest {

    /** {@code namespace} attribute of the {@code cxf:proxy-service} [soap-api-proxy.xml:7]. */
    private static final String NS = "http://predic8.com/wsdl/shop/1/";

    /** {@code service} attribute of the {@code cxf:proxy-service} [soap-api-proxy.xml:7]. */
    private static final String SERVICE = "ShopService";

    /** {@code port} attribute of the {@code cxf:proxy-service} [soap-api-proxy.xml:7]. */
    private static final String PORT = "ShopServicePTPort";

    /** Proxy address written into the named port's {@code location}. */
    private static final String NEW_ADDRESS = "http://localhost:8081/";

    /** Upstream {@code location} of the named port in every input WSDL. */
    private static final String UPSTREAM = "http://www.predic8.com:8080/shop/ShopService";

    /** Name of the second port of the {@code wsdl:service} in every input WSDL. */
    private static final String OTHER_PORT = "ShopServiceOtherPort";

    /** Upstream {@code location} of the second port in every input WSDL. */
    private static final String OTHER_LOCATION = "http://www.predic8.com:8080/shop/Other";

    /** {@code wsdl:service} name of the variant whose service does not match. */
    private static final String OTHER_SERVICE = "OtherService";

    /** {@code wsdl:port} name of the variant whose port does not match. */
    private static final String RENAMED_PORT = "OtherPort";

    /** {@code targetNamespace} of the variant whose namespace does not match. */
    private static final String OTHER_NS = "http://example.com/other/";

    /** Namespace of WSDL 1.1 elements. */
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

    /** Namespace of the WSDL 1.1 SOAP 1.1 binding elements. */
    private static final String SOAP11_NS = "http://schemas.xmlsoap.org/wsdl/soap/";

    /** Namespace of the WSDL 1.1 SOAP 1.2 binding elements. */
    private static final String SOAP12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";

    /** Prefix bound to {@link #SOAP11_NS} in the input WSDLs. */
    private static final String SOAP11_PREFIX = "soap";

    /** Prefix bound to {@link #SOAP12_NS} in the input WSDLs. */
    private static final String SOAP12_PREFIX = "soap12";

    /** XML declaration that starts every input WSDL. */
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    /** Start of the XML declaration the mapper writes after a rewrite. */
    private static final String UTF8_DECLARATION_START = "<?xml version=\"1.0\" encoding=\"UTF-8\"";

    /** Document type declaration with an internal entity, placed after the XML declaration. */
    private static final String DOCTYPE = "<!DOCTYPE wsdl:definitions [<!ENTITY e \"x\">]>\n";

    /**
     * {@code wsdl:definitions} of the input WSDLs. Arguments: 1 {@code targetNamespace} (also bound to
     * {@code tns}), 2 service name, 3 name of the first port, 4 binding of the first port, 5 prefix of the
     * first port's address element, 6 location of the first port, 7 name of the second port, 8 location of
     * the second port.
     */
    private static final String DEFINITIONS_TEMPLATE = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:soap12="http://schemas.xmlsoap.org/wsdl/soap12/"
                              xmlns:tns="%1$s"
                              name="ShopService"
                              targetNamespace="%1$s">
                <wsdl:portType name="ShopServicePT"/>
                <wsdl:binding name="ShopServicePTBinding" type="tns:ShopServicePT">
                    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                </wsdl:binding>
                <wsdl:binding name="ShopServicePTSoap12Binding" type="tns:ShopServicePT">
                    <soap12:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                </wsdl:binding>
                <wsdl:service name="%2$s">
                    <wsdl:port name="%3$s" binding="tns:%4$s">
                        <%5$s:address location="%6$s"/>
                    </wsdl:port>
                    <wsdl:port name="%7$s" binding="tns:ShopServicePTBinding">
                        <soap:address location="%8$s"/>
                    </wsdl:port>
                </wsdl:service>
            </wsdl:definitions>
            """;

    /** WSDL whose {@code ShopServicePTPort} carries a {@code soap:address} with {@link #UPSTREAM}. */
    private static final byte[] SOAP11_WSDL = wsdl(NS, SERVICE, PORT, SOAP11_PREFIX);

    /** WSDL whose {@code ShopServicePTPort} carries a {@code soap12:address} with {@link #UPSTREAM}. */
    private static final byte[] SOAP12_WSDL = wsdl(NS, SERVICE, PORT, SOAP12_PREFIX);

    /** {@link #SOAP11_WSDL} with the {@code wsdl:service} named {@code OtherService}. */
    private static final byte[] OTHER_SERVICE_WSDL = wsdl(NS, OTHER_SERVICE, PORT, SOAP11_PREFIX);

    /** {@link #SOAP11_WSDL} with the first {@code wsdl:port} named {@code OtherPort}. */
    private static final byte[] OTHER_PORT_WSDL = wsdl(NS, SERVICE, RENAMED_PORT, SOAP11_PREFIX);

    /** {@link #SOAP11_WSDL} with the {@code targetNamespace} {@code http://example.com/other/}. */
    private static final byte[] OTHER_NAMESPACE_WSDL = wsdl(OTHER_NS, SERVICE, PORT, SOAP11_PREFIX);

    /** {@link #SOAP11_WSDL} with {@link #DOCTYPE} between the XML declaration and the document element. */
    private static final byte[] DOCTYPE_WSDL =
            (XML_DECLARATION + DOCTYPE + definitions(NS, SERVICE, PORT, SOAP11_PREFIX))
                    .getBytes(StandardCharsets.UTF_8);

    /** WSDL whose {@code wsdl:definitions} and {@code wsdl:service} start tags are never closed. */
    private static final byte[] MALFORMED_WSDL = (XML_DECLARATION + """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              targetNamespace="http://predic8.com/wsdl/shop/1/">
                <wsdl:service name="ShopService">
            """).getBytes(StandardCharsets.UTF_8);

    /** The mapper under test. */
    private final WsdlAddressMapper mapper = new WsdlAddressMapper();

    /**
     * Rewrites the location of the named SOAP 1.1 port: the result is a new array that decodes as UTF-8,
     * starts with the UTF-8 XML declaration and parses; its {@code ShopServicePTPort} address is still a
     * {@code soap:address} and carries {@link #NEW_ADDRESS}, and the root {@code targetNamespace} is still
     * {@link #NS} (D-059).
     */
    @Test
    public void rewritesSoapAddressOfNamedPort() throws Exception {
        byte[] result = mapper.rewriteAddress(SOAP11_WSDL, NS, SERVICE, PORT, NEW_ADDRESS);

        assertNotSame(SOAP11_WSDL, result);
        String text = decodeUtf8(result);
        assertTrue(text.startsWith(UTF8_DECLARATION_START), text);
        Document document = parse(result);
        Element address = addressOf(document, PORT);
        assertEquals(SOAP11_NS, address.getNamespaceURI());
        assertEquals(NEW_ADDRESS, locationOf(document, PORT));
        assertEquals(WSDL_NS, document.getDocumentElement().getNamespaceURI());
        assertEquals(NS, document.getDocumentElement().getAttribute("targetNamespace"));
    }

    /**
     * Rewrites the location of the named SOAP 1.2 port: the result decodes as UTF-8 and parses, and its
     * {@code ShopServicePTPort} address element is still in the SOAP 1.2 binding namespace and carries
     * {@link #NEW_ADDRESS}; the root {@code targetNamespace} is still {@link #NS} (D-059).
     */
    @Test
    public void rewritesSoap12AddressOfNamedPort() throws Exception {
        byte[] result = mapper.rewriteAddress(SOAP12_WSDL, NS, SERVICE, PORT, NEW_ADDRESS);

        assertNotSame(SOAP12_WSDL, result);
        decodeUtf8(result);
        Document document = parse(result);
        Element address = addressOf(document, PORT);
        assertEquals(SOAP12_NS, address.getNamespaceURI());
        assertEquals("address", address.getLocalName());
        assertEquals(NEW_ADDRESS, locationOf(document, PORT));
        assertEquals(NS, document.getDocumentElement().getAttribute("targetNamespace"));
    }

    /**
     * Keeps the location of the second port of the same service: after the rewrite of
     * {@link #SOAP11_WSDL}, {@code ShopServicePTPort} carries {@link #NEW_ADDRESS} and
     * {@code ShopServiceOtherPort} still carries its {@code soap:address} {@link #OTHER_LOCATION} (D-059).
     */
    @Test
    public void keepsLocationOfOtherPortInSameService() throws Exception {
        byte[] result = mapper.rewriteAddress(SOAP11_WSDL, NS, SERVICE, PORT, NEW_ADDRESS);

        Document document = parse(result);
        assertEquals(NEW_ADDRESS, locationOf(document, PORT));
        assertEquals(OTHER_LOCATION, locationOf(document, OTHER_PORT));
        assertEquals(SOAP11_NS, addressOf(document, OTHER_PORT).getNamespaceURI());
    }

    /**
     * Returns the input array, with its bytes unchanged, when no {@code wsdl:service} is named
     * {@code ShopService}.
     */
    @Test
    public void returnsInputUnchangedWhenServiceMissing() {
        byte[] input = OTHER_SERVICE_WSDL;
        byte[] before = input.clone();

        byte[] result = mapper.rewriteAddress(input, NS, SERVICE, PORT, NEW_ADDRESS);

        assertSame(input, result);
        assertArrayEquals(before, result);
    }

    /**
     * Returns the input array, with its bytes unchanged, when the {@code ShopService} service has no
     * {@code wsdl:port} named {@code ShopServicePTPort}.
     */
    @Test
    public void returnsInputUnchangedWhenPortMissing() {
        byte[] input = OTHER_PORT_WSDL;
        byte[] before = input.clone();

        byte[] result = mapper.rewriteAddress(input, NS, SERVICE, PORT, NEW_ADDRESS);

        assertSame(input, result);
        assertArrayEquals(before, result);
    }

    /**
     * Returns the input array, with its bytes unchanged, when the root {@code targetNamespace} is not
     * {@code http://predic8.com/wsdl/shop/1/}.
     */
    @Test
    public void returnsInputUnchangedWhenTargetNamespaceDiffers() {
        byte[] input = OTHER_NAMESPACE_WSDL;
        byte[] before = input.clone();

        byte[] result = mapper.rewriteAddress(input, NS, SERVICE, PORT, NEW_ADDRESS);

        assertSame(input, result);
        assertArrayEquals(before, result);
    }

    /**
     * Raises {@link IllegalStateException}, with a {@link SAXException} as its cause, for a document whose
     * {@code wsdl:definitions} start tag is never closed (D-251).
     */
    @Test
    public void throwsOnMalformedXml() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mapper.rewriteAddress(MALFORMED_WSDL, NS, SERVICE, PORT, NEW_ADDRESS));

        assertInstanceOf(SAXException.class, thrown.getCause());
    }

    /**
     * Raises {@link IllegalStateException}, with a {@link SAXException} as its cause, for a well-formed
     * {@link #SOAP11_WSDL} that carries a document type declaration with an internal entity; the same bytes
     * parse with {@link #parse(byte[])}, which reads the declaration (D-251).
     */
    @Test
    public void throwsOnDoctype() throws Exception {
        Document wellFormed = parse(DOCTYPE_WSDL);
        assertNotNull(wellFormed.getDoctype());
        assertEquals(UPSTREAM, locationOf(wellFormed, PORT));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mapper.rewriteAddress(DOCTYPE_WSDL, NS, SERVICE, PORT, NEW_ADDRESS));

        assertInstanceOf(SAXException.class, thrown.getCause());
    }

    /**
     * Returns {@link #XML_DECLARATION} followed by {@link #definitions(String, String, String, String)} as
     * UTF-8 bytes.
     */
    private static byte[] wsdl(String targetNamespace, String serviceName, String portName, String addressPrefix) {
        return (XML_DECLARATION + definitions(targetNamespace, serviceName, portName, addressPrefix))
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Fills {@link #DEFINITIONS_TEMPLATE}: the first port is named {@code portName}, uses the SOAP 1.1 or
     * SOAP 1.2 binding matching {@code addressPrefix} and carries {@link #UPSTREAM}; the second port is
     * {@link #OTHER_PORT} with a {@code soap:address} carrying {@link #OTHER_LOCATION}.
     */
    private static String definitions(String targetNamespace, String serviceName, String portName,
            String addressPrefix) {
        String binding = SOAP12_PREFIX.equals(addressPrefix) ? "ShopServicePTSoap12Binding" : "ShopServicePTBinding";
        return DEFINITIONS_TEMPLATE.formatted(targetNamespace, serviceName, portName, binding, addressPrefix,
                UPSTREAM, OTHER_PORT, OTHER_LOCATION);
    }

    /**
     * Parses {@code xml} into a namespace-aware DOM; an internal document type declaration is read, and
     * external DTD and schema access is off.
     */
    private static Document parse(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    /**
     * Decodes {@code bytes} as UTF-8 and fails on any malformed or unmappable sequence.
     */
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /**
     * Returns the {@code location} attribute of the address element of the {@code wsdl:port} named
     * {@code portName}, as {@link #addressOf(Document, String)} finds it.
     */
    private static String locationOf(Document document, String portName) {
        return addressOf(document, portName).getAttribute("location");
    }

    /**
     * Returns the first child element, with local name {@code address} in the SOAP 1.1 or SOAP 1.2 binding
     * namespace, of the first {@code wsdl:port} named {@code portName}; fails the test when that port or
     * that element is absent.
     */
    private static Element addressOf(Document document, String portName) {
        NodeList ports = document.getElementsByTagNameNS(WSDL_NS, "port");
        for (int i = 0; i < ports.getLength(); i++) {
            Element port = (Element) ports.item(i);
            if (!portName.equals(port.getAttribute("name"))) {
                continue;
            }
            for (Node child = port.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.ELEMENT_NODE
                        && "address".equals(child.getLocalName())
                        && (SOAP11_NS.equals(child.getNamespaceURI()) || SOAP12_NS.equals(child.getNamespaceURI()))) {
                    return (Element) child;
                }
            }
            return fail("wsdl:port " + portName + " has no soap:address or soap12:address child");
        }
        return fail("no wsdl:port named " + portName);
    }
}
