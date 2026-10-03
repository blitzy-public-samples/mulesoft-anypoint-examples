package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;

/**
 * Unit tests of {@link ItemSupplyPlanMapper}, the writer of the DW-20 setter {@code payload map $}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:70-73] over NetSuite REST item supply plan
 * records (D-016). Each test calls a mapper built on
 * {@code new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles"))} directly, with no Spring
 * application context.
 *
 * <p>The input is the REST-shaped stub {@code stubs/itemsupplyplan-record.json}: plan {@code "9"}
 * without {@code units} and with one {@code order.items} line, and plan {@code "18"} with
 * {@code units} and two {@code order.items} lines. The tests assert
 *
 * <ul>
 *   <li>that the two stub plans are written as the committed bytes of
 *       {@code api/items-response.json};</li>
 *   <li>the keys and types of {@link ItemSupplyPlanMapper#LAYOUT}, which equal the keys of the
 *       example's second element in their order;</li>
 *   <li>that {@code units} is omitted when the plan has none and written as a RecordRef
 *       otherwise;</li>
 *   <li>that no element carries a {@code quantity} key, a top-level REST {@code quantity} included
 *       (FB-NS-01);</li>
 *   <li>that an empty list gives {@code []}, that a plan without {@code location} gives an element
 *       without it, and that the plans are written in list order;</li>
 *   <li>that the input list and its nodes are left unchanged;</li>
 *   <li>the refusals of a {@code null} list, a {@code null} or non-object element and a
 *       {@code null} value mapper, and the {@link UncheckedIOException} of a failed write.</li>
 * </ul>
 */
class ItemSupplyPlanMapperTest {

    /** Classpath name of the REST item supply plan stub. */
    private static final String STUB = "stubs/itemsupplyplan-record.json";

    /** Classpath name of the committed items response example. */
    private static final String EXAMPLE = "api/items-response.json";

