package com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Maps rows of the contacts CSV file to Dataverse contact attributes (D-177).
 *
 * <p>Instances hold no state; {@link #readRows(byte[])} and {@link #toContact(Map)} are side-effect
 * free and safe for concurrent use.
 */
@Component
public class DynamicsContactMapper {

    /**
     * Comma-separated values with {@code "} quoting and {@code \} escaping, the first record read as
     * the header, empty lines ignored and values kept untrimmed. CR, LF and CRLF end a record.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setEscape('\\')
            .setIgnoreEmptyLines(true)
            .get();

    /**
     * Reads a CSV document with a header row into one map per record, keyed by header name in header
     * order; a cell absent from a record maps to {@code null}.
     *
     * <p>The bytes are decoded as UTF-8. An empty cell maps to the empty string, and cells beyond the
     * header are not copied. An empty document, or a document holding only the header row, yields an
     * empty list.
     *
     * @param csv the CSV document, header row first
     * @return one insertion-ordered map per record, in record order
     * @throws NullPointerException if {@code csv} is {@code null}
     * @throws UncheckedIOException if the document cannot be parsed, for example when a quoted value
     *     is not terminated
     */
    public List<Map<String, String>> readRows(byte[] csv) {
        Objects.requireNonNull(csv, "csv");
        String text = new String(csv, StandardCharsets.UTF_8);
        try (CSVParser parser = FORMAT.parse(new StringReader(text))) {
            List<String> headers = parser.getHeaderNames();
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String name : headers) {
                    row.put(name, record.isSet(name) ? record.get(name) : null);
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps one CSV row to the Dataverse contact attributes {@code firstname}, {@code lastname},
     * {@code emailaddress1} and {@code telephone1}.
     *
     * <p>The attributes are filled in this order from these columns:
     *
     * <ul>
     *   <li>{@code firstname} from {@code firstname};</li>
     *   <li>{@code lastname} from {@code surname};</li>
     *   <li>{@code emailaddress1} from {@code email};</li>
     *   <li>{@code telephone1} from {@code phone}.</li>
     * </ul>
     *
     * <p>Values are copied unchanged. A column that is absent or {@code null} yields its attribute
     * with a {@code null} value.
     *
     * @param row one record of {@link #readRows(byte[])}, keyed by column name
     * @return a new insertion-ordered map holding exactly the four attributes
     * @throws NullPointerException if {@code row} is {@code null}
     */
    public Map<String, Object> toContact(Map<String, String> row) {
        Objects.requireNonNull(row, "row");
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("firstname", row.get("firstname"));
        contact.put("lastname", row.get("surname"));
        contact.put("emailaddress1", row.get("email"));
        contact.put("telephone1", row.get("phone"));
        return contact;
    }
}
