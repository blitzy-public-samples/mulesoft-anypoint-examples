package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model.CurrencyRates;

/**
 * Unit tests of {@link OrderMapper#toOrdersJson(Document, CurrencyRates)}, the DW-23 orders JSON of
 * {@code OrderFlow} [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:14-29] (D-034):
 * the items with a year above 2004, each with its title, one price per USD conversion rate and its
 * distinct authors, inside the wrapper object {@code {"orders": [...]}}.
 *
 * <p>Each test calls a mapper created with {@code new}, with no Spring application context, on a
 * document parsed from the committed {@code input/orders.xml} or from synthetic XML, with the rates
 * of the committed {@code currency.json} or rates built from decimal strings, and asserts the JSON
 * text, or the tree Jackson reads from it with decimals as {@link BigDecimal}:
 * <ul>
 *   <li>the JSON of the committed sample equals the committed {@code original/orders.json};</li>
 *   <li>an item from 2004 is left out and an item from 2005 is written;</li>
 *   <li>repeated author texts are written once, in order of first occurrence (D-472);</li>
 *   <li>each price is the unrounded {@link BigDecimal} product with its scale kept;</li>
 *   <li>the prices of every item follow the order of the rate list;</li>
 *   <li>no item after 2004 writes {@code {\n  "orders": []\n}} (D-472);</li>
 *   <li>an item without a {@code properties/title} element writes {@code "title": null} (D-472);</li>
 *   <li>a document element other than {@code orders} has no items and writes
 *       {@code {\n  "orders": []\n}} (D-472).</li>
 * </ul>
 * These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least
 * 0.80 (D-049).
 */
public class OrderMapperTest {

    /** Parser feature that rejects a document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL =
            "http://apache.org/xml/features/disallow-doctype-decl";

    /** Matches one {@code price} member of the orders JSON and captures its number text. */
    private static final Pattern PRICE_MEMBER = Pattern.compile("\"price\": ([^,\\n]+),");

    /** JSON reader that binds decimal numbers as {@link BigDecimal}, with their written scale. */
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** The mapper under test. */
    private final OrderMapper mapper = new OrderMapper();

    /**
     * Asserts the JSON of the committed {@code input/orders.xml} (Everyday Italian 30 and Harry Potter
     * 29.99 from 2005, XQuery Kick Start 49.99 and Learning XML 39.95 from 2003) with the rates of the
     * committed {@code currency.json} equals the whole text of the committed
     * {@code original/orders.json}.
     *
     * @throws Exception when a classpath resource cannot be read or parsed
     */
    @Test
    public void committedSampleMatchesOriginalOrdersJson() throws Exception {
        Document orders;
        try (InputStream in = open("input/orders.xml")) {
            orders = parse(in);
        }

        String json = mapper.toOrdersJson(orders, committedRates());

        assertEquals(resource("original/orders.json"), json,
                "orders.json text equals the committed original/orders.json");
    }

    /**
     * Asserts that of the item {@code Before} from 2004 and the item {@code After} from 2005 only
     * {@code After} is written: the {@code orders} array holds one element, titled {@code After},
     * with its price 10 times the EUR ratio 0.92 and its author, and the text holds no
     * {@code "title": "Before"}.
     *
     * @throws Exception when the synthetic document or the written JSON cannot be parsed
     */
    @Test
    public void itemFromYear2004IsExcludedAndYear2005IsIncluded() throws Exception {
        Document orders = parse(orders(
                item("Before", "10", "2004", "Author 2004"),
                item("After", "10", "2005", "Author 2005")));

        String json = mapper.toOrdersJson(orders, rates(rate("EUR", "0.92")));

        JsonNode written = tree(json).path("orders");
        assertTrue(written.isArray(), "orders member is an array");
        assertEquals(1, written.size(), "number of elements of the orders array");
        assertEquals("After", written.path(0).path("title").asText(), "title of the one order written");
        assertFalse(json.contains("\"title\": \"Before\""), "orders.json text without the 2004 title");
        assertEquals("""
                {
                  "orders": [
                    {
                      "title": "After",
                      "prices": [
                        {
                          "price": 9.20,
                          "currency": "EUR"
                        }
                      ],
                      "authors": [
                        {
                          "author": "Author 2005"
                        }
                      ]
                    }
                  ]
                }""", json, "orders.json text for one item from 2004 and one from 2005");
    }

