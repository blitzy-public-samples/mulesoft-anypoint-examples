package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Salesforce OAuth2 web-server (authorization-code) flow client of the {@code Salesforce__OAuth_}
 * connector configuration and {@code sfdc:authorize}
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:3-5,12] (D-014).
 *
 * <p>The client builds the Salesforce authorize URL, exchanges the authorization code delivered to the
 * callback listener for a token, refreshes that token, and holds the current {@link Token} in memory
 * only. The redirect URI is {@code http://<callback-domain>:<callback-port>/<callback-path>} from
 * {@code sfdc.oauth.*}, {@code http://localhost:8081/oauth2callback} with the committed
 * {@code application.yml}; the authorize URL and the code exchange carry the same value.
 *
 * <p>Each token-endpoint call is exactly one {@code application/x-www-form-urlencoded} POST to
 * {@code sfdc.oauth.access-token-url}, never re-sent, and its failures are classified as follows
 * (D-020):
 *
 * <ul>
 *   <li>HTTP 429: {@link UpstreamRateLimitException} carrying the {@code Retry-After} header value, or
 *       {@code null} when the answer has none. The stored token is kept.</li>
 *   <li>A read timeout, connect timeout or refused connection: {@link UpstreamUnavailableException}. The
 *       stored token is kept.</li>
 *   <li>Any other non-2xx answer: {@link #exchange(String)} throws {@link UpstreamAuthenticationException}
 *       for HTTP 401 and {@link IllegalStateException} for every other status, with the stored token
 *       unchanged; {@link #refresh()} clears the stored token and throws
 *       {@link UpstreamAuthenticationException}.</li>
 *   <li>Any other transport failure is rethrown unchanged as the {@link ResourceAccessException} raised
 *       by {@link RestClient}.</li>
 * </ul>
 *
 * <p>Every upstream exception carries {@link #UPSTREAM_SYSTEM} as its upstream system. The client
 * writes no log and persists nothing: no token, authorization code, client secret or form body leaves
 * it other than in the token-endpoint request.
 *
 * <pre>{@code
 * String location = client.authorizationUrl(state);   // GET / redirects the browser here
 * client.exchange(code);                               // GET /oauth2callback?code=...
 * SalesforceOAuthClient.Token token = client.currentToken();
 * }</pre>
 */
@Component
public class SalesforceOAuthClient {

    /** Upstream system name carried by every upstream exception this client raises. */
    public static final String UPSTREAM_SYSTEM = "Salesforce";

    /** Operation name of the authorization-code exchange, as passed to {@link #execute(String, Supplier)}. */
    private static final String EXCHANGE = "authorization code exchange";

    /** Operation name of the token refresh, as passed to {@link #execute(String, Supplier)}. */
    private static final String REFRESH = "token refresh";

    /** HTTP status of a rejected authorization-code exchange. */
    private static final int UNAUTHORIZED = 401;

    /** HTTP status of a rate-limited token-endpoint call. */
    private static final int TOO_MANY_REQUESTS = 429;

    /** Client of the Salesforce token endpoint, backed by a JDK {@link HttpClient} pinned to HTTP/1.1. */
    private final RestClient restClient;

    /** The {@code sfdc.*} credentials and OAuth endpoints. */
    private final SalesforceOAuthProperties properties;

    /**
     * {@code http://<callback-domain>:<callback-port>/<callback-path>}, carried by the authorize URL and the
     * code exchange.
     */
    private final String redirectUri;

    /** The token obtained by the last successful exchange or refresh, or {@code null} when none is held. */
    private final AtomicReference<Token> token = new AtomicReference<>();

    /**
     * Creates the client with no read timeout on the token endpoint.
     *
     * @param builder    the {@link RestClient} builder; its request factory is replaced
     * @param properties the {@code sfdc.*} credentials and OAuth endpoints; {@code sfdc.oauth} must be set
     * @throws NullPointerException if an argument or {@code properties.oauth()} is {@code null}
     */
    @Autowired
    public SalesforceOAuthClient(RestClient.Builder builder, SalesforceOAuthProperties properties) {
        this(builder, properties, null);
    }

    /**
     * Creates the client with the given read timeout on the token endpoint.
     *
     * <p>The {@link RestClient} sends through a {@link JdkClientHttpRequestFactory} over a JDK
     * {@link HttpClient} with protocol version HTTP/1.1 and no connect timeout. A non-null
     * {@code readTimeout} bounds the wait for each response; {@code null} leaves it unbounded. No retry
     * or interceptor is configured.
     *
     * @param builder     the {@link RestClient} builder; its request factory is replaced
     * @param properties  the {@code sfdc.*} credentials and OAuth endpoints; {@code sfdc.oauth} must be set
     * @param readTimeout the response timeout, or {@code null} for none
     * @throws NullPointerException if {@code builder}, {@code properties} or {@code properties.oauth()} is
     *                              {@code null}
     */
    SalesforceOAuthClient(RestClient.Builder builder, SalesforceOAuthProperties properties, Duration readTimeout) {
        Objects.requireNonNull(builder, "builder");
        this.properties = Objects.requireNonNull(properties, "properties");
        SalesforceOAuthProperties.OAuth oauth =
                Objects.requireNonNull(properties.oauth(), "sfdc.oauth is not configured");
        HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        if (readTimeout != null) {
            factory.setReadTimeout(readTimeout);
        }
        this.restClient = builder.requestFactory(factory).build();
        this.redirectUri = "http://" + oauth.callbackDomain() + ":" + oauth.callbackPort() + "/" + oauth.callbackPath();
    }

    /**
     * Returns the Salesforce authorize URL that starts the web-server flow
     * [salesforce-oauth.xml:12 {@code sfdc:authorize display="PAGE"}] (D-014).
     *
     * <p>The URL is {@code sfdc.oauth.authorization-url} followed by the query parameters
     * {@code response_type=code}, {@code client_id} ({@code sfdc.key}), {@code redirect_uri},
     * {@code display=page} and {@code state}, in that order. Each value is percent-encoded once, with
     * every reserved character encoded. No request is sent. With the committed endpoints, key
     * {@code abc} and state {@code s1} the result is:
     *
     * <pre>{@code
     * https://login.salesforce.com/services/oauth2/authorize?response_type=code&client_id=abc
     *     &redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth2callback&display=page&state=s1
     * }</pre>
     *
     * @param state the opaque value Salesforce returns to the callback unchanged; it is not validated,
     *              and {@code null} sends an empty value
     * @return the absolute authorize URL
     */
    public String authorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString(properties.oauth().authorizationUrl())
                .queryParam("response_type", "{responseType}")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("display", "{display}")
                .queryParam("state", "{state}")
                .encode()
                .buildAndExpand("code", properties.key(), redirectUri, "page", state)
                .toUriString();
    }

    /**
     * Exchanges an authorization code for a token and stores it, replacing any token held
     * [salesforce-oauth.xml:4 {@code sfdc:oauth-callback-config}, :12 {@code sfdc:authorize}] (D-014).
     *
     * <p>Sends one POST to {@code sfdc.oauth.access-token-url} with the form fields
     * {@code grant_type=authorization_code}, {@code code}, {@code client_id} ({@code sfdc.key}),
     * {@code client_secret} ({@code sfdc.secret}) and {@code redirect_uri}, in that order. From a 2xx JSON
     * answer it stores {@code access_token}, {@code refresh_token} ({@code null} when absent) and
     * {@code instance_url}; other members are ignored. On every failure the stored token is unchanged
     * (D-020).
     *
     * @param code the authorization code Salesforce passed to the callback
     * @throws UpstreamRateLimitException     if the token endpoint answers HTTP 429
     * @throws UpstreamAuthenticationException if the token endpoint answers HTTP 401
     * @throws UpstreamUnavailableException    if the token endpoint times out or cannot be reached
     * @throws IllegalStateException           if the token endpoint answers any other non-2xx status, or
     *                                         a 2xx answer lacks a non-blank {@code access_token} or
     *                                         {@code instance_url}
     * @throws ResourceAccessException         for any other transport failure, unchanged
     */
    public void exchange(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", properties.key());
        form.add("client_secret", properties.secret());
        form.add("redirect_uri", redirectUri);
        JsonNode body = execute(EXCHANGE, () -> postForm(form));
        String accessToken = text(body, "access_token");
        String instanceUrl = text(body, "instance_url");
        if (isBlank(accessToken) || isBlank(instanceUrl)) {
            throw new IllegalStateException("Salesforce token response lacks access_token or instance_url");
        }
        token.set(new Token(accessToken, text(body, "refresh_token"), instanceUrl));
    }

    /**
     * Refreshes the stored token with its refresh token (D-014, D-020).
     *
     * <p>Without a stored token, or with a {@code null} or blank refresh token, no request is sent: the
     * stored token is cleared and {@link UpstreamAuthenticationException} is thrown. Otherwise sends one
     * POST to {@code sfdc.oauth.access-token-url} with the form fields {@code grant_type=refresh_token},
     * {@code refresh_token}, {@code client_id} ({@code sfdc.key}) and {@code client_secret}
     * ({@code sfdc.secret}), in that order. From a 2xx JSON answer it stores the new {@code access_token}
     * and {@code instance_url}, with the answer's {@code refresh_token} when present and non-blank and the
     * previous refresh token otherwise.
     *
     * @throws UpstreamRateLimitException      if the token endpoint answers HTTP 429; the stored token is
     *                                         kept
     * @throws UpstreamUnavailableException    if the token endpoint times out or cannot be reached; the
     *                                         stored token is kept
     * @throws UpstreamAuthenticationException if no refresh token is held, the token endpoint answers any
     *                                         other non-2xx status, or a 2xx answer lacks a non-blank
     *                                         {@code access_token} or {@code instance_url}; the stored
     *                                         token is cleared
     * @throws ResourceAccessException         for any other transport failure, unchanged, with the stored
     *                                         token kept
     */
    public void refresh() {
        Token current = token.get();
        if (current == null || isBlank(current.refreshToken())) {
            clearToken();
            throw new UpstreamAuthenticationException("No Salesforce refresh token is held", UPSTREAM_SYSTEM);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", current.refreshToken());
        form.add("client_id", properties.key());
        form.add("client_secret", properties.secret());
        JsonNode body = execute(REFRESH, () -> postForm(form));
        String accessToken = text(body, "access_token");
        String instanceUrl = text(body, "instance_url");
        if (isBlank(accessToken) || isBlank(instanceUrl)) {
            clearToken();
            throw new UpstreamAuthenticationException(
                    "Salesforce token refresh response lacks access_token or instance_url", UPSTREAM_SYSTEM);
        }
        String refreshToken = text(body, "refresh_token");
        String keptRefreshToken = isBlank(refreshToken) ? current.refreshToken() : refreshToken;
        token.set(new Token(accessToken, keptRefreshToken, instanceUrl));
    }

    /**
     * Returns whether a token is held.
     *
     * @return {@code true} after a successful {@link #exchange(String)} or {@link #refresh()} and until the
     *         token is cleared
     */
    public boolean hasToken() {
        return token.get() != null;
    }

    /**
     * Returns the token held.
     *
     * @return the token stored by the last successful {@link #exchange(String)} or {@link #refresh()}, or
     *         {@code null} when none is held
     */
    public Token currentToken() {
        return token.get();
    }

    /** Discards the token held; {@link #hasToken()} then returns {@code false}. */
    public void clearToken() {
        token.set(null);
    }

    /**
     * Runs one token-endpoint call and classifies its failure by the rules of D-020.
     *
     * <ul>
     *   <li>{@link RestClientResponseException}: the exception
     *       {@link #statusFailure(String, RestClientResponseException)} returns for its status.</li>
     *   <li>{@link ResourceAccessException} whose cause chain holds a {@link SocketTimeoutException},
     *       {@link ConnectException}, {@link HttpTimeoutException} or {@link TimeoutException}:
     *       {@link UpstreamUnavailableException}.</li>
     *   <li>Any other failure: rethrown unchanged.</li>
     * </ul>
     *
     * <p>The call runs exactly once.
     *
     * @param operation {@link #EXCHANGE} or {@link #REFRESH}
     * @param call      the token-endpoint call
     * @param <T>       the result type of the call
     * @return the result of the call
     */
    private <T> T execute(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException ex) {
            throw statusFailure(operation, ex);
        } catch (ResourceAccessException ex) {
            if (isTimeoutOrConnectFailure(ex)) {
                throw new UpstreamUnavailableException(
                        "Salesforce token endpoint timed out or could not be reached", UPSTREAM_SYSTEM, ex);
            }
            throw ex;
        }
    }

    /**
     * Returns the exception for a non-2xx token-endpoint answer (D-020).
     *
     * <p>HTTP 429 gives {@link UpstreamRateLimitException} with the answer's {@code Retry-After} value or
     * {@code null}. For {@link #REFRESH} every other status clears the stored token and gives
     * {@link UpstreamAuthenticationException}. For {@link #EXCHANGE} HTTP 401 gives
     * {@link UpstreamAuthenticationException} and every other status gives {@link IllegalStateException}.
     *
     * @param operation {@link #EXCHANGE} or {@link #REFRESH}
     * @param ex        the error answer
     * @return the exception to throw, with {@code ex} as its cause
     */
    private RuntimeException statusFailure(String operation, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == TOO_MANY_REQUESTS) {
            HttpHeaders headers = ex.getResponseHeaders();
            String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
            return new UpstreamRateLimitException(
                    "Salesforce token endpoint rate limit exceeded", UPSTREAM_SYSTEM, retryAfter, ex);
        }
        if (REFRESH.equals(operation)) {
            clearToken();
            return new UpstreamAuthenticationException(
                    "Salesforce rejected the token refresh (HTTP " + status + ")", UPSTREAM_SYSTEM, ex);
        }
        if (status == UNAUTHORIZED) {
            return new UpstreamAuthenticationException(
                    "Salesforce rejected the authorization code exchange (HTTP 401)", UPSTREAM_SYSTEM, ex);
        }
        return new IllegalStateException("Salesforce token endpoint returned HTTP " + status, ex);
    }

    /**
     * Sends one form POST to {@code sfdc.oauth.access-token-url} and returns its parsed JSON answer.
     *
     * <p>The form is serialised once by the {@link RestClient}'s form message converter as
     * {@code application/x-www-form-urlencoded}.
     *
     * @param form the form fields, in sending order
     * @return the parsed 2xx answer, or {@code null} when the answer has no body
     * @throws RestClientResponseException for every non-2xx answer, 3xx included
     * @throws ResourceAccessException     for a transport failure
     */
    private JsonNode postForm(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(URI.create(properties.oauth().accessTokenUrl()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                // Every non-2xx answer, 3xx included, raises a RestClientResponseException.
                .onStatus(status -> !status.is2xxSuccessful(),
                        (request, response) -> new DefaultResponseErrorHandler().handleError(response))
                .body(JsonNode.class);
    }

    /**
     * Returns the text of a scalar member of a JSON object.
     *
     * @param body  the JSON answer, or {@code null}
     * @param field the member name
     * @return the member's text, or {@code null} when {@code body} is {@code null} or the member is
     *         absent, JSON {@code null}, an object or an array
     */
    private static String text(JsonNode body, String field) {
        JsonNode value = body == null ? null : body.get(field);
        return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    /**
     * Returns whether a value is {@code null}, empty or whitespace only.
     *
     * @param value the value
     * @return {@code true} when {@code value} is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Returns whether a failure's cause chain, the failure included, holds a timeout or connect failure.
     *
     * <p>The chain is walked once per distinct throwable and ends at the first repeated one. A read
     * timeout surfaces as {@link HttpTimeoutException} or, from the bounded response wait of
     * {@link JdkClientHttpRequestFactory}, as {@link TimeoutException}; a refused connection as
     * {@link ConnectException} (D-020).
     *
     * @param failure the transport failure
     * @return {@code true} when the chain holds a {@link SocketTimeoutException}, {@link ConnectException},
     *         {@link HttpTimeoutException} (connect timeouts included) or {@link TimeoutException}
     */
    private static boolean isTimeoutOrConnectFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Salesforce OAuth2 token held in memory by {@link SalesforceOAuthClient} (D-014).
     *
     * <p>The generated {@code toString()} renders all three values.
     *
     * @param accessToken  the {@code access_token} of the token response
     * @param refreshToken the {@code refresh_token} of the token response, or {@code null} when none was
     *                     issued
     * @param instanceUrl  the {@code instance_url} of the token response, the base URL of the org's API
     */
    public record Token(String accessToken, String refreshToken, String instanceUrl) {
    }
}

