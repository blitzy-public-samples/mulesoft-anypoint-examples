package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller.RawBody;

/**
 * Maps unmatched paths to 404 {@code No listener for endpoint: <uri>} with body {@code Resource not found.}, and
 * every other exception to 500 with the root-cause message; neither response has a Content-Type (D-010, D-066,
 * D-023).
 *
 * <p>The advice applies to every controller of the application, which answers the listener
 * {@code HTTP_Listener_Configuration} with its single flow {@code main} on {@code /}
 * [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:4-29]. It has exactly two handlers:
 * <ul>
 *   <li>{@link #noListenerForEndpoint} answers a path without a handler ({@link NoHandlerFoundException} or
 *       {@link NoResourceFoundException}, raised through {@code spring.mvc.throw-exception-if-no-handler-found: true}
 *       and {@code spring.web.resources.add-mappings: false}) with 404 (D-563);</li>
 *   <li>{@link #unexpected} answers every other exception, for example the {@link IllegalArgumentException} of an
 *       invalid JSON body or the {@link java.io.UncheckedIOException} of a failed FTP upload, with 500 (D-564).</li>
 * </ul>
 * Spring selects the handler whose declared exception type is closest to the thrown one, so the two 404 types never
 * reach {@link #unexpected}.
 *
 * <p>Both responses are written through {@link RawBody#write}, which sets the status and {@code Content-Length} and
 * no {@code Content-Type} (D-066, D-438). The 404 reason phrase is set through {@link ReasonPhrase#set}, which
 * reaches the status line through Undertow's exchange (D-010, D-420). The 404 and 500 texts are interim values that
 * the Tier 2A fixtures pin (D-023). Example exchanges:
 * <pre>
 * GET /x?a=1 -&gt; HTTP/1.1 404 No listener for endpoint: /x?a=1
 *               Content-Length: 19
 *
 *               Resource not found.
 *
 * new UncheckedIOException(new IOException("Connection refused"))
 *            -&gt; HTTP/1.1 500 Internal Server Error
 *               Content-Length: 18
 *
 *               Connection refused
 * </pre>
 *
 * <p>This project's own copy of the pattern (D-004). The class holds no mutable state and is safe for concurrent
 * requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Prefix of the 404 reason phrase; the raw request URI, with {@code ?<query>} when present, follows it. */
    static final String NO_LISTENER_FOR_ENDPOINT = "No listener for endpoint: ";

    /** Body of the 404 response. */
    static final String RESOURCE_NOT_FOUND = "Resource not found.";

    /**
     * Answers an unmatched path with 404, the reason phrase {@code No listener for endpoint: <uri>} and the body
     * {@code Resource not found.}, without a Content-Type (D-563, D-010, D-066, D-023).
     *
     * <p>The status is set first, the reason phrase second and the body last; writing the body commits the response.
     * {@code <uri>} is {@link HttpServletRequest#getRequestURI()} followed, when
     * {@link HttpServletRequest#getQueryString()} is not {@code null}, by {@code ?} and the query string, both as
     * received and undecoded: {@code GET /x} yields {@code /x} and {@code GET /x?a=1} yields {@code /x?a=1}. Nothing
     * is logged.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the
     *                 unmatched path
     * @param request  the unmatched request
     * @param response the response that receives status, reason phrase and body
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListenerForEndpoint(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(404);
        ReasonPhrase.set(NO_LISTENER_FOR_ENDPOINT + requestTarget(request));
        RawBody.write(response, 404, RESOURCE_NOT_FOUND.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers any other exception with 500 and the root-cause message as the body, without a Content-Type; logs the
     * exception at ERROR (D-564, D-066, D-023).
     *
     * <p>The exception is logged with its stack trace as {@code Message processing failed in flow main}. A response
     * that is already committed is then left unchanged. Otherwise the body is the UTF-8 encoding of the message of
     * the root cause, the last throwable of the {@link Throwable#getCause()} chain starting at {@code ex}; a cause
     * chain that loops ends at the first throwable it reaches a second time. A root cause with a {@code null}
     * message gives its class name, for example {@code java.lang.IllegalStateException}. No reason phrase is set,
     * so the status line carries Undertow's standard {@code Internal Server Error}.
     *
     * @param ex       the exception raised while the request was processed
     * @param response the response that receives status and body
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("Message processing failed in flow main", ex);
        if (response.isCommitted()) {
            return;
        }
        Throwable root = rootCause(ex);
        String text = root.getMessage() != null ? root.getMessage() : root.getClass().getName();
        RawBody.write(response, 500, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the raw request URI, followed by {@code ?} and the raw query string when the request has one.
     *
     * @param request the request whose target is read
     * @return the request target as received, for example {@code /x?a=1}
     */
    private static String requestTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        return query != null ? uri + "?" + query : uri;
    }

    /**
     * Returns the last throwable of the cause chain starting at {@code ex}. Throwables are compared by identity; a
     * chain that returns to a throwable already visited ends at that throwable.
     *
     * @param ex the first throwable of the chain, not {@code null}
     * @return the root cause, or {@code ex} itself when it has no cause
     */
    private static Throwable rootCause(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = ex;
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }
}
