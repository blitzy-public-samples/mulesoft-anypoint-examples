package com.mulesoft.examples.salesforce_data_retrieval.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mulesoft.examples.salesforce_data_retrieval.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Obtains and caches a Salesforce partner API session through the OAuth2 username-password grant
 * (D-013, D-015); never retries (D-020). Replaces the global element {@code sfdc:config} named
 * {@code Salesforce} [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:3].
 *
 * <p>Authentication is lazy: the constructor makes no network call and reads no credential. The
 * first {@link #connection()} sends one token request and caches the resulting
 * {@link PartnerConnection}; later calls return the cached connection. {@link #refresh()} always
 * sends one new token request and replaces the cached connection. Both methods are
 * {@code synchronized}: concurrent callers share one session, and at most one token request is in
 * flight.
 *
 * <p>The token request is a {@code POST} of {@code application/x-www-form-urlencoded} to
 * {@code <sfdc.login-url>/services/oauth2/token}, every trailing {@code /} of the login URL removed
 * first, with the fields in this order (D-013, D-015):
 * <ol>
 *   <li>{@code grant_type=password}</li>
 *   <li>{@code client_id} = {@code sfdc.key}</li>
 *   <li>{@code client_secret} = {@code sfdc.secret}</li>
 *   <li>{@code username} = {@code sfdc.username}</li>
 *   <li>{@code password} = {@code sfdc.password} immediately followed by
 *       {@code sfdc.securityToken}, with no delimiter</li>
 * </ol>
 * Spring's {@code FormHttpMessageConverter} encodes each value exactly once in UTF-8, for example
 * password {@code p+w&1} with token {@code T0k} is sent as {@code password=p%2Bw%261T0k}.
 *
 * <p>The returned connection carries the token's {@code access_token} as its session id and
 * {@code <instance_url>/services/Soap/u/65.0} as its service endpoint, with manual login enabled;
 * SOAP {@code login()} is never called and force-wsc's default endpoint is never used.
 *
 * <p>Failures of the token request map as follows, each after exactly one HTTP request (D-020):
 * <ul>
 *   <li>HTTP 400 or 401: {@link UpstreamAuthenticationException};</li>
 *   <li>HTTP 429: {@link UpstreamRateLimitException} carrying the {@code Retry-After} value, or
 *       {@code null} when the header is absent;</li>
 *   <li>any other error status: {@link IllegalStateException} with the status and response body;</li>
 *   <li>an I/O failure (connection refused, reset, timeout): {@link UpstreamUnavailableException};</li>
 *   <li>a 2xx answer whose body cannot be read, or lacks {@code access_token} or
 *       {@code instance_url}: {@link IllegalStateException}.</li>
 * </ul>
 * No exception message or log line contains the password, security token, consumer key or
 * consumer secret.
 *
 * <pre>{@code
 * SalesforceSessionProvider provider = new SalesforceSessionProvider(
 *         new SalesforceProperties("user@example.com", "p+w&1", "T0k", "key", "secret",
 *                 "https://login.salesforce.com"),
 *         RestClient.builder());
 * PartnerConnection connection = provider.connection();   // one token request
 * provider.connection();                                  // cached, no request
 * PartnerConnection renewed = provider.refresh();         // one new token request
 * }</pre>
 */
@Component
public class SalesforceSessionProvider {

    /** Upstream system name carried by every upstream exception of this class (D-020). */
    private static final String UPSTREAM = "Salesforce";

    /** Path of the OAuth2 token endpoint, appended to {@code sfdc.login-url}. */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Path of the partner SOAP API, version 65.0, appended to the token's {@code instance_url}. */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    private static final Logger log = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Bound {@code sfdc.*} values; read only when a token request is sent. */
    private final SalesforceProperties properties;

    /** Client of the token endpoint: JDK HTTP client, no interceptor, no retry, no base URL. */
    private final RestClient restClient;

    /** The cached partner connection, or {@code null} before the first successful authentication. */
    private PartnerConnection connection;

    /**
     * Creates the provider without contacting Salesforce and without reading any credential.
     *
     * <p>The token client is built from {@code restClientBuilder} with a
     * {@link JdkClientHttpRequestFactory}, which replaces any request factory already set on the
     * builder; no interceptor, retry handler or base URL is added (D-020). The builder's message
     * converters are kept.
     *
     * @param properties        the bound {@code sfdc.*} keys
     * @param restClientBuilder the builder of the token client, for example Boot's prototype
     *                          {@code RestClient.Builder} bean or {@code RestClient.builder()}
     * @throws NullPointerException if either argument is {@code null}
     */
    public SalesforceSessionProvider(SalesforceProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = Objects.requireNonNull(properties, "properties");
        // Any request factory set on the builder is replaced; the JDK client sends each POST once (D-020).
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder")
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    /**
     * Returns the cached partner connection, authenticating with one token request when none is
     * cached (D-013).
     *
     * <p>A failed authentication caches nothing, and the next call sends a new token request. The
     * call is never retried (D-020).
     *
     * @return the cached or newly created partner connection
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    if the token endpoint cannot be reached or the
     *                                         exchange fails with an I/O error
     * @throws IllegalStateException           for any other failure of the token request or of the
     *                                         partner connection
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            connection = authenticate();
        }
        return connection;
    }

    /**
     * Discards the cached partner connection, authenticates with one new token request, caches the
     * new connection and returns it (D-013).
     *
     * <p>A failed authentication leaves no connection cached, and the next {@link #connection()}
     * sends a new token request. The call is never retried; the single re-issue of a vendor call
     * after re-authentication belongs to {@code SalesforceClient.execute} (D-020).
     *
     * @return the newly created partner connection
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    if the token endpoint cannot be reached or the
     *                                         exchange fails with an I/O error
     * @throws IllegalStateException           for any other failure of the token request or of the
     *                                         partner connection
     */
    public synchronized PartnerConnection refresh() {
        connection = null;
        connection = authenticate();
        return connection;
    }

    /**
     * Sends one token request and builds a partner connection from its answer (D-013).
     *
     * @return a partner connection on the token's session and instance
     * @throws UpstreamAuthenticationException if the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      if the token endpoint answers 429
     * @throws UpstreamUnavailableException    on an I/O failure of the token request
     * @throws IllegalStateException           when {@code sfdc.login-url} is unset, for any other
     *                                         error status, for an unreadable or incomplete token
     *                                         response, or when the partner connection cannot be
     *                                         created
     */
    private PartnerConnection authenticate() {
        String loginUrl = properties.loginUrl();
        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException("Salesforce login URL sfdc.login-url is not set");
        }
        String tokenUrl = stripTrailingSlashes(loginUrl) + TOKEN_PATH;
        log.info("Requesting Salesforce session from {}", tokenUrl);

        TokenResponse token = requestToken(tokenUrl, passwordGrantForm());
        if (token == null || isBlank(token.accessToken()) || isBlank(token.instanceUrl())) {
            throw new IllegalStateException("Salesforce token response is missing access_token or instance_url");
        }
        return partnerConnection(token);
    }

    /**
     * Posts the form to the token endpoint once and classifies its failures (D-020).
     *
     * @param tokenUrl the absolute token endpoint URL
     * @param form     the password-grant form fields
     * @return the bound token response, or {@code null} when the answer has no body
     */
    private TokenResponse requestToken(String tokenUrl, MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 400 || code == 401) {
                throw new UpstreamAuthenticationException(UPSTREAM,
                        "Salesforce token request rejected with status " + code, e);
            }
            if (code == 429) {
                HttpHeaders headers = e.getResponseHeaders();
                String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                throw new UpstreamRateLimitException(UPSTREAM, "Salesforce token request rate limited", retryAfter, e);
            }
            throw new IllegalStateException(
                    "Salesforce token request failed with status " + code + ": " + e.getResponseBodyAsString(), e);
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(UPSTREAM,
                    "Salesforce token endpoint unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            // Any other client failure, such as a 2xx answer whose body is not JSON or does not parse.
            throw new IllegalStateException("Salesforce token response could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the password-grant form, fields in the order {@code grant_type}, {@code client_id},
     * {@code client_secret}, {@code username}, {@code password} (D-013, D-015).
     *
     * <p>{@code password} is the password immediately followed by the security token. A
     * {@code null} value is sent as an empty value. No value is pre-encoded.
     *
     * @return the form fields, unencoded
     */
    private MultiValueMap<String, String> passwordGrantForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", nullToEmpty(properties.key()));
        form.add("client_secret", nullToEmpty(properties.secret()));
        form.add("username", nullToEmpty(properties.username()));
        form.add("password", nullToEmpty(properties.password()) + nullToEmpty(properties.securityToken()));
        return form;
    }

    /**
     * Builds a partner connection on the token's session: session id {@code access_token}, service
     * endpoint {@code <instance_url>/services/Soap/u/65.0} with every trailing {@code /} of the
     * instance URL removed first, manual login enabled, and no proxy, timeout or trace setting.
     *
     * @param token a token response with a non-blank access token and instance URL
     * @return the partner connection
     * @throws IllegalStateException if force-wsc rejects the configuration
     */
    private static PartnerConnection partnerConnection(TokenResponse token) {
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(token.accessToken());
        config.setServiceEndpoint(stripTrailingSlashes(token.instanceUrl()) + SOAP_PATH);
        config.setManualLogin(true);
        try {
            return new PartnerConnection(config);
        } catch (ConnectionException e) {
            throw new IllegalStateException("Salesforce partner connection could not be created", e);
        }
    }

    /**
     * Removes every trailing {@code /}: {@code http://localhost:8080//} becomes
     * {@code http://localhost:8080}.
     *
     * @param url a non-null URL
     * @return the URL without trailing slashes
     */
    private static String stripTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
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

    /**
     * Returns whether the value is {@code null}, empty or whitespace only.
     *
     * @param value the value to test; may be {@code null}
     * @return {@code true} for a missing value
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The members of the OAuth2 token response that the partner connection needs; {@code id},
     * {@code token_type}, {@code issued_at}, {@code signature} and any other member are ignored.
     *
     * @param accessToken the {@code access_token} member, used as the SOAP session id
     * @param instanceUrl the {@code instance_url} member, the base URL of the partner endpoint
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("instance_url") String instanceUrl) {
    }
}
