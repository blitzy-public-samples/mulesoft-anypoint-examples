package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model.CurrencyRates;

/**
 * Builds the orders JSON of DW-23
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:14-29] (D-034): the items
 * with a year above 2004, each with its title, its price per USD conversion rate and its distinct
 * authors, inside the wrapper object {@code {"orders": [...]}}.
 *
 * <p>The items are the direct {@code item} children of a document element named {@code orders},
 * in document order; a document element with any other name has no items. Every step below reads
 * direct child elements only, the first one where a name repeats, matched by local name, or by node
 * name when the parser is not namespace-aware.
 *
 * <ul>
 *   <li>{@code properties/year}: an item is kept when the trimmed text, read as a
 *       {@link BigDecimal}, is greater than 2004;</li>
 *   <li>{@code properties/title}: the {@code title} value, the text unchanged;</li>
 *   <li>{@code price}: the trimmed text read as a {@link BigDecimal}; each {@code prices} entry is
 *       the unrounded product of a rate's {@code ratio} and that price, with the rate's
 *       {@code currency}, one entry per rate of {@link CurrencyRates#usd()} in list order;</li>
 *   <li>{@code properties/authors/author}: one {@code authors} entry per distinct text, in order of
 *       first occurrence.</li>
 * </ul>
 *
 * <p>The text has the layout of the committed {@code orders.json}: two spaces of indentation per
 * level, {@code \n} line feeds, {@code ": "} between a member name and its value, one member or
 * element per line, {@code []} for an empty array, numbers written with
 * {@link BigDecimal#toPlainString()} and no line feed after the closing brace. For the committed
 * {@code orders.xml} and {@code currency.json} the result is the 793 characters of
 * {@code orders.json}; its first item reads
 *
 * <pre>{@code
 * {
 *   "title": "Everyday Italian",
 *   "prices": [
 *     {
 *       "price": 27.60,
 *       "currency": "EUR"
 *     },
 *     ...
 *   ],
 *   "authors": [
 *     {
 *       "author": "Giada De Laurentiis"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toOrdersJson(Document, CurrencyRates)} is side-effect free
 * and safe for concurrent use on distinct documents.
 */
@Component
public class OrderMapper {

    private static final String ORDERS = "orders";
    private static final String ITEM = "item";
    private static final String AUTHOR = "author";
    private static final String YEAR_PATH = "properties/year";
    private static final String TITLE_PATH = "properties/title";
    private static final String PRICE_PATH = "price";
    private static final String AUTHORS_PATH = "properties/authors";

    /** Separator of the steps of a child-element path. */
    private static final String PATH_SEPARATOR = "/";

    /** The year an item's year has to exceed for the item to be kept. */
    private static final BigDecimal YEAR_FLOOR = BigDecimal.valueOf(2004);

