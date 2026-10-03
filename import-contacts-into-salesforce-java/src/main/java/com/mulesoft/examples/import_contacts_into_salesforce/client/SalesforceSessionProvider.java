package com.mulesoft.examples.import_contacts_into_salesforce.client;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.import_contacts_into_salesforce.config.SalesforceProperties;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_contacts_into_salesforce.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Provides the Salesforce partner API session of the project, replacing the global element
 * {@code sfdc:config name="Salesforce"} [import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml:4-6].
 *
 * <p>The session comes from the OAuth2 username-password grant (D-013): one {@code POST} of
 * {@code <sfdc.login-url>/services/oauth2/token} carrying {@code grant_type=password}, {@code client_id}
 * ({@code sfdc.key}), {@code client_secret} ({@code sfdc.secret}), {@code username} ({@code sfdc.user}, D-015)
 * and {@code password} ({@code sfdc.password} immediately followed by {@code sfdc.securityToken}). The
 * response's {@code access_token} becomes the session id and its {@code instance_url} plus
 * {@code /services/Soap/u/65.0} the service endpoint of a manual-login {@link PartnerConnection}; SOAP
 * {@code login()} is never called.
 *
 * <p>The constructor sends no request. {@link #connection()} authenticates on its first call and caches the
 * session; {@link #reauthenticate()} replaces it and is called by {@code SalesforceClient.execute} after an
 * {@code INVALID_SESSION_ID} fault (D-020). Each authentication sends exactly one HTTP request, with no retry.
 *
 * <p>Token request outcomes, all with upstream system {@code Salesforce} and operation {@code token}
 * (D-020, D-570):
 * <ul>
 *   <li>2xx with a non-blank {@code access_token} and {@code instance_url}: the token;</li>
 *   <li>2xx without them, or with a body that is not a token JSON object:
 *       {@code IllegalStateException("Salesforce token failed: incomplete token response")};</li>
 *   <li>400 or 401: {@link UpstreamAuthenticationException} with message
 *       {@code Salesforce token failed: <error>: <error_description>}, or
 *       {@code Salesforce token failed: HTTP <status>} when the body is not JSON or carries no {@code error};</li>
 *   <li>429: {@link UpstreamRateLimitException} with message {@code Salesforce token failed: HTTP 429} and the
 *       response's {@code Retry-After} value, {@code null} without one;</li>
 *   <li>any other status: {@code IllegalStateException("Salesforce token failed: HTTP <status>")};</li>
 *   <li>a refused connection, a timeout or an I/O failure while the response is read:
 *       {@link UpstreamUnavailableException} with message {@code Salesforce token failed: <I/O error message>};</li>
 *   <li>a blank {@code sfdc.login-url}, before any request:
 *       {@code IllegalStateException("Salesforce token failed: sfdc.login-url is not set")}.</li>
 * </ul>
 * No log line carries a credential, the form body, the access token or the instance URL, and no exception
 * message carries a credential, the form body or the access token.
 *
 * <p>Example:
 * <pre>{@code
 * PartnerConnection connection = sessionProvider.connection();   // first call: one token request
 * SaveResult[] results = connection.create(contacts);
 * PartnerConnection renewed = sessionProvider.reauthenticate();  // one new token request
 * }</pre>
 *
 * <p>{@link #connection()} and {@link #reauthenticate()} are synchronized on this instance, which guards the
 * cached session.
 */
@Component
public class SalesforceSessionProvider {

    /** Logger of the token request and session lifecycle; writes no credential, token or instance URL. */
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Reads the JSON error body of a rejected token request. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Upstream system named by every exception of the token request. */
    private static final String UPSTREAM_SYSTEM = "Salesforce";

    /** Operation named by every exception of the token request. */
    private static final String TOKEN_OPERATION = "token";

    /** Path of the OAuth2 token endpoint below {@code sfdc.login-url} (D-013). */
    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** Path of the partner SOAP endpoint, API version 65.0, below the token's {@code instance_url} (D-013). */
    private static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** Prefix of every token request failure message. */
    private static final String TOKEN_FAILURE = "Salesforce token failed: ";

    /** Message of a 2xx token response without a usable token. */
    private static final String INCOMPLETE_TOKEN = TOKEN_FAILURE + "incomplete token response";

    /** Bound {@code sfdc.*} settings: credentials, connected-app key and secret, login URL. */
    private final SalesforceProperties properties;

    /** Token client on {@code sfdc.login-url} with a {@link JdkClientHttpRequestFactory}. */
    private final RestClient restClient;

    /** Cached session; {@code null} until the first successful authentication. Guarded by {@code this}. */
    private PartnerConnection connection;

    /**
     * Creates the provider. Sets {@code sfdc.login-url} as the base URL of {@code builder} and a
     * {@link JdkClientHttpRequestFactory} at its defaults, adds no interceptor, retry or status handler, and
     * sends no request.
     *
     * @param properties bound {@code sfdc.*} settings
     * @param builder    builder of the token client, Boot's prototype {@code RestClient.Builder} at runtime
     * @throws NullPointerException when an argument is {@code null}
     */
    public SalesforceSessionProvider(SalesforceProperties properties, RestClient.Builder builder) {
        this.properties = Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(builder, "builder");
        this.restClient = builder.baseUrl(properties.loginUrl())
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }

    /**
     * Returns the cached session, authenticating with the OAuth2 username-password grant on the first call
     * (D-013). A failed authentication propagates its exception unchanged and leaves the cache empty; the next
     * call requests a token again.
     *
     * @return the cached {@link PartnerConnection}
     * @throws UpstreamAuthenticationException when the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamUnavailableException    when the token endpoint cannot be reached, times out or the
     *                                         response cannot be read
     * @throws IllegalStateException           for any other token failure, or when the session cannot be built
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            connection = newConnection();
        }
        return connection;
    }

    /**
     * Discards the cached session, requests a new token, and caches and returns the new session (D-020). A
     * failed authentication propagates its exception unchanged and leaves the cache empty.
     *
     * @return the new {@link PartnerConnection}
     * @throws UpstreamAuthenticationException when the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamUnavailableException    when the token endpoint cannot be reached, times out or the
     *                                         response cannot be read
     * @throws IllegalStateException           for any other token failure, or when the session cannot be built
     */
    public synchronized PartnerConnection reauthenticate() {
        connection = null;
        connection = newConnection();
        return connection;
    }

    /**
     * Requests a token and builds a manual-login {@link PartnerConnection} from it (D-013).
     *
     * @return the new session
     * @throws IllegalStateException wrapping the {@link ConnectionException} of the {@link PartnerConnection}
     *                               constructor
     */
    private PartnerConnection newConnection() {
        TokenResponse token = requestToken();
        ConnectorConfig config = connectorConfig(token);
        PartnerConnection created;
        try {
            created = new PartnerConnection(config);
        } catch (ConnectionException e) {
            throw new IllegalStateException("Salesforce session failed: " + e.getMessage(), e);
        }
        LOG.debug("Salesforce session established");
        return created;
    }

    /**
     * Requests an access token with the OAuth2 username-password grant (D-013): one {@code POST} of
     * {@code <sfdc.login-url>/services/oauth2/token} with the form fields {@code grant_type}, {@code client_id},
     * {@code client_secret}, {@code username} and {@code password}, in that order. The values are passed
     * unencoded to Spring's {@code FormHttpMessageConverter}, which URL-encodes each one once with UTF-8: password
     * {@code p+w&1} with token {@code T0k} is sent as {@code password=p%2Bw%261T0k}. The response is classified
     * by its status code as the class description lists; nothing is retried.
     *
     * @return the token response, with a non-blank access token and instance URL
     * @throws UpstreamAuthenticationException when the token endpoint answers 400 or 401
     * @throws UpstreamRateLimitException      when the token endpoint answers 429
     * @throws UpstreamUnavailableException    when the token endpoint cannot be reached, times out or the
     *                                         response cannot be read
     * @throws IllegalStateException           when {@code sfdc.login-url} is blank, the 2xx response holds no
     *                                         usable token, or the endpoint answers any other status
     */
    TokenResponse requestToken() {
        if (isBlank(properties.loginUrl())) {
            throw new IllegalStateException(TOKEN_FAILURE + "sfdc.login-url is not set");
        }
        // A null sfdc.* value is sent as an empty form value, never as the text "null" (D-570).
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", valueOrEmpty(properties.key()));
        form.add("client_secret", valueOrEmpty(properties.secret()));
        form.add("username", valueOrEmpty(properties.user()));
        form.add("password", valueOrEmpty(properties.password()) + valueOrEmpty(properties.securityToken()));

        LOG.debug("Requesting Salesforce access token");
        try {
            return restClient.post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, response) -> classify(response));
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException(UPSTREAM_SYSTEM, TOKEN_OPERATION, TOKEN_FAILURE + e.getMessage(), e);
        }
    }

    /**
     * Builds the force-wsc configuration of a manual-login session from a token (D-013): session id
     * {@code access_token}, service endpoint {@code instance_url + "/services/Soap/u/65.0"}, manual login
     * {@code true}. Sets no auth endpoint and keeps the force-wsc default connection and read timeouts.
     *
     * <p>Example: {@code connectorConfig(new TokenResponse("tok", "https://na1.my.salesforce.com"))} has service
     * endpoint {@code https://na1.my.salesforce.com/services/Soap/u/65.0} and session id {@code tok}.
     *
     * @param token token response with a non-blank access token and instance URL
     * @return a new configuration for {@link PartnerConnection#PartnerConnection(ConnectorConfig)}
     * @throws NullPointerException when {@code token} is {@code null}
     */
    ConnectorConfig connectorConfig(TokenResponse token) {
        Objects.requireNonNull(token, "token");
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(token.accessToken());
        config.setServiceEndpoint(token.instanceUrl() + SOAP_PATH);
        config.setManualLogin(true);
        return config;
    }

    /**
     * Classifies the token endpoint's response by its status code: 2xx returns the token, 400 and 401 throw
     * {@link UpstreamAuthenticationException}, 429 throws {@link UpstreamRateLimitException}, every other status
     * throws {@link IllegalStateException} (D-020).
     *
     * @param response the token endpoint's response
     * @return the token of a 2xx response
     * @throws IOException when the status, headers or body cannot be read; {@code RestClient} reports it as a
     *                     {@link ResourceAccessException}
     */
    private static TokenResponse classify(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            return tokenFrom(response);
        }
        int code = status.value();
        if (code == 400 || code == 401) {
            String detail = authenticationDetail(response.getBody().readAllBytes(), code);
            throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM, TOKEN_OPERATION, TOKEN_FAILURE + detail, null);
        }
        if (code == 429) {
            String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            throw new UpstreamRateLimitException(UPSTREAM_SYSTEM, TOKEN_OPERATION, TOKEN_FAILURE + "HTTP 429",
                    retryAfter, null);
        }
        throw new IllegalStateException(TOKEN_FAILURE + "HTTP " + code);
    }

    /**
     * Reads the token of a 2xx response. A missing body, a body that does not bind to {@link TokenResponse}, and
     * a blank {@code access_token} or {@code instance_url} throw
     * {@code IllegalStateException("Salesforce token failed: incomplete token response")}.
     *
     * @param response the 2xx response
     * @return the token, with a non-blank access token and instance URL
     * @throws IOException when the body cannot be read
     */
    private static TokenResponse tokenFrom(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        TokenResponse token;
        try {
            token = response.bodyTo(TokenResponse.class);
        } catch (RestClientException e) {
            // An I/O failure while the body is read is rethrown as the IOException, which RestClient reports as a
            // ResourceAccessException; any other failure to bind the body is an incomplete token response (D-570).
            if (e.getCause() instanceof IOException ioException && !(ioException instanceof JsonProcessingException)) {
                throw ioException;
            }
            throw new IllegalStateException(INCOMPLETE_TOKEN, e);
        }
        if (token == null || isBlank(token.accessToken()) || isBlank(token.instanceUrl())) {
            throw new IllegalStateException(INCOMPLETE_TOKEN);
        }
        return token;
    }

    /**
     * Returns the failure detail of a 400 or 401 body {@code {"error": …, "error_description": …}}:
     * {@code <error>: <error_description>}, {@code <error>} alone when {@code error_description} is absent or
     * {@code null}, and {@code HTTP <code>} when the body is not JSON or {@code error} is absent, {@code null} or
     * blank (D-570).
     *
     * @param body the response body bytes
     * @param code the response status code
     * @return the failure detail
     */
    private static String authenticationDetail(byte[] body, int code) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (IOException e) {
            return "HTTP " + code;
        }
        if (root == null || !root.hasNonNull("error") || root.get("error").asText().isBlank()) {
            return "HTTP " + code;
        }
        String error = root.get("error").asText();
        JsonNode description = root.get("error_description");
        if (description == null || description.isNull()) {
            return error;
        }
        return error + ": " + description.asText();
    }

    /**
     * Returns {@code value}, or the empty string when it is {@code null}.
     *
     * @param value a bound {@code sfdc.*} value
     * @return {@code value}, or {@code ""} for {@code null}
     */
    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns {@code true} when {@code value} is {@code null}, empty or whitespace only.
     *
     * @param value the text to test
     * @return whether {@code value} is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Members of the token endpoint's JSON response that the session uses; {@code id}, {@code token_type},
     * {@code issued_at}, {@code signature} and every other member are ignored (D-013).
     *
     * @param accessToken {@code access_token}: the session id of the partner API session
     * @param instanceUrl {@code instance_url}: base URL of the org's instance
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("instance_url") String instanceUrl) {
    }
}
