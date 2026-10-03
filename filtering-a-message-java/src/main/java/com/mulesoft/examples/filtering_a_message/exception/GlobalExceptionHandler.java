package com.mulesoft.examples.filtering_a_message.exception;

import com.mulesoft.examples.filtering_a_message.controller.RawBody;
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
 * Answers the exceptions raised while an HTTP request is handled with the responses of the
 * {@code filteringFlow1} listener ({@code allowedMethods="POST"}, {@code path="/"}) and of the
 * flow's default exception strategy.
 *
 * <ul>
 *   <li>{@link #methodNotAllowed}: a method other than {@code POST} on {@code /}, answered with
 *       {@code 405 Method Not Allowed};</li>
 *   <li>{@link #notFound}: a path other than {@code /}, {@code /error} included, answered with
 *       {@code 404 Not Found};</li>
 *   <li>{@link #unexpected}: every other exception, logged at ERROR and answered with
 *       {@code 500 Internal Server Error}.</li>
 * </ul>
 *
 * <p>Spring MVC selects the handler whose declared exception type is closest to the raised
 * exception: {@link HttpRequestMethodNotSupportedException} reaches {@code methodNotAllowed},
 * {@link NoHandlerFoundException} and {@link NoResourceFoundException} reach {@code notFound}, and
 * any other exception reaches {@code unexpected}. Each handler first sets the reason phrase of the
 * status line through {@link ReasonPhrase#set(String)} (D-010), then writes the status,
 * {@code Content-Length} and body bytes through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}. No response carries a
 * {@code Content-Type} header (D-066) or an {@code Allow} header.
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /                    -> HTTP/1.1 405 Method Not Allowed     body: Method not allowed for endpoint: /
 * POST /x                  -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /x
 * GET /error               -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /error
 * POST / (malformed JSON)  -> HTTP/1.1 500 Internal Server Error  body: the exception message
 * }</pre>
 *
 * <p>The class holds no mutable state and serves concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a disallowed method with 405 {@code Method Not Allowed} and the listener text
     * {@code Method not allowed for endpoint: <path>}, without a Content-Type (D-066).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as
     * received, without the query string. The body is UTF-8 encoded. No {@code Allow} header is
     * sent and nothing is logged.
     *
     * @param ex       the exception raised for a method other than {@code POST} on {@code /}, or
     *                 for a method that matches no mapping
     * @param request  the request whose method the listener does not allow
     * @param response the response that receives the 405 status line and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        ReasonPhrase.set(ReasonPhrase.METHOD_NOT_ALLOWED);
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                utf8("Method not allowed for endpoint: " + request.getRequestURI()));
    }

    /**
     * Answers an unmatched path with 404 {@code Not Found} and the listener text
     * {@code No listener for endpoint: <path>}, without a Content-Type (D-066).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as
     * received, without the query string. The body is UTF-8 encoded. Nothing is logged.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException}
     *                 raised for the request
     * @param request  the request whose path no listener serves
     * @param response the response that receives the 404 status line and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ReasonPhrase.set(ReasonPhrase.NOT_FOUND);
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                utf8("No listener for endpoint: " + request.getRequestURI()));
    }

    /**
     * Logs any other exception at ERROR and answers 500 {@code Internal Server Error} with the
     * exception message as the body, without a Content-Type (D-066).
     *
     * <p>The log entry carries {@link Exception#getMessage()} and the stack trace. The body is the
     * UTF-8 encoding of the same message (D-023), or empty with {@code Content-Length: 0} when the
     * message is {@code null}. Exceptions answered here include the {@link java.io.UncheckedIOException}
     * of a malformed JSON body or a JSON root that is not an object, the
     * {@link NullPointerException} of a missing {@code membership}, {@code months} or
     * {@code purchases} key, and the {@link NumberFormatException} of a {@code months} or
     * {@code purchases} value that is not an integer.
     *
     * @param ex       the exception raised while the request was handled
     * @param request  the request whose handling failed
     * @param response the response that receives the 500 status line and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.error(ex.getMessage(), ex);
        ReasonPhrase.set(ReasonPhrase.INTERNAL_SERVER_ERROR);
        String message = ex.getMessage();
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                message == null ? new byte[0] : utf8(message));
    }

    /**
     * Encodes {@code text} as UTF-8.
     *
     * @param text the body text
     * @return the UTF-8 bytes of {@code text}
     */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
