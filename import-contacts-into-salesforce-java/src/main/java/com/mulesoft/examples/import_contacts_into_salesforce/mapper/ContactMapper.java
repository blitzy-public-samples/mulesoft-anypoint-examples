package com.mulesoft.examples.import_contacts_into_salesforce.mapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Maps contact CSV rows to Salesforce {@code Contact} field maps, re-implementing DW-10 of
 * {@code import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml}. See D-034.
 */
@Component
public class ContactMapper {

    private static final String FIRST_NAME_FIELD = "FirstName";
    private static final String LAST_NAME_FIELD = "LastName";
    private static final String EMAIL_FIELD = "Email";
    private static final String PHONE_FIELD = "Phone";

    private static final String FIRST_NAME_COLUMN = "firstname";
    private static final String SURNAME_COLUMN = "surname";
    private static final String EMAIL_COLUMN = "email";
    private static final String PHONE_COLUMN = "phone";

    /**
     * Separator {@code ,}, quote {@code "}, escape {@code \}, header names read from the first record and
     * matched case-sensitively, empty lines ignored, LF, CRLF and CR record separators accepted.
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDelimiter(',')
            .setQuote('"')
            .setEscape('\\')
            .get();

    /**
     * Maps every data record of a contact CSV text to one Salesforce {@code Contact} field map.
     *
     * <p>Each map is a new {@link LinkedHashMap} holding exactly these four keys, in this order:
     * <ol>
     *   <li>{@code FirstName}: the value of column {@code firstname}</li>
     *   <li>{@code LastName}: the value of column {@code surname}</li>
     *   <li>{@code Email}: the value of column {@code email}</li>
     *   <li>{@code Phone}: the value of column {@code phone}</li>
     * </ol>
     *
     * <p>Values are the record's strings unchanged: no trimming, case change or number conversion, and an
     * empty cell stays {@code ""}. A column the header lacks, or a cell missing from a record shorter than
     * the header, yields its key with a {@code null} value. Maps are returned in record order.
     *
     * <p>For the sample {@code contacts.csv} the result is
     * <pre>{@code
     * [{FirstName=John, LastName=Doe, Email=john.doe@texasComp.com, Phone=096548763},
     *  {FirstName=Jane, LastName=Doe, Email=jane.doe@texasComp.com, Phone=091558780}]
     * }</pre>
     *
     * @param csvContent CSV text whose first record is the header row
     * @return a new mutable list with one map per data record, keyed {@code FirstName}, {@code LastName},
     *     {@code Email} and {@code Phone}; empty, never {@code null}, for empty or header-only input
     * @throws UncheckedIOException if the CSV text cannot be read, for example an unterminated quoted
     *     value in the header row
     * @throws IllegalArgumentException if the header row is rejected by the CSV reader
     */
    public List<Map<String, Object>> toContact(String csvContent) {
        List<Map<String, Object>> contacts = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(csvContent, FORMAT)) {
            for (CSVRecord row : parser) {
                Map<String, Object> contact = new LinkedHashMap<>();
                contact.put(FIRST_NAME_FIELD, value(row, FIRST_NAME_COLUMN));
                contact.put(LAST_NAME_FIELD, value(row, SURNAME_COLUMN));
                contact.put(EMAIL_FIELD, value(row, EMAIL_COLUMN));
                contact.put(PHONE_FIELD, value(row, PHONE_COLUMN));
                contacts.add(contact);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return contacts;
    }

    /**
     * Reads one cell of a record by column name.
     *
     * @param row the parsed CSV record
     * @param header the column name
     * @return the cell unchanged, or {@code null} when the header lacks the column or the record is shorter
     *     than the header
     */
    private static String value(CSVRecord row, String header) {
        return row.isMapped(header) && row.isSet(header) ? row.get(header) : null;
    }
}
