package com.mulesoft.examples.get_customer_list_from_netsuite.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamRateLimitException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException;
import com.mulesoft.examples.get_customer_list_from_netsuite.mapper.SuiteQlQueryBuilder;

import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;

/**
 * Sends NetSuite SuiteQL queries through the OAuth 2.0 M2M {@code WebClient} and translates failures
 * into the upstream exceptions (D-016, D-017, D-020). Replaces {@code netsuite:config} and
 * {@code netsuite:query-records} of {@code get-customer-list-from-netsuiteFlow}
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4,10].
 *
 * <p>This is the only class of the project that calls NetSuite. Every call goes through
 * {@link #execute(String, Supplier)}, which applies the failure modes of D-020: one re-authenticated
 * retry after HTTP 401, no retry after HTTP 429, a token endpoint failure or a connectivity failure.
 * The {@code WebClient} carries no timeout, no retry operator and no connector customisation of its
 * own; the reactor-netty defaults apply (D-020).
 *
 * <pre>{@code
 * SuiteQlQueryBuilder.SuiteQlRequest request = new SuiteQlQueryBuilder().customersByLastName("a");
 * List<Map<String, Object>> rows = netsuiteRestClient.query(request);
 * // rows.get(0) is {"links": [...], "email": "...", "firstname": "...", "lastname": "..."}
 * }</pre>
 */
@Component
public class NetsuiteRestClient {

    /** Upstream system name carried by every upstream exception and every {@code ErrorResponse}. */
    static final String UPSTREAM_SYSTEM = "NetSuite";

    /** Operation name of the SuiteQL query, used in log entries. */
    static final String SUITEQL_OPERATION = "suiteql";

    /** Message of {@link UpstreamAuthenticationException}. */
    static final String AUTH_MESSAGE = "NetSuite authentication failed";

    /** Message of {@link UpstreamRateLimitException}. */
    static final String RATE_MESSAGE = "NetSuite rate limit exceeded";

    /** Message of {@link UpstreamUnavailableException}. */
    static final String UNAVAILABLE_MESSAGE = "NetSuite unavailable";

    /** Message of the {@link IllegalStateException} raised for a body that cannot be serialised. */
    static final String SERIALISE_MESSAGE = "NetSuite SuiteQL request could not be serialised";

    /** Message of the {@link IllegalStateException} raised for a response that cannot be parsed. */
    static final String PARSE_MESSAGE = "NetSuite SuiteQL response could not be parsed";

    /** Prefix of the message raised for a page that reports {@code hasMore} without a positive count. */
    static final String EMPTY_PAGE_MESSAGE = "NetSuite SuiteQL page reported hasMore with count ";

    /** HTTP status of an authentication failure on a SuiteQL call. */
    private static final int UNAUTHORIZED = 401;

    /** HTTP status of a rate-limited call. */
    private static final int TOO_MANY_REQUESTS = 429;

    /** Body member holding the SuiteQL text. */
    private static final String Q_MEMBER = "q";

    /** Body member holding the bound parameter values. */
    private static final String PARAMS_MEMBER = "params";

    /** Response member holding the rows of the page. */
    private static final String ITEMS_MEMBER = "items";

    /** Response member stating whether a further page exists. */
    private static final String HAS_MORE_MEMBER = "hasMore";

    /** Response member holding the number of rows of the page. */
    private static final String COUNT_MEMBER = "count";

    /** Query parameter carrying the page size. */
    private static final String LIMIT_PARAMETER = "limit";

    /** Query parameter carrying the index of the first row of the page. */
    private static final String OFFSET_PARAMETER = "offset";

    /** Jackson target type of a SuiteQL response body: members in document order. */
    private static final TypeReference<LinkedHashMap<String, Object>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private static final Logger log = LoggerFactory.getLogger(NetsuiteRestClient.class);

    /** {@code WebClient} with the NetSuite REST base URL and the OAuth 2.0 M2M exchange filter. */
    private final WebClient netsuiteWebClient;

    /** Removes the cached access token before the single re-authenticated retry. */
    private final AuthorizedClientEvictor evictor;

