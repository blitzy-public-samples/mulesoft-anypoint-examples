package com.mulesoft.examples.import_leads_into_salesforce.client;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mulesoft.examples.import_leads_into_salesforce.config.SalesforceProperties;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Provides the Salesforce partner API session used by {@code SalesforceClient}, replacing the global element
 * {@code sfdc:config name="Salesforce"} with its {@code sfdc:connection-pooling-profile}
 * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:4-6].
 *
 * <p>The session comes from the OAuth2 username-password grant (D-013): one {@code POST} of
 * {@code ${sfdc.login-url}/services/oauth2/token} carrying {@code sfdc.key}, {@code sfdc.secret},
 * {@code sfdc.user} and {@code sfdc.password} followed directly by {@code sfdc.securityToken} (key names
 * D-015). The returned {@code access_token} becomes the session id and {@code instance_url} plus
 * {@code /services/Soap/u/65.0} becomes the service endpoint of a {@link PartnerConnection} in manual-login
 * mode; {@link PartnerConnection#login(String, String)} is never called.
 *
 * <p>One {@link PartnerConnection} is created on the first {@link #connection()} call and cached for every
 * later call; {@link #reauthenticate()} replaces it, and {@code SalesforceClient} calls that exactly once
 * per {@code INVALID_SESSION_ID} fault (D-020, D-306). No constructor and no startup hook authenticates, and
 * this class sends exactly one HTTP request per authentication, with no retry.
 *
 * <p>Token request failures map to the upstream exceptions with upstream system {@code Salesforce}
 * (D-020, D-488):
 * <ul>
 *   <li>HTTP 429: {@link UpstreamRateLimitException} carrying the response's {@code Retry-After} value;</li>
 *   <li>any other 4xx: {@link UpstreamAuthenticationException};</li>
 *   <li>5xx or any other non-4xx error status, and I/O failures such as a refused connection or a
 *       timeout: {@link UpstreamUnavailableException};</li>
 *   <li>a 2xx response whose body cannot be read, is empty, or lacks {@code access_token} or
 *       {@code instance_url}: {@link UpstreamAuthenticationException}.</li>
 * </ul>
 * Exception messages and log lines never contain the password, the security token, the client secret or
 * the access token.
 *
 * <p>Example:
 * <pre>{@code
 * PartnerConnection c = sessionProvider.connection();        // first call: one token request
 * QueryResult r = c.query("SELECT Id FROM Lead");
 * PartnerConnection fresh = sessionProvider.reauthenticate(); // one new token request
 * }</pre>
 *
 * <p>Thread safety: {@link #connection()} and {@link #reauthenticate()} are synchronized on this instance,
 * which guards the cached connection.
 */
@Component
public class SalesforceSessionProvider {

    /** Upstream system name carried by every exception this class throws. */
    static final String UPSTREAM_SYSTEM = "Salesforce";

    /** Path of the OAuth2 token endpoint, appended to {@code sfdc.login-url} (D-013). */
    static final String TOKEN_PATH = "/services/oauth2/token";

    /** Path of the partner SOAP endpoint for API version 65.0, appended to the token's {@code instance_url}. */
    static final String SOAP_PATH = "/services/Soap/u/65.0";

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Bound {@code sfdc.*} settings: credentials, connected-app key and secret, and login URL. */
    private final SalesforceProperties properties;

    /** HTTP client for the token request: no base URL, no interceptor, form and JSON converters. */
    private final RestClient restClient;

    /** Cached session; {@code null} until the first successful authentication. Guarded by {@code this}. */
    private PartnerConnection connection;

    /**
     * Creates the provider with a {@link JdkClientHttpRequestFactory} at its default settings. Sends no
     * request.
     *
     * @param properties        bound {@code sfdc.*} settings
     * @param restClientBuilder builder the token client is cloned from; it is not modified
     */
    @Autowired
    public SalesforceSessionProvider(SalesforceProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, restClientBuilder, new JdkClientHttpRequestFactory());
    }

    /**
     * Creates the provider with the given request factory. Clones {@code restClientBuilder}, sets
     * {@code requestFactory} on the clone and adds a {@link FormHttpMessageConverter} when the clone holds
     * no {@link FormHttpMessageConverter} or subclass of it. Sets no base URL and no interceptor. Sends no
     * request.
     *
     * @param properties        bound {@code sfdc.*} settings
     * @param restClientBuilder builder the token client is cloned from; it is not modified
     * @param requestFactory    factory that executes the token request
     * @throws NullPointerException when any argument is {@code null}
     */
    SalesforceSessionProvider(SalesforceProperties properties, RestClient.Builder restClientBuilder,
                              ClientHttpRequestFactory requestFactory) {
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        Objects.requireNonNull(requestFactory, "requestFactory");
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    boolean formPresent = converters.stream()
                            .anyMatch(converter -> converter instanceof FormHttpMessageConverter);
                    if (!formPresent) {
                        converters.add(new FormHttpMessageConverter());
                    }
                })
                .build();
    }

    /**
     * Returns the cached session, authenticating on the first call. A failed authentication throws its
     * exception unchanged and leaves the cache empty; the next call authenticates again.
     *
     * @return the cached {@link PartnerConnection}
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamAuthenticationException when the token endpoint answers another 4xx, returns no usable
     *                                         token, or the session cannot be created
     * @throws UpstreamUnavailableException    when the token endpoint answers 5xx, cannot be reached or
     *                                         times out
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            connection = authenticate();
        }
        return connection;
    }

    /**
     * Discards the cached session, requests a new token and caches and returns the new session. On failure
     * the cache stays empty and the exception propagates unchanged (D-020).
     *
     * @return the new {@link PartnerConnection}
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamAuthenticationException when the token endpoint answers another 4xx, returns no usable
     *                                         token, or the session cannot be created
     * @throws UpstreamUnavailableException    when the token endpoint answers 5xx, cannot be reached or
     *                                         times out
     */
    public synchronized PartnerConnection reauthenticate() {
        connection = null;
        connection = authenticate();
        return connection;
    }

    /**
     * Requests a token and builds a manual-login {@link PartnerConnection} from it (D-013). Sets no auth
     * endpoint and leaves the connect and read timeouts at the library defaults (D-488).
     */
    private PartnerConnection authenticate() {
        TokenResponse token = requestToken();
        String instanceUrl = withoutTrailingSlashes(token.instanceUrl());
        ConnectorConfig config = new ConnectorConfig();
        config.setManualLogin(true);
        config.setSessionId(token.accessToken());
        config.setServiceEndpoint(instanceUrl + SOAP_PATH);
        PartnerConnection created;
        try {
            created = new PartnerConnection(config);
        } catch (ConnectionException e) {
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM, "Salesforce session could not be created", e);
        }
        LOG.info("Salesforce session established for {}", instanceUrl);
        return created;
    }

    /**
     * Sends the username-password grant to {@code ${sfdc.login-url}/services/oauth2/token} and returns the
     * validated response (D-013). The form values are passed unencoded to {@link FormHttpMessageConverter},
     * which URL-encodes each one once with UTF-8. A {@code null} property value is sent as an empty value
     * (D-488).
     */
    private TokenResponse requestToken() {
        URI uri = tokenUri();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", nullToEmpty(properties.key()));
        form.add("client_secret", nullToEmpty(properties.secret()));
        form.add("username", nullToEmpty(properties.user()));
        form.add("password", nullToEmpty(properties.password()) + nullToEmpty(properties.securityToken()));

        LOG.debug("Requesting Salesforce access token from {}", uri);
        TokenResponse token;
        try {
            token = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (ResourceAccessException e) {
            throw unavailable(uri, e);
        } catch (RestClientException e) {
            // A RestClientException whose direct cause is an IOException carries a read timeout or a broken
            // connection raised while the status line or headers were read; it maps to the same
            // UpstreamUnavailableException as a ResourceAccessException. Every other RestClientException,
            // a malformed or non-JSON body included, maps to UpstreamAuthenticationException (D-488).
            if (e.getCause() instanceof IOException) {
                throw unavailable(uri, e);
            }
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                    "Salesforce token response could not be read", e);
        }
        if (token == null) {
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                    "Salesforce token response has no body", null);
        }
        if (isBlank(token.accessToken())) {
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                    "Salesforce token response has no access_token", null);
        }
        if (isBlank(token.instanceUrl())) {
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                    "Salesforce token response has no instance_url", null);
        }
        return token;
    }

    /**
     * Maps an error status of the token endpoint: 429 to {@link UpstreamRateLimitException} with the
     * {@code Retry-After} header value or {@code null}, any other 4xx to
     * {@link UpstreamAuthenticationException}, and every other status to {@link UpstreamUnavailableException}.
     */
    private static RuntimeException classify(RestClientResponseException e) {
        HttpStatusCode status = e.getStatusCode();
        String message = "Salesforce token request failed with status " + status.value();
        if (status.value() == 429) {
            HttpHeaders headers = e.getResponseHeaders();
            String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            return new UpstreamRateLimitException(UPSTREAM_SYSTEM, message, retryAfter, e);
        }
        if (status.is4xxClientError()) {
            return new UpstreamAuthenticationException(UPSTREAM_SYSTEM, message, e);
        }
        return new UpstreamUnavailableException(UPSTREAM_SYSTEM, message, e);
    }

    /**
     * Creates the {@link UpstreamUnavailableException} for an I/O failure of the token request: a refused
     * connection, an unknown host, a connect or read timeout, or a broken connection.
     */
    private static UpstreamUnavailableException unavailable(URI uri, RestClientException e) {
        return new UpstreamUnavailableException(UPSTREAM_SYSTEM,
                "Salesforce token request to " + uri + " failed with an I/O error", e);
    }

    /**
     * Builds {@code sfdc.login-url} plus {@link #TOKEN_PATH}; a trailing {@code /} in the login URL does not
     * double the slash.
     *
     * @throws IllegalStateException when {@code sfdc.login-url} is {@code null} or blank (D-488)
     */
    private URI tokenUri() {
        String loginUrl = properties.loginUrl();
        if (isBlank(loginUrl)) {
            throw new IllegalStateException("sfdc.login-url must be set");
        }
        return UriComponentsBuilder.fromUriString(loginUrl).path(TOKEN_PATH).build().toUri();
    }

    /** Returns {@code value} without any trailing {@code /} characters. */
    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /** Returns {@code value}, or the empty string when it is {@code null}. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Returns {@code true} when {@code value} is {@code null}, empty or whitespace only. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Fields read from the token endpoint's JSON response; every other field is ignored (D-013).
     *
     * @param accessToken {@code access_token}: the session id of the partner API session
     * @param instanceUrl {@code instance_url}: base URL of the org's instance
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("instance_url") String instanceUrl) {
    }
}
