package com.mulesoft.examples.filtering_a_message.service;

import com.mulesoft.examples.filtering_a_message.mapper.DiscountRequestMapper;
import com.mulesoft.examples.filtering_a_message.model.InboundHttpRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the body of flow {@code filteringFlow1} [filtering-a-message/src/main/app/filtering.xml:4-19]
 * for a request that {@code DiscountController} has bound into an {@link InboundHttpRequest}.
 *
 * <p>{@link #filteringFlow1(InboundHttpRequest)} runs the flow's steps in their XML order:
 * <ol>
 *   <li>filtering.xml:9-12 and-filter: {@link #payloadTypeAccepted(InboundHttpRequest)} (:10), then
 *       {@link #methodAccepted(InboundHttpRequest)} (:11), the second evaluated only when the first
 *       accepts;</li>
 *   <li>filtering.xml:14 JSON to {@code java.util.HashMap}: {@link DiscountRequestMapper#toMap(byte[])};</li>
 *   <li>filtering.xml:15 custom filter: {@link FreeMembershipDiscountFilter#accept(Map)};</li>
 *   <li>filtering.xml:16 set payload: the literal {@link #GRANTED};</li>
 *   <li>filtering.xml:17 logger: the payload text logged at INFO, with no prefix.</li>
 * </ol>
 *
 * <p>A rejecting filter ends the flow with {@link Optional#empty()}, which the controller answers
 * with 200 and an empty body; a granted request yields the {@link #GRANTED} text, which the
 * controller writes with no {@code Content-Type} (D-066).
 *
 * <p>The flow declares no exception strategy, and this class catches nothing: the
 * {@link java.io.UncheckedIOException} of the mapper and the {@link NullPointerException} or
 * {@link NumberFormatException} of the custom filter reach the caller unchanged, where
 * {@code GlobalExceptionHandler.unexpected} answers the default-strategy 500.
 *
 * <p>Usage:
 * <pre>{@code
 * DiscountService service =
 *         new DiscountService(new DiscountRequestMapper(), new FreeMembershipDiscountFilter());
 * byte[] body = "{\"purchases\": 2000, \"months\": 12, \"membership\": \"free\"}"
 *         .getBytes(StandardCharsets.UTF_8);
 * Optional<String> reply = service.filteringFlow1(
 *         new InboundHttpRequest("POST", body.length, null, "application/json", body));
 * // reply is Optional[the discount was granted.]; 100 purchases over 6 months give Optional.empty
 * }</pre>
 *
 * <p>The bean holds only its two collaborators, both stateless, and serves concurrent requests
 * without synchronization.
 */
@Service
public class DiscountService {

    /** Logger of the INFO logger step [filtering-a-message/src/main/app/filtering.xml:17]. */
    private static final Logger log = LoggerFactory.getLogger(DiscountService.class);

    /** The literal of the set-payload step [filtering-a-message/src/main/app/filtering.xml:16]. */
    static final String GRANTED = "the discount was granted.";

    /** {@code Transfer-Encoding} value of a chunked body, compared ignoring case. */
    private static final String CHUNKED = "chunked";

    /** Prefix of every multipart {@code Content-Type} base type, in lower case. */
    private static final String MULTIPART_PREFIX = "multipart/";

    /** {@code Content-Type} base type of an HTML form body, in lower case. */
    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    /** Value of the inbound {@code http.method} property that the method filter accepts, ignoring case. */
    private static final String POST = "post";

    /** Converts the request body into the map read by the custom filter (filtering.xml:14). */
    private final DiscountRequestMapper mapper;

    /** Decides whether the parsed request is granted the discount (filtering.xml:15). */
    private final FreeMembershipDiscountFilter filter;

    /**
     * Creates the service with its two flow steps.
     *
     * @param mapper the JSON-to-map step of filtering.xml:14
     * @param filter the custom filter step of filtering.xml:15
     */
    public DiscountService(DiscountRequestMapper mapper, FreeMembershipDiscountFilter filter) {
        this.mapper = mapper;
        this.filter = filter;
    }

    /**
     * Runs flow {@code filteringFlow1} [filtering-a-message/src/main/app/filtering.xml:4-19] for one
     * request.
     *
     * <p>When the and-filter of filtering.xml:9-12 rejects the request, the method returns
     * {@link Optional#empty()} without reading the body. Otherwise it parses the body into a map
     * (filtering.xml:14) and passes it to the custom filter (filtering.xml:15); a rejection returns
     * {@link Optional#empty()}. An accepted request sets the payload to {@link #GRANTED}
     * (filtering.xml:16), logs it at INFO (filtering.xml:17) and returns it.
     *
     * @param request the bound HTTP request
     * @return the granted payload text, or {@link Optional#empty()} when a filter rejects the request
     * @throws java.io.UncheckedIOException when the body is not a JSON object
     * @throws NullPointerException when {@code request} is {@code null}, when the body is the JSON
     *     literal {@code null}, or when the parsed map lacks {@code membership}, {@code months} or
     *     {@code purchases}, or holds {@code null} for one of them
     * @throws NumberFormatException when {@code months} or {@code purchases} is not an {@code int}
     *     literal
     */
    public Optional<String> filteringFlow1(InboundHttpRequest request) {
        // filtering.xml:9-12 and-filter, left to right, stopping at the first rejecting filter
        if (!(payloadTypeAccepted(request) && methodAccepted(request))) {
            return Optional.empty();
        }
        // filtering.xml:14 JSON to HashMap
        Map<String, Object> order = mapper.toMap(request.body());
        // filtering.xml:15 custom filter
        if (!filter.accept(order)) {
            return Optional.empty();
        }
        // filtering.xml:16 set payload
        String payload = GRANTED;
        // filtering.xml:17 logger, message #[payload] at INFO
        log.info(payload);
        return Optional.of(payload);
    }

    /**
     * Payload type filter of filtering.xml:10, whose expected type is the Grizzly
     * {@code org.glassfish.grizzly.utils.BufferInputStream} body stream.
     *
     * <p>Returns {@code true} only when all three conditions hold:
     * <ul>
     *   <li>the body holds at least one byte;</li>
     *   <li>{@code Transfer-Encoding} is absent, or its trimmed value is not {@code chunked} in any
     *       letter case;</li>
     *   <li>{@code Content-Type} is absent, or its base type (the text before the first {@code ;},
     *       trimmed and lower-cased in {@link Locale#ROOT}) neither starts with {@code multipart/}
     *       nor equals {@code application/x-www-form-urlencoded}.</li>
     * </ul>
     * The Tier 2A fixtures pin this reading once they are captured (D-023).
     *
     * @param request the bound HTTP request
     * @return {@code true} when the request passes the payload type filter
     */
    boolean payloadTypeAccepted(InboundHttpRequest request) {
        byte[] body = request.body();
        if (body == null || body.length == 0) {
            return false;
        }
        String transferEncoding = request.transferEncoding();
        if (transferEncoding != null && CHUNKED.equalsIgnoreCase(transferEncoding.trim())) {
            return false;
        }
        String contentType = request.contentType();
        if (contentType == null) {
            return true;
        }
        int separator = contentType.indexOf(';');
        String baseType = (separator < 0 ? contentType : contentType.substring(0, separator))
                .trim()
                .toLowerCase(Locale.ROOT);
        return !baseType.startsWith(MULTIPART_PREFIX) && !FORM_URLENCODED.equals(baseType);
    }

    /**
     * Message property filter of filtering.xml:11: accepts the request when its method equals
     * {@code post} ignoring case, the {@code http.method=post} pattern with
     * {@code caseSensitive="false"}.
     *
     * <p>Requests reach this flow through the POST-only listener of filtering.xml:5 (D-043).
     *
     * @param request the bound HTTP request
     * @return {@code true} for {@code POST} in any letter case, {@code false} for any other method
     *     or a {@code null} method
     */
    boolean methodAccepted(InboundHttpRequest request) {
        // method branch kept (D-043)
        return POST.equalsIgnoreCase(request.method());
    }
}