    /**
     * Asserts the authors Kurt Cagle, Per Bothner and Kurt Cagle of one item from 2005 are written as
     * the two {@code authors} entries Kurt Cagle and Per Bothner, in that order (D-472).
     *
     * @throws Exception when the synthetic document or the written JSON cannot be parsed
     */
    @Test
    public void duplicateAuthorsAreRemovedInFirstSeenOrder() throws Exception {
        Document orders = parse(orders(
                item("XQuery Kick Start", "49.99", "2005", "Kurt Cagle", "Per Bothner", "Kurt Cagle")));

        String json = mapper.toOrdersJson(orders, rates(rate("EUR", "0.92")));

        JsonNode authors = tree(json).path("orders").path(0).path("authors");
        assertEquals(2, authors.size(), "number of authors entries");
        assertEquals("Kurt Cagle", authors.path(0).path("author").asText(), "first authors entry");
        assertEquals("Per Bothner", authors.path(1).path("author").asText(), "second authors entry");
        assertEquals("""
                {
                  "orders": [
                    {
                      "title": "XQuery Kick Start",
                      "prices": [
                        {
                          "price": 45.9908,
                          "currency": "EUR"
                        }
                      ],
                      "authors": [
                        {
                          "author": "Kurt Cagle"
                        },
                        {
                          "author": "Per Bothner"
                        }
                      ]
                    }
                  ]
                }""", json, "orders.json text for an item with a repeated author");
    }

    /**
     * Asserts the items priced 30 and 29.99 from 2005 with the rates EUR 0.92, ARS 8.76 and GBP 0.66
     * built from decimal strings are written with the plain, unrounded products {@code 27.60},
     * {@code 262.80}, {@code 19.80}, {@code 27.5908}, {@code 262.7124} and {@code 19.7934}, in item
     * and rate order.
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void pricesKeepBigDecimalScale() throws Exception {
        Document orders = parse(orders(
                item("Everyday Italian", "30", "2005", "Giada De Laurentiis"),
                item("Harry Potter", "29.99", "2005", "J K. Rowling")));

        String json = mapper.toOrdersJson(orders, constructedRates());

        assertTrue(json.contains("\"price\": 27.60,"), "30 times 0.92 is written as 27.60");
        assertTrue(json.contains("\"price\": 27.5908,"), "29.99 times 0.92 is written as 27.5908");
        assertTrue(json.contains("\"price\": 262.80,"), "30 times 8.76 is written as 262.80");
        assertEquals(List.of("27.60", "262.80", "19.80", "27.5908", "262.7124", "19.7934"),
                matches(PRICE_MEMBER, json), "price texts in item and rate order");
    }

    /**
     * Asserts the prices follow the rate order: every {@code prices} array of the committed
     * {@code input/orders.xml} with the rates of the committed {@code currency.json} lists EUR, ARS
     * and GBP, and the {@code prices} array of one item from 2005 with rates built as GBP, EUR and ARS
     * lists GBP, EUR and ARS.
     *
     * @throws Exception when a classpath resource, the synthetic document or the written JSON cannot
     *     be read or parsed
     */
    @Test
    public void ratesKeepInputOrder() throws Exception {
        Document sample;
        try (InputStream in = open("input/orders.xml")) {
            sample = parse(in);
        }
        Document single = parse(orders(item("Single Item", "10", "2005", "Single Author")));

        JsonNode committed = tree(mapper.toOrdersJson(sample, committedRates())).path("orders");
        JsonNode constructed = tree(mapper.toOrdersJson(single,
                rates(rate("GBP", "0.66"), rate("EUR", "0.92"), rate("ARS", "8.76")))).path("orders");

        assertEquals(2, committed.size(), "number of orders of the committed sample");
        for (JsonNode order : committed) {
            assertEquals(List.of("EUR", "ARS", "GBP"), currencies(order),
                    "currency order of " + order.path("title").asText()
                            + " for the rates of the committed currency.json");
        }
        assertEquals(1, constructed.size(), "number of orders of the single 2005 item");
        assertEquals(List.of("GBP", "EUR", "ARS"), currencies(constructed.path(0)),
                "currency order for rates built as GBP, EUR, ARS");
    }

