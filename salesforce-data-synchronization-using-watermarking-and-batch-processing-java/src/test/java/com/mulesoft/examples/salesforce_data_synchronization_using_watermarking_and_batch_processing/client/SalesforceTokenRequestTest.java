package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectorConfig;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/**
 * Unit tests of {@link SalesforceSessionProvider}, the OAuth2 username-password grant that supplies the
 * partner API session of {@code sfdc:config name="Salesforce"}, whose {@code username}, {@code password} and
 * {@code securityToken} are {@code ${sfdc.user}}, {@code ${sfdc.password}} and {@code ${sfdc.securityToken}}
 * [salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml:3-5]
 * (D-013, D-015).
 *
 * <p>Each test starts its own okhttp {@link MockWebServer} on localhost as the token endpoint, and builds a new
 * provider over it with fake in-JVM values: user {@code test-user@example.com}, password {@code p+w&1},
 * security token {@code T0k}, consumer key {@code test-key}, consumer secret {@code test-secret}, and the
 * server's base URL as {@code sfdc.login-url}. No Spring application context is started and no request leaves
 * the JVM's loopback interface. The tests assert:
 * <ul>
 *   <li>the token request: one {@code POST /services/oauth2/token} with header
 *       {@code Content-Type: application/x-www-form-urlencoded;charset=UTF-8} and the exact form body, in which
 *       the password and the security token are joined before one form encoding
 *       ({@code password=p%2Bw%261T0k}) (D-013);</li>
 *   <li>the partner connection: session id {@code access_token} and service endpoint
 *       {@code <instance_url>/services/Soap/u/65.0}, with no further request (no SOAP {@code login()});</li>
 *   <li>the session lifecycle: no request from the constructor, one from the first {@code connection()}, none
 *       from later calls, and exactly one more from {@code refresh()}, which replaces the cached session;</li>
 *   <li>the token failures, each after exactly one request and upstream system {@code Salesforce} (D-020):
 *       HTTP 400 and 401 raise {@link UpstreamAuthenticationException}; HTTP 429 raises
 *       {@link UpstreamRateLimitException} carrying the {@code Retry-After} value; an unanswered request raises
 *       {@link UpstreamUnavailableException} once the read timeout set through
 *       {@code setTimeouts(Duration, Duration)} elapses.</li>
 * </ul>
 */
public class SalesforceTokenRequestTest {

    /** Value of {@code sfdc.user}. */
    private static final String USER = "test-user@example.com";

    /** Value of {@code sfdc.password}. */
    private static final String PASSWORD = "p+w&1";

    /** Value of {@code sfdc.securityToken}. */
    private static final String SECURITY_TOKEN = "T0k";

    /** Value of {@code sfdc.key}. */
    private static final String CONSUMER_KEY = "test-key";

    /** Value of {@code sfdc.secret}. */
    private static final String CONSUMER_SECRET = "test-secret";

    /** Path of the OAuth2 token endpoint below {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Path of the partner SOAP API, version 65.0, below the token's {@code instance_url}. */
    private static final String PARTNER_API_PATH = "/services/Soap/u/65.0";

    /** Exact {@code Content-Type} header of the token request. */
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8";

    /** Exact token request body for the values above, each form-encoded once in UTF-8 (D-013). */
    private static final String GRANT_BODY = "grant_type=password"
            + "&client_id=test-key"
            + "&client_secret=test-secret"
            + "&username=test-user%40example.com"
            + "&password=p%2Bw%261T0k";

    /** {@code access_token} of the first token answer. */
    private static final String ACCESS_TOKEN = "fake-access-token";

    /** {@code access_token} of the token answer to {@code refresh()}. */
    private static final String REFRESHED_ACCESS_TOKEN = "fake-refreshed-access-token";

    /** Upper bound, in seconds, of each wait for a request the token endpoint has received. */
    private static final long TAKE_TIMEOUT_SECONDS = 5;

    /** The token endpoint of the current test. */
    private MockWebServer server;

    /**
     * Starts a new token endpoint on an ephemeral localhost port.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    public void startTokenEndpoint() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    /**
     * Shuts the token endpoint down after every test, whatever its outcome, closing every open connection.
     *
     * @throws IOException if the server does not stop
     */
    @AfterEach
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void stopTokenEndpoint() throws IOException {
        server.shutdown();
    }

