package com.mulesoft.examples.netsuite_data_retrieval.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.netsuite_data_retrieval.client.NetsuiteRestClient;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.OpportunityMapper;
import com.mulesoft.examples.netsuite_data_retrieval.mapper.RecordQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs flow {@code get:/oportunities:netsuite-api-config}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:79-106], which the APIkit router maps to the
 * RAML resource {@code /opportunities} (:16). {@code OpportunitiesController} answers
 * {@code GET /api/opportunities} through {@link #getOportunities(String)}, whose name keeps the flow's
 * spelling (D-068).
 *
 * <p>The flow's processors map to {@link #getOportunities(String)} in this order:
 * <ul>
 *   <li>DW-21 {@code dw:set-payload} (:81-95): {@link RecordQueryBuilder#opportunities(String)}
 *       builds the record collection condition {@code title START_WITH "<title>"}, present only when
 *       the {@code title} query parameter is not {@code null} (D-016);</li>
 *   <li>{@code netsuite:search searchRecord="OPPORTUNITY"} (:97): a record collection query,
 *       {@link NetsuiteRestClient#queryIds(String, String)} on record type {@code opportunity}, lists
 *       the matching ids across every page; then one instance read per id,
 *       {@link NetsuiteRestClient#getRecord(String, String, boolean)} with sub-resources expanded,
 *       returns each opportunity with its shipping and billing address subrecords (D-016,
 *       FB-NS-03);</li>
 *   <li>DW-22 {@code dw:set-payload} (:99-103): {@link OpportunityMapper#toJson(List)} writes the
 *       records as the JSON array of SOAP-shaped opportunities, {@code []} when no id matches;</li>
 *   <li>{@code logger} (:105): one INFO entry {@code Get oportunities completes successfully.}, the
 *       original text with its spelling (D-068), written only after the mapper returns.</li>
 * </ul>
 *
 * <p>The instance reads run one after another on the calling thread, in the order of the ids the
 * collection query returns, and the mapper receives the records in that order. The flow defines no
 * exception strategy. This class catches nothing, retries nothing and returns no partial result. A
 * failed instance read ends the whole search, and every exception of a collaborator reaches the
 * caller unchanged, where {@code GlobalExceptionHandler} answers it (FB-NS-06, D-020).
 *
 * <p>The bean holds no mutable state; concurrent requests share its collaborators.
 *
 * <pre>{@code
 * byte[] matching = opportunitySearchService.getOportunities("Big");
 * // q = title START_WITH "Big": the opportunities whose title starts with "Big"
 * byte[] all = opportunitySearchService.getOportunities(null);
 * // no q: every opportunity
 * opportunitySearchService.getOportunities("Q3 \"big\" deal");
 * // throws BadRequestException before NetSuite is called (FB-NS-06)
 * }</pre>
 */
@Service
public class OpportunitySearchService {

    /** Logger of the flow's {@code logger} processor (:105). */
    private static final Logger LOG = LoggerFactory.getLogger(OpportunitySearchService.class);

    /** NetSuite REST record type id of the opportunity record (D-016). */
    private static final String RECORD_TYPE = "opportunity";

    /** Runs the opportunity collection query and the instance reads against NetSuite. */
    private final NetsuiteRestClient client;

    /** Builds the DW-21 record collection condition from the {@code title} query parameter. */
    private final RecordQueryBuilder recordQueryBuilder;

    /** Writes the DW-22 JSON array of opportunities. */
    private final OpportunityMapper opportunityMapper;

    /**
     * Creates the service.
     *
     * @param client             runs the opportunity collection query and the instance reads
     * @param recordQueryBuilder builds the record collection condition from the {@code title}
     * @param opportunityMapper  writes the opportunity instances as the JSON response array
     */
    public OpportunitySearchService(NetsuiteRestClient client,
                                    RecordQueryBuilder recordQueryBuilder,
                                    OpportunityMapper opportunityMapper) {
        this.client = client;
        this.recordQueryBuilder = recordQueryBuilder;
        this.opportunityMapper = opportunityMapper;
    }

    /**
     * Runs flow {@code get:/oportunities:netsuite-api-config} for the {@code title} query parameter
     * of {@code GET /api/opportunities} (D-068).
     *
     * <ol>
     *   <li>{@link RecordQueryBuilder#opportunities(String)} turns {@code title} into the condition
     *       {@code title START_WITH "<title>"}; {@code null} gives no condition, and {@code ""} is a
     *       value like any other (DW-21).</li>
     *   <li>{@link NetsuiteRestClient#queryIds(String, String)} lists the ids of record type
     *       {@code opportunity} that match the condition, or every opportunity id without one.</li>
     *   <li>{@link NetsuiteRestClient#getRecord(String, String, boolean)} reads each id in list order,
     *       one after another, with {@code expandSubResources} {@code true} (FB-NS-03).</li>
     *   <li>{@link OpportunityMapper#toJson(List)} writes the records in that order (DW-22).</li>
     *   <li>The INFO entry {@code Get oportunities completes successfully.} is logged once.</li>
     * </ol>
     *
     * @param title the {@code title} query parameter as received, or {@code null} when the request has
     *              none
     * @return the UTF-8 bytes of the JSON array the mapper writes, unchanged; {@code []} when no
     *         opportunity matches
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException
     *         if {@code title} contains a double quote {@code "}; NetSuite is not called (FB-NS-06)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authenticated retry of the query or
     *         of any instance read (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers 429 to the query or to any instance read (D-020)
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or a timeout ends the query or any instance read (D-020)
     * @throws RuntimeException any other exception of the client or the mapper, unchanged; nothing is
     *         logged
     */
    public byte[] getOportunities(String title) {
        // DW-21 (:81-95): the title criterion exists only when title is not null (D-016, FB-NS-06).
        String q = recordQueryBuilder.opportunities(title).orElse(null);
        // netsuite:search OPPORTUNITY (:97): the paged collection query, then one expanded read per id.
        List<String> ids = client.queryIds(RECORD_TYPE, q);
        List<JsonNode> records = new ArrayList<>(ids.size());
        for (String id : ids) {
            records.add(client.getRecord(RECORD_TYPE, id, true));
        }
        // DW-22 (:99-103): payload map $ in the committed example's opportunity shape.
        byte[] body = opportunityMapper.toJson(records);
        // logger (:105): the original message text (D-068).
        LOG.info("Get oportunities completes successfully.");
        return body;
    }
}
