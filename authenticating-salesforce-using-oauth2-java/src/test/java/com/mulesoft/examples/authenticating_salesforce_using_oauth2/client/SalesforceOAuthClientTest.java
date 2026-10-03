package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

/**
 * Unit tests of {@link SalesforceOAuthClient}, the OAuth2 web-server flow of {@code Salesforce__OAuth_} and
 * {@code sfdc:authorize} [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:3-5,12]
 * (D-014), against a {@link MockWebServer} standing in for the Salesforce authorize and token endpoints.
 *
 * <p>The tests assert the authorize URL, the authorization-code exchange form, the refresh form and the stored
 * {@link SalesforceOAuthClient.Token}, and the classification of token-endpoint failures with exactly one
 * outbound attempt per call (D-020). Every client is built through the package-private constructor with
 * consumer key {@code test-key}, consumer secret {@code test-secret}, the callback {@code localhost:8081}
 * path {@code oauth2callback} of salesforce-oauth.xml:4 and a 3 s read timeout. No Spring application
 * context and no mocks are used; recorded requests are read with a five-second bound.
 */
public class SalesforceOAuthClientTest {

    /** {@code redirect_uri} value {@code http://localhost:8081/oauth2callback}, percent-encoded once. */
    private static final String EXPECTED_REDIRECT = "http%3A%2F%2Flocalhost%3A8081%2Foauth2callback";

    /** Path of the stubbed Salesforce authorize endpoint [salesforce-oauth.xml:12 {@code authorizationUrl}]. */
    private static final String AUTHORIZE_PATH = "/services/oauth2/authorize";

    /** Path of the stubbed Salesforce token endpoint [salesforce-oauth.xml:12 {@code accessTokenUrl}]. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** {@code sfdc.key} of every test client [salesforce-oauth.xml:3 {@code consumerKey}]. */
    private static final String KEY = "test-key";

    /** {@code sfdc.secret} of every test client [salesforce-oauth.xml:3 {@code consumerSecret}]. */
    private static final String SECRET = "test-secret";

    /** {@code sfdc.oauth.callback-port} [salesforce-oauth.xml:4 {@code localPort}]. */
    private static final int CALLBACK_PORT = 8081;

    /** {@code sfdc.oauth.callback-path} [salesforce-oauth.xml:4 {@code path}]. */
    private static final String CALLBACK_PATH = "oauth2callback";

    /** {@code sfdc.oauth.callback-domain} [salesforce-oauth.xml:4 {@code domain}]. */
    private static final String CALLBACK_DOMAIN = "localhost";

    /** Read timeout of every test client. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    /** Upstream system name every upstream exception of the client carries. */
    private static final String SALESFORCE = "Salesforce";

    /** {@code instance_url} of the first issued token. */
    private static final String NA1 = "https://na1.salesforce.com";

    /** {@code instance_url} of the second issued token. */
    private static final String NA2 = "https://na2.salesforce.com";

    /** {@code Content-Type} prefix of every token-endpoint request. */
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    /** {@code Content-Type} of every stubbed token-endpoint answer. */
    private static final String JSON_CONTENT_TYPE = "application/json";

    /** Stub of the Salesforce authorize and token endpoints, started before and shut down after each test. */
    private MockWebServer server;

    /**
     * Starts the stub endpoint server on a free port.
     *
     * @throws IOException if the server cannot bind
     */
    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    /**
     * Shuts down the stub endpoint server and closes its open connections.
     *
     * @throws IOException if the server cannot be shut down
     */
    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    /**
     * Asserts the authorize URL is the authorization URL followed by {@code response_type}, {@code client_id},
     * {@code redirect_uri}, {@code display=page} and {@code state} in that order, with {@code redirect_uri}
     * encoded once and no request sent (D-014; salesforce-oauth.xml:3-5,12).
     */
    @Test
    @Timeout(10)
    public void authorizationUrlListsParametersInOrderWithRedirectUriEncodedOnce() {
        String url = newClient(tokenUrl()).authorizationUrl("st");

        assertThat(url).isEqualTo(server.url(AUTHORIZE_PATH) + "?response_type=code&client_id=" + KEY
                + "&redirect_uri=" + EXPECTED_REDIRECT + "&display=page&state=st");
        assertThat(url).doesNotContain("%25");
        assertThat(server.getRequestCount()).isZero();
    }

