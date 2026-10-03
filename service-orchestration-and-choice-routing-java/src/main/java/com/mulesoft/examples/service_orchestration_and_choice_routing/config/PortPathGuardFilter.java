package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import com.mulesoft.examples.service_orchestration_and_choice_routing.exception.ReasonPhrase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps every path on the port that owns it and answers paths and methods no listener owns, as the
 * Mule 3.8.0 HTTP listener does (D-011).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:24-27}, the four
 *       {@code http:listener-config} elements, and their listeners {@code POST orders} (:29),
 *       {@code POST api} (:145), {@code POST samsung/orders} (:151) and {@code GET populate} (:160);</li>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml:3}, the
 *       {@code ajax:connector} at {@code http://0.0.0.0:8090/orders}, whose docroot and channels live
 *       under {@code ${ajax-server.path}}.</li>
 * </ul>
 *
 * <p>Ownership by local port ({@link HttpServletRequest#getLocalPort()}) and raw request URI
 * ({@link HttpServletRequest#getRequestURI()}, query excluded):
 * <table>
 *   <caption>Owned requests per port</caption>
 *   <tr><th>Port</th><th>Owned requests</th></tr>
 *   <tr><td>AJAX port ({@link AdditionalPortsConfig.ListenerPorts#isAjaxPort(int)}, {@code server.port})</td>
 *       <td>every method on {@code ${ajax-server.path}} and on every path below
 *       {@code ${ajax-server.path}/}</td></tr>
 *   <tr><td>{@code ordersSoapPort} ({@code listener.http-listener-configuration3.port}, 1080)</td>
 *       <td>{@code POST /orders}; {@code GET /orders?wsdl} (D-028)</td></tr>
 *   <tr><td>{@code samsungSoapPort} ({@code listener.http-listener-configuration1.port}, 9090)</td>
 *       <td>{@code POST /samsung/orders}; {@code GET /samsung/orders?wsdl} (D-028)</td></tr>
 *   <tr><td>{@code apiPort} ({@code listener.http-listener-configuration2.port}, 9999)</td>
 *       <td>{@code POST /api} only, no path below it (D-067)</td></tr>
 *   <tr><td>{@code populatePort} ({@code listener.http-listener-configuration.port}, 8091)</td>
 *       <td>{@code GET /populate}</td></tr>
 * </table>
 *
 * <p>Matching rules on the four listener ports (D-011):
 * <ul>
 *   <li>A listener path matches itself and itself followed by exactly one {@code /}
 *       ({@code /populate/} is {@code /populate}); {@code /populate//}, {@code //populate} and every
 *       deeper path such as {@code /orders/index.html} or {@code /api/prices/AX02} match no listener.</li>
 *   <li>Method names are compared exactly, {@code post} is not {@code POST}.</li>
 *   <li>{@code GET} with the query {@code wsdl} in any letter case passes on the exact SOAP path of
 *       its own port only; {@code HEAD ?wsdl}, {@code GET /orders/?wsdl} and {@code GET /orders} without
 *       the query are a method the listener does not allow (D-028).</li>
 * </ul>
 *
 * <p>Answers (written directly, no {@code sendError}, no {@code Content-Type}):
 * <ul>
 *   <li>Listener port, owned path, other method: {@code 405} with the reason phrase
 *       {@code Method not allowed for endpoint: <uri>} and the body {@code Method Not Allowed}, as
 *       mulesoft/mule {@code mule-3.8.0} {@code NoMethodRequestHandler} answers.</li>
 *   <li>Listener port, unowned path: {@code 404} with the reason phrase
 *       {@code No listener for endpoint: <uri>} and the body {@code Resource not found.}, as
 *       {@code NoListenerRequestHandler} answers.</li>
 *   <li>{@code <uri>} is the raw request URI followed by {@code ?} and the raw query when the query is
 *       not empty. The reason phrase reaches the status line through {@link ReasonPhrase#set(String)}
 *       (D-010). The body carries no {@code Content-Length}: on a persistent HTTP/1.1 connection it is
 *       sent with {@code Transfer-Encoding: chunked}, otherwise the connection closes after it.</li>
 *   <li>AJAX port, path outside {@code ${ajax-server.path}}: {@code 404 Not Found} with an empty body
 *       and {@code Content-Length: 0}, for example the page's {@code /sh/scripts/shCore.js} and
 *       {@code /ajax/cometd}.</li>
 * </ul>
 *
 * <p>Only REQUEST dispatches are filtered: the {@link OncePerRequestFilter} defaults pass error and async
 * dispatches, and a forward or include inside an admitted request is not filtered again.
 *
 * <pre>{@code
 * // ports: orders 1080, samsung 9090, api 9999, populate 8091; ajax-server.path /orders
 * decide(1080, "POST", "/orders", null);           // PASS
 * decide(1080, "GET", "/orders", "wsdl");          // PASS
 * decide(1080, "GET", "/orders", null);            // METHOD_NOT_ALLOWED
 * decide(1080, "GET", "/populate", null);          // NO_LISTENER
 * decide(9999, "GET", "/api/prices/AX02", null);   // NO_LISTENER
 * decide(8090, "POST", "/orders/request", null);   // PASS
 * decide(8090, "GET", "/sh/scripts/shCore.js", null); // NOT_FOUND_EMPTY
 * }</pre>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortPathGuardFilter extends OncePerRequestFilter {

    /** Reason-phrase format of the 404 for a path no listener owns ({@code NoListenerRequestHandler}). */
    static final String NO_LISTENER_REASON_FORMAT = "No listener for endpoint: %s";

    /** Body of the 404 for a path no listener owns ({@code NoListenerRequestHandler.RESOURCE_NOT_FOUND}). */
    static final String NO_LISTENER_ENTITY = "Resource not found.";

    /** Reason-phrase format of the 405 for a method the listener does not allow ({@code NoMethodRequestHandler}). */
    static final String NO_METHOD_REASON_FORMAT = "Method not allowed for endpoint: %s";

    /** Body of the 405 for a method the listener does not allow ({@code NoMethodRequestHandler}). */
    static final String NO_METHOD_ENTITY = "Method Not Allowed";

    /*
     * The four listener paths are the fixed source paths of fulfillment.xml:29,145,151,160 with a leading
     * slash; the listener.<config>.path keys of application.yml are not read (D-011).
     */

    /** Path of the {@code orderService} SOAP listener on {@code ordersSoapPort}. */
    static final String ORDERS_PATH = "/orders";

    /** Path of the {@code samsungService} SOAP listener on {@code samsungSoapPort}. */
    static final String SAMSUNG_ORDERS_PATH = "/samsung/orders";

    /** Path of the {@code priceService} listener on {@code apiPort} (D-067). */
    static final String API_PATH = "/api";

    /** Path of the {@code databaseInitialisation} listener on {@code populatePort}. */
    static final String POPULATE_PATH = "/populate";

    /** Allowed method of {@code GET populate} and method of the {@code ?wsdl} request. */
    private static final String GET = "GET";

    /** Allowed method of {@code POST orders}, {@code POST samsung/orders} and {@code POST api}. */
    private static final String POST = "POST";

    /** Query of the WSDL request, compared in any letter case (D-028). */
    private static final String WSDL_QUERY = "wsdl";

    /** Path separator. */
    private static final String SLASH = "/";

    /** Key of the AJAX docroot and channel path. */
    private static final String AJAX_PATH_KEY = "ajax-server.path";

    /** Bytes of {@link #NO_LISTENER_ENTITY}, ASCII only. */
    private static final byte[] NO_LISTENER_BODY = NO_LISTENER_ENTITY.getBytes(StandardCharsets.UTF_8);

    /** Bytes of {@link #NO_METHOD_ENTITY}, ASCII only. */
    private static final byte[] NO_METHOD_BODY = NO_METHOD_ENTITY.getBytes(StandardCharsets.UTF_8);

    /**
     * Outcome of {@link #decide(int, String, String, String)} for one request.
     */
    enum Decision {

        /** The request continues down the filter chain. */
        PASS,

        /** {@code 404 Not Found} with an empty body: AJAX port, path outside {@code ${ajax-server.path}}. */
        NOT_FOUND_EMPTY,

        /** {@code 404 No listener for endpoint: <uri>}, body {@code Resource not found.}: listener port, unowned path. */
        NO_LISTENER,

        /** {@code 405 Method not allowed for endpoint: <uri>}, body {@code Method Not Allowed}: owned path, other method. */
        METHOD_NOT_ALLOWED
    }

    /** The four listener ports; every other local port is the AJAX port. */
    private final AdditionalPortsConfig.ListenerPorts ports;

    /** {@code ${ajax-server.path}} without trailing slashes; {@code /} when the AJAX context is the root. */
    private final String ajaxRoot;

    /** {@link #ajaxRoot} followed by one {@code /}, or {@code /} for the root context. */
    private final String ajaxPrefix;

    /**
     * Creates the guard for the four listener ports and the AJAX docroot path (D-011).
     *
     * @param ports    the four listener ports; every other local port is the AJAX port
     * @param ajaxPath {@code ${ajax-server.path}}, for example {@code /orders}; trailing slashes are
     *                 ignored and {@code /} owns every path on the AJAX port
     * @throws NullPointerException  when {@code ports} is {@code null}
     * @throws IllegalStateException naming {@code ajax-server.path} when {@code ajaxPath} is {@code null},
     *                               does not start with {@code /} or contains whitespace
     */
    public PortPathGuardFilter(AdditionalPortsConfig.ListenerPorts ports,
            @Value("${" + AJAX_PATH_KEY + "}") String ajaxPath) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.ajaxRoot = ajaxRoot(ajaxPath);
        this.ajaxPrefix = SLASH.equals(ajaxRoot) ? SLASH : ajaxRoot + SLASH;
    }

    /**
     * Passes an owned request down the chain and answers every other request on its port (D-011).
     *
     * @param request     the incoming request; its local port, method, raw URI and raw query decide
     * @param response    the response, written directly for a request that does not pass
     * @param filterChain the remaining chain, called only for {@link Decision#PASS}
     * @throws ServletException from the remaining chain
     * @throws IOException      from the remaining chain or while writing an answer
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        int localPort = request.getLocalPort();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String query = request.getQueryString();
        Decision decision = decide(localPort, method, path, query);
        if (decision == Decision.PASS) {
            filterChain.doFilter(request, response);
            return;
        }
        String uri = uri(path, query);
        if (logger.isDebugEnabled()) {
            logger.debug("Port " + localPort + " answers " + decision + " for " + printable(method) + " "
                    + printable(uri));
        }
        if (response.isCommitted()) {
            logger.warn("Response already committed, no " + decision + " answer written for port " + localPort
                    + " " + printable(method) + " " + printable(uri));
            return;
        }
        switch (decision) {
            case NOT_FOUND_EMPTY -> writeEmptyNotFound(response);
            case NO_LISTENER -> writeListenerAnswer(response, HttpServletResponse.SC_NOT_FOUND,
                    String.format(NO_LISTENER_REASON_FORMAT, printable(uri)), NO_LISTENER_BODY);
            case METHOD_NOT_ALLOWED -> writeListenerAnswer(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                    String.format(NO_METHOD_REASON_FORMAT, printable(uri)), NO_METHOD_BODY);
            default -> throw new IllegalStateException("Unexpected decision " + decision);
        }
    }

    /**
     * Decides one request from its local port, method, raw path and raw query (D-011).
     *
     * @param localPort the request's local port
     * @param method    the HTTP method as received, compared exactly
     * @param path      the raw request URI without query; {@code null} owns nothing
     * @param query     the raw query string, or {@code null} when absent
     * @return {@link Decision#PASS} for an owned request, otherwise the answer the port gives
     */
    Decision decide(int localPort, String method, String path, String query) {
        if (ports.isAjaxPort(localPort)) {
            return isAjaxPath(path) ? Decision.PASS : Decision.NOT_FOUND_EMPTY;
        }
        if (path == null) {
            return Decision.NO_LISTENER;
        }
        if (localPort == ports.ordersSoapPort()) {
            return decideListener(ORDERS_PATH, POST, true, method, path, query);
        }
        if (localPort == ports.samsungSoapPort()) {
            return decideListener(SAMSUNG_ORDERS_PATH, POST, true, method, path, query);
        }
        if (localPort == ports.apiPort()) {
            return decideListener(API_PATH, POST, false, method, path, query);
        }
        return decideListener(POPULATE_PATH, GET, false, method, path, query);
    }

    /**
     * Decides a request on one listener port.
     *
     * @param listenerPath  the listener's path
     * @param allowedMethod the listener's only allowed method
     * @param servesWsdl    whether {@code GET <listenerPath>?wsdl} passes (D-028)
     * @param method        the request method
     * @param path          the raw request path, not {@code null}
     * @param query         the raw query, or {@code null}
     * @return {@link Decision#PASS}, {@link Decision#METHOD_NOT_ALLOWED} or {@link Decision#NO_LISTENER}
     */
    private static Decision decideListener(String listenerPath, String allowedMethod, boolean servesWsdl,
            String method, String path, String query) {
        if (!isListenerPath(path, listenerPath)) {
            return Decision.NO_LISTENER;
        }
        if (allowedMethod.equals(method)) {
            return Decision.PASS;
        }
        if (servesWsdl && GET.equals(method) && path.equals(listenerPath) && WSDL_QUERY.equalsIgnoreCase(query)) {
            return Decision.PASS;
        }
        return Decision.METHOD_NOT_ALLOWED;
    }

    /**
     * Tells whether a request path is a listener path, alone or followed by exactly one {@code /}.
     *
     * @param path         the raw request path
     * @param listenerPath the listener's path
     * @return {@code true} for {@code listenerPath} and {@code listenerPath + "/"}
     */
    private static boolean isListenerPath(String path, String listenerPath) {
        return path.equals(listenerPath)
                || (path.length() == listenerPath.length() + 1 && path.startsWith(listenerPath) && path.endsWith(SLASH));
    }

    /**
     * Tells whether a path on the AJAX port lies at or below {@code ${ajax-server.path}}.
     *
     * @param path the raw request path, or {@code null}
     * @return {@code true} for the AJAX path itself and every path below it
     */
    private boolean isAjaxPath(String path) {
        return path != null && (path.equals(ajaxRoot) || path.startsWith(ajaxPrefix));
    }

    /**
     * Writes the 404 of the AJAX port: standard reason phrase, empty body, {@code Content-Length: 0}.
     *
     * @param response the uncommitted response
     * @throws IOException when the response cannot be flushed
     */
    private static void writeEmptyNotFound(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentLength(0);
        response.flushBuffer();
    }

    /**
     * Writes a listener-port answer: status, reason phrase and the fixed body without {@code Content-Length}.
     *
     * <p>Flushing the body before the response completes leaves its length unset; Undertow then sends it
     * with {@code Transfer-Encoding: chunked} on a persistent HTTP/1.1 connection and closes any other
     * connection after it, the framing of the Mule 3.8.0 listener's streamed error entity (D-011).
     *
     * @param response     the uncommitted response
     * @param status       {@code 404} or {@code 405}
     * @param reasonPhrase the custom reason phrase (D-010)
     * @param body         the fixed body bytes
     * @throws IOException when the body cannot be written
     */
    private static void writeListenerAnswer(HttpServletResponse response, int status, String reasonPhrase,
            byte[] body) throws IOException {
        response.setStatus(status);
        ReasonPhrase.set(reasonPhrase);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /**
     * Builds the request URI the Mule listener names in its reason phrase.
     *
     * @param path  the raw request path, or {@code null}
     * @param query the raw query, or {@code null}
     * @return {@code path}, followed by {@code ?query} when the query is not empty
     */
    static String uri(String path, String query) {
        String base = path == null ? "" : path;
        return query == null || query.isEmpty() ? base : base + "?" + query;
    }

    /**
     * Replaces every character outside printable ASCII with {@code ?}, for the status line and the log.
     *
     * @param text the text, or {@code null}
     * @return the printable text, {@code "null"} for {@code null}
     */
    private static String printable(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder printable = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                if (printable == null) {
                    printable = new StringBuilder(text);
                }
                printable.setCharAt(i, '?');
            }
        }
        return printable == null ? text : printable.toString();
    }

    /**
     * Validates {@code ${ajax-server.path}} and strips its trailing slashes.
     *
     * @param ajaxPath the configured AJAX path
     * @return the path without trailing slashes, or {@code /}
     * @throws IllegalStateException naming {@code ajax-server.path} when the path is {@code null}, does not
     *                               start with {@code /} or contains whitespace
     */
    private static String ajaxRoot(String ajaxPath) {
        if (ajaxPath == null || !ajaxPath.startsWith(SLASH) || ajaxPath.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(AJAX_PATH_KEY + " must start with '/' and contain no whitespace, was '"
                    + printable(ajaxPath) + "'");
        }
        String root = ajaxPath;
        while (root.length() > 1 && root.endsWith(SLASH)) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }
}
