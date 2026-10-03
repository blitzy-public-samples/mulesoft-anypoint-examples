package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.SuiteQlQueryBuilder.SuiteQlRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of {@link SuiteQlQueryBuilder}, the SuiteQL form of the customer list DSQL query of
 * {@code get-customer-list-from-netsuiteFlow} [get-customer-list-from-netsuite.xml:10] (D-017).
 *
 * <p>Each test calls the builder directly, with no Spring application context, no mocks and no file or
 * network I/O, and asserts
 * <ul>
 *   <li>the first page request for the last name {@code a}: path {@value #EXPECTED_PATH}, limit 1000,
 *       offset 0, the single header {@code Prefer: transient}, the query text {@link #EXPECTED_Q} with one
 *       {@code ?} placeholder and the single parameter {@code a%};</li>
 *   <li>the next page request returned by {@link SuiteQlRequest#withOffset(int)}, which differs from the
 *       first page request in its offset only and leaves the first page request unchanged;</li>
 *   <li>the last name is sent as a bound parameter: last names holding single quotes, double quotes,
 *       {@code %} or SQL text appear only in {@link SuiteQlRequest#params()}, and the query text is the
 *       same for every last name;</li>
 *   <li>a {@code null} last name, a negative offset, a limit below 1 and {@code null} request components
 *       are rejected, and the headers and parameters of a request are unmodifiable copies.</li>
 * </ul>
 * These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
class SuiteQlQueryBuilderTest {

    /** Path of the SuiteQL resource of the NetSuite REST API. */
    private static final String EXPECTED_PATH = "/services/rest/query/v1/suiteql";

    /** SuiteQL text of the customer list query, with one bound parameter placeholder (D-017). */
    private static final String EXPECTED_Q =
            "SELECT email, firstname, lastname FROM customer WHERE lastname LIKE ? ORDER BY lastname ASC";

    /** Number of rows requested per page. */
    private static final int EXPECTED_LIMIT = 1000;

    /** Offset of the second page of a query whose pages hold {@link #EXPECTED_LIMIT} rows. */
    private static final int SECOND_PAGE_OFFSET = 1000;

    /**
     * Last name the original integration test queries with
     * [get-customer-list-from-netsuite/src/test/java/org/mule/examples/GetCustomersListFromNetsuiteIT.java:33].
     */
    private static final String ORIGINAL_LAST_NAME = "a";

    /** The builder under test. */
    private final SuiteQlQueryBuilder builder = new SuiteQlQueryBuilder();

    /**
     * Asserts the request for the last name {@code a}: path {@value #EXPECTED_PATH}, limit 1000, offset 0,
     * exactly the header {@code Prefer: transient}, the query text {@link #EXPECTED_Q} with exactly one
     * {@code ?}, and the single parameter {@code a%}. Also asserts the public constants of the builder hold
     * the same values.
     */
    @Test
    void customersByLastNameBuildsFirstPageRequest() {
        SuiteQlRequest request = builder.customersByLastName(ORIGINAL_LAST_NAME);

        assertThat(request.path()).isEqualTo(EXPECTED_PATH);
        assertThat(request.limit()).isEqualTo(EXPECTED_LIMIT);
        assertThat(request.offset()).isZero();
        assertThat(request.headers()).hasSize(1).isEqualTo(Map.of("Prefer", "transient"));
        assertThat(request.q()).isEqualTo(EXPECTED_Q);
        assertThat(request.q().chars().filter(character -> character == '?').count()).isEqualTo(1L);
        assertThat(request.params()).isEqualTo(List.of("a%"));

        assertThat(SuiteQlQueryBuilder.SUITEQL_PATH).isEqualTo(EXPECTED_PATH);
        assertThat(SuiteQlQueryBuilder.PAGE_LIMIT).isEqualTo(EXPECTED_LIMIT);
        assertThat(SuiteQlQueryBuilder.PREFER_HEADER).isEqualTo("Prefer");
        assertThat(SuiteQlQueryBuilder.PREFER_TRANSIENT).isEqualTo("transient");
        assertThat(SuiteQlQueryBuilder.CUSTOMERS_BY_LAST_NAME).isEqualTo(EXPECTED_Q);
    }

    /**
     * Asserts {@code withOffset(1000)} on the request for the last name {@code a} returns a request with
     * offset 1000 whose path, limit, headers, query text and parameters equal those of the first page
     * request, and that the first page request keeps offset 0.
     */
    @Test
    void withOffsetChangesOnlyTheOffset() {
        SuiteQlRequest first = builder.customersByLastName(ORIGINAL_LAST_NAME);

        SuiteQlRequest next = first.withOffset(SECOND_PAGE_OFFSET);

        assertThat(next.offset()).isEqualTo(SECOND_PAGE_OFFSET);
        assertThat(next.path()).isEqualTo(first.path());
        assertThat(next.limit()).isEqualTo(first.limit());
        assertThat(next.headers()).isEqualTo(first.headers());
        assertThat(next.q()).isEqualTo(first.q());
        assertThat(next.params()).isEqualTo(first.params());
        assertThat(first.offset()).isZero();
        assertThat(next.withOffset(0)).isEqualTo(first);
    }

    /**
     * Asserts the last name is sent as a bound parameter: the single parameter is the last name followed by
     * {@code %}, the query text does not contain the last name, and the query text equals
     * {@link #EXPECTED_Q}, the text built for the last name {@code a}.
     *
     * @param lastName a last name holding a single quote, a double quote, a {@code %} or SQL text
     */
    @ParameterizedTest
    @ValueSource(strings = {"O'Brien", "a\"b", "50%", "x' OR '1'='1"})
    void lastNameIsSentOnlyAsBoundParameter(String lastName) {
        SuiteQlRequest request = builder.customersByLastName(lastName);

        assertThat(request.params()).isEqualTo(List.of(lastName + "%"));
        assertThat(request.q()).doesNotContain(lastName);
        assertThat(request.q()).isEqualTo(EXPECTED_Q);
        assertThat(request.q()).isEqualTo(builder.customersByLastName(ORIGINAL_LAST_NAME).q());
    }

    /**
     * Asserts an empty last name, which the {@code lastName != null} filter of the flow accepts
     * [get-customer-list-from-netsuite.xml:9], yields the single parameter {@code %} and the query text
     * {@link #EXPECTED_Q}.
     */
    @Test
    void emptyLastNameYieldsWildcardParameter() {
        SuiteQlRequest request = builder.customersByLastName("");

        assertThat(request.params()).isEqualTo(List.of("%"));
        assertThat(request.q()).isEqualTo(EXPECTED_Q);
        assertThat(request.offset()).isZero();
    }

    /**
     * Asserts the last name reaches the parameter unchanged apart from the trailing {@code %}: it is not
     * trimmed, escaped or case-converted.
     *
     * @param lastName a last name with surrounding spaces, mixed case, an underscore or a backslash
     */
    @ParameterizedTest
    @ValueSource(strings = {" McDonald ", "sMiTh", "de_la", "back\\slash"})
    void lastNameIsPassedUnchanged(String lastName) {
        SuiteQlRequest request = builder.customersByLastName(lastName);

        assertThat(request.params()).containsExactly(lastName + "%");
        assertThat(request.q()).isEqualTo(EXPECTED_Q);
    }

    /**
     * Asserts a {@code null} last name is rejected with a {@link NullPointerException} whose message is
     * {@code lastName}.
     */
    @Test
    void nullLastNameIsRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> builder.customersByLastName(null))
                .withMessage("lastName");
    }

    /**
     * Asserts {@code withOffset} with a negative offset is rejected with an
     * {@link IllegalArgumentException} that names the offset, and leaves the request unchanged.
     */
    @Test
    void negativeOffsetIsRejected() {
        SuiteQlRequest first = builder.customersByLastName(ORIGINAL_LAST_NAME);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> first.withOffset(-1))
                .withMessage("offset must not be negative: -1");
        assertThat(first.offset()).isZero();
    }

    /**
     * Asserts a request with a limit below 1 is rejected with an {@link IllegalArgumentException} that
     * names the limit.
     *
     * @param limit a limit of 0 or less
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void limitBelowOneIsRejected(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SuiteQlRequest(
                        EXPECTED_PATH, limit, 0, Map.of("Prefer", "transient"), EXPECTED_Q, List.of("a%")))
                .withMessage("limit must be greater than 0: " + limit);
    }

    /**
     * Asserts a request with a {@code null} path, query text, header map or parameter list, or with a
     * {@code null} header value or parameter element, is rejected with a {@link NullPointerException};
     * a {@code null} component yields the component name as the message.
     */
    @Test
    void nullComponentsAreRejected() {
        Map<String, String> headers = Map.of("Prefer", "transient");
        List<String> params = List.of("a%");

        assertThatNullPointerException()
                .isThrownBy(() -> new SuiteQlRequest(null, EXPECTED_LIMIT, 0, headers, EXPECTED_Q, params))
                .withMessage("path");
        assertThatNullPointerException()
                .isThrownBy(() -> new SuiteQlRequest(EXPECTED_PATH, EXPECTED_LIMIT, 0, headers, null, params))
                .withMessage("q");
        assertThatNullPointerException()
                .isThrownBy(() -> new SuiteQlRequest(EXPECTED_PATH, EXPECTED_LIMIT, 0, null, EXPECTED_Q, params))
                .withMessage("headers");
        assertThatNullPointerException()
                .isThrownBy(() -> new SuiteQlRequest(EXPECTED_PATH, EXPECTED_LIMIT, 0, headers, EXPECTED_Q, null))
                .withMessage("params");

        Map<String, String> nullHeaderValue = new HashMap<>();
        nullHeaderValue.put("Prefer", null);
        assertThatNullPointerException().isThrownBy(() -> new SuiteQlRequest(
                EXPECTED_PATH, EXPECTED_LIMIT, 0, nullHeaderValue, EXPECTED_Q, params));

        List<String> nullParameter = new ArrayList<>();
        nullParameter.add(null);
        assertThatNullPointerException().isThrownBy(() -> new SuiteQlRequest(
                EXPECTED_PATH, EXPECTED_LIMIT, 0, headers, EXPECTED_Q, nullParameter));
    }

    /**
     * Asserts the headers and parameters of a request are copies of the collections passed in, unaffected
     * by later changes to those collections, and that {@code put} and {@code add} on them, on a built
     * request and on a request returned by {@code withOffset}, throw
     * {@link UnsupportedOperationException}.
     */
    @Test
    void headersAndParamsAreUnmodifiableCopies() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "transient");
        List<String> params = new ArrayList<>();
        params.add("a%");

        SuiteQlRequest request = new SuiteQlRequest(EXPECTED_PATH, EXPECTED_LIMIT, 0, headers, EXPECTED_Q, params);
        headers.put("X-Extra", "1");
        params.add("b%");

        assertThat(request.headers()).isEqualTo(Map.of("Prefer", "transient"));
        assertThat(request.params()).isEqualTo(List.of("a%"));
        assertThat(request).isEqualTo(builder.customersByLastName(ORIGINAL_LAST_NAME));

        SuiteQlRequest built = builder.customersByLastName(ORIGINAL_LAST_NAME);
        SuiteQlRequest next = built.withOffset(SECOND_PAGE_OFFSET);
        for (SuiteQlRequest candidate : List.of(request, built, next)) {
            assertThatThrownBy(() -> candidate.headers().put("X-Extra", "1"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> candidate.params().add("b%"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
