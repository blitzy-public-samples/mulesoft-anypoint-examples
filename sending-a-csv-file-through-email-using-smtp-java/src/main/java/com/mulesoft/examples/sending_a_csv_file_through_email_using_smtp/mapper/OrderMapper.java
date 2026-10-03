package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Maps the records of an orders CSV document to order maps: DataWeave setter DW-29 of flow
 * {@code csv-to-smtpFlow} [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:8-17],
 * re-implemented in Java (D-034, D-398).
 *
 * <p>The input is CSV text whose first record is the header: {@code ,} separates values, {@code "}
 * quotes them and {@code \} escapes the next character; CR, LF and CRLF each end a record, empty
 * lines are skipped and values are not trimmed. Each record becomes one map holding exactly the keys
 * {@code name}, {@code orderId}, {@code pricePerUnit}, {@code units} and {@code totalPrice}, in that
 * order. The numbers are rendered by {@link #dwNumber(BigDecimal)}.
 *
 * <p>Instances hold no state; {@link #toOrders(String)} has no side effects and is safe for
 * concurrent use. The class works with or without a Spring context.
 *
 * <p>Example: the committed {@code input.csv}
 * <pre>{@code
 * orderId,name,units,pricePerUnit
 * 1,aaa,2.0,10
 * 2,bbb,4.15,5
 * }</pre>
 * maps to a list whose {@code toString()} is the committed {@code email.txt}, one line:
 * <pre>{@code
 * [{name=aaa, orderId=1, pricePerUnit=10, units=2, totalPrice=20}, {name=bbb, orderId=2, pricePerUnit=5, units=4.15, totalPrice=20.75}]
 * }</pre>
 */
@Component
public class OrderMapper {

    /**
     * Reads CSV text with a header row and returns one order map per record, in record order.
     *
     * <p>Each map is a new mutable {@link LinkedHashMap} holding these keys, in this order:
     * <ol>
     *   <li>{@code name}: the raw {@code name} cell;</li>
     *   <li>{@code orderId}: the raw {@code orderId} cell, kept as a {@code String};</li>
     *   <li>{@code pricePerUnit}: the {@code pricePerUnit} cell as a number;</li>
     *   <li>{@code units}: the {@code units} cell as a number;</li>
     *   <li>{@code totalPrice}: the exact product of {@code units} and {@code pricePerUnit} as a
     *       number.</li>
     * </ol>
     * A text cell whose column is absent from the header, or from a record shorter than the header,
     * maps to {@code null}. Each number cell is parsed from its raw text with
     * {@link BigDecimal#BigDecimal(String)}; the product is taken on the parsed values, and the three
     * numbers are then rendered by {@link #dwNumber(BigDecimal)}. Columns other than the four read
     * here are not copied.
     *
     * <p>Text holding only the header row, or no record at all, yields an empty mutable list, whose
     * {@code toString()} is {@code []}.
     *
     * @param csv the CSV text, header row first
     * @return a new mutable list of new mutable order maps, one per record, in record order
     * @throws NullPointerException if {@code csv} is {@code null}, or if a record has no
     *     {@code units} or {@code pricePerUnit} cell
     * @throws NumberFormatException if a {@code units} or {@code pricePerUnit} cell is not text that
     *     {@link BigDecimal#BigDecimal(String)} accepts, an empty cell or a cell with surrounding
     *     spaces included
     * @throws UncheckedIOException if the text is malformed, for example when a quoted value is not
     *     terminated; the parser's {@link IOException} is its cause
     * @throws IllegalArgumentException if the header row holds an empty column name
     */
    public List<Map<String, Object>> toOrders(String csv) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                // The DataWeave CSV reader's default escape character (D-398).
                .setEscape('\\')
                .get();
        List<Map<String, Object>> orders = new ArrayList<>();
        try (CSVParser parser = format.parse(new StringReader(csv))) {
            for (CSVRecord record : parser) {
                BigDecimal pricePerUnit = new BigDecimal(raw(record, "pricePerUnit"));
                BigDecimal units = new BigDecimal(raw(record, "units"));
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("name", raw(record, "name"));
                order.put("orderId", raw(record, "orderId"));
                order.put("pricePerUnit", dwNumber(pricePerUnit));
                order.put("units", dwNumber(units));
                order.put("totalPrice", dwNumber(units.multiply(pricePerUnit)));
                orders.add(order);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return orders;
    }

    /**
     * Renders a number as the DataWeave 1.0 Java writer does (D-398). Trailing fractional zeros are
     * removed first. An integral value then becomes an {@link Integer} when it fits 32 bits, else a
     * {@link Long} when it fits 64 bits, else a {@link BigInteger}; any other value is returned as the
     * stripped {@link BigDecimal}.
     *
     * <p>Examples: {@code 2.0} gives the {@code Integer} {@code 2}, {@code 20.0} the {@code Integer}
     * {@code 20}, {@code 0.0} the {@code Integer} {@code 0}, {@code 3000000000} the {@code Long}
     * {@code 3000000000}, a 40-digit integer a {@code BigInteger}, and {@code 20.750} the
     * {@code BigDecimal} {@code 20.75}.
     *
     * @param v the number to render
     * @return an {@code Integer}, {@code Long}, {@code BigInteger} or {@code BigDecimal} equal in
     *     value to {@code v}
     * @throws NullPointerException if {@code v} is {@code null}
     */
    static Object dwNumber(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() > 0) {
            return s;
        }
        BigInteger i = s.toBigIntegerExact();
        if (i.bitLength() < 32) {
            return Integer.valueOf(i.intValue());
        }
        if (i.bitLength() < 64) {
            return Long.valueOf(i.longValue());
        }
        return i;
    }

    /**
     * Returns the raw value of {@code column} in {@code record}, or {@code null} when the header has
     * no such column or the record is too short to hold it.
     */
    private static String raw(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column) : null;
    }
}
