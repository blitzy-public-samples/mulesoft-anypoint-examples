package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

/**
 * Order XML and summary JSON transforms of the {@code orderRequest} channel (DW-32, DW-33;
 * service-orchestration-and-choice-routing/src/main/app/mule-config.xml; D-034).
 *
 * <p>Both methods work on generic data, never on the {@code model} classes, and follow the DataWeave
 * 1.0 readers and writers:
 *
 * <ul>
 *   <li>{@link #toOrderXml(String)} (DW-32, mule-config.xml:9-16) reads the page JSON and writes
 *       {@code order{orderId, customer, orderItems: payload.orderItems.item}} as XML text;</li>
 *   <li>{@link #toSummaryJson(String)} (DW-33, mule-config.xml:27-34) reads a {@code summary}
 *       element and writes {@code {orderId, customer, orderItems}} as JSON text.</li>
 * </ul>
 *
 * <p>Generic values: a JSON or XML object is a {@code List<Map.Entry<String, Object>>} of its
 * members in document order, repeated names kept; a JSON array is a {@code List<Object>}; every
 * scalar is a {@code String}; JSON {@code null}, an absent member and an empty JSON object are
 * {@code null} (D-194).
 *
 * <p>With the page's own request, whose {@code orderItems} entries are {@code {"item": {...}}}
 * objects, DW-32 writes one {@code orderItems} element per item holding that item's members
 * directly, with no {@code item} element. The summary {@code orderItems} written by DW-33 is the
 * object or the empty string read from the reply, never an array (D-043, D-194).
 *
 * <p>Instances hold no state; both methods are side-effect free and safe for concurrent use.
 */
@Component
public class OrderXmlMapper {

    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String INDENT = "  ";
    private static final char NEWLINE = '\n';

    private static final String ORDER = "order";
    private static final String ORDER_ID = "orderId";
    private static final String CUSTOMER = "customer";
    private static final String ORDER_ITEMS = "orderItems";
    private static final String ITEM = "item";
    private static final String SUMMARY = "summary";

    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    /** Shared, immutable after construction; creates one parser per call. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /**
     * Writes {@code order{orderId, customer, orderItems}} from the page JSON; {@code orderItems}
     * repeats once per selected {@code item} value (DW-32, mule-config.xml:9-16; D-043, D-194).
     *
     * <p>Selection follows the DataWeave 1.0 single-value selector: {@code orderId} and
     * {@code customer} are the first members of those names of the JSON root, and
     * {@code orderItems} is {@code payload.orderItems.item}. On an array that selector maps over
     * the elements: each object element with an {@code item} member contributes its first
     * {@code item} value, and every other element is left out. On {@code null} or a string it
     * gives {@code null}.
     *
     * <p>Writing: the first line is {@code <?xml version='1.0' encoding='UTF-8'?>}; each element
     * starts on its own line, indented two spaces per depth; lines are joined with {@code \n} and
     * the text has no trailing newline. For a member {@code key}:
     *
     * <ul>
     *   <li>{@code null}, an absent member, an empty object, or an object whose members write no
     *       element gives {@code <key/>};</li>
     *   <li>a string gives {@code <key>text</key>} ({@code <key></key>} when empty), with
     *       {@code &}, {@code <} and {@code >} written as {@code &amp;}, {@code &lt;} and
     *       {@code &gt;};</li>
     *   <li>an object gives {@code <key>}, its members one level deeper, then {@code </key>} on
     *       its own line;</li>
     *   <li>an array writes no element of its own: each element is written under {@code key},
     *       nested arrays alike, and an empty array writes nothing.</li>
     * </ul>
     *
     * <p>JSON numbers keep their token text, a decimal number written by
     * {@code BigDecimal.toPlainString()}; {@code true} and {@code false} become those strings.
     * Element names are the JSON member names, written unchanged; no attribute or namespace
     * declaration is written.
     *
     * <p>Example: {@code {"orderId":"12","customer":{"firstName":"John"},
     * "orderItems":[{"item":{"name":"s-1"}}]}} gives
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <order>
     *   <orderId>12</orderId>
     *   <customer>
     *     <firstName>John</firstName>
     *   </customer>
     *   <orderItems>
     *     <name>s-1</name>
     *   </orderItems>
     * </order>
     * }</pre>
     *
     * @param uiJson the JSON order sent by the page on {@code /orders/request}
     * @return the order XML text
     * @throws NullPointerException if {@code uiJson} is {@code null}
     * @throws IllegalArgumentException if {@code uiJson} holds no JSON value, is malformed, or
     *     holds content after its first value; the message is the parser's description
     */
    public String toOrderXml(String uiJson) {
        Objects.requireNonNull(uiJson, "uiJson");
        Object payload = readJson(uiJson);

        List<Map.Entry<String, Object>> order = new ArrayList<>(3);
        order.add(entry(ORDER_ID, select(payload, ORDER_ID)));
        order.add(entry(CUSTOMER, select(payload, CUSTOMER)));
        order.add(entry(ORDER_ITEMS, select(select(payload, ORDER_ITEMS), ITEM)));

        StringBuilder out = new StringBuilder(XML_DECLARATION);
        writeXml(out, ORDER, order, 0);
        return out.toString();
    }

