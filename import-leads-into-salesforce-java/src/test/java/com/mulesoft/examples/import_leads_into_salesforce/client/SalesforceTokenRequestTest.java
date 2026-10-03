package com.mulesoft.examples.import_leads_into_salesforce.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.mulesoft.examples.import_leads_into_salesforce.config.SalesforceProperties;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Unit tests of {@link SalesforceSessionProvider}, the replacement of the global element
 * {@code sfdc:config name="Salesforce"} with {@code username=${sfdc.user}}, {@code password=${sfdc.password}}
 * and {@code securityToken=${sfdc.securityToken}}
 * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:4-6].
 *
 * <p>Each test runs the provider against a {@link MockWebServer} token endpoint on the loopback interface,
 * without a Spring context, and verifies:
 * <ul>
 *   <li>the OAuth2 username-password grant (D-013): one {@code POST} of {@code /services/oauth2/token} whose
 *       {@code application/x-www-form-urlencoded} body carries {@code grant_type=password}, the connected-app
 *       key and secret, the user, and the password immediately followed by the security token, each value
 *       encoded exactly once; the values come from the {@code sfdc.*} keys of {@link SalesforceProperties}
 *       (D-015);</li>
 *   <li>the token's {@code access_token} and {@code instance_url} become the session id and the
 *       {@code /services/Soap/u/65.0} service endpoint of the returned {@link PartnerConnection};</li>
 *   <li>the session is cached, and {@link SalesforceSessionProvider#reauthenticate()} sends exactly one new
 *       token request and replaces the cached session (D-306);</li>
 *   <li>the token-request failure modes (D-020, classification D-488), each from a single attempt and with
 *       upstream system {@code Salesforce}: 400 gives {@link UpstreamAuthenticationException}; 429 gives
 *       {@link UpstreamRateLimitException} with the {@code Retry-After} value; 503, a read timeout and a refused
 *       connection give {@link UpstreamUnavailableException}.</li>
 * </ul>
 */
public class SalesforceTokenRequestTest {

    /** Upstream system name expected on every upstream exception. */
    private static final String UPSTREAM_SYSTEM = "Salesforce";

    /** {@code instance_url} returned by {@link #tokenResponse(String)}. */
    private static final String INSTANCE_URL = "https://na1.example.my.salesforce.com";

    /** Stub token endpoint; started before and shut down after each test. */
    private MockWebServer server;

    /** {@code true} once a test has shut {@link #server} down itself. */
    private boolean serverShutDown;

    /**
     * {@code sfdc.*} settings: user {@code u@x.org}, password {@code p+w&1}, token {@code T0k}, key {@code cid},
     * secret {@code csec}, and the base URL of {@link #server} without its trailing {@code /} as login URL.
     */
    private SalesforceProperties props;

    /** Starts {@link #server} on a free loopback port and binds its base URL as the login URL. */
    @BeforeEach
    public void startServer() throws IOException {
        server = new MockWebServer();
        server.start();
        serverShutDown = false;
        String url = server.url("/").toString();
        String loginUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        props = new SalesforceProperties("u@x.org", "p+w&1", "T0k", "cid", "csec", loginUrl);
    }

    /** Shuts {@link #server} down unless the test has already done so. */
    @AfterEach
    public void stopServer() throws IOException {
        if (!serverShutDown) {
            server.shutdown();
            serverShutDown = true;
        }
    }

    /**
     * Input: one token request. Output: a {@code POST} of {@code /services/oauth2/token} with a form content
     * type and exactly the five grant fields, each encoded once ({@code @} as {@code %40}, {@code +} as
     * {@code %2B}, {@code &} as {@code %26}, no {@code %25}), and the security token appended to the password.
     */
    @Test
    public void tokenRequestPostsPasswordGrantFormEncodedOnce() throws InterruptedException {
        server.enqueue(tokenResponse("00Dx!tok"));

        provider().connection();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/services/oauth2/token");
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        String body = request.getBody().readUtf8();
        assertThat(body.split("&")).containsExactlyInAnyOrder(
                "grant_type=password",
                "client_id=cid",
                "client_secret=csec",
                "username=u%40x.org",
                "password=p%2Bw%261T0k");
        assertThat(body).doesNotContain("%25");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Input: a token response with {@code access_token} {@code 00Dx!tok}. Output: a manual-login connection
     * whose session id is {@code 00Dx!tok} and whose service endpoint is {@code instance_url} plus
     * {@code /services/Soap/u/65.0}.
     */
    @Test
    public void connectionUsesAccessTokenAndInstanceUrl() {
        server.enqueue(tokenResponse("00Dx!tok"));

        PartnerConnection connection = provider().connection();

        assertThat(connection.getConfig().getSessionId()).isEqualTo("00Dx!tok");
        assertThat(connection.getConfig().getServiceEndpoint())
                .isEqualTo(INSTANCE_URL + "/services/Soap/u/65.0");
        assertThat(connection.getConfig().isManualLogin()).isTrue();
    }

    /**
     * Input: two {@link SalesforceSessionProvider#connection()} calls, one
     * {@link SalesforceSessionProvider#reauthenticate()} call, then one more {@code connection()} call. Output:
     * the first two calls return the same connection after one request; {@code reauthenticate()} sends the
     * second request and returns the {@code 00Dx!tok2} session; the last call returns that session with no
     * further request.
     */
    @Test
    public void connectionIsCachedAndReauthenticateRequestsOnce() {
        server.enqueue(tokenResponse("00Dx!tok"));
        server.enqueue(tokenResponse("00Dx!tok2"));
        SalesforceSessionProvider provider = provider();

        PartnerConnection first = provider.connection();
        PartnerConnection second = provider.connection();
        assertThat(second).isSameAs(first);
        assertThat(server.getRequestCount()).isEqualTo(1);

        PartnerConnection renewed = provider.reauthenticate();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(renewed.getConfig().getSessionId()).isEqualTo("00Dx!tok2");

        PartnerConnection afterRenewal = provider.connection();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(afterRenewal.getConfig().getSessionId()).isEqualTo("00Dx!tok2");
        assertThat(afterRenewal).isSameAs(renewed);
    }

    /**
     * Input: the token endpoint answers 400 {@code invalid_grant}. Output:
     * {@link UpstreamAuthenticationException} with upstream system {@code Salesforce}, after one request.
     */
    @Test
    public void token400MapsToUpstreamAuthenticationException() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}"));

        UpstreamAuthenticationException thrown =
                catchThrowableOfType(() -> provider().connection(), UpstreamAuthenticationException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getUpstreamSystem()).isEqualTo(UPSTREAM_SYSTEM);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Input: the token endpoint answers 429 with {@code Retry-After: 30}. Output:
     * {@link UpstreamRateLimitException} carrying {@code 30} and upstream system {@code Salesforce}, after one
     * request and no retry (D-020).
     */
    @Test
    public void token429MapsToUpstreamRateLimitExceptionWithRetryAfter() {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "30"));

        UpstreamRateLimitException thrown =
                catchThrowableOfType(() -> provider().connection(), UpstreamRateLimitException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getRetryAfter()).isEqualTo("30");
        assertThat(thrown.getUpstreamSystem()).isEqualTo(UPSTREAM_SYSTEM);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Input: the token endpoint answers 503. Output: {@link UpstreamUnavailableException} with upstream system
     * {@code Salesforce}, after one request and no retry (D-020).
     */
    @Test
    public void token503MapsToUpstreamUnavailableException() {
        server.enqueue(new MockResponse().setResponseCode(503));

        UpstreamUnavailableException thrown =
                catchThrowableOfType(() -> provider().connection(), UpstreamUnavailableException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.getUpstreamSystem()).isEqualTo(UPSTREAM_SYSTEM);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Input: the token endpoint reads the request and never answers; the provider uses a request factory with a
     * 1000 ms connect timeout and a 500 ms read timeout. Output: {@link UpstreamUnavailableException} with
     * upstream system {@code Salesforce} within 10 s, after exactly one request and no retry (D-020).
     */
    @Test
    public void tokenTimeoutMapsToUpstreamUnavailableExceptionAfterOneAttempt() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(500);
        SalesforceSessionProvider provider = new SalesforceSessionProvider(props, RestClient.builder(), factory);

        UpstreamUnavailableException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> catchThrowableOfType(provider::connection, UpstreamUnavailableException.class));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getUpstreamSystem()).isEqualTo(UPSTREAM_SYSTEM);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Input: the login URL of a server that has been shut down; its port refuses connections. Output:
     * {@link UpstreamUnavailableException} with upstream system {@code Salesforce} (D-020).
     */
    @Test
    public void unreachableLoginHostMapsToUpstreamUnavailableException() throws IOException {
        server.shutdown();
        serverShutDown = true;

        assertThatThrownBy(() -> provider().connection())
                .isInstanceOfSatisfying(UpstreamUnavailableException.class,
                        e -> assertThat(e.getUpstreamSystem()).isEqualTo(UPSTREAM_SYSTEM));
    }

    /**
     * Returns a 200 {@code application/json} token response with the given {@code access_token},
     * {@code instance_url} {@value #INSTANCE_URL} and the other fields of a password-grant response.
     */
    private static MockResponse tokenResponse(String accessToken) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"" + accessToken + "\","
                        + "\"instance_url\":\"" + INSTANCE_URL + "\","
                        + "\"id\":\"https://login.salesforce.com/id/00Dx/005x\","
                        + "\"token_type\":\"Bearer\","
                        + "\"issued_at\":\"1700000000000\","
                        + "\"signature\":\"sig\"}");
    }

    /** Returns a provider on {@link #props} built through the public constructor. */
    private SalesforceSessionProvider provider() {
        return new SalesforceSessionProvider(props, RestClient.builder());
    }
}
