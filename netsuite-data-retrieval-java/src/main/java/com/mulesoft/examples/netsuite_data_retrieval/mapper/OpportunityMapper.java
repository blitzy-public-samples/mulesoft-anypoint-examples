package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType;
import org.springframework.stereotype.Component;

/**
 * Writes NetSuite REST opportunity instances as the JSON array that the DW-22 setter
 * {@code payload map $} [netsuite-data-retrieval/src/main/app/netsuite-api.xml:99-103] produces in the
 * flow {@code get:/oportunities}, which answers {@code GET /api/opportunities} (D-068). The array holds
 * one object per opportunity, in list order, in the SOAP {@code Opportunity} shape of the committed
 * response example {@code api/opportunities-response.json} (AAP 0.6.5, D-016).
 *
 * <p>Input: the bodies of
 * {@code GET /services/rest/record/v1/opportunity/{id}?expandSubResources=true}, one per opportunity.
 * Their {@code shippingaddress} and {@code billingaddress} members, when present, are address
 * subrecord objects (FB-NS-03).
 *
 * <p>Output, per opportunity:
 *
 * <ul>
 *   <li>the keys of {@link #LAYOUT}, in its order, each rendered by
 *       {@link NetsuiteValueMapper#writeRecord} with its SOAP type (D-294);</li>
 *   <li>a key whose REST value is absent or JSON {@code null} is omitted, and no value is
 *       invented (FB-NS-02);</li>
 *   <li>{@code shippingAddress} and {@code billingAddress}: omitted when the subrecord is absent;
 *       otherwise the 15 Address members of the example in its order, with {@code country} as the
 *       SOAP country name ({@code US} gives {@code UNITED_STATES}), {@code addrText} lines joined
 *       with {@code \n<br>}, and {@code internalId} from the subrecord {@code id} (FB-NS-03);</li>
 *   <li>{@code status}: the {@code refName} of the REST {@code {id, refName}} object, as a
 *       string;</li>
 *   <li>numbers: whole values without a fraction ({@code estGrossProfitPercent} {@code 100},
 *       {@code exchangeRate} {@code 1}), other values as the shortest plain decimal
 *       ({@code projectedTotal} {@code 3875.85});</li>
 *   <li>dateTime keys: rendered by {@link NetsuiteValueMapper#formatDateTime(String)} in the zone
 *       of the given {@link NetsuiteValueMapper}, e.g. {@code 2014-12-05T00:00:00-08:00}.</li>
 * </ul>
 *
 * <p>The document is UTF-8 in the {@link DwJsonLayout} layout: two-space indentation, {@code \n}
 * line feeds and {@code ": "} between a member name and its value, with no line feed after the
 * closing {@code ]} (D-180). An empty list gives {@code []}. Control characters inside string values,
 * the line feeds of {@code addrText} included, are written as JSON escapes such as {@code \n}; the
 * committed example holds raw line feeds there instead (D-045).
 *
 * <p>The mapper holds no mutable state and writes no log entries; one instance serves concurrent
 * callers.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * OpportunityMapper mapper =
 *         new OpportunityMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));
 * byte[] body = mapper.toJson(List.of(restOpportunity));
 * }</pre>
 */
@Component
public class OpportunityMapper {

    /**
     * The 29 SOAP {@code Opportunity} keys in the order of the element of
     * {@code api/opportunities-response.json}, each mapped to its SOAP type (AAP 0.6.5):
     *
     * <ul>
     *   <li>{@link FieldType#RECORD_REF} (8): {@code salesRep}, {@code currency}, {@code leadSource},
     *       {@code forecastType}, {@code subsidiary}, {@code entityStatus}, {@code location},
     *       {@code entity};</li>
     *   <li>{@link FieldType#DATE_TIME} (4): {@code expectedCloseDate}, {@code lastModifiedDate},
     *       {@code createdDate}, {@code tranDate};</li>
     *   <li>{@link FieldType#ADDRESS} (2): {@code shippingAddress}, {@code billingAddress}
     *       (FB-NS-03);</li>
     *   <li>{@link FieldType#LONG} (1): {@code daysOpen};</li>
     *   <li>{@link FieldType#DOUBLE} (7): {@code estGrossProfitPercent}, {@code projectedTotal},
     *       {@code exchangeRate}, {@code weightedTotal}, {@code totalCostEstimate},
     *       {@code probability}, {@code estGrossProfit};</li>
     *   <li>{@link FieldType#BOOLEAN} (2): {@code shipIsResidential}, {@code isBudgetApproved};</li>
     *   <li>{@link FieldType#STRING} (5): {@code tranId}, {@code title}, {@code internalId},
     *       {@code currencyName}, {@code status}.</li>
     * </ul>
     *
     * <p>The map is unmodifiable and iterates in insertion order.
     */
    public static final Map<String, NetsuiteValueMapper.FieldType> LAYOUT = layout();

