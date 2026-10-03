package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException;
import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;

/**
 * Unit tests of {@link ItemSupplyPlanQuantityFilter}, the Java predicate that replaces the DW-19
 * criterion {@code ItemSupplyPlanSearchBasic.quantity} with {@code operator} and {@code quantity}
 * over the {@code order.items} lines of expanded NetSuite REST item supply plans (FB-NS-01, D-016).
 * Each test calls a new filter directly, with no Spring application context.
 *
 * <p>The two reference plans are built inline: plan {@code "9"} with one line of quantity
 * {@code 3}, and plan {@code "18"} with lines of quantity {@code 5} and {@code 12}. The tests
 * assert
 *
 * <ul>
 *   <li>the plans kept by each of the five RAML operators, the RAML default {@code GREATER_THAN}
 *       {@code 0} included, one entry per plan in input order, a plan with several matching lines
 *       appearing once;</li>
 *   <li>that a plan without {@code order}, without {@code order.items}, with no line, or whose
 *       lines carry no decimal quantity is never kept, and that a {@code null} list element is
 *       skipped;</li>
 *   <li>the {@link java.math.BigDecimal#compareTo} comparison: {@code 5.0} equals {@code 5}, read
 *       as a double node and as a decimal node, and a text quantity is read as the decimal it
 *       holds after trimming;</li>
 *   <li>that an operator outside the five RAML enum names, {@code null} included, is refused with
 *       {@link BadRequestException} by {@code requireOperator} and by {@code itemSupplyPlans},
 *       before the plan list is read.</li>
 * </ul>
 */
class ItemSupplyPlanQuantityFilterTest {

    /** Mapper that parses the inline plans; floating-point numbers become double nodes. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Plan {@code "9"}: one {@code order.items} line of quantity {@code 3}. */
    private static final String PLAN_9 = "{\"id\":\"9\",\"order\":{\"items\":[{\"quantity\":3}]}}";

    /** Plan {@code "18"}: {@code order.items} lines of quantity {@code 5} and {@code 12}. */
    private static final String PLAN_18 =
            "{\"id\":\"18\",\"order\":{\"items\":[{\"quantity\":5},{\"quantity\":12}]}}";

    /** Plan {@code "55"}: one {@code order.items} line of quantity {@code 5.0}. */
    private static final String PLAN_55 = "{\"id\":\"55\",\"order\":{\"items\":[{\"quantity\":5.0}]}}";

    /** The filter under test. */
    private final ItemSupplyPlanQuantityFilter filter = new ItemSupplyPlanQuantityFilter();

    @ParameterizedTest(name = "{0} {1} keeps the plans {2}")
    @CsvSource({
        "GREATER_THAN,0,9;18",
        "GREATER_THAN,4,18",
        "GREATER_THAN,12,",
        "LESS_THAN,4,9",
        "LESS_THAN,3,",
        "LESS_THAN,6,9;18",
        "EQUAL_TO,3,9",
        "EQUAL_TO,12,18",
        "EQUAL_TO,7,",
        "LESS_THAN_OR_EQUAL_TO,3,9",
        "LESS_THAN_OR_EQUAL_TO,5,9;18",
        "GREATER_THAN_OR_EQUAL_TO,12,18",
        "GREATER_THAN_OR_EQUAL_TO,3,9;18",
        "GREATER_THAN_OR_EQUAL_TO,13,"
    })
    @DisplayName("Each operator keeps the plans that have at least one matching line")
    void eachOperatorKeepsPlansWithAMatchingLine(String operator, int quantity, String expectedIds)
            throws JsonProcessingException {
        List<JsonNode> matching = filter.itemSupplyPlans(plans(PLAN_9, PLAN_18), quantity, operator);

        assertThat(ids(matching)).containsExactlyElementsOf(expectedIds(expectedIds));
    }

    @Test
    @DisplayName("A plan with several matching lines is kept once")
    void planWithSeveralMatchingLinesIsKeptOnce() throws JsonProcessingException {
        JsonNode plan18 = plan(PLAN_18);

        List<JsonNode> matching =
                filter.itemSupplyPlans(List.of(plan(PLAN_9), plan18), 4, "GREATER_THAN");

        assertThat(matching).hasSize(1);
        assertThat(matching.get(0)).isSameAs(plan18);
        assertThat(ids(matching)).containsExactly("18");
    }

    @Test
    @DisplayName("Kept plans follow the input order")
    void keptPlansFollowTheInputOrder() throws JsonProcessingException {
        List<JsonNode> matching = filter.itemSupplyPlans(plans(PLAN_18, PLAN_9), 0, "GREATER_THAN");

        assertThat(ids(matching)).containsExactly("18", "9");
    }

