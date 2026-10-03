package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Supplies the Salesforce partner API session of the global element {@code sfdc:config name="Salesforce"}
 * [salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml:3-5]
 * through the OAuth2 username-password grant (D-013).
 *
 * <p><b>Session lifecycle.</b> The constructor sends no request and reads no credential. The first
 * {@link #connection()} sends one token request and caches the {@link PartnerConnection} built from its
 * answer; later calls return the cached connection. {@link #refresh()} discards the cached connection,
 * sends one new token request and caches the new connection. This one cached connection stands in for
 * the original {@code sfdc:connection-pooling-profile} [watermarking.xml:4]: no pool exists and nothing
 * is opened at startup. Both methods are {@code synchronized}; concurrent callers share one session and at
 * most one token request is in flight.
 *
 * <p><b>Token request.</b> {@code POST <sfdc.login-url>/services/oauth2/token}, with one trailing {@code /}
 * of the login URL removed, sent as {@code application/x-www-form-urlencoded;charset=UTF-8} with the fields
 * in this order (D-013):
 * <ol>
 *   <li>{@code grant_type} = {@code password}</li>
 *   <li>{@code client_id} = {@code sfdc.key}</li>
 *   <li>{@code client_secret} = {@code sfdc.secret}</li>
 *   <li>{@code username} = {@code sfdc.user}</li>
 *   <li>{@code password} = {@code sfdc.password} immediately followed by {@code sfdc.securityToken}, with
 *       no delimiter</li>
 * </ol>
 * A {@code null} value is sent as an empty value. Spring's {@code FormHttpMessageConverter} encodes each
 * value exactly once in UTF-8: password {@code p+w&1} with security token {@code T0k} is sent as
 * {@code password=p%2Bw%261T0k}. The request runs on the JDK {@link HttpClient} through a
 * {@link JdkClientHttpRequestFactory}, with no interceptor and no retry: every token request is one HTTP
 * exchange (D-020).
 *
 * <p><b>Partner connection.</b> Session id {@code access_token}; service and authentication endpoint
 * {@code <instance_url>/services/Soap/u/65.0}, with one trailing {@code /} of the instance URL removed;
 * manual login enabled. SOAP {@code login()} is never called and force-wsc's built-in default endpoint is
 * never used.
 *
 * <p><b>Failures</b> (D-020), every upstream exception naming the upstream system {@code Salesforce}:
 * <ul>
 *   <li>token answer HTTP 400 or 401: {@link UpstreamAuthenticationException};</li>
 *   <li>token answer HTTP 429: {@link UpstreamRateLimitException} carrying the {@code Retry-After} header
 *       value, or none when the header is absent;</li>
 *   <li>connection refused, connect or read timeout, or another I/O failure of the exchange:
 *       {@link UpstreamUnavailableException};</li>
 *   <li>any other non-2xx token answer: {@link IllegalStateException} naming the status;</li>
 *   <li>a 2xx answer whose body cannot be read as JSON, or that lacks a non-blank {@code access_token} or
 *       {@code instance_url}: {@link IllegalStateException};</li>
 *   <li>an unset or blank {@code sfdc.login-url}: {@link IllegalStateException}, before any request;</li>
 *   <li>a {@code sfdc.login-url} that is not an absolute {@code http} or {@code https} URL:
 *       {@link IllegalArgumentException}, before any request;</li>
 *   <li>a partner connection that force-wsc refuses to create: {@link IllegalStateException}.</li>
 * </ul>
 * No exception message or log line carries the response body, the username, the password, the security
 * token, the consumer key, the consumer secret or the access token. Log lines are written at DEBUG and
 * name only the token URL and the response status.
 *
 * <p>Example:
 * <pre>{@code
 * SalesforceSessionProvider provider =
 *         new SalesforceSessionProvider(properties, beanFactory.getBeanProvider(RestClient.Builder.class));
 * PartnerConnection first = provider.connection();   // one token request
 * PartnerConnection same = provider.connection();    // the cached connection, no request
 * provider.refresh();                                // one new token request, new cached connection
 * }</pre>
 */
@Component
public class SalesforceSessionProvider {

    /** Upstream system name carried by every upstream exception of this class (D-020). */
    private static final String UPSTREAM = "Salesforce";

    /** Path of the partner SOAP API, version 65.0, appended to the token's {@code instance_url}. */
    static final String API_VERSION_PATH = "/services/Soap/u/65.0";

    /** Path of the OAuth2 token endpoint, appended to {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Message of the failure raised for a 2xx token answer without a usable session. */
    private static final String INCOMPLETE_TOKEN_MESSAGE =
            "Salesforce token response lacks access_token or instance_url";

    /** Message of every {@link UpstreamUnavailableException} raised for the token request. */
    private static final String UNREACHABLE_MESSAGE = "Salesforce token endpoint unreachable";

    /** JSON object type of the token answer; members other than the two read are ignored. */
    private static final ParameterizedTypeReference<Map<String, Object>> TOKEN_RESPONSE_TYPE =
            ParameterizedTypeReference.forType(
                    ResolvableType.forClassWithGenerics(Map.class, String.class, Object.class).getType());

    /** DEBUG log of the token URL and the token answer's status. */
    private static final Logger log = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Bound {@code sfdc.*} keys, read when a token request is sent. */
    private final SalesforceProperties props;

    /** Client of the token endpoint; replaced only by {@link #setTimeouts(Duration, Duration)}. */
    private RestClient restClient;

    /** The cached partner connection, or {@code null} before the first successful authentication. */
    private PartnerConnection connection;

    /** Connect timeout applied to connections built after {@link #setTimeouts}, or {@code null}. */
    private Duration connectTimeout;

    /** Read timeout applied to connections built after {@link #setTimeouts}, or {@code null}. */
    private Duration readTimeout;

    /**
     * Creates the provider without contacting Salesforce and without reading any credential.
     *
     * <p>The token client is built from the {@code RestClient.Builder} bean when the context holds one, and
     * from {@link RestClient#builder()} otherwise. Its request interceptors are removed and its request
     * factory is a {@link JdkClientHttpRequestFactory} over {@link HttpClient#newHttpClient()}, which
     * replaces any factory already set on the builder; the builder's message converters are kept (D-020).
     *
     * @param props    the bound {@code sfdc.*} keys
     * @param builders source of the {@code RestClient.Builder} that builds the token client
     * @throws NullPointerException if either argument is {@code null}
     */
    public SalesforceSessionProvider(SalesforceProperties props, ObjectProvider<RestClient.Builder> builders) {
        this.props = Objects.requireNonNull(props, "props");
        RestClient.Builder builder = Objects.requireNonNull(builders, "builders").getIfAvailable(RestClient::builder);
        // No interceptor and the JDK request factory: one HTTP exchange per token request (D-020).
        this.restClient = builder
                .requestInterceptors(List::clear)
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
                .build();
    }

    /**
     * Returns the cached partner connection, authenticating with one token request when none is cached
     * (D-013).
     *
     * <p>A failed authentication caches nothing; the next call sends a new token request. No call is
     * retried (D-020).
     *
     * @return the cached or newly created partner connection
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    if the token endpoint cannot be reached, times out or the
     *                                         exchange fails with an I/O error
     * @throws IllegalStateException           for any other non-2xx answer, an unreadable or incomplete
     *                                         token answer, an unset {@code sfdc.login-url}, or a partner
     *                                         connection that cannot be created
     * @throws IllegalArgumentException        if {@code sfdc.login-url} is not an absolute {@code http} or
     *                                         {@code https} URL
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            connection = authenticate();
        }
        return connection;
    }

    /**
     * Discards the cached partner connection, authenticates with one new token request and caches the new
     * connection (D-013).
     *
     * <p>A failed authentication leaves no connection cached, and the next {@link #connection()} sends a new
     * token request. The exceptions of the token request propagate unchanged; the single re-issue of a
     * vendor call after re-authentication belongs to {@code SalesforceClient.execute} (D-020).
     *
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    if the token endpoint cannot be reached, times out or the
     *                                         exchange fails with an I/O error
     * @throws IllegalStateException           for any other non-2xx answer, an unreadable or incomplete
     *                                         token answer, an unset {@code sfdc.login-url}, or a partner
     *                                         connection that cannot be created
     * @throws IllegalArgumentException        if {@code sfdc.login-url} is not an absolute {@code http} or
     *                                         {@code https} URL
     */
    public synchronized void refresh() {
        connection = null;
        connection = authenticate();
    }

    /**
     * Sets the connect and read timeouts of the token request and of every partner connection built
     * afterwards; the cached connection, if any, keeps its settings.
     *
     * <p>The token client is rebuilt from the current one with a {@link JdkClientHttpRequestFactory} whose
     * read timeout is {@code read}, over an {@link HttpClient} whose connect timeout is {@code connect}.
     * Every {@link ConnectorConfig} built afterwards gets {@code connect} as its connection timeout and
     * {@code read} as its read timeout, truncated to whole milliseconds. Without a call, the library
     * defaults apply.
     *
     * @param connect connect timeout, at least one millisecond and at most {@link Integer#MAX_VALUE}
     *                milliseconds
     * @param read    read timeout, at least one millisecond and at most {@link Integer#MAX_VALUE}
     *                milliseconds
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if either duration lies outside that range
     */
    synchronized void setTimeouts(Duration connect, Duration read) {
        requireTimeoutMillis(connect, "connect");
        requireTimeoutMillis(read, "read");
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        this.restClient = restClient.mutate().requestFactory(factory).build();
        this.connectTimeout = connect;
        this.readTimeout = read;
    }

    /**
     * Sends one token request and builds a partner connection from its answer (D-013).
     *
     * @return a partner connection on the token's session and instance
     * @throws IllegalStateException when the answer lacks a non-blank {@code access_token} or
     *                               {@code instance_url}, and for the failures of
     *                               {@link #requestToken()} and {@link #build(String, String)}
     */
    private PartnerConnection authenticate() {
        Map<String, Object> token = requestToken();
        String accessToken = nonBlankString(token, "access_token");
        String instanceUrl = nonBlankString(token, "instance_url");
        if (accessToken == null || instanceUrl == null) {
            throw new IllegalStateException(INCOMPLETE_TOKEN_MESSAGE);
        }
        return build(accessToken, instanceUrl);
    }

    /**
     * Posts the password-grant form to the token endpoint once and classifies its failures (D-013, D-020).
     *
     * @return the JSON object of a 2xx answer, or {@code null} when the answer has no body
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    if the exchange fails with an I/O error
     * @throws IllegalStateException           when {@code sfdc.login-url} is unset or blank, for any other
     *                                         non-2xx answer, and for a 2xx body that is not readable JSON
     */
    private Map<String, Object> requestToken() {
        String loginUrl = props.loginUrl();
        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException("Salesforce login URL sfdc.login-url is not set");
        }
        URI tokenUri = URI.create(stripTrailingSlash(loginUrl) + TOKEN_PATH);
        log.debug("Requesting Salesforce token from {}", tokenUri);
        try {
            ResponseEntity<Map<String, Object>> response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(passwordGrantForm())
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), SalesforceSessionProvider::rejectTokenAnswer)
                    .toEntity(TOKEN_RESPONSE_TYPE);
            log.debug("Salesforce token endpoint {} answered {}", tokenUri, response.getStatusCode().value());
            return response.getBody();
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(UNREACHABLE_MESSAGE, UPSTREAM, e);
        } catch (RestClientException e) {
            // An IOException as the direct cause is an I/O failure while the 2xx answer is read (D-020).
            if (e.getCause() instanceof IOException) {
                throw new UpstreamUnavailableException(UNREACHABLE_MESSAGE, UPSTREAM, e);
            }
            throw new IllegalStateException("Salesforce token response could not be read", e);
        }
    }

    /**
     * Raises the failure for a non-2xx token answer without reading its body (D-020).
     *
     * @param request  the token request
     * @param response the non-2xx token answer
     * @throws IOException                     if the status line or headers cannot be read
     * @throws UpstreamAuthenticationException for status 400 or 401, with no cause
     * @throws UpstreamRateLimitException      for status 429, with the {@code Retry-After} value or
     *                                         {@code null}, and no cause
     * @throws IllegalStateException           for every other status
     */
    private static void rejectTokenAnswer(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        log.debug("Salesforce token endpoint {} answered {}", request.getURI(), status);
        if (status == 400 || status == 401) {
            throw new UpstreamAuthenticationException("Salesforce token request rejected: " + status, UPSTREAM, null);
        }
        if (status == 429) {
            throw new UpstreamRateLimitException("Salesforce token request rate limited", UPSTREAM,
                    response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), null);
        }
        throw new IllegalStateException("Salesforce token request failed: " + status);
    }

    /**
     * Returns the password-grant form, fields in the order {@code grant_type}, {@code client_id},
     * {@code client_secret}, {@code username}, {@code password}; no value is pre-encoded (D-013).
     *
     * @return the unencoded form fields
     */
    private MultiValueMap<String, String> passwordGrantForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        // A null value is sent as an empty value, every field in name=value form.
        form.add("client_id", nullToEmpty(props.key()));
        form.add("client_secret", nullToEmpty(props.secret()));
        form.add("username", nullToEmpty(props.user()));
        form.add("password", nullToEmpty(props.password()) + nullToEmpty(props.securityToken()));
        return form;
    }

    /**
     * Builds a partner connection on the token's session: manual login, session id {@code accessToken},
     * service and authentication endpoint {@code <instanceUrl>/services/Soap/u/65.0}, and the timeouts of
     * {@link #setTimeouts} when they were set (D-013).
     *
     * @param accessToken the non-blank {@code access_token} of the token answer
     * @param instanceUrl the non-blank {@code instance_url} of the token answer
     * @return the partner connection
     * @throws IllegalStateException if force-wsc refuses the configuration
     */
    private PartnerConnection build(String accessToken, String instanceUrl) {
        ConnectorConfig config = new ConnectorConfig();
        config.setManualLogin(true);
        config.setSessionId(accessToken);
        String endpoint = stripTrailingSlash(instanceUrl) + API_VERSION_PATH;
        config.setServiceEndpoint(endpoint);
        config.setAuthEndpoint(endpoint);
        if (connectTimeout != null && readTimeout != null) {
            config.setConnectionTimeout((int) connectTimeout.toMillis());
            config.setReadTimeout((int) readTimeout.toMillis());
        }
        try {
            return new PartnerConnection(config);
        } catch (ConnectionException e) {
            throw new IllegalStateException("Salesforce partner connection could not be created", e);
        }
    }

    /**
     * Checks that a timeout lies between 1 millisecond and {@link Integer#MAX_VALUE} milliseconds, both
     * included.
     *
     * @param timeout the timeout to check
     * @param name    {@code connect} or {@code read}, used in the exception message
     * @throws NullPointerException     if {@code timeout} is {@code null}
     * @throws IllegalArgumentException if the timeout lies outside that range
     */
    private static void requireTimeoutMillis(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "Salesforce " + name + " timeout must lie between 1 ms and " + Integer.MAX_VALUE + " ms");
        }
    }

    /**
     * Returns the named member of the token answer when it is a non-blank string.
     *
     * @param token  the JSON object of the token answer; may be {@code null}
     * @param member the member name
     * @return the member value, or {@code null} when the answer, the member or its text is missing, or the
     *         member is not a JSON string
     */
    private static String nonBlankString(Map<String, Object> token, String member) {
        if (token == null) {
            return null;
        }
        Object value = token.get(member);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /**
     * Removes one trailing {@code /}: {@code https://login.salesforce.com/} becomes
     * {@code https://login.salesforce.com}, and {@code https://host//} becomes {@code https://host/}.
     *
     * @param url a non-null URL
     * @return the URL without its last character when that character is {@code /}, otherwise the URL
     */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Returns the value, or the empty string for {@code null}.
     *
     * @param value a form value; may be {@code null}
     * @return the value or {@code ""}
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
