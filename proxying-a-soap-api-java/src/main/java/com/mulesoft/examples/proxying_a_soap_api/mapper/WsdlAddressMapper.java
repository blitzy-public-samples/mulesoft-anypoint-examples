package com.mulesoft.examples.proxying_a_soap_api.mapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Rewrites the SOAP address of the WSDL that the {@code cxf:proxy-service} of flow {@code main} serves
 * on {@code GET /?wsdl} [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:7]: the upstream
 * ShopService WSDL is returned with the {@code location} of the proxied port's address set to the proxy
 * URL the caller used (D-059, D-251).
 *
 * <p>Matched path, by namespace URI and local name (never by prefix), over direct child elements only:
 *
 * <ol>
 *   <li>the document element {@code wsdl:definitions} whose {@code targetNamespace} equals the given
 *       namespace;</li>
 *   <li>its first {@code wsdl:service} child whose {@code name} equals the given service;</li>
 *   <li>that service's first {@code wsdl:port} child whose {@code name} equals the given port;</li>
 *   <li>that port's first {@code soap:address} or {@code soap12:address} child, whose
 *       {@code location} attribute is rewritten.</li>
 * </ol>
 *
 * <p>Namespaces: {@code wsdl} is {@code http://schemas.xmlsoap.org/wsdl/}, {@code soap} is
 * {@code http://schemas.xmlsoap.org/wsdl/soap/} and {@code soap12} is
 * {@code http://schemas.xmlsoap.org/wsdl/soap12/}.
 *
 * <p>Usage, with the values of {@code soap-api-proxy.xml:7}:
 *
 * <pre>{@code
 * byte[] served = mapper.rewriteAddress(upstreamWsdl,
 *         "http://predic8.com/wsdl/shop/1/", "ShopService", "ShopServicePTPort",
 *         "http://localhost:8081/");
 * }</pre>
 *
 * <p>Instances hold no state; the method is side-effect free and safe for concurrent use.
 */
@Component
public class WsdlAddressMapper {

    /** Namespace of WSDL 1.1 elements. */
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    /** Namespace of the WSDL 1.1 SOAP 1.1 binding elements. */
    private static final String SOAP_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    /** Namespace of the WSDL 1.1 SOAP 1.2 binding elements. */
    private static final String SOAP12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";

    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    private static final String DEFINITIONS = "definitions";
    private static final String SERVICE = "service";
    private static final String PORT = "port";
    private static final String ADDRESS = "address";

    private static final String TARGET_NAMESPACE_ATTRIBUTE = "targetNamespace";
    private static final String NAME_ATTRIBUTE = "name";
    private static final String LOCATION_ATTRIBUTE = "location";

    private static final String OUTPUT_ENCODING = "UTF-8";

    /**
     * Returns {@code wsdl} with the {@code location} attribute of the matched SOAP address set to
     * {@code address} (D-059, {@code soap-api-proxy.xml:7}).
     *
     * <p>The bytes are parsed namespace-aware with secure processing on; a document type declaration
     * is refused, external general and parameter entities are not loaded, XInclude is off and entity
     * references are not expanded (D-251). Parser diagnostics are not printed.
     *
     * <p>When every step of the matched path matches, the {@code location} attribute of the address
     * element is set (and added when the element has none) and the document is written again by the
     * identity transform in UTF-8, whatever encoding the upstream document declares, without
     * indentation. The output starts with an XML declaration naming the document's XML version,
     * {@code encoding="UTF-8"} and, for a document not declared standalone, {@code standalone="no"};
     * no other attribute, element, namespace declaration, comment or whitespace node changes.
     *
     * <p>When any step does not match, the input array itself is returned and nothing is written. A
     * {@code null} namespace, service or port matches nothing; an absent attribute reads as the empty
     * string.
     *
     * @param wsdl      the upstream WSDL document bytes; not {@code null}
     * @param namespace the {@code targetNamespace} the document element must carry, for example
     *                  {@code http://predic8.com/wsdl/shop/1/}
     * @param service   the {@code name} of the {@code wsdl:service}, for example {@code ShopService}
     * @param port      the {@code name} of the {@code wsdl:port}, for example {@code ShopServicePTPort}
     * @param address   the value written into the {@code location} attribute, the proxy URL the caller
     *                  used, for example {@code http://localhost:8081/}
     * @return the rewritten document as UTF-8 bytes, or {@code wsdl} itself when the path does not match
     * @throws IllegalStateException when the bytes are not well-formed XML, carry a document type
     *                               declaration, or cannot be parsed or written; its cause is the parser,
     *                               I/O or transformer exception and its message is the cause's message
     */
    public byte[] rewriteAddress(byte[] wsdl, String namespace, String service, String port, String address) {
        try {
            Document document = parse(wsdl);
            Element addressElement = findAddress(document, namespace, service, port);
            if (addressElement == null) {
                return wsdl;
            }
            addressElement.setAttribute(LOCATION_ATTRIBUTE, address);
            return serialize(document);
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Parses the WSDL bytes into a namespace-aware DOM with secure processing on and document type
     * declarations refused.
     */
    private static Document parse(byte[] wsdl) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
        factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new ByteArrayInputStream(wsdl));
    }

    /**
     * Follows the matched path from the document element down to the SOAP address element.
     *
     * @return the {@code soap:address} or {@code soap12:address} element, or {@code null} when a step
     *         does not match
     */
    private static Element findAddress(Document document, String namespace, String service, String port) {
        Element definitions = document.getDocumentElement();
        if (!isElement(definitions, WSDL_NS, DEFINITIONS)
                || !matches(namespace, definitions.getAttribute(TARGET_NAMESPACE_ATTRIBUTE))) {
            return null;
        }
        Element serviceElement = firstNamedWsdlChild(definitions, SERVICE, service);
        if (serviceElement == null) {
            return null;
        }
        Element portElement = firstNamedWsdlChild(serviceElement, PORT, port);
        if (portElement == null) {
            return null;
        }
        return firstSoapAddressChild(portElement);
    }

    /**
     * Returns the first direct child element of {@code parent} in the WSDL namespace with the given
     * local name whose {@code name} attribute equals {@code name}, or {@code null} when none does.
     */
    private static Element firstNamedWsdlChild(Element parent, String localName, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (isElement(child, WSDL_NS, localName)
                    && matches(name, ((Element) child).getAttribute(NAME_ATTRIBUTE))) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Returns the first direct child element of {@code port} with local name {@code address} in the
     * SOAP 1.1 or SOAP 1.2 binding namespace, or {@code null} when none exists.
     */
    private static Element firstSoapAddressChild(Element port) {
        NodeList children = port.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (isElement(child, SOAP_NS, ADDRESS) || isElement(child, SOAP12_NS, ADDRESS)) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Tells whether {@code node} is an element with the given namespace URI and local name.
     */
    private static boolean isElement(Node node, String namespaceUri, String localName) {
        return node != null
                && node.getNodeType() == Node.ELEMENT_NODE
                && namespaceUri.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName());
    }

    /**
     * Tells whether {@code actual} equals {@code expected}; a {@code null} expected value matches nothing.
     */
    private static boolean matches(String expected, String actual) {
        return expected != null && expected.equals(actual);
    }

    /**
     * Writes the document with the identity transform as UTF-8 bytes, with secure processing on and no
     * external DTD or stylesheet access.
     */
    private static byte[] serialize(Document document) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, OUTPUT_ENCODING);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The identity transform reads a copy that declares no encoding and writes it in UTF-8 (D-251).
        transformer.transform(new DOMSource(withoutDeclaredEncoding(document)), new StreamResult(out));
        return out.toByteArray();
    }

    /**
     * Returns a new document holding deep copies of every child of {@code document} (comments,
     * processing instructions and the document element, in order), with the same XML version and
     * standalone flag and no declared or input encoding.
     */
    private static Document withoutDeclaredEncoding(Document document) {
        Document copy = document.getImplementation().createDocument(null, null, null);
        copy.setXmlVersion(document.getXmlVersion());
        copy.setXmlStandalone(document.getXmlStandalone());
        for (Node child = document.getFirstChild(); child != null; child = child.getNextSibling()) {
            copy.appendChild(copy.importNode(child, true));
        }
        return copy;
    }
}
