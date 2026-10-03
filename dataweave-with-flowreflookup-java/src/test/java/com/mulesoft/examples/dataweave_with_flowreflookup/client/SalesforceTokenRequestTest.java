package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.mulesoft.examples.dataweave_with_flowreflookup.config.SalesforceProperties;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamRateLimitException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of the Salesforce OAuth2 username-password grant of {@link SalesforceSessionProvider}, the
 * replacement of the global element {@code sfdc:config name="Salesforce__Basic_authentication"}
 * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:3] (D-013).
 *
 * <p>The token endpoint is an okhttp {@link MockWebServer} on the loopback interface; its base URL is both the
 * {@code sfdc.login-url} of the {@link SalesforceProperties} and the {@code instance_url} of every token
 * response. The properties carry fake values for the keys {@code sfdc.user}, {@code sfdc.password},
 * {@code sfdc.securityToken}, {@code sfdc.key}, {@code sfdc.secret} and {@code sfdc.login-url} (D-015). The
 * server's queue dispatcher fails fast: a request with no queued response is answered 404 and counted. No
 * Spring context starts.
 *
 * <p>The tests cover:
 * <ul>
 *   <li>the token request: no request at construction, then one {@code POST} of the exact form body
 *       {@value #EXPECTED_FORM} to {@code /services/oauth2/token} (D-013);</li>
 *   <li>the {@link PartnerConnection} built from the token response, its caching, and
 *       {@link SalesforceSessionProvider#reauthenticate()} (D-013);</li>
 *   <li>the classification of token-request failures into the upstream exception family with upstream system
 *       {@code Salesforce}, each after exactly one request (D-020).</li>
 * </ul>
 *
 * <p>The eight test methods are public; the lifecycle methods are package-private and the helpers private
 * (D-133).
 */
public class SalesforceTokenRequestTest {

    /** Fake Salesforce username, bound to {@code sfdc.user}. */
    private static final String USER = "u@example.com";

    /** Fake Salesforce password, bound to {@code sfdc.password}. */
    private static final String PASSWORD = "p+w&1";

    /** Fake Salesforce security token, bound to {@code sfdc.securityToken}. */
    private static final String SECURITY_TOKEN = "T0k";

    /** Fake connected-app consumer key, bound to {@code sfdc.key}. */
    private static final String KEY = "KEY123";

    /** Fake connected-app consumer secret, bound to {@code sfdc.secret}. */
    private static final String SECRET = "SECRET456";

    /** Path of the OAuth2 token endpoint below {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Partner SOAP path appended to the token response's {@code instance_url}. */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** Upstream system name carried by every upstream exception of the provider. */
    private static final String UPSTREAM = "Salesforce";

    /**
     * The exact password-grant form body: five fields in order, each value form-encoded once, and the password
     * immediately followed by the security token with no delimiter (D-013).
     */
    private static final String EXPECTED_FORM = "grant_type=password&client_id=KEY123&client_secret=SECRET456"
            + "&username=u%40example.com&password=p%2Bw%261T0k";

    /** Seconds {@link MockWebServer#takeRequest(long, TimeUnit)} waits for a recorded request. */
    private static final long TAKE_TIMEOUT_SECONDS = 5;

    /** Category of the DEBUG line written when shutting the server down fails. */
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceTokenRequestTest.class);

    /** Token endpoint stand-in; started before and shut down after each test. */
    private MockWebServer server;

    /** Base URL of {@link #server} without its trailing {@code /}, for example {@code http://localhost:54321}. */
    private String baseUrl;

    /** Settings with the fake credentials and {@link #baseUrl} as {@code sfdc.login-url}. */
    private SalesforceProperties props;

    /**
     * Starts {@link #server} with a fail-fast queue dispatcher and builds {@link #baseUrl} and {@link #props}.
     *
     * @throws IOException when the server cannot bind a port
     */
    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        ((QueueDispatcher) server.getDispatcher()).setFailFast(true);
        server.start();
        String rootUrl = server.url("/").toString();
        baseUrl = rootUrl.substring(0, rootUrl.length() - 1);
        props = new SalesforceProperties(USER, PASSWORD, SECURITY_TOKEN, KEY, SECRET, baseUrl);
    }

    /**
     * Shuts {@link #server} down. An {@link IOException} from a server already shut down is logged at DEBUG and
     * not rethrown.
     */
    @AfterEach
    void stopServer() {
        try {
            server.shutdown();
        } catch (IOException alreadyShutDown) {
            LOG.debug("MockWebServer shutdown reported a failure", alreadyShutDown);
        }
    }

    /** Asserts that constructing the provider with its public constructor sends no request. */
    @Test
    public void constructionSendsNoRequest() {
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        assertThat(provider).isNotNull();
        assertThat(server.getRequestCount()).isZero();
    }

    /**
     * Asserts the password-grant request: one {@code POST} to {@value #TOKEN_PATH} with an
     * {@code application/x-www-form-urlencoded} content type and exactly the body {@value #EXPECTED_FORM}
     * (D-013, D-015).
     *
     * @throws InterruptedException when waiting for the recorded request is interrupted
     */
    @Test
    public void postsPasswordGrantFormToTokenEndpoint() throws InterruptedException {
        server.enqueue(tokenResponse("00D!AT1"));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        provider.connection();

        RecordedRequest request = server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        assertThat(request.getBody().readUtf8()).isEqualTo(EXPECTED_FORM);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts that the connection carries the token's {@code access_token} as session id and the token's
     * {@code instance_url} followed by {@value #SOAP_PATH} as service endpoint, and that building it sends no
     * request beyond the token request (D-013).
     */
    @Test
    public void buildsPartnerConnectionFromTokenResponse() {
        server.enqueue(tokenResponse("00D!AT1"));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        PartnerConnection c = provider.connection();

        assertThat(c.getConfig().getSessionId()).isEqualTo("00D!AT1");
        assertThat(c.getConfig().getServiceEndpoint()).isEqualTo(baseUrl + SOAP_PATH);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts that {@link SalesforceSessionProvider#connection()} returns the cached connection without a new
     * request, and that {@link SalesforceSessionProvider#reauthenticate()} sends exactly one new token request
     * with the same form body and caches the new session (D-013).
     *
     * @throws InterruptedException when waiting for a recorded request is interrupted
     */
    @Test
    public void cachesConnectionAndReauthenticatesOnRequest() throws InterruptedException {
        server.enqueue(tokenResponse("00D!AT1"));
        server.enqueue(tokenResponse("00D!AT2"));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        PartnerConnection first = provider.connection();
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(first.getConfig().getSessionId()).isEqualTo("00D!AT1");

        PartnerConnection cached = provider.connection();
        assertThat(cached).isSameAs(first);
        assertThat(server.getRequestCount()).isEqualTo(1);

        PartnerConnection renewed = provider.reauthenticate();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(renewed).isNotSameAs(first);
        assertThat(renewed.getConfig().getSessionId()).isEqualTo("00D!AT2");

        PartnerConnection afterRenewal = provider.connection();
        assertThat(afterRenewal).isSameAs(renewed);
        assertThat(server.getRequestCount()).isEqualTo(2);

        RecordedRequest firstRequest = server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        RecordedRequest secondRequest = server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(firstRequest).isNotNull();
        assertThat(firstRequest.getBody().readUtf8()).isEqualTo(EXPECTED_FORM);
        assertThat(secondRequest).isNotNull();
        assertThat(secondRequest.getMethod()).isEqualTo("POST");
        assertThat(secondRequest.getPath()).isEqualTo(TOKEN_PATH);
        assertThat(secondRequest.getBody().readUtf8()).isEqualTo(EXPECTED_FORM);
    }

    /**
     * Asserts that a 400 {@code invalid_grant} answer raises {@link UpstreamAuthenticationException} with
     * upstream system {@code Salesforce} after exactly one request (D-013, D-020).
     */
    @Test
    public void invalidGrantRaisesUpstreamAuthenticationException() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}"));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamAuthenticationException.class,
                        e -> assertThat(e.getUpstreamSystem()).isEqualTo(UPSTREAM));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts that a 429 answer raises {@link UpstreamRateLimitException} carrying the {@code Retry-After} value
     * {@code 30} and upstream system {@code Salesforce} after exactly one request (D-013, D-020).
     */
    @Test
    public void tooManyRequestsRaisesUpstreamRateLimitExceptionWithRetryAfter() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30")
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"rate_limited\"}"));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
                    assertThat(e.getRetryAfter()).isEqualTo(Optional.of("30"));
                    assertThat(e.getUpstreamSystem()).isEqualTo(UPSTREAM);
                });
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts that a connection closed after the request is read raises {@link UpstreamUnavailableException}
     * with upstream system {@code Salesforce}, and that the token request is sent once and never re-sent
     * (D-013, D-020).
     */
    @Test
    public void disconnectedTokenCallRaisesUpstreamUnavailableExceptionAfterOneRequest() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamUnavailableException.class,
                        e -> assertThat(e.getUpstreamSystem()).isEqualTo(UPSTREAM));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Asserts that a token endpoint with no listener raises {@link UpstreamUnavailableException} with upstream
     * system {@code Salesforce} and an {@link IOException} in its cause chain, and that no request reaches the
     * shut-down server (D-013, D-020).
     *
     * @throws IOException when shutting the server down fails
     */
    @Test
    public void unreachableTokenEndpointRaisesUpstreamUnavailableException() throws IOException {
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props);
        server.shutdown();

        assertThatThrownBy(provider::connection)
                .isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
                    assertThat(e.getUpstreamSystem()).isEqualTo(UPSTREAM);
                    assertThat(causeChain(e)).anyMatch(IOException.class::isInstance);
                });
        assertThat(server.getRequestCount()).isZero();
    }

    /**
     * Returns a 200 token response with a JSON body holding {@code accessToken} as {@code access_token} and
     * {@link #baseUrl} as {@code instance_url}.
     *
     * @param accessToken value of {@code access_token}
     * @return the queued response
     */
    private MockResponse tokenResponse(String accessToken) {
        String body = "{\"access_token\":\"" + accessToken + "\","
                + "\"instance_url\":\"" + baseUrl + "\","
                + "\"id\":\"" + baseUrl + "/id/00D000000000001/005000000000001\","
                + "\"token_type\":\"Bearer\","
                + "\"issued_at\":\"1700000000000\","
                + "\"signature\":\"c2ln\"}";
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    /**
     * Returns the causes of {@code failure} in {@link Throwable#getCause()} order, {@code failure} excluded;
     * each throwable is listed at most once.
     *
     * @param failure the throwable whose causes are listed
     * @return the cause chain, empty when {@code failure} has no cause
     */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(failure);
        for (Throwable cause = failure.getCause(); cause != null && visited.add(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        return causes;
    }
}
