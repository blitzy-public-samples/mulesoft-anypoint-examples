package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException;

/**
 * Applies the item supply plan quantity criterion of the DW-19 setter
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:56-66] (DW-19 netsuite-api.xml:56) to
 * expanded NetSuite REST item supply plan records, as a predicate over their {@code order.items}
 * sublist lines (AAP 0.6.5, FB-NS-01, D-016, D-399).
 *
 * <p>DW-19 builds {@code ItemSupplyPlanSearchBasic.quantity} with {@code operator} and
 * {@code searchValue: quantity as :number} from the {@code operator} and {@code quantity} query
 * parameters of {@code GET /api/items}
 * [netsuite-data-retrieval/src/main/api/netsuite-api.raml:18-36]. The input of this class is the
 * list of plan bodies that
 * {@code GET /services/rest/record/v1/itemsupplyplan/{id}?expandSubResources=true} returns, each
 * carrying its lines under {@code order.items}, and each line its {@code quantity}.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li>{@code operator} is one of the five RAML enum names, matched exactly and case-sensitively:
 *       {@code LESS_THAN} ({@code <}), {@code GREATER_THAN} ({@code >}), {@code EQUAL_TO}
 *       ({@code =}), {@code LESS_THAN_OR_EQUAL_TO} ({@code <=}) and
 *       {@code GREATER_THAN_OR_EQUAL_TO} ({@code >=}). Any other value, {@code null} included,
 *       throws {@link BadRequestException} with the message
 *       {@code Unsupported operator: <value>}.</li>
 *   <li>A line matches when its quantity, read as a {@link BigDecimal}, compares to
 *       {@code quantity} with the operator's comparison ({@link BigDecimal#compareTo}, so
 *       {@code 5.0} equals {@code 5}).</li>
 *   <li>The result holds one entry per plan that has at least one matching line, in input order;
 *       a plan with several matching lines appears once. A plan without an {@code order.items}
 *       array, or without a line whose quantity is a number, is not in the result.</li>
 *   <li>A line quantity is read from a JSON number, or from text holding a decimal number after
 *       trimming; any other value leaves the line unmatched.</li>
 *   <li>No default is applied here: the RAML defaults {@code quantity} {@code 0} and
 *       {@code operator} {@code GREATER_THAN} are supplied by the caller.</li>
 *   <li>The input list and its nodes are never modified; each call returns a new mutable list.</li>
 * </ul>
 *
 * <p>The class holds no state and is safe for concurrent use.
 *
 * <p>Usage:
 *
 * <pre>
 * filter.requireOperator(operator);                       // before any NetSuite call
 * List&lt;JsonNode&gt; matching = filter.itemSupplyPlans(plans, 10, "GREATER_THAN");
 * </pre>
 *
 * <p>Example: with {@code GREATER_THAN} and {@code 4}, a plan whose lines hold the quantities
 * {@code 5} and {@code 12} is returned once, and a plan whose only line holds {@code 3} is not
 * returned.
 */
@Component
public class ItemSupplyPlanQuantityFilter {

    /** REST key of the plan's order subrecord, which holds the sublist. */
    private static final String ORDER = "order";

    /** REST key of the order's line sublist. */
    private static final String ITEMS = "items";

    /** REST key of a line's quantity. */
    private static final String QUANTITY = "quantity";

    /** The five RAML {@code operator} enum names, each mapped to its comparison. */
    private static final Map<String, Comparison> COMPARISONS_BY_OPERATOR = comparisonsByOperator();

    /**
     * Checks that {@code operator} is one of the five RAML {@code operator} enum names
     * {@code LESS_THAN}, {@code GREATER_THAN}, {@code EQUAL_TO}, {@code LESS_THAN_OR_EQUAL_TO} and
     * {@code GREATER_THAN_OR_EQUAL_TO}, matched exactly and case-sensitively (DW-19
     * netsuite-api.xml:56, FB-NS-01, D-016, D-399).
     *
     * @param operator the {@code operator} query parameter after the caller has applied the RAML
     *     default
     * @throws BadRequestException when {@code operator} is {@code null} or not one of the five
     *     names, with the message {@code Unsupported operator: <operator>}
     */
    public void requireOperator(String operator) {
        comparisonFor(operator);
    }