    /**
     * Asserts the password-grant request of the first {@code connection()} and the partner connection built
     * from its answer (D-013, D-015).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @DisplayName("connection sends one password-grant token request and builds the partner session from its answer")
    public void connectionSendsOnePasswordGrantAndBuildsPartnerSessionFromTokenAnswer() throws InterruptedException {
        server.enqueue(tokenAnswer(ACCESS_TOKEN));
        SalesforceSessionProvider provider = newProvider();

        PartnerConnection connection = provider.connection();

        RecordedRequest request = takeTokenRequest();
        assertEquals(FORM_CONTENT_TYPE, request.getHeader("Content-Type"));
        assertEquals(GRANT_BODY, request.getBody().readUtf8());
        ConnectorConfig config = connection.getConfig();
        assertEquals(ACCESS_TOKEN, config.getSessionId());
        assertEquals(baseUrl() + PARTNER_API_PATH, config.getServiceEndpoint());
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Asserts that the constructor sends no request, that the first {@code connection()} sends one, and that a
     * repeated {@code connection()} returns the cached session without a request (D-013).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @DisplayName("the provider authenticates lazily on the first connection call and caches the session")
    public void providerAuthenticatesLazilyAndCachesSession() throws InterruptedException {
        server.enqueue(tokenAnswer(ACCESS_TOKEN));

        SalesforceSessionProvider provider = newProvider();

        assertEquals(0, server.getRequestCount());

        PartnerConnection first = provider.connection();

        takeTokenRequest();
        assertEquals(1, server.getRequestCount());

        PartnerConnection second = provider.connection();

        assertSame(first, second);
        assertEquals(ACCESS_TOKEN, second.getConfig().getSessionId());
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Asserts that {@code refresh()} sends exactly one new password-grant request and that the next
     * {@code connection()} returns the session of its answer without a request (D-013).
     *
     * @throws InterruptedException if the wait for a recorded request is interrupted
     */
    @Test
    @DisplayName("refresh sends exactly one new token request and replaces the cached session")
    public void refreshSendsOneNewTokenRequestAndReplacesCachedSession() throws InterruptedException {
        server.enqueue(tokenAnswer(ACCESS_TOKEN));
        server.enqueue(tokenAnswer(REFRESHED_ACCESS_TOKEN));
        SalesforceSessionProvider provider = newProvider();
        PartnerConnection initial = provider.connection();
        takeTokenRequest();

        provider.refresh();

        RecordedRequest refreshRequest = takeTokenRequest();
        assertEquals(FORM_CONTENT_TYPE, refreshRequest.getHeader("Content-Type"));
        assertEquals(GRANT_BODY, refreshRequest.getBody().readUtf8());
        assertEquals(2, server.getRequestCount());

        PartnerConnection refreshed = provider.connection();

        assertNotSame(initial, refreshed);
        assertEquals(REFRESHED_ACCESS_TOKEN, refreshed.getConfig().getSessionId());
        assertEquals(baseUrl() + PARTNER_API_PATH, refreshed.getConfig().getServiceEndpoint());
        assertEquals(ACCESS_TOKEN, initial.getConfig().getSessionId());
        assertEquals(2, server.getRequestCount());
    }

