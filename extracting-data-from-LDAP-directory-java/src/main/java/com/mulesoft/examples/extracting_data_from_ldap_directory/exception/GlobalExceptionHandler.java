package com.mulesoft.examples.extracting_data_from_ldap_directory.exception;

import com.mulesoft.examples.extracting_data_from_ldap_directory.controller.RawBody;
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
 * Maps unmatched paths to the listener's 404 and any other exception to HTTP 500, writing bodies
 * without a Content-Type (D-066).
 *
 * <p>Source: the listener configuration {@code HTTP_Listener_Configuration}
 * [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:6] and the flow {@code ldapFlow1}
 * [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:7-16], which declares no exception
 * strategy. The advice applies to every request the {@code DispatcherServlet} serves and answers
 * through two handler methods, one per error behaviour:
 *
 * <ul>
 *   <li>{@link #notFound(Exception, HttpServletRequest, HttpServletResponse)}: a path no listener
 *       serves, answered with {@code 404 Not Found} (D-010);</li>
 *   <li>{@link #unexpected(Exception, HttpServletRequest, HttpServletResponse)}: every other
 *       exception, logged at ERROR and answered with {@code 500 Internal Server Error}.</li>
 * </ul>
 *
 * <p>Spring selects the handler whose declared exception type is closest to the raised exception:
 * a {@link NoHandlerFoundException} or {@link NoResourceFoundException} reaches {@code notFound},
 * and any other exception, for example a Spring LDAP {@code CommunicationException} raised while no
 * directory server is reachable, reaches {@code unexpected}. Both write the body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets {@code Content-Length} and no
 * {@code Content-Type} (D-066). This project carries its own copy of the class (D-004).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /x        -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /x
 * GET /x?a=1    -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /x?a=1
 * POST /a/b     -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /a/b
 * GET / (directory unreachable)
 *               -> HTTP/1.1 500 Internal Server Error  body: <the exception message>
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Prefix of the 404 body; the request URI and its query follow it. */
    private static final String NO_LISTENER_FOR_ENDPOINT = "No listener for endpoint: ";

    /** Reason phrase written after the 404 status code on the status line (D-010). */
    private static final String NOT_FOUND = "Not Found";

    /**
     * 404 {@code Not Found} with body {@code No listener for endpoint: <uri>} (D-010, D-066).
     *
     * <p>{@code <uri>} is {@link HttpServletRequest#getRequestURI()} as received, not decoded,
     * followed by {@code ?} and {@link HttpServletRequest#getQueryString()} when the request carries
     * a query string. The body is encoded as UTF-8 and sent with {@code Content-Length} and no
     * {@code Content-Type}. The status is set before the reason phrase, and {@link RawBody} leaves
     * both unchanged. Nothing is logged.
     *
     * <pre>{@code
     * GET /a/b?x=1&y=2  -> HTTP/1.1 404 Not Found
     *                      Content-Length: 38
     *
     *                      No listener for endpoint: /a/b?x=1&y=2
     * }</pre>
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for
     *     the request
     * @param request the request whose path no listener serves
     * @param response the response that receives the 404 status, the reason phrase and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        ReasonPhrase.set(NOT_FOUND);
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        String text = NO_LISTENER_FOR_ENDPOINT + uri + (query != null ? "?" + query : "");
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs at ERROR and answers 500 with the exception message.
     *
     * <p>The log entry names the request method and URI and carries the exception with its stack
     * trace. The body is {@code String.valueOf(ex.getMessage())} encoded as UTF-8, that is the text
     * {@code null} for an exception without a message, sent with {@code Content-Length} and no
     * {@code Content-Type} (D-066). Undertow writes the standard reason phrase
     * {@code Internal Server Error}.
     *
     * <pre>{@code
     * GET / (directory unreachable) -> HTTP/1.1 500 Internal Server Error
     *                                  body: <the exception message>
     * }</pre>
     *
     * @param ex the exception raised while the request was handled
     * @param request the request that failed; its method and URI appear in the log entry
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