    /** Serialises request bodies and parses response bodies. */
    private final ObjectMapper objectMapper;

    /**
     * Removes the cached {@code netsuite} authorized client so the next call obtains a new access
     * token (D-020).
     */
    @FunctionalInterface
    public interface AuthorizedClientEvictor {

        /**
         * Removes the cached {@code netsuite} authorized client; the next call through the
         * {@code netsuiteWebClient} requests a new access token from the token endpoint (D-020).
         */
        void evict();
    }

    /**
     * Creates the client.
     *
     * @param netsuiteWebClient the {@code WebClient} whose base URL is the account's REST Web Services
     *                          host and whose exchange filter adds the OAuth 2.0 M2M access token
     * @param evictor           removes the cached authorized client before the re-authenticated retry
     * @param objectMapper      serialises the SuiteQL body and parses the SuiteQL response
     * @throws NullPointerException if any argument is {@code null}
     */
    public NetsuiteRestClient(@Qualifier("netsuiteWebClient") WebClient netsuiteWebClient,
                              AuthorizedClientEvictor evictor,
                              ObjectMapper objectMapper) {
        this.netsuiteWebClient = Objects.requireNonNull(netsuiteWebClient, "netsuiteWebClient");
        this.evictor = Objects.requireNonNull(evictor, "evictor");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Runs a SuiteQL query and returns every row of every page.
     *
     * <p>Each page is one {@code POST <path>?limit=<limit>&offset=<offset>} with the headers of the
     * request ({@code Prefer: transient}), {@code Content-Type: application/json} and the body
     * {@code {"q": <q>, "params": <params>}}; the OAuth 2.0 exchange filter adds the
     * {@code Authorization} header (D-017). The first page uses {@code request} unchanged. While a
     * page reports {@code "hasMore": true}, the next page is {@code request} with the offset advanced
     * by that page's {@code count}; a page without {@code count} advances by its number of rows. A
     * page without {@code hasMore} is the last page, and a page without {@code items} has no rows
     * (D-405).
     *
     * <p>Rows are returned unchanged and in NetSuite order across pages: each is the parsed JSON object
     * of one {@code items} element, with the lower-case keys {@code email}, {@code firstname} and
     * {@code lastname} of the query's select list and the {@code links} member NetSuite adds. A failure
     * on any page ends the query; no partial result is returned (D-020).
     *
     * @param request the first page request, as built by
     *                {@link SuiteQlQueryBuilder#customersByLastName(String)}
     * @return the rows of all pages in order, unmodifiable; empty when NetSuite returns no row
     * @throws UpstreamAuthenticationException if NetSuite answers 401 again after one re-authenticated
     *                                         retry, or the token endpoint rejects the token request
     * @throws UpstreamRateLimitException      if NetSuite or its token endpoint answers 429
     * @throws UpstreamUnavailableException    if NetSuite or its token endpoint cannot be reached or a
     *                                         timeout ends the call
     * @throws IllegalStateException           if the body cannot be serialised, a response is empty or
     *                                         is not a SuiteQL page, or a page reports
     *                                         {@code "hasMore": true} with a {@code count} that is not
     *                                         greater than 0
     * @throws NullPointerException            if {@code request} is {@code null}
     * @throws WebClientResponseException      for any other HTTP error status, unchanged
     */
    public List<Map<String, Object>> query(SuiteQlQueryBuilder.SuiteQlRequest request) {
        Objects.requireNonNull(request, "request");
        List<Map<String, Object>> rows = new ArrayList<>();
        SuiteQlQueryBuilder.SuiteQlRequest page = request;
        while (true) {
            SuiteQlQueryBuilder.SuiteQlRequest current = page;
            byte[] body = requestBody(current);
            byte[] response = execute(SUITEQL_OPERATION, () -> send(current, body));
            Map<String, Object> parsed = parse(response);
            List<Map<String, Object>> items = items(parsed);
            rows.addAll(items);
            boolean hasMore = Boolean.TRUE.equals(parsed.get(HAS_MORE_MEMBER));
            int count = count(parsed, items.size());
            log.debug("NetSuite {} page at offset {} returned {} rows, hasMore={}",
                    SUITEQL_OPERATION, current.offset(), items.size(), hasMore);
            if (!hasMore) {
                return Collections.unmodifiableList(rows);
            }
            // A page that reports hasMore without a positive count fails the whole query (D-405).
            if (count <= 0) {
                throw new IllegalStateException(EMPTY_PAGE_MESSAGE + count);
            }
            page = request.withOffset(Math.addExact(current.offset(), count));
        }
    }

    /**
     * Serialises the SuiteQL body {@code {"q": <q>, "params": <params>}}, members in that order.
     *
     * @param page the page request
     * @return the JSON bytes of the body
     * @throws IllegalStateException if Jackson cannot serialise the body
     */
    private byte[] requestBody(SuiteQlQueryBuilder.SuiteQlRequest page) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(Q_MEMBER, page.q());
        body.put(PARAMS_MEMBER, page.params());
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(SERIALISE_MESSAGE, e);
        }
    }

