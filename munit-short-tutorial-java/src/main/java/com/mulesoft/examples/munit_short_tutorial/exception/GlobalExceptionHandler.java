package com.mulesoft.examples.munit_short_tutorial.exception;

import com.mulesoft.examples.munit_short_tutorial.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers the HTTP listener's own error cases of {@code munit-short-tutorial} and the
 * default-strategy 500, with no Content-Type (D-062, D-066).
 *
 * <p>The original binds one listener, {@code HTTP_Listener_Configuration} on
 * {@code 0.0.0.0:${http.port}} [munit-short-tutorial/src/main/app/production-code.xml:4], with the
 * single flow {@code exampleFlow} on {@code path="/" allowedMethods="GET"} (:7), and defines no
 * exception strategy (:1-41). The advice has no {@code assignableTypes} or {@code basePackages}
 * selector and maps exactly three cases, one handler method each:
 *
 * <pre>{@code
 * GET /x?url_key=1          -> 404 Not Found              No listener for endpoint: /x
 * GET /error                -> 404 Not Found              No listener for endpoint: /error
 * POST, PUT, PATCH, DELETE,
 * HEAD, OPTIONS or TRACE /  -> 405 Method Not Allowed     Method not allowed for endpoint: /
 * GET /, service fails      -> 500 Internal Server Error  the exception message
 * }</pre>
 *
 * <p>Every handler sets the status, then the reason phrase through {@link ReasonPhrase#set(String)}
 * (D-010), then writes the UTF-8 body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}. No response carries a
 * {@code Content-Type} or an {@code Allow} header, and the request path in a body is
 * {@link HttpServletRequest#getRequestURI()} as received, without the query string (D-615).
 *
 * <p>A {@link NoHandlerFoundException} or {@link NoResourceFoundException} is answered by
 * {@link #noListener(Exception, HttpServletRequest, HttpServletResponse)}, a
 * {@link HttpRequestMethodNotSupportedException} by
 * {@link #methodNotAllowed(HttpRequestMethodNotSupportedException, HttpServletRequest,
 * HttpServletResponse)}, and every other exception by
 * {@link #unexpected(Exception, HttpServletRequest, HttpServletResponse)}, the only handler that
 * logs. The bean holds no request state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Fixed start of the 404 body; the request path follows it. */
    static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Fixed start of the 405 body; the request path follows it. */
    static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Reason phrase of the 404 status line. */
    static final String NOT_FOUND = "Not Found";

    /** Reason phrase of the 405 status line. */
    static final String METHOD_NOT_ALLOWED = "Method Not Allowed";

    /** Reason phrase of the 500 status line. */
    static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers 404 {@code Not Found} with body {@code No listener for endpoint: <request path>}.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException}
     *                 raised for the unmatched path; it is not read
     * @param request  the HTTP request; only its request URI is read
     * @param response the HTTP response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        // Status line reason phrase (D-010).
        ReasonPhrase.set(NOT_FOUND);
        String body = NO_LISTENER_PREFIX + request.getRequestURI();
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers 405 {@code Method Not Allowed} with body
     * {@code Method not allowed for endpoint: <request path>}.
     *
     * @param ex       the exception raised for the method outside {@code allowedMethods="GET"}; it
     *                 is not read
     * @param request  the HTTP request; only its request URI is read
     * @param response the HTTP response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        // Status line reason phrase (D-010).
        ReasonPhrase.set(METHOD_NOT_ALLOWED);
        String body = METHOD_NOT_ALLOWED_PREFIX + request.getRequestURI();
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers 500 {@code Internal Server Error} with body {@code ex.getMessage()}, or an empty body
     * when the message is {@code null}.
     *
     * <p>The exception is logged at ERROR, once and with its stack trace, as
     * {@code Request to <request path> failed}, before the response is written.
     *
     * @param ex       any exception raised while handling a request and not mapped by another
     *                 handler of this class
     * @param request  the HTTP request; only its request URI is read
     * @param response the HTTP response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.error("Request to {} failed", request.getRequestURI(), ex);
        String message = ex.getMessage();
        byte[] body = (message == null) ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        // Status line reason phrase (D-010).
        ReasonPhrase.set(INTERNAL_SERVER_ERROR);
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
    }
}