    /**
     * Asserts that items from the years 2004 and 2003 only give the wrapper object with an empty
     * {@code orders} array, {@code {\n  "orders": []\n}} (D-472).
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void noItemAfter2004YieldsEmptyOrdersArray() throws Exception {
        Document orders = parse(orders(
                item("Year 2004 Title", "10", "2004", "Author 2004"),
                item("Year 2003 Title", "10", "2003", "Author 2003")));

        String json = mapper.toOrdersJson(orders, rates(rate("EUR", "0.92")));

        assertEquals("{\n  \"orders\": []\n}", json, "orders.json text when no item is after 2004");
    }

    /**
     * Asserts an item from 2005 without a {@code properties/title} element is written with
     * {@code "title": null}, followed by its prices and authors (D-472).
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void itemWithoutTitleWritesNullTitle() throws Exception {
        Document orders = parse(orders(item(null, "10", "2005", "Untitled Author")));

        String json = mapper.toOrdersJson(orders, rates(rate("EUR", "0.92")));

        assertEquals("""
                {
                  "orders": [
                    {
                      "title": null,
                      "prices": [
                        {
                          "price": 9.20,
                          "currency": "EUR"
                        }
                      ],
                      "authors": [
                        {
                          "author": "Untitled Author"
                        }
                      ]
                    }
                  ]
                }""", json, "orders.json text for an item without a title element");
    }

    /**
     * Asserts a document whose document element is {@code order}, not {@code orders}, has no items and
     * gives {@code {\n  "orders": []\n}}, although it holds an {@code item} from 2005 (D-472).
     *
     * @throws Exception when the synthetic document cannot be parsed
     */
    @Test
    public void documentElementOtherThanOrdersYieldsEmptyOrdersArray() throws Exception {
        Document orders = parse(
                "<order>" + item("Year 2005 Title", "10", "2005", "Author 2005") + "</order>");

        String json = mapper.toOrdersJson(orders, rates(rate("EUR", "0.92")));

        assertEquals("{\n  \"orders\": []\n}", json,
                "orders.json text for a document whose document element is order");
    }

    /**
     * Reads the classpath {@code currency.json} with decimal ratios kept as written, and asserts its
     * rates are EUR 0.92, ARS 8.76 and GBP 0.66, in that order and with those scales.
     *
     * @return the rates of the committed {@code currency.json}
     * @throws IOException when the resource cannot be read or bound
     */
    private static CurrencyRates committedRates() throws IOException {
        CurrencyRates rates = JSON.readValue(resource("currency.json"), CurrencyRates.class);
        assertEquals(List.of(rate("EUR", "0.92"), rate("ARS", "8.76"), rate("GBP", "0.66")), rates.usd(),
                "rates of the committed currency.json");
        return rates;
    }

    /**
     * Builds the rates EUR 0.92, ARS 8.76 and GBP 0.66, in that order, each ratio read from its
     * decimal string.
     *
     * @return the conversion rates
     */
    private static CurrencyRates constructedRates() {
        return new CurrencyRates(List.of(
                new CurrencyRates.Rate("EUR", new BigDecimal("0.92")),
                new CurrencyRates.Rate("ARS", new BigDecimal("8.76")),
                new CurrencyRates.Rate("GBP", new BigDecimal("0.66"))));
    }