    @ParameterizedTest(name = "{0} {1} keeps none of them")
    @CsvSource({
        "LESS_THAN,0",
        "GREATER_THAN,0",
        "EQUAL_TO,0",
        "LESS_THAN_OR_EQUAL_TO,0",
        "GREATER_THAN_OR_EQUAL_TO,0",
        "GREATER_THAN_OR_EQUAL_TO,-1000",
        "LESS_THAN_OR_EQUAL_TO,1000"
    })
    @DisplayName("Plans without order, without order items or with no line are never kept")
    void plansWithoutLinesAreNeverKept(String operator, int quantity) throws JsonProcessingException {
        List<JsonNode> plans = plans(
                "{\"id\":\"77\"}",
                "{\"id\":\"78\",\"order\":{}}",
                "{\"id\":\"79\",\"order\":{\"items\":[]}}");

        assertThat(filter.itemSupplyPlans(plans, quantity, operator)).isEmpty();
    }

    @ParameterizedTest(name = "{0} 5 keeps the 5.0 line: {1}")
    @CsvSource({
        "EQUAL_TO,true",
        "LESS_THAN_OR_EQUAL_TO,true",
        "GREATER_THAN_OR_EQUAL_TO,true",
        "LESS_THAN,false",
        "GREATER_THAN,false"
    })
    @DisplayName("A quantity of 5.0 compares equal to 5 as a double node and as a decimal node")
    void fractionalQuantityComparesNumerically(String operator, boolean kept)
            throws JsonProcessingException {
        JsonNode doublePlan = plan(PLAN_55);
        JsonNode decimalPlan = RamlExampleReader.parse(PLAN_55.getBytes(StandardCharsets.UTF_8));
        assertThat(doublePlan.at("/order/items/0/quantity").isDouble()).isTrue();
        assertThat(decimalPlan.at("/order/items/0/quantity").isBigDecimal()).isTrue();

        List<JsonNode> fromDouble = filter.itemSupplyPlans(List.of(doublePlan), 5, operator);
        List<JsonNode> fromDecimal = filter.itemSupplyPlans(List.of(decimalPlan), 5, operator);

        assertThat(ids(fromDouble)).isEqualTo(kept ? List.of("55") : List.of());
        assertThat(ids(fromDecimal)).isEqualTo(kept ? List.of("55") : List.of());
    }

    @Test
    @DisplayName("An empty plan list gives an empty result")
    void emptyPlanListGivesEmptyResult() {
        assertThat(filter.itemSupplyPlans(List.of(), 0, "GREATER_THAN")).isEmpty();
    }

    @Test
    @DisplayName("A null element of the plan list is skipped")
    void nullPlanElementIsSkipped() throws JsonProcessingException {
        List<JsonNode> plans = Arrays.asList(null, plan(PLAN_9), null);

        assertThat(ids(filter.itemSupplyPlans(plans, 0, "GREATER_THAN"))).containsExactly("9");
    }

    @Test
    @DisplayName("A missing plan list is refused with a null pointer exception naming the plans")
    void missingPlanListIsRefused() {
        assertThatNullPointerException()
                .isThrownBy(() -> filter.itemSupplyPlans(null, 0, "GREATER_THAN"))
                .withMessage("plans");
    }

    @Test
    @DisplayName("The result is a new mutable list and the input list is left unchanged")
    void resultIsNewMutableListAndInputIsUnchanged() throws JsonProcessingException {
        List<JsonNode> plans = new ArrayList<>(plans(PLAN_9, PLAN_18));
        List<JsonNode> before = List.copyOf(plans);

        List<JsonNode> matching = filter.itemSupplyPlans(plans, 0, "GREATER_THAN");
        matching.clear();

        assertThat(plans).containsExactlyElementsOf(before);
        assertThat(ids(plans)).containsExactly("9", "18");
        assertThat(plans.get(1)).isEqualTo(plan(PLAN_18));
    }

    @ParameterizedTest(name = "A line {0} is never matched")
    @ValueSource(strings = {
        "{}",
        "{\"quantity\":null}",
        "{\"quantity\":\"abc\"}",
        "{\"quantity\":\"\"}",
        "{\"quantity\":\"   \"}",
        "{\"quantity\":true}",
        "{\"quantity\":[5]}",
        "{\"quantity\":{\"value\":5}}"
    })
    @DisplayName("A line without a decimal quantity never matches")
    void lineWithoutDecimalQuantityNeverMatches(String line) throws JsonProcessingException {
        List<JsonNode> plans = plans("{\"id\":\"60\",\"order\":{\"items\":[" + line + "]}}");

        assertThat(filter.itemSupplyPlans(plans, -1000, "GREATER_THAN_OR_EQUAL_TO")).isEmpty();
        assertThat(filter.itemSupplyPlans(plans, 1000, "LESS_THAN_OR_EQUAL_TO")).isEmpty();
    }

