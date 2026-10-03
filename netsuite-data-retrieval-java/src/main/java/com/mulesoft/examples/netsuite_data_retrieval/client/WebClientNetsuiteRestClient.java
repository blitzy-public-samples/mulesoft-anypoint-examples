package com.mulesoft.examples.netsuite_data_retrieval.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteOAuth2Config;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * {@link NetsuiteRestClient} on the NetSuite REST record API, sent through the OAuth 2.0 M2M
 * {@code netsuiteWebClient} of {@link NetsuiteOAuth2Config} (D-016). It replaces:
 * <ul>
 *   <li>the connection {@code netsuite:config-login-authentication NetSuite__Login_Authentication}
 *       [netsuite-data-retrieval/src/main/app/netsuite-api.xml:18];</li>
 *   <li>the {@code netsuite:search} calls on {@code CUSTOMER}, {@code ITEM_SUPPLY_PLAN_BASIC} and
 *       {@code OPPORTUNITY} [netsuite-api.xml:41, :68, :97], as {@link #queryIds(String, String)}
 *       followed by {@link #getRecord(String, String, boolean)} per id;</li>
 *   <li>the {@code netsuite:add-record} and {@code netsuite:delete-record} calls of the MUnit
 *       sub-flows {@code createCustomerInNetsuite} and {@code deleteCustomerInNetsuite}
 *       [netsuite-data-retrieval/src/test/munit/netsuite-api-test-suite.xml:51-73], as
 *       {@link #createCustomer(String, String)} and {@link #deleteCustomer(String)}.</li>
 * </ul>
 *
 * <p>Requests:
 * <ul>
 *   <li>Every path starts with {@code /services/rest/record/v1} under the {@code netsuiteWebClient}
 *       base URL. Record types, ids and the {@code q} filter are URI template variables, which the
 *       {@code WebClient} URI builder percent-encodes: a space becomes {@code %20}, {@code "}
 *       {@code %22}, {@code &} {@code %26} and {@code +} {@code %2B}.</li>
 *   <li>Every request carries the exchange-filter attributes of registration
 *       {@value NetsuiteOAuth2Config#REGISTRATION_ID} and of an authenticated principal named
 *       {@value NetsuiteOAuth2Config#PRINCIPAL_NAME} (D-524). The filter adds
 *       {@code Authorization: Bearer <access token>} through the authorized-client manager of
 *       {@link NetsuiteOAuth2Config}; no servlet request is needed. The access token is requested on
 *       the first call, not at construction.</li>
 *   <li>No {@code Accept} header is set. Each request is one blocking exchange with no Reactor retry,
 *       error-resume or timeout operator; connect and response timeouts are those of the
 *       {@code netsuiteWebClient} (D-095).</li>
 *   <li>Response bodies are read whole as bytes and parsed by a copy of the injected
 *       {@link ObjectMapper} with {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} enabled:
 *       floating-point numbers become {@code BigDecimal}-backed nodes. The injected mapper is not
 *       changed.</li>
 * </ul>
 *
 * <p>Failures (D-020): each REST call, each page of a collection query included, runs through the
 * private {@code execute} wrapper, which raises {@link UpstreamUnavailableException},
 * {@link UpstreamRateLimitException} or {@link UpstreamAuthenticationException}, re-issues a call at
 * most once and only after removing the stored authorized client, and rethrows every other failure
 * unchanged, for example a NetSuite 404 as {@code WebClientResponseException.NotFound}.
 *
 * <p>The client holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * List<String> ids = client.queryIds("customer", "companyname START_WITH \"Acme\"");
 * JsonNode customer = client.getRecord("customer", ids.get(0), false);
 * }</pre>
 */
@Component
public class WebClientNetsuiteRestClient implements NetsuiteRestClient {

    /** Logger of the client: INFO for the customer fixture, WARN for each classified failure, DEBUG per page. */
    private static final Logger log = LoggerFactory.getLogger(WebClientNetsuiteRestClient.class);

    /** Path of the NetSuite REST record API under the REST base URL (D-016). */
    private static final String RECORD_PATH = "/services/rest/record/v1";

    /** Page size of a record collection query, sent as the {@code limit} query parameter (D-016). */
    private static final int PAGE_LIMIT = 1000;

    /**
     * Principal of every request: an authenticated token named
     * {@value NetsuiteOAuth2Config#PRINCIPAL_NAME} with no authorities (D-524).
     */
    private static final Authentication PRINCIPAL = UsernamePasswordAuthenticationToken.authenticated(
            NetsuiteOAuth2Config.PRINCIPAL_NAME, null, AuthorityUtils.NO_AUTHORITIES);

    /** Record type of {@link #createCustomer(String, String)} and {@link #deleteCustomer(String)}. */
    private static final String CUSTOMER_PATH = RECORD_PATH + "/customer";

    /** Message prefix of {@link UpstreamUnavailableException}. */
    private static final String UNAVAILABLE = "NetSuite unavailable: ";

    /** Message prefix of {@link UpstreamRateLimitException}. */
    private static final String RATE_LIMITED = "NetSuite rate limit exceeded: ";

    /** Message prefix of {@link UpstreamAuthenticationException}. */
    private static final String AUTHENTICATION_FAILED = "NetSuite authentication failed: ";

    /** Message prefix of the {@link IllegalStateException} raised for an empty response body. */
    private static final String EMPTY_RESPONSE = "Empty NetSuite response: ";

    /** Message prefix of the {@link IllegalStateException} raised for a response of the wrong shape. */
    private static final String INVALID_RESPONSE = "Invalid NetSuite response: ";

    /** OAuth-filtered {@code WebClient} whose base URL is the NetSuite REST Web Services host. */
    private final WebClient webClient;

    /** Store of the NetSuite authorized client; its entry is removed before the single retry. */
    private final OAuth2AuthorizedClientService authorizedClientService;

    /** Copy of Boot's mapper with {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} enabled. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the client. No request is sent and no token is requested here.
     *
     * @param webClient               the {@code netsuiteWebClient} bean of {@link NetsuiteOAuth2Config}
     * @param authorizedClientService the store of the NetSuite authorized client
     * @param objectMapper            Boot's auto-configured mapper; a copy with
     *                                {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} enabled is
     *                                kept, and this instance is not modified
     * @throws NullPointerException if any argument is {@code null}
     */
    public WebClientNetsuiteRestClient(@Qualifier("netsuiteWebClient") WebClient webClient,
                                       OAuth2AuthorizedClientService authorizedClientService,
                                       ObjectMapper objectMapper) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.authorizedClientService = Objects.requireNonNull(authorizedClientService, "authorizedClientService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each page is one call {@code GET /services/rest/record/v1/<recordType>?limit=1000&offset=<n>},
     * plus {@code &q=<q>} when {@code q} is not {@code null}, wrapped on its own in {@code execute} with
     * the operation {@code queryIds <recordType>}. The first page has offset 0. From each page the
     * client appends the {@code id} of every {@code items} element, in order, and reads {@code count}
     * (0 when absent) and {@code hasMore} ({@code false} when absent). It stops when {@code hasMore} is
     * {@code false} or {@code count} is 0 or less, and otherwise requests the next page at
     * {@code offset + count}. Ids are kept as returned, duplicates included.
     *
     * @return the ids of all pages in collection order, unmodifiable
     * @throws NullPointerException  if {@code recordType} is {@code null}
     * @throws IllegalStateException if a page body is empty, is not a JSON object, has an {@code items}
     *                               member that is not an array, or lists an element without an
     *                               {@code id} value
     * @throws UncheckedIOException  if a page body is not valid JSON
     */
    @Override
    public List<String> queryIds(String recordType, String q) {
        Objects.requireNonNull(recordType, "recordType");
        String operation = "queryIds " + recordType;
        Map<String, Object> vars = new HashMap<>();
        vars.put("type", recordType);
        if (q != null) {
            vars.put("q", q);
        }
        Map<String, Object> uriVariables = Collections.unmodifiableMap(vars);

        List<String> ids = new ArrayList<>();
        int offset = 0;
        while (true) {
            int pageOffset = offset;
            byte[] body = execute(operation, () -> webClient.get()
                    .uri(builder -> {
                        builder.path(RECORD_PATH + "/{type}")
                                .queryParam("limit", PAGE_LIMIT)
                                .queryParam("offset", pageOffset);
                        if (q != null) {
                            builder.queryParam("q", "{q}");
                        }
                        return builder.build(uriVariables);
                    })
                    .attributes(oauth())
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block());

            JsonNode page = parse(body, operation);
            if (!page.isObject()) {
                throw new IllegalStateException(INVALID_RESPONSE + operation);
            }
            int idsBefore = ids.size();
            appendIds(page, ids, operation);
            int count = page.path("count").asInt(0);
            boolean hasMore = page.path("hasMore").asBoolean(false);
            log.debug("NetSuite {} page at offset {} listed {} ids (count {}, hasMore {})",
                    operation, pageOffset, ids.size() - idsBefore, count, hasMore);
            if (!hasMore || count <= 0) {
                return Collections.unmodifiableList(ids);
            }
            offset = Math.addExact(offset, count);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>One call {@code GET /services/rest/record/v1/<recordType>/<id>}, plus
     * {@code ?expandSubResources=true} only when {@code expandSubResources} is set, wrapped in
     * {@code execute} with the operation {@code getRecord <recordType> <id>}. The parsed body is returned
     * unchanged.
     *
     * @throws NullPointerException  if {@code recordType} or {@code id} is {@code null}
     * @throws IllegalStateException if the response body is empty
     * @throws UncheckedIOException  if the response body is not valid JSON
     */
    @Override
    public JsonNode getRecord(String recordType, String id, boolean expandSubResources) {
        Objects.requireNonNull(recordType, "recordType");
        Objects.requireNonNull(id, "id");
        String operation = "getRecord " + recordType + " " + id;
        Map<String, Object> uriVariables = Map.of("type", recordType, "id", id);

        byte[] body = execute(operation, () -> webClient.get()
                .uri(builder -> {
                    builder.path(RECORD_PATH + "/{type}/{id}");
                    if (expandSubResources) {
                        builder.queryParam("expandSubResources", true);
                    }
                    return builder.build(uriVariables);
                })
                .attributes(oauth())
                .retrieve()
                .bodyToMono(byte[].class)
                .block());
        return parse(body, operation);
    }

    /**
     * {@inheritDoc}
     *
     * <p>One call {@code POST /services/rest/record/v1/customer} with {@code Content-Type:
     * application/json} and the body {@code {"companyname":<companyName>,"subsidiary":{"id":<subsidiaryId>}}},
     * wrapped in {@code execute} with the operation {@code createCustomer}; a timed-out request is not
     * re-sent (D-020). The returned id is the last path segment of the response's {@code Location}
     * header, for example {@code 42} from {@code .../services/rest/record/v1/customer/42}. On success
     * the client logs {@code Test customer created with ID: <id>} at INFO
     * [netsuite-api-test-suite.xml:66].
     *
     * @throws NullPointerException  if {@code companyName} or {@code subsidiaryId} is {@code null}
     * @throws IllegalStateException if the response has no {@code Location} header, an unparseable one,
     *                               or one whose path ends without a record id
     */
    @Override
    public String createCustomer(String companyName, String subsidiaryId) {
        Objects.requireNonNull(companyName, "companyName");
        Objects.requireNonNull(subsidiaryId, "subsidiaryId");
        String operation = "createCustomer";
        ObjectNode customer = objectMapper.createObjectNode();
        customer.put("companyname", companyName);
        customer.putObject("subsidiary").put("id", subsidiaryId);
        byte[] body = serialize(customer, operation);

        ResponseEntity<Void> response = execute(operation, () -> webClient.post()
                .uri(CUSTOMER_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .attributes(oauth())
                .retrieve()
                .toBodilessEntity()
                .block());

        String customerId = idFromLocation(response, operation);
        log.info("Test customer created with ID: {}", customerId);
        return customerId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One call {@code DELETE /services/rest/record/v1/customer/<id>}, wrapped in {@code execute}
     * with the operation {@code deleteCustomer <id>}. On success the client logs
     * {@code Test customer with ID <id> was deleted.} at INFO [netsuite-api-test-suite.xml:72].
     *
     * @throws NullPointerException if {@code id} is {@code null}
     */
    @Override
    public void deleteCustomer(String id) {
        Objects.requireNonNull(id, "id");
        String operation = "deleteCustomer " + id;
        Map<String, Object> uriVariables = Map.of("id", id);
        Supplier<Void> delete = () -> {
            webClient.delete()
                    .uri(CUSTOMER_PATH + "/{id}", uriVariables)
                    .attributes(oauth())
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return null;
        };
        execute(operation, delete);
        log.info("Test customer with ID {} was deleted.", id);
    }

    /**
     * Request attributes of every NetSuite call: client registration
     * {@value NetsuiteOAuth2Config#REGISTRATION_ID} and the principal {@link #PRINCIPAL} (D-524).
     *
     * @return the attribute consumer passed to {@code WebClient.RequestHeadersSpec#attributes}
     */
    private static Consumer<Map<String, Object>> oauth() {
        return ServletOAuth2AuthorizedClientExchangeFilterFunction
                .clientRegistrationId(NetsuiteOAuth2Config.REGISTRATION_ID)
                .andThen(ServletOAuth2AuthorizedClientExchangeFilterFunction.authentication(PRINCIPAL));
    }

    /**
     * Runs one NetSuite REST call and translates its failure (D-020).
     *
     * <p>The call runs once. A {@link RuntimeException} it throws is classified by its cause chain
     * (the exception and each {@link Throwable#getCause()} link, every link visited once), in this
     * order:
     * <ol>
     *   <li>I/O: a link is a {@link WebClientRequestException}, {@link ConnectException},
     *       {@link SocketTimeoutException}, {@link ReadTimeoutException} or
     *       {@link ResourceAccessException} (the token request's I/O failure): throws
     *       {@link UpstreamUnavailableException} {@code NetSuite unavailable: <operation>}; the call is
     *       not re-issued.</li>
     *   <li>Rate limit: a link is a {@link WebClientResponseException} or an
     *       {@link HttpClientErrorException} (the token request's error) with status 429: throws
     *       {@link UpstreamRateLimitException} {@code NetSuite rate limit exceeded: <operation>} with that
     *       link's {@code Retry-After} header value, or {@code null} without one; the call is not
     *       re-issued.</li>
     *   <li>Authentication: a link is a {@link WebClientResponseException} with status 401 or an
     *       {@link OAuth2AuthorizationException} (token request rejected): logs WARN, removes the stored
     *       authorized client {@value NetsuiteOAuth2Config#REGISTRATION_ID} /
     *       {@value NetsuiteOAuth2Config#PRINCIPAL_NAME} and runs the call a second time, which requests
     *       a new access token. A failure of the second run is classified by rules 1 and 2; any other
     *       failure of it throws {@link UpstreamAuthenticationException}
     *       {@code NetSuite authentication failed: <operation>}. No call runs a third time.</li>
     *   <li>Any other failure, for example a 403 or 404 {@link WebClientResponseException}, is rethrown
     *       unchanged.</li>
     * </ol>
     * Each upstream exception carries the classified failure as its cause. {@link Error}s are not
     * caught.
     *
     * @param operation the operation name used in log lines and exception messages
     * @param call      the REST call; it runs at most twice
     * @param <T>       the result type of the call
     * @return the result of the successful run
     */
    private <T> T execute(String operation, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException ex) {
            List<Throwable> chain = causeChain(ex);
            throwIfUnavailable(operation, ex, chain);
            throwIfRateLimited(operation, ex, chain);
            if (!isAuthenticationFailure(chain)) {
                throw ex;
            }
            log.warn("NetSuite authentication failed: {}; requesting a new token and retrying once", operation);
            authorizedClientService.removeAuthorizedClient(
                    NetsuiteOAuth2Config.REGISTRATION_ID, NetsuiteOAuth2Config.PRINCIPAL_NAME);
            try {
                return call.get();
            } catch (RuntimeException ex2) {
                List<Throwable> retryChain = causeChain(ex2);
                throwIfUnavailable(operation, ex2, retryChain);
                throwIfRateLimited(operation, ex2, retryChain);
                log.warn("NetSuite authentication failed again after a new token: {} ({})",
                        operation, ex2.getClass().getName());
                throw new UpstreamAuthenticationException(AUTHENTICATION_FAILED + operation, ex2);
            }
        }
    }

    /**
     * Throws {@link UpstreamUnavailableException} when a link of {@code chain} is an I/O failure:
     * {@link WebClientRequestException}, {@link ConnectException}, {@link SocketTimeoutException},
     * {@link ReadTimeoutException} or {@link ResourceAccessException}. Other {@link IOException}s, such as
     * a JSON parse error, are not I/O failures here.
     *
     * @param operation the operation name used in the log line and the exception message
     * @param ex        the failure of the call, kept as the exception's cause
     * @param chain     the cause chain of {@code ex}
     * @throws UpstreamUnavailableException {@code NetSuite unavailable: <operation>}
     */
    private static void throwIfUnavailable(String operation, RuntimeException ex, List<Throwable> chain) {
        for (Throwable link : chain) {
            if (link instanceof WebClientRequestException
                    || link instanceof ConnectException
                    || link instanceof SocketTimeoutException
                    || link instanceof ReadTimeoutException
                    || link instanceof ResourceAccessException) {
                log.warn("NetSuite unavailable: {} ({})", operation, link.getClass().getName());
                throw new UpstreamUnavailableException(UNAVAILABLE + operation, ex);
            }
        }
    }

    /**
     * Throws {@link UpstreamRateLimitException} when a link of {@code chain} is a
     * {@link WebClientResponseException} or {@link HttpClientErrorException} with status 429, carrying
     * that link's {@code Retry-After} header value unchanged, or {@code null} when it has none.
     *
     * @param operation the operation name used in the log line and the exception message
     * @param ex        the failure of the call, kept as the exception's cause
     * @param chain     the cause chain of {@code ex}
     * @throws UpstreamRateLimitException {@code NetSuite rate limit exceeded: <operation>}
     */
    private static void throwIfRateLimited(String operation, RuntimeException ex, List<Throwable> chain) {
        for (Throwable link : chain) {
            HttpHeaders headers = null;
            boolean rateLimited = false;
            if (link instanceof WebClientResponseException response
                    && response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                headers = response.getHeaders();
                rateLimited = true;
            } else if (link instanceof HttpClientErrorException response
                    && response.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                headers = response.getResponseHeaders();
                rateLimited = true;
            }
            if (rateLimited) {
                String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                log.warn("NetSuite rate limit exceeded: {} (Retry-After {})", operation, retryAfter);
                throw new UpstreamRateLimitException(RATE_LIMITED + operation, retryAfter, ex);
            }
        }
    }

    /**
     * Whether a link of {@code chain} is a {@link WebClientResponseException} with status 401 or an
     * {@link OAuth2AuthorizationException}, {@code ClientAuthorizationException} included.
     *
     * @param chain the cause chain of the failure
     * @return {@code true} when the chain holds such a link
     */
    private static boolean isAuthenticationFailure(List<Throwable> chain) {
        for (Throwable link : chain) {
            if (link instanceof WebClientResponseException response
                    && response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                return true;
            }
            if (link instanceof OAuth2AuthorizationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * The cause chain of {@code ex}: {@code ex} first, then each {@link Throwable#getCause()} link; a
     * link already visited ends the chain.
     *
     * @param ex the failure
     * @return the links in cause order, {@code ex} first
     */
    private static List<Throwable> causeChain(Throwable ex) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable link = ex; link != null && visited.add(link); link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }

    /**
     * Appends the {@code id} of each element of the page's {@code items} array to {@code ids}, in
     * order; an absent or JSON {@code null} {@code items} member appends nothing.
     *
     * @param page      the parsed collection page, a JSON object
     * @param ids       the ids read so far; the page's ids are appended
     * @param operation the operation name used in the exception message
     * @throws IllegalStateException if {@code items} is not an array, or an element has no
     *                               {@code id}, a JSON {@code null}, non-scalar or empty one
     */
    private static void appendIds(JsonNode page, List<String> ids, String operation) {
        JsonNode items = page.path("items");
        if (items.isMissingNode() || items.isNull()) {
            return;
        }
        if (!items.isArray()) {
            throw new IllegalStateException(INVALID_RESPONSE + operation);
        }
        for (JsonNode item : items) {
            JsonNode id = item.get("id");
            if (id == null || id.isNull() || !id.isValueNode() || id.asText().isEmpty()) {
                throw new IllegalStateException(INVALID_RESPONSE + operation);
            }
            ids.add(id.asText());
        }
    }

    /**
     * Parses a response body with the {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS} mapper.
     *
     * @param body      the response body, {@code null} when the response had none
     * @param operation the operation name used in the exception messages
     * @return the parsed tree, never {@code null} or a missing node
     * @throws IllegalStateException {@code Empty NetSuite response: <operation>} when the body is
     *                               {@code null}, has no bytes or holds no JSON value
     * @throws UncheckedIOException  when the body is not valid JSON
     */
    private JsonNode parse(byte[] body, String operation) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException(EMPTY_RESPONSE + operation);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unreadable NetSuite response: " + operation, ex);
        }
        if (node == null || node.isMissingNode()) {
            throw new IllegalStateException(EMPTY_RESPONSE + operation);
        }
        return node;
    }

    /**
     * Serializes a request body to UTF-8 JSON bytes.
     *
     * @param body      the request body
     * @param operation the operation name used in the exception message
     * @return the JSON bytes of {@code body}
     * @throws UncheckedIOException when Jackson cannot write the body
     */
    private byte[] serialize(JsonNode body, String operation) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException("Unwritable NetSuite request body: " + operation, ex);
        }
    }

    /**
     * The record id of a create response: the substring after the last {@code /} of the path of its
     * {@code Location} header.
     *
     * @param response  the bodiless create response
     * @param operation the operation name used in the exception messages
     * @return the record id, never empty
     * @throws IllegalStateException {@code NetSuite returned no Location header: <operation>} when the
     *                               header is absent or blank; an {@code invalid Location header}
     *                               message when it is not a URI; a {@code no record id} message when
     *                               its path is empty or ends with {@code /}
     */
    private static String idFromLocation(ResponseEntity<Void> response, String operation) {
        String location = response == null ? null : response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("NetSuite returned no Location header: " + operation);
        }
        String path;
        try {
            path = URI.create(location).getPath();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("NetSuite returned an invalid Location header: " + operation, ex);
        }
        String id = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (id.isEmpty()) {
            throw new IllegalStateException("NetSuite returned no record id in the Location header: " + operation);
        }
        return id;
    }
}

