package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

import com.mulesoft.examples.get_customer_list_from_netsuite.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates the exceptions raised while an HTTP request is handled into the responses of the Mule flow
 * {@code get-customer-list-from-netsuiteFlow}, whose listener serves {@code /customers} and which defines no
 * exception strategy (D-444). One handler method answers each case:
 *
 * <ul>
 *   <li>{@link #upstreamAuthentication}: NetSuite rejected authentication after the single re-authenticated
 *       retry of the client; 502 with an {@link ErrorResponse} JSON body (D-020, D-007).</li>
 *   <li>{@link #upstreamRateLimit}: NetSuite answered with a rate limit; 429 with an {@link ErrorResponse} JSON
 *       body and the vendor {@code Retry-After} value, when NetSuite sent one (D-020, D-007).</li>
 *   <li>{@link #upstreamUnavailable}: NetSuite timed out or could not be reached; 503 with an
 *       {@link ErrorResponse} JSON body (D-020, D-007).</li>
 *   <li>{@link #notFound}: a path no controller maps, the listener 404; empty body and no {@code Content-Type}
 *       (D-444).</li>
 *   <li>{@link #unexpected}: every other exception, the default HTTP strategy of the flow; 500 with the exception
 *       message as body and no {@code Content-Type} (D-066).</li>
 * </ul>
 *
 * <p>The advice has no {@code assignableTypes}, {@code basePackages} or {@code annotations} selector: it applies
 * to every handler of the application and to requests for which no handler exists. Spring MVC selects the handler
 * method whose declared exception type is closest to the raised exception: the three upstream exceptions and the
 * two no-handler exceptions reach their own methods, and every other exception, among them
 * {@link IllegalArgumentException}, {@link IllegalStateException} and the Spring MVC framework exceptions, reaches
 * {@link #unexpected}. No handler retries a call or sets a custom reason phrase: the status lines carry Undertow's
 * standard phrases (D-010), and the client is the only class that retries (D-020).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /customers?lastName=a, NetSuite answers 401 twice
 *   -> 502 Bad Gateway, Content-Type: application/json
 *      {"message":"NetSuite authentication failed","upstreamSystem":"NetSuite"}
 * GET /customers?lastName=a, NetSuite answers 429 with Retry-After: 30
 *   -> 429 Too Many Requests, Content-Type: application/json, Retry-After: 30
 *      {"message":"NetSuite rate limit exceeded","upstreamSystem":"NetSuite"}
 * GET /customers?lastName=a, NetSuite times out
 *   -> 503 Service Unavailable, Content-Type: application/json
 *      {"message":"NetSuite unavailable","upstreamSystem":"NetSuite"}
 * GET /unknown
 *   -> 404 Not Found, empty body, no Content-Type
 * GET /customers?lastName=a, NetSuite answers 500
 *   -> 500 Internal Server Error, the exception message as body, no Content-Type
 * }</pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests. This project carries its own copy of
 * the class (D-004).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives one ERROR entry for every exception a handler method of this class answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers an authentication failure of an upstream system with 502 and an {@link ErrorResponse} JSON body
     * (D-020, D-007).
     *
     * <p>The body is {@code new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem())}, for example
     * {@code {"message":"NetSuite authentication failed","upstreamSystem":"NetSuite"}}, sent with
     * {@code Content-Type: application/json} whatever the request's {@code Accept} header. One ERROR entry names
     * the upstream system and carries the exception and its stack trace.
     *
     * @param ex the failure raised after the single re-authenticated retry of the client
     * @return status 502, {@code Content-Type: application/json} and the {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public ResponseEntity<ErrorResponse> upstreamAuthentication(UpstreamAuthenticationException ex) {
        log.error("Upstream authentication failed; upstreamSystem={}; answering 502",
                printable(ex.getUpstreamSystem()), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a rate limit of an upstream system with 429 and an {@link ErrorResponse} JSON body, copying the
     * vendor {@code Retry-After} value (D-020, D-007).
     *
     * <p>The body is {@code new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem())}, for example
     * {@code {"message":"NetSuite rate limit exceeded","upstreamSystem":"NetSuite"}}, sent with
     * {@code Content-Type: application/json}. When {@link UpstreamRateLimitException#getRetryAfter()} is not
     * {@code null}, the response carries one {@code Retry-After} header with that value exactly as the vendor sent
     * it, for example {@code 30} or {@code Wed, 21 Oct 2026 07:28:00 GMT}; otherwise the response has no
     * {@code Retry-After} header. One ERROR entry names the upstream system and the {@code Retry-After} value and
     * carries the exception and its stack trace.
     *
     * @param ex the rate-limit failure, carrying the vendor {@code Retry-After} value or {@code null}
     * @return status 429, {@code Content-Type: application/json}, the {@code Retry-After} header when the vendor
     *     sent one, and the {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<ErrorResponse> upstreamRateLimit(UpstreamRateLimitException ex) {
        String retryAfter = ex.getRetryAfter();
        log.error("Upstream rate limit exceeded; upstreamSystem={}; Retry-After={}; answering 429",
                printable(ex.getUpstreamSystem()), printable(retryAfter), ex);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a timeout or connectivity failure of an upstream system with 503 and an {@link ErrorResponse} JSON
     * body (D-020, D-007).
     *
     * <p>The body is {@code new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem())}, for example
     * {@code {"message":"NetSuite unavailable","upstreamSystem":"NetSuite"}}, sent with
     * {@code Content-Type: application/json}. The failed call is not re-sent. One ERROR entry names the upstream
     * system and carries the exception and its stack trace.
     *
     * @param ex the failure of the single attempt to reach the upstream system
     * @return status 503, {@code Content-Type: application/json} and the {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> upstreamUnavailable(UpstreamUnavailableException ex) {
        log.error("Upstream unavailable; upstreamSystem={}; answering 503", printable(ex.getUpstreamSystem()), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a request whose path no controller maps, the listener 404, with an empty body and no
     * {@code Content-Type} (D-444).
     *
     * <p>Spring MVC raises {@link NoResourceFoundException} for such a request when static resource mappings are
     * enabled, its default, and {@link NoHandlerFoundException} when they are disabled and
     * {@code spring.mvc.throw-exception-if-no-handler-found} is {@code true}; both reach this method. For example,
     * {@code GET /unknown} answers {@code HTTP/1.1 404 Not Found} with {@code Content-Length: 0}. One ERROR entry
     * carries the exception message, which names the unmatched path, with control characters escaped.
     *
     * @param ex the {@link NoResourceFoundException} or {@link NoHandlerFoundException} raised for the request
     * @return status 404 with no body and no {@code Content-Type}
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Void> notFound(Exception ex) {
        log.error("No handler for the request; answering 404: {}", printable(ex.getMessage()));
        return ResponseEntity.notFound().build();
    }

    /**
     * Answers every other exception with 500 and the exception message as body, with no {@code Content-Type}: the
     * default HTTP strategy of the flow (D-066).
     *
     * <p>One ERROR entry carries the exception and its stack trace. The body is the UTF-8 encoding of
     * {@link Exception#getMessage()}, or empty when the message is {@code null}, written through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the status, {@code Content-Length} and
     * the body bytes only. A {@code Content-Type} that the failed request left on the uncommitted response is
     * cleared with {@link HttpServletResponse#reset()} before the write. When the response is already committed,
     * the method returns after logging and writes nothing.
     *
     * <p>Exceptions answered here include a NetSuite failure other than an authentication failure, a rate limit or
     * an unreachable service, an {@link IllegalArgumentException} or {@link IllegalStateException} raised while the
     * request is handled, and the Spring MVC framework exceptions. The exception is answered as received: it is not
     * retried, rethrown or wrapped.
     *
     * @param exception the exception raised while the request was handled
     * @param response  the response the 500 is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception exception, HttpServletResponse response) throws IOException {
        log.error("Unhandled exception while handling the request; answering 500", exception);
        if (response.isCommitted()) {
            return;
        }
        String message = exception.getMessage();
        byte[] bytes = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        // Clears a Content-Type the failed request left on the response; the 500 carries none (D-066, D-444).
        if (response.getContentType() != null) {
            response.reset();
        }
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, bytes);
    }

    /**
     * Returns {@code value} for a log entry, with each ISO control character, among them CR, LF and TAB, replaced
     * by a backslash, the letter {@code u} and its four-digit lowercase hexadecimal code.
     *
     * @param value the text to log; may be {@code null}
     * @return the escaped text, {@code value} itself when it holds no control character, or {@code null} for a
     *     {@code null} value
     */
    private static String printable(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                if (escaped == null) {
                    escaped = new StringBuilder(value.length() + 16).append(value, 0, i);
                }
                escaped.append(String.format("\\u%04x", (int) c));
            } else if (escaped != null) {
                escaped.append(c);
            }
        }
        return escaped == null ? value : escaped.toString();
    }
}
