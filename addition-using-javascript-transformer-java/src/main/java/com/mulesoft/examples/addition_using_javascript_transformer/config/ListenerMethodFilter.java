package com.mulesoft.examples.addition_using_javascript_transformer.config;

import com.mulesoft.examples.addition_using_javascript_transformer.controller.RawBody;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers OPTIONS, TRACE and extension methods with 405 on {@code /} and 404 on any other path, with
 * an empty body and no {@code Content-Type} or {@code Allow} header; GET, HEAD, POST, PUT, DELETE and
 * PATCH pass through. See DECISIONS.md D-428, D-446 and D-589 (Listener no-match answers) and D-066.
 *
 * <p>Source: the Mule listener {@code HTTP_Listener_Configuration} of flow
 * {@code javascript-calculatorFlow1} accepts only {@code POST} on {@code /}
 * [addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:3-5].
 *
 * <p>Example exchanges:
 * <pre>
 * POST /      → rest of the filter chain ({@code controller.CalculatorController}, 200)
 * GET /       → rest of the filter chain ({@code exception.GlobalExceptionHandler}, 405)
 * GET /x      → rest of the filter chain ({@code exception.GlobalExceptionHandler}, 404)
 * OPTIONS /   → 405 Method Not Allowed, Content-Length: 0
 * TRACE /     → 405 Method Not Allowed, Content-Length: 0
 * FOO /       → 405 Method Not Allowed, Content-Length: 0
 * get /       → 405 Method Not Allowed, Content-Length: 0
 * CONNECT /   → 405 Method Not Allowed, Content-Length: 0
 * OPTIONS /x  → 404 Not Found, Content-Length: 0
 * TRACE /x    → 404 Not Found, Content-Length: 0
 * PROPFIND /error → 404 Not Found, Content-Length: 0
 * </pre>
 *
 * <p>Undertow answers the asterisk form {@code OPTIONS *} and the authority form
 * {@code CONNECT host:port} with 500 before any filter runs; neither reaches this class (D-589).
 *
 * <p>Method names are compared exactly and case-sensitively against GET, HEAD, POST, PUT, DELETE and
 * PATCH: a lowercase {@code get} is an extension method. The path test reads
 * {@link HttpServletRequest#getRequestURI()} unchanged, which holds no query string: {@code OPTIONS /?a=1}
 * is a request for {@code /} and answers 405.
 *
 * <p>An answered request gets its status and {@code Content-Length: 0} through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, and no other header from this class: no
 * {@code Content-Type} (D-066), no {@code Allow} and no custom reason phrase; the status line carries
 * Undertow's standard {@code Method Not Allowed} or {@code Not Found} phrase (D-010). The rest of the filter chain
 * and the {@code DispatcherServlet} do not run for it, and it writes one DEBUG log entry with the
 * method, the request URI and the status. A request with a pass-through method reaches the rest of the
 * filter chain with its request and response unchanged.
 *
 * <p>Spring Boot registers this bean for {@code /*} with the order {@link Ordered#HIGHEST_PRECEDENCE}
 * {@code + 1}. Error and async dispatches pass through unfiltered, the {@link OncePerRequestFilter}
 * defaults. The filter holds no per-request state and is safe for concurrent requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ListenerMethodFilter extends OncePerRequestFilter {

    /** Logger of answered requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(ListenerMethodFilter.class);

    /** Method names that pass through to the rest of the filter chain, compared case-sensitively. */
    private static final Set<String> STANDARD_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH");

    /**
     * Passes a request whose method is GET, HEAD, POST, PUT, DELETE or PATCH to the rest of the filter
     * chain and answers any other request itself: 405 for the request URI {@code /}, 404 for any other
     * request URI, with {@code Content-Length: 0}, an empty body and no {@code Content-Type} or
     * {@code Allow} header (D-428, D-446, D-589, D-066).
     *
     * @param request  the HTTP request; its method and request URI select the outcome
     * @param response the HTTP response, written and committed for an answered request
     * @param chain    the rest of the filter chain, invoked only for a pass-through method
     * @throws ServletException if the rest of the filter chain throws it
     * @throws IOException      if the rest of the filter chain throws it, or the answer cannot be written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (STANDARD_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        int status = "/".equals(request.getRequestURI()) ? 405 : 404;
        LOG.debug("Answered {} {} with {}", request.getMethod(), request.getRequestURI(), status);
        RawBody.write(response, status, new byte[0]);
    }
}