    @ParameterizedTest(name = "A line quantity of {0} is never matched")
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("A line whose quantity is a number with no decimal value never matches")
    void lineWithNonFiniteQuantityNeverMatches(double quantity) {
        ObjectNode plan = MAPPER.createObjectNode().put("id", "61");
        plan.putObject("order").putArray("items").addObject().put("quantity", quantity);

        assertThat(filter.itemSupplyPlans(List.of(plan), -1000, "GREATER_THAN_OR_EQUAL_TO")).isEmpty();
        assertThat(filter.itemSupplyPlans(List.of(plan), 1000, "LESS_THAN_OR_EQUAL_TO")).isEmpty();
    }

    @ParameterizedTest(name = "A line quantity of text {0} equals 7")
    @ValueSource(strings = {"\"7\"", "\" 7 \"", "\"7.0\""})
    @DisplayName("A line quantity held as text is read as the decimal it holds after trimming")
    void textQuantityIsReadAsTrimmedDecimal(String quantity) throws JsonProcessingException {
        List<JsonNode> plans =
                plans("{\"id\":\"62\",\"order\":{\"items\":[{\"quantity\":" + quantity + "}]}}");

        assertThat(ids(filter.itemSupplyPlans(plans, 7, "EQUAL_TO"))).containsExactly("62");
        assertThat(filter.itemSupplyPlans(plans, 7, "GREATER_THAN")).isEmpty();
    }

    @Test
    @DisplayName("A line without a decimal quantity does not stop the search of the later lines")
    void unmatchedLineDoesNotStopLaterLines() throws JsonProcessingException {
        List<JsonNode> plans = plans(
                "{\"id\":\"63\",\"order\":{\"items\":[{\"quantity\":\"abc\"},{},{\"quantity\":9}]}}");

        assertThat(ids(filter.itemSupplyPlans(plans, 8, "GREATER_THAN"))).containsExactly("63");
    }

    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {
        "LESS_THAN",
        "GREATER_THAN",
        "EQUAL_TO",
        "LESS_THAN_OR_EQUAL_TO",
        "GREATER_THAN_OR_EQUAL_TO"
    })
    @DisplayName("The five RAML operator names are accepted")
    void ramlOperatorNamesAreAccepted(String operator) {
        assertThatCode(() -> filter.requireOperator(operator)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "Operator [{0}] is refused")
    @NullSource
    @ValueSource(strings = {"FOO", "greater_than", ""})
    @DisplayName("An operator outside the five RAML names is refused as a bad request")
    void otherOperatorsAreRefused(String operator) {
        assertThatThrownBy(() -> filter.requireOperator(operator))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unsupported operator: " + operator);
    }

    @ParameterizedTest(name = "Filtering with operator [{0}] is refused")
    @NullSource
    @ValueSource(strings = {"FOO"})
    @DisplayName("Filtering with an operator outside the five RAML names is refused as a bad request")
    void filteringWithOtherOperatorIsRefused(String operator) throws JsonProcessingException {
        List<JsonNode> plans = plans(PLAN_9, PLAN_18);

        assertThatThrownBy(() -> filter.itemSupplyPlans(plans, 0, operator))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unsupported operator: " + operator);
        assertThatThrownBy(() -> filter.itemSupplyPlans(List.of(), 0, operator))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> filter.itemSupplyPlans(null, 0, operator))
                .isInstanceOf(BadRequestException.class);
    }

    /**
     * Parses one plan with {@link #MAPPER}.
     *
     * @param json the plan body
     * @return the parsed plan
     * @throws JsonProcessingException when {@code json} is not valid JSON
     */
    private static JsonNode plan(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    /**
     * Parses each plan with {@link #MAPPER}, keeping the argument order.
     *
     * @param json the plan bodies
     * @return an unmodifiable list of the parsed plans
     * @throws JsonProcessingException when one body is not valid JSON
     */
    private static List<JsonNode> plans(String... json) throws JsonProcessingException {
        List<JsonNode> plans = new ArrayList<>();
        for (String body : json) {
            plans.add(plan(body));
        }
        return List.copyOf(plans);
    }

    /**
     * Returns the {@code id} text of each plan, in list order.
     *
     * @param plans the plans
     * @return the plan ids
     */
    private static List<String> ids(List<JsonNode> plans) {
        return plans.stream().map(plan -> plan.get("id").asText()).toList();
    }

    /**
     * Splits a {@code ;}-separated id list of a {@code CsvSource} row.
     *
     * @param ids the ids separated by {@code ;}, or {@code null} for none
     * @return the ids in order; empty for {@code null}
     */
    private static List<String> expectedIds(String ids) {
        return ids == null ? List.of() : List.of(ids.split(";"));
    }
}
