package com.mulesoft.examples.netsuite_data_retrieval.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.CustomerMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.RecordQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers {@code GET /api/customers} as flow {@code get:/customers:netsuite-api-config}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:25-53], by a NetSuite REST record collection
 * query on {@code customer} followed by one instance read per returned id (D-016).
 *
 * <p>The flow's processors map to {@link #getCustomers(String)} in this order:
 * <ul>
 *   <li>DW-17 {@code dw:transform-message} (:27-39): {@link RecordQueryBuilder#customers(String)}
 *       builds the condition {@code companyname START_WITH "<name>"}, or no condition when
 *       {@code name} is {@code null}; a {@code name} holding {@code "} is refused (FB-NS-06);</li>
 *   <li>{@code netsuite:search searchRecord="CUSTOMER"} (:41):
 *       {@link NetsuiteRestClient#queryIds(String, String)} lists the matching customer ids across
 *       every page, then {@link NetsuiteRestClient#getRecord(String, String, boolean)} reads each
 *       customer body, one after another in id order, without sub-resource expansion (D-016);</li>
 *   <li>DW-18 {@code dw:transform-message} (:44-47): {@link CustomerMapper#toJson(List)} writes the
 *       JSON array of {@code payload map $};</li>
 *   <li>{@code logger} (:50): the INFO line {@code Get customers completes successfully.}</li>
 * </ul>
 *
 * <p>This class catches nothing, retries nothing and returns no partial result: every exception of a
 * collaborator reaches the caller unchanged, and a failed instance read fails the whole search
 * (D-016, D-020).
 *
 * <p>The bean holds no mutable state; concurrent requests share its collaborators.
 *
 * <pre>{@code
 * byte[] all = customerSearchService.getCustomers(null);
 * // every customer: the collection query is sent without q
 * byte[] acme = customerSearchService.getCustomers("Acme");
 * // the customers listed by q = companyname START_WITH "Acme"
 * customerSearchService.getCustomers("A\"B");
 * // throws BadRequestException before NetSuite is called (FB-NS-06)
 * }</pre>
 */
@Service
public class CustomerSearchService {

    /** Logger of the flow's {@code logger} processor (netsuite-api.xml:50). */
    private static final Logger LOG = LoggerFactory.getLogger(CustomerSearchService.class);

    /** NetSuite REST record type id of the customer record (D-016). */
    private static final String RECORD_TYPE = "customer";

    /** Runs the record collection query and the instance reads against NetSuite. */
    private final NetsuiteRestClient client;

    /** Builds the DW-17 collection condition from the {@code name} query parameter. */
    private final RecordQueryBuilder recordQueryBuilder;

    /** Writes the DW-18 JSON array of the customer instances. */
    private final CustomerMapper customerMapper;

    /**
     * Creates the service.
     *
     * @param client             runs the customer collection query and instance reads
     * @param recordQueryBuilder builds the collection condition from the {@code name} parameter
     * @param customerMapper     writes the JSON array of the customer instances
     */
    public CustomerSearchService(NetsuiteRestClient client,
                                 RecordQueryBuilder recordQueryBuilder,
                                 CustomerMapper customerMapper) {
        this.client = client;
        this.recordQueryBuilder = recordQueryBuilder;
        this.customerMapper = customerMapper;
    }

    /**
     * Runs flow {@code get:/customers:netsuite-api-config} for the {@code name} query parameter.
     *
     * <p>Steps, in this order:
     * <ol>
     *   <li>{@link RecordQueryBuilder#customers(String)} turns {@code name}, passed unchanged, into
     *       the condition {@code companyname START_WITH "<name>"}; a {@code null} {@code name} gives
     *       no condition and the empty string gives {@code companyname START_WITH ""} (DW-17).</li>
     *   <li>{@link NetsuiteRestClient#queryIds(String, String)} returns the ids of the
     *       {@code customer} collection, filtered by that condition or unfiltered without one, in
     *       collection order across every page.</li>
     *   <li>{@link NetsuiteRestClient#getRecord(String, String, boolean)} reads each id with
     *       {@code expandSubResources} {@code false}, one call after another in id order.</li>
     *   <li>{@link CustomerMapper#toJson(List)} writes the instances, in id order, as the DW-18 JSON
     *       array; no ids give {@code []}.</li>
     *   <li>The INFO line {@code Get customers completes successfully.} is logged once.</li>
     *   <li>The mapper's bytes are returned as they are.</li>
     * </ol>
     *
     * @param name the {@code name} query parameter as received, or {@code null} when the request has
     *             none
     * @return the UTF-8 bytes of the JSON array of the matching customers, in collection order
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException
     *         if {@code name} contains a double quote {@code "}; NetSuite is not called (FB-NS-06)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authenticated retry (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers HTTP 429 to the collection query or an instance read (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or does not answer in time (D-020)
     * @throws RuntimeException any other exception of the client, the query builder or the mapper,
     *         unchanged
     */
    public byte[] getCustomers(String name) {
        // DW-17 (:27-39): the companyName STARTS_WITH criterion, present only for a non-null name.
        String q = recordQueryBuilder.customers(name).orElse(null);

        // netsuite:search searchRecord="CUSTOMER" (:41): paged collection query, then instance reads.
        List<String> ids = client.queryIds(RECORD_TYPE, q);
        List<JsonNode> records = new ArrayList<>(ids.size());
        for (String id : ids) {
            records.add(client.getRecord(RECORD_TYPE, id, false));
        }

        // DW-18 (:44-47): payload map $ to JSON.
        byte[] body = customerMapper.toJson(records);

        // logger (:50).
        LOG.info("Get customers completes successfully.");
        return body;
    }
}