    /**
     * Writes {@code {orderId, customer, orderItems}} from a {@code summary} element; repeated child
     * elements become repeated members (DW-33, mule-config.xml:27-34; D-043, D-194).
     *
     * <p>Reading follows the DataWeave 1.0 XML reader. Element names are local names; namespace
     * prefixes, namespace declarations, attributes, comments and processing instructions are
     * ignored. An element with at least one child element is an object of its child elements in
     * document order, repeated names kept, and its text is ignored. An element without child
     * elements is the concatenation of its text and CDATA content, kept verbatim: an empty element
     * is {@code ""}. Every value is a string, numbers included ({@code "2550.0"}).
     *
     * <p>Selection: {@code payload.summary} is the document element when its local name is
     * {@code summary}, otherwise {@code null}; {@code orderId}, {@code customer} and
     * {@code orderItems} are its first child elements of those names, {@code null} when absent.
     *
     * <p>Writing: an object of exactly the members {@code orderId}, {@code customer} and
     * {@code orderItems}, in that order. Each opening brace is followed by a line break; each member
     * line is two spaces per depth, then {@code "key": value}; members are separated by
     * {@code ,\n}; the closing brace sits on its own line at the parent depth; the text has no
     * trailing newline. {@code null} is written as {@code null}; a string is quoted with
     * {@code "}, {@code \} and control characters escaped, {@code /} and non-ASCII characters
     * unchanged; repeated names are written as repeated members. With two {@code item} elements,
     * the {@code orderItems} object holds two {@code "item": {...}} members; {@code <orderItems/>}
     * gives {@code "orderItems": ""}.
     *
     * <p>Example: {@code <summary><orderId>12</orderId><orderItems><item><name>s-1</name></item>
     * </orderItems></summary>} gives
     *
     * <pre>{@code
     * {
     *   "orderId": "12",
     *   "customer": null,
     *   "orderItems": {
     *     "item": {
     *       "name": "s-1"
     *     }
     *   }
     * }
     * }</pre>
     *
     * @param summaryXml the XML text whose document element is the order summary
     * @return the summary JSON text
     * @throws NullPointerException if {@code summaryXml} is {@code null}
     * @throws IllegalArgumentException if {@code summaryXml} is not well-formed XML or declares a
     *     document type; the message is the parser's description
     */
    public String toSummaryJson(String summaryXml) {
        Objects.requireNonNull(summaryXml, "summaryXml");
        Element root = parseXml(summaryXml).getDocumentElement();
        List<Map.Entry<String, Object>> payload = List.of(entry(root.getLocalName(), readElement(root)));
        Object summary = select(payload, SUMMARY);

        List<Map.Entry<String, Object>> result = new ArrayList<>(3);
        result.add(entry(ORDER_ID, select(summary, ORDER_ID)));
        result.add(entry(CUSTOMER, select(summary, CUSTOMER)));
        result.add(entry(ORDER_ITEMS, select(summary, ORDER_ITEMS)));

        StringBuilder out = new StringBuilder();
        writeJson(out, result, 0);
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Generic values and the single-value selector
    // ---------------------------------------------------------------------------------------

    /** Returns an immutable member; the value may be {@code null}. */
    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    /** True for an object: a non-empty list of members. An empty list is an empty array. */
    private static boolean isObject(Object value) {
        return value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map.Entry;
    }

    /**
     * The DataWeave 1.0 single-value selector {@code value.key}: on an object, the value of its first
     * member named {@code key}, or {@code null}; on an array, a new array of the first {@code key}
     * value of every object element that has such a member; on {@code null} or a string,
     * {@code null}.
     */
    private static Object select(Object value, String key) {
        if (isObject(value)) {
            return firstMember((List<?>) value, key);
        }
        if (value instanceof List<?> elements) {
            List<Object> selected = new ArrayList<>();
            for (Object element : elements) {
                if (isObject(element) && hasMember((List<?>) element, key)) {
                    selected.add(firstMember((List<?>) element, key));
                }
            }
            return selected;
        }
        return null;
    }

    /** The value of the first member named {@code key}, or {@code null} when there is none. */
    private static Object firstMember(List<?> members, String key) {
        for (Object member : members) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) member;
            if (key.equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** True when the object has a member named {@code key}, whatever its value. */
    private static boolean hasMember(List<?> members, String key) {
        for (Object member : members) {
            if (key.equals(((Map.Entry<?, ?>) member).getKey())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // DW-32: JSON reader and XML writer
    // ---------------------------------------------------------------------------------------

    /**
     * Reads one JSON value as a generic value. Duplicate member names are kept in document order.
     * An empty JSON object reads as {@code null}: both select nothing and both write
     * {@code <key/>}.
     */
    private static Object readJson(String json) {
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("No JSON value in the order request");
            }
            Object value = readJsonValue(parser, first);
            JsonToken trailing = parser.nextToken();
            if (trailing != null) {
                throw new IllegalArgumentException("Unexpected JSON content after the order value: " + trailing);
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Reads the value that starts at {@code token}, the parser's current token. */
    private static Object readJsonValue(JsonParser parser, JsonToken token) throws IOException {
        if (token == JsonToken.START_OBJECT) {
            return readJsonObject(parser);
        }
        if (token == JsonToken.START_ARRAY) {
            return readJsonArray(parser);
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return new BigDecimal(parser.getText()).toPlainString();
        }
        // A string, an integer, true or false: the token text.
        return parser.getText();
    }

    /** Reads the members up to the matching end of the object; {@code null} for {@code {}}. */
    private static Object readJsonObject(JsonParser parser) throws IOException {
        List<Map.Entry<String, Object>> members = new ArrayList<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            members.add(entry(name, readJsonValue(parser, parser.nextToken())));
        }
        return members.isEmpty() ? null : members;
    }

    /** Reads the elements up to the matching end of the array. */
    private static List<Object> readJsonArray(JsonParser parser) throws IOException {
        List<Object> elements = new ArrayList<>();
        JsonToken token = parser.nextToken();
        while (token != JsonToken.END_ARRAY) {
            elements.add(readJsonValue(parser, token));
            token = parser.nextToken();
        }
        return elements;
    }

    /**
     * Writes {@code value} under {@code key} at {@code depth} and returns whether an element was
     * written; only an array without elements to write returns {@code false}.
     */
    private static boolean writeXml(StringBuilder out, String key, Object value, int depth) {
        if (value instanceof List<?> elements && !isObject(value)) {
            boolean written = false;
            for (Object element : elements) {
                written |= writeXml(out, key, element, depth);
            }
            return written;
        }
        newLine(out, depth);
        if (value instanceof String text) {
            out.append('<').append(key).append('>').append(escapeXmlText(text))
                    .append("</").append(key).append('>');
            return true;
        }
        int start = out.length();
        out.append('<').append(key).append('>');
        if (value != null && writeXmlMembers(out, (List<?>) value, depth + 1)) {
            newLine(out, depth);
            out.append("</").append(key).append('>');
        } else {
            out.setLength(start);
            out.append('<').append(key).append("/>");
        }
        return true;
    }

    /** Writes every member of an object at {@code depth}; returns whether any element was written. */
    private static boolean writeXmlMembers(StringBuilder out, List<?> members, int depth) {
        boolean written = false;
        for (Object member : members) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) member;
            written |= writeXml(out, (String) entry.getKey(), entry.getValue(), depth);
        }
        return written;
    }

    /** Replaces {@code &}, {@code <} and {@code >} with their XML entity references. */
    private static String escapeXmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Starts a new line indented two spaces per depth. */
    private static void newLine(StringBuilder out, int depth) {
        out.append(NEWLINE).append(INDENT.repeat(depth));
    }

    // ---------------------------------------------------------------------------------------
    // DW-33: XML reader and JSON writer
    // ---------------------------------------------------------------------------------------

    /**
     * Parses {@code xml} with a namespace-aware JDK DOM parser that refuses document type
     * declarations and reads no external entity, DTD or XInclude. Parse errors are thrown, never
     * printed.
     */
    private static Document parseXml(String xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("The JDK XML parser rejected its configuration", e);
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Reads an element: an object of its child elements, keyed by local name in document order,
     * when it has any; otherwise the concatenation of its text and CDATA content.
     */
    private static Object readElement(Element element) {
        List<Map.Entry<String, Object>> members = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            short type = child.getNodeType();
            if (type == Node.ELEMENT_NODE) {
                members.add(entry(child.getLocalName(), readElement((Element) child)));
            } else if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return members.isEmpty() ? text.toString() : members;
    }

    /** Writes {@code value}, a string, an object or {@code null}, as JSON at {@code depth}. */
    private static void writeJson(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeJsonString(out, text);
        } else {
            out.append('{');
            String separator = String.valueOf(NEWLINE);
            for (Object member : (List<?>) value) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) member;
                out.append(separator).append(INDENT.repeat(depth + 1));
                writeJsonString(out, (String) entry.getKey());
                out.append(": ");
                writeJson(out, entry.getValue(), depth + 1);
                separator = "," + NEWLINE;
            }
            newLine(out, depth);
            out.append('}');
        }
    }

    /** Writes {@code text} as a quoted JSON string. */
    private static void writeJsonString(StringBuilder out, String text) {
        out.append('"').append(JsonStringEncoder.getInstance().quoteAsString(text)).append('"');
    }
}
