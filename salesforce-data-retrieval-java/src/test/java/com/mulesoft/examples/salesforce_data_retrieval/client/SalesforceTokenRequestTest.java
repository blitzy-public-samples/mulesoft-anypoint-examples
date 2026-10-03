package com.mulesoft.examples.salesforce_data_retrieval.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mulesoft.examples.salesforce_data_retrieval.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Unit tests of {@link SalesforceSessionProvider}: the OAuth2 username-password token request
 * (D-013, D-015), the partner connection built from its answer and cached until
 * {@link SalesforceSessionProvider#refresh()}, and the failure classification of the token request
 * after exactly one outbound attempt (D-020).
 *
 * <p>Each test starts an okhttp {@link MockWebServer} on an ephemeral port and uses its base URL as
 * {@code sfdc.login-url} and as the token's {@code instance_url}. No Spring context is started.
 *
 * <p>The provider builds its token client with its own {@link JdkClientHttpRequestFactory}, which
 * replaces the factory set on the injected builder (D-451). The builder handed to the provider
 * applies {@link #READ_TIMEOUT} to that JDK factory (D-643).
 */
class SalesforceTokenRequestTest {

    /**
     * Password-grant body for the fixture properties: fields in the order {@code grant_type},
     * {@code client_id}, {@code client_secret}, {@code username}, {@code password}, each value
     * form-encoded once, and the password {@code p+w&1} immediately followed by the security token
     * {@code T0k} (D-013).
     */
    private static final String EXPECTED_BODY = "grant_type=password&client_id=test-key&client_secret=test-secret&username=user%40example.com&password=p%2Bw%261T0k";

    /** Path of the OAuth2 token endpoint below {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Path of the partner SOAP API below the token's {@code instance_url}. */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** Upstream system named by every upstream exception of the provider (D-020). */
    private static final String UPSTREAM = "Salesforce";

    /** Connect timeout of the request factory set on the injected builder. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Read timeout of the injected builder's factory and of the provider's JDK factory (D-643). */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    /** Stub of the Salesforce login host. */
    private MockWebServer server;

    /** Unit under test, bound to {@link #server}. */
    private SalesforceSessionProvider provider;

    /** Base URL of {@link #server} without a trailing {@code /}, for example {@code http://localhost:54321}. */
    private String baseUrl;

    /**
     * Starts the stub login host and creates the provider with the fixture credentials and
     * {@code sfdc.login-url} = {@link #baseUrl}.
     *
     * @throws IOException if the stub server cannot bind a port
     */
    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        String root = server.url("/").toString();
        baseUrl = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;

        SalesforceProperties props = new SalesforceProperties(
                "user@example.com", "p+w&1", "T0k", "test-key", "test-secret", baseUrl);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // The provider replaces this factory with its own JdkClientHttpRequestFactory (D-451);
        // jdkReadTimeout sets READ_TIMEOUT on that JDK factory (D-643).
        provider = new SalesforceSessionProvider(props,
                jdkReadTimeout(RestClient.builder().requestFactory(factory), READ_TIMEOUT));
    }

    /**
     * Stops the stub login host and closes every connection it holds.
     *
     * @throws IOException if the stub server fails to shut down
     */
    @AfterEach
    void stopServer() throws IOException {
        server.shutdown();
    }

    /**
     * The token request is one {@code POST} of {@code application/x-www-form-urlencoded} to
     * {@code /services/oauth2/token} whose body equals {@link #EXPECTED_BODY} byte for byte (D-013).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    void passwordGrantRequestBodyIsFormEncodedOnce() throws InterruptedException {
        server.enqueue(tokenResponse("00Dxx!token"));

        provider.connection();

        RecordedRequest r = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        assertThat(r.getMethod()).isEqualTo("POST");
        assertThat(r.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(r.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(r.getBody().readUtf8()).isEqualTo(EXPECTED_BODY);
    }

    /**
     * The partner connection carries the token's {@code access_token} as its session id and
     * {@code <instance_url>/services/Soap/u/65.0} as its service endpoint (D-013).
     */
    @Test
    void connectionUsesAccessTokenAndInstanceUrl() {
        server.enqueue(tokenResponse("00Dxx!token"));

        PartnerConnection connection = provider.connection();

        assertThat(connection.getConfig().getSessionId()).isEqualTo("00Dxx!token");
        assertThat(connection.getConfig().getServiceEndpoint()).isEqualTo(baseUrl + SOAP_PATH);
    }

    /**
     * A second {@code connection()} returns the cached connection and sends no token request.
     */
    @Test
    void connectionIsCachedUntilRefresh() {
        server.enqueue(tokenResponse("00Dxx!token"));

        PartnerConnection first = provider.connection();
        PartnerConnection second = provider.connection();

        assertThat(second).isSameAs(first);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * {@code refresh()} sends one new token request with the same body, returns a connection on the
     * new session and caches it for later {@code connection()} calls (D-013).
     *
     * @throws InterruptedException if the wait for a recorded request is interrupted
     */
    @Test
    void refreshIssuesNewTokenRequest() throws InterruptedException {
        server.enqueue(tokenResponse("00Dxx!token"));
        server.enqueue(tokenResponse("00Dxx!token2"));

        PartnerConnection initial = provider.connection();
        PartnerConnection refreshed = provider.refresh();

        assertThat(initial.getConfig().getSessionId()).isEqualTo("00Dxx!token");
        assertThat(refreshed.getConfig().getSessionId()).isEqualTo("00Dxx!token2");
        assertThat(server.getRequestCount()).isEqualTo(2);
        RecordedRequest firstRequest = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest secondRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(firstRequest).isNotNull();
        assertThat(secondRequest).isNotNull();
        assertThat(secondRequest.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(secondRequest.getBody().readUtf8()).isEqualTo(EXPECTED_BODY);

        assertThat(provider.connection()).isSameAs(refreshed);
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    /**
     * An HTTP 400 token answer raises {@link UpstreamAuthenticationException} for
     * {@code Salesforce} after one request (D-020).
     */
    @Test
    void badRequestRaisesUpstreamAuthenticationException() {
        server.enqueue(jsonResponse(400,
                "{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}"));

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                        e -> assertThat(e.upstreamSystem()).isEqualTo(UPSTREAM));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * An HTTP 401 token answer raises {@link UpstreamAuthenticationException} for
     * {@code Salesforce} after one request (D-020).
     */
    @Test
    void unauthorizedRaisesUpstreamAuthenticationException() {
        server.enqueue(jsonResponse(401, "{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                        e -> assertThat(e.upstreamSystem()).isEqualTo(UPSTREAM));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * An HTTP 429 token answer raises {@link UpstreamRateLimitException} for {@code Salesforce}
     * carrying the raw {@code Retry-After} value, after one request (D-020).
     */
    @Test
    void tooManyRequestsRaisesUpstreamRateLimitExceptionWithRetryAfter() {
        server.enqueue(jsonResponse(429, "{\"error\":\"rate_limit_exceeded\"}")
                .setHeader("Retry-After", "30"));

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
                    assertThat(e.retryAfter()).isEqualTo("30");
                    assertThat(e.upstreamSystem()).isEqualTo(UPSTREAM);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * A token endpoint that reads the request and never answers ends the call after
     * {@link #READ_TIMEOUT} with {@link UpstreamUnavailableException} for {@code Salesforce}; exactly
     * one request reaches the endpoint, read within 5 seconds of the failure, and none is re-sent.
     * The test is bounded to 15 seconds (D-020, D-643).
     *
     * @throws InterruptedException if the wait for the recorded request is interrupted
     */
    @Test
    @Timeout(15)
    void readTimeoutRaisesUpstreamUnavailableExceptionWithoutRetry() throws InterruptedException {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamUnavailableException.class,
                        e -> assertThat(e.upstreamSystem()).isEqualTo(UPSTREAM))
                .hasRootCauseInstanceOf(TimeoutException.class);
        RecordedRequest r = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(r).isNotNull();
        assertThat(r.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Returns a 200 token answer with the given access token and {@link #baseUrl} as
     * {@code instance_url}.
     *
     * @param accessToken the {@code access_token} member
     * @return the stub response
     */
    private MockResponse tokenResponse(String accessToken) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"" + accessToken + "\",\"instance_url\":\"" + baseUrl
                        + "\",\"token_type\":\"Bearer\"}");
    }

    /**
     * Returns a stub response with the given status, {@code Content-Type: application/json} and body.
     *
     * @param status the HTTP status code
     * @param body   the JSON body
     * @return the stub response
     */
    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    /**
     * Returns a {@link RestClient.Builder} that forwards every call to {@code delegate}. Before it
     * forwards {@code requestFactory} with a {@link JdkClientHttpRequestFactory}, it sets
     * {@code readTimeout} on that factory. A call that returns {@code delegate} returns the
     * forwarding builder instead (D-643).
     *
     * @param delegate    the builder that receives every call
     * @param readTimeout the read timeout set on each JDK request factory
     * @return the forwarding builder
     */
    private static RestClient.Builder jdkReadTimeout(RestClient.Builder delegate, Duration readTimeout) {
        return (RestClient.Builder) Proxy.newProxyInstance(
                RestClient.Builder.class.getClassLoader(),
                new Class<?>[] {RestClient.Builder.class},
                (proxy, method, args) -> {
                    if ("requestFactory".equals(method.getName())
                            && args[0] instanceof JdkClientHttpRequestFactory jdkFactory) {
                        jdkFactory.setReadTimeout(readTimeout);
                    }
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    return result == delegate ? proxy : result;
                });
    }
}
