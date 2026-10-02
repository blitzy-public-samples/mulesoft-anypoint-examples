package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Builds the order report CSV of DW-24
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:30-37] (D-034): item count,
 * price total and average price over every item of the orders document, before any year filter.
 *
 * <p>The input is the document that DW-23 ({@code OrderMapper.toOrdersJson}) also reads. Its items
 * are the direct {@code item} children of a document element named {@code orders}; a document
 * element with any other name has no items. Element names are matched by local name, or by node
 * name when the parser is not namespace-aware.
 *
 * <p>The output is one header record and one data record, each ended by {@code \n}:
 *
 * <ul>
 *   <li>{@code totalNumberOfItems}: the number of items;</li>
 *   <li>{@code totalAmount}: the unrounded sum of the item prices;</li>
 *   <li>{@code averageSellingPrice}: that sum divided by the number of prices with
 *       {@link MathContext#DECIMAL128}.</li>
 * </ul>
 *
 * <p>Numbers are written with {@link BigDecimal#toPlainString()}. For the committed
 * {@code orders.xml} (prices 30, 29.99, 49.99 and 39.95) the result is the 68 characters
 *
 * <pre>{@code
 * totalNumberOfItems,totalAmount,averageSellingPrice
 * 4,149.93,37.4825
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toReportCsv(Document)} is side-effect free and safe for
 * concurrent use on distinct documents.
 */
@Component
public class ReportMapper {

    private static final String ORDERS = "orders";
    private static final String ITEM = "item";
    private static final String PRICE = "price";

    /** The message of the exception raised when no price is read. */
    private static final String NO_ITEMS_TO_AVERAGE = "No items to average";

    /** Comma-separated, minimal quoting, the DW-24 header, {@code \n} after every record. */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("totalNumberOfItems", "totalAmount", "averageSellingPrice")
            .setRecordSeparator("\n")
            .get();

    /**
     * Writes the DW-24 report of the given orders document as CSV text.
     *
     * <p>Every item is counted. The price of an item is the trimmed text of its first direct
     * {@code price} child, read as a {@link BigDecimal}; an item without a {@code price} child is
     * counted and adds no price (D-164).
     *
     * @param orders the parsed orders document
     * @return the header line {@code totalNumberOfItems,totalAmount,averageSellingPrice} and the
     *     data line, each followed by {@code \n}
     * @throws NullPointerException if {@code orders} is {@code null}
     * @throws IllegalArgumentException with the message {@code No items to average} when no price
     *     is read, including a document without items (D-164)
     * @throws NumberFormatException if a price text is not a decimal number
     */
    public String toReportCsv(Document orders) {
        Objects.requireNonNull(orders, "orders");
        List<Element> items = items(orders);
        List<BigDecimal> prices = prices(items);
        if (prices.isEmpty()) {
            throw new IllegalArgumentException(NO_ITEMS_TO_AVERAGE);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal price : prices) {
            total = total.add(price);
        }
        // The average divides the total by the number of prices read, which equals the item
        // count when every item has a price (D-164).
        BigDecimal average = total.divide(BigDecimal.valueOf(prices.size()), MathContext.DECIMAL128);
        return write(String.valueOf(items.size()), total.toPlainString(), average.toPlainString());
    }

    /** Returns the direct {@code item} children of an {@code orders} document element, else none. */
    private static List<Element> items(Document orders) {
        Element root = orders.getDocumentElement();
        if (root == null || !hasName(root, ORDERS)) {
            return List.of();
        }
        return childElements(root, ITEM);
    }

    /** Returns, in item order, the price of each item that has a direct {@code price} child. */
    private static List<BigDecimal> prices(List<Element> items) {
        List<BigDecimal> prices = new ArrayList<>(items.size());
        for (Element item : items) {
            Element price = firstChildElement(item, PRICE);
            if (price != null) {
                prices.add(new BigDecimal(price.getTextContent().trim()));
            }
        }
        return prices;
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

    /**
     * Returns the first direct child element of {@code parent} named {@code name}, or {@code null}.
     */
    private static Element firstChildElement(Element parent, String name) {
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

    /** Prints the header record and the one data record of {@link #FORMAT}. */
    private static String write(
            String totalNumberOfItems, String totalAmount, String averageSellingPrice) {
        StringBuilder csv = new StringBuilder();
        try (CSVPrinter printer = new CSVPrinter(csv, FORMAT)) {
            printer.printRecord(totalNumberOfItems, totalAmount, averageSellingPrice);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return csv.toString();
    }
}
