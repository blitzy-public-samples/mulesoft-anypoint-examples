package com.mulesoft.examples.netsuite_data_retrieval.client;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * NetSuite REST record-API client contract: paged record collection queries, record instance reads,
 * and the customer create and delete of the live-test fixture, over the OAuth 2.0 M2M connection of
 * D-016. Every operation reports NetSuite authentication, rate-limit and connectivity failures as the
 * upstream exceptions of D-020.
 */
public interface NetsuiteRestClient {

    /**
     * Returns the ids of the records listed by the record collection query
     * {@code GET /services/rest/record/v1/{recordType}}, in collection order, across all pages.
     *
     * <p>Pages are requested with {@code limit=1000} and an {@code offset} that advances by each page's
     * {@code count} while the page reports {@code hasMore}. A failure on any page ends the query with no
     * partial result. An empty collection yields an empty list.
     *
     * <pre>{@code
     * List<String> ids = client.queryIds("customer", "companyname START_WITH \"Acme\"");
     * }</pre>
     *
     * @param recordType the REST record type id: {@code customer}, {@code itemsupplyplan} or
     *                   {@code opportunity}
     * @param q          a REST record-collection filter sent as the {@code q} query parameter, such as
     *                   {@code companyname START_WITH "Acme"}; {@code null} sends no filter and lists
     *                   the whole collection
     * @return the record ids in collection order, never {@code null}
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authentication and retry
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers HTTP 429; the exception carries the vendor {@code Retry-After} value
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or does not answer in time; the call is not retried
     */
    List<String> queryIds(String recordType, String q);

    /**
     * Returns the body of one record instance, {@code GET /services/rest/record/v1/{recordType}/{id}},
     * as parsed JSON.
     *
     * <p>With {@code expandSubResources} set, the request carries {@code expandSubResources=true} and the
     * body includes the expanded sublists and subrecords: the {@code order.items[].quantity} lines of an
     * {@code itemsupplyplan} (FB-NS-01) and the address subrecords of an {@code opportunity}
     * (FB-NS-03). Floating-point numbers are {@link java.math.BigDecimal}-backed nodes.
     *
     * <pre>{@code
     * JsonNode plan = client.getRecord("itemsupplyplan", "7", true);
     * }</pre>
     *
     * @param recordType         the REST record type id: {@code customer}, {@code itemsupplyplan} or
     *                           {@code opportunity}
     * @param id                 the record's internal id, as returned by {@link #queryIds(String, String)}
     * @param expandSubResources {@code true} to expand the record's sublists and subrecords,
     *                           {@code false} to read its body fields only
     * @return the record instance body, never {@code null}
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authentication and retry
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers HTTP 429; the exception carries the vendor {@code Retry-After} value
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or does not answer in time; the call is not retried
     */
    JsonNode getRecord(String recordType, String id, boolean expandSubResources);

    /**
     * Creates a customer record, {@code POST /services/rest/record/v1/customer} with the body
     * {@code {"companyname": <companyName>, "subsidiary": {"id": <subsidiaryId>}}}, and returns the
     * internal id NetSuite assigns to it. Used only by the Tier 2B live-test fixture.
     *
     * @param companyName  the company name of the new customer
     * @param subsidiaryId the internal id of the customer's subsidiary
     * @return the internal id of the created customer
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authentication and retry
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers HTTP 429; the exception carries the vendor {@code Retry-After} value
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or does not answer in time; the request is not re-sent
     */
    String createCustomer(String companyName, String subsidiaryId);

    /**
     * Deletes the customer record with the given internal id,
     * {@code DELETE /services/rest/record/v1/customer/{id}}. Used only by the Tier 2B live-test
     * fixture.
     *
     * @param id the internal id of the customer to delete
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException
     *         if NetSuite rejects authentication again after one re-authentication and retry
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException
     *         if NetSuite answers HTTP 429; the exception carries the vendor {@code Retry-After} value
     * @throws com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException
     *         if NetSuite cannot be reached or does not answer in time; the request is not re-sent
     */
    void deleteCustomer(String id);
}
