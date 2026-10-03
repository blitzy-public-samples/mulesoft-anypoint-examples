package com.mulesoft.examples.dataweave_with_flowreflookup.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Maps companies CSV text to Salesforce Account field maps: DW-03
 * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:9-19] of
 * {@code CreateNewSalesforceAccountFlow}, re-implemented by hand (D-034).
 *
 * <p>The CSV text is read as comma-separated records with {@code "} quotes and {@code \} as the
 * escape character. The first record is the header and names the columns; empty lines are skipped;
 * values are kept exactly as written, with no trimming and no type conversion; CR, LF and CRLF all
 * end a record (D-181).
 *
 * <p>Each company record becomes one Account map, in file order. The sales region of a record comes
 * from the {@code LookUpSalesRegionFlow} function the caller passes, which receives the whole record.
 * For the committed {@code companies.csv} and the {@code SalesRegionLookup} regions the result is:
 *
 * <pre>{@code
 * [{Name=Universal Exports, BillingStreet=55 Main St, BillingCity=Miami, BillingState=FL,
 *   BillingPostalCode=33126, Region__c=South East},
 *  {Name=Best Widgets, BillingStreet=1966 Latrobe Rd, BillingCity=El Dorado Hills, BillingState=CA,
 *   BillingPostalCode=95762, Region__c=West Coast}]
 * }</pre>
 *
 * <p>Instances hold no state; {@link #toAccount(String, Function)} has no side effects of its own and
 * is safe for concurrent use.
 */
@Component
public class AccountMapper {

    /**
     * Comma separator, {@code "} quote, {@code \} escape, first record as header, empty lines
     * ignored, values untrimmed, missing column names allowed (D-181).
     */
    private static final CSVFormat COMPANIES_CSV = CSVFormat.DEFAULT.builder()
            .setDelimiter(',')
            .setQuote('"')
            .setEscape('\\')
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setAllowMissingColumnNames(true)
            .setTrim(false)
            .setIgnoreSurroundingSpaces(false)
            .get();

    /**
     * Maps every company record of the CSV text to a Salesforce Account field map.
     *
     * <p>The input columns read are {@code company_name}, {@code company_address},
     * {@code company_city}, {@code company_state} and {@code company_zip}; any other column, such as
     * {@code has_given_contact_permission}, is not mapped. Each record gives one
     * {@link LinkedHashMap} with exactly these six keys, in this order:
     *
     * <ol>
     *   <li>{@code Name}: {@code company_name};</li>
     *   <li>{@code BillingStreet}: {@code company_address};</li>
     *   <li>{@code BillingCity}: {@code company_city};</li>
     *   <li>{@code BillingState}: {@code company_state} upper-cased with
     *       {@link Locale#getDefault()} (D-181), or {@code null} when the record has no
     *       {@code company_state} value;</li>
     *   <li>{@code BillingPostalCode}: {@code company_zip};</li>
     *   <li>{@code Region__c}: the {@code region} entry of the map that
     *       {@code lookUpSalesRegionFlow} returns for the record, or {@code null} when the function
     *       returns {@code null}.</li>
     * </ol>
     *
     * <p>A column absent from the header, or from a record shorter than the header, maps to
     * {@code null}. An empty value stays the empty string. The function is called exactly once per
     * record, in file order, while that record is mapped; its argument is the whole record as a
     * {@link LinkedHashMap} from column name to raw value, header order kept and
     * {@code company_state} not upper-cased.
     *
     * <p>A {@code null} or empty {@code csv}, or a header without records, gives an empty list; the
     * function is then not called.
     *
     * @param csv the companies CSV text, header line first; may be {@code null}
     * @param lookUpSalesRegionFlow the {@code LookUpSalesRegionFlow} lookup
     *     [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:26-54], from the
     *     whole record to a map holding its {@code region}
     * @return a mutable list holding one Account map per record, in record order
     * @throws NullPointerException if {@code lookUpSalesRegionFlow} is {@code null}
     * @throws UncheckedIOException if the CSV text cannot be parsed, for example an unterminated
     *     quoted value
     */
    public List<Map<String, Object>> toAccount(String csv,
            Function<Map<String, String>, Map<String, String>> lookUpSalesRegionFlow) {
        Objects.requireNonNull(lookUpSalesRegionFlow, "lookUpSalesRegionFlow");
        List<Map<String, Object>> accounts = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return accounts;
        }
        try (CSVParser parser = CSVParser.parse(new StringReader(csv), COMPANIES_CSV)) {
            for (CSVRecord csvRecord : parser) {
                accounts.add(account(new LinkedHashMap<>(csvRecord.toMap()), lookUpSalesRegionFlow));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return accounts;
    }

    /**
     * Builds the Account map of one company record and calls the region lookup once for it.
     *
     * @param company the record, from column name to raw value
     * @param lookUpSalesRegionFlow the region lookup
     * @return the six-key Account map
     */
    private static Map<String, Object> account(Map<String, String> company,
            Function<Map<String, String>, Map<String, String>> lookUpSalesRegionFlow) {
        String state = company.get("company_state");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("Name", company.get("company_name"));
        account.put("BillingStreet", company.get("company_address"));
        account.put("BillingCity", company.get("company_city"));
        account.put("BillingState", state == null ? null : state.toUpperCase(Locale.getDefault()));
        account.put("BillingPostalCode", company.get("company_zip"));
        Map<String, String> lookup = lookUpSalesRegionFlow.apply(company);
        account.put("Region__c", lookup == null ? null : lookup.get("region"));
        return account;
    }
}
