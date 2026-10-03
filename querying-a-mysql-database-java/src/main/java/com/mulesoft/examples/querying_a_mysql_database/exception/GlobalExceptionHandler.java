package com.mulesoft.examples.querying_a_mysql_database.exception;

import com.mulesoft.examples.querying_a_mysql_database.controller.RawBody;
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
 * Error answers of the HTTP listener of flow {@code database-to-jsonFlow}, which binds only the path {@code /}.
 *
 * <ul>
 *   <li>{@link #noListener} answers a request whose path matches no handler: status {@code 404}, reason phrase
 *       {@code Not Found} (D-010) and the body {@code No listener for endpoint: <request URI>}.</li>
 *   <li>{@link #unexpected} answers every other exception that leaves a controller, the database and JSON failures
 *       propagated by {@code EmployeeQueryService} and {@code EmployeeJdbcClient} included: one ERROR log entry with
 *       the stack trace, then status {@code 500} with the exception message as the body.</li>
 * </ul>
 *
 * <p>Both bodies are UTF-8 bytes written by {@link RawBody#write(HttpServletResponse, int, byte[])} with a
 * {@code Content-Length} header and no {@code Content-Type} header (D-066). The advice applies to every controller of
 * the application and holds no state besides its logger.
 *
 * <p>Example exchanges, with {@code DataAccessResourceFailureException("boom")} raised by the query in the second:
 *
 * <pre>
 * GET /other?x=1          -&gt; HTTP/1.1 404 Not Found              body: No listener for endpoint: /other?x=1
 * GET /?lastname=Smith    -&gt; HTTP/1.1 500 Internal Server Error  body: boom
 * </pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason phrase on the status line of the unmatched-path {@code 404} (D-010). */
    static final String NOT_FOUND_REASON = "Not Found";

    /** Text in front of the request URI in the body of the unmatched-path {@code 404}. */
    static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /**
     * Answers a request whose path matches no handler with {@code 404 Not Found}.
     *
     * <p>Spring MVC raises {@link NoHandlerFoundException} for every path other than {@code /}, and
     * {@link NoResourceFoundException} when a static-resource handler finds no resource. The answer is:
     *
     * <ol>
     *   <li>status {@code 404};</li>
     *   <li>reason phrase {@code Not Found}, set through {@link ReasonPhrase#set(HttpServletResponse, String)}
     *       (D-010);</li>
     *   <li>body {@code No listener for endpoint: } followed by {@link HttpServletRequest#getRequestURI()} as received
     *       and, when the request has a query string, {@code ?} and {@link HttpServletRequest#getQueryString()}, for
     *       example {@code No listener for endpoint: /other?x=1}, written in UTF-8 with no {@code Content-Type}
     *       header (D-066).</li>
     * </ol>
     *
     * <p>Nothing is logged above DEBUG.
     *
     * @param ex the exception raised for the unmatched path
     * @param request the request that matched no handler
     * @param response the response receiving the {@code 404}; not yet committed
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.debug("No listener for {} {} ({})", request.getMethod(), request.getRequestURI(),
                ex.getClass().getSimpleName());
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        ReasonPhrase.set(response, NOT_FOUND_REASON);
        String query = request.getQueryString();
        String body = NO_LISTENER_PREFIX + request.getRequestURI() + (query != null ? "?" + query : "");
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers every exception without a more specific handler with {@code 500}.
     *
     * <p>The exception is logged at ERROR with the request method and URI and its stack trace. The response then
     * receives status {@code 500} with Undertow's standard reason phrase {@code Internal Server Error} and the body
     * {@code String.valueOf(ex.getMessage())} in UTF-8, which is the text {@code null} for an exception without a
     * message, with no {@code Content-Type} header (D-066). A response that is already committed keeps the status
     * and bytes already sent and receives only the log entry.
     *
     * @param ex the exception that left the controller
     * @param request the request being processed when {@code ex} was raised
     * @param response the response receiving the {@code 500}
     * @throws IOException if the body cannot be written to the client
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        LOG.error("Exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        // A committed response is left unchanged: it receives no status, header or body bytes from this handler.
        if (response.isCommitted()) {
            return;
        }
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
