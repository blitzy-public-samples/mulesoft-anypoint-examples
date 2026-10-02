/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import java.util.List;
import java.util.Map;
// Objects supplies the null checks of lastName and of the request components.
import java.util.Objects;

import org.springframework.stereotype.Component;

/**
 * Builds the NetSuite REST SuiteQL request of the customer list query of
 * {@code get-customer-list-from-netsuiteFlow}: the email, first name and last name of every
 * customer whose last name starts with a given value, ordered by last name ascending.
 *
 * <p>The query text is always {@link #CUSTOMERS_BY_LAST_NAME}. The last name reaches NetSuite only
 * as the bound parameter {@code <lastName>%} in {@link SuiteQlRequest#params()} (D-017). The table
 * and column names are those listed under FB-NS-09; the case and order semantics of {@code LIKE}
 * are those listed under FB-NS-07.
 *
 * <p>This class builds data only. The NetSuite client sends each request as
 * {@code POST <path>?limit=<limit>&offset=<offset>} with the {@link SuiteQlRequest#headers()} and
 * the JSON body {@code {"q": <q>, "params": <params>}}, and requests the next page through
 * {@link SuiteQlRequest#withOffset(int)}.
 *
 * <pre>{@code
 * SuiteQlRequest first = builder.customersByLastName("Smith");
 * // first.params() is ["Smith%"], first.offset() is 0
 * SuiteQlRequest next = first.withOffset(first.offset() + count);
 * }</pre>
 */
@Component
public class SuiteQlQueryBuilder {

    /** Path of the SuiteQL resource of the NetSuite REST API. */
    public static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    /** Number of rows requested per page, sent as the {@code limit} query parameter. */
    public static final int PAGE_LIMIT = 1000;

    /** Name of the header that selects the SuiteQL response mode. */
    public static final String PREFER_HEADER = "Prefer";

    /** Value of {@link #PREFER_HEADER} sent with every SuiteQL request. */
    public static final String PREFER_TRANSIENT = "transient";

    /**
     * SuiteQL text of the customer list query. Its single {@code ?} placeholder takes the last name
     * pattern of {@link SuiteQlRequest#params()} (D-017, FB-NS-09).
     */
    public static final String CUSTOMERS_BY_LAST_NAME =
            "SELECT email, firstname, lastname FROM customer WHERE lastname LIKE ? ORDER BY lastname ASC";

    /**
     * Builds the SuiteQL request that lists customers whose last name starts with the given value.
     *
     * <p>The value is followed by a single {@code %} and is otherwise passed unchanged: it is not
     * trimmed, escaped or case-converted, and it appears only in {@link SuiteQlRequest#params()},
     * never in {@link SuiteQlRequest#q()}.
     *
     * @param lastName the last name prefix, possibly empty
     * @return the first page request: path {@link #SUITEQL_PATH}, limit {@link #PAGE_LIMIT},
     *         offset {@code 0}, header {@code Prefer: transient}, query
     *         {@link #CUSTOMERS_BY_LAST_NAME} and the single parameter {@code lastName + "%"}
     * @throws NullPointerException if {@code lastName} is {@code null}
     */
    public SuiteQlRequest customersByLastName(String lastName) {
        Objects.requireNonNull(lastName, "lastName");
        return new SuiteQlRequest(
                SUITEQL_PATH,
                PAGE_LIMIT,
                0,
                Map.of(PREFER_HEADER, PREFER_TRANSIENT),
                CUSTOMERS_BY_LAST_NAME,
                List.of(lastName + "%"));
    }

    /**
     * One immutable page request of a SuiteQL query.
     *
     * <p>The headers and parameters are unmodifiable copies of the values passed in:
     * {@code put} and {@code add} on them throw {@link UnsupportedOperationException}.
     *
     * @param path    the resource path, sent before the query string
     * @param limit   the page size, sent as the {@code limit} query parameter; greater than 0
     * @param offset  the index of the first row of the page, sent as the {@code offset} query
     *                parameter; 0 or greater
     * @param headers the request headers, without {@code null} names or values
     * @param q       the SuiteQL text, sent as the {@code q} body member
     * @param params  the bound parameter values in placeholder order, sent as the {@code params}
     *                body member; without {@code null} elements
     */
    public record SuiteQlRequest(
            String path,
            int limit,
            int offset,
            Map<String, String> headers,
            String q,
            List<String> params) {

        /**
         * Validates the components and copies {@code headers} and {@code params} into
         * unmodifiable collections.
         *
         * @param path                      the resource path
         * @param limit                     the page size
         * @param offset                    the index of the first row of the page
         * @param headers                   the request headers
         * @param q                         the SuiteQL text
         * @param params                    the bound parameter values in placeholder order
         * @throws NullPointerException     if {@code path}, {@code q}, {@code headers} or
         *                                  {@code params} is {@code null}, or if {@code headers} or
         *                                  {@code params} holds a {@code null} entry
         * @throws IllegalArgumentException if {@code limit} is not greater than 0 or
         *                                  {@code offset} is negative
         */
        public SuiteQlRequest {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(q, "q");
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be greater than 0: " + limit);
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative: " + offset);
            }
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            params = List.copyOf(Objects.requireNonNull(params, "params"));
        }

        /**
         * Returns the request for the page that starts at the given offset; every other component
         * is unchanged.
         *
         * @param offset the index of the first row of the page; 0 or greater
         * @return a request equal to this one except for {@code offset}
         * @throws IllegalArgumentException if {@code offset} is negative
         */
        public SuiteQlRequest withOffset(int offset) {
            return new SuiteQlRequest(path, limit, offset, headers, q, params);
        }
    }
}
