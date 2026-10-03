package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.ListenerProperties.Listener;
import com.mulesoft.examples.foreach_processing_and_choice_routing.controller.RawBody;
import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.ReasonPhrase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps every path on the port of the HTTP listener that owns it (D-011).
 *
 * <p>Sources: the seven {@code http:listener-config} elements of
 * {@code foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:4,7-12} and their flow
 * listeners, each with {@code path="/*"} [same file:21,139,147,157,168,178,188]. A port's owned paths are its
 * listener's {@code base-path} and every path below it:
 * <ul>
 *   <li>listener 1 ({@code server.port}, default {@code 11081}) has no base path and owns every path; every
 *       request on it passes;</li>
 *   <li>listener 2 (default {@code 18080}) owns {@code /mule/TheCreditAgencyService} and
 *       {@code /mule/TheCreditAgencyService/…};</li>
 *   <li>listeners 3 … 7 (defaults {@code 10080}, {@code 20080}, {@code 30080}, {@code 40080}, {@code 50080}) own
 *       {@code /mule/TheBank1} … {@code /mule/TheBank5} and every path below each.</li>
 * </ul>
 * The ports and base paths are read from {@link ListenerProperties#additionalOnPort(int)}. A request whose local
 * port is none of listeners 2 … 7 belongs to listener 1.
 *
 * <p>On listeners 2 … 7 the raw, undecoded {@link HttpServletRequest#getRequestURI()} is owned when it equals the
 * base path or starts with the base path followed by {@code /}. The query string takes no part in the match, and
 * the HTTP method takes none either: every method is treated alike. For listener 3 (base path
 * {@code /mule/TheBank1}):
 * <ul>
 *   <li>{@code /mule/TheBank1}, {@code /mule/TheBank1/} and {@code /mule/TheBank1/a/b?wsdl} pass to the rest of
 *       the filter chain unchanged;</li>
 *   <li>{@code /mule/TheBank10}, {@code /mule/TheBank2}, {@code /mule/TheCreditAgencyService}, {@code /} and
 *       {@code /other} are answered here.</li>
 * </ul>
 *
 * <p>The answer to a path its port does not own is the HTTP listener's no-listener response: status {@code 404},
 * the reason phrase {@code No listener for endpoint: <uri>} on the status line (D-010) and the body
 * {@code Resource not found.}, with no {@code Content-Type} (D-066). {@code <uri>} is the raw request URI followed
 * by {@code ?} and the raw query when the query is not empty; each character outside printable ASCII
 * ({@code 0x20} … {@code 0x7E}) becomes {@code ?} in the phrase. The body is written through
 * {@link RawBody#write(HttpServletResponse, int, byte[])} and flushed before the response completes, which leaves
 * {@code Content-Length} unset: Undertow sends it with {@code Transfer-Encoding: chunked} on a persistent HTTP/1.1
 * connection and closes any other connection after it. The rest of the filter chain is not called for such a
 * request. For example, a keep-alive {@code GET /other?x=1} on port {@code 18080} is answered:
 * <pre>
 * HTTP/1.1 404 No listener for endpoint: /other?x=1
 * Connection: keep-alive
 * Transfer-Encoding: chunked
 * Date: …
 *
 * 13
 * Resource not found.
 * 0
 * </pre>
 *
 * <p>Spring Boot registers this {@code @Component} as a servlet filter for {@code /*} at
 * {@link Ordered#HIGHEST_PRECEDENCE}. Inherited from {@link OncePerRequestFilter}: the filter runs once per request
 * and is skipped for async and error dispatches. It holds no mutable state and is safe for concurrent requests.
 * A rejected request is logged at DEBUG with its local port, method and printable URI.
 */
@Component
public class PortPathGuardFilter extends OncePerRequestFilter implements Ordered {

    /** Reason-phrase format of the no-listener answer; {@code %s} is the request URI with its query. */
    private static final String NO_LISTENER_REASON_FORMAT = "No listener for endpoint: %s";

    /** Body bytes of the no-listener answer, {@code Resource not found.} in UTF-8 (ASCII only). */
    private static final byte[] NO_LISTENER_BODY = "Resource not found.".getBytes(StandardCharsets.UTF_8);

    /** Path separator appended to a base path to form the prefix of its child paths. */
    private static final String SLASH = "/";

    /** Separator between the request URI and its query in the reason phrase. */
    private static final String QUERY_SEPARATOR = "?";

    /** First printable ASCII character. */
    private static final char FIRST_PRINTABLE = 0x20;

    /** Last printable ASCII character. */
    private static final char LAST_PRINTABLE = 0x7E;

    /** Replacement for a character outside printable ASCII in the reason phrase and the log. */
    private static final char REPLACEMENT = '?';

    /** Listener addresses; listeners 2 … 7 are the ports this filter guards. */
    private final ListenerProperties listeners;

    /** Writes the reason phrase of the current response status line. */
    private final Consumer<String> reasonPhrase;

    /**
     * Creates the guard over the bound listener addresses; the reason phrase is written through
     * {@link ReasonPhrase#set(String)} on the current Undertow exchange (D-010).
     *
     * @param listeners the bound {@code listener.http-listener-configuration-<n>} entries
     * @throws NullPointerException when {@code listeners} is {@code null}
     */
    @Autowired
    public PortPathGuardFilter(ListenerProperties listeners) {
        this(listeners, ReasonPhrase::set);
    }

    /**
     * Creates the guard with the given reason-phrase writer, for example a recorder in a test that runs without
     * an Undertow exchange.
     *
     * @param listeners    the bound {@code listener.http-listener-configuration-<n>} entries
     * @param reasonPhrase receives the reason phrase of each no-listener answer, before the response is committed
     * @throws NullPointerException when {@code listeners} or {@code reasonPhrase} is {@code null}
     */
    PortPathGuardFilter(ListenerProperties listeners, Consumer<String> reasonPhrase) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.reasonPhrase = Objects.requireNonNull(reasonPhrase, "reasonPhrase");
    }

    /**
     * Returns the filter order.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Passes a request on listener 1, or on a path its port owns, to the rest of the chain unchanged; answers
     * every other request with the no-listener response (D-011).
     *
     * @param request     the incoming request; its local port and raw request URI decide, and its raw query is
     *                    echoed in the reason phrase
     * @param response    the response, written here only for a path its port does not own
     * @param filterChain the rest of the chain, called only for a passing request
     * @throws ServletException      from the rest of the chain
     * @throws IOException           from the rest of the chain, or while writing the no-listener answer
     * @throws IllegalStateException from {@link ListenerProperties#additionalOnPort(int)} when one of the entries
     *                               {@code http-listener-configuration-2} … {@code -7} is not bound, or from
     *                               {@link ReasonPhrase#set(String)} when no Undertow request is active
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<Listener> owner = listeners.additionalOnPort(request.getLocalPort());
        String path = request.getRequestURI();
        if (owner.isEmpty() || owns(owner.get().basePath(), path)) {
            filterChain.doFilter(request, response);
            return;
        }
        String uri = printable(uri(path, request.getQueryString()));
        if (logger.isDebugEnabled()) {
            logger.debug("No listener on port " + request.getLocalPort() + " for " + printable(request.getMethod())
                    + " " + uri);
        }
        // No-listener answer of the Mule 3.8.0 HTTP listener: the diagnostic text is the reason phrase and the
        // body is "Resource not found.", flushed with no Content-Length (D-011, D-010, D-066).
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, NO_LISTENER_BODY);
        reasonPhrase.accept(String.format(NO_LISTENER_REASON_FORMAT, uri));
        response.flushBuffer();
    }

    /**
     * Tells whether a listener with base path {@code basePath} owns the raw request path {@code path}.
     *
     * @param basePath the listener's base path, {@code ""} for a listener without one
     * @param path     the raw request URI without its query, or {@code null}
     * @return {@code true} when {@code path} equals {@code basePath} or starts with {@code basePath + "/"};
     *         {@code false} for a {@code null} path
     */
    static boolean owns(String basePath, String path) {
        return path != null && (path.equals(basePath) || path.startsWith(basePath + SLASH));
    }

    /**
     * Builds the request URI echoed in the reason phrase.
     *
     * @param path  the raw request URI without its query, or {@code null}
     * @param query the raw query string, or {@code null} when the request has none
     * @return {@code path} ({@code ""} for {@code null}), followed by {@code ?query} when {@code query} is not
     *         empty
     */
    static String uri(String path, String query) {
        String base = (path == null) ? "" : path;
        return (query == null || query.isEmpty()) ? base : base + QUERY_SEPARATOR + query;
    }

    /**
     * Replaces each character outside printable ASCII with {@code ?}.
     *
     * @param text the text, or {@code null}
     * @return the printable text, {@code ""} for {@code null}
     */
    static String printable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder printable = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < FIRST_PRINTABLE || c > LAST_PRINTABLE) {
                if (printable == null) {
                    printable = new StringBuilder(text);
                }
                printable.setCharAt(i, REPLACEMENT);
            }
        }
        return (printable == null) ? text : printable.toString();
    }
}
