package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link SalesRegionLookup#lookUpSalesRegionFlow(Map)}, the Java form of script SC-02 (D-034).
 *
 * <p>Each lookup is checked for its result, exactly {@code {region=<region>}}, for leaving the input record
 * unchanged, and for its two INFO events in order: {@code State to lookup is: <state>} on the category
 * {@code com.mulesoft.examples.dataweave_with_flowreflookup.script.GetRegionByState}, then
 * {@code Region is : <region>} on {@code com.mulesoft.examples.dataweave_with_flowreflookup.service.SalesRegionLookup}.
 * The tests cover {@code SalesRegionLookup} under the JaCoCo line rule (D-049) and use no Spring context.
 */
class SalesRegionLookupTest {

    /** Category of the {@code State to lookup is: <state>} event. */
    private static final String SCRIPT_CATEGORY =
            "com.mulesoft.examples.dataweave_with_flowreflookup.script.GetRegionByState";

    /** Category of the {@code Region is : <region>} event. */
    private static final String SERVICE_CATEGORY =
            "com.mulesoft.examples.dataweave_with_flowreflookup.service.SalesRegionLookup";

    /** The unit under test. */
    private final SalesRegionLookup lookup = new SalesRegionLookup();

    /** Collects the events of both categories in the order they are logged. */
    private ListAppender<ILoggingEvent> appender;

    /** Logger of {@link #SCRIPT_CATEGORY}. */
    private Logger scriptLogger;

    /** Logger of {@link #SERVICE_CATEGORY}. */
    private Logger serviceLogger;

    /** Level of {@link #scriptLogger} before the test, restored after it; {@code null} when inherited. */
    private Level previousScriptLevel;

    /** Level of {@link #serviceLogger} before the test, restored after it; {@code null} when inherited. */
    private Level previousServiceLevel;

