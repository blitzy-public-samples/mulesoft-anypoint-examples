package com.mulesoft.examples.mule_component_bindings.config;

import com.mulesoft.examples.mule_component_bindings.controller.RawBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 404 with the body {@code No listener for endpoint: <path>} and no {@code Content-Type}
 * for any path requested on a port that does not own it (D-011, D-066).
 *
 * <p>Source: {@code HTTP_Listener_Configuration} on {@code localhost:8081}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:31] serves the listener path
 * {@code /} [:33] of {@code mule-component-bindingsFlow1}; {@code HTTP_Listener_Configuration2} on
 * {@code localhost:8180} [:40] serves the listener path {@code /api/*} [:42] of
 * {@code StockServiceFlow}.
 *
 * <p>Ownership, keyed on {@link HttpServletRequest#getLocalPort()} and the undecoded
 * {@link HttpServletRequest#getRequestURI()} without its query string:
 * <ul>
 *   <li>the listener on {@code listener.http-listener-configuration2.port} (default {@code 8180})
 *       owns every path that starts with {@code /api/};</li>
 *   <li>every other local port is the primary listener, which owns exactly {@code /}.</li>
 * </ul>
 *
 * <p>Example exchanges with the default ports:
 * <pre>
 * GET http://localhost:8081/                                          → rest of the filter chain
 * GET http://localhost:8180/api/stockStats?stock=AAPL&amp;date=2012-11-28 → rest of the filter chain
 * GET http://localhost:8180/api/unknown                               → rest of the filter chain
 * GET http://localhost:8081/api/stockStats → 404 Not Found "No listener for endpoint: /api/stockStats"
 * GET http://localhost:8180/               → 404 Not Found "No listener for endpoint: /"
 * GET http://localhost:8180/api            → 404 Not Found "No listener for endpoint: /api"
 * GET http://localhost:8081/favicon.ico    → 404 Not Found "No listener for endpoint: /favicon.ico"
 * </pre>
 *
 * <p>A rejected request gets status 404, {@code Content-Length} and the UTF-8 bytes of the body,
 * written through {@link RawBody#write(HttpServletResponse, int, byte[])}; this filter sets no
 * header and no reason phrase, and Undertow sends its {@code Not Found} reason phrase (D-010). The
 * rest of the filter chain does not run for a rejected request. The rule applies to every HTTP
 * method. A request without a request URI is owned by neither listener.
 *
 * <p>Spring Boot registers this bean for {@code /*} with the order
 * {@link Ordered#HIGHEST_PRECEDENCE}. Error and async dispatches pass through unfiltered, the
 * {@link OncePerRequestFilter} defaults. Each rejected request writes one DEBUG log entry with the
 * method, the path and the local port, ISO control characters escaped. The filter holds no
 * per-request state and is safe for concurrent requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortPathGuardFilter extends OncePerRequestFilter {

    /** Start of the body of a rejected request; the request URI follows it (D-011). */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /**
     * Prefix of every path owned by the listener on {@code secondaryPort}, the listener path
     * {@code /api/*}.
     */
    private static final String SECONDARY_PATH_PREFIX = "/api/";

    /** The only path owned by the primary listener, the listener path {@code /}. */
    private static final String PRIMARY_PATH = "/";

    /** Logger of rejected requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(PortPathGuardFilter.class);

    /** Local port of the listener that owns {@code /api/*}. */
    private final int secondaryPort;

    /**
     * Creates the guard for the listener on {@code listener.http-listener-configuration2.port}
     * (D-011).
     *
     * <p>A request whose local port equals {@code secondaryPort} is checked against {@code /api/*};
     * a request on any other local port is checked against {@code /}.
     *
     * @param secondaryPort the value of {@code listener.http-listener-configuration2.port}, owner
     *                      of {@code /api/*}
     */
    public PortPathGuardFilter(@Value("${listener.http-listener-configuration2.port}") int secondaryPort) {
        super();
        this.secondaryPort = secondaryPort;
    }

    /**
     * Passes a request for a path its local port owns to the rest of the chain, and answers every
     * other request with the no-listener 404 without calling the chain (D-011, D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the path is {@link HttpServletRequest#getRequestURI()}, undecoded and without the query
     *       string;</li>
     *   <li>the request is on the secondary listener when {@link HttpServletRequest#getLocalPort()}
     *       equals {@code secondaryPort}, otherwise on the primary listener;</li>
     *   <li>the secondary listener owns a path that starts with {@code /api/}; the primary listener
     *       owns the path {@code /}; a {@code null} path is owned by neither;</li>
     *   <li>an owned path goes to {@code chain.doFilter(request, response)};</li>
     *   <li>any other path gets status 404 with the UTF-8 body
     *       {@code No listener for endpoint: <path>} through {@link RawBody#write}, and the chain is
     *       not called.</li>
     * </ol>
     *
     * @param request  the incoming request
     * @param response the response, written only for a path its local port does not own
     * @param chain    the rest of the filter chain
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if a later filter or the servlet fails to write, or writing the 404
     *                          fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean secondary = request.getLocalPort() == secondaryPort;
        // A null request URI is owned by neither listener; every non-null path follows the two
        // ownership rules unchanged.
        boolean owned = secondary
                ? path != null && path.startsWith(SECONDARY_PATH_PREFIX)
                : PRIMARY_PATH.equals(path);
        if (owned) {
            chain.doFilter(request, response);
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Answering 404 for {} {} on port {}: no listener owns the path on this port",
                    escapeControlCharacters(request.getMethod()), escapeControlCharacters(path),
                    request.getLocalPort());
        }
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                (NO_LISTENER_PREFIX + path).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code value} with every ISO control character written as an escape sequence:
     * {@code \r}, {@code \n} and {@code \t} by name, any other as {@code \}{@code u} and four
     * lower-case hexadecimal digits, for example {@code \}{@code u001b}. A value without control
     * characters is returned unchanged.
     *
     * @param value the text to place in a log entry; may be {@code null}
     * @return the escaped text, or {@code null} for a {@code null} value
     */
    private static String escapeControlCharacters(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                if (escaped != null) {
                    escaped.append(c);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            switch (c) {
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(String.format("\\u%04x", (int) c));
            }
        }
        return escaped == null ? value : escaped.toString();
    }
}
