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
 * Writes the NetSuite REST customer instances of a {@code GET /api/customers} search as the JSON
 * array of SOAP {@code Customer} objects that the DW-18 setter {@code payload map $} produces
 * [DW-18 netsuite-api.xml:44, netsuite-data-retrieval/src/main/app/netsuite-api.xml:44-47]
 * (AAP 0.6.5, D-016).
 *
 * <p>Input: the bodies of {@code GET /services/rest/record/v1/customer/{id}}, in collection order.
 * Output: one JSON object per body, in the same order. Each object carries the 47 keys of
 * {@code api/customers-response.json}, the only response contract, in that file's order and with
 * the SOAP type of each key ({@link #LAYOUT}); {@link NetsuiteValueMapper#writeRecord} renders every
 * value. A key that a REST body does not resolve is omitted from that object, and no key outside
 * {@link #LAYOUT} is written (FB-NS-02). No record is filtered, reordered or merged.
 *
 * <p>Bytes: UTF-8 in the {@link DwJsonLayout} layout, two-space indentation, {@code \n} line feeds,
 * {@code ": "} between name and value, and no line feed after the closing {@code ]}. Non-ASCII
 * characters are written as their UTF-8 bytes, not as JSON Unicode escape sequences. An empty list
 * gives the two bytes {@code []} (D-180). The {@code companyName} member reads
 * {@code "companyName": "<name>"}, the text the MUnit check
 * [netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml:28] searches for.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * CustomerMapper mapper = new CustomerMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));
 * byte[] json = mapper.toJson(List.of(restCustomer));
 * // [
 * //   {
 * //     "lastName": "Wolfe",
 * //     ...
 * //     "defaultAddress": "US"
 * //   }
 * // ]
 * }</pre>
 *
 * <p>The class holds no mutable state. Each {@link #toJson} call writes through its own generator and
 * its own {@link DwJsonLayout} instance; one mapper serves concurrent callers.
 */
@Component
public class CustomerMapper {

    /**
     * The SOAP {@code Customer} keys of {@code api/customers-response.json} in that file's order, each
     * mapped to its SOAP type (AAP 0.6.5 key table): 5 {@code RECORD_REF}, 5 {@code DATE_TIME},
     * 15 {@code DOUBLE}, 1 {@code LONG}, 6 {@code BOOLEAN}, 1 {@code STAGE}, 1
     * {@code CREDIT_HOLD_OVERRIDE}, 1 {@code CUSTOM_FIELD_LIST} and 12 {@code STRING}, 47 keys in all.
     * The map is unmodifiable and iterates in example order.
     */
    public static final Map<String, NetsuiteValueMapper.FieldType> LAYOUT;

    static {
        Map<String, NetsuiteValueMapper.FieldType> layout = new LinkedHashMap<>();
        layout.put("lastName", NetsuiteValueMapper.FieldType.STRING);
        layout.put("isInactive", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("companyName", NetsuiteValueMapper.FieldType.STRING);
        layout.put("receivablesAccount", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("consolBalance", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("isPerson", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("lastPageVisited", NetsuiteValueMapper.FieldType.STRING);
        layout.put("consolAging2", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("consolAging3", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("internalId", NetsuiteValueMapper.FieldType.STRING);
        layout.put("visits", NetsuiteValueMapper.FieldType.LONG);
        layout.put("billPay", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("consolAging4", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("dateCreated", NetsuiteValueMapper.FieldType.DATE_TIME);
        layout.put("aging2", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("consolDepositBalance", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("aging3", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("accessRole", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("altEmail", NetsuiteValueMapper.FieldType.STRING);
        layout.put("consolAging1", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("aging1", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("unbilledOrders", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("consolUnbilledOrders", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("currency", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("aging4", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("consolOverdueBalance", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("firstVisit", NetsuiteValueMapper.FieldType.DATE_TIME);
        layout.put("email", NetsuiteValueMapper.FieldType.STRING);
        layout.put("giveAccess", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("customFieldList", NetsuiteValueMapper.FieldType.CUSTOM_FIELD_LIST);
        layout.put("taxable", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("lastModifiedDate", NetsuiteValueMapper.FieldType.DATE_TIME);
        layout.put("lastVisit", NetsuiteValueMapper.FieldType.DATE_TIME);
        layout.put("externalId", NetsuiteValueMapper.FieldType.STRING);
        layout.put("entityId", NetsuiteValueMapper.FieldType.STRING);
        layout.put("subsidiary", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("clickStream", NetsuiteValueMapper.FieldType.STRING);
        layout.put("shipComplete", NetsuiteValueMapper.FieldType.BOOLEAN);
        layout.put("firstName", NetsuiteValueMapper.FieldType.STRING);
        layout.put("stage", NetsuiteValueMapper.FieldType.STAGE);
        layout.put("creditHoldOverride", NetsuiteValueMapper.FieldType.CREDIT_HOLD_OVERRIDE);
        layout.put("phone", NetsuiteValueMapper.FieldType.STRING);
        layout.put("consolAging", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("entityStatus", NetsuiteValueMapper.FieldType.RECORD_REF);
        layout.put("aging", NetsuiteValueMapper.FieldType.DOUBLE);
        layout.put("startDate", NetsuiteValueMapper.FieldType.DATE_TIME);
        layout.put("defaultAddress", NetsuiteValueMapper.FieldType.STRING);
        LAYOUT = Collections.unmodifiableMap(layout);
    }

    /** Factory of the UTF-8 generators; Jackson defaults, non-ASCII characters left unescaped. */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Renders each REST customer as one SOAP-shaped object. */
    private final NetsuiteValueMapper values;

    /**
     * Creates a mapper that renders every customer value through {@code values}.
     *
     * @param values the NetSuite value mapper that writes each record
     * @throws NullPointerException if {@code values} is {@code null}
     */
    public CustomerMapper(NetsuiteValueMapper values) {
        this.values = Objects.requireNonNull(values, "values");
    }

    /**
     * Returns the JSON array of the DW-18 setter for the given REST customer instances
     * [DW-18 netsuite-api.xml:44] (AAP 0.6.5, D-016).
     *
     * <p>The array holds one object per list element, in list order, each written by
     * {@link NetsuiteValueMapper#writeRecord} with {@link #LAYOUT}. An empty list gives {@code []}.
     * The elements are read and never modified.
     *
     * @param restCustomers the REST customer instance bodies, in collection order
     * @return the UTF-8 bytes of the JSON array, with no trailing line feed
     * @throws NullPointerException     if {@code restCustomers} is {@code null}
     * @throws IllegalArgumentException if an element is {@code null} or not a JSON object
     * @throws UncheckedIOException     if the generator cannot write; the cause is the original
     *                                  {@link IOException}
     */
    public byte[] toJson(List<JsonNode> restCustomers) {
        Objects.requireNonNull(restCustomers, "restCustomers");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = FACTORY.createGenerator(out, JsonEncoding.UTF8)) {
            g.setPrettyPrinter(new DwJsonLayout());
            g.writeStartArray();
            for (JsonNode restCustomer : restCustomers) {
                values.writeRecord(g, restCustomer, LAYOUT);
            }
            g.writeEndArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Customer JSON could not be written", e);
        }
        return out.toByteArray();
    }
}
