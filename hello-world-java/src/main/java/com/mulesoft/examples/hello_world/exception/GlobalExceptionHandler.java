package com.mulesoft.examples.hello_world.exception;

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

import com.mulesoft.examples.hello_world.controller.RawBody;

/**
 * Maps request failures of the HTTP listener to the listener's own responses.
 *
 * <p>The advice applies to every controller of the application, the listener
 * {@code HTTP_Listener_Configuration} with its single flow {@code HelloWorldFlow1} on
 * {@code /helloWorld} [hello-world/src/main/app/HelloWorld.xml:3-7]. It has exactly two handlers:
 * <ul>
 *   <li>{@link #noListenerForEndpoint} answers a path without a handler
 *       ({@link NoHandlerFoundException} or {@link NoResourceFoundException}, raised through
 *       {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 *       {@code spring.web.resources.add-mappings: false}) with 404;</li>
 *   <li>{@link #unexpected} answers every other exception with 500.</li>
 * </ul>
 * Spring selects the handler whose declared exception type is closest to the thrown one, so the two
 * 404 types never reach {@link #unexpected}.
 *
 * <p>Both responses are written through {@link RawBody#write}, which sets the status and
 * {@code Content-Length} and no {@code Content-Type} (D-066). The 404 reason phrase is set through
 * {@link ReasonPhrase#set}, which reaches the status line through Undertow's exchange (D-010).
 * Example exchanges:
 * <pre>
 * GET /nope?x=1 -&gt; HTTP/1.1 404 No listener for endpoint: /nope
 *                  Content-Length: 19
 *
 *                  Resource not found.
 *
 * failure with message "boom" -&gt; HTTP/1.1 500 Internal Server Error
 *                                Content-Length: 4
 *
 *                                boom
 * </pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Format of the 404 reason phrase; {@code %s} receives the request path without the query string. */
    static final String NO_LISTENER_FOR_ENDPOINT = "No listener for endpoint: %s";

    /** Body of the 404 response. */
    static final String RESOURCE_NOT_FOUND = "Resource not found.";

    /**
     * Answers an unmatched path with 404, the reason phrase {@code No listener for endpoint: <path>}
     * and the body {@code Resource not found.}, without a Content-Type (D-010, D-066).
     *
     * <p>The status is set first, the reason phrase second and the body last; writing the body
     * commits the response. {@code <path>} is {@link HttpServletRequest#getRequestURI()}, so
     * {@code /nope?x=1} yields {@code /nope}. Nothing is logged.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for
     *                 the unmatched path
     * @param request  the unmatched request
     * @param response the response that receives status, reason phrase and body
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListenerForEndpoint(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(404);
        ReasonPhrase.set(String.format(NO_LISTENER_FOR_ENDPOINT, request.getRequestURI()));
        RawBody.write(response, 404, RESOURCE_NOT_FOUND.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers any other exception with 500 and the exception message as the body, without a
     * Content-Type; logs it at ERROR.
     *
     * <p>The body is the UTF-8 encoding of {@link String#valueOf(Object)} of the message, so a
     * {@code null} message is written as {@code null}. No reason phrase is set, so the status line
     * carries Undertow's standard {@code Internal Server Error}. A response that is already committed
     * is left unchanged; the exception is still logged.
     *
     * @param ex       the exception raised while the request was processed
     * @param response the response that receives status and body
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Exception while processing request", ex);
        if (!response.isCommitted()) {
            RawBody.write(response, 500, String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
