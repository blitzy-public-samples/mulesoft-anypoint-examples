package com.mulesoft.examples.dataweave_with_flowreflookup.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link AccountMapper#toAccount(String, Function)}, the DataWeave setter DW-03
 * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:9-19] of
 * {@code CreateNewSalesforceAccountFlow}, re-implemented by hand (D-034):
 *
 * <pre>{@code
 * payload map {
 *   Name: $.company_name,
 *   BillingStreet: $.company_address,
 *   BillingCity: $.company_city,
 *   BillingState: upper $.company_state,
 *   BillingPostalCode: $.company_zip,
 *   Region__c: lookup("LookUpSalesRegionFlow", $).region
 * }
 * }</pre>
 *
 * <p>Each test calls the mapper directly, with no Spring application context, and passes a
 * {@link RecordingLookup} as the {@code LookUpSalesRegionFlow} function. The tests check the six
 * Account keys and their order, the values of each key, the whole record handed to the lookup, the
 * CSV reading of the mapper (quotes, the {@code \} escape, CRLF, empty values, missing and extra
 * columns, empty input) and the committed {@code companies.csv} sample. They run every line of the
 * mapper (D-049).
 */
class AccountMapperTest {

    /** Header line of the committed {@code companies.csv}, without its line end. */
    private static final String HEADER =
            "company_name,company_address,company_city,company_state,company_zip,has_given_contact_permission";

    /** Byte length of the committed {@code companies.csv}. */
    private static final int SAMPLE_LENGTH = 196;

    /** SHA-256 of the committed {@code companies.csv}, lower-case hex. */
    private static final String SAMPLE_SHA_256 =
            "c3e466fac6e24d1bbebb731040e6b7dceb10b6ead1e38880df7c05cf60cb20c6";

    private final AccountMapper mapper = new AccountMapper();

    // ---------------------------------------------------------------------------------------------
    // Fixture guard
    // ---------------------------------------------------------------------------------------------

    /** The classpath {@code /companies.csv} holds the original sample bytes, with no final newline. */
    @Test
    void classpathFixtureIsTheOriginalSample() throws Exception {
        byte[] bytes;
        try (InputStream in = AccountMapperTest.class.getResourceAsStream("/companies.csv")) {
            assertThat(in).as("classpath resource /companies.csv").isNotNull();
            bytes = in.readAllBytes();
        }

        assertThat(bytes).hasSize(SAMPLE_LENGTH);
        assertThat(bytes[bytes.length - 1]).as("last byte").isNotEqualTo((byte) '\n');
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(sha256).isEqualTo(SAMPLE_SHA_256);
    }

    // ---------------------------------------------------------------------------------------------
    // DW-03 mapping
    // ---------------------------------------------------------------------------------------------

    /** The committed sample gives two Account maps with the DW-03 values, keys in DW-03 order. */
    @Test
    void mapsTheClasspathSampleToTwoAccounts() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts = mapper.toAccount(classpathCompanies(), lookup);

        assertThat(accounts).hasSize(2);

        Map<String, Object> universalExports = new LinkedHashMap<>();
        universalExports.put("Name", "Universal Exports");
        universalExports.put("BillingStreet", "55 Main St");
        universalExports.put("BillingCity", "Miami");
        universalExports.put("BillingState", "FL");
        universalExports.put("BillingPostalCode", "33126");
        universalExports.put("Region__c", "South East");
        assertKeyOrder(accounts.get(0));
        assertThat(accounts.get(0)).isEqualTo(universalExports);

