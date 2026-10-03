package com.mulesoft.examples.netsuite_data_retrieval.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.ItemSupplyPlanMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.ItemSupplyPlanQuantityFilter;

/**
 * Answers {@code GET /api/items} as flow {@code get:/items:netsuite-api-config}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:54-78]: lists every item supply plan,
 * reads each plan with its sub-resources expanded, keeps the plans whose lines satisfy the
 * {@code quantity}/{@code operator} criterion and writes them as the JSON array of
 * {@code api/items-response.json} (D-016, FB-NS-01).
 *
 * <p>The flow's processors map to {@link #getItems(int, String)} in this order:
 * <ul>
 *   <li>DW-19 {@code dw:transform-message} (:56-66), the criterion
 *       {@code ItemSupplyPlanSearchBasic.quantity {operator, searchValue: quantity as :number}},
 *       always applied: {@link ItemSupplyPlanQuantityFilter#requireOperator(String)} checks the
 *       operator before any NetSuite request (D-399), and
 *       {@link ItemSupplyPlanQuantityFilter#itemSupplyPlans(List, int, String)} keeps each plan with
 *       at least one {@code order.items} line that satisfies the comparison, once per plan and in
 *       collection order (FB-NS-01);</li>
 *   <li>{@code netsuite:search searchRecord="ITEM_SUPPLY_PLAN_BASIC"} (:68): the record collection
 *       query {@code GET /services/rest/record/v1/itemsupplyplan} with no {@code q} condition,
 *       through {@link NetsuiteRestClient#queryIds(String, String)}, then one instance read
 *       {@code GET /services/rest/record/v1/itemsupplyplan/{id}?expandSubResources=true} per id,
 *       one after another in id order, through
 *       {@link NetsuiteRestClient#getRecord(String, String, boolean)} (D-016);</li>
 *   <li>DW-20 {@code dw:transform-message} {@code payload map $} (:70-73):
 *       {@link ItemSupplyPlanMapper#toJson(List)};</li>
 *   <li>{@code logger} (:76): {@code Get items completes successfully.} at INFO.</li>
 * </ul>
 *
 * <p>The flow defines no exception strategy. This class catches nothing, retries nothing and
 * returns no partial result: the first failing NetSuite call, filter check or mapper call ends
 * the search, its exception reaches the caller unchanged, and the INFO line is not logged
 * (D-016, D-020). The RAML defaults {@code quantity} {@code 0} and {@code operator}
 * {@code GREATER_THAN} [netsuite-data-retrieval/src/main/api/netsuite-api.raml:22-31] are applied
 * by the caller.
 *
 * <p>The bean holds no mutable state; concurrent requests share its collaborators.
 *
 * <pre>{@code
 * byte[] body = itemSupplyPlanSearchService.getItems(0, "GREATER_THAN");
 * // the plans that have a line with quantity > 0, as the api/items-response.json array
 * byte[] none = itemSupplyPlanSearchService.getItems(100, "GREATER_THAN");
 * // the two bytes [] when no plan has a line with quantity > 100
 * itemSupplyPlanSearchService.getItems(0, "FOO");
 * // throws BadRequestException; NetSuite is not called
 * }</pre>
 */
@Service
public class ItemSupplyPlanSearchService {

    /** Logger of the flow's {@code logger} processor (:76). */
    private static final Logger LOG = LoggerFactory.getLogger(ItemSupplyPlanSearchService.class);

    /** NetSuite REST record type id of the item supply plan record. */
    private static final String RECORD_TYPE = "itemsupplyplan";

    /** Lists the item supply plan ids and reads each plan from NetSuite. */
    private final NetsuiteRestClient client;

    /** Checks the operator and applies the DW-19 quantity criterion to the expanded plans. */
    private final ItemSupplyPlanQuantityFilter quantityFilter;

    /** Writes the matching plans as the DW-20 JSON array. */
    private final ItemSupplyPlanMapper itemSupplyPlanMapper;

    /**
     * Creates the service.
     *
     * @param client               lists the item supply plan ids and reads each plan from NetSuite
     * @param quantityFilter       checks the operator and keeps the plans whose lines match
     * @param itemSupplyPlanMapper writes the matching plans as the DW-20 JSON array
     */
    public ItemSupplyPlanSearchService(NetsuiteRestClient client,
                                       ItemSupplyPlanQuantityFilter quantityFilter,
                                       ItemSupplyPlanMapper itemSupplyPlanMapper) {
        this.client = client;
        this.quantityFilter = quantityFilter;
        this.itemSupplyPlanMapper = itemSupplyPlanMapper;
    }

    /**
     * Runs flow {@code get:/items:netsuite-api-config} for the {@code quantity} and
     * {@code operator} query parameters.
     *
     * <p>The operator is checked first, and an unsupported one ends the call before any NetSuite
     * request. Every item supply plan id is then listed with no {@code q} condition, each plan is
     * read with {@code expandSubResources=true} in id order, the plans with at least one
     * {@code order.items} line whose quantity satisfies {@code <line quantity> <operator> <quantity>}
     * are kept once each in that order (FB-NS-01), and the kept plans are written as the JSON array
     * of {@code api/items-response.json}. {@code Get items completes successfully.} is logged at
     * INFO once the array is written.
     *
     * @param quantity the {@code quantity} query parameter, RAML default {@code 0} applied by the
     *                 caller
     * @param operator the {@code operator} query parameter, RAML default {@code GREATER_THAN}
     *                 applied by the caller; one of {@code LESS_THAN}, {@code GREATER_THAN},
     *                 {@code EQUAL_TO}, {@code LESS_THAN_OR_EQUAL_TO} and
     *                 {@code GREATER_THAN_OR_EQUAL_TO}
     * @return the UTF-8 bytes of the JSON array of the matching plans, unchanged from
     *         {@link ItemSupplyPlanMapper#toJson(List)}; {@code []} when no plan matches or none
     *         exists
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException
     *         if {@code operator} is {@code null} or not one of the five RAML enum names; NetSuite
     *         is not called (D-399)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects a request again after one re-authenticated retry (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers 429 (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or a timeout ends a call (D-020)
     * @throws RuntimeException any other exception of the client, the filter or the mapper,
     *         unchanged
     */
    public byte[] getItems(int quantity, String operator) {
        // DW-19 (:56-66): the operator of the always-applied quantity criterion (D-399).
        quantityFilter.requireOperator(operator);

        // netsuite:search ITEM_SUPPLY_PLAN_BASIC (:68): every plan id, no q condition (FB-NS-01).
        List<String> ids = client.queryIds(RECORD_TYPE, null);

        // One expanded instance read per id, in id order (D-016).
        List<JsonNode> plans = new ArrayList<>(ids.size());
        for (String id : ids) {
            plans.add(client.getRecord(RECORD_TYPE, id, true));
        }

        // DW-19 (:56-66): plans with at least one matching order.items line (FB-NS-01).
        List<JsonNode> matching = quantityFilter.itemSupplyPlans(plans, quantity, operator);

        // DW-20 (:70-73): payload map $.
        byte[] body = itemSupplyPlanMapper.toJson(matching);

        // logger (:76).
        LOG.info("Get items completes successfully.");
        return body;
    }
}