    /** Streaming JSON factory writing every {@link BigDecimal} with {@code toPlainString()}. */
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /**
     * Writes the DW-23 orders JSON of the given orders document and USD conversion rates.
     *
     * <p>Edge behaviours (D-472):
     * <ul>
     *   <li>an item without a {@code properties/year} element is not kept;</li>
     *   <li>no kept item, a document without items included, writes
     *       {@code {\n  "orders": []\n}};</li>
     *   <li>an item without a {@code properties/title} element writes {@code "title": null};</li>
     *   <li>an empty rate list writes {@code "prices": []} and reads no price;</li>
     *   <li>an item without {@code properties/authors} or without {@code author} children writes
     *       {@code "authors": []}.</li>
     * </ul>
     *
     * @param orders the parsed orders document
     * @param rates the USD conversion rates, applied in the order of {@link CurrencyRates#usd()}
     * @return the orders JSON text, without a trailing line feed
     * @throws NullPointerException if {@code orders}, {@code rates} or {@code rates.usd()} is
     *     {@code null}, or a kept item has no {@code price} element while the rate list is not
     *     empty (D-472)
     * @throws NumberFormatException if the year of an item or the price of a kept item is not a
     *     decimal number
     */
    public String toOrdersJson(Document orders, CurrencyRates rates) {
        Objects.requireNonNull(orders, "orders");
        Objects.requireNonNull(rates, "rates");
        List<CurrencyRates.Rate> usd = Objects.requireNonNull(rates.usd(), "rates.usd");
        List<Element> kept = keptItems(orders);
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(out)) {
            generator.setPrettyPrinter(new DwJsonPrettyPrinter());
            generator.writeStartObject();
            generator.writeArrayFieldStart(ORDERS);
            for (Element item : kept) {
                writeOrder(generator, item, usd);
            }
            generator.writeEndArray();
            generator.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    /** Returns, in document order, the items whose year is greater than 2004. */
    private static List<Element> keptItems(Document orders) {
        Element root = orders.getDocumentElement();
        if (root == null || !hasName(root, ORDERS)) {
            return List.of();
        }
        List<Element> kept = new ArrayList<>();
        for (Element item : childElements(root, ITEM)) {
            if (isAfter2004(item)) {
                kept.add(item);
            }
        }
        return kept;
    }

    /**
     * Tells whether the trimmed {@code properties/year} text of {@code item} is greater than 2004;
     * an item without a year is not kept (D-472).
     */
    private static boolean isAfter2004(Element item) {
        String year = text(item, YEAR_PATH);
        return year != null && new BigDecimal(year.trim()).compareTo(YEAR_FLOOR) > 0;
    }

    /** Writes the {@code title}, {@code prices} and {@code authors} object of one kept item. */
    private static void writeOrder(JsonGenerator generator, Element item, List<CurrencyRates.Rate> usd)
            throws IOException {
        generator.writeStartObject();
        String title = text(item, TITLE_PATH);
        generator.writeFieldName("title");
        if (title == null) {
            generator.writeNull();
        } else {
            generator.writeString(title);
        }
        writePrices(generator, item, usd);
        writeAuthors(generator, item);
        generator.writeEndObject();
    }

    /**
     * Writes the {@code prices} array: one {@code {price, currency}} object per rate, the price being
     * the unrounded product of the rate's ratio and the item price. The item price is read only when
     * the rate list is not empty (D-472).
     */
    private static void writePrices(JsonGenerator generator, Element item, List<CurrencyRates.Rate> usd)
            throws IOException {
        generator.writeArrayFieldStart("prices");
        if (!usd.isEmpty()) {
            String priceText = Objects.requireNonNull(text(item, PRICE_PATH), PRICE_PATH);
            BigDecimal price = new BigDecimal(priceText.trim());
            for (CurrencyRates.Rate rate : usd) {
                generator.writeStartObject();
                generator.writeFieldName("price");
                generator.writeNumber(rate.ratio().multiply(price));
                generator.writeFieldName("currency");
                generator.writeString(rate.currency());
                generator.writeEndObject();
            }
        }
        generator.writeEndArray();
    }

    /** Writes the {@code authors} array: one {@code {author}} object per distinct author text. */
    private static void writeAuthors(JsonGenerator generator, Element item) throws IOException {
        generator.writeArrayFieldStart("authors");
        for (String author : distinctAuthors(item)) {
            generator.writeStartObject();
            generator.writeFieldName(AUTHOR);
            generator.writeString(author);
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    /**
     * Returns the text of each {@code properties/authors/author} element of {@code item}, unchanged,
     * without repeats and in order of first occurrence; empty when the item has no {@code authors}
     * element (D-472).
     */
    private static Set<String> distinctAuthors(Element item) {
        Set<String> authors = new LinkedHashSet<>();
        Element authorsElement = element(item, AUTHORS_PATH);
        if (authorsElement != null) {
            for (Element author : childElements(authorsElement, AUTHOR)) {
                authors.add(author.getTextContent());
            }
        }
        return authors;
    }

    /**
     * Returns the text content of the element at the {@code /}-separated child-element
     * {@code path} below {@code parent}, or {@code null} when a step is missing.
     */
    private static String text(Element parent, String path) {
        Element element = element(parent, path);
        return element == null ? null : element.getTextContent();
    }

    /**
     * Returns the element at the {@code /}-separated child-element {@code path} below
     * {@code parent}, taking the first direct child of each step's name, or {@code null} when a step
     * is missing.
     */
    private static Element element(Element parent, String path) {
        Element current = parent;
        for (String step : path.split(PATH_SEPARATOR)) {
            current = child(current, step);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Returns the direct child elements of {@code parent} named {@code name}, in document order. */
    private static List<Element> childElements(Element parent, String name) {
        List<Element> matches = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (isElementNamed(node, name)) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    /** Returns the first direct child element of {@code parent} named {@code name}, or {@code null}. */
    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (isElementNamed(node, name)) {
                return (Element) node;
            }
        }
        return null;
    }

    /** Tells whether {@code node} is an element named {@code name}. */
    private static boolean isElementNamed(Node node, String name) {
        return node.getNodeType() == Node.ELEMENT_NODE && hasName((Element) node, name);
    }

    /**
     * Tells whether the local name of {@code element}, or its node name when it has none, is
     * {@code name}.
     */
    private static boolean hasName(Element element, String name) {
        String localName = element.getLocalName();
        return name.equals(localName != null ? localName : element.getNodeName());
    }

    /**
     * Pretty printer of the layout described on {@link OrderMapper}: objects and arrays indented by
     * two spaces per level after a {@code \n} line feed, {@code ": "} between a member name and its
     * value, and {@code {}} or {@code []} for an empty object or array. An instance tracks the
     * nesting depth of the one generator that writes through it.
     */
    private static final class DwJsonPrettyPrinter extends DefaultPrettyPrinter {

        private static final long serialVersionUID = 1L;

        /** Creates a printer with two-space indentation, {@code \n} line feeds and depth zero. */
        DwJsonPrettyPrinter() {
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            indentObjectsWith(indenter);
            indentArraysWith(indenter);
        }

        /** Creates a printer with the layout and depth of {@code base}. */
        DwJsonPrettyPrinter(DwJsonPrettyPrinter base) {
            super(base);
        }

        /**
         * Returns a new printer with this printer's layout.
         *
         * @return a new {@code DwJsonPrettyPrinter}
         */
        @Override
        public DwJsonPrettyPrinter createInstance() {
            return new DwJsonPrettyPrinter(this);
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

        /**
         * Closes an object: a line feed and the indentation of the enclosing level before
         * <code>&#125;</code> when the object has members, <code>&#125;</code> alone when it has
         * none.
         *
         * @param g the generator that receives the closing brace
         * @param nrOfEntries the number of members written in the object
         * @throws IOException when the generator cannot write
         */
        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
            if (!_objectIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfEntries > 0) {
                _objectIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw('}');
        }

        /**
         * Closes an array: a line feed and the indentation of the enclosing level before {@code ]}
         * when the array has elements, {@code ]} alone when it has none.
         *
         * @param g the generator that receives the closing bracket
         * @param nrOfValues the number of elements written in the array
         * @throws IOException when the generator cannot write
         */
        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
            if (!_arrayIndenter.isInline()) {
                --_nesting;
            }
            if (nrOfValues > 0) {
                _arrayIndenter.writeIndentation(g, _nesting);
            }
            g.writeRaw(']');
        }
    }
}
