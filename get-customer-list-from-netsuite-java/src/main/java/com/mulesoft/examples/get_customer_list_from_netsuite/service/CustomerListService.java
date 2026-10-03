package com.mulesoft.examples.get_customer_list_from_netsuite.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteRestClient;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.CustomerHtmlMapper;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.CustomerPageRenderer;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.SuiteQlQueryBuilder;

/**
 * Orchestrates {@code get-customer-list-from-netsuiteFlow}
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:7-36]: filters
 * requests without {@code lastName}, queries NetSuite customers by last-name prefix (D-017) and
 * renders the customer page (D-056).
 *
 * <p>The flow's processors map to {@link #getCustomerListFromNetsuiteFlow(String)} in this order:
 * <ul>
 *   <li>{@code expression-filter} (:9): a {@code null} last name ends the flow with an empty
 *       result; every other value, the empty string included, passes;</li>
 *   <li>{@code netsuite:query-records} (:10): {@link SuiteQlQueryBuilder#customersByLastName(String)}
 *       builds the SuiteQL request with the bound parameter {@code <lastName>%} (D-017), and
 *       {@link NetsuiteRestClient#query(SuiteQlQueryBuilder.SuiteQlRequest)} returns the rows of
 *       every page (D-016, D-020);</li>
 *   <li>DW-04 {@code dw:transform-message} (:11-33): {@link CustomerHtmlMapper#toRows(List)} renders
 *       the rows, or the {@code No customers found} row for an empty result;</li>
 *   <li>{@code object-to-string-transformer} (:34) and the SC-09 {@code parse-template} (:35):
 *       {@link CustomerPageRenderer#render(String)} inserts that text into
 *       {@code customer/index.html} (D-056).</li>
 * </ul>
 *
 * <p>The page is returned as UTF-8 bytes, the application encoding of
 * {@code get-customer-list-from-netsuite/src/main/app/mule-deploy.properties}. This class sets no
 * header and no media type; the controller writes the bytes with no {@code Content-Type} (D-066).
 * The flow defines no exception strategy and no logger; this class catches nothing and logs
 * nothing, and every exception of a collaborator reaches the caller unchanged (D-020).
 *
 * <p>The bean holds no mutable state; concurrent requests share its collaborators.
 *
 * <pre>{@code
 * byte[] page = customerListService.getCustomerListFromNetsuiteFlow("Ab");
 * // the customer/index.html page listing every customer whose last name starts with "Ab"
 * byte[] all = customerListService.getCustomerListFromNetsuiteFlow("");
 * // the page listing every customer: the bound parameter is "%"
 * byte[] filtered = customerListService.getCustomerListFromNetsuiteFlow(null);
 * // an empty array; NetSuite is not called
 * }</pre>
 */
@Service
public class CustomerListService {

    /** Sends the SuiteQL query to NetSuite and returns the rows of every page. */
    private final NetsuiteRestClient client;

    /** Builds the SuiteQL request of the customer list query. */
    private final SuiteQlQueryBuilder queryBuilder;

    /** Renders the rows as the DW-04 XML text. */
    private final CustomerHtmlMapper htmlMapper;

    /** Inserts the DW-04 text into the {@code customer/index.html} template. */
    private final CustomerPageRenderer pageRenderer;

    /**
     * Creates the service.
     *
     * @param client       sends the SuiteQL query to NetSuite
     * @param queryBuilder builds the SuiteQL request from the last name
     * @param htmlMapper   renders the rows as the DW-04 XML text
     * @param pageRenderer renders the customer page from the DW-04 text
     */
    public CustomerListService(NetsuiteRestClient client,
                               SuiteQlQueryBuilder queryBuilder,
                               CustomerHtmlMapper htmlMapper,
                               CustomerPageRenderer pageRenderer) {
        this.client = client;
        this.queryBuilder = queryBuilder;
        this.htmlMapper = htmlMapper;
        this.pageRenderer = pageRenderer;
    }

    /**
     * Runs flow {@code get-customer-list-from-netsuiteFlow} for the {@code lastName} query parameter.
     *
     * <p>A {@code null} {@code lastName} returns an empty array, and neither NetSuite nor any mapper
     * is called (filter :9). Any other value, the empty string included, is passed unchanged to
     * {@link SuiteQlQueryBuilder#customersByLastName(String)}, which binds {@code lastName + "%"}
     * (D-017). The rows NetSuite returns are rendered by {@link CustomerHtmlMapper#toRows(List)}, the
     * result is inserted into the template by {@link CustomerPageRenderer#render(String)} (D-056), and
     * the page is returned encoded as UTF-8.
     *
     * @param lastName the {@code lastName} query parameter, or {@code null} when the request has none
     * @return an empty array when {@code lastName} is {@code null}; otherwise the rendered
     *         {@code customer/index.html} page encoded as UTF-8
     * @throws com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamAuthenticationException
     *         if NetSuite rejects the request again after one re-authenticated retry, or rejects the
     *         token request (D-020)
     * @throws com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamRateLimitException
     *         if NetSuite or its token endpoint answers 429 (D-020)
     * @throws com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException
     *         if NetSuite or its token endpoint cannot be reached or a timeout ends the call (D-020)
     * @throws RuntimeException any other exception of the client, the query builder or a mapper,
     *         unchanged
     */
    public byte[] getCustomerListFromNetsuiteFlow(String lastName) {
        // expression-filter (:9): a request without lastName ends the flow with an empty body.
        if (lastName == null) {
            return new byte[0];
        }
        // netsuite:query-records (:10): SuiteQL with the bound parameter <lastName>% (D-017).
        SuiteQlQueryBuilder.SuiteQlRequest request = queryBuilder.customersByLastName(lastName);
        List<Map<String, Object>> rows = client.query(request);
        // DW-04 (:11-33): the customer rows, or the "No customers found" row.
        String rowsXml = htmlMapper.toRows(rows);
        // object-to-string-transformer (:34) and parse-template (:35), SC-09 (D-056).
        String page = pageRenderer.render(rowsXml);
        return page.getBytes(StandardCharsets.UTF_8);
    }
}
