package com.mulesoft.examples.mule_component_bindings.exception;

import com.mulesoft.examples.mule_component_bindings.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error answers of the two HTTP listeners of mule-component-bindings: flow
 * {@code mule-component-bindingsFlow1} on {@code /} of the primary port and flow
 * {@code StockServiceFlow} ({@code GET /api/stockStats}) on the port set by
 * {@code listener.http-listener-configuration2.port}. Neither flow declares an exception strategy;
 * each method below answers one error behaviour the original exhibits, and no other error answer
 * exists (D-062).
 *
 * <table>
 *   <caption>Answers by exception</caption>
 *   <tr><th>Method</th><th>Exception</th><th>Status</th><th>Headers</th><th>Body</th></tr>
 *   <tr><td>{@link #responseStatus}</td><td>{@link ResponseStatusException}</td>
 *       <td>the exception's status, for example 400 or 500</td>
 *       <td>{@code Content-Type: application/json} when the reason is non-{@code null}; none
 *       otherwise</td>
 *       <td>the reason as raw UTF-8 bytes, not JSON-quoted; empty for a {@code null}
 *       reason</td></tr>
 *   <tr><td>{@link #methodNotAllowed}</td><td>{@link HttpRequestMethodNotSupportedException}</td>
 *       <td>405</td><td>{@code Allow} with the supported methods, when the exception names any</td>
 *       <td>empty</td></tr>
 *   <tr><td>{@link #unsupportedMediaType}</td><td>{@link HttpMediaTypeNotSupportedException}</td>
 *       <td>415</td><td>none</td><td>empty</td></tr>
 *   <tr><td>{@link #notAcceptable}</td><td>{@link HttpMediaTypeNotAcceptableException}</td>
 *       <td>406</td><td>none</td><td>empty</td></tr>
 *   <tr><td>{@link #notFound}</td><td>{@link NoHandlerFoundException},
 *       {@link NoResourceFoundException}</td><td>404</td><td>none</td><td>empty</td></tr>
 *   <tr><td>{@link #unexpected}</td><td>any other {@link Exception}</td><td>500</td><td>none</td>
 *       <td>the exception message as UTF-8 bytes; empty for a {@code null} message</td></tr>
 * </table>
 *
 * <p>Every empty-bodied answer and the 500 of {@link #unexpected} carry {@code Content-Length} and
 * no {@code Content-Type} (D-066). The answers written directly to the servlet response (the 500
 * of {@link #unexpected} and the {@code null}-reason answer of {@link #responseStatus}) first
 * discard any buffered body bytes and any {@code Content-Type} set before the exception, and leave
 * a committed response unchanged (D-453). No custom reason phrase is set: every status line carries
 * Undertow's standard phrase, for example {@code 400 Bad Request} (D-010).
 *
 * <p>Example exchanges on the secondary port:
 *
 * <pre>
 * GET /api/stockStats?date=2012-10-26
 *   -&gt; HTTP/1.1 400 Bad Request, Content-Type: application/json, body: stock is required
 * POST /api/stockStats?stock=AAPL&amp;date=2012-10-26
 *   -&gt; HTTP/1.1 405 Method Not Allowed, Allow: GET, Content-Length: 0
 * GET /api/unknown
 *   -&gt; HTTP/1.1 404 Not Found, Content-Length: 0
 * </pre>
 *
 * <p>Wrong (port, path) pairs never reach this class: {@code config.PortPathGuardFilter} answers
 * them before dispatch. Spring MVC calls the method whose declared exception type is closest to the
 * thrown type, and {@link #unexpected} receives every exception no other method declares.
 *
 * <p>Logging: 4xx answers at DEBUG, 5xx answers at ERROR with the stack trace. Logged exception
 * messages and reasons have their control characters escaped (D-453). The class holds no mutable
 * state and is safe for concurrent use; it is a per-project copy of the shared handler shape
 * (D-004).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the error answers: DEBUG for 4xx, ERROR for 5xx. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a {@link ResponseStatusException} with its status. A non-{@code null} reason is the
     * body, written as raw UTF-8 bytes under {@code Content-Type: application/json} with no charset
     * parameter; a {@code null} reason sends an empty body, {@code Content-Length: 0} and no
     * {@code Content-Type} (D-066), written through {@link #writeRaw}.
     *
     * <p>Answers of {@code service.StockStatsService}:
     * <ul>
     *   <li>400 {@code stock is required}, {@code date is required},
     *       {@code date format must be YYYY-MM-DD}, {@code Invalid stock symbol <stock>};</li>
     *   <li>500 with the message of the failure, for example
     *       {@code Stockylitics returned status 500}.</li>
     * </ul>
     *
     * <p>A 4xx status is logged at DEBUG, a 5xx status at ERROR with the reason and stack trace.
     *
     * <p>Example: {@code new ResponseStatusException(HttpStatus.BAD_REQUEST, "stock is required")}
     * answers {@code HTTP/1.1 400 Bad Request}, {@code Content-Type: application/json} and the
     * 17-byte body {@code stock is required}.
     *
     * @param ex       the exception carrying the status and the optional reason
     * @param response the response written directly when the reason is {@code null}
     * @return the answer with the reason bytes, or {@code null} when the reason is {@code null} and
     *         the empty answer has been written to {@code response}
     * @throws IOException if the empty answer cannot be written
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<byte[]> responseStatus(ResponseStatusException ex, HttpServletResponse response)
            throws IOException {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason();
        if (status.is5xxServerError()) {
            LOG.error("Answering {} with reason: {}", status.value(), escapeControlCharacters(reason), ex);
        } else {
            LOG.debug("Answering {} with reason: {}", status.value(), escapeControlCharacters(reason));
        }
        // A null reason answers the status with an empty body and no Content-Type (D-066).
        if (reason == null) {
            writeRaw(response, status.value(), new byte[0]);
            return null;
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reason.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a request method the mapped path does not support with 405, an empty body and an
     * {@code Allow} header listing the methods of
     * {@link HttpRequestMethodNotSupportedException#getSupportedHttpMethods()}. When that set is
     * {@code null} or empty, no {@code Allow} header is sent. No
     * {@code Content-Type} is sent (D-066). Logs the request method at DEBUG.
     *
     * <p>Example: {@code POST /api/stockStats} answers {@code HTTP/1.1 405 Method Not Allowed} with
     * {@code Allow: GET} and {@code Content-Length: 0}.
     *
     * @param ex the exception naming the request method and the supported methods
     * @return the empty-bodied 405 answer
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Void> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        LOG.debug("Answering 405 for request method {}", escapeControlCharacters(ex.getMethod()));
        HttpHeaders headers = new HttpHeaders();
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        // A null or empty supported-method set sends no Allow header.
        if (supported != null && !supported.isEmpty()) {
            headers.setAllow(supported);
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).build();
    }

    /**
     * Answers a request whose {@code Content-Type} is not {@code application/json} with 415, an
     * empty body and no {@code Content-Type} (D-066). Logs the exception message at DEBUG.
     *
     * <p>Example: {@code GET /api/stockStats} with {@code Content-Type: text/plain} answers
     * {@code HTTP/1.1 415 Unsupported Media Type} with {@code Content-Length: 0}.
     *
     * @param ex the exception naming the unsupported content type
     * @return the empty-bodied 415 answer
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Void> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        LOG.debug("Answering 415: {}", escapeControlCharacters(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    /**
     * Answers a request whose {@code Accept} header excludes {@code application/json} with 406, an
     * empty body and no {@code Content-Type} (D-066). Logs the exception message at DEBUG.
     *
     * <p>Example: {@code GET /api/stockStats} with {@code Accept: text/xml} answers
     * {@code HTTP/1.1 406 Not Acceptable} with {@code Content-Length: 0}.
     *
     * @param ex the exception naming the producible media types
     * @return the empty-bodied 406 answer
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> notAcceptable(HttpMediaTypeNotAcceptableException ex) {
        LOG.debug("Answering 406: {}", escapeControlCharacters(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /**
     * Answers a path no handler maps with 404, an empty body and no {@code Content-Type} (D-066).
     * Requires {@code spring.mvc.throw-exception-if-no-handler-found: true} and
     * {@code spring.web.resources.add-mappings: false}, both set in {@code application.yml}. Logs
     * the exception type and message at DEBUG.
     *
     * <p>Example: {@code GET /api/unknown} on the secondary port answers
     * {@code HTTP/1.1 404 Not Found} with {@code Content-Length: 0}.
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for
     *           the unmapped path
     * @return the empty-bodied 404 answer
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Void> notFound(Exception ex) {
        LOG.debug("Answering 404 ({}): {}", ex.getClass().getSimpleName(),
                escapeControlCharacters(ex.getMessage()));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * Answers every exception no other method of this class declares, on either listener, with
     * the default exception strategy's 500: the exception message as the UTF-8 body,
     * {@code Content-Length} and no {@code Content-Type} (D-066). A {@code null} message sends an
     * empty body with {@code Content-Length: 0}. Logs the message at ERROR with the stack trace.
     *
     * <p>The answer is written through {@link #writeRaw}: buffered body bytes and a
     * {@code Content-Type} set before the exception are discarded, and a response that is already
     * committed is left as it is, with only the log entry written.
     *
     * <p>Example: a {@code twitter4j.TwitterException} with message {@code boom} raised for
     * {@code GET /} answers {@code HTTP/1.1 500 Internal Server Error} with the 4-byte body
     * {@code boom}.
     *
     * @param ex       the failure no other method of this class declares
     * @param response the response written by this method
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        String message = ex.getMessage();
        LOG.error("Exception caught by the default exception strategy; answering 500: {}",
                escapeControlCharacters(message), ex);
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        writeRaw(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), body);
    }

    /**
     * Writes {@code status} and {@code body} through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}, with {@code Content-Length} and no
     * {@code Content-Type} (D-066).
     *
     * <p>An uncommitted response first loses its buffered body bytes and its {@code Content-Type}
     * and {@code Content-Length} headers; every other header set before the call is kept with its
     * values. A committed response is left as it is and nothing is written (D-453).
     *
     * <p>Example: a response holding {@code Content-Type: application/json}, {@code X-A: 1} and
     * 10 buffered bytes, written with status 500 and body {@code boom}, answers
     * {@code 500}, {@code X-A: 1}, {@code Content-Length: 4} and the body {@code boom}.
     *
     * @param response the response to write
     * @param status   the HTTP status code
     * @param body     the body bytes, possibly empty
     * @throws IOException if the body cannot be written
     */
    private static void writeRaw(HttpServletResponse response, int status, byte[] body) throws IOException {
        // A committed response receives no status or body.
        if (response.isCommitted()) {
            return;
        }
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                    && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                kept.putIfAbsent(name, new ArrayList<>(response.getHeaders(name)));
            }
        }
        // reset() clears the buffer, the status and every header, Content-Type included.
        response.reset();
        kept.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        RawBody.write(response, status, body);
    }

    /**
     * Returns {@code value} with every ISO control character written as an escape sequence:
     * {@code \r}, {@code \n} and {@code \t} by name, any other as {@code \}{@code u} and four
     * lower-case hexadecimal digits, for example {@code \}{@code u001b}. A value without control
     * characters, and {@code null}, are returned unchanged (D-453).
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
