package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.controller.RawBody;
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

/**
 * Maps the connector failure modes to 502, 429 and 503 JSON responses (D-007, D-020) and every other
 * exception to the default 500 response without Content-Type (D-066).
 *
 * <p>The advice applies to every handler of the application, among them the controllers of the
 * listener flow {@code salesforce-oauthFlow1} on {@code /} and of the OAuth callback on
 * {@code /oauth2callback}, and to requests for which no handler exists. It answers through four
 * handler methods:
 *
 * <ul>
 *   <li>{@link #upstreamAuthentication(UpstreamAuthenticationException)}: Salesforce authentication
 *       failed after one re-authentication, answered with 502 and an {@link ErrorResponse} body;</li>
 *   <li>{@link #upstreamRateLimit(UpstreamRateLimitException)}: Salesforce applied a rate limit,
 *       answered with 429, an {@link ErrorResponse} body and the vendor {@code Retry-After} value
 *       when one was received;</li>
 *   <li>{@link #upstreamUnavailable(UpstreamUnavailableException)}: Salesforce timed out or could
 *       not be reached, answered with 503 and an {@link ErrorResponse} body;</li>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every other exception, answered as the
 *       Mule default exception strategy answers the listener: 500 with the exception text as the
 *       body, after an ERROR log entry. Spring MVC's own request exceptions keep their status with an
 *       empty body.</li>
 * </ul>
 *
 * <p>Spring MVC selects the handler whose declared exception type is closest to the raised
 * exception: the three upstream exception types reach their own methods and every other exception
 * reaches {@code unexpected}. No handler retries a call or sets a custom reason phrase; the status
 * line carries Undertow's standard phrase (D-010).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /  (re-authentication failed)  -> 502  Content-Type: application/json
 *                                       {"message":"<message>","upstreamSystem":"Salesforce"}
 * GET /  (rate limit, Retry-After 30) -> 429  Content-Type: application/json, Retry-After: 30
 * GET /  (timeout)                   -> 503  Content-Type: application/json
 * GET /oauth2callback (no code)      -> 500  no Content-Type, body: <exception message>
 * GET /unknown                       -> 404  no Content-Type, empty body
 * POST /oauth2callback               -> 405  no Content-Type, empty body
 * }</pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception that {@link #unexpected} answers with 500. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a Salesforce authentication failure that persisted after one re-authentication with
     * HTTP 502 (D-020).
     *
     * <p>The body is the JSON {@link ErrorResponse} {@code {"message": <ex message>, "upstreamSystem":
     * <ex upstream system>}} with {@code Content-Type: application/json}; {@code upstreamSystem} is
     * omitted when it is {@code null} (D-007). Nothing is retried or logged here.
     *
     * @param ex the authentication failure raised by the Salesforce clients
     * @return the 502 response carrying the JSON error body
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public ResponseEntity<ErrorResponse> upstreamAuthentication(UpstreamAuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a Salesforce rate limit with HTTP 429 (D-020).
     *
     * <p>The body is the JSON {@link ErrorResponse} built from the exception's message and upstream
     * system, with {@code Content-Type: application/json} (D-007). A {@code Retry-After} header
     * carrying {@link UpstreamRateLimitException#getRetryAfter()} unchanged is added only when that
     * value is present and not blank; otherwise the response has no {@code Retry-After} header at all.
     * Nothing is retried or logged here.
     *
     * <p>Example: a {@code retryAfter} of {@code "30"} answers {@code 429} with
     * {@code Retry-After: 30}; a {@code null} {@code retryAfter} answers {@code 429} without the
     * header.
     *
     * @param ex the rate-limit failure raised by the Salesforce clients
     * @return the 429 response carrying the JSON error body and, when received, the vendor
     *     {@code Retry-After} value
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<ErrorResponse> upstreamRateLimit(UpstreamRateLimitException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        String retryAfter = ex.getRetryAfter();
        // A null or blank vendor value sends no Retry-After header, never an empty one.
        if (retryAfter != null && !retryAfter.isBlank()) {
            builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return builder.body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a Salesforce timeout or connectivity failure with HTTP 503 (D-020).
     *
     * <p>The body is the JSON {@link ErrorResponse} built from the exception's message and upstream
     * system, with {@code Content-Type: application/json} (D-007). The failed request is not re-sent
     * and nothing is logged here.
     *
     * @param ex the timeout or connectivity failure raised by the Salesforce clients
     * @return the 503 response carrying the JSON error body
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> upstreamUnavailable(UpstreamUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers every other exception as the default exception strategy of
     * {@code salesforce-oauthFlow1} answers the HTTP listener.
     *
     * <p>Two outcomes, decided in this order:
     * <ol>
     *   <li>An exception that implements Spring's {@code org.springframework.web.ErrorResponse}, for
     *       example {@code NoResourceFoundException} or {@code NoHandlerFoundException} (404) for a
     *       path no handler maps and {@code HttpRequestMethodNotSupportedException} (405) for
     *       {@code POST /oauth2callback}: the response status is that exception's own status code.
     *       The body is empty, and no {@code Content-Type} or other header is added. Nothing is
     *       logged.</li>
     *   <li>Any other exception, for example the {@link IllegalStateException} raised for a callback
     *       without {@code code}, a token-endpoint status other than 401 and 429, or a Salesforce
     *       {@code ApiFault}: the exception and its stack trace are logged at ERROR, and the response
     *       is written through {@link RawBody#write(HttpServletResponse, int, byte[])} with status 500
     *       and the UTF-8 bytes of {@link Exception#getMessage()} as the body, or of the exception's
     *       class name when the message is {@code null}. No {@code Content-Type} header is sent
     *       (D-066).</li>
     * </ol>
     *
     * <p>Spring MVC renders nothing after this method returns: the response carries only the status
     * and bytes this method writes.
     *
     * @param ex       the exception raised while the request was handled, or while no handler was
     *                 found for it
     * @param response the response that receives the status and, for the 500 outcome, the body
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        if (ex instanceof org.springframework.web.ErrorResponse springError) {
            response.setStatus(springError.getStatusCode().value());
            return;
        }
        log.error("Exception while processing flow salesforce-oauthFlow1; answering HTTP 500", ex);
        String text = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
        RawBody.write(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), text.getBytes(StandardCharsets.UTF_8));
    }
}
