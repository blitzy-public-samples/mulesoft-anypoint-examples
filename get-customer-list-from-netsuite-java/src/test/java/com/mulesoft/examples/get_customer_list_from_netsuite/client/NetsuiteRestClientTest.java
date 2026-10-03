package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamRateLimitException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.SuiteQlQueryBuilder;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import reactor.netty.http.client.HttpClient;

/**
 * Unit tests of {@link NetsuiteRestClient}, the NetSuite REST SuiteQL client that replaces
 * {@code netsuite:config} and {@code netsuite:query-records}
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4,10] (D-016, D-017).
 *
 * <p>Each test builds the client over a plain {@code WebClient} whose base URL is a local okhttp
 * {@link MockWebServer}, with no OAuth 2.0 exchange filter and no Spring application context. The
 * {@link NetsuiteRestClient.AuthorizedClientEvictor} is an {@link AtomicInteger} that counts evictions.
 * The tests assert:
 * <ul>
 *   <li>the SuiteQL request of {@link SuiteQlQueryBuilder#customersByLastName(String)} for {@code a}:
 *       {@code POST /services/rest/query/v1/suiteql?limit=1000&offset=<n>}, header
 *       {@code Prefer: transient} and the body {@code {"q": ..., "params": ["a%"]}}, with pages
 *       followed while {@code hasMore} is {@code true} (D-017);</li>
 *   <li>the failure modes of D-020: one eviction and one re-issued request after HTTP 401, then
 *       {@link UpstreamAuthenticationException} on a second 401; {@link UpstreamRateLimitException}
 *       carrying {@code Retry-After} after HTTP 429; {@link UpstreamUnavailableException} after exactly one
 *       request on a response timeout or a refused connection; any other error status unchanged as
 *       {@link WebClientResponseException};</li>
 *   <li>the token endpoint rule of D-404, applied by {@code execute} to an
 *       {@link OAuth2AuthorizationException} without any HTTP exchange.</li>
 * </ul>
 * Every request targets {@code localhost}.
 */
class NetsuiteRestClientTest {

    /** Upstream system name carried by every upstream exception. */
    private static final String UPSTREAM_SYSTEM = "NetSuite";

    /** SuiteQL text of the customer list query, with one bound parameter placeholder (D-017). */
    private static final String EXPECTED_Q =
            "SELECT email, firstname, lastname FROM customer WHERE lastname LIKE ? ORDER BY lastname ASC";

    /** JSON body of every SuiteQL request for the last name {@code a} (D-017). */
    private static final String EXPECTED_BODY = "{\"q\":\"" + EXPECTED_Q + "\",\"params\":[\"a%\"]}";

    /** Path and query string of the first SuiteQL page request. */
    private static final String FIRST_PAGE_PATH = "/services/rest/query/v1/suiteql?limit=1000&offset=0";

    /** Path and query string of the page request that follows a first page of two rows. */
    private static final String SECOND_PAGE_PATH = "/services/rest/query/v1/suiteql?limit=1000&offset=2";

    /** Response timeout of the test-only {@code WebClient} of the timeout case (D-587). */
    private static final Duration TEST_RESPONSE_TIMEOUT = Duration.ofMillis(500);

    /** Upper bound on the run time of the timeout case. */
    private static final Duration TIMEOUT_CASE_LIMIT = Duration.ofSeconds(10);

    /** Local HTTP server standing in for the NetSuite REST Web Services host. */
    private MockWebServer server;

    /** Number of {@link NetsuiteRestClient.AuthorizedClientEvictor#evict()} calls. */
    private AtomicInteger counter;

    /** Client under test, over a {@code WebClient} whose base URL is {@link #server}. */
    private NetsuiteRestClient client;

    /** Parses recorded request bodies and builds response bodies. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** First page request of the customer list query for the last name {@code a}. */
    private final SuiteQlQueryBuilder.SuiteQlRequest request = new SuiteQlQueryBuilder().customersByLastName("a");

