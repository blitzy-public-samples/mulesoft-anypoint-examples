package com.mulesoft.examples.login_form_using_the_http_connector.exception;

import com.mulesoft.examples.login_form_using_the_http_connector.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers the exceptions raised while an HTTP request is handled with the default responses of the
 * Mule listener configuration {@code HttpListenerConfig}, which serves {@code GET /login}
 * ({@code GetLoginPageFlow}), {@code POST /login} ({@code DoLoginFlow}) and every method on
 * {@code /requesterLogin} ({@code CallLoginFlowUsingRequester}).
 *
 * <ul>
 *   <li>{@link #methodNotAllowed}: a method no mapping of a served path supports, such as
 *       {@code PUT}, {@code DELETE}, {@code PATCH} or {@code TRACE} on {@code /login}, answered with
 *       {@code 405 Method Not Allowed};</li>
 *   <li>{@link #notFound}: a path no listener serves, {@code /error} included, answered with
 *       {@code 404 Not Found};</li>
 *   <li>{@link #unexpected}: every other exception escaping {@code GetLoginPageFlow} or
 *       {@code CallLoginFlowUsingRequester}, logged at ERROR and answered with
 *       {@code 500 Internal Server Error}.</li>
 * </ul>
 *
 * <p>The failed-login {@code 403 Forbidden} of the {@code DoLoginFlow} catch exception strategy is
 * answered by the login controller and never reaches this class.
 *
 * <p>Spring MVC selects the handler whose declared exception type is closest to the raised
 * exception: {@link HttpRequestMethodNotSupportedException} reaches {@code methodNotAllowed},
 * {@link NoHandlerFoundException} and {@link NoResourceFoundException} reach {@code notFound}, and
 * every other exception, {@link ResponseValidatorException} included, reaches {@code unexpected}.
 * The advice carries no selector and applies to every request, including a request that has no
 * handler (D-614).
 *
 * <p>Each handler writes the status, {@code Content-Length} and the UTF-8 body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}. No response carries a
 * {@code Content-Type} header (D-066) or an {@code Allow} header, and no reason phrase is set: the
 * status line carries Undertow's standard phrase ({@code Method Not Allowed}, {@code Not Found},
 * {@code Internal Server Error}). The three body texts are pinned by Tier 2A fixtures (D-023) and
 * recorded in D-614.
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * PUT /login           -> HTTP/1.1 405 Method Not Allowed     body: Method not allowed for endpoint: /login
 * GET /nope            -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /nope
 * GET /requesterLogin  -> HTTP/1.1 500 Internal Server Error  body: Response code 403 mapped as failure.
 *                         (the requester's POST /login answered 403)
 * }</pre>
 *
 * <p>The class holds no mutable state and serves concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Prefix of the 405 body; the request path follows it. */
    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Prefix of the 404 body; the request path follows it. */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /**
     * Answers a method the listener does not allow on a served path with
     * {@code HTTP/1.1 405 Method Not Allowed} and the body
     * {@code Method not allowed for endpoint: <path>}, with no {@code Content-Type} (D-066) and no
     * {@code Allow} header.
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as received,
     * without the query string. The body is UTF-8 encoded and pinned by Tier 2A fixture (D-023).
     * Nothing is logged. For example {@code PUT /login?x=1} answers
     * {@code Method not allowed for endpoint: /login}.
     *
     * @param ex       the exception raised for a method that no mapping of the path supports
     * @param request  the request whose method the listener does not allow
     * @param response the response that receives the 405 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                METHOD_NOT_ALLOWED_PREFIX + request.getRequestURI());
    }

    /**
     * Answers a path no listener serves with {@code HTTP/1.1 404 Not Found} and the body
     * {@code No listener for endpoint: <path>}, with no {@code Content-Type} (D-066).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path as received,
     * without the query string. The body is UTF-8 encoded and pinned by Tier 2A fixture (D-023).
     * Nothing is logged. For example {@code GET /nope} answers
     * {@code No listener for endpoint: /nope}, and {@code GET /error} answers
     * {@code No listener for endpoint: /error} (D-614).
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for the request
     * @param request  the request whose path no listener serves
     * @param response the response that receives the 404 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        write(response, HttpServletResponse.SC_NOT_FOUND, NO_LISTENER_PREFIX + request.getRequestURI());
    }

    /**
     * Logs any other exception at ERROR and answers {@code HTTP/1.1 500 Internal Server Error} with
     * the exception message as the body, with no {@code Content-Type} (D-066).
     *
     * <p>The log entry is one ERROR event with the text
     * {@code Exception while processing <method> <path>: <message>} and the exception with its stack
     * trace attached. The body is the UTF-8 encoding of
     * {@code String.valueOf(ex.getMessage())}: the message, or the text {@code null} when the message
     * is {@code null} (D-614); it is pinned by Tier 2A fixture (D-023). When the response is already
     * committed, the exception is logged and nothing is written.
     *
     * <p>Exceptions answered here include the {@link ResponseValidatorException}
     * ({@code Response code <n> mapped as failure.}) raised when the requester's {@code POST /login}
     * answers a status outside {@code 0..399}, the
     * {@code org.springframework.web.client.ResourceAccessException} raised when the requester cannot
     * connect, and every other exception escaping {@code GetLoginPageFlow} or
     * {@code CallLoginFlowUsingRequester}.
     *
     * @param ex       the exception raised while the request was handled
     * @param request  the request whose handling failed
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.error("Exception while processing {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage(), ex);
        if (!response.isCommitted()) {
            write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Writes {@code status}, {@code Content-Length} and the UTF-8 bytes of {@code text} through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}, with no {@code Content-Type} (D-066).
     *
     * @param response the response that receives the status and the body
     * @param status   the HTTP status code
     * @param text     the body text
     * @throws IOException if the body cannot be written to the response
     */
    private static void write(HttpServletResponse response, int status, String text) throws IOException {
        RawBody.write(response, status, text.getBytes(StandardCharsets.UTF_8));
    }
}
