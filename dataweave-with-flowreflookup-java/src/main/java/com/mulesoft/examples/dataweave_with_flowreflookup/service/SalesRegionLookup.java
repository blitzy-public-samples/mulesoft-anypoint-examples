package com.mulesoft.examples.dataweave_with_flowreflookup.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Looks up the sales region of one company record from its {@code company_state} value.
 *
 * <p>{@link #lookUpSalesRegionFlow(Map)} implements the flow {@code LookUpSalesRegionFlow}: the
 * script component "Get Region by State" (script SC-02, re-implemented in Java, D-034) followed by
 * the logger "Log the Region". {@code AccountImportService} passes the method as the region lookup
 * of {@code AccountMapper}, which writes the returned {@code region} entry to {@code Region__c}.
 *
 * <p>Region lists, tested in this order; the first list holding the upper-cased state gives the
 * region:
 * <ul>
 *   <li>{@code North East}: CT, ME, MA, NH, VT, RI, NY, NJ, DE, DC, MD, NH</li>
 *   <li>{@code South East}: AL, AR, FL, GA, LA, SC, NC, TN, TX</li>
 *   <li>{@code Mid West}: ID, IL, IA, KS, MT, WY, ND, SD, OH</li>
 *   <li>{@code South West}: AZ, CO, OK, NM, NV</li>
 *   <li>{@code West Coast}: CA, HI, WA, OR, AK</li>
 * </ul>
 * A missing state, or a state in none of the lists, gives {@code UNKNOWN}.
 *
 * <pre>{@code
 * SalesRegionLookup lookup = new SalesRegionLookup();
 * lookup.lookUpSalesRegionFlow(Map.of("company_state", "fl")); // {region=South East}
 * lookup.lookUpSalesRegionFlow(Map.of("company_state", "CA")); // {region=West Coast}
 * lookup.lookUpSalesRegionFlow(Map.of("company_state", "ZZ")); // {region=UNKNOWN}
 * lookup.lookUpSalesRegionFlow(Map.of());                      // {region=UNKNOWN}
 * }</pre>
 *
 * <p>The class holds no state other than its constants and is safe for concurrent use.
 */
@Component
public class SalesRegionLookup {

    /** Category of the {@code State to lookup is: <state>} line of "Get Region by State" (D-186). */
    private static final Logger SCRIPT_LOG =
            LoggerFactory.getLogger("com.mulesoft.examples.dataweave_with_flowreflookup.script.GetRegionByState");

    /** Category of the {@code Region is : <region>} line of "Log the Region". */
    private static final Logger LOG = LoggerFactory.getLogger(SalesRegionLookup.class);

    /** Input key holding the company's state code. */
    private static final String STATE_KEY = "company_state";

    /** Output key holding the region name. */
    private static final String REGION_KEY = "region";

    /** Region of a missing state or of a state in none of the lists. */
    private static final String UNKNOWN = "UNKNOWN";

    private static final String NORTH_EAST_REGION = "North East";
    private static final String SOUTH_EAST_REGION = "South East";
    private static final String MID_WEST_REGION = "Mid West";
    private static final String SOUTH_WEST_REGION = "South West";
    private static final String WEST_COAST_REGION = "West Coast";

    /** State codes of {@code North East}, in source order, {@code NH} listed twice (D-188). */
    private static final List<String> NORTH_EAST =
            List.of("CT", "ME", "MA", "NH", "VT", "RI", "NY", "NJ", "DE", "DC", "MD", "NH");

    /** State codes of {@code South East}, in source order. */
    private static final List<String> SOUTH_EAST =
            List.of("AL", "AR", "FL", "GA", "LA", "SC", "NC", "TN", "TX");

    /** State codes of {@code Mid West}, in source order. */
    private static final List<String> MID_WEST =
            List.of("ID", "IL", "IA", "KS", "MT", "WY", "ND", "SD", "OH");

    /** State codes of {@code South West}, in source order. */
    private static final List<String> SOUTH_WEST = List.of("AZ", "CO", "OK", "NM", "NV");

    /** State codes of {@code West Coast}, in source order. */
    private static final List<String> WEST_COAST = List.of("CA", "HI", "WA", "OR", "AK");

    /**
     * Returns the sales region of a company record.
     *
     * <ol>
     *   <li>Reads {@code company_state} from the record and, when present, upper-cases it with the
     *       default locale (D-187).</li>
     *   <li>Logs {@code State to lookup is: <state>} at INFO on the category
     *       {@code com.mulesoft.examples.dataweave_with_flowreflookup.script.GetRegionByState}; a
     *       missing state logs {@code State to lookup is: null} (D-186).</li>
     *   <li>Compares the upper-cased state for exact equality with the codes of the North East,
     *       South East, Mid West, South West and West Coast lists, in that order. The first list
     *       holding it gives the region. The value is not trimmed: an empty state, {@code " FL"}
     *       and an unlisted code give {@code UNKNOWN}, as does a missing state (D-188).</li>
     *   <li>Logs {@code Region is : <region>} at INFO on this class's logger.</li>
     * </ol>
     *
     * <p>The record is read only, never modified.
     *
     * @param payload one company record keyed by CSV column name, for example {@code company_name},
     *                {@code company_state} and {@code company_zip}; only {@code company_state} is read
     * @return a new immutable map whose only entry is {@code region}, holding one of
     *         {@code North East}, {@code South East}, {@code Mid West}, {@code South West},
     *         {@code West Coast} or {@code UNKNOWN}
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    Map<String, String> lookUpSalesRegionFlow(Map<String, String> payload) {
        String region = UNKNOWN;
        String state = payload.get(STATE_KEY);
        if (state != null) {
            state = state.toUpperCase(Locale.getDefault());
        }
        SCRIPT_LOG.info("State to lookup is: " + state);
        if (state != null) {
            if (NORTH_EAST.contains(state)) {
                region = NORTH_EAST_REGION;
            } else if (SOUTH_EAST.contains(state)) {
                region = SOUTH_EAST_REGION;
            } else if (MID_WEST.contains(state)) {
                region = MID_WEST_REGION;
            } else if (SOUTH_WEST.contains(state)) {
                region = SOUTH_WEST_REGION;
            } else if (WEST_COAST.contains(state)) {
                region = WEST_COAST_REGION;
            }
        }
        LOG.info("Region is : " + region);
        return Map.of(REGION_KEY, region);
    }
}
