package com.mulesoft.examples.soap_webservice_security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 404 for a path requested on a port that does not own it (D-011).
 *
 * <p>Sources: {@code HTTP_Listener_Configuration1} on {@code 0.0.0.0:63081} with base path {@code services}
 * [soap-webservice-security/src/main/app/mule-config.xml:6] serves the listener paths {@code unsecure},
 * {@code username}, {@code signed}, {@code encrypted}, {@code saml} and {@code signedsaml} [:9,16,29,43,57,72];
 * {@code HTTP_Listener_Configuration} on {@code 0.0.0.0:63080}
 * [soap-webservice-security/src/main/app/service-clients.xml:6] serves the listener path {@code client} [:9].
 * Both listeners share one servlet deployment: Spring Boot's primary Undertow listener on {@code server.port} and
 * the client listener that {@link AdditionalPortsConfig} adds.
 *
 * <p>Ownership, keyed on {@link HttpServletRequest#getLocalPort()} and the context-relative path, which is the
 * undecoded {@link HttpServletRequest#getRequestURI()} without the context path and without the query string:
 * <ul>
 *   <li>the local port {@code listener.http-listener-configuration.port} (default {@code 63080}) is the client
 *       listener, which owns exactly {@code /client};</li>
 *   <li>every other local port is the services listener ({@code server.port}, bound to
 *       {@code listener.http-listener-configuration1.port}, default {@code 63081}, or a random test port), which
 *       owns exactly the six paths {@code /<base-path>/unsecure}, {@code /<base-path>/username},
 *       {@code /<base-path>/signed}, {@code /<base-path>/encrypted}, {@code /<base-path>/saml} and
 *       {@code /<base-path>/signedsaml}, where {@code <base-path>} is
 *       {@code listener.http-listener-configuration1.base-path} (default {@code services}).</li>
 * </ul>
 * Paths are compared as exact, case-sensitive strings: a trailing slash, a further segment, a path parameter or a
 * percent-encoded variant of an owned path is not owned. The HTTP method and the query string take no part in the
 * decision, so {@code GET /services/username?wsdl} reaches {@code WsdlQueryFilter}, which runs after this filter.
 * A request without a request URI, or whose URI does not start with the context path, is owned by neither
 * listener.
 *
 * <p>Example exchanges with the default ports:
 * <pre>
 * POST http://localhost:63081/services/unsecure       → rest of the filter chain
 * GET  http://localhost:63081/services/signedsaml?wsdl → rest of the filter chain
 * GET  http://localhost:63080/client?clientType=unsecure&amp;name=Mule → rest of the filter chain
 * GET  http://localhost:63081/client                  → 404, no body
 * POST http://localhost:63080/services/unsecure       → 404, no body
 * GET  http://localhost:63081/services/Greeter.wsdl   → 404, no body
 * GET  http://localhost:63081/services/unknown        → 404, no body
 * GET  http://localhost:63081/services/unsecure/      → 404, no body
 * </pre>
 *
 * <p>A request for a path its local port does not own gets status 404 and {@code Content-Length: 0}, with no
 * body, no {@code Content-Type} and Undertow's standard {@code Not Found} reason phrase (D-010). The rest of the
 * filter chain does not run for it, and no error dispatch takes place. Each such request writes one DEBUG log
 * entry with the method, the path and the local port, ISO control characters escaped.
 *
 * <p>Spring Boot registers this bean for {@code /*} and the {@code REQUEST} dispatcher type with the order
 * {@link #ORDER}. Error and async dispatches pass through unfiltered, the {@link OncePerRequestFilter} defaults.
 * The filter holds no per-request state and is safe for concurrent requests.
 */
@Component
public class PortPathGuardFilter extends OncePerRequestFilter implements Ordered {

    /** Filter order, lower than {@code WsdlQueryFilter.ORDER}: this filter runs before {@code WsdlQueryFilter}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /** The only path owned by the client listener, the listener path {@code client}. */
    private static final String CLIENT_PATH = "/client";

    /** Listener paths of the six Greeter service flows under the services listener's base path. */
    private static final List<String> SERVICE_PATH_NAMES =
            List.of("unsecure", "username", "signed", "encrypted", "saml", "signedsaml");

    /** Logger of rejected requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(PortPathGuardFilter.class);

    /** Local port of the client listener, {@code listener.http-listener-configuration.port}. */
    private final int clientPort;

    /** The six context-relative paths owned by the services listener; immutable. */
    private final Set<String> servicePaths;

    /**
     * Creates the guard for the client listener port and the services listener base path (D-011).
     *
     * <p>The owned service paths are {@code "/" + basePath + "/" + name} for each of the six listener path names.
     * A {@code clientPort} of {@code 0} equals no local port, and every request is then checked against the
     * service paths.
     *
     * @param clientPort the value of {@code listener.http-listener-configuration.port}, owner of {@code /client}
     * @param basePath   the value of {@code listener.http-listener-configuration1.base-path}, default
     *                   {@code services}
     * @throws NullPointerException if {@code basePath} is {@code null}
     */
    public PortPathGuardFilter(
            @Value("${listener.http-listener-configuration.port}") int clientPort,
            @Value("${listener.http-listener-configuration1.base-path:services}") String basePath) {
        super();
        Objects.requireNonNull(basePath, "listener.http-listener-configuration1.base-path must not be null");
        this.clientPort = clientPort;
        Set<String> paths = new LinkedHashSet<>();
        for (String name : SERVICE_PATH_NAMES) {
            paths.add("/" + basePath + "/" + name);
        }
        this.servicePaths = Set.copyOf(paths);
    }

    /**
     * Passes a request for a path its local port owns to the rest of the chain, and answers every other request
     * with an empty 404 without calling the chain (D-011).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the path is {@link HttpServletRequest#getRequestURI()} without {@link HttpServletRequest#getContextPath()};
     *       the query string is not part of it;</li>
     *   <li>the request is on the client listener when {@link HttpServletRequest#getLocalPort()} equals
     *       {@code clientPort}, otherwise on the services listener;</li>
     *   <li>the client listener owns {@code /client}; the services listener owns the six service paths; a
     *       {@code null} path is owned by neither;</li>
     *   <li>an owned path goes to {@code filterChain.doFilter(request, response)};</li>
     *   <li>any other path gets {@code setStatus(404)} and {@code setContentLength(0)}, and the method returns
     *       without calling the chain.</li>
     * </ol>
     *
     * @param request     the incoming request
     * @param response    the response, written only for a path its local port does not own
     * @param filterChain the rest of the filter chain
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if a later filter or the servlet fails to read or write
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = contextRelativePath(request);
        int localPort = request.getLocalPort();
        if (owns(localPort, path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Answering 404 for {} {} on local port {}: no listener on this port owns the path",
                    escapeControlCharacters(request.getMethod()), escapeControlCharacters(path), localPort);
        }
        // Empty 404: status and a zero Content-Length only, without sendError and without an error dispatch (D-011).
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentLength(0);
    }

    /**
     * Returns the filter order, {@link #ORDER}.
     *
     * @return {@code Ordered.HIGHEST_PRECEDENCE + 10}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Tells whether the listener on {@code localPort} owns {@code path}.
     *
     * @param localPort the local port the request arrived on
     * @param path      the context-relative path; may be {@code null}
     * @return {@code true} for {@code /client} on the client port and for a service path on any other port
     */
    private boolean owns(int localPort, String path) {
        if (path == null) {
            return false;
        }
        if (localPort == clientPort) {
            return CLIENT_PATH.equals(path);
        }
        return servicePaths.contains(path);
    }

    /**
     * Returns the request URI without the context path.
     *
     * @param request the incoming request
     * @return the undecoded request URI without the context path; {@code null} when the request has no URI or the
     *         URI does not start with a non-empty context path
     */
    private static String contextRelativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return uri;
        }
        return uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : null;
    }

    /**
     * Returns {@code value} with every ISO control character written as an escape sequence: {@code \r},
     * {@code \n} and {@code \t} by name, any other as {@code \}{@code u} and four lower-case hexadecimal digits,
     * for example {@code \}{@code u001b}. A value without control characters is returned unchanged.
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