    /**
     * Starts {@link #server}, resets {@link #counter} and builds {@link #client} over a plain
     * {@code WebClient} with the server's base URL. The server answers each request with the next
     * enqueued response, and with HTTP 404 once none is left: a request beyond those a test enqueues is
     * recorded and answered at once (D-587).
     *
     * @throws IOException if the server cannot start
     */
    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        QueueDispatcher dispatcher = new QueueDispatcher();
        dispatcher.setFailFast(true);
        server.setDispatcher(dispatcher);
        server.start();
        counter = new AtomicInteger();
        client = new NetsuiteRestClient(
                WebClient.builder().baseUrl(server.url("/").toString()).build(),
                counter::incrementAndGet,
                new ObjectMapper());
    }

    /**
     * Stops {@link #server} and closes its connections.
     *
     * @throws IOException if the server cannot stop
     */
    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /**
     * Two pages, the first with {@code "hasMore": true}: {@code query} returns the three rows of both
     * pages in order, sends two {@code POST} requests whose offsets are 0 and 2, each with
     * {@code Prefer: transient} and the bound-parameter body, and evicts nothing (D-017).
     *
     * @throws Exception if a body cannot be built or parsed, or a recorded request cannot be read
     */
    @Test
    void queryPagesWhileHasMoreAndSendsBoundSuiteQl() throws Exception {
        server.enqueue(jsonResponse(pageBody(2, true, 0, 3,
                item("ann.abbott@example.com", "Ann", "Abbott"),
                item("bob.adams@example.com", "Bob", "Adams"))));
        server.enqueue(jsonResponse(pageBody(1, false, 2, 3,
                item("cid.avery@example.com", "Cid", "Avery"))));

        List<Map<String, Object>> rows = client.query(request);

        assertEquals(3, rows.size());
        assertRow(rows.get(0), "ann.abbott@example.com", "Ann", "Abbott");
        assertRow(rows.get(1), "bob.adams@example.com", "Bob", "Adams");
        assertRow(rows.get(2), "cid.avery@example.com", "Cid", "Avery");

        assertEquals(2, server.getRequestCount());
        JsonNode expectedBody = mapper.readTree(EXPECTED_BODY);

        RecordedRequest first = server.takeRequest();
        assertEquals("POST", first.getMethod());
        assertEquals(FIRST_PAGE_PATH, first.getPath());
        assertEquals("transient", first.getHeader("Prefer"));
        assertEquals("application/json", first.getHeader(HttpHeaders.CONTENT_TYPE));
        assertEquals(expectedBody, mapper.readTree(first.getBody().readUtf8()));

        RecordedRequest second = server.takeRequest();
        assertEquals("POST", second.getMethod());
        assertEquals(SECOND_PAGE_PATH, second.getPath());
        assertEquals("transient", second.getHeader("Prefer"));
        assertEquals("application/json", second.getHeader(HttpHeaders.CONTENT_TYPE));
        assertEquals(expectedBody, mapper.readTree(second.getBody().readUtf8()));

        assertEquals(0, counter.get());
    }

    /**
     * HTTP 401, then a single page: the authorized client is evicted once, the same first page request
     * is sent a second time and its row is returned (D-020).
     *
     * @throws Exception if a body cannot be built or a recorded request cannot be read
     */
    @Test
    void unauthorizedThenSuccessEvictsOnceAndRetries() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(jsonResponse(pageBody(1, false, 0, 1,
                item("ann.abbott@example.com", "Ann", "Abbott"))));

        List<Map<String, Object>> rows = client.query(request);

        assertEquals(1, rows.size());
        assertRow(rows.get(0), "ann.abbott@example.com", "Ann", "Abbott");
        assertEquals(2, server.getRequestCount());
        assertEquals(1, counter.get());
        assertEquals(FIRST_PAGE_PATH, server.takeRequest().getPath());
        assertEquals(FIRST_PAGE_PATH, server.takeRequest().getPath());
    }

    /**
     * HTTP 401 on both attempts: {@link UpstreamAuthenticationException} after one eviction and two
     * requests, with no third request (D-020).
     */
    @Test
    void unauthorizedTwiceRaisesUpstreamAuthentication() {
        server.enqueue(new MockResponse().setResponseCode(401));
        server.enqueue(new MockResponse().setResponseCode(401));

        UpstreamAuthenticationException thrown =
                assertThrows(UpstreamAuthenticationException.class, () -> client.query(request));

        assertEquals(UPSTREAM_SYSTEM, thrown.getUpstreamSystem());
        assertEquals("NetSuite authentication failed", thrown.getMessage());
        assertEquals(2, server.getRequestCount());
        assertEquals(1, counter.get());
    }

    /**
     * HTTP 429 with {@code Retry-After: 30}: {@link UpstreamRateLimitException} carrying {@code 30}
     * after a single request and no eviction (D-020).
     */
    @Test
    void rateLimitedRaisesUpstreamRateLimitWithRetryAfter() {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader(HttpHeaders.RETRY_AFTER, "30"));

        UpstreamRateLimitException thrown =
                assertThrows(UpstreamRateLimitException.class, () -> client.query(request));

        assertEquals("30", thrown.getRetryAfter());
        assertEquals(UPSTREAM_SYSTEM, thrown.getUpstreamSystem());
        assertEquals("NetSuite rate limit exceeded", thrown.getMessage());
        assertEquals(1, server.getRequestCount());
        assertEquals(0, counter.get());
    }

    /**
     * A request that receives no response within the 500 ms Reactor Netty response timeout of a
     * test-only {@code WebClient} (D-587): {@link UpstreamUnavailableException} after exactly one request
     * and no eviction, with no retry (D-020). The run is bounded at 10 s.
     */
    @Test
    void responseTimeoutRaisesUpstreamUnavailableAfterOneAttempt() {
        NetsuiteRestClient timingOutClient = new NetsuiteRestClient(
                WebClient.builder()
                        .baseUrl(server.url("/").toString())
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create().responseTimeout(TEST_RESPONSE_TIMEOUT)))
                        .build(),
                counter::incrementAndGet,
                new ObjectMapper());
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        UpstreamUnavailableException thrown = assertTimeoutPreemptively(TIMEOUT_CASE_LIMIT,
                () -> assertThrows(UpstreamUnavailableException.class, () -> timingOutClient.query(request)));

        assertEquals(UPSTREAM_SYSTEM, thrown.getUpstreamSystem());
        assertEquals("NetSuite unavailable", thrown.getMessage());
        assertEquals(1, server.getRequestCount());
        assertEquals(0, counter.get());
    }

    /**
     * A base URL whose local port no longer accepts connections: {@link UpstreamUnavailableException}
     * and no eviction (D-020).
     *
     * @throws IOException if the second server cannot start or stop
     */
    @Test
    void refusedConnectionRaisesUpstreamUnavailable() throws IOException {
        MockWebServer closedServer = new MockWebServer();
        closedServer.start();
        String closedBaseUrl = closedServer.url("/").toString();
        closedServer.shutdown();
        NetsuiteRestClient refusedClient = new NetsuiteRestClient(
                WebClient.builder().baseUrl(closedBaseUrl).build(),
                counter::incrementAndGet,
                new ObjectMapper());

        UpstreamUnavailableException thrown =
                assertThrows(UpstreamUnavailableException.class, () -> refusedClient.query(request));

        assertEquals(UPSTREAM_SYSTEM, thrown.getUpstreamSystem());
        assertEquals(0, counter.get());
    }

    /**
     * HTTP 500: the {@link WebClientResponseException} with status 500 propagates unchanged, unclassified,
     * after a single request and no eviction (D-020).
     */
    @Test
    void serverErrorPropagatesAsWebClientResponseException() {
        server.enqueue(new MockResponse().setResponseCode(500));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> client.query(request));

        WebClientResponseException response = assertInstanceOf(WebClientResponseException.class, thrown);
        assertFalse(thrown instanceof UpstreamAuthenticationException);
        assertFalse(thrown instanceof UpstreamRateLimitException);
        assertFalse(thrown instanceof UpstreamUnavailableException);
        assertEquals(500, response.getStatusCode().value());
        assertEquals(1, server.getRequestCount());
        assertEquals(0, counter.get());
    }

    /**
     * A token endpoint failure raised inside {@code execute}, with no HTTP exchange, classified by the
     * token endpoint rule of D-404, one block per branch:
     * <ul>
     *   <li>an {@link OAuth2AuthorizationException} with neither a 429 response nor an {@link IOException}
     *       in its cause chain, raised directly or as a {@link ClientAuthorizationException} deeper in the
     *       chain: {@link UpstreamAuthenticationException};</li>
     *   <li>an {@link IOException} in the chain: {@link UpstreamUnavailableException};</li>
     *   <li>an {@code HttpStatusCodeException} with status 429 in the chain:
     *       {@link UpstreamRateLimitException} carrying that response's {@code Retry-After} value, or
     *       {@code null} when it has none.</li>
     * </ul>
     * In every branch the call runs once and nothing is evicted.
     */
    @Test
    void authorizationExceptionInsideExecuteIsClassified() {
        UpstreamAuthenticationException rejected = assertExecuteThrows(UpstreamAuthenticationException.class,
                new OAuth2AuthorizationException(new OAuth2Error("invalid_client")));
        assertEquals(UPSTREAM_SYSTEM, rejected.getUpstreamSystem());

        UpstreamAuthenticationException nestedRejected = assertExecuteThrows(UpstreamAuthenticationException.class,
                new IllegalStateException("access token request failed",
                        new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "netsuite")));
        assertEquals(UPSTREAM_SYSTEM, nestedRejected.getUpstreamSystem());

        UpstreamUnavailableException unreachable = assertExecuteThrows(UpstreamUnavailableException.class,
                new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"),
                        new ResourceAccessException("I/O error on POST request",
                                new IOException("Connection reset"))));
        assertEquals(UPSTREAM_SYSTEM, unreachable.getUpstreamSystem());

        HttpHeaders retryAfterHeaders = new HttpHeaders();
        retryAfterHeaders.set(HttpHeaders.RETRY_AFTER, "30");
        UpstreamRateLimitException limited = assertExecuteThrows(UpstreamRateLimitException.class,
                new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"),
                        tooManyRequests(retryAfterHeaders)));
        assertEquals(UPSTREAM_SYSTEM, limited.getUpstreamSystem());
        assertEquals("30", limited.getRetryAfter());

        UpstreamRateLimitException limitedWithoutRetryAfter = assertExecuteThrows(UpstreamRateLimitException.class,
                new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"),
                        tooManyRequests(new HttpHeaders())));
        assertEquals(UPSTREAM_SYSTEM, limitedWithoutRetryAfter.getUpstreamSystem());
        assertNull(limitedWithoutRetryAfter.getRetryAfter());

        assertEquals(0, counter.get());
    }

    /**
     * Runs {@code execute("query", ...)} with a call that throws {@code failure}, and asserts the thrown
     * exception type and that the call ran once.
     *
     * @param expected the exception type {@code execute} is expected to throw
     * @param failure  the failure thrown by the call
     * @param <E>      the expected exception type
     * @return the exception thrown by {@code execute}
     */
    private <E extends RuntimeException> E assertExecuteThrows(Class<E> expected, RuntimeException failure) {
        AtomicInteger attempts = new AtomicInteger();
        E thrown = assertThrows(expected, () -> client.execute("query", () -> {
            attempts.incrementAndGet();
            throw failure;
        }));
        assertEquals(1, attempts.get());
        return thrown;
    }

    /**
     * Builds the {@code HttpClientErrorException} with status 429 that a {@code RestTemplate} token request
     * raises for a rate-limited token endpoint.
     *
     * @param headers the response headers
     * @return the exception, with an empty body
     */
    private static HttpClientErrorException tooManyRequests(HttpHeaders headers) {
        return HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", headers,
                new byte[0], StandardCharsets.UTF_8);
    }

    /**
     * Asserts the three SuiteQL columns of a returned row and the presence of its {@code links} member.
     *
     * @param row       the returned row
     * @param email     the expected {@code email} value
     * @param firstname the expected {@code firstname} value
     * @param lastname  the expected {@code lastname} value
     */
    private static void assertRow(Map<String, Object> row, String email, String firstname, String lastname) {
        assertEquals(email, row.get("email"));
        assertEquals(firstname, row.get("firstname"));
        assertEquals(lastname, row.get("lastname"));
        assertTrue(row.containsKey("links"));
    }

    /**
     * Builds a SuiteQL response:
     * status 200 with {@code Content-Type: application/json} and the given body.
     *
     * @param body the JSON body
     * @return the response
     */
    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .setBody(body);
    }

    /**
     * Builds a SuiteQL page body
     * {@code {"links":[],"count":n,"hasMore":b,"offset":o,"totalResults":t,"items":[...]}}.
     *
     * @param count        the {@code count} member
     * @param hasMore      the {@code hasMore} member
     * @param offset       the {@code offset} member
     * @param totalResults the {@code totalResults} member
     * @param items        the rows of the page, in order
     * @return the JSON text of the page
     * @throws JsonProcessingException if Jackson cannot write the page
     */
    private String pageBody(int count, boolean hasMore, int offset, int totalResults, ObjectNode... items)
            throws JsonProcessingException {
        ObjectNode page = mapper.createObjectNode();
        page.putArray("links");
        page.put("count", count);
        page.put("hasMore", hasMore);
        page.put("offset", offset);
        page.put("totalResults", totalResults);
        page.putArray("items").addAll(List.of(items));
        return mapper.writeValueAsString(page);
    }

    /**
     * Builds one SuiteQL row {@code {"links":[],"email":...,"firstname":...,"lastname":...}}.
     *
     * @param email     the {@code email} value
     * @param firstname the {@code firstname} value
     * @param lastname  the {@code lastname} value
     * @return the row
     */
    private ObjectNode item(String email, String firstname, String lastname) {
        ObjectNode item = mapper.createObjectNode();
        item.putArray("links");
        item.put("email", email);
        item.put("firstname", firstname);
        item.put("lastname", lastname);
        return item;
    }
}