    /** Factory of the generators that {@link #toJson(List)} writes through. */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Renders each REST field value in its SOAP shape. */
    private final NetsuiteValueMapper values;

    /**
     * Creates a mapper that renders each opportunity field through {@code values}.
     *
     * @param values the NetSuite REST-to-SOAP value mapper
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public OpportunityMapper(NetsuiteValueMapper values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    /**
     * Writes the REST opportunity instances as one JSON array of SOAP-shaped {@code Opportunity}
     * objects, one element per instance in list order (DW-22 netsuite-api.xml:99, D-016).
     *
     * <p>The input list and its elements are only read.
     *
     * @param restOpportunities the REST opportunity instance bodies, each a JSON object
     * @return the UTF-8 bytes of the JSON array; {@code []} for an empty list
     * @throws NullPointerException     if {@code restOpportunities} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or not a JSON object
     * @throws UncheckedIOException     wrapping the {@link IOException} of a generator that cannot
     *                                  write
     */
    public byte[] toJson(List<JsonNode> restOpportunities) {
        Objects.requireNonNull(restOpportunities, "restOpportunities");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            g.setPrettyPrinter(new DwJsonLayout());
            g.writeStartArray();
            for (JsonNode opp : restOpportunities) {
                values.writeRecord(g, opp, LAYOUT);
            }
            g.writeEndArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * Builds {@link #LAYOUT}: the example keys in example order with their SOAP types.
     *
     * @return the unmodifiable, insertion-ordered layout
     */
    private static Map<String, NetsuiteValueMapper.FieldType> layout() {
        Map<String, NetsuiteValueMapper.FieldType> layout = new LinkedHashMap<>();
        layout.put("tranId", FieldType.STRING);
        layout.put("estGrossProfitPercent", FieldType.DOUBLE);
        layout.put("salesRep", FieldType.RECORD_REF);
        layout.put("projectedTotal", FieldType.DOUBLE);
        layout.put("title", FieldType.STRING);
        layout.put("expectedCloseDate", FieldType.DATE_TIME);
        layout.put("internalId", FieldType.STRING);
        layout.put("currencyName", FieldType.STRING);
        layout.put("exchangeRate", FieldType.DOUBLE);
        layout.put("weightedTotal", FieldType.DOUBLE);
        layout.put("currency", FieldType.RECORD_REF);
        layout.put("totalCostEstimate", FieldType.DOUBLE);
        layout.put("lastModifiedDate", FieldType.DATE_TIME);
        layout.put("leadSource", FieldType.RECORD_REF);
        layout.put("probability", FieldType.DOUBLE);
        layout.put("daysOpen", FieldType.LONG);
        layout.put("forecastType", FieldType.RECORD_REF);
        layout.put("subsidiary", FieldType.RECORD_REF);
        layout.put("createdDate", FieldType.DATE_TIME);
        layout.put("entityStatus", FieldType.RECORD_REF);
        layout.put("estGrossProfit", FieldType.DOUBLE);
        layout.put("shippingAddress", FieldType.ADDRESS);
        layout.put("tranDate", FieldType.DATE_TIME);
        layout.put("location", FieldType.RECORD_REF);
        layout.put("billingAddress", FieldType.ADDRESS);
        layout.put("shipIsResidential", FieldType.BOOLEAN);
        layout.put("isBudgetApproved", FieldType.BOOLEAN);
        layout.put("entity", FieldType.RECORD_REF);
        layout.put("status", FieldType.STRING);
        return Collections.unmodifiableMap(layout);
    }
}
