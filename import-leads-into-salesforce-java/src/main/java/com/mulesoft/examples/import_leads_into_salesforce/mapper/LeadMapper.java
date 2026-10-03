package com.mulesoft.examples.import_leads_into_salesforce.mapper;

import java.io.IOException;
import java.io.Reader;
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
 * Maps the lead CSV file read by the {@code batch:input} of batch job {@code CreateLeadsBatch} to Lead
 * records, re-implementing DataWeave setter DW-11
 * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:13-21] in Java (D-034, D-278).
 *
 * <p>The class holds no state and has no side effects; one instance serves any number of concurrent calls.
 */
@Component
public class LeadMapper {

    /**
     * CSV reader settings: separator {@code ,}, quote {@code "}, escape {@code \}, header names read from the
     * first record and matched case-sensitively, the header record not returned as data, empty lines ignored,
     * values and header names not trimmed; CR, LF and CRLF each end a record (D-278).
     */
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDelimiter(',')
            .setQuote('"')
            .setEscape('\\')
            .setIgnoreEmptyLines(true)
            .setTrim(false)
            .setIgnoreSurroundingSpaces(false)
            .get();

    /** Output keys of every Lead record, in output order: the field order of the DW-11 object constructor. */
    private static final List<String> FIELDS = List.of("Company", "Email", "FirstName", "LastName");

    /**
     * Maps every data record of a lead CSV document to one Lead record.
     *
     * <p>The first record of the CSV is its header record. Each Lead record is a new {@link LinkedHashMap}
     * holding exactly these four keys, in this order:
     * <ol>
     *   <li>{@code Company}: the value of column {@code Company}</li>
     *   <li>{@code Email}: the value of column {@code Email}</li>
     *   <li>{@code FirstName}: the value of column {@code FirstName}</li>
     *   <li>{@code LastName}: the value of column {@code LastName}</li>
     * </ol>
     *
     * <p>Values are the parsed {@link String}s unchanged: no trimming and no type conversion, and an empty
     * value stays {@code ""}. A column absent from the input, either missing from the header or missing
     * from a record shorter than the header, yields its key with a {@code null} value. Other columns, such
     * as {@code Birthday}, are not included. Records are returned in file order.
     *
     * <p>For the sample {@code leads.csv} the result is
     * <pre>{@code
     * [{Company=Aliquam Tincidunt Nunc LLC, Email=faucibus@egetmetus.org, FirstName=Ishmael, LastName=Alexander},
     *  {Company=Eu Odio PC, Email=sem.egestas@mollis.org, FirstName=Cole, LastName=Burks}]
     * }</pre>
     *
     * <p>The reader is closed when this method returns or throws.
     *
     * @param csv the CSV document, closed by this method
     * @return a new mutable list with one map per data record, keyed {@code Company}, {@code Email},
     *     {@code FirstName} and {@code LastName}; empty, never {@code null}, for an empty or header-only
     *     document
     * @throws IOException when the CSV cannot be read or parsed, for example an unterminated quoted value
     *     (commons-csv {@code CSVException})
     * @throws IllegalArgumentException when the CSV reader rejects the header record, for example an empty
     *     header name
     */
    public List<Map<String, Object>> toLead(Reader csv) throws IOException {
        List<Map<String, Object>> leads = new ArrayList<>();
        // The reader is its own resource: it is closed even when the header record fails to parse (D-278).
        try (Reader in = csv; CSVParser parser = FORMAT.parse(in)) {
            for (CSVRecord record : parser) {
                leads.add(toLeadRecord(record));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return leads;
    }

    /**
     * Builds one Lead record from one CSV data record.
     *
     * @param record the parsed CSV data record
     * @return a new map holding the four output keys in order, each with its column value or {@code null}
     */
    private static Map<String, Object> toLeadRecord(CSVRecord record) {
        Map<String, Object> lead = new LinkedHashMap<>();
        for (String name : FIELDS) {
            lead.put(name, record.isSet(name) ? record.get(name) : null);
        }
        return lead;
    }
}
