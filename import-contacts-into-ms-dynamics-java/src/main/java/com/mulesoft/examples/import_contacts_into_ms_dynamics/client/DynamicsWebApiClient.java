package com.mulesoft.examples.import_contacts_into_ms_dynamics.client;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Calls the Dataverse Web API v9.2 {@code contacts} entity set (D-019).
 *
 * <p>Replaces the {@code dynamicscrm:create logicalName="contact"} operation
 * [import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml:22-29] and the test
 * sub-flows {@code selectContactFromDynamics} and {@code deleteContactFromDynamics}
 * [import-contacts-into-ms-dynamics/src/test/resources/testflows/test-flows.xml:3-14]:
 * <ul>
 *   <li>{@link #createContact(Map)}: {@code POST /api/data/v9.2/contacts};</li>
 *   <li>{@link #findContactsByEmail(String)}:
 *       {@code GET /api/data/v9.2/contacts?$select=contactid,firstname,lastname&$filter=emailaddress1 eq '<email>'};</li>
 *   <li>{@link #deleteContact(String)}: {@code DELETE /api/data/v9.2/contacts(<id>)}.</li>
 * </ul>
 *
 * <p>Every request goes through the {@code dynamicsWebClient} bean, whose base URL is {@code dynamics.service-url},
 * whose default headers are {@code OData-Version: 4.0}, {@code OData-MaxVersion: 4.0} and
 * {@code Accept: application/json}, and whose exchange filter adds {@code Authorization: Bearer <token>} for the
 * client-credentials registration {@value #REGISTRATION_ID}. Every operation runs through
 * {@link #execute(String, Supplier)}, which turns a
 * failure into one of the {@code Upstream*Exception}s or rethrows it unchanged (D-020). A request is sent once,
 * except after a Dataverse {@code 401}, when it is sent once more with a new token.
 *
 * <p>Instances hold no mutable state and are safe for concurrent use.
 *
 * <p>Usage:
 * <pre>{@code
 * String id = client.createContact(contact);          // "00000000-0000-0000-0000-000000000001"
 * List<Map<String, Object>> found = client.findContactsByEmail("john.doe@texasComp.com");
 * client.deleteContact((String) found.get(0).get("contactid"));
 * }</pre>
 */
@Component
public class DynamicsWebApiClient {

    /** Upstream system name carried by every {@code Upstream*Exception} this client throws (D-020). */
    public static final String UPSTREAM_SYSTEM = "Microsoft Dataverse";

    /** Registration id of the Microsoft Entra client-credentials registration for the Dataverse Web API (D-019). */
    public static final String REGISTRATION_ID = "dynamics";

    /**
     * Principal name under which the OAuth 2.0 exchange filter stores the token of {@value #REGISTRATION_ID} when no
     * authentication is present in the Reactor context.
     */
    public static final String PRINCIPAL_NAME = "anonymousUser";

    /** Path of the Dataverse Web API v9.2 contacts entity set, relative to {@code dynamics.service-url} (D-019). */
    private static final String CONTACTS_PATH = "/api/data/v9.2/contacts";

    /** Matches {@code contacts(<id>)} in an {@code OData-EntityId} URI; group 1 is the record id. */
    private static final Pattern CONTACT_ID = Pattern.compile("contacts\\(([^)]+)\\)");

    /** Response header holding the URI of the created record. */
    private static final String ODATA_ENTITY_ID = "OData-EntityId";

    /** Columns read by {@link #findContactsByEmail(String)}, as the original DSQL selects them. */
    private static final String CONTACT_COLUMNS = "contactid,firstname,lastname";

    /** Member of an OData collection response that holds the records. */
    private static final String VALUE_MEMBER = "value";

    /** Response body type of an OData collection read: a JSON object with string keys. */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {
            };

    private static final Logger log = LoggerFactory.getLogger(DynamicsWebApiClient.class);

    /** The {@code dynamicsWebClient} bean: Dataverse base URL, bearer-token filter and OData headers. */
    private final WebClient webClient;

    /** The {@code dynamicsAuthorizedClientService} bean: the store of the obtained access token. */
    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;

    /**
     * Creates the client over the Dataverse {@code WebClient} and the token store of its exchange filter.
     *
     * @param dynamicsWebClient       the {@code dynamicsWebClient} bean of {@code config/DynamicsOAuth2Config}
     * @param authorizedClientService the store that holds the token of {@value #REGISTRATION_ID}; its entry for
     *                                {@value #PRINCIPAL_NAME} is removed after a Dataverse {@code 401}
     * @throws NullPointerException if either argument is {@code null}
     */
    public DynamicsWebApiClient(@Qualifier("dynamicsWebClient") WebClient dynamicsWebClient,
                                ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        this.webClient = Objects.requireNonNull(dynamicsWebClient, "dynamicsWebClient");
        this.authorizedClientService = Objects.requireNonNull(authorizedClientService, "authorizedClientService");
    }

    /**
     * Creates a Dataverse contact from the given attributes and returns its id (D-019).
     *
     * <p>Sends {@code POST /api/data/v9.2/contacts} with {@code Content-Type: application/json} and the attributes
     * as one JSON object, in map iteration order, a {@code null} value written as JSON {@code null}. Any 2xx status
     * is success. The id is the text inside {@code contacts(...)} of the {@code OData-EntityId} response header, for
     * example {@code 00000000-0000-0000-0000-000000000001} for
     * {@code https://org.crm.dynamics.com/api/data/v9.2/contacts(00000000-0000-0000-0000-000000000001)}.
     *
     * @param attributes the contact attributes, for example {@code firstname}, {@code lastname},
     *                   {@code emailaddress1} and {@code telephone1}
     * @return the id of the created contact, or {@code null} when the response has no {@code OData-EntityId}
     *     header or the header holds no {@code contacts(<id>)} segment
     * @throws NullPointerException             if {@code attributes} is {@code null}
     * @throws UpstreamUnavailableException     when the Dataverse or token request cannot connect or times out
     * @throws UpstreamAuthenticationException  when the token request is rejected, or Dataverse answers
     *                                          {@code 401} to both the request and its re-issue
     * @throws UpstreamRateLimitException       when Dataverse or the token endpoint answers {@code 429}
     * @throws WebClientResponseException       for any other non-2xx status, unchanged
     */
    public String createContact(Map<String, Object> attributes) {
        Objects.requireNonNull(attributes, "attributes");
        ResponseEntity<Void> entity = execute("createContact", () -> webClient.post()
                .uri(CONTACTS_PATH)
                .attributes(ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(REGISTRATION_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(attributes)
                .retrieve()
                .toBodilessEntity()
                .block());
        return entity == null ? null : contactId(entity.getHeaders().getFirst(ODATA_ENTITY_ID));
    }

    /**
     * Returns the contacts whose {@code emailaddress1} equals the given value.
     *
     * <p>Sends {@code GET /api/data/v9.2/contacts} with {@code $select=contactid,firstname,lastname} and
     * {@code $filter=emailaddress1 eq '<email>'}, with every {@code '} of the value written as {@code ''}. The
     * filter is passed as an encoded URI variable. Each returned map holds the record's
     * {@code contactid}, {@code firstname} and {@code lastname} members, plus any OData annotation members
     * Dataverse adds, such as {@code @odata.etag}.
     *
     * @param email the e-mail address to match, for example {@code john.doe@texasComp.com}
     * @return the matching records of the response's {@code value} array, in response order; an empty list when the
     *     response has no body, no {@code value} array, or no matching record. The list is unmodifiable
     * @throws NullPointerException             if {@code email} is {@code null}
     * @throws UpstreamUnavailableException     when the Dataverse or token request cannot connect or times out
     * @throws UpstreamAuthenticationException  when the token request is rejected, or Dataverse answers
     *                                          {@code 401} to both the request and its re-issue
     * @throws UpstreamRateLimitException       when Dataverse or the token endpoint answers {@code 429}
     * @throws WebClientResponseException       for any other non-2xx status, unchanged
     */
    public List<Map<String, Object>> findContactsByEmail(String email) {
        Objects.requireNonNull(email, "email");
        String filter = "emailaddress1 eq '" + email.replace("'", "''") + "'";
        Map<String, Object> body = execute("findContactsByEmail", () -> webClient.get()
                .uri(builder -> builder.path(CONTACTS_PATH)
                        .queryParam("$select", CONTACT_COLUMNS)
                        .queryParam("$filter", "{filter}")
                        .build(filter))
                .attributes(ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(REGISTRATION_ID))
                .retrieve()
                .bodyToMono(JSON_OBJECT)
                .block());
        return contacts(body);
    }

    /**
     * Deletes the Dataverse contact with the given id.
     *
     * <p>Sends {@code DELETE /api/data/v9.2/contacts(<id>)}, the id passed as an encoded URI variable. Any 2xx
     * status is success.
     *
     * @param id the {@code contactid} of the contact, for example {@code 00000000-0000-0000-0000-000000000001}
     * @throws NullPointerException             if {@code id} is {@code null}
     * @throws UpstreamUnavailableException     when the Dataverse or token request cannot connect or times out
     * @throws UpstreamAuthenticationException  when the token request is rejected, or Dataverse answers
     *                                          {@code 401} to both the request and its re-issue
     * @throws UpstreamRateLimitException       when Dataverse or the token endpoint answers {@code 429}
     * @throws WebClientResponseException       for any other non-2xx status, such as {@code 404}, unchanged
     */
    public void deleteContact(String id) {
        Objects.requireNonNull(id, "id");
        execute("deleteContact", () -> webClient.delete()
                .uri(CONTACTS_PATH + "({id})", id)
                .attributes(ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(REGISTRATION_ID))
                .retrieve()
                .toBodilessEntity()
                .block());
    }

    /**
     * Classifies upstream failures; removes the cached token and re-issues the call once after a 401 (D-020).
     *
     * <p>Runs {@code call} and returns its result. A {@link RuntimeException} thrown by {@code call} is classified
     * by the throwables of its cause chain, by these rules in this order:
     * <ol>
     *   <li>a {@link WebClientRequestException}, Netty {@link ReadTimeoutException}, {@link SocketTimeoutException}
     *       or {@link ConnectException}, on the Dataverse or the token request: {@link UpstreamUnavailableException},
     *       and {@code call} is not run again;</li>
     *   <li>a {@link WebClientResponseException.Unauthorized}: the token stored for {@value #REGISTRATION_ID} and
     *       {@value #PRINCIPAL_NAME} is removed and {@code call} runs exactly once more. That run's result is
     *       returned. Its failure is classified by rules 1, 3, 4 and 5, and a second {@code 401} becomes
     *       {@link UpstreamAuthenticationException};</li>
     *   <li>an {@link OAuth2AuthorizationException}, the token request rejected:
     *       {@link UpstreamAuthenticationException};</li>
     *   <li>a {@link WebClientResponseException.TooManyRequests}, on the Dataverse or the token request:
     *       {@link UpstreamRateLimitException} carrying the {@code Retry-After} header value exactly as received,
     *       or {@code null} when the response has none;</li>
     *   <li>anything else, for example a {@code 400}, {@code 404} or {@code 500} response: the thrown exception
     *       itself, rethrown unchanged.</li>
     * </ol>
     * Every {@code Upstream*Exception} thrown here has a message of the form
     * {@code Dataverse <operation> failed: <condition>}, the upstream system {@value #UPSTREAM_SYSTEM}, and the
     * classified failure as its cause.
     *
     * @param operation the operation name used in exception messages, for example {@code createContact}
     * @param call      the Dataverse request, ending in a blocking read of its response
     * @param <T>       the result type of {@code call}
     * @return the result of the first run of {@code call} that completes normally
     * @throws NullPointerException             if {@code operation} or {@code call} is {@code null}
     * @throws UpstreamUnavailableException     by rule 1
     * @throws UpstreamAuthenticationException  by rules 2 and 3
     * @throws UpstreamRateLimitException       by rule 4
     * @throws RuntimeException                 the failure of {@code call}, unchanged, by rule 5
     */
    public <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        try {
            return call.get();
        } catch (RuntimeException failure) {
            List<Throwable> chain = causeChain(failure);
            if (isUnauthorizedResponse(chain)) {
                return retryOnceAfterReauthentication(operation, call);
            }
            throw classify(operation, failure, chain);
        }
    }

    /**
     * Removes the stored token of {@value #REGISTRATION_ID} and {@value #PRINCIPAL_NAME}, then runs {@code call} a
     * second and last time; the only retry path of this client (D-020).
     *
     * <p>The removal makes the exchange filter request a new client-credentials token for the second run. A
     * failure of the second run is a {@code 401} again, giving {@link UpstreamAuthenticationException}, or is
     * classified by {@link #classify(String, RuntimeException, List)}.
     *
     * @param operation the operation name used in exception messages
     * @param call      the Dataverse request answered with {@code 401} on its first run
     * @param <T>       the result type of {@code call}
     * @return the result of the second run
     */
    private <T> T retryOnceAfterReauthentication(String operation, Supplier<T> call) {
        log.warn("Dataverse {} answered HTTP 401; removing the stored access token and re-issuing the request once",
                operation);
        authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME).block();
        try {
            return call.get();
        } catch (RuntimeException secondFailure) {
            List<Throwable> chain = causeChain(secondFailure);
            if (isUnauthorizedResponse(chain)) {
                throw new UpstreamAuthenticationException(
                        message(operation, "HTTP 401 after re-authentication"), UPSTREAM_SYSTEM, secondFailure);
            }
            throw classify(operation, secondFailure, chain);
        }
    }

    /**
     * Applies rules 1, 3, 4 and 5 of {@link #execute(String, Supplier)} to a failure.
     *
     * @param operation the operation name used in exception messages
     * @param failure   the exception thrown by the call
     * @param chain     the cause chain of {@code failure}, {@code failure} first
     * @return the exception to throw: an {@code Upstream*Exception} with {@code failure} as its cause, or
     *     {@code failure} itself
     */
    private static RuntimeException classify(String operation, RuntimeException failure, List<Throwable> chain) {
        if (isNetworkFailure(chain)) {
            return new UpstreamUnavailableException(
                    message(operation, "connection or timeout"), UPSTREAM_SYSTEM, failure);
        }
        OAuth2AuthorizationException tokenFailure = first(chain, OAuth2AuthorizationException.class);
        if (tokenFailure != null) {
            return new UpstreamAuthenticationException(
                    message(operation, "token request rejected (" + tokenFailure.getError().getErrorCode() + ")"),
                    UPSTREAM_SYSTEM, failure);
        }
        WebClientResponseException.TooManyRequests tooMany =
                first(chain, WebClientResponseException.TooManyRequests.class);
        if (tooMany != null) {
            return new UpstreamRateLimitException(message(operation, "rate limited"), UPSTREAM_SYSTEM,
                    tooMany.getHeaders().getFirst("Retry-After"), failure);
        }
        return failure;
    }

    /**
     * Returns whether a cause chain holds a Dataverse {@code 401} response and no connection or timeout failure.
     *
     * @param chain the cause chain, outermost exception first
     * @return {@code true} when rule 2 of {@link #execute(String, Supplier)} applies
     */
    private static boolean isUnauthorizedResponse(List<Throwable> chain) {
        return !isNetworkFailure(chain) && first(chain, WebClientResponseException.Unauthorized.class) != null;
    }

    /**
     * Returns whether a cause chain holds a connection or timeout failure: a {@link WebClientRequestException}, a
     * Netty {@link ReadTimeoutException}, a {@link SocketTimeoutException} or a {@link ConnectException}, which
     * includes Netty's {@code ConnectTimeoutException}.
     *
     * @param chain the cause chain, outermost exception first
     * @return {@code true} when rule 1 of {@link #execute(String, Supplier)} applies
     */
    private static boolean isNetworkFailure(List<Throwable> chain) {
        for (Throwable throwable : chain) {
            if (throwable instanceof WebClientRequestException
                    || throwable instanceof ReadTimeoutException
                    || throwable instanceof SocketTimeoutException
                    || throwable instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first throwable of a cause chain that is an instance of the given type.
     *
     * @param chain the cause chain, outermost exception first
     * @param type  the throwable type to look for
     * @param <E>   the throwable type
     * @return the first matching throwable, or {@code null} when none matches
     */
    private static <E extends Throwable> E first(List<Throwable> chain, Class<E> type) {
        for (Throwable throwable : chain) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
        }
        return null;
    }

    /**
     * Returns a throwable followed by its causes, outermost first, each throwable listed once; the walk stops at a
     * cause already listed.
     *
     * @param failure the outermost throwable
     * @return the cause chain of {@code failure}, {@code failure} first
     */
    private static List<Throwable> causeChain(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /**
     * Returns the exception message {@code Dataverse <operation> failed: <condition>}.
     *
     * @param operation the failed operation, for example {@code createContact}
     * @param condition the classified condition, for example {@code connection or timeout}
     * @return the message
     */
    private static String message(String operation, String condition) {
        return "Dataverse " + operation + " failed: " + condition;
    }

    /**
     * Returns the record id inside {@code contacts(...)} of an {@code OData-EntityId} header value.
     *
     * @param entityId the header value, for example
     *                 {@code https://org.crm.dynamics.com/api/data/v9.2/contacts(00000000-0000-0000-0000-000000000001)};
     *                 may be {@code null}
     * @return the id, or {@code null} when {@code entityId} is {@code null} or holds no {@code contacts(<id>)}
     */
    private static String contactId(String entityId) {
        if (entityId == null) {
            return null;
        }
        Matcher matcher = CONTACT_ID.matcher(entityId);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Returns the records of an OData collection response.
     *
     * @param body the parsed response body; may be {@code null}
     * @return the JSON objects of the {@code value} array, in array order, as an unmodifiable list; an empty list
     *     when {@code body} is {@code null} or its {@code value} member is not an array. Array elements that are
     *     not JSON objects are not returned
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contacts(Map<String, Object> body) {
        if (body == null || !(body.get(VALUE_MEMBER) instanceof List<?> records)) {
            return List.of();
        }
        List<Map<String, Object>> contacts = new ArrayList<>(records.size());
        for (Object element : records) {
            if (element instanceof Map<?, ?> contact) {
                contacts.add((Map<String, Object>) contact);
            }
        }
        return Collections.unmodifiableList(contacts);
    }
}

