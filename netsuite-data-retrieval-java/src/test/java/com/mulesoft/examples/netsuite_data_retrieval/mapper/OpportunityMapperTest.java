package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType;
import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link OpportunityMapper}, the writer of the DW-22 setter {@code payload map $}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:99-103], run without a Spring context (D-016).
 *
 * <p>The mapper renders dateTime values in {@code America/Los_Angeles}. Its input is the REST-shaped
 * opportunity instance stub {@code stubs/opportunity-record.json}, whose {@code shippingaddress} and
 * {@code billingaddress} subrecords hold plain {@code \n} line breaks (FB-NS-03). The tests assert
 *
 * <ul>
 *   <li>that the stub is written as the bytes of the committed example
 *       {@code api/opportunities-response.json}, read leniently (D-045), with its two {@code addrText}
 *       values in the escaped, unindented {@code \n<br>} form (FB-NS-03, D-532);</li>
 *   <li>the 29 keys of {@link OpportunityMapper#LAYOUT} in example order, each with its SOAP type;</li>
 *   <li>{@code status} written as the {@code refName} of the REST enumeration object;</li>
 *   <li>the 15 Address members of both addresses, with {@code internalId} and {@code override} from the
 *       shipping subrecord and {@code null} for the billing subrecord, which carries its state under
 *       {@code dropdownstate} (FB-NS-03);</li>
 *   <li>{@code []} for an empty list, the omission of a key whose REST field is absent, and an input
 *       left unmodified;</li>
 *   <li>the documented refusals: {@link NullPointerException} for a null value mapper or list,
 *       {@link IllegalArgumentException} for a null or non-object element, and
 *       {@link UncheckedIOException} carrying the {@link IOException} of a failed write.</li>
 * </ul>
 */
@DisplayName("Opportunity mapper")
class OpportunityMapperTest {

    /** Classpath name of the committed opportunities response example. */
    private static final String EXAMPLE = "api/opportunities-response.json";

    /** Classpath name of the REST opportunity instance stub. */
    private static final String STUB = "stubs/opportunity-record.json";

    /**
     * The JSON string literal the mapper writes for both {@code addrText} values: the five address
     * lines joined with the escaped, unindented {@code \n<br>} (FB-NS-03).
     */
    private static final String UNINDENTED = "\"Williams Electronics and Communications\\n<br>123 Main St.\\n<br>Suite 700\\n<br>San Mate CA 94002\\n<br>United States\"";

    /** Number of line feeds in the expected body: the example's 112 less the 8 inside its two addrText values. */
    private static final long EXPECTED_LINE_FEEDS = 104;

    /** Default Jackson mapper: parses the mapper output as strict JSON. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The mapper under test, rendering dateTime values in {@code America/Los_Angeles}. */
    private final OpportunityMapper mapper =
            new OpportunityMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));

    @Test
    @DisplayName("The REST opportunity stub is written as the committed example with unindented addrText joins")
    void stubIsWrittenAsTheExampleWithUnindentedAddrText() {
        String expected = expectedBody();

        byte[] written = mapper.toJson(stub());

        assertThat(new String(written, UTF_8)).isEqualTo(expected);
        assertThat(written).isEqualTo(expected.getBytes(UTF_8));
        assertThat(written[written.length - 1]).isEqualTo((byte) ']');
    }

    @Test
    @DisplayName("LAYOUT lists the 29 keys of the example element in example order")
    void layoutListsTheExampleKeysInExampleOrder() {
        assertThat(new ArrayList<>(OpportunityMapper.LAYOUT.keySet()))
                .isEqualTo(exampleKeys())
                .hasSize(29);
    }

    @Test
    @DisplayName("LAYOUT maps each key to its SOAP type")
    void layoutMapsEachKeyToItsSoapType() {
        Map<String, FieldType> expected = new LinkedHashMap<>();
        putAll(expected, FieldType.RECORD_REF, "salesRep", "currency", "leadSource", "forecastType",
                "subsidiary", "entityStatus", "location", "entity");
        putAll(expected, FieldType.DATE_TIME, "expectedCloseDate", "lastModifiedDate", "createdDate", "tranDate");
        putAll(expected, FieldType.ADDRESS, "shippingAddress", "billingAddress");
        putAll(expected, FieldType.LONG, "daysOpen");
        putAll(expected, FieldType.DOUBLE, "estGrossProfitPercent", "projectedTotal", "exchangeRate",
                "weightedTotal", "totalCostEstimate", "probability", "estGrossProfit");
        putAll(expected, FieldType.BOOLEAN, "shipIsResidential", "isBudgetApproved");
        putAll(expected, FieldType.STRING, "tranId", "title", "internalId", "currencyName", "status");

        Map<FieldType, Long> counts = OpportunityMapper.LAYOUT.values().stream()
                .collect(groupingBy(identity(), () -> new EnumMap<>(FieldType.class), counting()));

        assertThat(OpportunityMapper.LAYOUT).isEqualTo(expected);
        assertThat(counts).isEqualTo(Map.of(
                FieldType.RECORD_REF, 8L,
                FieldType.DATE_TIME, 4L,
                FieldType.ADDRESS, 2L,
                FieldType.LONG, 1L,
                FieldType.DOUBLE, 7L,
                FieldType.BOOLEAN, 2L,
                FieldType.STRING, 5L));
    }

    @Test
    @DisplayName("The REST status enumeration object is written as its display name")
    void statusIsWrittenAsItsRefName() throws IOException {
        JsonNode status = mapped(stub()).get(0).get("status");

        assertThat(status.isTextual()).isTrue();
        assertThat(status.textValue()).isEqualTo("Issued Quote");
    }

    @Test
    @DisplayName("Both addresses carry the 15 example members, with id and override from the shipping subrecord only")
    void addressesCarryTheExampleMembers() throws IOException {
        JsonNode example = RamlExampleReader.read(EXAMPLE).get(0);
        JsonNode element = mapped(stub()).get(0);
        JsonNode shipping = element.get("shippingAddress");
        JsonNode billing = element.get("billingAddress");

        assertThat(shipping.get("internalId").textValue()).isEqualTo("2475");
        assertThat(shipping.get("override").isBoolean()).isTrue();
        assertThat(shipping.get("override").booleanValue()).isFalse();
        assertThat(billing.get("internalId").isNull()).isTrue();
        assertThat(billing.get("override").isNull()).isTrue();
        assertThat(billing.get("state").textValue()).isEqualTo("CA");
        for (String address : List.of("shippingAddress", "billingAddress")) {
            assertThat(fieldNames(element.get(address)))
                    .as("members of %s", address)
                    .isEqualTo(fieldNames(example.get(address)))
                    .hasSize(15);
        }
    }

    @Test
    @DisplayName("An empty list is written as two brackets")
    void emptyListIsWrittenAsTwoBrackets() {
        assertThat(mapper.toJson(List.of())).isEqualTo("[]".getBytes(UTF_8));
    }

    @Test
    @DisplayName("An opportunity without a title is written without the title key and keeps the other 28 keys in order")
    void absentTitleIsOmitted() throws IOException {
        ObjectNode opportunity = stub().get(0).deepCopy();
        opportunity.remove("title");
        List<String> expectedKeys = exampleKeys();
        expectedKeys.remove("title");

        JsonNode element = mapped(List.of(opportunity)).get(0);

        assertThat(element.has("title")).isFalse();
        assertThat(fieldNames(element)).isEqualTo(expectedKeys).hasSize(28);
    }

    @Test
    @DisplayName("An opportunity without a billing address is written without billingAddress and keeps shippingAddress")
    void absentBillingAddressIsOmitted() throws IOException {
        ObjectNode opportunity = stub().get(0).deepCopy();
        opportunity.remove("billingaddress");
        List<String> expectedKeys = exampleKeys();
        expectedKeys.remove("billingAddress");

        JsonNode element = mapped(List.of(opportunity)).get(0);

        assertThat(element.has("billingAddress")).isFalse();
        assertThat(element.has("shippingAddress")).isTrue();
        assertThat(element.get("shippingAddress").get("internalId").textValue()).isEqualTo("2475");
        assertThat(fieldNames(element)).isEqualTo(expectedKeys).hasSize(28);
    }

    @Test
    @DisplayName("The REST opportunity is not modified by the mapping")
    void inputIsNotModified() {
        List<JsonNode> opportunities = stub();
        JsonNode before = opportunities.get(0).deepCopy();

        mapper.toJson(opportunities);

        assertThat(opportunities).hasSize(1);
        assertThat(opportunities.get(0)).isEqualTo(before);
    }

    @Test
    @DisplayName("A null value mapper and a null list are refused with NullPointerException")
    void nullArgumentsAreRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OpportunityMapper(null))
                .withMessage("values");
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toJson(null))
                .withMessage("restOpportunities");
    }

    @Test
    @DisplayName("A null element and an element that is not a JSON object are refused with IllegalArgumentException")
    void nonObjectElementsAreRefused() {
        assertThatThrownBy(() -> mapper.toJson(Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JSON object");
        assertThatThrownBy(() -> mapper.toJson(List.of(TextNode.valueOf("1271"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("A write failure of the value mapper is thrown as UncheckedIOException with the original cause")
    void writeFailureIsThrownAsUncheckedIoException() {
        IOException failure = new IOException("generator cannot write");
        NetsuiteValueMapper failing = new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")) {
            @Override
            public void writeRecord(JsonGenerator g, JsonNode restRecord, Map<String, FieldType> layout)
                    throws IOException {
                throw failure;
            }
        };

        UncheckedIOException thrown =
                catchThrowableOfType(() -> new OpportunityMapper(failing).toJson(stub()), UncheckedIOException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getCause()).isSameAs(failure);
    }

    /**
     * Returns the elements of the REST opportunity stub {@code stubs/opportunity-record.json}, read with
     * {@link RamlExampleReader#read(String)}, in file order.
     *
     * @return a new mutable list of the stub's opportunity instance bodies
     * @throws IllegalStateException if the stub is not a JSON array
     */
    private static List<JsonNode> stub() {
        JsonNode root = RamlExampleReader.read(STUB);
        if (!root.isArray()) {
            throw new IllegalStateException(STUB + " is not a JSON array: " + root.getNodeType());
        }
        List<JsonNode> elements = new ArrayList<>();
        root.elements().forEachRemaining(elements::add);
        return elements;
    }

    /**
     * Returns the text of the committed example {@code api/opportunities-response.json} with its two
     * {@code addrText} values replaced by the escaped, unindented {@code \n<br>} join that the mapper
     * writes (FB-NS-03, D-532). Each raw-LF {@code "<addrText>"} literal of {@code shippingAddress} and
     * {@code billingAddress}, read leniently (D-045), occurs exactly once in the file and is replaced by
     * {@link #UNINDENTED}; every other byte of the file is kept unchanged. The result holds no raw line
     * feed inside an {@code addrText} value and 104 line feeds in total.
     *
     * @return the expected mapper output, as text
     */
    private static String expectedBody() {
        String text = new String(RamlExampleReader.bytes(EXAMPLE), UTF_8);
        JsonNode example = RamlExampleReader.read(EXAMPLE).get(0);

        String expected = text;
        for (String address : List.of("shippingAddress", "billingAddress")) {
            String raw = example.get(address).get("addrText").asText();
            String rawLiteral = "\"" + raw + "\"";

            assertThat(occurrences(text, rawLiteral))
                    .as("occurrences of the raw %s.addrText literal in %s", address, EXAMPLE)
                    .isEqualTo(1);
            expected = expected.replace(rawLiteral, UNINDENTED);
        }
        assertThat(expected)
                .as("raw line feeds inside the expected addrText values")
                .doesNotContainPattern("\"addrText\": \"[^\"]*\n");
        assertThat(expected.chars().filter(c -> c == '\n').count())
                .as("line feeds in the expected body")
                .isEqualTo(EXPECTED_LINE_FEEDS);
        return expected;
    }

    /**
     * Maps {@code opportunities} and parses the output with the strict default Jackson parser.
     *
     * @param opportunities the REST opportunity instance bodies
     * @return the root array of the mapper output
     * @throws IOException when the output is not strict JSON
     */
    private JsonNode mapped(List<JsonNode> opportunities) throws IOException {
        return JSON.readTree(mapper.toJson(opportunities));
    }

    /**
     * Returns the member names of the first element of {@code api/opportunities-response.json}, read
     * leniently (D-045), in file order.
     *
     * @return a new mutable list of the 29 example keys
     */
    private static List<String> exampleKeys() {
        return fieldNames(RamlExampleReader.read(EXAMPLE).get(0));
    }

    /**
     * Returns the member names of a JSON object in iteration order.
     *
     * @param object the JSON object
     * @return a new mutable list of its member names
     */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Maps each of {@code keys} to {@code type} in {@code target}.
     *
     * @param target the map that receives the entries
     * @param type   the SOAP type of every key
     * @param keys   the keys
     */
    private static void putAll(Map<String, FieldType> target, FieldType type, String... keys) {
        for (String key : keys) {
            target.put(key, type);
        }
    }

    /**
     * Counts the non-overlapping occurrences of {@code part} in {@code text}.
     *
     * @param text the text searched
     * @param part the non-empty text counted
     * @return the number of occurrences, scanning from the start of {@code text}
     * @throws IllegalArgumentException if {@code part} is empty
     */
    private static int occurrences(String text, String part) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException("part must not be empty");
        }
        int count = 0;
        int from = text.indexOf(part);
        while (from >= 0) {
            count++;
            from = text.indexOf(part, from + part.length());
        }
        return count;
    }
}
