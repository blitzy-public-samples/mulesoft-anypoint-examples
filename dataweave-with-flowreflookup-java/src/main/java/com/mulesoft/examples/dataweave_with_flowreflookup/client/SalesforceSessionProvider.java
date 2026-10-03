package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.dataweave_with_flowreflookup.config.SalesforceProperties;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamRateLimitException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Provides the Salesforce partner API session that {@code SalesforceClient} uses, replacing the
 * global element {@code sfdc:config name="Salesforce__Basic_authentication"}
 * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:3].
 *
 * <p>Authentication is the OAuth2 username-password grant (D-013). One {@code POST} of
 * {@code application/x-www-form-urlencoded} to {@code <sfdc.login-url>/services/oauth2/token}
 * carries, in this order:
 * <ol>
 *   <li>{@code grant_type} = {@code password};</li>
 *   <li>{@code client_id} = {@code sfdc.key};</li>
 *   <li>{@code client_secret} = {@code sfdc.secret};</li>
 *   <li>{@code username} = {@code sfdc.user} (key name D-015);</li>
 *   <li>{@code password} = {@code sfdc.password} immediately followed by
 *       {@code sfdc.securityToken}, with no delimiter.</li>
 * </ol>
 * Each value is URL-encoded once by Spring's {@code FormHttpMessageConverter}: password
 * {@code p+w&1} with token {@code T0k} is sent as {@code password=p%2Bw%261T0k}. The
 * {@code access_token} of the JSON response becomes the session id and its {@code instance_url}
 * followed by {@code /services/Soap/u/65.0} becomes the service endpoint of a manual-login
 * {@link PartnerConnection} opened by {@link Connector#newConnection(ConnectorConfig)}. The SOAP
 * {@code login()} call is never made, and the auth endpoint that {@code newConnection} fills in
 * when none is set is never contacted.
 *
 * <p>Caching: {@link #connection()} authenticates on its first call and returns the cached
 * connection on every later call; {@link #reauthenticate()} discards it and authenticates again.
 * Each authentication sends exactly one token request and is never retried here; the single
 * re-issue after re-authentication belongs to {@code SalesforceClient} (D-020). Construction and
 * application startup send no request.
 *
 * <p>Token request failures (D-020), each with upstream system {@code Salesforce}:
 * <ul>
 *   <li>HTTP 400 or 401: {@link UpstreamAuthenticationException};</li>
 *   <li>HTTP 429: {@link UpstreamRateLimitException} carrying the {@code Retry-After} header value,
 *       or none when the response has no such header;</li>
 *   <li>an I/O failure, a refused connection or a timeout: {@link UpstreamUnavailableException};</li>
 *   <li>any other client or server error status, and any other failure: the Spring exception,
 *       unchanged.</li>
 * </ul>
 * Log lines and exception messages written here never contain the password, the security token,
 * the consumer key or secret, the access token, the form body or the token response body.
 *
 * <pre>{@code
 * PartnerConnection c = sessionProvider.connection();        // first call: one token request
 * PartnerConnection same = sessionProvider.connection();     // cached: no request
 * PartnerConnection fresh = sessionProvider.reauthenticate(); // one new token request
 * }</pre>
 *
 * <p>Thread safety: {@link #connection()} and {@link #reauthenticate()} are {@code synchronized}
 * on this instance, which guards the cached connection; at most one authentication runs at a time.
 */
@Component
public class SalesforceSessionProvider {

    /** Upstream system name carried by every upstream exception this class throws. */
    private static final String UPSTREAM = "Salesforce";

    /** Partner SOAP path for API version 65.0, appended to the token's {@code instance_url}. */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** OAuth2 token endpoint path, appended to {@code sfdc.login-url} (D-013). */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Category of the DEBUG token-request line and the INFO session-opened line. */
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Bound {@code sfdc.*} settings: credentials, connected-app key and secret, login URL. */
    private final SalesforceProperties properties;

    /** Applied to every {@link ConnectorConfig} before its connection is opened. */
    private final Consumer<ConnectorConfig> customizer;

    /** Client of the token request, with the builder's default form and JSON converters. */
    private final RestClient restClient;

    /** Cached session; {@code null} until an authentication succeeds. Guarded by {@code this}. */
    private PartnerConnection connection;

    /**
     * Creates the provider with a customizer that leaves every {@link ConnectorConfig} unchanged.
     * Sends no request.
     *
     * @param properties bound {@code sfdc.*} settings
     * @throws NullPointerException when {@code properties} is {@code null}
     */
    @Autowired
    public SalesforceSessionProvider(SalesforceProperties properties) {
        this(properties, config -> { });
    }

    /**
     * Creates the provider and its token client. Sends no request.
     *
     * @param properties bound {@code sfdc.*} settings
     * @param customizer applied to each {@link ConnectorConfig} after the session id and service
     *                   endpoint are set and before the connection is opened, for example to set
     *                   timeouts or a transport
     * @throws NullPointerException when an argument is {@code null}
     */
    SalesforceSessionProvider(SalesforceProperties properties, Consumer<ConnectorConfig> customizer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.customizer = Objects.requireNonNull(customizer, "customizer");
        // The token request always runs on the JDK HttpClient; no factory is auto-detected (D-538).
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    /**
     * Returns the cached session, authenticating first when none is cached. A failed
     * authentication caches nothing and propagates its exception; the next call authenticates
     * again.
     *
     * @return the cached {@link PartnerConnection}
     * @throws UpstreamAuthenticationException when the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamUnavailableException    when the token endpoint cannot be reached, the
     *                                         request fails with an I/O error or times out, or
     *                                         opening the connection fails with an I/O cause
     * @throws IllegalStateException           when {@code sfdc.login-url} is unset or not an
     *                                         absolute URI, the token response lacks
     *                                         {@code access_token} or {@code instance_url}, or the
     *                                         connection cannot be opened for another reason
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            connection = authenticate();
        }
        return connection;
    }

    /**
     * Discards the cached session, authenticates again with one token request, and caches and
     * returns the new session. A failed authentication leaves no session cached and propagates
     * its exception (D-020).
     *
     * @return the new {@link PartnerConnection}
     * @throws UpstreamAuthenticationException when the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamUnavailableException    when the token endpoint cannot be reached, the
     *                                         request fails with an I/O error or times out, or
     *                                         opening the connection fails with an I/O cause
     * @throws IllegalStateException           in the cases {@link #connection()} lists
     */
    public synchronized PartnerConnection reauthenticate() {
        connection = null;
        connection = authenticate();
        return connection;
    }

    /**
     * Sends one password-grant token request and opens a manual-login {@link PartnerConnection}
     * on the returned session (D-013). Sends no request when {@code sfdc.login-url} is unusable.
     */
    private PartnerConnection authenticate() {
        URI tokenUri = tokenUri();
        MultiValueMap<String, String> form = passwordGrantForm();
        LOG.debug("Requesting Salesforce access token from {}", tokenUri);
        JsonNode token = requestToken(tokenUri, form);
        String accessToken = text(token, "access_token");
        String instanceUrl = text(token, "instance_url");
        if (accessToken == null || instanceUrl == null) {
            // An empty body, a missing field and a blank field are all reported this way (D-538).
            throw new IllegalStateException("Salesforce token response lacks access_token or instance_url");
        }
        return openConnection(accessToken, instanceUrl);
    }

    /**
     * Returns {@code sfdc.login-url}, with one trailing {@code /} removed when present, followed by
     * {@link #TOKEN_PATH}.
     *
     * @throws IllegalStateException when {@code sfdc.login-url} is {@code null}, blank, not a valid
     *                               URI or not absolute (D-538)
     */
    private URI tokenUri() {
        String loginUrl = properties.loginUrl();
        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException("Salesforce login URL sfdc.login-url is not set");
        }
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(loginUrl) + TOKEN_PATH);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Salesforce login URL sfdc.login-url is not a valid URI", e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalStateException("Salesforce login URL sfdc.login-url is not an absolute URI");
        }
        return uri;
    }

    /**
     * Builds the five form fields of the password grant in their fixed order. Values are added
     * unencoded; the form converter encodes each once.
     */
    private MultiValueMap<String, String> passwordGrantForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        // An absent sfdc.* value is sent as an empty form value (D-538).
        form.add("client_id", nullToEmpty(properties.key()));
        form.add("client_secret", nullToEmpty(properties.secret()));
        form.add("username", nullToEmpty(properties.user()));
        form.add("password", nullToEmpty(properties.password()) + nullToEmpty(properties.securityToken()));
        return form;
    }

    /**
     * POSTs the form to the token endpoint once and returns the parsed JSON body, or {@code null}
     * for an empty body. Classifies 400, 401, 429 and I/O failures (D-020); every other exception
     * propagates unchanged.
     */
    private JsonNode requestToken(URI tokenUri, MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401) {
                throw new UpstreamAuthenticationException("Salesforce authentication failed", UPSTREAM, e);
            }
            if (status == 429) {
                HttpHeaders headers = e.getResponseHeaders();
                String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                throw new UpstreamRateLimitException("Salesforce rate limit reached", UPSTREAM, retryAfter, e);
            }
            throw e;
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException("Salesforce token endpoint unreachable", UPSTREAM, e);
        }
    }

    /**
     * Opens a manual-login {@link PartnerConnection} with the given session id and the service
     * endpoint {@code instanceUrl} (one trailing {@code /} removed) plus {@link #SOAP_PATH}, after
     * applying {@link #customizer}. Sends no request.
     *
     * @throws UpstreamUnavailableException when the {@link ConnectionException} has an
     *                                      {@link IOException} in its cause chain (D-538)
     * @throws IllegalStateException        for any other {@link ConnectionException}
     */
    private PartnerConnection openConnection(String accessToken, String instanceUrl) {
        ConnectorConfig config = new ConnectorConfig();
        config.setManualLogin(true);
        config.setSessionId(accessToken);
        config.setServiceEndpoint(stripTrailingSlash(instanceUrl) + SOAP_PATH);
        customizer.accept(config);
        PartnerConnection opened;
        try {
            opened = Connector.newConnection(config);
        } catch (ConnectionException e) {
            if (hasIoCause(e)) {
                throw new UpstreamUnavailableException("Salesforce connection could not be opened", UPSTREAM, e);
            }
            throw new IllegalStateException("Salesforce connection could not be opened: " + e.getMessage(), e);
        }
        LOG.info("Salesforce session opened for {}", hostOf(config.getServiceEndpoint()));
        return opened;
    }

    /**
     * Returns the non-blank string value of {@code field} in {@code node}, or {@code null} when
     * {@code node} is {@code null}, the field is absent, not a JSON string, or blank.
     */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.textValue();
        return text.isBlank() ? null : text;
    }

    /**
     * Returns {@code true} when {@code failure} or a throwable in its {@link Throwable#getCause()}
     * chain is an {@link IOException}. Each throwable is visited at most once, which ends the walk
     * on a cyclic chain.
     */
    private static boolean hasIoCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /** Returns the host of {@code endpoint}, or {@code unknown host} when it has none or cannot be parsed. */
    private static String hostOf(String endpoint) {
        if (endpoint == null) {
            return "unknown host";
        }
        try {
            String host = new URI(endpoint).getHost();
            return host == null ? "unknown host" : host;
        } catch (URISyntaxException e) {
            return "unknown host";
        }
    }

    /** Returns {@code url} without its last character when that character is {@code /}. */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Returns {@code value}, or the empty string when it is {@code null}. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
