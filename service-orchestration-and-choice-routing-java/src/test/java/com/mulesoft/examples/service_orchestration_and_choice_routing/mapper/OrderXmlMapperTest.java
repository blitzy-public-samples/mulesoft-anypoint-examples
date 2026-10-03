/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests of {@link OrderXmlMapper}: DW-32 page JSON to order XML and DW-33 summary XML to JSON (D-034, D-043).
 *
 * <ul>
 *   <li>DW-32 [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:9-16], flow
 *       {@code orderRequest}: {@link OrderXmlMapper#toOrderXml(String)} turns the compact JSON order the docroot page
 *       sends on {@code /orders/request} into {@code order{orderId, customer, orderItems}} XML, with one
 *       {@code orderItems} element per item holding the item's {@code manufacturer}, {@code name},
 *       {@code productId} and {@code quantity} directly and no {@code item} element.</li>
 *   <li>DW-33 [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:27-34]:
 *       {@link OrderXmlMapper#toSummaryJson(String)} turns the {@code summary} element of the SOAP reply into
 *       {@code {orderId, customer, orderItems}} JSON, every leaf a string and {@code orderItems} an object keyed
 *       {@code item} (D-043).</li>
 * </ul>
 *
 * <p>Each test calls an {@link OrderXmlMapper} created with {@code new}, with no Spring application context and no
 * network. The only file read is the classpath resource {@code original/reply.xml}, the original SOAP reply; it is
 * read and never written. The two-item order and the tab-indented summary are the original design-time samples
 * {@code sample_data/json_1.json} and {@code sample_data/summary_1.xml}, held inline as text blocks (D-037).
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
public class OrderXmlMapperTest {

    /** The one-item order of the original reply, as the page's {@code JSON.stringify} writes it. */
    private static final String ONE_ITEM = """
            {"orderId":"12","customer":{"firstName":"John","lastName":"Doe","address":"Main Street 123"},\
            "orderItems":[{"item":{"manufacturer":"Samsung","name":"s-1","productId":"AX02","quantity":"1"}}]}""";

    /** The two-item order of {@code sample_data/json_1.json} in compact form (D-037). */
    private static final String TWO_ITEMS = """
            {"orderId":"123","customer":{"firstName":"Nial","lastName":"Darbey","address":"Buenos Aires"},\
            "orderItems":[{"item":{"manufacturer":"Samsung","name":"Galaxy","productId":"i9000","quantity":"30"}},\
            {"item":{"manufacturer":"Philips","name":"P3123","productId":"23","quantity":"10"}}]}""";

    /** The DW 1.0 XML rendering of DW-32 for {@link #ONE_ITEM}. */
    private static final String EXPECTED_ONE_ITEM = """
            <?xml version='1.0' encoding='UTF-8'?>
            <order>
              <orderId>12</orderId>
              <customer>
                <firstName>John</firstName>
                <lastName>Doe</lastName>
                <address>Main Street 123</address>
              </customer>
              <orderItems>
                <manufacturer>Samsung</manufacturer>
                <name>s-1</name>
                <productId>AX02</productId>
                <quantity>1</quantity>
              </orderItems>
            </order>""";

    /** The DW 1.0 XML rendering of DW-32 for {@link #TWO_ITEMS}: two sibling {@code orderItems} elements. */
    private static final String EXPECTED_TWO_ITEMS = """
            <?xml version='1.0' encoding='UTF-8'?>
            <order>
              <orderId>123</orderId>
              <customer>
                <firstName>Nial</firstName>
                <lastName>Darbey</lastName>
                <address>Buenos Aires</address>
              </customer>
              <orderItems>
                <manufacturer>Samsung</manufacturer>
                <name>Galaxy</name>
                <productId>i9000</productId>
                <quantity>30</quantity>
              </orderItems>
              <orderItems>
                <manufacturer>Philips</manufacturer>
                <name>P3123</name>
                <productId>23</productId>
                <quantity>10</quantity>
              </orderItems>
            </order>""";

    /**
     * The DW 1.0 JSON rendering of DW-33 for the summary of the original reply: two-space indentation,
     * {@code ": "} between name and value, {@code \n} line feeds, no trailing newline, every leaf a string and
     * {@code orderItems} an object keyed {@code item} (D-043).
     */
    private static final String EXPECTED_SUMMARY_JSON = """
            {
              "orderId": "12",
              "customer": {
                "address": "Main Street 123",
                "firstName": "John",
                "lastName": "Doe"
              },
              "orderItems": {
                "item": {
                  "manufacturer": "Samsung",
                  "name": "s-1",
                  "productId": "AX02",
                  "purchaseReceipt": {
                    "id": "1",
                    "status": "ACCEPTED",
                    "totalPrice": "2550.0"
                  },
                  "quantity": "1"
                }
              }
            }""";

    /**
     * The summary as {@code SoapEnvelopeToBodyChild.xslt} hands it to DW-33, in the tab-indented layout of
     * {@code sample_data/summary_1.xml} (D-037), with the envelope's namespace declarations on the root.
     */
    private static final String INDENTED_SUMMARY = """
            <?xml version='1.0' encoding='UTF-8'?>
            <summary xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ns2="http://orders.se.mulesoft.com/">
            \t<orderId>12</orderId>
            \t<customer>
            \t\t<address>Main Street 123</address>
            \t\t<firstName>John</firstName>
            \t\t<lastName>Doe</lastName>
            \t</customer>
            \t<orderItems>
            \t\t<item>
            \t\t\t<manufacturer>Samsung</manufacturer>
            \t\t\t<name>s-1</name>
            \t\t\t<productId>AX02</productId>
            \t\t\t<purchaseReceipt>
            \t\t\t\t<id>1</id>
            \t\t\t\t<status>ACCEPTED</status>
            \t\t\t\t<totalPrice>2550.0</totalPrice>
            \t\t\t</purchaseReceipt>
            \t\t\t<quantity>1</quantity>
            \t\t</item>
            \t</orderItems>
            </summary>""";

    /** Classpath location of the original SOAP reply. */
    private static final String REPLY_RESOURCE = "/original/reply.xml";

    /** Location of the {@code summary} element inside the original SOAP reply, by local names. */
    private static final String SUMMARY_IN_REPLY = "/*[local-name()='Envelope']/*[local-name()='Body']"
            + "/*[local-name()='processOrderResponse']/summary";

    private final OrderXmlMapper mapper = new OrderXmlMapper();

    private final ObjectMapper json = new ObjectMapper();

    // ---------------------------------------------------------------------------------------
    // DW-32: toOrderXml
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-32 writes a one-item page order as order XML with one orderItems element holding the item fields")
    public void dw32SingleItem() throws Exception {
        String actual = mapper.toOrderXml(ONE_ITEM);

        assertThat(actual).containsPattern("^<\\?xml version=['\"]1\\.0['\"] encoding=['\"]UTF-8['\"]\\?>");

        Document doc = parse(actual);
        Element root = doc.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("order");
        assertThat(root.getNamespaceURI()).isNull();
        assertThat(childNames(root)).containsExactly("orderId", "customer", "orderItems");
        assertThat(childNames(element(doc, "/order/customer"))).containsExactly("firstName", "lastName", "address");
        assertThat(childNames(element(doc, "/order/orderItems[1]")))
                .containsExactly("manufacturer", "name", "productId", "quantity");

        assertThat(xpath(doc, "/order/orderId")).isEqualTo("12");
        assertThat(xpath(doc, "/order/customer/firstName")).isEqualTo("John");
        assertThat(xpath(doc, "/order/customer/lastName")).isEqualTo("Doe");
        assertThat(xpath(doc, "/order/customer/address")).isEqualTo("Main Street 123");
        assertThat(xpath(doc, "/order/orderItems[1]/manufacturer")).isEqualTo("Samsung");
        assertThat(xpath(doc, "/order/orderItems[1]/name")).isEqualTo("s-1");
        assertThat(xpath(doc, "/order/orderItems[1]/productId")).isEqualTo("AX02");
        assertThat(xpath(doc, "/order/orderItems[1]/quantity")).isEqualTo("1");

        assertThat(count(doc, "/order/orderItems")).isEqualTo(1.0);
        assertThat(count(doc, "//item")).isEqualTo(0.0);

        assertSimilar(EXPECTED_ONE_ITEM, actual);
    }

    @Test
    @DisplayName("DW-32 writes a two-item page order as two sibling orderItems elements in input order")
    public void dw32TwoItems() throws Exception {
        String actual = mapper.toOrderXml(TWO_ITEMS);

        Document doc = parse(actual);
        assertThat(childNames(doc.getDocumentElement()))
                .containsExactly("orderId", "customer", "orderItems", "orderItems");
        assertThat(count(doc, "/order/orderItems")).isEqualTo(2.0);
        assertThat(count(doc, "//item")).isEqualTo(0.0);

        assertThat(xpath(doc, "/order/orderItems[1]/name")).isEqualTo("Galaxy");
        assertThat(xpath(doc, "/order/orderItems[1]/quantity")).isEqualTo("30");
        assertThat(xpath(doc, "/order/orderItems[2]/manufacturer")).isEqualTo("Philips");
        assertThat(xpath(doc, "/order/orderItems[2]/productId")).isEqualTo("23");
        assertThat(xpath(doc, "/order/orderItems[2]/quantity")).isEqualTo("10");

        assertSimilar(EXPECTED_TWO_ITEMS, actual);
    }

    @Test
    @DisplayName("DW-32 escapes markup characters in text values and writes well-formed XML")
    public void dw32EscapesMarkupCharacters() throws Exception {
        String input = ONE_ITEM.replace("\"address\":\"Main Street 123\"", "\"address\":\"A & B <C>\"");
        assertThat(input).contains("\"address\":\"A & B <C>\"");

        String actual = mapper.toOrderXml(input);

        Document doc = parse(actual);
        assertThat(xpath(doc, "/order/customer/address")).isEqualTo("A & B <C>");
    }

    @Test
    @DisplayName("DW-32 rejects a malformed page order")
    public void dw32MalformedJsonFails() {
        assertThatThrownBy(() -> mapper.toOrderXml("{")).isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------------------------------
    // DW-33: toSummaryJson
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("DW-33 writes the summary of the original SOAP reply as JSON with string leaves and an object orderItems")
    public void dw33SummaryFromReply() throws Exception {
        String actual = mapper.toSummaryJson(summaryFromReply());

        assertThat(actual).isEqualTo(EXPECTED_SUMMARY_JSON);

        JsonNode tree = json.readTree(actual);
        List<String> fieldNames = new ArrayList<>();
        Iterator<String> names = tree.fieldNames();
        names.forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly("orderId", "customer", "orderItems");

        JsonNode orderItems = tree.get("orderItems");
        assertThat(orderItems.isObject()).isTrue();
        assertThat(orderItems.isArray()).isFalse();
        JsonNode item = orderItems.get("item");
        assertThat(item.isObject()).isTrue();

        assertThat(tree.get("orderId").isTextual()).isTrue();
        assertThat(tree.get("orderId").asText()).isEqualTo("12");
        assertThat(item.get("quantity").isTextual()).isTrue();
        assertThat(item.get("quantity").asText()).isEqualTo("1");
        JsonNode receipt = item.get("purchaseReceipt");
        assertThat(receipt.get("totalPrice").isTextual()).isTrue();
        assertThat(receipt.get("totalPrice").asText()).isEqualTo("2550.0");
        assertThat(receipt.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("DW-33 ignores indentation and namespace declarations of the summary the XSLT hands over")
    public void dw33IndentedSummary() {
        assertThat(INDENTED_SUMMARY).contains("\n\t\t\t\t<id>1</id>\n");

        String actual = mapper.toSummaryJson(INDENTED_SUMMARY);

        assertThat(actual).isEqualTo(EXPECTED_SUMMARY_JSON);
    }

    @Test
    @DisplayName("DW-33 rejects a summary that is not well-formed XML")
    public void dw33MalformedXmlFails() {
        assertThatThrownBy(() -> mapper.toSummaryJson("<summary>")).isInstanceOf(Exception.class);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Parses {@code xml} with a namespace-aware DOM parser that refuses document type declarations.
     */
    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /** Evaluates {@code expr} on {@code d} as a string. */
    private static String xpath(Document d, String expr) throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();
        return (String) xp.evaluate(expr, d, XPathConstants.STRING);
    }

    /** Evaluates {@code count(expr)} on {@code d} as a number. */
    private static double count(Document d, String expr) throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();
        return (Double) xp.evaluate("count(" + expr + ")", d, XPathConstants.NUMBER);
    }

    /** Selects the first element that {@code expr} matches on {@code d}; fails when none matches. */
    private static Element element(Document d, String expr) throws Exception {
        XPath xp = XPathFactory.newInstance().newXPath();
        Node node = (Node) xp.evaluate(expr, d, XPathConstants.NODE);
        assertThat(node).as("element at %s", expr).isInstanceOf(Element.class);
        return (Element) node;
    }

    /** Local names of the element children of {@code e}, in document order. */
    private static List<String> childNames(Element e) {
        List<String> names = new ArrayList<>();
        NodeList children = e.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                names.add(child.getLocalName());
            }
        }
        return names;
    }

    /**
     * Asserts that {@code actual} is similar to {@code expected}: the same elements, text and order, with
     * whitespace-only text, indentation and declaration quote style ignored.
     */
    private static void assertSimilar(String expected, String actual) {
        Diff d = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.fromString(actual))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(d.hasDifferences()).as(d.toString()).isFalse();
    }

    /**
     * Reads {@code original/reply.xml} from the classpath as UTF-8 and returns its {@code summary} element,
     * serialized without an XML declaration.
     */
    private String summaryFromReply() throws Exception {
        String reply;
        try (InputStream in = getClass().getResourceAsStream(REPLY_RESOURCE)) {
            assertThat(in).as("classpath resource %s", REPLY_RESOURCE).isNotNull();
            reply = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Document doc = parse(reply);
        XPath xp = XPathFactory.newInstance().newXPath();
        Node summary = (Node) xp.evaluate(SUMMARY_IN_REPLY, doc, XPathConstants.NODE);
        assertThat(summary).as("summary element of %s", REPLY_RESOURCE).isNotNull();

        Transformer identity = TransformerFactory.newInstance().newTransformer();
        identity.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        identity.transform(new DOMSource(summary), new StreamResult(out));

        String text = out.toString();
        assertThat(text).startsWith("<summary");
        return text;
    }
}