    /**
     * Strict parser of the mapper output: Jackson's default syntax rules, and a failure on a
     * duplicate member name and on content after the root value.
     */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** The mapper under test, rendering in the default {@code netsuite.time-zone}. */
    private final ItemSupplyPlanMapper mapper =
            new ItemSupplyPlanMapper(new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")));

    @Test
    @DisplayName("The two stub plans are written as the bytes of the committed items example")
    void stubPlansAreWrittenAsTheExampleBytes() {
        byte[] expected = RamlExampleReader.bytes(EXAMPLE);

        byte[] written = mapper.toJson(stub());

        assertThat(new String(written, StandardCharsets.UTF_8))
                .isEqualTo(new String(expected, StandardCharsets.UTF_8));
        assertThat(written).isEqualTo(expected);
    }

    @Test
    @DisplayName("The layout lists internalId, item, location, units and subsidiary with their types")
    void layoutListsTheExampleKeysWithTheirTypes() {
        List<String> exampleKeys = new ArrayList<>();
        RamlExampleReader.read(EXAMPLE).get(1).fieldNames().forEachRemaining(exampleKeys::add);

        assertThat(ItemSupplyPlanMapper.LAYOUT.keySet())
                .containsExactly("internalId", "item", "location", "units", "subsidiary");
        assertThat(ItemSupplyPlanMapper.LAYOUT.values()).containsExactly(
                NetsuiteValueMapper.FieldType.STRING,
                NetsuiteValueMapper.FieldType.RECORD_REF,
                NetsuiteValueMapper.FieldType.RECORD_REF,
                NetsuiteValueMapper.FieldType.RECORD_REF,
                NetsuiteValueMapper.FieldType.RECORD_REF);
        assertThat(ItemSupplyPlanMapper.LAYOUT.keySet()).containsExactlyElementsOf(exampleKeys);
        assertThatThrownBy(() -> ItemSupplyPlanMapper.LAYOUT.put(
                        "quantity", NetsuiteValueMapper.FieldType.DOUBLE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Units are omitted for a plan without units and written as a record reference otherwise")
    void unitsAreOmittedWhenUnsetAndWrittenAsRecordRefOtherwise() {
        JsonNode output = parse(mapper.toJson(stub()));

        assertThat(output.get(0).get("internalId").textValue()).isEqualTo("9");
        assertThat(output.get(0).has("units")).isFalse();
        assertThat(fieldNames(output.get(0))).containsExactly("internalId", "item", "location", "subsidiary");

        JsonNode units = output.get(1).get("units");
        assertThat(output.get(1).get("internalId").textValue()).isEqualTo("18");
        assertThat(fieldNames(units)).containsExactly("externalId", "type", "internalId", "name");
        assertThat(units.get("externalId").isNull()).isTrue();
        assertThat(units.get("type").isNull()).isTrue();
        assertThat(units.get("internalId").textValue()).isEqualTo("15");
        assertThat(units.get("name").textValue()).isEqualTo("Square Feet");
    }

    @Test
    @DisplayName("No element carries a quantity key, even when the plan has a top-level quantity")
    void noElementCarriesQuantity() {
        JsonNode output = parse(mapper.toJson(stub()));
        ObjectNode withQuantity = plan("18").deepCopy();
        withQuantity.put("quantity", 7);

        JsonNode fromQuantity = parse(mapper.toJson(List.of(withQuantity)));

        assertThat(output.size()).isEqualTo(2);
        output.forEach(element -> assertThat(element.has("quantity")).isFalse());
        assertThat(fromQuantity.size()).isEqualTo(1);
        assertThat(fromQuantity.get(0).has("quantity")).isFalse();
        assertThat(fieldNames(fromQuantity.get(0)))
                .containsExactly("internalId", "item", "location", "units", "subsidiary");
    }

    @Test
    @DisplayName("An empty plan list is written as an empty array")
    void emptyPlanListIsWrittenAsEmptyArray() {
        assertThat(mapper.toJson(List.of())).isEqualTo("[]".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A plan without location is written without the location key")
    void planWithoutLocationIsWrittenWithoutLocation() {
        ObjectNode withoutLocation = plan("18").deepCopy();
        withoutLocation.remove("location");

        JsonNode output = parse(mapper.toJson(List.of(withoutLocation)));

        assertThat(output.size()).isEqualTo(1);
        assertThat(output.get(0).has("location")).isFalse();
        assertThat(fieldNames(output.get(0))).containsExactly("internalId", "item", "units", "subsidiary");
    }

    @Test
    @DisplayName("Plans are written in list order")
    void plansAreWrittenInListOrder() {
        List<JsonNode> reversed = List.of(plan("18"), plan("9"));

        JsonNode output = parse(mapper.toJson(reversed));

        assertThat(output.size()).isEqualTo(2);
        assertThat(output.get(0).get("internalId").textValue()).isEqualTo("18");
        assertThat(output.get(1).get("internalId").textValue()).isEqualTo("9");
    }

    @Test
    @DisplayName("The plan list and its plans are left unchanged by a write")
    void inputPlansAreNotModified() {
        List<JsonNode> plans = stub();
        List<JsonNode> before = plans.stream().<JsonNode>map(JsonNode::deepCopy).toList();

        mapper.toJson(plans);

        assertThat(plans).hasSize(before.size());
        assertThat(plans).containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("A missing plan list is refused with a null pointer exception naming restPlans")
    void missingPlanListIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toJson(null))
                .withMessage("restPlans");
    }

    @Test
    @DisplayName("A null plan or a plan that is not a JSON object is refused with an illegal argument exception")
    void nullOrNonObjectPlanIsRefused() {
        List<JsonNode> withNull = Arrays.asList(plan("9"), null);
        List<JsonNode> withText = List.of(plan("9"), TextNode.valueOf("18"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.toJson(withNull))
                .withMessage("NetSuite REST record is not a JSON object: null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.toJson(withText))
                .withMessage("NetSuite REST record is not a JSON object: STRING");
    }

    @Test
    @DisplayName("A missing value mapper is refused with a null pointer exception naming the values")
    void missingValueMapperIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ItemSupplyPlanMapper(null))
                .withMessage("values");
    }

    @Test
    @DisplayName("A failed write is thrown as an unchecked I/O exception carrying the original cause")
    void failedWriteIsThrownAsUncheckedIoException() {
        IOException failure = new IOException("generator closed");
        NetsuiteValueMapper failing = new NetsuiteValueMapper(ZoneId.of("America/Los_Angeles")) {
            @Override
            public void writeRecord(JsonGenerator g, JsonNode restRecord,
                    Map<String, FieldType> layout) throws IOException {
                throw failure;
            }
        };
        ItemSupplyPlanMapper failingMapper = new ItemSupplyPlanMapper(failing);

        assertThatThrownBy(() -> failingMapper.toJson(stub()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Cannot write the item supply plan JSON")
                .hasCause(failure);
    }

    /**
     * Returns the plans of the stub, in file order, as read by {@link RamlExampleReader#read(String)}.
     *
     * @return a new mutable list of the stub's two plan nodes, {@code "9"} then {@code "18"}
     * @throws IllegalStateException if the stub root is not a JSON array
     */
    private static List<JsonNode> stub() {
        JsonNode root = RamlExampleReader.read(STUB);
        if (!root.isArray()) {
            throw new IllegalStateException(STUB + " is not a JSON array: " + root.getNodeType());
        }
        List<JsonNode> plans = new ArrayList<>();
        root.forEach(plans::add);
        return plans;
    }

    /**
     * Returns the stub plan whose REST {@code id} equals {@code id}.
     *
     * @param id the REST {@code id} of the plan, {@code "9"} or {@code "18"}
     * @return the plan node
     * @throws IllegalStateException if no stub plan has that id
     */
    private static ObjectNode plan(String id) {
        return stub().stream()
                .filter(p -> id.equals(p.path("id").textValue()))
                .map(ObjectNode.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(STUB + " holds no plan with id " + id));
    }

    /**
     * Parses mapper output with {@link #STRICT}.
     *
     * @param json the UTF-8 bytes written by the mapper
     * @return the root node of the output
     * @throws UncheckedIOException if the bytes are not one strict JSON document
     */
    private static JsonNode parse(byte[] json) {
        try {
            return STRICT.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException("Mapper output is not strict JSON", e);
        }
    }

    /**
     * Returns the member names of a JSON object in their order.
     *
     * @param object the JSON object
     * @return a new list of its member names
     */
    private static List<String> fieldNames(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