    /**
     * Asserts that a token answer with HTTP 400 raises {@link UpstreamAuthenticationException} for
     * {@code Salesforce} after one request (D-020).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @DisplayName("a token answer with HTTP 400 raises UpstreamAuthenticationException after one request")
    public void tokenAnswer400RaisesAuthenticationFailureAfterOneRequest() throws InterruptedException {
        server.enqueue(jsonAnswer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}"));
        SalesforceSessionProvider provider = newProvider();

        UpstreamAuthenticationException failure =
                assertThrowsExactly(UpstreamAuthenticationException.class, provider::connection);

        assertEquals("Salesforce", failure.upstreamSystem());
        takeTokenRequest();
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Asserts that a token answer with HTTP 401 raises {@link UpstreamAuthenticationException} for
     * {@code Salesforce} after one request (D-020).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @DisplayName("a token answer with HTTP 401 raises UpstreamAuthenticationException after one request")
    public void tokenAnswer401RaisesAuthenticationFailureAfterOneRequest() throws InterruptedException {
        server.enqueue(jsonAnswer(401, "{\"error\":\"invalid_client\",\"error_description\":\"invalid client credentials\"}"));
        SalesforceSessionProvider provider = newProvider();

        UpstreamAuthenticationException failure =
                assertThrowsExactly(UpstreamAuthenticationException.class, provider::connection);

        assertEquals("Salesforce", failure.upstreamSystem());
        takeTokenRequest();
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Asserts that a token answer with HTTP 429 and {@code Retry-After: 30} raises
     * {@link UpstreamRateLimitException} for {@code Salesforce} carrying {@code 30}, after one request and with
     * no retry (D-020).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @DisplayName("a token answer with HTTP 429 raises UpstreamRateLimitException with Retry-After and no retry")
    public void tokenAnswer429RaisesRateLimitWithRetryAfterAndNoRetry() throws InterruptedException {
        server.enqueue(jsonAnswer(429, "{\"error\":\"rate_limit_exceeded\"}").setHeader("Retry-After", "30"));
        SalesforceSessionProvider provider = newProvider();

        UpstreamRateLimitException failure =
                assertThrowsExactly(UpstreamRateLimitException.class, provider::connection);

        assertEquals("Salesforce", failure.upstreamSystem());
        assertEquals(Optional.of("30"), failure.retryAfter());
        takeTokenRequest();
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Asserts that a token request the endpoint receives but never answers raises
     * {@link UpstreamUnavailableException} for {@code Salesforce} once the 3 s read timeout elapses, with a 2 s
     * connect timeout, after exactly one request (D-020). The test is bounded to fifteen seconds, more than
     * the read timeout and the {@value #TAKE_TIMEOUT_SECONDS} s wait for the recorded request together.
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("an unanswered token request raises UpstreamUnavailableException after exactly one request")
    public void unansweredTokenRequestRaisesUnavailableAfterExactlyOneRequest() throws InterruptedException {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        SalesforceSessionProvider provider = newProvider();
        provider.setTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3));

        UpstreamUnavailableException failure =
                assertThrowsExactly(UpstreamUnavailableException.class, provider::connection);

        assertEquals("Salesforce", failure.upstreamSystem());
        takeTokenRequest();
        assertEquals(1, server.getRequestCount());
    }

    /**
     * Builds a new provider over this test's token endpoint, with a {@link RestClient.Builder} supplied through a
     * bean factory that holds only that builder.
     *
     * @return a provider that has sent no request
     */
    private SalesforceSessionProvider newProvider() {
        SalesforceProperties properties = new SalesforceProperties(
                USER, PASSWORD, SECURITY_TOKEN, CONSUMER_KEY, CONSUMER_SECRET, baseUrl());
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("restClientBuilder", RestClient.builder());
        ObjectProvider<RestClient.Builder> builders = beanFactory.getBeanProvider(RestClient.Builder.class);
        return new SalesforceSessionProvider(properties, builders);
    }

    /**
     * Returns the base URL of this test's token endpoint, {@code http://<host>:<port>}, without a trailing
     * {@code /}.
     *
     * @return the base URL
     */
    private String baseUrl() {
        String root = server.url("/").toString();
        return root.substring(0, root.length() - 1);
    }

    /**
     * Returns a 200 token answer of the password grant with {@code Content-Type: application/json}, whose
     * {@code instance_url} is this test's base URL.
     *
     * @param accessToken the {@code access_token} member
     * @return the token answer
     */
    private MockResponse tokenAnswer(String accessToken) {
        String base = baseUrl();
        String body = "{"
                + "\"access_token\":\"" + accessToken + "\","
                + "\"instance_url\":\"" + base + "\","
                + "\"id\":\"" + base + "/id/00D000000000001AAA/005000000000001AAA\","
                + "\"token_type\":\"Bearer\","
                + "\"issued_at\":\"1700000000000\","
                + "\"signature\":\"ZmFrZS1zaWduYXR1cmU=\""
                + "}";
        return jsonAnswer(200, body);
    }

    /**
     * Returns an answer with the given status, {@code Content-Type: application/json} and body.
     *
     * @param status the HTTP status code
     * @param body   the JSON body
     * @return the answer
     */
    private static MockResponse jsonAnswer(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    /**
     * Takes the next request the token endpoint has received, waiting at most
     * {@value #TAKE_TIMEOUT_SECONDS} seconds, and asserts that it is {@code POST /services/oauth2/token}.
     *
     * @return the recorded token request
     * @throws InterruptedException if the wait is interrupted
     */
    private RecordedRequest takeTokenRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(request, "no token request received within " + TAKE_TIMEOUT_SECONDS + " s");
        assertEquals("POST", request.getMethod());
        assertEquals(TOKEN_PATH, request.getPath());
        return request;
    }
}