    /**
     * Returns the plans that have at least one {@code order.items} line whose quantity satisfies
     * {@code <line quantity> <operator> <quantity>}, one entry per plan, in input order (DW-19
     * netsuite-api.xml:56, FB-NS-01, D-016, D-399).
     *
     * <p>The operator is checked by {@link #requireOperator(String)} before any plan is read, so an
     * unsupported operator throws for an empty list as well. The plans are tested one after
     * another; the lines of a plan are tested in array order up to the first match. A plan is added
     * at its first matching line. A {@code null} list element is not a plan and is not in the
     * result.
     *
     * @param plans the expanded item supply plan bodies, in collection order; neither the list nor
     *     its nodes are modified
     * @param quantity the {@code quantity} query parameter after the caller has applied the RAML
     *     default
     * @param operator the {@code operator} query parameter after the caller has applied the RAML
     *     default
     * @return a new mutable list of the matching plans, in input order; empty when none matches
     * @throws BadRequestException when {@code operator} is {@code null} or not one of the five RAML
     *     enum names
     * @throws NullPointerException when {@code plans} is {@code null}
     */
    public List<JsonNode> itemSupplyPlans(List<JsonNode> plans, int quantity, String operator) {
        requireOperator(operator);
        Objects.requireNonNull(plans, "plans");
        Comparison comparison = comparisonFor(operator);
        BigDecimal threshold = BigDecimal.valueOf(quantity);

        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode plan : plans) {
            if (plan != null && hasMatchingLine(plan, comparison, threshold)) {
                matching.add(plan);
            }
        }
        return matching;
    }

    /**
     * Tells whether one line of {@code plan.order.items} has a quantity that satisfies
     * {@code comparison} against {@code threshold}; stops at the first such line.
     *
     * @param plan one expanded item supply plan body
     * @param comparison the comparison of the requested operator
     * @param threshold the requested quantity
     * @return {@code true} when a line matches; {@code false} when none does or the plan has no
     *     {@code order.items} array
     */
    private static boolean hasMatchingLine(
            JsonNode plan, Comparison comparison, BigDecimal threshold) {
        JsonNode lines = plan.path(ORDER).path(ITEMS);
        if (!lines.isArray()) {
            return false;
        }
        for (JsonNode line : lines) {
            BigDecimal lineQuantity = lineQuantity(line);
            if (lineQuantity != null && comparison.test(lineQuantity.compareTo(threshold))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code quantity} of one sublist line as a {@link BigDecimal}: a JSON number as its
     * decimal value, text as the decimal number it holds after trimming. Returns {@code null} for a
     * missing key, JSON {@code null}, text that is not a decimal number, a number with no decimal
     * value (NaN, infinity) and any other node type; a {@code null} result leaves the line
     * unmatched (D-399).
     *
     * @param line one element of {@code order.items}
     * @return the line quantity, or {@code null} when the line carries no decimal number
     */
    private static BigDecimal lineQuantity(JsonNode line) {
        JsonNode quantity = line.get(QUANTITY);
        if (quantity == null) {
            return null;
        }
        try {
            if (quantity.isNumber()) {
                return quantity.decimalValue();
            }
            if (quantity.isTextual()) {
                return new BigDecimal(quantity.textValue().trim());
            }
        } catch (NumberFormatException notADecimal) {
            return null;
        }
        return null;
    }

    /**
     * Returns the comparison named by {@code operator}.
     *
     * @param operator the requested operator name
     * @return the comparison whose name equals {@code operator}
     * @throws BadRequestException when {@code operator} is {@code null} or not one of the five
     *     RAML enum names
     */
    private static Comparison comparisonFor(String operator) {
        Comparison comparison = operator == null ? null : COMPARISONS_BY_OPERATOR.get(operator);
        if (comparison == null) {
            throw new BadRequestException("Unsupported operator: " + operator);
        }
        return comparison;
    }

    /**
     * Builds the map from each RAML enum name to its comparison.
     *
     * @return an unmodifiable map with one entry per {@link Comparison} constant
     */
    private static Map<String, Comparison> comparisonsByOperator() {
        Map<String, Comparison> byOperator = new HashMap<>();
        for (Comparison comparison : Comparison.values()) {
            byOperator.put(comparison.name(), comparison);
        }
        return Collections.unmodifiableMap(byOperator);
    }

    /**
     * The comparisons of the RAML {@code operator} enum
     * [netsuite-data-retrieval/src/main/api/netsuite-api.raml:27-31]. Each tests the result of
     * {@code lineQuantity.compareTo(threshold)}.
     */
    private enum Comparison {

        /** {@code <}: the line quantity is less than the threshold. */
        LESS_THAN {
            @Override
            boolean test(int compareToResult) {
                return compareToResult < 0;
            }
        },

        /** {@code >}: the line quantity is greater than the threshold. */
        GREATER_THAN {
            @Override
            boolean test(int compareToResult) {
                return compareToResult > 0;
            }
        },

        /** {@code =}: the line quantity equals the threshold numerically. */
        EQUAL_TO {
            @Override
            boolean test(int compareToResult) {
                return compareToResult == 0;
            }
        },

        /** {@code <=}: the line quantity is less than or equal to the threshold. */
        LESS_THAN_OR_EQUAL_TO {
            @Override
            boolean test(int compareToResult) {
                return compareToResult <= 0;
            }
        },

        /** {@code >=}: the line quantity is greater than or equal to the threshold. */
        GREATER_THAN_OR_EQUAL_TO {
            @Override
            boolean test(int compareToResult) {
                return compareToResult >= 0;
            }
        };

        /**
         * Tells whether a {@code compareTo} result satisfies this comparison.
         *
         * @param compareToResult the result of {@code lineQuantity.compareTo(threshold)}
         * @return {@code true} when the line quantity satisfies this comparison
         */
        abstract boolean test(int compareToResult);
    }
}