    /**
     * Sends one page request and returns the raw response body.
     *
     * @param page the page request
     * @param body the serialised SuiteQL body
     * @return the response body bytes, or {@code null} for an empty body
     * @throws WebClientResponseException for an HTTP error status
     * @throws WebClientRequestException  for a connectivity failure or timeout
     */
    private byte[] send(SuiteQlQueryBuilder.SuiteQlRequest page, byte[] body) {
        return netsuiteWebClient.post()
                .uri(builder -> builder.path(page.path())
                        .queryParam(LIMIT_PARAMETER, page.limit())
                        .queryParam(OFFSET_PARAMETER, page.offset())
                        .build())
                .headers(headers -> page.headers().forEach(headers::set))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();
    }

    /**
     * Parses a SuiteQL response body into its top-level members, in document order.
     *
     * @param response the response body bytes, possibly {@code null}
     * @return the top-level members of the JSON object
     * @throws IllegalStateException if the body is {@code null}, empty, JSON {@code null} or not a JSON
     *                               object
     */
    private Map<String, Object> parse(byte[] response) {
        if (response == null || response.length == 0) {
            throw new IllegalStateException(PARSE_MESSAGE);
        }
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(response, RESPONSE_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException(PARSE_MESSAGE, e);
        }
        if (parsed == null) {
            throw new IllegalStateException(PARSE_MESSAGE);
        }
        return parsed;
    }

    /**
     * Returns the rows of a page: the elements of its {@code items} array, unchanged and in order.
     *
     * @param page the parsed page
     * @return the rows; empty when {@code items} is absent or JSON {@code null}
     * @throws IllegalStateException if {@code items} is not an array or holds an element that is not a
     *                               JSON object
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        Object items = page.get(ITEMS_MEMBER);
        if (items == null) {
            return List.of();
        }
        if (!(items instanceof List<?> elements)) {
            throw new IllegalStateException(PARSE_MESSAGE);
        }
        List<Map<String, Object>> rows = new ArrayList<>(elements.size());
        for (Object element : elements) {
            if (!(element instanceof Map<?, ?>)) {
                throw new IllegalStateException(PARSE_MESSAGE);
            }
            rows.add((Map<String, Object>) element);
        }
        return rows;
    }

    /**
     * Returns the {@code count} member of a page.
     *
     * @param page     the parsed page
     * @param rowCount the number of rows of the page
     * @return {@code count} as an {@code int}, or {@code rowCount} when {@code count} is absent or JSON
     *         {@code null}
     * @throws IllegalStateException if {@code count} is not a number
     */
    private static int count(Map<String, Object> page, int rowCount) {
        Object count = page.get(COUNT_MEMBER);
        if (count == null) {
            return rowCount;
        }
        if (!(count instanceof Number number)) {
            throw new IllegalStateException(PARSE_MESSAGE);
        }
        return number.intValue();
    }

