package com.mulesoft.examples.mule_expression_language_basics.exception;

import com.mulesoft.examples.mule_expression_language_basics.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers the exceptions raised while serving HTTP requests on the listener configuration
 * {@code HTTP_Listener_Configuration} of {@code greeting.xml}, whose six flows {@code greetingFlow1},
 * {@code greetingFlow2}, {@code docs-greetingFlow3}, {@code docs-greetingFlow4}, {@code docs-greetingFlow5} and
 * {@code greetingFlow6} define no exception strategy (D-653).
 *
 * <p>The advice has no selector: it applies to every handler of the application and to requests for which no
 * handler exists. It answers through exactly two handler methods, one per listener default:
 *
 * <ul>
 *   <li>{@link #notFound}: a path no listener owns is answered with 404 and the body
 *       {@code No listener for endpoint: <path>};</li>
 *   <li>{@link #unexpected}: every other exception is logged at ERROR and answered with the default exception
 *       strategy's 500 and the exception message as the body.</li>
 * </ul>
 *
 * <p>With {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 * {@code spring.web.resources.add-mappings: false} in {@code application.yml}, a path no controller maps raises a
 * {@link NoHandlerFoundException} or a {@link NoResourceFoundException}. Spring MVC's
 * {@code ExceptionHandlerExceptionResolver} selects the handler whose declared exception type is closest to the
 * raised one: those two types always reach {@code notFound}, and every other exception reaches {@code unexpected}.
 *
 * <p>Both methods return {@code void} and take the {@link HttpServletResponse}, which marks the request handled:
 * no message converter runs and no view is rendered. Both write through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, so each answer carries {@code Content-Length} and no
 * {@code Content-Type} header (D-066). Neither sets a reason phrase: the status line carries Undertow's standard
 * phrase for the status code (D-010). Example exchanges:
 *
 * <pre>{@code
 * GET /nope?x=1                      -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /nope
 * GET /greet1/                       -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /greet1/
 * GET /error                         -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /error
 * OPTIONS /nope                      -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /nope
 * GET /greet3?username=Mule&age=abc  -> HTTP/1.1 500 Internal Server Error  body: <exception message>
 * }</pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the one ERROR event written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Prefix of the unmatched-path 404 body; the request URI follows it. */
    static final String NO_LISTENER_FOR_ENDPOINT = "No listener for endpoint: ";

    /**
     * Answers a path no listener owns with {@code HTTP/1.1 404 Not Found} and the UTF-8 body
     * {@code No listener for endpoint: <path>}, with no {@code Content-Type} (D-066, D-653).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()} as received: undecoded and without the query
     * string, so {@code GET /nope?x=1} answers {@code No listener for endpoint: /nope}. The text is the Mule 3.8
     * listener's no-listener answer as read from its configuration, pinned by a Tier 2A fixture (D-653).
     *
     * <p>Requests answered here:
     * <ul>
     *   <li>every path other than {@code /greet1} … {@code /greet6}, for every HTTP method, for example
     *       {@code /nope} and {@code /favicon.ico};</li>
     *   <li>a mapped path with a trailing slash, such as {@code /greet1/}: Spring MVC 6.1 matches paths exactly;</li>
     *   <li>{@code /error}, which no controller maps (D-485);</li>
     *   <li>{@code OPTIONS} on any of those paths: the body is flushed, which commits the response before
     *       {@code FrameworkServlet.doOptions} can add an {@code Allow} header, and the answer is the same 404
     *       with no {@code Allow} header.</li>
     * </ul>
     *
     * <p>Nothing is logged and no reason phrase is set (D-010).
     *
     * @param e        the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the path;
     *                 its content is not read
     * @param request  the request whose URI no handler maps; the URI forms the body
     * @param response the response that receives the 404 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        RawBody.write(response, 404, (NO_LISTENER_FOR_ENDPOINT + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs any other exception at ERROR and answers {@code HTTP/1.1 500 Internal Server Error} with the exception
     * message as the UTF-8 body, with no {@code Content-Type} (D-066, D-653).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>one ERROR event {@code Exception processing request <request URI>} is written, with the exception and
     *       its stack trace attached;</li>
     *   <li>status 500 and the body {@code String.valueOf(e.getMessage())} are written through
     *       {@link RawBody#write(HttpServletResponse, int, byte[])}: the message text, or {@code null} when the
     *       exception has no message.</li>
     * </ol>
     *
     * <p>No reason phrase is set (D-010). The exception is answered as received: it is not retried, rethrown or
     * wrapped. The exact body of the original's default exception strategy is pinned by Tier 2A fixtures (D-653).
     *
     * <p>Exceptions answered here are those raised by {@code controller/GreetingController} and
     * {@code service/GreetingService} other than the two 404 types, among them an
     * {@link IllegalArgumentException} for an {@code age} that cannot be compared with 18, a malformed XML body on
     * {@code /greet5} or a JSON body on {@code /greet6} that is malformed or not an object, an XPath that matches
     * no node or several nodes, and a malformed {@code %} escape in the query string; and the
     * {@link java.io.UncheckedIOException} of {@code service/GreetingFileWriter} when an output file cannot be
     * written.
     *
     * @param e        the exception raised while the request was handled
     * @param request  the request being handled; its URI is written to the log event
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.error("Exception processing request {}", request.getRequestURI(), e);
        RawBody.write(response, 500, String.valueOf(e.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
