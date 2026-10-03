package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.exception;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller.RawBody;
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
 * Answers the exceptions that leave a controller of this application, and the requests no
 * controller maps, with the responses of the two {@code http:listener} flows
 * {@code boxUserLoginFlow} ({@code /web}) and {@code userLoginDoneFlow} ({@code /web/loginDone}) on
 * port 8081, of the OAuth module's HTTPS listener on port 8082 ({@code /authorization},
 * {@code /redirectUrl}) and of Mule's default exception strategy, which is the only error
 * behaviour those flows have.
 *
 * <p>Three handler methods, one per behaviour:
 * <ul>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every exception without a closer
 *       handler, logged at ERROR and answered with 500 and the exception message (D-512);</li>
 *   <li>{@link #notFound(Exception, HttpServletRequest, HttpServletResponse)}: a path no
 *       controller maps, answered with 404 and {@code No listener for endpoint: <request URI>},
 *       the text {@code config/PortPathGuardFilter} writes for a path requested on the wrong port
 *       (D-011, D-513);</li>
 *   <li>{@link #methodNotAllowed(HttpRequestMethodNotSupportedException, HttpServletRequest,
 *       HttpServletResponse)}: a method a mapped path does not accept, for example {@code POST} on
 *       the GET-only {@code /authorization} and {@code /redirectUrl}, answered with 405 and
 *       {@code Method not allowed for endpoint: <request URI>} (D-514).</li>
 * </ul>
 *
 * <p>Spring MVC picks the handler whose declared exception type is closest to the raised one:
 * {@link NoHandlerFoundException} and {@link NoResourceFoundException} reach {@code notFound},
 * {@link HttpRequestMethodNotSupportedException} reaches {@code methodNotAllowed}, and every other
 * exception reaches {@code unexpected}. The advice has no {@code basePackages} or
 * {@code assignableTypes} selector and also receives the {@link NoHandlerFoundException} raised
 * for a request without a handler ({@code spring.mvc.throw-exception-if-no-handler-found: true},
 * {@code spring.web.resources.add-mappings: false}).
 *
 * <p>Every answer is written through {@link RawBody#write(HttpServletResponse, int, byte[])}: the
 * status and the UTF-8 body bytes, with no {@code Content-Type} header (D-066), no {@code Allow}
 * header and no {@code sendError}. No handler sets a reason phrase; the status line carries
 * Undertow's standard phrase ({@code Internal Server Error}, {@code Not Found},
 * {@code Method Not Allowed}) (D-010). A handler that finds the response already committed writes
 * nothing and only logs.
 *
 * <p>The statuses the OAuth endpoints choose themselves (400 without {@code code}, 500 for a failed
 * token call, the {@code authorizationStatus} redirects) and the 302 of {@code /web} are written by
 * their controllers and never reach this class.
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET  http://localhost:8081/unknown?x=1    -> 404 "No listener for endpoint: /unknown"
 * POST https://localhost:8082/authorization  -> 405 "Method not allowed for endpoint: /authorization"
 * GET  http://localhost:8081/web/loginDone   -> 500 "<exception message>" (no token stored)
 * }</pre>
 *
 * <p>The class holds no mutable state and serves concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Text before the request URI in the 404 body; equal to the text of {@code PortPathGuardFilter}
     * (D-011, D-513).
     */
    public static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Text before the request URI in the 405 body (D-514). */
    public static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Receives the ERROR events of {@code unexpected} and the DEBUG and WARN events of the others. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers an exception that no closer handler takes with 500, after one ERROR event, as Mule's
     * default exception strategy answers an HTTP listener (D-512).
     *
     * <p>The ERROR event carries the exception message, with ISO control characters replaced by
     * {@code ?} (D-512), and, as its throwable, the exception with its stack trace. The body is
     * the UTF-8 encoding of {@link Exception#getMessage()}, unchanged, or empty when the message
     * is {@code null}; the response has no {@code Content-Type} (D-066). Examples
     * are the missing-token failure of {@code GET /web/loginDone} and a failed Box search call.
     * When the response is already committed, the ERROR event is written and nothing else.
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        String message = ex.getMessage();
        LOG.error("Exception while processing the request; answering HTTP 500: {}", printable(message), ex);
        if (response.isCommitted()) {
            return;
        }
        byte[] body = message == null ? new byte[0] : utf8(message);
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
    }

    /**
     * Answers a path that no controller maps with 404 and the body
     * {@code No listener for endpoint: <request URI>}, the text {@code PortPathGuardFilter} writes
     * for a path requested on the wrong port (D-011, D-513).
     *
     * <p>{@code <request URI>} is {@link HttpServletRequest#getRequestURI()}: the path as received,
     * undecoded and without the query string. The body is UTF-8 encoded and the response has no
     * {@code Content-Type} (D-066). A written answer logs one DEBUG event and no ERROR event. When
     * the response is already committed, one WARN event is written and nothing else.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for the request
     * @param request  the request whose path no controller maps
     * @param response the response that receives the 404 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String uri = request.getRequestURI();
        if (response.isCommitted()) {
            LOG.warn("Response already committed; HTTP 404 not written for {} {}",
                    printable(request.getMethod()), printable(uri));
            return;
        }
        LOG.debug("No handler for {} {}; answering HTTP 404", printable(request.getMethod()), printable(uri));
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, utf8(NO_LISTENER_PREFIX + uri));
    }

    /**
     * Answers a method that a mapped path does not accept with 405 and the body
     * {@code Method not allowed for endpoint: <request URI>} (D-514).
     *
     * <p>{@code <request URI>} is {@link HttpServletRequest#getRequestURI()}: the path as received,
     * undecoded and without the query string. The body is UTF-8 encoded; the response has no
     * {@code Content-Type} (D-066) and no {@code Allow} header. A written answer logs one DEBUG
     * event and no ERROR event. When the response is already committed, one WARN event is written
     * and nothing else.
     *
     * @param ex       the exception raised for the method, for example {@code POST /authorization}
     * @param request  the request whose method the mapped path does not accept
     * @param response the response that receives the 405 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (response.isCommitted()) {
            LOG.warn("Response already committed; HTTP 405 not written for {} {}",
                    printable(ex.getMethod()), printable(uri));
            return;
        }
        LOG.debug("Method {} not accepted for {}; answering HTTP 405",
                printable(ex.getMethod()), printable(uri));
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                utf8(METHOD_NOT_ALLOWED_PREFIX + uri));
    }

    /**
     * Encodes {@code text} as UTF-8.
     *
     * @param text the body text
     * @return the UTF-8 bytes of {@code text}
     */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns {@code value} for a log event with every ISO control character replaced by
     * {@code ?}; {@code null} is returned as {@code "null"}.
     *
     * @param value the request-derived text, or {@code null}
     * @return the text with control characters replaced
     */
    private static String printable(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? '?' : c);
        }
        return out.toString();
    }
}