    /** Starts one appender, attaches it to both loggers and sets both to {@code INFO}. */
    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        scriptLogger = (Logger) LoggerFactory.getLogger(SCRIPT_CATEGORY);
        serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_CATEGORY);
        previousScriptLevel = scriptLogger.getLevel();
        previousServiceLevel = serviceLogger.getLevel();
        scriptLogger.setLevel(Level.INFO);
        serviceLogger.setLevel(Level.INFO);
        scriptLogger.addAppender(appender);
        serviceLogger.addAppender(appender);
    }

    /** Detaches and stops the appender and restores both logger levels. */
    @AfterEach
    void detachAppender() {
        scriptLogger.detachAppender(appender);
        serviceLogger.detachAppender(appender);
        appender.stop();
        scriptLogger.setLevel(previousScriptLevel);
        serviceLogger.setLevel(previousServiceLevel);
    }

    /** Asserts {@code ct} is logged as {@code CT} and gives {@code North East}. */
    @Test
    @DisplayName("dataweave-with-flowreflookup_north-east")
    void northEast() {
        assertLookup(row("ct"), "CT", "North East");
    }

    /**
     * Asserts {@code fl}, the state of the {@code Universal Exports} row, is logged as {@code FL} and gives
     * {@code South East}.
     */
    @Test
    @DisplayName("dataweave-with-flowreflookup_south-east")
    void southEast() {
        assertLookup(row("fl"), "FL", "South East");
    }

    /** Asserts {@code OH} gives {@code Mid West}. */
    @Test
    @DisplayName("dataweave-with-flowreflookup_mid-west")
    void midWest() {
        assertLookup(row("OH"), "OH", "Mid West");
    }

    /** Asserts {@code NV} gives {@code South West}. */
    @Test
    @DisplayName("dataweave-with-flowreflookup_south-west")
    void southWest() {
        assertLookup(row("NV"), "NV", "South West");
    }

    /** Asserts {@code CA} gives {@code West Coast}. */
    @Test
    @DisplayName("dataweave-with-flowreflookup_west-coast")
    void westCoast() {
        assertLookup(row("CA"), "CA", "West Coast");
    }

    /**
     * Asserts {@code ZZ}, {@code " FL"} and an empty state each give {@code UNKNOWN} and are logged exactly as
     * given: {@code State to lookup is: ZZ}, {@code State to lookup is:  FL} with two spaces, and
     * {@code State to lookup is: } with one trailing space.
     */
    @Test
    @DisplayName("dataweave-with-flowreflookup_unlisted-state-unknown")
    void unlistedStateUnknown() {
        assertLookup(row("ZZ"), "ZZ", "UNKNOWN");
        assertLookup(row(" FL"), " FL", "UNKNOWN");
        assertLookup(row(""), "", "UNKNOWN");
    }

    /**
     * Asserts a record without a {@code company_state} key, and a record whose {@code company_state} is
     * {@code null}, each log {@code State to lookup is: null} and give {@code UNKNOWN}.
     */
    @Test
    @DisplayName("dataweave-with-flowreflookup_null-state-unknown")
    void nullStateUnknown() {
        Map<String, String> withoutState = company();
        assertThat(withoutState).doesNotContainKey("company_state");
        assertLookup(withoutState, "null", "UNKNOWN");

        Map<String, String> withNullState = row(null);
        assertThat(withNullState).containsEntry("company_state", null);
        assertLookup(withNullState, "null", "UNKNOWN");
    }

    /**
     * Asserts every listed state code, in upper case and in lower case, is logged in upper case and gives the
     * region of its list.
     *
     * @param code   the upper-case state code
     * @param region the region of the list holding {@code code}
     */
    @ParameterizedTest(name = "listed state {0} maps to {1}")
    @DisplayName("listed state codes map to their regions")
    @CsvSource({
        "CT, North East",
        "ME, North East",
        "MA, North East",
        "NH, North East",
        "VT, North East",
        "RI, North East",
        "NY, North East",
        "NJ, North East",
        "DE, North East",
        "DC, North East",
        "MD, North East",
        "AL, South East",
        "AR, South East",
        "FL, South East",
        "GA, South East",
        "LA, South East",
        "SC, South East",
        "NC, South East",
        "TN, South East",
        "TX, South East",
        "ID, Mid West",
        "IL, Mid West",
        "IA, Mid West",
        "KS, Mid West",
        "MT, Mid West",
        "WY, Mid West",
        "ND, Mid West",
        "SD, Mid West",
        "OH, Mid West",
        "AZ, South West",
        "CO, South West",
        "OK, South West",
        "NM, South West",
        "NV, South West",
        "CA, West Coast",
        "HI, West Coast",
        "WA, West Coast",
        "OR, West Coast",
        "AK, West Coast"
    })
    void everyListedStateCode(String code, String region) {
        assertLookup(row(code), code, region);
        assertLookup(row(code.toLowerCase(Locale.ROOT)), code, region);
    }

    /**
     * Returns a new mutable record holding the {@code Universal Exports} row of {@code companies.csv} with
     * {@code company_state} set to {@code state}.
     *
     * @param state the {@code company_state} value; {@code null} stores the key with a {@code null} value
     * @return the record
     */
    private static Map<String, String> row(String state) {
        Map<String, String> record = company();
        record.put("company_state", state);
        return record;
    }

    /**
     * Returns a new mutable record holding the {@code company_name}, {@code company_address},
     * {@code company_city}, {@code company_zip} and {@code has_given_contact_permission} values of the
     * {@code Universal Exports} row of {@code companies.csv}, without a {@code company_state} key.
     *
     * @return the record
     */
    private static Map<String, String> company() {
        Map<String, String> record = new HashMap<>();
        record.put("company_name", "Universal Exports");
        record.put("company_address", "55 Main St");
        record.put("company_city", "Miami");
        record.put("company_zip", "33126");
        record.put("has_given_contact_permission", "");
        return record;
    }

    /**
     * Clears the captured events, looks up {@code input} and asserts the result is exactly
     * {@code {region=<expectedRegion>}}, {@code input} is unchanged, and exactly two INFO events were logged:
     * {@code State to lookup is: <expectedLoggedState>} on {@link #SCRIPT_CATEGORY}, then
     * {@code Region is : <expectedRegion>} on {@link #SERVICE_CATEGORY}.
     *
     * @param input               the record passed to the lookup
     * @param expectedLoggedState the state text expected after {@code State to lookup is: }
     * @param expectedRegion      the expected {@code region} value
     */
    private void assertLookup(Map<String, String> input, String expectedLoggedState, String expectedRegion) {
        Map<String, String> before = new HashMap<>(input);
        appender.list.clear();

        Map<String, String> result = lookup.lookUpSalesRegionFlow(input);

        assertThat(result)
                .as("result of the lookup of %s", before)
                .hasSize(1)
                .isEqualTo(Map.of("region", expectedRegion));
        assertThat(input)
                .as("record after the lookup")
                .isEqualTo(before);
        assertThat(appender.list)
                .as("events logged by the lookup of %s", before)
                .extracting(ILoggingEvent::getLoggerName, ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(SCRIPT_CATEGORY, Level.INFO, "State to lookup is: " + expectedLoggedState),
                        tuple(SERVICE_CATEGORY, Level.INFO, "Region is : " + expectedRegion));
    }
}