    /**
     * Reads {@code json} as a tree whose decimal numbers are {@link BigDecimal} values.
     *
     * @param json the JSON text
     * @return the root node
     * @throws IOException when the text is not well-formed JSON
     */
    private static JsonNode tree(String json) throws IOException {
        return JSON.readTree(json);
    }

    /**
     * Returns the {@code currency} text of each element of the {@code prices} array of
     * {@code order}, in array order.
     *
     * @param order one element of the {@code orders} array
     * @return the currency codes
     */
    private static List<String> currencies(JsonNode order) {
        List<String> codes = new ArrayList<>();
        for (JsonNode price : order.path("prices")) {
            codes.add(price.path("currency").asText());
        }
        return codes;
    }

    /**
     * Wraps {@code usd} in a {@link CurrencyRates}.
     *
     * @param usd the rates, in list order
     * @return the conversion rates
     */
    private static CurrencyRates rates(CurrencyRates.Rate... usd) {
        return new CurrencyRates(List.of(usd));
    }

    /**
     * Builds one rate whose ratio is read from the decimal string {@code ratio}.
     *
     * @param currency the currency code
     * @param ratio the decimal text of the ratio
     * @return the rate
     */
    private static CurrencyRates.Rate rate(String currency, String ratio) {
        return new CurrencyRates.Rate(currency, new BigDecimal(ratio));
    }

    /**
     * Returns the first capture group of each match of {@code pattern} in {@code text}, in text order.
     *
     * @param pattern the pattern with one capture group
     * @param text the text searched
     * @return the captured texts
     */
    private static List<String> matches(Pattern pattern, String text) {
        List<String> captured = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            captured.add(matcher.group(1));
        }
        return captured;
    }

    /**
     * Opens the classpath resource {@code path}.
     *
     * @param path the resource path relative to the classpath root
     * @return the open stream, which the caller closes
     */
    private static InputStream open(String path) {
        InputStream in = OrderMapperTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(in, "classpath resource " + path);
        return in;
    }

    /**
     * Reads the classpath resource {@code path} as UTF-8 text, untrimmed.
     *
     * @param path the resource path relative to the classpath root
     * @return the full text of the resource
     * @throws IOException when the resource cannot be read
     */
    private static String resource(String path) throws IOException {
        try (InputStream in = open(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses {@code in} with a namespace-aware parser that rejects a document type declaration.
     *
     * @param in the XML bytes
     * @return the parsed document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the bytes are not well-formed XML
     * @throws IOException when the bytes cannot be read
     */
    private static Document parse(InputStream in)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder().parse(in);
    }

    /**
     * Parses the XML text {@code xml}, encoded as UTF-8, with {@link #parse(InputStream)}.
     *
     * @param xml the XML text
     * @return the parsed document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed XML
     * @throws IOException when the text cannot be read
     */
    private static Document parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Wraps {@code items} in an {@code orders} document element.
     *
     * @param items the {@code item} elements, in document order
     * @return the XML text of the orders document
     */
    private static String orders(String... items) {
        return "<orders>" + String.join("", items) + "</orders>";
    }

    /**
     * Builds one {@code item} element of the {@code orders.xml} shape.
     *
     * @param title the {@code properties/title} text, or {@code null} for an item without a
     *     {@code title} element
     * @param price the {@code price} text
     * @param year the {@code properties/year} text
     * @param authors the {@code properties/authors/author} texts, in document order
     * @return the XML text of the item
     */
    private static String item(String title, String price, String year, String... authors) {
        StringBuilder xml = new StringBuilder()
                .append("<item><type>book</type><price>").append(price)
                .append("</price><properties>");
        if (title != null) {
            xml.append("<title>").append(title).append("</title>");
        }
        xml.append("<authors>");
        for (String author : authors) {
            xml.append("<author>").append(author).append("</author>");
        }
        return xml.append("</authors><year>").append(year).append("</year></properties></item>")
                .toString();
    }
}
