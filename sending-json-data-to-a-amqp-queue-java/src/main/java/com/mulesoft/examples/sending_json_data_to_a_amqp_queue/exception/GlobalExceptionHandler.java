package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.exception;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.controller.RawBody;
import jakarta.servlet.ServletException;
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
 * Answers the HTTP errors of the {@code POST /} listener of flow {@code json-to-rabbitmqFlow} with
 * plain-text bodies and custom status-line reason phrases, without a {@code Content-Type} header.
 * See D-007, D-010, D-066 and D-490 ({@code sending-json-data-to-a-amqp-queue-java: listener
 * 404/405 bodies}).
 *
 * <p>Answers, by exception type:
 * <ul>
 *   <li>{@link HttpRequestMethodNotSupportedException}, raised for every method other than POST on
 *       {@code /}: {@code 405 Method Not Allowed} with body
 *       {@code Method not allowed for endpoint: <target>};</li>
 *   <li>{@link NoHandlerFoundException} and {@link NoResourceFoundException}, raised for every other
 *       path: {@code 404 Not Found} with body {@code No listener for endpoint: <target>};</li>
 *   <li>any other {@link Exception}: an ERROR log entry naming the request method and target, with
 *       the stack trace, then {@code 500 Internal Server Error} with the exception message as body,
 *       or the exception class name when the message is {@code null}.</li>
 * </ul>
 *
 * <p>{@code <target>} is the request URI as received, followed by {@code ?} and the query string
 * when the request has one; neither part is decoded or re-encoded. Every body is written as UTF-8
 * bytes through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets
 * {@code Content-Length} and no other header. No {@code Allow} header is set and
 * {@link HttpServletResponse#sendError(int)} is never called.
 *
 * <p>The advice has no {@code assignableTypes} or {@code basePackages} selector and also receives
 * the exceptions of requests that matched no controller. The 405 and 404 exception types are
 * answered by their own handler methods and never reach
 * {@link #unexpected(Exception, HttpServletRequest, HttpServletResponse)}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the ERROR event written by {@code unexpected}. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason phrase of the 405 status line. */
    private static final String METHOD_NOT_ALLOWED_PHRASE = "Method Not Allowed";

    /** Reason phrase of the 404 status line. */
    private static final String NOT_FOUND_PHRASE = "Not Found";

    /** Reason phrase of the 500 status line. */
    private static final String INTERNAL_SERVER_ERROR_PHRASE = "Internal Server Error";

    /** Text of the 405 body before the request target. */
    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Text of the 404 body before the request target. */
    private static final String NOT_FOUND_PREFIX = "No listener for endpoint: ";

    /**
     * Answers 405 with reason {@code Method Not Allowed} and a plain-text body naming the request
     * target, for example {@code Method not allowed for endpoint: /} for {@code GET /}.
     *
     * @param ex       the method mismatch raised by Spring MVC or by {@code SalesController}
     * @param request  the rejected request
     * @param response the response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_PHRASE,
                METHOD_NOT_ALLOWED_PREFIX + target(request));
    }

    /**
     * Answers 404 with reason {@code Not Found} and a plain-text body naming the request target,
     * for example {@code No listener for endpoint: /x?a=1} for {@code POST /x?a=1}.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for a path without a handler
     * @param request  the unmatched request
     * @param response the response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(ServletException ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        write(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_PHRASE, NOT_FOUND_PREFIX + target(request));
    }

    /**
     * Logs {@code ex} at ERROR with the request method, the request target and the stack trace, then
     * answers 500 with reason {@code Internal Server Error} and the exception message as plain-text
     * body, or the exception class name when the message is {@code null}. This is the default
     * exception strategy of flow {@code json-to-rabbitmqFlow}, which defines no exception strategy.
     *
     * @param ex       the exception that left the request handling
     * @param request  the failed request
     * @param response the response the answer is written to
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.error("Request {} {} failed", request.getMethod(), target(request), ex);
        String message = ex.getMessage();
        String body = message != null ? message : ex.getClass().getName();
        write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR_PHRASE, body);
    }

    /**
     * Writes {@code code}, then {@code phrase} as the status-line reason phrase, then the UTF-8 bytes
     * of {@code body} through {@link RawBody#write(HttpServletResponse, int, byte[])}. A response
     * that is already committed is left as sent and nothing is written.
     *
     * @param response the response the answer is written to
     * @param code     the HTTP status code, set on the response and passed to {@code RawBody.write}
     * @param phrase   the reason phrase of the status line
     * @param body     the body text, sent as UTF-8 bytes
     * @throws IOException if the body cannot be written
     */
    private static void write(HttpServletResponse response, int code, String phrase, String body)
            throws IOException {
        // A committed response keeps the status line and bytes already sent; a second status, phrase
        // or body is not written to it.
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(code);
        ReasonPhrase.set(phrase);
        RawBody.write(response, code, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the request URI as received, followed by {@code ?} and the query string when the
     * request has one, for example {@code /x?a=1}.
     *
     * @param request the request whose target is returned
     * @return the request URI, with {@code ?} and the query string when present
     */
    private static String target(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
