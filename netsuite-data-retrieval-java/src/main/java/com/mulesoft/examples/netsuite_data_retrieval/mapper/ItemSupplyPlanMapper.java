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
import org.springframework.stereotype.Component;

/**
 * Writes NetSuite REST item supply plan records as the JSON array of the DW-20 setter
 * {@code %output application/json --- payload map $}
 * [DW-20 netsuite-api.xml:70, netsuite-data-retrieval/src/main/app/netsuite-api.xml:70-73], in the
 * shape of the committed response example {@code api/items-response.json}
 * [netsuite-data-retrieval/src/main/api/items-response.json] (AAP 0.6.5, D-016).
 *
 * <p>The input is the list of plan bodies that
 * {@code GET /services/rest/record/v1/itemsupplyplan/{id}?expandSubResources=true} returns, after
 * {@code ItemSupplyPlanQuantityFilter} has selected them. The output holds one JSON object per input
 * plan, in input order, with the keys of {@link #LAYOUT} in this order:
 *
 * <ul>
 *   <li>{@code internalId}: the REST {@code id} of the plan, as a JSON string;</li>
 *   <li>{@code item}, {@code location}, {@code units} and {@code subsidiary}: each REST reference
 *       {@code {links, refName, id}} as the RecordRef object
 *       {@code {"externalId": null, "type": null, "internalId": <id>, "name": <refName>}}.</li>
 * </ul>
 *
 * <p>A key whose REST value is absent or JSON {@code null} is omitted by
 * {@link NetsuiteValueMapper#writeRecord}: a plan without units has no {@code units} key, as the
 * first element of the example shows. No {@code quantity} key is written, and neither the expanded
 * {@code order.items} sublist nor any other REST key reaches the output (AAP 0.6.5).
 *
 * <p>The bytes are UTF-8 in the {@link DwJsonLayout} layout of the example: two-space indentation,
 * {@code \n} line feeds, {@code ": "} between a member name and its value, and no line feed after
 * the closing {@code ]}. An empty input list gives the two bytes {@code []}.
 *
 * <p>Example: the two plans below give the 929 bytes of {@code api/items-response.json}.
 *
 * <pre>{@code
 * [{"id": "9",  "item": {"id": "702", "refName": "CUS00001"},
 *   "location": {"id": "1", "refName": "02: Boston"},
 *   "subsidiary": {"id": "1", "refName": "Honeycomb Mfg."},
 *   "order": {"items": [{"quantity": 3}]}},
 *  {"id": "18", "item": {"id": "721", "refName": "SHT00001"},
 *   "location": {"id": "1", "refName": "02: Boston"},
 *   "units": {"id": "15", "refName": "Square Feet"},
 *   "subsidiary": {"id": "1", "refName": "Honeycomb Mfg."},
 *   "order": {"items": [{"quantity": 5}, {"quantity": 12}]}}]
 * }</pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ItemSupplyPlanMapper mapper =
 *         new ItemSupplyPlanMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));
 * byte[] body = mapper.toJson(matchingPlans);
 * }</pre>
 *
 * <p>The class holds no mutable state; each call writes through its own generator and printer, and
 * one instance serves concurrent callers.
 */
@Component
public class ItemSupplyPlanMapper {

    /**
     * Output keys of one item supply plan, in the order of the second element of
     * {@code api/items-response.json}, each with its SOAP type (AAP 0.6.5): {@code internalId}
     * {@link NetsuiteValueMapper.FieldType#STRING}, then {@code item}, {@code location},
     * {@code units} and {@code subsidiary} {@link NetsuiteValueMapper.FieldType#RECORD_REF}.
     * Unmodifiable; iterates in insertion order.
     */
    public static final Map<String, NetsuiteValueMapper.FieldType> LAYOUT;

    static {
        Map<String, NetsuiteValueMapper.FieldType> layout = new LinkedHashMap<>();
        layout.put("internalId", NetsuiteValueMapper.FieldType.STRING);
        layout.put("item", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("location", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("units", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("subsidiary", NetsuiteValueMapper.FieldType.RECORD_REF);
        LAYOUT = Collections.unmodifiableMap(layout);
    }

    /** Factory of the UTF-8 generators that write each response; thread-safe and shared. */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Value mapper that writes each plan as one object with the keys and types of {@link #LAYOUT}. */
    private final NetsuiteValueMapper values;

    /**
     * Creates a mapper that writes each plan through the given value mapper.
     *
     * <p>The output does not depend on the time zone of {@code values}: {@link #LAYOUT} holds no
     * dateTime key.
     *
     * @param values the value mapper that renders each plan's keys
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public ItemSupplyPlanMapper(NetsuiteValueMapper values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    /**
     * Writes the plans as one JSON array, one object per plan in list order, with the keys of
     * {@link #LAYOUT} (DW-20 netsuite-api.xml:70, AAP 0.6.5, D-016).
     *
     * <p>The plans are neither filtered nor reordered, and the list and its nodes are not modified.
     *
     * @param restPlans the REST item supply plan bodies, in output order; may be empty
     * @return the UTF-8 bytes of the array in the {@link DwJsonLayout} layout; {@code []} for an
     *         empty list
     * @throws NullPointerException     if {@code restPlans} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or not a JSON object
     * @throws UncheckedIOException     if the generator cannot write
     */
    public byte[] toJson(List<JsonNode> restPlans) {
        Objects.requireNonNull(restPlans, "restPlans");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            g.setPrettyPrinter(new DwJsonLayout());
            g.writeStartArray();
            for (JsonNode plan : restPlans) {
                values.writeRecord(g, plan, LAYOUT);
            }
            g.writeEndArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the item supply plan JSON", e);
        }
        return out.toByteArray();
    }
}
