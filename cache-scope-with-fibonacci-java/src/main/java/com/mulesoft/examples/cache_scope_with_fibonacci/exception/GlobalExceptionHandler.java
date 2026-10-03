package com.mulesoft.examples.cache_scope_with_fibonacci.exception;

import com.mulesoft.examples.cache_scope_with_fibonacci.controller.RawBody;
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
 * Translates exceptions raised while serving HTTP requests into the answers of the Mule listener
 * {@code HTTP_Listener_Configuration}, which serves only the flow {@code cache-exampleFlow1} on the path
 * {@code fibonacci} (D-434).
 *
 * <p>The advice applies to every handler of the application and to requests for which no handler exists. It
 * answers through exactly two handler methods:
 *
 * <ul>
 *   <li>{@link #notFound(HttpServletRequest, HttpServletResponse)}: a path the listener does not own is answered
 *       with the listener's unmatched-path 404, reason {@code Not Found} and the body
 *       {@code No listener for endpoint: <path>} (D-227);</li>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every other exception is logged at ERROR and answered
 *       with the default exception strategy's 500 and the exception message as the body.</li>
 * </ul>
 *
 * <p>With {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 * {@code spring.web.resources.add-mappings: false} in {@code application.yml}, an unmapped path raises a
 * {@link NoHandlerFoundException} or a {@link NoResourceFoundException}. Spring MVC selects the handler whose
 * declared exception type is closest to the raised one: those two types reach {@code notFound}, and every other
 * exception, including a {@link NumberFormatException} for a non-numeric {@code n} and an
 * {@link ArithmeticException} from the calculation, reaches {@code unexpected}.
 *
 * <p>Both bodies are written through {@link RawBody#write(HttpServletResponse, int, byte[])}: the response carries
 * {@code Content-Length} and no {@code Content-Type} header (D-066). Example exchanges:
 *
 * <pre>{@code
 * GET /other            -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /other
 * GET /favicon.ico      -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /favicon.ico
 * GET /fibonacci?n=abc  -> HTTP/1.1 500 Internal Server Error  body: For input string: "abc"
 * }</pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the one ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason phrase written on the status line of the unmatched-path 404 (D-010). */
    static final String NOT_FOUND_REASON = "Not Found";

    /** Prefix of the unmatched-path 404 body; the request path follows it. */
    static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /**
     * Answers a path the listener does not own with {@code HTTP/1.1 404 Not Found} and the body
     * {@code No listener for endpoint: <path>}, with no {@code Content-Type} (D-066, D-227).
     *
     * <p>The steps, in order: the status is set to 404, the reason phrase {@code Not Found} is written to the
     * Undertow exchange through {@link ReasonPhrase#set(String)} (D-010), and the body is written through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}. {@code <path>} is
     * {@link HttpServletRequest#getRequestURI()} as received, without the query string and without decoding: for
     * example {@code GET /other?n=1} answers {@code No listener for endpoint: /other}. The body is UTF-8 encoded.
     * Nothing is logged.
     *
     * @param request  the request whose path no handler maps
     * @param response the response that receives the 404 status, the reason phrase and the body
     * @throws IOException           if the body cannot be written to the response
     * @throws IllegalStateException if no Undertow request is active on the calling thread
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(404);
        ReasonPhrase.set(NOT_FOUND_REASON);
        RawBody.write(response, 404, (NO_LISTENER_PREFIX + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs any other exception at ERROR and answers {@code HTTP/1.1 500 Internal Server Error} with the exception
     * message as the body, with no {@code Content-Type} (D-066, D-434).
     *
     * <p>The log entry is one ERROR event with the text
     * {@code Exception while processing the HTTP request: <message>} and the exception with its stack trace
     * attached. The body is the UTF-8 encoding of {@link Exception#getMessage()}, or empty, with
     * {@code Content-Length: 0}, when the message is {@code null}. No reason phrase is set: the status line carries
     * Undertow's standard phrase for 500. The exception is answered as received: it is not retried, rethrown or
     * wrapped.
     *
     * <p>Exceptions answered here include the {@link NumberFormatException} raised for a non-numeric {@code n}
     * ({@code GET /fibonacci?n=abc}), the {@link ArithmeticException} raised by the calculation, and Spring MVC
     * request-binding failures.
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Exception while processing the HTTP request: {}", ex.getMessage(), ex);
        byte[] body = ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, 500, body);
    }
}