        Map<String, Object> bestWidgets = new LinkedHashMap<>();
        bestWidgets.put("Name", "Best Widgets");
        bestWidgets.put("BillingStreet", "1966 Latrobe Rd");
        bestWidgets.put("BillingCity", "El Dorado Hills");
        bestWidgets.put("BillingState", "CA");
        bestWidgets.put("BillingPostalCode", "95762");
        bestWidgets.put("Region__c", "West Coast");
        assertKeyOrder(accounts.get(1));
        assertThat(accounts.get(1)).isEqualTo(bestWidgets);
    }

    /**
     * The lookup runs once per record, in file order, and receives the whole raw record: every
     * column in header order, {@code company_state} not upper-cased.
     */
    @Test
    void callsTheLookupOncePerRecordInOrderWithTheFullRecord() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        mapper.toAccount(classpathCompanies(), lookup);

        assertThat(lookup.calls).hasSize(2);

        Map<String, String> universalExports = new LinkedHashMap<>();
        universalExports.put("company_name", "Universal Exports");
        universalExports.put("company_address", "55 Main St");
        universalExports.put("company_city", "Miami");
        universalExports.put("company_state", "fl");
        universalExports.put("company_zip", "33126");
        universalExports.put("has_given_contact_permission", "");
        assertThat(lookup.calls.get(0)).isEqualTo(universalExports);
        assertThat(lookup.calls.get(0)).containsKey("has_given_contact_permission");
        assertThat(lookup.calls.get(0).keySet()).containsExactly(
                "company_name", "company_address", "company_city", "company_state", "company_zip",
                "has_given_contact_permission");

        Map<String, String> bestWidgets = new LinkedHashMap<>();
        bestWidgets.put("company_name", "Best Widgets");
        bestWidgets.put("company_address", "1966 Latrobe Rd");
        bestWidgets.put("company_city", "El Dorado Hills");
        bestWidgets.put("company_state", "CA");
        bestWidgets.put("company_zip", "95762");
        bestWidgets.put("has_given_contact_permission", "");
        assertThat(lookup.calls.get(1)).isEqualTo(bestWidgets);
    }

    /** {@code BillingState} is the upper-cased state; the lookup receives the state as written. */
    @Test
    void upperCasesTheBillingState() throws Exception {
        RecordingLookup lowerCaseLookup = new RecordingLookup();
        List<Map<String, Object>> lowerCase =
                mapper.toAccount(HEADER + "\nAcme,1 Elm St,Miami,fl,33126,", lowerCaseLookup);

        assertThat(lowerCase).hasSize(1);
        assertThat(lowerCase.get(0)).containsEntry("BillingState", "FL");
        assertThat(lowerCaseLookup.calls).hasSize(1);
        assertThat(lowerCaseLookup.calls.get(0)).containsEntry("company_state", "fl");

        RecordingLookup mixedCaseLookup = new RecordingLookup();
        List<Map<String, Object>> mixedCase =
                mapper.toAccount(HEADER + "\nAcme,1 Elm St,Fresno,Ca,93650,", mixedCaseLookup);

        assertThat(mixedCase).hasSize(1);
        assertThat(mixedCase.get(0)).containsEntry("BillingState", "CA");
        assertThat(mixedCaseLookup.calls).hasSize(1);
        assertThat(mixedCaseLookup.calls.get(0)).containsEntry("company_state", "Ca");
    }

    /**
     * A header without {@code company_state} keeps the {@code BillingState} key with a {@code null}
     * value, and the lookup still runs for the record.
     */
    @Test
    void missingStateColumnGivesNullBillingStateAndStillCallsTheLookup() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts = mapper.toAccount(
                "company_name,company_address,company_city,company_zip\nAcme,1 Elm St,Springfield,12345",
                lookup);

        assertThat(accounts).hasSize(1);
        Map<String, Object> account = accounts.get(0);
        assertKeyOrder(account);
        assertThat(account).containsEntry("BillingState", null);
        assertThat(account).containsEntry("Name", "Acme");
        assertThat(account).containsEntry("BillingPostalCode", "12345");
        assertThat(account).containsEntry("Region__c", "UNKNOWN");

        assertThat(lookup.calls).hasSize(1);
        assertThat(lookup.calls.get(0).get("company_state")).isNull();
    }

    /** Empty CSV values map to the empty string, never to {@code null}. */
    @Test
    void emptyValuesStayEmptyStrings() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts = mapper.toAccount(HEADER + "\nAcme,,Springfield,,,", lookup);

        assertThat(accounts).hasSize(1);
        Map<String, Object> account = accounts.get(0);
        assertKeyOrder(account);
        assertThat(account.get("BillingStreet")).isNotNull().isEqualTo("");
        assertThat(account.get("BillingState")).isNotNull().isEqualTo("");
        assertThat(account.get("BillingPostalCode")).isNotNull().isEqualTo("");
        assertThat(account).containsEntry("Name", "Acme");
        assertThat(account).containsEntry("BillingCity", "Springfield");

        assertThat(lookup.calls).hasSize(1);
        assertThat(lookup.calls.get(0)).containsEntry("company_state", "");
    }

    /** A quoted value holding a comma is one value, and the following columns keep their place. */
    @Test
    void quotedFieldWithCommaIsOneValue() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts =
                mapper.toAccount(HEADER + "\n\"Widgets, Inc.\",1 Way,Austin,TX,73301,", lookup);

        assertThat(accounts).hasSize(1);
        Map<String, Object> account = accounts.get(0);
        assertKeyOrder(account);
        assertThat(account).containsEntry("Name", "Widgets, Inc.");
        assertThat(account).containsEntry("BillingStreet", "1 Way");
        assertThat(account).containsEntry("BillingCity", "Austin");
        assertThat(account).containsEntry("BillingState", "TX");
        assertThat(account).containsEntry("BillingPostalCode", "73301");
    }

    /**
     * The mapper reads {@code \} as the escape character (D-181): the raw text
     * {@code 12 Oak St\, Unit 4} is the single value {@code 12 Oak St, Unit 4}.
     */
    @Test
    void backslashIsHandledAsTheMapperDeclares() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts =
                mapper.toAccount(HEADER + "\nAcme,12 Oak St\\, Unit 4,Miami,fl,33126,", lookup);

        assertThat(accounts).hasSize(1);
        Map<String, Object> account = accounts.get(0);
        assertKeyOrder(account);
        assertThat(account).containsEntry("BillingStreet", "12 Oak St, Unit 4");
        assertThat(account).containsEntry("BillingCity", "Miami");
        assertThat(account).containsEntry("BillingState", "FL");
        assertThat(account).containsEntry("BillingPostalCode", "33126");
    }

    /** CRLF line ends give the same accounts as LF, with no {@code \r} left in the last column. */
    @Test
    void crlfInputParsesLikeLf() throws Exception {
        String lf = classpathCompanies();
        List<Map<String, Object>> fromLf = mapper.toAccount(lf, new RecordingLookup());
        List<Map<String, Object>> fromCrlf = mapper.toAccount(lf.replace("\n", "\r\n"), new RecordingLookup());

        assertThat(fromLf).hasSize(2);
        assertThat(fromCrlf).isEqualTo(fromLf);

        RecordingLookup lookup = new RecordingLookup();
        List<Map<String, Object>> zipLast = mapper.toAccount(
                "company_name,company_address,company_city,company_state,company_zip\r\n"
                        + "Acme,1 Elm St,Boston,ny,02108\r\n",
                lookup);

        assertThat(zipLast).hasSize(1);
        assertThat(zipLast.get(0)).containsEntry("BillingPostalCode", "02108");
        assertThat((String) zipLast.get(0).get("BillingPostalCode")).doesNotContain("\r");
        assertThat(zipLast.get(0)).containsEntry("BillingState", "NY");
        assertThat(zipLast.get(0)).containsEntry("Region__c", "North East");
        assertThat(lookup.calls).hasSize(1);
        assertThat(lookup.calls.get(0)).containsEntry("company_zip", "02108");
    }

    /** Empty text and a header without records give an empty list and no lookup call. */
    @Test
    void emptyAndHeaderOnlyInputGiveEmptyLists() throws Exception {
        for (String csv : List.of("", HEADER, HEADER + "\n")) {
            RecordingLookup lookup = new RecordingLookup();

            List<Map<String, Object>> accounts = mapper.toAccount(csv, lookup);

            assertThat(accounts).as("accounts of %s", csv.replace("\n", "\\n")).isEmpty();
            assertThat(lookup.calls).as("lookup calls of %s", csv.replace("\n", "\\n")).isEmpty();
        }
    }

    /** Columns beyond the five read are not mapped; the lookup still receives them. */
    @Test
    void extraColumnsAreIgnored() throws Exception {
        RecordingLookup lookup = new RecordingLookup();

        List<Map<String, Object>> accounts = mapper.toAccount(
                HEADER + ",industry,rating\nAcme,1 Elm St,Springfield,ma,01101,,Manufacturing,5", lookup);

        assertThat(accounts).hasSize(1);
        Map<String, Object> account = accounts.get(0);
        assertKeyOrder(account);
        assertThat(account).doesNotContainKeys("industry", "rating", "has_given_contact_permission");
        assertThat(account).containsEntry("BillingState", "MA");
        assertThat(account).containsEntry("BillingPostalCode", "01101");

        assertThat(lookup.calls).hasSize(1);
        assertThat(lookup.calls.get(0)).containsEntry("industry", "Manufacturing");
        assertThat(lookup.calls.get(0)).containsEntry("rating", "5");
    }

    /**
     * An unterminated quoted value is a parse error, raised as {@link UncheckedIOException}, in the
     * header line and in a record line alike (D-181); the lookup is not called.
     */
    @Test
    void unterminatedQuotedValueThrowsUncheckedIOException() throws Exception {
        RecordingLookup headerLookup = new RecordingLookup();
        assertThatThrownBy(() -> mapper.toAccount("\"company_name,company_address\nAcme,1 Elm St", headerLookup))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(headerLookup.calls).isEmpty();

        RecordingLookup recordLookup = new RecordingLookup();
        assertThatThrownBy(() -> mapper.toAccount(HEADER + "\n\"Acme,1 Elm St,Miami,fl,33126,", recordLookup))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(recordLookup.calls).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads the classpath {@code /companies.csv} as UTF-8 text.
     *
     * @return the sample CSV text
     * @throws Exception if the resource cannot be read
     */
    private String classpathCompanies() throws Exception {
        try (InputStream in = AccountMapperTest.class.getResourceAsStream("/companies.csv")) {
            assertThat(in).as("classpath resource /companies.csv").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Asserts that an Account map is a {@link LinkedHashMap} holding exactly the six DW-03 keys in
     * DW-03 order.
     *
     * @param account the Account map under test
     */
    private void assertKeyOrder(Map<String, Object> account) {
        assertThat(account).isInstanceOf(LinkedHashMap.class);
        assertThat(account.keySet()).containsExactly(
                "Name", "BillingStreet", "BillingCity", "BillingState", "BillingPostalCode", "Region__c");
    }

    /**
     * {@code LookUpSalesRegionFlow} stand-in: records a copy of every record it receives and answers
     * {@code {region=...}} from the upper-cased {@code company_state}: {@code FL} gives
     * {@code South East}, {@code CA} gives {@code West Coast}, {@code NY} gives {@code North East},
     * and any other or missing state gives {@code UNKNOWN}.
     */
    private static final class RecordingLookup implements Function<Map<String, String>, Map<String, String>> {

        /** Copies of the records received, in call order. */
        final List<Map<String, String>> calls = new ArrayList<>();

        @Override
        public Map<String, String> apply(Map<String, String> record) {
            calls.add(new LinkedHashMap<>(record));
            String state = record.get("company_state");
            String region = state == null ? "UNKNOWN" : switch (state.toUpperCase(Locale.ROOT)) {
                case "FL" -> "South East";
                case "CA" -> "West Coast";
                case "NY" -> "North East";
                default -> "UNKNOWN";
            };
            return Map.of("region", region);
        }
    }
}
