package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.BOOLEAN;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.CREDIT_HOLD_OVERRIDE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.CUSTOM_FIELD_LIST;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.DATE_TIME;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.DOUBLE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.LONG;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.RECORD_REF;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.STAGE;
import static com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType.STRING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.NetsuiteValueMapper.FieldType;
import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of {@link CustomerMapper}, the DW-18 setter {@code payload map $}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:44-47], run without a Spring context (D-016).
 *
 * <p>The mapper renders in {@code America/Los_Angeles}. Its input is the REST-shaped customer instance
 * stub {@code stubs/customer-record.json}; its contract is {@code api/customers-response.json}. The
 * mapped stub is compared byte for byte with that example less its four {@code SelectCustomFieldRef__*}
 * {@code typeId} members, which the mapper does not write (FB-NS-05, D-539). The tests also pin the
 * {@link CustomerMapper#LAYOUT} key order and SOAP types, the empty array, the omission of a key that
 * REST does not supply, the unchanged input and one object per list element.
 */
@DisplayName("Customer mapper")
class CustomerMapperTest {

    /** Classpath name of the REST-shaped customer instance stub (D-539). */
    private static final String STUB = "stubs/customer-record.json";

    /** Classpath name of the committed customers response example. */
    private static final String EXAMPLE = "api/customers-response.json";

    /** Regex of one {@code typeId} member of a Select custom-field entry, with the comma and line feed before it. */
    private static final String TYPE_ID_MEMBER = ",\\n\\s*\"typeId\": \"[^\"]*\"";

    /** The quoted member name counted in the example text. */
    private static final String TYPE_ID_NAME = "\"typeId\"";

    /** Number of {@code typeId} members in the example, one per Select custom-field entry (FB-NS-05). */
    private static final int EXAMPLE_TYPE_ID_COUNT = 4;

    /** Default Jackson mapper: strict JSON parsing of the mapper output and of the expected body. */
    private static final ObjectMapper STRICT = new ObjectMapper();

    /** The example keys of each SOAP type (AAP 0.6.5 key table). */
    private static final Map<FieldType, List<String>> KEYS_BY_TYPE = Map.of(
            RECORD_REF, List.of("receivablesAccount", "accessRole", "currency", "subsidiary", "entityStatus"),
            DATE_TIME, List.of("dateCreated", "firstVisit", "lastModifiedDate", "lastVisit", "startDate"),
            DOUBLE, List.of("consolBalance", "consolAging", "consolAging1", "consolAging2", "consolAging3",
                    "consolAging4", "aging", "aging1", "aging2", "aging3", "aging4", "consolDepositBalance",
                    "consolOverdueBalance", "unbilledOrders", "consolUnbilledOrders"),
            LONG, List.of("visits"),
            BOOLEAN, List.of("isInactive", "isPerson", "billPay", "giveAccess", "taxable", "shipComplete"),
            STAGE, List.of("stage"),
            CREDIT_HOLD_OVERRIDE, List.of("creditHoldOverride"),
            CUSTOM_FIELD_LIST, List.of("customFieldList"),
            STRING, List.of("lastName", "companyName", "lastPageVisited", "internalId", "altEmail", "email",
                    "externalId", "entityId", "clickStream", "firstName", "phone", "defaultAddress"));

    /** Number of keys of each SOAP type in the example; no {@code ADDRESS} key. */
    private static final Map<FieldType, Long> KEY_COUNT_BY_TYPE = Map.of(
            RECORD_REF, 5L, DATE_TIME, 5L, DOUBLE, 15L, LONG, 1L, BOOLEAN, 6L,
            STAGE, 1L, CREDIT_HOLD_OVERRIDE, 1L, CUSTOM_FIELD_LIST, 1L, STRING, 12L);

    /** Mapper under test, rendering dateTime values in {@code America/Los_Angeles}. */
    private final CustomerMapper mapper = new CustomerMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));

    @Test
    @DisplayName("The customer stub maps to the customers example without its four typeId members")
    void stubMapsToExampleWithoutTypeIdMembers() {
        List<JsonNode> customers = stub();
        String expected = expectedBody();

        byte[] json = mapper.toJson(customers);

        assertThat(customers).hasSize(1);
        assertThat(new String(json, UTF_8)).isEqualTo(expected);
        assertThat(json).isEqualTo(expected.getBytes(UTF_8));
        assertThat(json[json.length - 1]).isEqualTo((byte) ']');
    }

    @Test
    @DisplayName("The customer layout lists the 47 example keys in example order")
    void layoutListsExampleKeysInExampleOrder() {
        List<String> exampleKeys = fieldNames(RamlExampleReader.read(EXAMPLE).get(0));

        assertThat(new ArrayList<>(CustomerMapper.LAYOUT.keySet())).isEqualTo(exampleKeys);
        assertThat(CustomerMapper.LAYOUT).hasSize(47);
    }

    @Test
    @DisplayName("The customer layout gives each example key its SOAP type")
    void layoutGivesEachKeyItsSoapType() {
        Map<String, FieldType> expected = new LinkedHashMap<>();
        KEYS_BY_TYPE.forEach((type, keys) -> keys.forEach(key -> expected.put(key, type)));

        Map<FieldType, Long> counts = CustomerMapper.LAYOUT.values().stream()
                .collect(Collectors.groupingBy(Function.identity(), () -> new EnumMap<>(FieldType.class),
                        Collectors.counting()));

        assertThat(expected).hasSize(47);
        assertThat(CustomerMapper.LAYOUT).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(KEY_COUNT_BY_TYPE);
    }

    @Test
    @DisplayName("An empty customer list maps to an empty JSON array")
    void emptyListMapsToEmptyArray() {
        assertThat(mapper.toJson(List.of())).isEqualTo("[]".getBytes(UTF_8));
    }

    @ParameterizedTest(name = "REST key {0} absent")
    @ValueSource(strings = {"phone", "subsidiary"})
    @DisplayName("A key the REST customer lacks is omitted and the other 46 keys keep example order and values")
    void absentRestKeyIsOmitted(String key) throws IOException {
        ObjectNode customer = stub().get(0).deepCopy();
        customer.remove(key);
        ObjectNode expected = STRICT.readTree(expectedBody()).get(0).deepCopy();
        expected.remove(key);
        List<String> expectedKeys = fieldNames(RamlExampleReader.read(EXAMPLE).get(0));
        expectedKeys.remove(key);

        JsonNode element = STRICT.readTree(mapper.toJson(List.of(customer))).get(0);

        assertThat(fieldNames(element)).hasSize(46).doesNotContain(key).isEqualTo(expectedKeys);
        assertThat(element).isEqualTo(expected);
    }

    @Test
    @DisplayName("Mapping leaves the REST customer unchanged")
    void inputIsNotModified() {
        List<JsonNode> customers = stub();
        JsonNode before = customers.get(0).deepCopy();

        mapper.toJson(customers);

        assertThat(customers.get(0)).isEqualTo(before);
    }

    @Test
    @DisplayName("Two REST customers map to an array of two equal customer objects")
    void twoCustomersMapToTwoObjects() throws IOException {
        JsonNode customer = stub().get(0);
        JsonNode expected = STRICT.readTree(expectedBody()).get(0);

        JsonNode array = STRICT.readTree(mapper.toJson(List.of(customer, customer)));

        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0)).isEqualTo(expected);
        assertThat(array.get(1)).isEqualTo(array.get(0));
    }

    /**
     * Returns the elements of the customer stub array {@code stubs/customer-record.json}, read with
     * {@link RamlExampleReader#read(String)}.
     *
     * @return the REST customer instance bodies, in stub order
     */
    private static List<JsonNode> stub() {
        JsonNode root = RamlExampleReader.read(STUB);
        assertThat(root.isArray()).isTrue();
        List<JsonNode> customers = new ArrayList<>();
        root.elements().forEachRemaining(customers::add);
        return customers;
    }

    /**
     * Returns the text of {@code api/customers-response.json} with its four {@code typeId} members removed
     * from the example, each together with the comma and line feed before it (FB-NS-05, D-539). Every
     * other byte of the example is kept. The example holds {@code "typeId"} exactly four times and the
     * result holds it no time, four lines fewer than the example.
     *
     * @return the expected mapper output for the customer stub
     */
    private static String expectedBody() {
        String example = new String(RamlExampleReader.bytes(EXAMPLE), UTF_8);
        String expected = example.replaceAll(TYPE_ID_MEMBER, "");

        assertThat(occurrences(example, TYPE_ID_NAME)).isEqualTo(EXAMPLE_TYPE_ID_COUNT);
        assertThat(occurrences(expected, TYPE_ID_NAME)).isZero();
        assertThat(occurrences(expected, "\n")).isEqualTo(occurrences(example, "\n") - EXAMPLE_TYPE_ID_COUNT);
        return expected;
    }

    /**
     * Returns the member names of a JSON object in document order.
     *
     * @param object the JSON object
     * @return a new modifiable list of its member names
     */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * Counts the non-overlapping occurrences of {@code token} in {@code text}.
     *
     * @param text  the text searched
     * @param token the non-empty text counted
     * @return the number of occurrences
     */
    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) {
            count++;
        }
        return count;
    }
}
