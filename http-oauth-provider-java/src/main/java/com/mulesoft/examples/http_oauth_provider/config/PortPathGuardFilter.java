package com.mulesoft.examples.http_oauth_provider.config;

import com.mulesoft.examples.http_oauth_provider.controller.RawBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 404 with no body for any path not owned by the receiving port (D-011).
 *
 * <p>Source: the provider module listens on {@code ${http.provider.port}}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:26] and serves {@code /authorize} and
 * {@code /token}; {@code HTTP_Listener_Configuration} on {@code localhost:${http.listener.port}}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:43] serves {@code /resources}
 * [:45] and {@code /redirect} [:54]. The defaults are 8081 and 8082
 * [http-oauth-provider/src/main/app/mule-app.properties].
 *
 * <p>Behaviour per request, keyed on {@link HttpServletRequest#getLocalPort()} and the exact,
 * undecoded {@link HttpServletRequest#getRequestURI()}:
 * <ul>
 *   <li>Provider port with {@code /authorize} or {@code /token}, any method: the rest of the
 *       filter chain receives the request and response unchanged.</li>
 *   <li>Listener port with {@code /resources} or {@code /redirect}, any method: the rest of the
 *       filter chain receives the request and response unchanged.</li>
 *   <li>Any other port and path pair, an owned path on the other port, an owned path with a
 *       trailing slash and a request without a request URI included: status 404,
 *       {@code Content-Length: 0}, no {@code Content-Type} and an empty body, written through
 *       {@link RawBody#write} (D-011, D-066). The rest of the filter chain does not run.</li>
 * </ul>
 * The query string is not part of the request URI, so {@code /redirect?code=x} is the path
 * {@code /redirect}. The HTTP method is never checked here; the security chains and controllers
 * check it.
 *
 * <p>The filter is not a Spring bean. {@code AdditionalPortsConfig.portPathGuardFilterRegistration(...)}
 * registers one instance with the configured {@code http.provider.port} and
 * {@code http.listener.port} values, for {@code REQUEST} dispatches only and ahead of every other
 * servlet filter:
 *
 * <pre>{@code
 * FilterRegistrationBean<PortPathGuardFilter> registration =
 *         new FilterRegistrationBean<>(new PortPathGuardFilter(providerPort, listenerPort));
 * registration.addUrlPatterns("/*");
 * registration.setDispatcherTypes(DispatcherType.REQUEST);
 * registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
 * }</pre>
 *
 * An {@code ERROR} dispatch, such as the one a {@code sendError(400)} of the authorization
 * endpoint starts, does not pass through the filter and reaches the {@code /error} handling.
 *
 * <p>The filter holds no per-request state and is safe for concurrent requests.
 */
public class PortPathGuardFilter extends OncePerRequestFilter {

    /** Paths served on {@code http.provider.port} (D-011). */
    private static final Set<String> PROVIDER_PATHS = Set.of("/authorize", "/token");

    /** Paths served on {@code http.listener.port} (D-011). */
    private static final Set<String> LISTENER_PATHS = Set.of("/resources", "/redirect");

    /** Status of a request whose path the receiving port does not own (D-011). */
    private static final int NOT_FOUND = HttpServletResponse.SC_NOT_FOUND;

    /** Body of a request whose path the receiving port does not own: no bytes (D-011). */
    private static final byte[] EMPTY_BODY = new byte[0];

    /** Local port of the provider listener, the value of {@code http.provider.port}. */
    private final int providerPort;

    /** Local port of the primary listener, the value of {@code http.listener.port}. */
    private final int listenerPort;

    /**
     * Creates the guard for the two configured listener ports (D-011).
     *
     * <p>Each value is compared with {@link HttpServletRequest#getLocalPort()} as given; a value
     * of {@code 0} matches no request.
     *
     * @param providerPort the value of {@code http.provider.port}, owner of {@code /authorize}
     *                     and {@code /token}
     * @param listenerPort the value of {@code http.listener.port}, owner of {@code /resources}
     *                     and {@code /redirect}
     */
    public PortPathGuardFilter(int providerPort, int listenerPort) {
        super();
        this.providerPort = providerPort;
        this.listenerPort = listenerPort;
    }

    /**
     * Passes a request for a path owned by its receiving port to the rest of the chain, and
     * answers every other request with an empty 404 without calling the chain (D-011).
     *
     * @param request  the incoming request
     * @param response the response, written only for a path the receiving port does not own
     * @param chain    the rest of the filter chain
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if a later filter or the servlet fails to write, or writing the
     *                          404 fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int port = request.getLocalPort();
        String path = request.getRequestURI();
        boolean owned = path != null
                && ((port == providerPort && PROVIDER_PATHS.contains(path))
                        || (port == listenerPort && LISTENER_PATHS.contains(path)));
        if (owned) {
            chain.doFilter(request, response);
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug(request.getMethod() + " " + path + " on port " + port
                    + " answered 404: the path is not owned by this port");
        }
        RawBody.write(response, NOT_FOUND, EMPTY_BODY);
    }
}
