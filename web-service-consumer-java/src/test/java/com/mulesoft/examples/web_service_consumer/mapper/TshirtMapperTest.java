package com.mulesoft.examples.web_service_consumer.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Unit tests of {@link TshirtMapper}, the four DataWeave setters of
 * [web-service-consumer/src/main/app/tshirt-service-consumer.xml], each run on {@code new TshirtMapper()} with no
 * application context:
 *
 * <ul>
 *   <li>DW-35 {@link TshirtMapper#toOrderTshirt(String)} [:12-16]: the root is {@code ns0:OrderTshirt} of namespace
 *       {@code http://mulesoft.org/tshirt-service}; every JSON member is an unqualified child element in input key
 *       order, duplicates and extra keys included; numbers keep their input text; {@code null} is an empty element;
 *       nested objects nest, arrays repeat their member element, and a scalar root is the root's text; null,
 *       blank, malformed and trailing input is rejected;</li>
 *   <li>DW-36 {@link TshirtMapper#authenticationHeader(String)} [:17-23]: the generated JAXB
 *       {@code AuthenticationHeader} (D-028) carries the API key passed in;</li>
 *   <li>DW-37 {@link TshirtMapper#toOrderJson(Node)} [:28-31]: the {@code OrderTshirtResponse} element is the exact
 *       JSON text {@link #ORDER_JSON}, with prefixes, attributes and whitespace-only text dropped, an empty element
 *       as {@code null} and repeated elements as repeated keys;</li>
 *   <li>DW-38 {@link TshirtMapper#toInventoryJson(Node)} [:41-45]: the five-item {@code ListInventoryResponse} is
 *       the exact JSON text {@link #INVENTORY_JSON}; another root name, another namespace and an empty response
 *       give {@code null}.</li>
 * </ul>
 *
 * <p>The sample request and the inventory response are inlined from the Studio {@code sample_data} previews
 * (D-037). Every JSON expectation is compared byte for byte. The test imports no JAX-WS, CXF or Mule type (D-050).
 * These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 */
public class TshirtMapperTest {

    /** Target namespace of the t-shirt service schema [tshirt.wsdl:9]. */
    private static final String NS = "http://mulesoft.org/tshirt-service";

    /** The sample order request of the Studio {@code sample_data/json.json} preview, in its key order (D-037). */
    private static final String SAMPLE_ORDER_JSON = String.join("\n",
            "{",
            "\t\"email\":\"customerID@gmail.com\",",
            "\t\"address1\":\"Corrientes 316\",",
            "\t\"address2\":\"EP\",",
            "\t\"city\":\"Buenos Aires\",",
            "\t\"country\":\"Argentina\",",
            "\t\"name\":\"MuleSoft Argentina\",",
            "\t\"postalCode\":\"C1043AAQ\",",
            "\t\"size\":\"L\",",
            "\t\"stateOrProvince\":\"CABA\"",
            "}");

    /** The order response stub: a prefixed {@code OrderTshirtResponse} holding {@code orderId} {@code 1}. */
    private static final String ORDER_XML = "<ns2:OrderTshirtResponse xmlns:ns2=\"" + NS + "\">"
            + "<orderId>1</orderId></ns2:OrderTshirtResponse>";

    /** DW-37 JSON of the order response stub. */
    private static final String ORDER_JSON = String.join("\n",
            "{",
            "  \"OrderTshirtResponse\": {",
            "    \"orderId\": \"1\"",
            "  }",
            "}");

    /** DW-37 JSON of an order response whose {@code orderId} element is empty. */
    private static final String ORDER_JSON_NULL = String.join("\n",
            "{",
            "  \"OrderTshirtResponse\": {",
            "    \"orderId\": null",
            "  }",
            "}");

    /** DW-37 JSON of {@code <r><a>1</a><a>2</a></r>}. */
    private static final String REPEATED_KEYS_JSON = String.join("\n",
            "{",
            "  \"r\": {",
            "    \"a\": \"1\",",
            "    \"a\": \"2\"",
            "  }",
            "}");

    /**
     * The five-item inventory response stub, pretty-printed as the Studio
     * {@code sample_data/ListInventoryResponse.xml} preview (D-037).
     */
    private static final String INVENTORY_XML = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<ns2:ListInventoryResponse xmlns:ns2=\"" + NS + "\">",
            "  <inventory>",
            "    <productCode>4102</productCode>",
            "    <size>L</size>",
            "    <description>Prueba</description>",
            "    <count>2</count>",
            "  </inventory>",
            "  <inventory>",
            "    <productCode>1412</productCode>",
            "    <size>L</size>",
            "    <description>Foo</description>",
            "    <count>9</count>",
            "  </inventory>",
            "  <inventory>",
            "    <productCode>5656</productCode>",
            "    <size>S</size>",
            "    <description>Bar</description>",
            "    <count>2</count>",
            "  </inventory>",
            "  <inventory>",
            "    <productCode>5657</productCode>",
            "    <size>M</size>",
            "    <description>Prueba2</description>",
            "    <count>3</count>",
            "  </inventory>",
            "  <inventory>",
            "    <productCode>1411</productCode>",
            "    <size>M</size>",
            "    <description>Awesome Tshirt</description>",
            "    <count>5</count>",
            "  </inventory>",
            "</ns2:ListInventoryResponse>");

    /** DW-38 JSON of {@link #INVENTORY_XML}: five repeated {@code inventory} members in stub order. */
    private static final String INVENTORY_JSON = String.join("\n",
            "{",
            "  \"inventory\": {",
            "    \"productCode\": \"4102\",",
            "    \"size\": \"L\",",
            "    \"description\": \"Prueba\",",
            "    \"count\": \"2\"",
            "  },",
            "  \"inventory\": {",
            "    \"productCode\": \"1412\",",
            "    \"size\": \"L\",",
            "    \"description\": \"Foo\",",
            "    \"count\": \"9\"",
            "  },",
            "  \"inventory\": {",
            "    \"productCode\": \"5656\",",
            "    \"size\": \"S\",",
            "    \"description\": \"Bar\",",
            "    \"count\": \"2\"",
            "  },",
            "  \"inventory\": {",
            "    \"productCode\": \"5657\",",
            "    \"size\": \"M\",",
            "    \"description\": \"Prueba2\",",
            "    \"count\": \"3\"",
            "  },",
            "  \"inventory\": {",
            "    \"productCode\": \"1411\",",
            "    \"size\": \"M\",",
            "    \"description\": \"Awesome Tshirt\",",
            "    \"count\": \"5\"",
            "  }",
            "}");

    /** The mapper under test. */
    private final TshirtMapper mapper = new TshirtMapper();

    // ---------------------------------------------------------------------------------------------
    // DW-35: toOrderTshirt
    // ---------------------------------------------------------------------------------------------

    /**
     * DW-35: asserts {@code {"size":"L"}} gives the root {@code OrderTshirt} of namespace {@link #NS} with prefix
     * {@code ns0}, serialized as {@code <ns0:OrderTshirt ...>} with the declaration
     * {@code xmlns:ns0="http://mulesoft.org/tshirt-service"}.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtWrapsPayloadInQualifiedRoot() throws Exception {
        Element root = toDom(mapper.toOrderTshirt("{\"size\":\"L\"}"));

        assertThat(root.getNamespaceURI()).isEqualTo(NS);
        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(root.getPrefix()).isEqualTo("ns0");
        assertThat(serialize(root))
                .startsWith("<ns0:OrderTshirt")
                .contains("xmlns:ns0=\"http://mulesoft.org/tshirt-service\"");
    }

    /**
     * DW-35: asserts the sample order request gives one unqualified child element per member, with the member's
     * name and text, in JSON key order rather than the WSDL sequence order.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtWritesUnqualifiedChildrenInKeyOrder() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt(SAMPLE_ORDER_JSON)));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(
                        tuple("email", "customerID@gmail.com"),
                        tuple("address1", "Corrientes 316"),
                        tuple("address2", "EP"),
                        tuple("city", "Buenos Aires"),
                        tuple("country", "Argentina"),
                        tuple("name", "MuleSoft Argentina"),
                        tuple("postalCode", "C1043AAQ"),
                        tuple("size", "L"),
                        tuple("stateOrProvince", "CABA"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts a key outside the WSDL schema, {@code giftWrap}, is written after {@code size} as an
     * unqualified element with text {@code yes}.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtCarriesExtraKeys() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"size\":\"L\",\"giftWrap\":\"yes\"}")));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("size", "L"), tuple("giftWrap", "yes"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts numbers keep their input text: {@code 1.50} stays {@code 1.50} and {@code 3} stays {@code 3}.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtKeepsNumberTextAsGiven() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"price\":1.50,\"qty\":3}")));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("price", "1.50"), tuple("qty", "3"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts {@code true} and {@code false} are written as the texts {@code true} and {@code false}.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtRendersBooleans() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"gift\":true,\"rush\":false}")));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("gift", "true"), tuple("rush", "false"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts a {@code null} member is an unqualified element with no content: no text and no child
     * elements.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtRendersNullAsEmptyElement() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"address2\":null}")));

        assertThat(children).extracting(Element::getLocalName).containsExactly("address2");
        assertUnqualified(children);
        Element address2 = children.get(0);
        assertThat(address2.getTextContent()).isEmpty();
        assertThat(childElements(address2)).isEmpty();
        assertThat(address2.hasChildNodes()).isFalse();
    }

    /**
     * DW-35: asserts a nested object is an element holding one unqualified child element per nested member, in
     * key order.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtRendersNestedObjectAsNestedElements() throws Exception {
        List<Element> children =
                childElements(toDom(mapper.toOrderTshirt("{\"addr\":{\"city\":\"X\",\"zip\":\"1\"}}")));

        assertThat(children).extracting(Element::getLocalName).containsExactly("addr");
        assertUnqualified(children);
        List<Element> nested = childElements(children.get(0));
        assertThat(nested)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("city", "X"), tuple("zip", "1"));
        assertUnqualified(nested);
    }

    /**
     * DW-35: asserts an array member gives one element of the member's name per item, directly under the root, in
     * item order.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtRendersArrayAsRepeatedElements() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"tag\":[\"a\",\"b\"]}")));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("tag", "a"), tuple("tag", "b"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts a repeated key gives one element per occurrence, in input order.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtKeepsDuplicateKeys() throws Exception {
        List<Element> children = childElements(toDom(mapper.toOrderTshirt("{\"k\":\"x\",\"k\":\"y\"}")));

        assertThat(children)
                .extracting(Element::getLocalName, Element::getTextContent)
                .containsExactly(tuple("k", "x"), tuple("k", "y"));
        assertUnqualified(children);
    }

    /**
     * DW-35: asserts the JSON string root {@code "abc"} is the text of the root element, which has no child
     * elements.
     *
     * @throws Exception when the mapper or the identity transform fails
     */
    @Test
    public void toOrderTshirtRendersScalarRootAsText() throws Exception {
        Element root = toDom(mapper.toOrderTshirt("\"abc\""));

        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        assertThat(root.getTextContent()).isEqualTo("abc");
        assertThat(childElements(root)).isEmpty();
    }

    /**
     * DW-35: asserts a {@code null}, empty or whitespace-only request throws {@link IllegalArgumentException}.
     *
     * @param json the rejected request body
     * @throws Exception not thrown: the assertion catches the mapper's exception
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t"})
    public void toOrderTshirtRejectsNullEmptyAndBlankInput(String json) throws Exception {
        assertThatThrownBy(() -> mapper.toOrderTshirt(json)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * DW-35: asserts malformed JSON throws the parser's {@link JsonProcessingException} itself, not wrapped.
     *
     * @throws Exception not thrown: the assertion catches the mapper's exception
     */
    @Test
    public void toOrderTshirtRejectsMalformedJson() throws Exception {
        Throwable thrown = catchThrowable(() -> mapper.toOrderTshirt("{\"size\":"));

        assertThat(thrown).isInstanceOf(JsonProcessingException.class);
    }

    /**
     * DW-35: asserts a second JSON value after the root value throws {@link IllegalArgumentException}.
     *
     * @throws Exception not thrown: the assertion catches the mapper's exception
     */
    @Test
    public void toOrderTshirtRejectsTrailingContent() throws Exception {
        assertThatThrownBy(() -> mapper.toOrderTshirt("{} {}")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------------------------
    // DW-36: authenticationHeader
    // ---------------------------------------------------------------------------------------------

    /**
     * DW-36: asserts the generated JAXB {@code AuthenticationHeader} (D-028) carries the API key passed in.
     *
     * @throws Exception not thrown
     */
    @Test
    public void authenticationHeaderCarriesApiKey() throws Exception {
        // Typed by the mapper's return type, the generated JAXB AuthenticationHeader (D-028); this file imports no
        // generated-package type.
        var header = mapper.authenticationHeader("abc");

        assertThat(header).isNotNull();
        assertThat(header.getApiKey()).isEqualTo("abc");
    }

    // ---------------------------------------------------------------------------------------------
    // DW-37: toOrderJson
    // ---------------------------------------------------------------------------------------------

    /**
     * DW-37: asserts the prefixed order response stub gives exactly {@link #ORDER_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonRendersOrderStub() throws Exception {
        assertThat(mapper.toOrderJson(parse(ORDER_XML))).isEqualTo(ORDER_JSON);
    }

    /**
     * DW-37: asserts the unqualified stub, the shape of the Studio {@code sample_data/OrderTshirtResponse.xml}
     * preview (D-037), gives exactly {@link #ORDER_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonRendersUnqualifiedStubIdentically() throws Exception {
        String xml = "<OrderTshirtResponse><orderId>1</orderId></OrderTshirtResponse>";

        assertThat(mapper.toOrderJson(parse(xml))).isEqualTo(ORDER_JSON);
    }

    /**
     * DW-37: asserts attributes on the root and on {@code orderId} are not written: the output is exactly
     * {@link #ORDER_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonDropsPrefixesAndAttributes() throws Exception {
        String xml = "<ns2:OrderTshirtResponse xmlns:ns2=\"" + NS + "\" a=\"b\">"
                + "<orderId x=\"y\">1</orderId></ns2:OrderTshirtResponse>";

        assertThat(mapper.toOrderJson(parse(xml))).isEqualTo(ORDER_JSON);
    }

    /**
     * DW-37: asserts an empty {@code orderId} element gives exactly {@link #ORDER_JSON_NULL}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonRendersEmptyElementAsNull() throws Exception {
        String xml = "<ns2:OrderTshirtResponse xmlns:ns2=\"" + NS + "\"><orderId/></ns2:OrderTshirtResponse>";

        assertThat(mapper.toOrderJson(parse(xml))).isEqualTo(ORDER_JSON_NULL);
    }

    /**
     * DW-37: asserts the stub pretty-printed with line feeds and two-space indentation gives exactly
     * {@link #ORDER_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonIgnoresWhitespaceOnlyText() throws Exception {
        String xml = String.join("\n",
                "<ns2:OrderTshirtResponse xmlns:ns2=\"" + NS + "\">",
                "  <orderId>1</orderId>",
                "</ns2:OrderTshirtResponse>");

        assertThat(mapper.toOrderJson(parse(xml))).isEqualTo(ORDER_JSON);
    }

    /**
     * DW-37: asserts repeated sibling elements give repeated member names, not an array: exactly
     * {@link #REPEATED_KEYS_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonRendersRepeatedElementsAsRepeatedKeys() throws Exception {
        assertThat(mapper.toOrderJson(parse("<r><a>1</a><a>2</a></r>"))).isEqualTo(REPEATED_KEYS_JSON);
    }

    /**
     * DW-37: asserts the stub's JSON ends without a line feed and separates member names from values with
     * {@code ": "}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toOrderJsonHasNoTrailingNewline() throws Exception {
        assertThat(mapper.toOrderJson(parse(ORDER_XML)))
                .doesNotEndWith("\n")
                .contains("\"orderId\": \"1\"")
                .doesNotContain("\":\"");
    }

    // ---------------------------------------------------------------------------------------------
    // DW-38: toInventoryJson
    // ---------------------------------------------------------------------------------------------

    /**
     * DW-38: asserts the five-item inventory response stub gives exactly {@link #INVENTORY_JSON}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toInventoryJsonRendersFiveItemStub() throws Exception {
        assertThat(mapper.toInventoryJson(parse(INVENTORY_XML))).isEqualTo(INVENTORY_JSON);
    }

    /**
     * DW-38: asserts a root of the service namespace with another local name gives {@code null}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toInventoryJsonReturnsNullForOtherRootName() throws Exception {
        String xml = "<ns2:Other xmlns:ns2=\"" + NS + "\"><inventory/></ns2:Other>";

        assertThat(mapper.toInventoryJson(parse(xml))).isEqualTo("null");
    }

    /**
     * DW-38: asserts a {@code ListInventoryResponse} root of another namespace gives {@code null}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toInventoryJsonReturnsNullForOtherNamespace() throws Exception {
        String xml = "<x:ListInventoryResponse xmlns:x=\"urn:other\"><inventory/></x:ListInventoryResponse>";

        assertThat(mapper.toInventoryJson(parse(xml))).isEqualTo("null");
    }

    /**
     * DW-38: asserts an empty {@code ListInventoryResponse} of the service namespace gives {@code null}.
     *
     * @throws Exception when the stub cannot be parsed
     */
    @Test
    public void toInventoryJsonReturnsNullForEmptyResponse() throws Exception {
        String xml = "<ns2:ListInventoryResponse xmlns:ns2=\"" + NS + "\"/>";

        assertThat(mapper.toInventoryJson(parse(xml))).isEqualTo("null");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Parses {@code xml} as UTF-8 with a namespace-aware JDK parser and returns its document element. */
    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return document.getDocumentElement();
    }

    /** Copies {@code source} into a new DOM document with one identity transform and returns its root element. */
    private static Element toDom(Source source) throws Exception {
        DOMResult result = new DOMResult();
        newTransformer().transform(source, result);
        return ((Document) result.getNode()).getDocumentElement();
    }

    /** Writes {@code node} as XML text without an XML declaration. */
    private static String serialize(Node node) throws Exception {
        Transformer transformer = newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(out));
        return out.toString();
    }

    /** Returns a new identity transformer of the JDK's default {@link TransformerFactory}. */
    private static Transformer newTransformer() throws Exception {
        return TransformerFactory.newInstance().newTransformer();
    }

    /** Returns the element children of {@code element} in document order, without text and other nodes. */
    private static List<Element> childElements(Element element) {
        List<Element> children = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                children.add(childElement);
            }
        }
        return children;
    }

    /** Asserts every element of {@code elements} has no namespace URI and no prefix. */
    private static void assertUnqualified(List<Element> elements) {
        assertThat(elements).allSatisfy(element -> {
            assertThat(element.getNamespaceURI()).isNull();
            assertThat(element.getPrefix()).isNull();
        });
    }
}

