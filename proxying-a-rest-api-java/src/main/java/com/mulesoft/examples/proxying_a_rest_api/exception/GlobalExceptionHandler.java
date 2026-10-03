package com.mulesoft.examples.proxying_a_rest_api.exception;

import com.mulesoft.examples.proxying_a_rest_api.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers any exception raised while a request of flow {@code rest-api-proxy} is handled with an ERROR log entry
 * and status 500 whose body is the exception message, without a {@code Content-Type} header (D-007, D-066). When the
 * response is already committed, nothing is written and only the log entry is produced.
 *
 * <p>Flow {@code rest-api-proxy} declares no exception strategy, and the advice has exactly one handler method,
 * {@link #unexpected}, the default-strategy branch. It answers every exception type the same way, among them an
 * upstream status outside {@code 0..399} ({@code UpstreamStatusException}), a connect failure and a timeout. See the
 * DECISIONS.md row "proxying-a-rest-api-java — upstream status mapping".
 *
 * <p>The advice carries no {@code assignableTypes}, {@code basePackages} or {@code annotations} selector and applies to
 * every handler of the application. The application registers no error controller, so this advice is the only error
 * path (D-314). The body is written through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the
 * status, {@code Content-Length} and the body bytes only. No reason phrase is set, and the status line carries
 * Undertow's standard phrase {@code Internal Server Error} (D-010).
 *
 * <p>Example, for an upstream answer {@code 404}, showing the status line, the one header this class determines and
 * the body; Undertow adds its own {@code Date} header:
 *
 * <pre>{@code
 * HTTP/1.1 500 Internal Server Error
 * Content-Length: 36
 *
 * Response code 404 mapped as failure.
 * }</pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests. This project carries its own copy of the
 * class (D-004).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Logs {@code ex} at ERROR and, when {@code response} is not yet committed, writes status 500 with the exception
     * message as the body.
     *
     * <p>The log entry is one ERROR event with the text {@code Exception in flow rest-api-proxy: <message>}, where
     * {@code <message>} is {@link Exception#getMessage()}, or {@code null} when the exception has no message; the event
     * carries {@code ex} with its stack trace. The log entry is always written first.
     *
     * <p>The body is the UTF-8 encoding of the message, or zero bytes when the message is {@code null}, written through
     * {@link RawBody#write(HttpServletResponse, int, byte[])} with no {@code Content-Type}, character encoding or
     * reason phrase (D-066); {@code Content-Length}, set by {@code RawBody}, is the only header the write adds. A
     * response that is already committed, for example one whose upstream body has started streaming, is left unchanged.
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response that receives the status 500 and the body
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        String message = ex.getMessage();
        LOGGER.error("Exception in flow rest-api-proxy: {}", message, ex);
        if (!response.isCommitted()) {
            byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
            RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
        }
    }
}
