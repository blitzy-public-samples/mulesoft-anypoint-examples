package com.mulesoft.examples.http_request_response_with_logger.exception;

import com.mulesoft.examples.http_request_response_with_logger.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers any exception raised while an {@code EchoFlow} request is handled with status 500, the exception message
 * as the body and no {@code Content-Type} header (D-007, D-066).
 *
 * <p>{@code EchoFlow} declares no exception strategy, and the advice has one handler method, {@link #unexpected},
 * which answers every exception type with the default-strategy 500. The advice is unrestricted: it applies to every
 * handler of the application (D-421). The body is written through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the status, {@code Content-Length} and the body
 * bytes only. No reason phrase is set, and the status line carries Undertow's standard phrase
 * {@code HTTP/1.1 500 Internal Server Error} (D-010).
 *
 * <p>Example, for a handler that throws {@code new IllegalStateException("boom")}:
 *
 * <pre>{@code
 * HTTP/1.1 500 Internal Server Error
 * Content-Length: 4
 *
 * boom
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
     * Logs the exception at ERROR and, when the response is not yet committed, writes the 500 response with the
     * exception message as the body.
     *
     * <p>The log entry is one ERROR event whose text is {@link Exception#getMessage()}, or {@code null} when the
     * exception has no message, and which carries {@code ex} with its stack trace. The body is the UTF-8 encoding of
     * the message, or zero bytes when the message is {@code null}, written through
     * {@link RawBody#write(HttpServletResponse, int, byte[])} with no {@code Content-Type}, character encoding or
     * reason phrase (D-066). A response that is already committed is left unchanged, and only the log entry is
     * written (D-421). The exact Mule default-strategy body is pinned by a Tier 2A fixture (D-023).
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        String message = ex.getMessage();
        LOGGER.error(String.valueOf(message), ex);
        if (!response.isCommitted()) {
            byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
            RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
        }
    }
}
