package com.mulesoft.examples.document_integration_using_the_cmis_connector.exception;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

/**
 * Translates the exceptions raised while serving HTTP requests into the error responses of the original listener
 * flow {@code CMIS} [document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:4-15],
 * which defines no exception strategy.
 *
 * <p>The advice applies to every request the {@code DispatcherServlet} serves, including requests no handler matches,
 * and answers through exactly two handler methods:
 *
 * <ul>
 *   <li>{@link #notFound(Exception, HttpServletRequest, HttpServletResponse)}: a path no listener serves, answered
 *       with {@code 404 Not Found} and {@code No listener for endpoint: <uri>} (D-424);</li>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every other exception, logged at ERROR as a failure of
 *       flow {@code CMIS} and answered with {@code 500 Internal Server Error} and the root-cause message (D-425).</li>
 * </ul>
 *
 * <p>Spring MVC selects the handler whose declared exception type is closest to the raised exception: a
 * {@link NoHandlerFoundException} or {@link NoResourceFoundException} reaches {@code notFound}, and any other
 * exception reaches {@code unexpected}. Each handler sets the status, then the reason phrase through
 * {@link ReasonPhrase#set(String)} (D-010), then writes the UTF-8 body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sends no {@code Content-Type} or other header
 * (D-066). This project carries its own copy of the class (D-004).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /unknown                  -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /unknown
 * POST /cmis (repository down)  -> HTTP/1.1 500 Internal Server Error  body: <root-cause message>
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR event written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason phrase on the status line of the unmatched-path answer (D-010). */
    private static final String NOT_FOUND_PHRASE = "Not Found";

    /** Reason phrase on the status line of the default-strategy answer (D-010). */
    private static final String INTERNAL_SERVER_ERROR_PHRASE = "Internal Server Error";

    /** Text that precedes the request URI in the unmatched-path body (D-424). */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Text of the ERROR event written for a failure of flow {@code CMIS} (D-425). */
    private static final String FLOW_FAILURE_LOG = "Exception thrown while processing flow CMIS";

    /**
     * Answers an unmatched path with {@code 404 Not Found} and the body {@code No listener for endpoint: <uri>},
     * with no Content-Type (D-010, D-066, D-424).
     *
     * <p>{@code <uri>} is {@link HttpServletRequest#getRequestURI()}: the request path as received, without the query
     * string. The body is encoded as UTF-8. Nothing is logged and no header is set.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the request
     * @param request  the request whose path no listener serves
     * @param response the response that receives the status line and the body
     * @throws java.io.UncheckedIOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        ReasonPhrase.set(NOT_FOUND_PHRASE);
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                (NO_LISTENER_PREFIX + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs the failure of flow {@code CMIS} at ERROR and answers {@code 500 Internal Server Error} with the
     * root-cause message, with no Content-Type (D-010, D-066, D-425).
     *
     * <p>The ERROR event has the text {@code Exception thrown while processing flow CMIS} and carries {@code ex} with
     * its stack trace. The root cause is the exception reached by following {@link Throwable#getCause()} from
     * {@code ex} until the cause is {@code null} or an exception already visited. The body is the UTF-8 encoding of
     * the root cause's {@link Throwable#getMessage()}, or of its class name when that message is {@code null}.
     *
     * <p>Every failure of the flow is answered alike: an unreachable CMIS repository, an error status or an
     * unreadable answer from it, a missing logo resource, and any Spring MVC exception that has no closer handler. No
     * other error response exists (D-062).
     *
     * @param ex       the exception raised while the request was served
     * @param response the response that receives the status line and the body
     * @throws java.io.UncheckedIOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) {
        log.error(FLOW_FAILURE_LOG, ex);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        ReasonPhrase.set(INTERNAL_SERVER_ERROR_PHRASE);
        Throwable root = rootCause(ex);
        String message = root.getMessage();
        if (message == null) {
            message = root.getClass().getName();
        }
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the deepest exception of the cause chain that starts at {@code throwable} (D-425).
     *
     * <p>The chain is followed through {@link Throwable#getCause()} and ends at the first {@code null} cause or at the
     * first cause already visited, compared by identity; a chain that leads back to one of its own members returns the
     * last exception reached before the repeat. A {@code throwable} without a cause is returned unchanged.
     *
     * @param throwable the outermost exception, not {@code null}
     * @return the innermost exception of the chain
     */
    private static Throwable rootCause(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root = throwable;
        visited.add(root);
        Throwable cause = root.getCause();
        while (cause != null && visited.add(cause)) {
            root = cause;
            cause = root.getCause();
        }
        return root;
    }
}
