package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamRateLimitException;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectorConfig;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Tests of the OAuth 2.0 username-password token request that {@link SalesforceSessionProvider} sends in
 * place of the {@code sfdc:config} element
 * [salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:4-6]
 * (D-013, D-015, D-020).
 *
 * <p>Each test starts one local {@link MockWebServer} and passes its base URL, without a trailing slash,
 * as {@code sfdc.login-url}. The provider is built through its package-private constructor from a
 * directly constructed {@link SalesforceProperties} holding the test values of {@code sfdc.user},
 * {@code sfdc.password} ({@code p+w&1}), {@code sfdc.securityToken} ({@code T0k}), {@code sfdc.key},
 * {@code sfdc.secret} and {@code sfdc.login-url}, a {@link JdkClientHttpRequestFactory} with a 2 s
 * connect timeout and a 5 s read timeout, and a no-op {@link ConnectorConfig} customizer. No Spring
 * application context starts and no request leaves the loopback interface.
 *
 * <p>The tests assert:
 * <ul>
 *   <li>the token request is one {@code POST /services/oauth2/token} with an
 *       {@code application/x-www-form-urlencoded} body carrying {@code grant_type=password},
 *       {@code client_id}, {@code client_secret}, {@code username} and exactly one {@code password}
 *       field, whose raw form is {@code password=p%2Bw%261T0k} (D-013, D-015);</li>
 *   <li>the Partner connection carries the {@code access_token} as session id and the service endpoint
 *       {@code <instance_url>/services/Soap/u/65.0}, not force-wsc's bundled
 *       {@link Connector#END_POINT}, and opening it sends no SOAP request (D-013);</li>
 *   <li>HTTP 400 and HTTP 401 answers each raise {@link UpstreamAuthenticationException}, and HTTP 429
 *       raises {@link UpstreamRateLimitException} with the {@code Retry-After} value when one is sent,
 *       each with upstream system {@code Salesforce} after exactly one token request (D-020).</li>
 * </ul>
 * A second token answer is queued behind every first answer; the request count of {@code 1} shows that
 * it was never requested.
 */
public class SalesforceTokenRequestTest {

    /** Test value of {@code sfdc.user}. */
    private static final String USER = "batch.user@example.com";

    /** Test value of {@code sfdc.password}; holds the form-reserved characters {@code +} and {@code &}. */
    private static final String PASSWORD = "p+w&1";

    /** Test value of {@code sfdc.securityToken}. */
    private static final String SECURITY_TOKEN = "T0k";

    /** Test value of {@code sfdc.key}, the connected-app consumer key. */
    private static final String KEY = "test-consumer-key";

    /** Test value of {@code sfdc.secret}, the connected-app consumer secret. */
    private static final String SECRET = "test-consumer-secret";

    /** {@code access_token} member of the first token answer. */
    private static final String ACCESS_TOKEN = "access-token";

    /** {@code access_token} member of the queued second token answer. */
    private static final String SECOND_ACCESS_TOKEN = "second-access-token";

    /** Path of the OAuth 2.0 token endpoint below {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Partner API SOAP path appended to {@code instance_url}. */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** Raw form field of {@code p+w&1} followed by {@code T0k}, form-encoded once (D-013). */
    private static final String RAW_PASSWORD_FIELD = "password=p%2Bw%261T0k";

    /** Connect timeout of the test request factory. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** Read timeout of the test request factory. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Upper bound of each wait for a recorded request, in seconds. */
    private static final long TAKE_TIMEOUT_SECONDS = 5;

    private MockWebServer server;

    private String baseUrl;

    private SalesforceSessionProvider provider;

    /**
     * Starts the local token endpoint and builds the provider with {@code sfdc.login-url} set to the
     * server's base URL without a trailing slash.
     */
    @BeforeEach
    void startTokenEndpoint() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://" + server.getHostName() + ":" + server.getPort();
        SalesforceProperties properties =
                new SalesforceProperties(USER, PASSWORD, SECURITY_TOKEN, KEY, SECRET, baseUrl);
        provider = new SalesforceSessionProvider(properties, shortTimeoutRequestFactory(), config -> { });
    }

    /** Stops the local token endpoint. */
    @AfterEach
    void stopTokenEndpoint() throws IOException {
        server.shutdown();
    }

    /**
     * A successful token answer yields one form-encoded password-grant {@code POST} and a Partner
     * connection on {@code <instance_url>/services/Soap/u/65.0} with the access token as session id; a
     * second {@link SalesforceSessionProvider#connection()} call returns the same connection and sends no
     * request (D-013, D-015).
     */
    @Test
    @DisplayName("token HTTP 200: one form-encoded password grant and the Partner endpoint of instance_url")
    public void passwordGrantSendsOneFormRequestAndOpensInstanceEndpoint() throws InterruptedException {
        server.enqueue(tokenAnswer(ACCESS_TOKEN));
        server.enqueue(tokenAnswer(SECOND_ACCESS_TOKEN));

        PartnerConnection connection = provider.connection();

        RecordedRequest request = takeTokenRequest();
        MediaType contentType = MediaType.parseMediaType(request.getHeader("Content-Type"));
        assertThat(contentType.equalsTypeAndSubtype(MediaType.APPLICATION_FORM_URLENCODED))
                .as("Content-Type %s", contentType)
                .isTrue();
        assertThat(contentType.getCharset()).isIn(null, UTF_8);

        String rawBody = request.getBody().readUtf8();
        List<String> rawFields = List.of(rawBody.split("&", -1));
        assertThat(rawBody).contains(RAW_PASSWORD_FIELD);
        assertThat(rawFields)
                .as("raw password fields of %s", rawBody)
                .filteredOn(field -> field.startsWith("password="))
                .containsExactly(RAW_PASSWORD_FIELD);

        Map<String, List<String>> fields = decodeForm(rawBody);
        assertThat(fields).containsOnlyKeys("grant_type", "client_id", "client_secret", "username", "password");
        assertThat(fields.get("grant_type")).containsExactly("password");
        assertThat(fields.get("client_id")).containsExactly(KEY);
        assertThat(fields.get("client_secret")).containsExactly(SECRET);
        assertThat(fields.get("username")).containsExactly(USER);
        assertThat(fields.get("password")).containsExactly(PASSWORD + SECURITY_TOKEN);

        ConnectorConfig config = connection.getConfig();
        assertThat(config.getServiceEndpoint())
                .isEqualTo(baseUrl + SOAP_PATH)
                .isNotEqualTo(Connector.END_POINT);
        assertThat(config.getSessionId()).isEqualTo(ACCESS_TOKEN);

        assertThat(provider.connection()).isSameAs(connection);
        assertThat(server.getRequestCount()).as("token and SOAP requests received").isEqualTo(1);
    }

    /**
     * A token answer of HTTP 400 raises {@link UpstreamAuthenticationException} with upstream system
     * {@code Salesforce} after exactly one token request (D-020).
     */
    @Test
    @DisplayName("token HTTP 400: UpstreamAuthenticationException from Salesforce after one token request")
    public void tokenHttp400RaisesUpstreamAuthenticationAfterOneRequest() throws InterruptedException {
        assertAuthenticationRejection(400);
    }

    /**
     * A token answer of HTTP 401 raises {@link UpstreamAuthenticationException} with upstream system
     * {@code Salesforce} after exactly one token request (D-020).
     */
    @Test
    @DisplayName("token HTTP 401: UpstreamAuthenticationException from Salesforce after one token request")
    public void tokenHttp401RaisesUpstreamAuthenticationAfterOneRequest() throws InterruptedException {
        assertAuthenticationRejection(401);
    }

    /**
     * A token answer of HTTP 429 with {@code Retry-After: 17} raises {@link UpstreamRateLimitException}
     * with upstream system {@code Salesforce} and Retry-After value {@code 17} after exactly one token
     * request (D-020).
     */
    @Test
    @DisplayName("token HTTP 429 with Retry-After 17: UpstreamRateLimitException carrying 17 after one token request")
    public void tokenHttp429RaisesUpstreamRateLimitWithRetryAfterAfterOneRequest() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "17"));
        server.enqueue(tokenAnswer(SECOND_ACCESS_TOKEN));

        UpstreamRateLimitException failure = assertThrows(UpstreamRateLimitException.class, provider::connection);

        assertThat(failure.upstreamSystem()).isEqualTo("Salesforce");
        assertThat(failure.retryAfter().orElseThrow()).isEqualTo("17");
        takeTokenRequest();
        assertThat(server.getRequestCount()).as("token requests after HTTP 429").isEqualTo(1);
    }

    /**
     * A token answer of HTTP 429 without a {@code Retry-After} header raises
     * {@link UpstreamRateLimitException} with upstream system {@code Salesforce} and no Retry-After value
     * after exactly one token request (D-020).
     */
    @Test
    @DisplayName("token HTTP 429 without Retry-After: UpstreamRateLimitException with no value after one token request")
    public void tokenHttp429WithoutRetryAfterRaisesUpstreamRateLimitWithoutValue() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(tokenAnswer(SECOND_ACCESS_TOKEN));

        UpstreamRateLimitException failure = assertThrows(UpstreamRateLimitException.class, provider::connection);

        assertThat(failure.upstreamSystem()).isEqualTo("Salesforce");
        assertThat(failure.retryAfter()).isEmpty();
        takeTokenRequest();
        assertThat(server.getRequestCount()).as("token requests after HTTP 429").isEqualTo(1);
    }

    /**
     * Answers the token request with {@code status} and asserts {@link UpstreamAuthenticationException}
     * with upstream system {@code Salesforce} and exactly one token request.
     */
    private void assertAuthenticationRejection(int status) throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_grant\",\"error_description\":\"authentication failure\"}"));
        server.enqueue(tokenAnswer(SECOND_ACCESS_TOKEN));

        UpstreamAuthenticationException failure =
                assertThrows(UpstreamAuthenticationException.class, provider::connection, "HTTP " + status);

        assertThat(failure.upstreamSystem()).as("upstream system after HTTP %d", status).isEqualTo("Salesforce");
        takeTokenRequest();
        assertThat(server.getRequestCount()).as("token requests after HTTP %d", status).isEqualTo(1);
    }

    /**
     * Returns the next recorded request within {@value #TAKE_TIMEOUT_SECONDS} s and asserts that it is a
     * {@code POST} to exactly {@code /services/oauth2/token}.
     */
    private RecordedRequest takeTokenRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(TAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(request).as("token request within %d s", TAKE_TIMEOUT_SECONDS).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo(TOKEN_PATH);
        return request;
    }

    /**
     * Returns a JSON token answer with the given {@code access_token} and the server's base URL as
     * {@code instance_url}.
     */
    private MockResponse tokenAnswer(String accessToken) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"" + accessToken + "\",\"instance_url\":\"" + baseUrl + "\"}");
    }

    /**
     * Returns a {@link JdkClientHttpRequestFactory} over an HTTP/1.1 {@link HttpClient} with the
     * {@link #CONNECT_TIMEOUT} connect timeout and the {@link #READ_TIMEOUT} read timeout.
     */
    private static ClientHttpRequestFactory shortTimeoutRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * Splits a raw {@code application/x-www-form-urlencoded} body on {@code &} and {@code =} and decodes
     * each name and value as UTF-8, keeping repeated names in order.
     */
    private static Map<String, List<String>> decodeForm(String rawBody) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (String pair : rawBody.split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            fields.computeIfAbsent(URLDecoder.decode(name, UTF_8), key -> new ArrayList<>())
                    .add(URLDecoder.decode(value, UTF_8));
        }
        return fields;
    }
}