    /**
     * Asserts the code exchange is one form POST to the token path with the exact authorization-code body, and
     * that the answer's {@code access_token}, {@code refresh_token} and {@code instance_url} become the stored
     * token (D-014).
     *
     * @throws InterruptedException if waiting for the recorded request is interrupted
     */
    @Test
    @Timeout(10)
    public void exchangePostsAuthorizationCodeFormAndStoresToken() throws InterruptedException {
        SalesforceOAuthClient client = newClient(tokenUrl());
        enqueueToken("at1", "rt1", NA1);

        client.exchange("abc");

        RecordedRequest request = takeRequest();
        assertFormPost(request);
        assertThat(request.getBody().readUtf8()).isEqualTo("grant_type=authorization_code&code=abc&client_id="
                + KEY + "&client_secret=" + SECRET + "&redirect_uri=" + EXPECTED_REDIRECT);
        assertThat(client.hasToken()).isTrue();
        assertThat(client.currentToken()).isEqualTo(new SalesforceOAuthClient.Token("at1", "rt1", NA1));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts a second code exchange replaces the stored token with the second answer and sends the second code
     * (D-014).
     *
     * @throws InterruptedException if waiting for a recorded request is interrupted
     */
    @Test
    @Timeout(10)
    public void secondExchangeReplacesToken() throws InterruptedException {
        SalesforceOAuthClient client = newClient(tokenUrl());
        enqueueToken("at1", "rt1", NA1);
        enqueueToken("at2", "rt2", NA2);

        client.exchange("abc");
        client.exchange("def");

        assertThat(takeRequest().getBody().readUtf8()).contains("code=abc");
        RecordedRequest second = takeRequest();
        assertFormPost(second);
        assertThat(second.getBody().readUtf8()).contains("code=def");
        assertThat(client.currentToken()).isEqualTo(new SalesforceOAuthClient.Token("at2", "rt2", NA2));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /**
     * Asserts the refresh is one form POST with the exact refresh-token body, and that an answer without
     * {@code refresh_token} keeps the previous refresh token beside the new access token and instance URL
     * (D-014).
     *
     * @throws InterruptedException if waiting for a recorded request is interrupted
     */
    @Test
    @Timeout(10)
    public void refreshPostsRefreshTokenFormAndKeepsRefreshTokenWhenOmitted() throws InterruptedException {
        SalesforceOAuthClient client = seededClient();
        enqueueToken("at2", null, NA2);

        client.refresh();

        RecordedRequest request = takeRequest();
        assertFormPost(request);
        assertThat(request.getBody().readUtf8()).isEqualTo(
                "grant_type=refresh_token&refresh_token=rt1&client_id=" + KEY + "&client_secret=" + SECRET);
        assertThat(client.currentToken()).isEqualTo(new SalesforceOAuthClient.Token("at2", "rt1", NA2));
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /**
     * Asserts a refresh without a stored token throws {@link UpstreamAuthenticationException} with upstream
     * {@code Salesforce}, sends no request and leaves no token (D-014).
     */
    @Test
    @Timeout(10)
    public void refreshWithoutTokenThrowsUpstreamAuthentication() {
        SalesforceOAuthClient client = newClient(tokenUrl());

        assertThatThrownBy(client::refresh).isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                ex -> assertSalesforceUpstream(ex.getUpstreamSystem()));
        assertThat(client.hasToken()).isFalse();
        assertThat(client.currentToken()).isNull();
        assertThat(server.getRequestCount()).isZero();
    }

    /**
     * Asserts a refresh answered HTTP 400 {@code invalid_grant} throws {@link UpstreamAuthenticationException}
     * with upstream {@code Salesforce} and clears the stored token (D-014).
     *
     * @throws InterruptedException if waiting for a recorded request is interrupted
     */
    @Test
    @Timeout(10)
    public void refreshAnswered400ThrowsUpstreamAuthenticationAndClearsToken() throws InterruptedException {
        SalesforceOAuthClient client = seededClient();
        enqueueError(400, "{\"error\":\"invalid_grant\",\"error_description\":\"expired access/refresh token\"}");

        assertThatThrownBy(client::refresh).isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                ex -> assertSalesforceUpstream(ex.getUpstreamSystem()));
        assertThat(client.hasToken()).isFalse();
        assertThat(client.currentToken()).isNull();
        assertFormPost(takeRequest());
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /**
     * Asserts an HTTP 401 answer to the code exchange throws {@link UpstreamAuthenticationException} with
     * upstream {@code Salesforce} after exactly one request (D-020).
     */
    @Test
    @Timeout(10)
    public void status401ThrowsUpstreamAuthentication() {
        SalesforceOAuthClient client = newClient(tokenUrl());
        enqueueError(401, "{\"error\":\"invalid_client\",\"error_description\":\"invalid client credentials\"}");

        assertThatThrownBy(() -> client.exchange("abc")).isInstanceOfSatisfying(
                UpstreamAuthenticationException.class, ex -> assertSalesforceUpstream(ex.getUpstreamSystem()));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(client.hasToken()).isFalse();
    }

    /**
     * Asserts an HTTP 429 answer with {@code Retry-After: 7} to the code exchange throws
     * {@link UpstreamRateLimitException} carrying {@code 7} and upstream {@code Salesforce} after exactly one
     * request (D-020).
     */
    @Test
    @Timeout(10)
    public void status429ThrowsUpstreamRateLimitWithRetryAfter() {
        SalesforceOAuthClient client = newClient(tokenUrl());
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setHeader("Content-Type", JSON_CONTENT_TYPE)
                .setBody("{\"error\":\"rate_limit_exceeded\",\"error_description\":\"too many requests\"}"));

        assertThatThrownBy(() -> client.exchange("abc")).isInstanceOfSatisfying(UpstreamRateLimitException.class,
                ex -> {
                    assertThat(ex.getRetryAfter()).isEqualTo("7");
                    assertSalesforceUpstream(ex.getUpstreamSystem());
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(client.hasToken()).isFalse();
    }

    /**
     * Asserts an HTTP 400 answer to the code exchange throws {@link IllegalStateException}, none of the upstream
     * exception types, and leaves no token (D-020).
     */
    @Test
    @Timeout(10)
    public void status400OnExchangeThrowsIllegalState() {
        SalesforceOAuthClient client = newClient(tokenUrl());
        enqueueError(400, "{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}");

        assertThatThrownBy(() -> client.exchange("abc"))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOfAny(UpstreamAuthenticationException.class, UpstreamRateLimitException.class,
                        UpstreamUnavailableException.class);
        assertThat(client.hasToken()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts a token endpoint that reads the request and never answers makes the code exchange throw
     * {@link UpstreamUnavailableException} with upstream {@code Salesforce} after the 3 s read timeout, with
     * exactly one request and no retry, within 15 seconds (D-020).
     *
     * @throws InterruptedException if waiting for the recorded request is interrupted
     */
    @Test
    @Timeout(15)
    public void noResponseThrowsUpstreamUnavailableAfterOneAttempt() throws InterruptedException {
        SalesforceOAuthClient client = newClient(tokenUrl());
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(() -> client.exchange("abc")).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                ex -> assertSalesforceUpstream(ex.getUpstreamSystem()));
        assertFormPost(takeRequest());
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(client.hasToken()).isFalse();
    }

    /**
     * Asserts a token URL whose server has shut down makes the code exchange throw
     * {@link UpstreamUnavailableException} with upstream {@code Salesforce} (D-020).
     *
     * @throws IOException if the second stub server cannot start or shut down
     */
    @Test
    @Timeout(10)
    public void shutDownServerThrowsUpstreamUnavailable() throws IOException {
        MockWebServer dead = new MockWebServer();
        dead.start();
        String deadUrl = dead.url(TOKEN_PATH).toString();
        dead.shutdown();
        SalesforceOAuthClient client = newClient(deadUrl);

        assertThatThrownBy(() -> client.exchange("abc")).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                ex -> assertSalesforceUpstream(ex.getUpstreamSystem()));
        assertThat(client.hasToken()).isFalse();
        assertThat(server.getRequestCount()).isZero();
    }

    /**
     * Returns a client for the stub authorize endpoint and the given token URL, with the test credentials, the
     * {@code localhost:8081/oauth2callback} callback and a 3 s read timeout.
     *
     * @param tokenUrl {@code sfdc.oauth.access-token-url} of the client
     * @return a client holding no token
     */
    private SalesforceOAuthClient newClient(String tokenUrl) {
        return new SalesforceOAuthClient(
                RestClient.builder(),
                new SalesforceOAuthProperties(KEY, SECRET, new SalesforceOAuthProperties.OAuth(
                        CALLBACK_PORT, CALLBACK_PATH, CALLBACK_DOMAIN, server.url(AUTHORIZE_PATH).toString(),
                        tokenUrl)),
                READ_TIMEOUT);
    }

    /**
     * Returns the token URL of the stub server.
     *
     * @return {@code http://<stub host>:<stub port>/services/oauth2/token}
     */
    private String tokenUrl() {
        return server.url(TOKEN_PATH).toString();
    }

    /**
     * Returns a client of the stub server holding the token {@code at1}/{@code rt1}/{@code na1}, stored by one
     * code exchange whose recorded request is consumed.
     *
     * @return the seeded client
     * @throws InterruptedException if waiting for the recorded request is interrupted
     */
    private SalesforceOAuthClient seededClient() throws InterruptedException {
        SalesforceOAuthClient client = newClient(tokenUrl());
        enqueueToken("at1", "rt1", NA1);
        client.exchange("abc");
        assertFormPost(takeRequest());
        assertThat(client.currentToken()).isEqualTo(new SalesforceOAuthClient.Token("at1", "rt1", NA1));
        return client;
    }

    /**
     * Enqueues an HTTP 200 JSON token answer with the Salesforce members {@code access_token},
     * {@code refresh_token} when given, {@code signature}, {@code instance_url}, {@code id},
     * {@code token_type} {@code Bearer} and {@code issued_at}.
     *
     * @param accessToken  the {@code access_token} value
     * @param refreshToken the {@code refresh_token} value, or {@code null} to omit the member
     * @param instanceUrl  the {@code instance_url} value
     */
    private void enqueueToken(String accessToken, String refreshToken, String instanceUrl) {
        StringBuilder json = new StringBuilder("{\"access_token\":\"").append(accessToken).append('"');
        if (refreshToken != null) {
            json.append(",\"refresh_token\":\"").append(refreshToken).append('"');
        }
        json.append(",\"signature\":\"c2lnbmF0dXJlLW9mLXRoZS10ZXN0LXRva2Vu\"")
                .append(",\"instance_url\":\"").append(instanceUrl).append('"')
                .append(",\"id\":\"https://login.salesforce.com/id/00D000000000001AAA/005000000000001AAA\"")
                .append(",\"token_type\":\"Bearer\"")
                .append(",\"issued_at\":\"1696291200000\"}");
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", JSON_CONTENT_TYPE)
                .setBody(json.toString()));
    }

    /**
     * Enqueues a JSON error answer.
     *
     * @param status the HTTP status
     * @param body   the JSON body
     */
    private void enqueueError(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", JSON_CONTENT_TYPE)
                .setBody(body));
    }

    /**
     * Returns the next recorded request, waiting at most five seconds.
     *
     * @return the recorded request, never {@code null}
     * @throws InterruptedException if the wait is interrupted
     */
    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("recorded token-endpoint request").isNotNull();
        return request;
    }

    /**
     * Asserts a recorded request is a POST to the token path with a form-urlencoded {@code Content-Type}.
     *
     * @param request the recorded request
     */
    private static void assertFormPost(RecordedRequest request) {
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(request.getHeader("Content-Type")).startsWith(FORM_CONTENT_TYPE);
    }

    /**
     * Asserts an upstream system name equals {@link SalesforceOAuthClient#UPSTREAM_SYSTEM} and {@code Salesforce}.
     *
     * @param upstreamSystem the upstream system name of the thrown exception
     */
    private static void assertSalesforceUpstream(String upstreamSystem) {
        assertThat(upstreamSystem).isEqualTo(SalesforceOAuthClient.UPSTREAM_SYSTEM).isEqualTo(SALESFORCE);
    }
}

