package com.mulesoft.examples.scatter_gather_flow_control.exception;

import com.mulesoft.examples.scatter_gather_flow_control.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates failures of the HTTP listener {@code /scatterGather} into HTTP responses written without a
 * Content-Type (D-066).
 *
 * <p>The advice has two handlers, one per outcome of the listener:
 *
 * <ul>
 *   <li>{@link #noListener} answers a path with no handler, {@code /error} included (D-470), with {@code 404};</li>
 *   <li>{@link #unexpected} answers every other exception that leaves a controller with {@code 500}: a
 *       {@code MailException} of the report mail, the {@code IllegalStateException} of a failed aggregation and a
 *       {@code TaskRejectedException} of {@code scatterGatherExecutor} (D-263) among them.</li>
 * </ul>
 *
 * <p>Both bodies are UTF-8 bytes written by {@link RawBody#write(HttpServletResponse, int, byte[])} with a
 * {@code Content-Length} header. The advice holds no state besides its logger. Example exchanges:
 *
 * <pre>
 * GET /unknown?x=1   -&gt; HTTP/1.1 404 Not Found              body: No listener for endpoint: /unknown?x=1
 * GET /scatterGather -&gt; HTTP/1.1 500 Internal Server Error  body: smtp down   (MailSendException("smtp down"))
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the advice; only {@link #unexpected} writes to it, one ERROR event per exception. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a request for a path with no handler with 404, reason phrase {@code Not Found} (D-010) and the body
     * {@code No listener for endpoint: <path>[?<query>]} (D-630). Sets the status, then the reason phrase, then
     * writes the body.
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()} as received. {@code ?<query>} is appended when
     * {@link HttpServletRequest#getQueryString()} is neither {@code null} nor empty. Nothing is logged. Without an
     * Undertow request context, as under MockMvc, the reason phrase call has no effect and the status and body are
     * still written.
     *
     * @param request the request whose path matched no handler
     * @param response the response receiving the {@code 404}
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(404);
        ReasonPhrase.set(response, "Not Found");
        String query = request.getQueryString();
        String body = "No listener for endpoint: " + request.getRequestURI();
        if (query != null && !query.isEmpty()) {
            body = body + "?" + query;
        }
        RawBody.write(response, 404, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs the exception at ERROR and answers 500 with its message, or its class name when the message is null, as
     * the body (AAP 0.6.2).
     *
     * <p>The log entry carries the stack trace of {@code ex}. A response that is already committed receives the log
     * entry only. Otherwise the status line carries Undertow's standard reason phrase {@code Internal Server Error},
     * and the body is the message of {@code ex} itself, not of a cause.
     *
     * @param ex the exception that left the controller
     * @param response the response receiving the {@code 500}
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("Exception while processing flow scatter-gatherFlow: " + ex.getMessage(), ex);
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(500);
        RawBody.write(response, 500,
                (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName()).getBytes(StandardCharsets.UTF_8));
    }
}