    /**
     * Runs one NetSuite call and translates its failure into the upstream exceptions of D-020.
     *
     * <p>Attempts and retry:
     * <ul>
     *   <li>The call runs once. When it fails with HTTP 401, the cached authorized client is evicted
     *       through {@link AuthorizedClientEvictor#evict()} and the call runs a second time, which
     *       obtains a new access token. A second HTTP 401 raises
     *       {@link UpstreamAuthenticationException}; any other failure of the second attempt is
     *       translated as below. No call runs a third time.</li>
     *   <li>No other failure is retried.</li>
     * </ul>
     *
     * <p>Translation, checked in this order:
     * <ol>
     *   <li>HTTP 429 from NetSuite: {@link UpstreamRateLimitException} carrying the response's
     *       {@code Retry-After} value, or {@code null} when the response has none.</li>
     *   <li>Token endpoint failure, that is an {@link OAuth2AuthorizationException} (including
     *       {@code ClientAuthorizationException}) at any depth of the cause chain, classified by the
     *       token-endpoint rule of D-404:
     *       <ul>
     *         <li>an {@link HttpStatusCodeException} with status 429 in the chain:
     *             {@link UpstreamRateLimitException} carrying that response's {@code Retry-After}
     *             value, or {@code null} when it has none;</li>
     *         <li>otherwise an {@link IOException} in the chain:
     *             {@link UpstreamUnavailableException};</li>
     *         <li>otherwise: {@link UpstreamAuthenticationException}.</li>
     *       </ul></li>
     *   <li>Connectivity failure or timeout, that is a {@link WebClientRequestException}, or a
     *       {@link ConnectException}, {@link SocketTimeoutException}, {@link ReadTimeoutException},
     *       {@link WriteTimeoutException} or {@link TimeoutException} at any depth of the cause chain:
     *       {@link UpstreamUnavailableException}.</li>
     *   <li>Any other failure, including HTTP error statuses other than 401 and 429, is rethrown
     *       unchanged.</li>
     * </ol>
     *
     * <p>Each translated failure and the re-authenticated retry are logged once at WARN with the
     * operation name and the classification; no header, token or key is logged.
     *
     * <pre>{@code
     * byte[] body = execute("suiteql", () -> webClient.post()...retrieve().bodyToMono(byte[].class).block());
     * }</pre>
     *
     * @param operation the operation name written to the log
     * @param call      the NetSuite call; it runs at most twice
     * @param <T>       the result type of the call
     * @return the result of the successful attempt
     * @throws UpstreamAuthenticationException if both attempts answer 401, or the token endpoint
     *                                         rejects the token request
     * @throws UpstreamRateLimitException      if NetSuite or its token endpoint answers 429
     * @throws UpstreamUnavailableException    if NetSuite or its token endpoint cannot be reached or a
     *                                         timeout ends the call
     * @throws RuntimeException                any other failure of the call, unchanged
     */
    <T> T execute(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException first) {
            if (!isStatus(first, UNAUTHORIZED)) {
                throw translate(operation, first);
            }
            log.warn("NetSuite {} returned 401; authorized client evicted, request re-sent once", operation);
            evictor.evict();
            try {
                return call.get();
            } catch (RuntimeException second) {
                if (isStatus(second, UNAUTHORIZED)) {
                    log.warn("NetSuite {} failed: authentication rejected after re-authentication", operation);
                    throw new UpstreamAuthenticationException(AUTH_MESSAGE, UPSTREAM_SYSTEM, second);
                }
                throw translate(operation, second);
            }
        }
    }

    /**
     * Returns whether a failure is an HTTP error response with the given status.
     *
     * @param e    the failure
     * @param code the HTTP status code
     * @return {@code true} when {@code e} is a {@link WebClientResponseException} with status
     *         {@code code}
     */
    private static boolean isStatus(RuntimeException e, int code) {
        return e instanceof WebClientResponseException response && response.getStatusCode().value() == code;
    }

    /**
     * Translates a failed call into the exception {@link #execute(String, Supplier)} throws.
     *
     * @param operation the operation name written to the log
     * @param e         the failure of the call
     * @return the upstream exception wrapping {@code e}, or {@code e} itself when no rule applies
     */
    private RuntimeException translate(String operation, RuntimeException e) {
        if (isStatus(e, TOO_MANY_REQUESTS)) {
            String retryAfter = ((WebClientResponseException) e).getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            log.warn("NetSuite {} failed: rate limited, Retry-After={}", operation, retryAfter);
            return new UpstreamRateLimitException(RATE_MESSAGE, UPSTREAM_SYSTEM, retryAfter, e);
        }
        List<Throwable> chain = causeChain(e);
        // A token endpoint failure is classified before the connectivity rule and is never retried (D-404).
        if (contains(chain, OAuth2AuthorizationException.class)) {
            return translateTokenEndpointFailure(operation, e, chain);
        }
        if (e instanceof WebClientRequestException || containsConnectivityFailure(chain)) {
            log.warn("NetSuite {} failed: unavailable, {}", operation, e.getClass().getName());
            return new UpstreamUnavailableException(UNAVAILABLE_MESSAGE, UPSTREAM_SYSTEM, e);
        }
        return e;
    }

    /**
     * Classifies a token endpoint failure by the token-endpoint rule of D-404; the failure is never
     * retried.
     *
     * @param operation the operation name written to the log
     * @param e         the failure of the call
     * @param chain     the cause chain of {@code e}, starting with {@code e}
     * @return {@link UpstreamRateLimitException} for a 429 response in the chain, otherwise
     *         {@link UpstreamUnavailableException} for an {@link IOException} in the chain, otherwise
     *         {@link UpstreamAuthenticationException}
     */
    private RuntimeException translateTokenEndpointFailure(String operation, RuntimeException e,
                                                           List<Throwable> chain) {
        for (Throwable cause : chain) {
            if (cause instanceof HttpStatusCodeException status
                    && status.getStatusCode().value() == TOO_MANY_REQUESTS) {
                HttpHeaders headers = status.getResponseHeaders();
                String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                log.warn("NetSuite {} failed: token endpoint rate limited, Retry-After={}", operation, retryAfter);
                return new UpstreamRateLimitException(RATE_MESSAGE, UPSTREAM_SYSTEM, retryAfter, e);
            }
        }
        if (contains(chain, IOException.class)) {
            log.warn("NetSuite {} failed: token endpoint unavailable", operation);
            return new UpstreamUnavailableException(UNAVAILABLE_MESSAGE, UPSTREAM_SYSTEM, e);
        }
        log.warn("NetSuite {} failed: token request rejected", operation);
        return new UpstreamAuthenticationException(AUTH_MESSAGE, UPSTREAM_SYSTEM, e);
    }

    /**
     * Returns whether the cause chain holds a connectivity failure or timeout: a
     * {@link ConnectException}, {@link SocketTimeoutException}, {@link ReadTimeoutException},
     * {@link WriteTimeoutException} or {@link TimeoutException}.
     *
     * @param chain the cause chain
     * @return {@code true} when any element is one of those types or a subtype
     */
    private static boolean containsConnectivityFailure(List<Throwable> chain) {
        return contains(chain, ConnectException.class)
                || contains(chain, SocketTimeoutException.class)
                || contains(chain, ReadTimeoutException.class)
                || contains(chain, WriteTimeoutException.class)
                || contains(chain, TimeoutException.class);
    }

    /**
     * Returns whether any element of the cause chain is an instance of the given type.
     *
     * @param chain the cause chain
     * @param type  the type looked for
     * @return {@code true} when an element is an instance of {@code type}
     */
    private static boolean contains(List<Throwable> chain, Class<? extends Throwable> type) {
        for (Throwable cause : chain) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the cause chain of a failure: the failure itself, then each {@link Throwable#getCause()}
     * in turn. The walk stops at a {@code null} cause, at a self-referencing cause and at a cause
     * already listed, compared by identity.
     *
     * @param e the failure
     * @return the chain, starting with {@code e}
     */
    private static List<Throwable> causeChain(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = e;
        while (current != null && visited.add(current)) {
            chain.add(current);
            current = current.getCause();
        }
        return chain;
    }
}
