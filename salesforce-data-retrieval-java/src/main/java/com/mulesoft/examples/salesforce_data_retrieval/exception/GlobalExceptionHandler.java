package com.mulesoft.examples.salesforce_data_retrieval.exception;

import com.mulesoft.examples.salesforce_data_retrieval.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions of the HTTP entry points to the responses of the original listener flows and the connector
 * failure modes (D-007, D-020, D-066).
 *
 * <p>The advice applies to every request the {@code DispatcherServlet} serves, including requests for which no
 * handler is found (D-427), and answers through six handler methods, one per branch:
 *
 * <ul>
 *   <li>{@link #upstreamAuthentication(UpstreamAuthenticationException)}: 502 with an {@link ErrorResponse} body
 *       (D-020);</li>
 *   <li>{@link #upstreamRateLimit(UpstreamRateLimitException)}: 429 with an {@link ErrorResponse} body and, when the
 *       upstream system supplied one, its {@code Retry-After} value (D-020);</li>
 *   <li>{@link #upstreamUnavailable(UpstreamUnavailableException)}: 503 with an {@link ErrorResponse} body
 *       (D-020);</li>
 *   <li>{@link #methodNotAllowed(HttpRequestMethodNotSupportedException, HttpServletRequest, HttpServletResponse)}:
 *       a method other than {@code GET} and {@code POST} on {@code /}, answered with 405 (D-427);</li>
 *   <li>{@link #notFound(Exception, HttpServletRequest, HttpServletResponse)}: a path other than {@code /}, answered
 *       with 404 (D-427);</li>
 *   <li>{@link #unexpected(Exception, HttpServletRequest, HttpServletResponse)}: every other exception, answered with
 *       500 after an ERROR log entry (D-007).</li>
 * </ul>
 *
 * <p>{@code unexpected} answers only the exceptions that none of the other five handlers declares (D-427). The
 * three upstream handlers send {@code Content-Type: application/json} and log nothing. The 405, 404 and 500
 * handlers write their body through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the status
 * and {@code Content-Length} and sends no {@code Content-Type} (D-066, D-174). Every status line carries
 * Undertow's standard reason phrase (D-010). The {@code Invalid Salesforce query.} answer of {@code POST /} is
 * produced by the retrieval service and never reaches this advice. No further error answer is defined (D-062).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * PUT /                      -> HTTP/1.1 405 Method Not Allowed     body: Method not allowed for endpoint: /
 * GET /missing               -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /missing
 * GET / (describe fails)     -> HTTP/1.1 500 Internal Server Error  body: <the exception message>
 * GET / (rate limited, 30 s) -> HTTP/1.1 429 Too Many Requests      Retry-After: 30
 *                               body: {"message":"<message>","upstreamSystem":"Salesforce"}
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps {@link UpstreamAuthenticationException} to 502 with an {@link ErrorResponse} body (D-020).
     *
     * <p>The body is {@code {"message":"<message>","upstreamSystem":"<system>"}} with
     * {@code Content-Type: application/json}. Nothing is logged.
     *
     * @param ex the authentication or authorization failure reported by the upstream system
     * @return the 502 response carrying the exception's message and upstream system name
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public ResponseEntity<ErrorResponse> upstreamAuthentication(UpstreamAuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.upstreamSystem()));
    }

    /**
     * Maps {@link UpstreamRateLimitException} to 429 with {@code Retry-After} and an {@link ErrorResponse} body
     * (D-020).
     *
     * <p>The {@code Retry-After} header carries {@link UpstreamRateLimitException#retryAfter()} exactly as the
     * upstream system sent it, delay-seconds or an HTTP-date, and is absent when that value is {@code null}. The
     * body is {@code {"message":"<message>","upstreamSystem":"<system>"}} with
     * {@code Content-Type: application/json}. Nothing is logged.
     *
     * @param ex the rate-limit answer reported by the upstream system
     * @return the 429 response carrying the exception's message, upstream system name and optional retry delay
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<ErrorResponse> upstreamRateLimit(UpstreamRateLimitException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        String retryAfter = ex.retryAfter();
        if (retryAfter != null) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorResponse(ex.getMessage(), ex.upstreamSystem()));
    }

    /**
     * Maps {@link UpstreamUnavailableException} to 503 with an {@link ErrorResponse} body (D-020).
     *
     * <p>The body is {@code {"message":"<message>","upstreamSystem":"<system>"}} with
     * {@code Content-Type: application/json}. Nothing is logged.
     *
     * @param ex the timeout or connectivity failure reported for the upstream system
     * @return the 503 response carrying the exception's message and upstream system name
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> upstreamUnavailable(UpstreamUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.upstreamSystem()));
    }

    /**
     * Answers a method other than {@code GET} and {@code POST} on {@code /} with 405 and the body
     * {@code Method not allowed for endpoint: <path>} (D-427), with no {@code Content-Type} (D-066) and no
     * {@code Allow} header.
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as received, without the
     * query string. The body is encoded as UTF-8. Nothing is logged.
     *
     * @param ex the exception Spring or the retrieval controller raised for the unsupported method
     * @param request the request whose method the path does not accept
     * @param response the response that receives the 405 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                ("Method not allowed for endpoint: " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a path other than {@code /} with 404 and the body {@code No listener for endpoint: <path>} (D-427),
     * with no {@code Content-Type} (D-066).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as received, without the
     * query string. The body is encoded as UTF-8. Nothing is logged.
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the request
     * @param request the request whose path no listener serves
     * @param response the response that receives the 404 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                ("No listener for endpoint: " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs the exception at ERROR and answers 500 with its message as the body, without a {@code Content-Type}
     * (D-007, D-066).
     *
     * <p>The log entry reads {@code Exception caught while processing <method> <path>} and carries the exception and
     * its stack trace (D-427). The body is {@link Exception#getMessage()} encoded as UTF-8, or empty when the message
     * is {@code null}. A describe-global failure of {@code GET /} is among the exceptions answered here.
     *
     * @param ex the exception raised while the request was handled
     * @param request the request that failed
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.error("Exception caught while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        String message = ex.getMessage();
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                (message == null ? "" : message).getBytes(StandardCharsets.UTF_8));
    }
}
