package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers 404 with an empty body when a path is requested on a port that does not serve it: the
 * callback port {@code sfdc.oauth.callback-port} serves only {@code /<sfdc.oauth.callback-path>}, and
 * every other port serves all other paths (D-011, D-466).
 *
 * <p>The ports and paths are those of the two listeners of
 * {@code authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml}:
 * <ul>
 *   <li>{@code :4} {@code sfdc:oauth-callback-config}: port {@code sfdc.oauth.callback-port} (8081),
 *       path {@code sfdc.oauth.callback-path} ({@code oauth2callback}), opened beside the primary
 *       listener by {@code config.AdditionalPortsConfig};</li>
 *   <li>{@code :6} and {@code :10} {@code http:listener-config} with {@code path="/"}: the primary
 *       listener on {@code server.port} ({@code http.port}, 8082), flow {@code salesforce-oauthFlow1}.</li>
 * </ul>
 *
 * <p>The path of a request is {@link HttpServletRequest#getRequestURI()} without
 * {@link HttpServletRequest#getContextPath()}: undecoded, without the query string. The port is
 * {@link HttpServletRequest#getLocalPort()}, the port of the listener that accepted the connection.
 * Each request is decided as follows, for every HTTP method; the example paths use the default
 * {@code sfdc.oauth.callback-path} {@code oauth2callback}:
 *
 * <pre>{@code
 * port                      path                                         result
 * sfdc.oauth.callback-port  /oauth2callback, compared exactly            passed to the filter chain
 * sfdc.oauth.callback-port  every other path, e.g. /, /error,
 *                           /favicon.ico, /oauth2callback/,
 *                           /OAUTH2CALLBACK, /oauth2%63allback           404
 * every other port          /oauth2callback, or a path that resolves
 *                           to it, e.g. /oauth2%63allback,
 *                           /oauth2callback;x=1                          404
 * every other port          every other path, e.g. /, /missing           passed to the filter chain
 * }</pre>
 *
 * <p>A rejected request gets status 404 with Undertow's standard reason phrase,
 * {@code Content-Length: 0}, no {@code Content-Type} and no body (D-066); the response is committed
 * with {@link HttpServletResponse#flushBuffer()} and the rest of the filter chain does not run. A
 * DEBUG entry names the method, path and port of each rejected request; the configured values are
 * not logged.
 *
 * <p>The filter runs first among the application's filters ({@link Ordered#HIGHEST_PRECEDENCE}). It
 * keeps the {@link OncePerRequestFilter} defaults: it runs once per request and does not run for async
 * or error dispatches. The instance is immutable after construction and safe for concurrent requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortPathGuardFilter extends OncePerRequestFilter {

    /** The highest TCP port number accepted for {@code sfdc.oauth.callback-port}. */
    private static final int MAX_TCP_PORT = 65_535;

    /** {@code sfdc.oauth.callback-port}: the only port that serves the callback path. */
    private final int callbackPort;

    /** {@code "/" + sfdc.oauth.callback-path}: the callback path, {@code /oauth2callback} by default. */
    private final String callbackPath;

    /**
     * Creates the filter from the bound {@code sfdc.oauth.*} keys.
     *
     * @param properties the bound {@code sfdc.*} keys; only {@code sfdc.oauth.callback-port} and
     *                   {@code sfdc.oauth.callback-path} are read
     * @throws NullPointerException  if {@code properties} is {@code null}
     * @throws IllegalStateException if no {@code sfdc.oauth.*} key is bound, if
     *                               {@code sfdc.oauth.callback-port} is outside 1 to 65535, or if
     *                               {@code sfdc.oauth.callback-path} is unset or blank
     */
    public PortPathGuardFilter(SalesforceOAuthProperties properties) {
        Objects.requireNonNull(properties, "properties");
        SalesforceOAuthProperties.OAuth oauth = properties.oauth();
        if (oauth == null) {
            throw new IllegalStateException(
                    "sfdc.oauth.callback-port and sfdc.oauth.callback-path must be set");
        }
        if (oauth.callbackPort() < 1 || oauth.callbackPort() > MAX_TCP_PORT) {
            throw new IllegalStateException("sfdc.oauth.callback-port must be between 1 and "
                    + MAX_TCP_PORT + ", was " + oauth.callbackPort());
        }
        if (oauth.callbackPath() == null || oauth.callbackPath().isBlank()) {
            throw new IllegalStateException("sfdc.oauth.callback-path must be set");
        }
        this.callbackPort = oauth.callbackPort();
        this.callbackPath = "/" + oauth.callbackPath();
    }

    /**
     * Passes the request to {@code filterChain} when its port serves its path, and otherwise answers
     * 404 with an empty body without calling {@code filterChain}.
     *
     * @param request     the current request
     * @param response    the current response
     * @param filterChain the rest of the filter chain
     * @throws ServletException if a later filter or the servlet throws it
     * @throws IOException      if writing the 404 or a later filter or the servlet fails with it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean onCallbackPort = request.getLocalPort() == callbackPort;
        boolean isCallbackPath = callbackPath.equals(path);
        // The callback port accepts only the exact undecoded callback path. Every other port also
        // rejects a path whose dispatcher-resolved form (each segment percent-decoded, ";" parameters
        // dropped), such as /oauth2%63allback or /oauth2callback;x=1, equals the callback path (D-466).
        boolean rejected = onCallbackPort
                ? !isCallbackPath
                : isCallbackPath || resolvesToCallbackPath(path);
        if (rejected) {
            reject(request, response, path);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Tells whether {@code path}, resolved as Spring MVC's path matching resolves it, equals the
     * callback path: each segment percent-decoded as UTF-8 and stripped of its {@code ;} parameters.
     * A path without {@code %} and {@code ;} resolves to itself. A path with a malformed percent
     * escape does not resolve to the callback path.
     *
     * @param path the undecoded request path within the application
     * @return {@code true} if the resolved path equals the callback path
     */
    private boolean resolvesToCallbackPath(String path) {
        if (path.indexOf('%') < 0 && path.indexOf(';') < 0) {
            return callbackPath.equals(path);
        }
        StringBuilder resolved = new StringBuilder(path.length());
        try {
            for (PathContainer.Element element : PathContainer.parsePath(path).elements()) {
                resolved.append(element instanceof PathContainer.PathSegment segment
                        ? segment.valueToMatch()
                        : element.value());
            }
        } catch (IllegalArgumentException malformedEscape) {
            return false;
        }
        return callbackPath.contentEquals(resolved);
    }

    /**
     * Writes the 404 answer: status 404, {@code Content-Length: 0}, no {@code Content-Type} and no
     * body, then commits the response.
     *
     * @param request  the rejected request, read for the DEBUG entry
     * @param response the response to answer
     * @param path     the rejected request path within the application
     * @throws IOException if committing the response fails
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        if (logger.isDebugEnabled()) {
            logger.debug("Answering 404 for " + printable(request.getMethod()) + " " + printable(path)
                    + " on port " + request.getLocalPort());
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentLength(0);
        response.flushBuffer();
    }

    /**
     * Returns {@code value} with every ISO control character, carriage return and line feed among
     * them, replaced by {@code _}, and {@code null} as {@code "null"}.
     *
     * @param value a request-supplied value
     * @return the value as written to the log
     */
    private static String printable(String value) {
        String text = String.valueOf(value);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '_' : c);
        }
        return out.toString();
    }
}
