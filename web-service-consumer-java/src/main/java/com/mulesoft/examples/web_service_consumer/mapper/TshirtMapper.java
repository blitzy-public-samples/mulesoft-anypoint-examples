package com.mulesoft.examples.web_service_consumer.mapper;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import org.mulesoft.tshirt_service.AuthenticationHeader;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/**
 * The four DataWeave setters of the web-service-consumer example
 * [web-service-consumer/src/main/app/tshirt-service-consumer.xml], implemented by hand in Java:
 *
 * <ul>
 *   <li>DW-35, {@link #toOrderTshirt(String)} [:12-16]: the JSON request body of flow {@code orderTshirt} as the
 *       {@code ns0:OrderTshirt} element of namespace {@code http://mulesoft.org/tshirt-service};</li>
 *   <li>DW-36, {@link #authenticationHeader(String)} [:17-23]: the {@code AuthenticationHeader} SOAP header that
 *       carries the API key the caller passes in (D-012);</li>
 *   <li>DW-37, {@link #toOrderJson(Node)} [:28-31]: the {@code OrderTshirtResponse} SOAP body element as JSON;</li>
 *   <li>DW-38, {@link #toInventoryJson(Node)} [:41-45]: the content of the {@code ListInventoryResponse} SOAP body
 *       element of flow {@code listInventory} as JSON.</li>
 * </ul>
 *
 * <p>JSON layout of DW-37 and DW-38: each object member starts on a new line, a {@code \n} line feed followed by
 * two spaces per nesting level; the closing brace of an object starts on a new line at the level of the line that
 * opened it; {@code ": "} (a colon and one space) separates a member name from its value; a comma directly follows
 * every member except the last; no line feed follows the root value. Element values:
 *
 * <ul>
 *   <li>an element with child elements is an object holding one member per child element, in document order,
 *       named after the child's local name; repeated sibling elements give repeated member names, never an array;
 *       text, CDATA, comments and processing instructions beside child elements are not written;</li>
 *   <li>an element without child elements that holds text or CDATA is a JSON string of all its text and CDATA
 *       data joined in document order, unchanged and untrimmed; numeric text such as {@code 5} is the string
 *       {@code "5"};</li>
 *   <li>an element with neither child elements nor text or CDATA, such as {@code <orderId/>}, is {@code null}.</li>
 * </ul>
 *
 * <p>Prefixes, namespace declarations and attributes are never written. The element
 * {@code <ns2:OrderTshirtResponse xmlns:ns2="http://mulesoft.org/tshirt-service"><orderId>1</orderId></ns2:OrderTshirtResponse>}
 * gives, through {@link #toOrderJson(Node)}:
 *
 * <pre>{@code
 * {
 *   "OrderTshirtResponse": {
 *     "orderId": "1"
 *   }
 * }
 * }</pre>
 *
 * <p>Instances hold no state. Every method performs no I/O, has no side effect on its arguments and is safe for
 * concurrent use; {@code new TshirtMapper()} needs no Spring context.
 */
@Component
public class TshirtMapper {

    /** Target namespace of the t-shirt service schema [tshirt.wsdl:9]. */
    private static final String TSHIRT_NS = "http://mulesoft.org/tshirt-service";

    /** Prefix that DW-35 declares for {@link #TSHIRT_NS}. */
    private static final String TSHIRT_PREFIX = "ns0";

    /** Namespace of {@code xmlns} declaration attributes. */
    private static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    /** Local name of the DW-35 root element. */
    private static final String ORDER_TSHIRT = "OrderTshirt";

    /** Local name of the root element DW-38 selects. */
    private static final String LIST_INVENTORY_RESPONSE = "ListInventoryResponse";

    /** Jackson streaming factory with its default features and stream-read constraints. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /** Namespace-aware DOM factory, configured once; each call creates its own {@code DocumentBuilder}. */
    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY = newDocumentBuilderFactory();

