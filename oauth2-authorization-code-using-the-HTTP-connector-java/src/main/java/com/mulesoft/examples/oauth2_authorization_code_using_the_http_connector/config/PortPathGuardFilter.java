package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller.RawBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps each listener path on the port that owns it and answers a path requested on the wrong port
 * with 404 and the body {@code No listener for endpoint: <request URI>}, with no {@code Content-Type}
 * (D-011, D-066, D-513, D-668).
 *
 * <p>Source
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml]:
 * <ul>
 *   <li>the plain-HTTP listener {@code HTTP_Listener_Configuration} (:26), Spring Boot's primary
 *       listener on {@code server.port} = {@code listener.http-listener-configuration.port}
 *       (default 8081), owns {@code /web} ({@code boxUserLoginFlow}, :28) and
 *       {@code /web/loginDone} ({@code userLoginDoneFlow}, :39);</li>
 *   <li>the HTTPS listener the OAuth module opens for {@code localAuthorizationUrl} (:7) and
 *       {@code redirectionUrl} (:6), added by {@code AdditionalPortsConfig} on the ports of
 *       {@link BoxProperties#localListenerAddresses()} ({@code box.local-authorization-url},
 *       {@code box.redirection-url}; default 8082), owns exactly {@code /authorization} and
 *       {@code /redirectUrl}.</li>
 * </ul>
 *
 * <p>Ownership is decided per request on {@link HttpServletRequest#getLocalPort()} and the
 * undecoded {@link HttpServletRequest#getRequestURI()}, without the query string, for every HTTP
 * method:
 * <ul>
 *   <li>a local port in the HTTPS listener set passes only the exact paths
 *       {@code /authorization} and {@code /redirectUrl}; {@code /web}, {@code /web/loginDone}, a
 *       trailing-slash, letter-case, percent-encoded or {@code ;}-parameter variant, and every
 *       other path get the 404;</li>
 *   <li>every other local port is the primary listener. It answers the 404 for
 *       {@code /authorization}, {@code /redirectUrl} and every path whose dispatcher-resolved form
 *       equals one of them: each segment percent-decoded as UTF-8 with its {@code ;} parameters
 *       dropped, as {@link PathContainer.PathSegment#valueToMatch()} gives it, for example
 *       {@code /%61uthorization} and {@code /redirectUrl;x=1}. Every other path passes to the rest
 *       of the chain, where a path no controller maps reaches
 *       {@code exception/GlobalExceptionHandler.notFound}, which writes the same 404 body (D-513).
 *       A path with a malformed percent escape resolves to no path and passes.</li>
 * </ul>
 *
 * <p>Example exchanges with the committed ports:
 * <pre>
 * GET  http://localhost:8081/web                    → rest of the filter chain (302)
 * GET  http://localhost:8081/web/loginDone          → rest of the filter chain
 * GET  http://localhost:8081/unknown                → rest of the filter chain (404 from the advice)
 * GET  http://localhost:8081/authorization?x=1      → 404 "No listener for endpoint: /authorization"
 * POST http://localhost:8081/%72edirectUrl          → 404 "No listener for endpoint: /%72edirectUrl"
 * GET  https://localhost:8082/authorization         → rest of the filter chain (302)
 * GET  https://localhost:8082/redirectUrl?code=abc  → rest of the filter chain
 * GET  https://localhost:8082/web                   → 404 "No listener for endpoint: /web"
 * GET  https://localhost:8082/unknown               → 404 "No listener for endpoint: /unknown"
 * </pre>
 *
 * <p>A rejected request gets status 404 and the UTF-8 bytes of the body, written through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}: no {@code Content-Type} header (D-066)
 * and no reason phrase; the status line carries Undertow's standard {@code Not Found} (D-010).
 * The rest of the filter chain does not run for a rejected request. A request without a request
 * URI passes on the primary listener and is rejected on the HTTPS listener with the body
 * {@code No listener for endpoint: null}.
 *
 * <p>Spring Boot registers this bean for {@code /*} with the order
 * {@link Ordered#HIGHEST_PRECEDENCE}. Error and async dispatches pass through unfiltered, the
 * {@link OncePerRequestFilter} defaults. Each rejected request writes one DEBUG log entry with the
 * method, the request URI, the local port and the listener that owns the path, ISO control
 * characters replaced by {@code ?}. The filter holds no per-request state and serves concurrent
 * requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortPathGuardFilter extends OncePerRequestFilter {

    /**
     * Paths of the plain-HTTP listener {@code HTTP_Listener_Configuration}: {@code /web} (:28) and
     * {@code /web/loginDone} (:39). On the HTTPS listener they get the 404 (D-011).
     */
    static final Set<String> PRIMARY_PATHS = Set.of("/web", "/web/loginDone");

    /**
     * Paths of the HTTPS listener of the OAuth module: {@code /authorization}
     * ({@code localAuthorizationUrl}, :7) and {@code /redirectUrl} ({@code redirectionUrl}, :6).
     * They are the only paths the HTTPS listener passes, and the primary listener answers them with
     * the 404 (D-011).
     */
    static final Set<String> EXTRA_PORT_PATHS = Set.of("/authorization", "/redirectUrl");

    /** Start of the body of a rejected request; the request URI follows it (D-513). */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Owner named in the DEBUG entry of a primary-listener path requested on the HTTPS listener. */
    private static final String HTTP_LISTENER = "the HTTP listener";

    /** Owner named in the DEBUG entry of an HTTPS-listener path requested on the primary listener. */
    private static final String HTTPS_LISTENER = "the HTTPS listener";

    /** Owner named in the DEBUG entry of a path that neither listener owns. */
    private static final String NO_OWNER = "no listener";

    /** Logger of rejected requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(PortPathGuardFilter.class);

    /** Local ports of the HTTPS listener, the ports of {@link BoxProperties#localListenerAddresses()}. */
    private final Set<Integer> extraPorts;

    /**
     * Creates the guard for the HTTPS listener ports that {@code box.local-authorization-url} and
     * {@code box.redirection-url} name (D-011).
     *
     * <p>The ports are read once, here, as the {@link InetSocketAddress#getPort() ports} of
     * {@link BoxProperties#localListenerAddresses()}; with the committed {@code application.yml} the
     * set is the single port 8082. A request whose local port is in the set is checked against
     * {@link #EXTRA_PORT_PATHS}; a request on any other local port is the primary listener's. With
     * neither URL bound the set is empty and every local port is the primary listener's.
     *
     * @param box the bound {@code box.*} properties
     * @throws NullPointerException  if {@code box} is {@code null}
     * @throws IllegalStateException if {@link BoxProperties#localListenerAddresses()} rejects
     *                               {@code box.local-authorization-url} or
     *                               {@code box.redirection-url}; the message names the key
     */
    public PortPathGuardFilter(BoxProperties box) {
        Objects.requireNonNull(box, "box");
        this.extraPorts = box.localListenerAddresses().stream()
                .map(InetSocketAddress::getPort)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Passes a request for a path its local port owns to the rest of the chain, and answers a path
     * requested on the wrong port with the no-listener 404 without calling the chain (D-011, D-066,
     * D-668).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the path is {@link HttpServletRequest#getRequestURI()}: undecoded, without the query
     *       string, and with the empty context path of this application;</li>
     *   <li>the request is on the HTTPS listener when {@link HttpServletRequest#getLocalPort()} is
     *       one of the ports of {@link BoxProperties#localListenerAddresses()}, otherwise on the
     *       primary listener;</li>
     *   <li>on the HTTPS listener a path that is not exactly {@code /authorization} or
     *       {@code /redirectUrl} is rejected;</li>
     *   <li>on the primary listener a path is rejected when its dispatcher-resolved form (each
     *       segment percent-decoded, {@code ;} parameters dropped) is {@code /authorization} or
     *       {@code /redirectUrl};</li>
     *   <li>any other request goes to {@code chain.doFilter(request, response)};</li>
     *   <li>a rejected request gets status 404 with the UTF-8 body
     *       {@code No listener for endpoint: <path>} through {@link RawBody#write}, no
     *       {@code Content-Type} and no reason phrase, and the chain is not called.</li>
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
        boolean onExtraPort = extraPorts.contains(request.getLocalPort());
        boolean rejected = onExtraPort ? !isExtraPortPath(path) : resolvesToExtraPortPath(path);
        if (!rejected) {
            chain.doFilter(request, response);
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Answering 404 for {} {} on port {}: the path belongs to {}",
                    printable(request.getMethod()), printable(path), request.getLocalPort(),
                    ownerOf(path, onExtraPort));
        }
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                (NO_LISTENER_PREFIX + path).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns whether {@code path} is exactly one of {@link #EXTRA_PORT_PATHS}.
     *
     * @param path the undecoded request URI, or {@code null}
     * @return {@code true} for {@code /authorization} and {@code /redirectUrl}; {@code false} for
     *         every other value, {@code null} included
     */
    private static boolean isExtraPortPath(String path) {
        return path != null && EXTRA_PORT_PATHS.contains(path);
    }

    /**
     * Returns whether {@code path}, or its dispatcher-resolved form, is one of
     * {@link #EXTRA_PORT_PATHS}.
     *
     * @param path the undecoded request URI, or {@code null}
     * @return {@code true} when {@code path} or {@link #resolve(String)} of it is
     *         {@code /authorization} or {@code /redirectUrl}; {@code false} otherwise, also for
     *         {@code null} and for a path with a malformed percent escape
     */
    private static boolean resolvesToExtraPortPath(String path) {
        if (path == null) {
            return false;
        }
        if (EXTRA_PORT_PATHS.contains(path)) {
            return true;
        }
        String resolved = resolve(path);
        return resolved != null && EXTRA_PORT_PATHS.contains(resolved);
    }

    /**
     * Returns {@code path} as Spring MVC's path matching reads it: the separators of
     * {@link PathContainer#parsePath(String)} unchanged and each segment replaced by its
     * {@link PathContainer.PathSegment#valueToMatch()}, the segment percent-decoded as UTF-8 without
     * its {@code ;} parameters. {@code /%61uthorization} and {@code /authorization;x=1} both resolve
     * to {@code /authorization}.
     *
     * @param path the undecoded request URI
     * @return the resolved path, or {@code null} when a segment holds a malformed percent escape
     */
    private static String resolve(String path) {
        try {
            StringBuilder resolved = new StringBuilder(path.length());
            for (PathContainer.Element element : PathContainer.parsePath(path).elements()) {
                if (element instanceof PathContainer.PathSegment segment) {
                    resolved.append(segment.valueToMatch());
                } else {
                    resolved.append(element.value());
                }
            }
            return resolved.toString();
        } catch (IllegalArgumentException malformedEscape) {
            return null;
        }
    }

    /**
     * Names the listener that owns a rejected path, for the DEBUG entry.
     *
     * @param path        the undecoded request URI, or {@code null}
     * @param onExtraPort whether the request arrived on the HTTPS listener
     * @return {@code the HTTPS listener} for a rejection on the primary listener;
     *         {@code the HTTP listener} for one of {@link #PRIMARY_PATHS} on the HTTPS listener;
     *         {@code no listener} otherwise
     */
    private static String ownerOf(String path, boolean onExtraPort) {
        if (!onExtraPort) {
            return HTTPS_LISTENER;
        }
        return path != null && PRIMARY_PATHS.contains(path) ? HTTP_LISTENER : NO_OWNER;
    }

    /**
     * Returns {@code value} for a log entry with every ISO control character replaced by {@code ?};
     * {@code null} is returned as {@code "null"}.
     *
     * @param value the request-derived text, or {@code null}
     * @return the text with control characters replaced
     */
    private static String printable(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}
