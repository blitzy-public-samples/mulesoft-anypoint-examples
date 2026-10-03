package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper;

import java.io.IOException;
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
 * Maps the records of a customers CSV file to MongoDB documents: DataWeave setter DW-12
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:11-20], re-implemented in
 * Java (D-034, D-236).
 *
 * <p>The input is a UTF-8 CSV document whose first record is the header: {@code ,} separates
 * values, {@code "} quotes them and {@code \} escapes the next character; CR, LF and CRLF each end
 * a record, empty lines are skipped and values are not trimmed. Each record becomes one document
 * holding exactly the keys {@code firstname}, {@code surname}, {@code phone} and {@code email}, in
 * that order.
 *
 * <p>Instances hold no state; {@link #toDocuments(byte[])} has no side effects and is safe for
 * concurrent use. The class works with or without a Spring context.
 *
 * <p>Example: the bytes
 * {@code firstname,surname,phone,email\r\nJohn,Doe,096548763,john.doe@texasComp.com} map to a list
 * whose {@code toString()} is
 * {@code [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]}.
 */
@Component
public class CustomerCsvMapper {

    /**
     * Comma-separated values with {@code "} quoting and {@code \} escaping, the first record read
     * as the header and skipped, empty lines ignored and values kept untrimmed.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setEscape('\\')
            .get();

    /** The document keys, in output order; each key reads the header column of the same name. */
    private static final List<String> KEYS = List.of("firstname", "surname", "phone", "email");

    /**
     * Reads a CSV document with a header row and returns one document per record, in record order.
     *
     * <p>Each document is a new mutable {@link LinkedHashMap} holding the four keys
     * {@code firstname}, {@code surname}, {@code phone} and {@code email}, in that order. A value
     * is the record's raw column string, with no trimming, case change or number conversion, so
     * {@code 096548763} stays the string {@code "096548763"}. A key whose column is absent from the
     * header, or from a record shorter than the header, is present with a {@code null} value. An
     * empty cell gives the empty string. Columns other than the four keys are not copied.
     *
     * <p>A document of 0 bytes, or one holding only the header row, yields an empty mutable list.
     *
     * @param csv the CSV document as UTF-8 bytes, header row first
     * @return a new mutable list of new mutable documents, one per record, in record order
     * @throws NullPointerException if {@code csv} is {@code null}
     * @throws UncheckedIOException if the document is malformed, for example when a quoted value is
     *     not terminated or a character follows a closing quote; the parser's
     *     {@link org.apache.commons.csv.CSVException} is its cause
     * @throws IllegalArgumentException if the header row holds an empty column name
     */
    public List<Map<String, Object>> toDocuments(byte[] csv) {
        Objects.requireNonNull(csv, "csv");
        String text = new String(csv, StandardCharsets.UTF_8);
        try (CSVParser parser = CSVParser.parse(text, FORMAT)) {
            List<Map<String, Object>> documents = new ArrayList<>();
            for (CSVRecord record : parser) {
                documents.add(toDocument(record));
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Builds the document of one record: the four keys in {@link #KEYS} order, each holding the
     * record's value for the column of the same name, or {@code null} when that column is absent
     * from the header or from the record.
     */
    private static Map<String, Object> toDocument(CSVRecord record) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (String key : KEYS) {
            document.put(key, record.isMapped(key) && record.isSet(key) ? record.get(key) : null);
        }
        return document;
    }
}