    /**
     * DW-35: writes a JSON document as the content of a new {@code ns0:OrderTshirt} element.
     *
     * <p>The returned document holds one root element, {@code OrderTshirt} in namespace
     * {@code http://mulesoft.org/tshirt-service} with prefix {@code ns0} and the declaration
     * {@code xmlns:ns0="http://mulesoft.org/tshirt-service"}. The JSON root value is written as its content:
     *
     * <ul>
     *   <li>object: one child element per member, in the member order of the input, duplicate names included,
     *       each named after its JSON key with no namespace and no prefix; a member whose value is an array gives
     *       one element of that name per array item instead, in item order;</li>
     *   <li>nested object: nested unqualified elements under its member element, by the same rules;</li>
     *   <li>string: its text, unquoted; number: its text exactly as written in the input, for example
     *       {@code 1.50}; {@code true} and {@code false}: that text;</li>
     *   <li>{@code null}: no content, an empty element;</li>
     *   <li>array at the root or inside an array: the content of each item, in order, written directly into the
     *       enclosing element.</li>
     * </ul>
     *
     * <p>The sample request {@code {"email":"a@b.c","size":"L"}} gives
     * {@code <ns0:OrderTshirt xmlns:ns0="http://mulesoft.org/tshirt-service"><email>a@b.c</email><size>L</size></ns0:OrderTshirt>}.
     *
     * @param json the JSON request body
     * @return a {@link DOMSource} over the new document
     * @throws IllegalArgumentException when {@code json} is {@code null}, empty or whitespace only, or holds a
     *     second JSON value after the root value
     * @throws org.w3c.dom.DOMException when a JSON key is not a valid unqualified XML element name, unchanged
     * @throws IOException a Jackson {@code JsonParseException} for malformed JSON, propagated unwrapped
     * @throws IllegalStateException when the JDK provides no namespace-aware {@code DocumentBuilder}
     */
    public Source toOrderTshirt(String json) throws IOException {
        if (json == null) {
            throw new IllegalArgumentException("JSON input is null");
        }
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            if (parser.nextToken() == null) {
                throw new IllegalArgumentException("JSON input is empty");
            }
            Document document = newDocument();
            Element root = document.createElementNS(TSHIRT_NS, TSHIRT_PREFIX + ":" + ORDER_TSHIRT);
            root.setAttributeNS(XMLNS_NS, "xmlns:" + TSHIRT_PREFIX, TSHIRT_NS);
            document.appendChild(root);
            writeValue(parser, document, root);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("Unexpected content after the JSON root value");
            }
            return new DOMSource(document);
        }
    }

    /**
     * DW-36: builds the {@code AuthenticationHeader} SOAP header (D-012).
     *
     * @param apiKey the API key written to {@code apiKey}, unchanged; {@code null} leaves it unset
     * @return a new header holding {@code apiKey}
     */
    public AuthenticationHeader authenticationHeader(String apiKey) {
        AuthenticationHeader header = new AuthenticationHeader();
        header.setApiKey(apiKey);
        return header;
    }

    /**
     * DW-37: writes the {@code OrderTshirtResponse} SOAP body element as one JSON object whose single member is
     * named after the element's local name and holds the element's value, in the layout and with the element
     * values described on this class.
     *
     * @param orderTshirtResponse the response element, or a document whose document element it is
     * @return the JSON text, with no line feed after the closing brace
     * @throws IllegalArgumentException when the argument is {@code null}, is neither an {@link Element} nor a
     *     {@link Document}, or is a document without a document element
     * @throws UncheckedIOException wrapping an {@link IOException} of the JSON generator
     */
    public String toOrderJson(Node orderTshirtResponse) {
        Element root = rootElement(orderTshirtResponse);
        StringWriter json = new StringWriter();
        try (JsonGenerator generator = newGenerator(json)) {
            generator.writeStartObject();
            generator.writeFieldName(localName(root));
            writeElementValue(generator, root);
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return json.toString();
    }

    /**
     * DW-38: writes the value of the {@code ListInventoryResponse} element of namespace
     * {@code http://mulesoft.org/tshirt-service} as JSON, in the layout and with the element values described on
     * this class.
     *
     * <p>A matching element with {@code inventory} items gives one object with one {@code "inventory"} member per
     * item, in document order, each an object of the item's child elements with string values, for example
     * {@code "count": "5"}. A matching element without content, and an element of any other local name or
     * namespace, unqualified {@code ListInventoryResponse} included, give {@code null}.
     *
     * @param listInventoryResponse the response element, or a document whose document element it is
     * @return the JSON text, with no line feed after it
     * @throws IllegalArgumentException when the argument is {@code null}, is neither an {@link Element} nor a
     *     {@link Document}, or is a document without a document element
     * @throws UncheckedIOException wrapping an {@link IOException} of the JSON generator
     */
    public String toInventoryJson(Node listInventoryResponse) {
        Element root = rootElement(listInventoryResponse);
        StringWriter json = new StringWriter();
        try (JsonGenerator generator = newGenerator(json)) {
            if (TSHIRT_NS.equals(root.getNamespaceURI()) && LIST_INVENTORY_RESPONSE.equals(localName(root))) {
                writeElementValue(generator, root);
            } else {
                generator.writeNull();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return json.toString();
    }

    // ---------------------------------------------------------------------------------------------
    // DW-35: JSON stream to DOM.
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes the JSON value at the parser's current token as the content of {@code parent}, leaving the parser on
     * the value's last token.
     */
    private static void writeValue(JsonParser parser, Document document, Element parent) throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case START_OBJECT -> writeMembers(parser, document, parent);
            case START_ARRAY -> {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    writeValue(parser, document, parent);
                }
            }
            case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT, VALUE_TRUE, VALUE_FALSE ->
                    parent.appendChild(document.createTextNode(parser.getText()));
            case VALUE_NULL -> {
                // A JSON null writes no content.
            }
            default -> throw new IllegalStateException("Unexpected JSON token " + token);
        }
    }

    /**
     * Writes the members of the object whose {@code START_OBJECT} is the current token as unqualified child
     * elements of {@code parent}, leaving the parser on the object's {@code END_OBJECT}. An array member gives one
     * element per item.
     */
    private static void writeMembers(JsonParser parser, Document document, Element parent) throws IOException {
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            if (parser.nextToken() == JsonToken.START_ARRAY) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    writeValue(parser, document, appendElement(document, parent, name));
                }
            } else {
                writeValue(parser, document, appendElement(document, parent, name));
            }
        }
    }

    /** Appends a new element named {@code name}, with no namespace and no prefix, to {@code parent}. */
    private static Element appendElement(Document document, Element parent, String name) {
        Element child = document.createElementNS(null, name);
        parent.appendChild(child);
        return child;
    }

    /** Returns a new empty document from a builder of the shared namespace-aware factory. */
    private static Document newDocument() {
        try {
            return DOCUMENT_BUILDER_FACTORY.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("No namespace-aware DocumentBuilder is available", e);
        }
    }

    /** Returns a namespace-aware {@link DocumentBuilderFactory}. */
    private static DocumentBuilderFactory newDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory;
    }

    // ---------------------------------------------------------------------------------------------
    // DW-37 and DW-38: DOM to JSON.
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes the value of {@code element}: an object of its child elements, the string of its text and CDATA
     * data, or {@code null}, as described on this class.
     */
    private static void writeElementValue(JsonGenerator generator, Element element) throws IOException {
        boolean hasChildElements = false;
        StringBuilder text = null;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                hasChildElements = true;
            } else if (child instanceof Text characters) {
                if (text == null) {
                    text = new StringBuilder();
                }
                text.append(characters.getData());
            }
        }
        if (hasChildElements) {
            generator.writeStartObject();
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element childElement) {
                    generator.writeFieldName(localName(childElement));
                    writeElementValue(generator, childElement);
                }
            }
            generator.writeEndObject();
        } else if (text != null) {
            generator.writeString(text.toString());
        } else {
            generator.writeNull();
        }
    }

    /** Returns {@code node} when it is an element, or the document element of a document. */
    private static Element rootElement(Node node) {
        if (node instanceof Element element) {
            return element;
        }
        if (node instanceof Document document && document.getDocumentElement() != null) {
            return document.getDocumentElement();
        }
        throw new IllegalArgumentException("Expected an Element or a Document with a document element, got "
                + (node == null ? "null" : "a node of type " + node.getNodeType()));
    }

    /**
     * Returns the local name of {@code node}; for a node created without namespace support, its node name without
     * any {@code prefix:}.
     */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName == null) {
            String nodeName = node.getNodeName();
            localName = nodeName.substring(nodeName.indexOf(':') + 1);
        }
        return localName;
    }

    /** Returns a generator over {@code out} that writes through a new {@link DwPrettyPrinter}. */
    private static JsonGenerator newGenerator(StringWriter out) throws IOException {
        JsonGenerator generator = JSON_FACTORY.createGenerator(out);
        generator.setPrettyPrinter(new DwPrettyPrinter());
        return generator;
    }

    /**
     * Pretty printer of the JSON layout described on {@link TshirtMapper}: object members indented by two spaces
     * per level after a {@code \n} line feed, and {@code ": "} between a member name and its value. An instance
     * tracks the nesting depth of the one generator that writes through it.
     */
    private static final class DwPrettyPrinter extends DefaultPrettyPrinter {

        private static final long serialVersionUID = 1L;

        /** Creates a printer with two-space object indentation, {@code \n} line feeds and nesting depth zero. */
        DwPrettyPrinter() {
            indentObjectsWith(new DefaultIndenter("  ", "\n"));
        }

        /**
         * Returns a new printer with this layout and nesting depth zero.
         *
         * @return a new {@code DwPrettyPrinter}
         */
        @Override
        public DwPrettyPrinter createInstance() {
            return new DwPrettyPrinter();
        }

        /**
         * Writes {@code ": "} between a member name and its value.
         *
         * @param g the generator that receives the separator
         * @throws IOException when the generator cannot write
         */
        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }
    }
}
