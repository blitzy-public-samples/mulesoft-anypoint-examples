package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
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
 * Error answers of the HTTP listener of {@code add-customer-flow} ({@code POST /}), one handler
 * method per branch (D-007, D-413).
 *
 * <table>
 *   <caption>Answers by exception</caption>
 *   <tr><th>Method</th><th>Exception</th><th>Status line</th><th>Body</th><th>Content-Type</th></tr>
 *   <tr><td>{@link #methodNotAllowed}</td><td>{@link HttpRequestMethodNotSupportedException}</td>
 *       <td>{@code 405 Method not allowed for endpoint: <uri>}</td><td>{@code Method Not Allowed}</td>
 *       <td>none</td></tr>
 *   <tr><td>{@link #notFound}</td><td>{@link NoHandlerFoundException},
 *       {@link NoResourceFoundException}</td><td>{@code 404 No listener for endpoint: <uri>}</td>
 *       <td>{@code Resource not found.}</td><td>none</td></tr>
 *   <tr><td>{@link #upstreamAuthentication}</td><td>{@link UpstreamAuthenticationException}</td>
 *       <td>{@code 502 Bad Gateway}</td><td>{@link ErrorResponse}</td><td>{@code application/json}</td></tr>
 *   <tr><td>{@link #upstreamRateLimit}</td><td>{@link UpstreamRateLimitException}</td>
 *       <td>{@code 429 Too Many Requests}, plus {@code Retry-After} when the vendor sent one</td>
 *       <td>{@link ErrorResponse}</td><td>{@code application/json}</td></tr>
 *   <tr><td>{@link #upstreamUnavailable}</td><td>{@link UpstreamUnavailableException}</td>
 *       <td>{@code 503 Service Unavailable}</td><td>{@link ErrorResponse}</td>
 *       <td>{@code application/json}</td></tr>
 *   <tr><td>{@link #unexpected}</td><td>any other {@link Exception}</td>
 *       <td>{@code 500 <first line of the exception message>}</td><td>the exception message</td>
 *       <td>none</td></tr>
 * </table>
 *
 * <p>{@code <uri>} is the request path as received, followed by {@code ?} and the query string
 * when the request has a non-empty one: {@code GET /?a=1} answers
 * {@code HTTP/1.1 405 Method not allowed for endpoint: /?a=1}.
 *
 * <p>The 404, 405 and 500 answers set the status, then the reason phrase through
 * {@link ReasonPhrase#set(String)} (D-010), then write the UTF-8 body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sends {@code Content-Length} and no
 * {@code Content-Type} (D-066). They send no {@code Allow} header. The three connector answers
 * carry Undertow's standard reason phrase and the JSON {@link ErrorResponse} of D-020.
 *
 * <p>Spring MVC calls the method whose declared exception type is closest to the thrown type:
 * {@link #unexpected} receives every exception that no other method of this class declares. No
 * method retries a call; the single re-authentication retry happens inside
 * {@code client.WorkdayRevenueClient.execute} (D-020).
 *
 * <p>The class excludes {@link ErrorMvcAutoConfiguration} from the application context: no
 * {@code BasicErrorController}, no container error page and no whitelabel view are registered, and
 * {@code /error} answers the 404 of {@link #notFound} like every other path except {@code /}
 * (D-414).
 */
@RestControllerAdvice
@ImportAutoConfiguration(exclude = ErrorMvcAutoConfiguration.class)
public class GlobalExceptionHandler {

    /** Logger of the error answers: DEBUG for 404 and 405, ERROR for the 429, 500, 502 and 503. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason-phrase format of the listener's 405 answer; {@code %s} is the request target (D-413). */
    static final String NO_METHOD_REASON_FORMAT = "Method not allowed for endpoint: %s";

    /** Body of the listener's 405 answer (D-413). */
    static final String NO_METHOD_ENTITY = "Method Not Allowed";

    /** Reason-phrase format of the listener's 404 answer; {@code %s} is the request target (D-413). */
    static final String NO_LISTENER_REASON_FORMAT = "No listener for endpoint: %s";

    /** Body of the listener's 404 answer (D-413). */
    static final String NO_LISTENER_ENTITY = "Resource not found.";

    /**
     * Answers a request with a method other than {@code POST} on {@code /} with
     * {@code HTTP/1.1 405 Method not allowed for endpoint: <uri>}, the UTF-8 body
     * {@code Method Not Allowed}, no {@code Content-Type} and no {@code Allow} header (D-066, D-102,
     * D-413). Logs the method and target at DEBUG.
     *
     * <p>Example: {@code GET /} answers {@code HTTP/1.1 405 Method not allowed for endpoint: /} with
     * the 18-byte body {@code Method Not Allowed}.
     *
     * @param ex       the exception raised for the request method
     * @param request  the request whose path and query form {@code <uri>}
     * @param response the response written by this method
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String target = requestTarget(request);
        LOG.debug("Answering 405 for method {} on {}", escapeControlCharacters(ex.getMethod()),
                escapeControlCharacters(target));
        int status = HttpStatus.METHOD_NOT_ALLOWED.value();
        response.setStatus(status);
        ReasonPhrase.set(String.format(NO_METHOD_REASON_FORMAT, target));
        RawBody.write(response, status, NO_METHOD_ENTITY.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a request for a path other than {@code /} with
     * {@code HTTP/1.1 404 No listener for endpoint: <uri>}, the UTF-8 body {@code Resource not found.}
     * and no {@code Content-Type} (D-066, D-413). Logs the exception type and target at DEBUG.
     *
     * <p>Example: {@code POST /x} answers {@code HTTP/1.1 404 No listener for endpoint: /x} with the
     * 19-byte body {@code Resource not found.}.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for the unmapped path
     * @param request  the request whose path and query form {@code <uri>}
     * @param response the response written by this method
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String target = requestTarget(request);
        LOG.debug("Answering 404 ({}) for {}", ex.getClass().getSimpleName(),
                escapeControlCharacters(target));
        int status = HttpStatus.NOT_FOUND.value();
        response.setStatus(status);
        ReasonPhrase.set(String.format(NO_LISTENER_REASON_FORMAT, target));
        RawBody.write(response, status, NO_LISTENER_ENTITY.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a Workday authentication failure that persisted through the client's single
     * re-authentication retry with {@code 502 Bad Gateway} and the {@code application/json} body
     * {@code {"message":"<message>","upstreamSystem":"<system>"}} (D-007, D-020). Logs the upstream
     * system and the message at ERROR.
     *
     * @param ex the classified authentication failure
     * @return the 502 answer with its {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public ResponseEntity<ErrorResponse> upstreamAuthentication(UpstreamAuthenticationException ex) {
        LOG.error("Upstream authentication failed; answering 502: upstreamSystem={} message={}",
                escapeControlCharacters(ex.getUpstreamSystem()), escapeControlCharacters(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a Workday rate-limit response with {@code 429 Too Many Requests}, the
     * {@code application/json} body {@code {"message":"<message>","upstreamSystem":"<system>"}} and,
     * when the vendor sent one, its {@code Retry-After} value unchanged; without a vendor value no
     * {@code Retry-After} header is sent (D-007, D-020). Logs the upstream system, the message and the
     * {@code Retry-After} value at ERROR.
     *
     * <p>Example: {@code getRetryAfter()} {@code "30"} answers {@code 429} with
     * {@code Retry-After: 30}.
     *
     * @param ex the classified rate-limit failure
     * @return the 429 answer with its {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<ErrorResponse> upstreamRateLimit(UpstreamRateLimitException ex) {
        String retryAfter = ex.getRetryAfter();
        LOG.error("Upstream rate limit reached; answering 429: upstreamSystem={} message={} retryAfter={}",
                escapeControlCharacters(ex.getUpstreamSystem()), escapeControlCharacters(ex.getMessage()),
                escapeControlCharacters(retryAfter));
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            answer.header(HttpHeaders.RETRY_AFTER, retryAfter);
        }
        return answer.body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers a Workday timeout or connectivity failure with {@code 503 Service Unavailable} and the
     * {@code application/json} body {@code {"message":"<message>","upstreamSystem":"<system>"}}; the
     * failed {@code Put_Customer} call is not re-sent (D-007, D-020). Logs the upstream system and
     * the message at ERROR.
     *
     * @param ex the classified timeout or connectivity failure
     * @return the 503 answer with its {@link ErrorResponse} body
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ErrorResponse> upstreamUnavailable(UpstreamUnavailableException ex) {
        LOG.error("Upstream unavailable; answering 503: upstreamSystem={} message={}",
                escapeControlCharacters(ex.getUpstreamSystem()), escapeControlCharacters(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(ex.getMessage(), ex.getUpstreamSystem()));
    }

    /**
     * Answers every other failure of {@code add-customer-flow} with status 500, the reason phrase
     * formed by the first line of the exception message, the message as the UTF-8 body and no
     * {@code Content-Type}, the default exception strategy's answer for a flow without its own
     * strategy (D-066, D-413). Logs the message at ERROR with the stack trace.
     *
     * <p>The first line is the text before the first CR or LF, trimmed. When the message is
     * {@code null} or that line is empty, no phrase is set and the status line carries Undertow's
     * standard {@code Internal Server Error}; a {@code null} message sends an empty body with
     * {@code Content-Length: 0}. A response that is already committed is left as it is and only the
     * log entry is written (D-413).
     *
     * <p>Example: a {@code RuntimeException("boom")} answers {@code HTTP/1.1 500 boom} with the
     * 4-byte body {@code boom}.
     *
     * @param ex       the failure no other method of this class declares
     * @param response the response written by this method
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        String message = ex.getMessage();
        LOG.error("add-customer-flow failed; answering 500: message={}", escapeControlCharacters(message), ex);
        // A committed response is returned unchanged: no status, phrase or body is written (D-413).
        if (response.isCommitted()) {
            return;
        }
        int status = HttpStatus.INTERNAL_SERVER_ERROR.value();
        response.setStatus(status);
        String phrase = firstLine(message);
        if (!phrase.isEmpty()) {
            ReasonPhrase.set(phrase);
        }
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, status, body);
    }

    /**
     * Returns the request target of the listener's 404 and 405 reason phrases: the request URI as
     * received, followed by {@code ?} and the query string when the query string is neither
     * {@code null} nor empty (D-413).
     *
     * @param request the current request
     * @return for example {@code /x} or {@code /x?a=1}
     */
    private static String requestTarget(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        // A null or empty query string adds no "?" to the target (D-413).
        if (query == null || query.isEmpty()) {
            return uri;
        }
        return uri + "?" + query;
    }

    /**
     * Returns the text of {@code message} before its first CR or LF, trimmed; an empty string for a
     * {@code null} message.
     *
     * @param message the exception message, or {@code null}
     * @return the trimmed first line, never {@code null}
     */
    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int end = message.length();
        int cr = message.indexOf('\r');
        int lf = message.indexOf('\n');
        if (cr >= 0) {
            end = cr;
        }
        if (lf >= 0 && lf < end) {
            end = lf;
        }
        return message.substring(0, end).trim();
    }

    /**
     * Returns {@code value} with every ISO control character written as an escape sequence:
     * {@code \r}, {@code \n} and {@code \t} by name, any other as {@code \}{@code u} and four
     * lower-case hexadecimal digits, for example {@code \}{@code u001b}. A value
     * without control characters, and {@code null}, are returned unchanged.
     *
     * @param value the text placed into a log entry, or {@code null}
     * @return the escaped text, or {@code null}
     */
    private static String escapeControlCharacters(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder escaped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) {
                if (escaped != null) {
                    escaped.append(c);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(value.length() + 8).append(value, 0, i);
            }
            switch (c) {
                case '\r' -> escaped.append("\\r");
                case '\n' -> escaped.append("\\n");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(String.format("\\u%04x", (int) c));
            }
        }
        return escaped == null ? value : escaped.toString();
    }
}
