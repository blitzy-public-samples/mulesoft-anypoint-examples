package com.mulesoft.examples.web_service_consumer.exception;

import com.mulesoft.examples.web_service_consumer.controller.RawBody;
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
 * Translates exceptions from the HTTP controllers into the responses of the Mule HTTP listener
 * {@code HTTP_Listener_Configuration}, which serves the flows {@code orderTshirt} on {@code /orders} and
 * {@code listInventory} on {@code /inventory}.
 *
 * <ul>
 *   <li>An unmatched path answers 404 with the body {@code No listener for endpoint: <request URI and query>}
 *       ({@link #notFound}).</li>
 *   <li>Any other exception answers 500 with its message as the body ({@link #unexpected}).</li>
 * </ul>
 *
 * <p>Neither response carries a {@code Content-Type} header (D-066): both bodies are written through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the status, {@code Content-Length} and the
 * body bytes only. Neither handler sets a custom reason phrase, and the status lines carry Undertow's standard
 * phrases {@code HTTP/1.1 404 Not Found} and {@code HTTP/1.1 500 Internal Server Error} (D-010).
 *
 * <p>The advice is unrestricted: it applies to every handler of the application and to requests for which no
 * handler exists. With {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 * {@code spring.web.resources.add-mappings: false} in the application configuration, an unmapped path raises a
 * {@link NoHandlerFoundException} or a {@link NoResourceFoundException}. Spring MVC selects the handler whose
 * declared exception type is closest to the raised one: those two types reach {@link #notFound}, and every other
 * exception reaches {@link #unexpected}.
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Prefix of the 404 body; the request URI and query follow it. */
    static final String NO_LISTENER_FOR_ENDPOINT = "No listener for endpoint: ";

    /** Opening rule of the ERROR log entry: 80 asterisks. */
    private static final String RULE_STARS = "*".repeat(80);

    /** Rule between the message line and the stack trace of the ERROR log entry: 80 hyphens. */
    private static final String RULE_DASHES = "-".repeat(80);

    /**
     * Answers 404 with {@code No listener for endpoint: <request URI and query>} for a path no controller maps.
     *
     * <p>The URI is {@link HttpServletRequest#getRequestURI()} as received, without decoding, followed by
     * {@code ?} and {@link HttpServletRequest#getQueryString()} when the request has a query string. Examples:
     * <ul>
     *   <li>{@code GET /orders/x} answers {@code No listener for endpoint: /orders/x};</li>
     *   <li>{@code GET /foo?a=1} answers {@code No listener for endpoint: /foo?a=1};</li>
     *   <li>{@code GET /} answers {@code No listener for endpoint: /}.</li>
     * </ul>
     *
     * <p>The body is UTF-8 encoded. No header other than {@code Content-Length} is set, no reason phrase is set
     * and nothing is logged.
     *
     * @param request  the request that matched no handler
     * @param response the response the 404 is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String queryString = request.getQueryString();
        String uri = queryString != null ? request.getRequestURI() + "?" + queryString : request.getRequestURI();
        RawBody.write(response, 404, (NO_LISTENER_FOR_ENDPOINT + uri).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs the exception at ERROR in the Mule default-strategy layout and answers 500 with the exception message,
     * or the exception's class name when the message is {@code null}.
     *
     * <p>The log entry is one ERROR event whose text starts with a line break and then holds these lines, in order,
     * followed by the stack trace of {@code exception}:
     * <ol>
     *   <li>80 asterisks;</li>
     *   <li>{@code Message               : <message>}, the label {@code Message} padded with spaces to 22
     *       characters;</li>
     *   <li>80 hyphens;</li>
     *   <li>{@code Root Exception stack trace:}.</li>
     * </ol>
     *
     * <p>Exceptions answered here include a remote SOAP fault ({@code SoapFaultClientException}), a transport
     * failure ({@code WebServiceTransportException}), a connection failure or timeout
     * ({@code WebServiceIOException}), {@link IllegalArgumentException}, {@link IllegalStateException}, and the
     * Jackson parse exceptions and {@code DOMException}s raised for an absent or malformed JSON body on
     * {@code /orders}.
     *
     * <p>The body is the UTF-8 encoding of the message. No {@code Content-Type} header (D-066) and no reason phrase
     * is set. The message is logged and written as received, control characters included. The exception is answered
     * as received: it is not retried, rethrown or wrapped.
     *
     * @param exception the exception raised while a controller handled the request
     * @param response  the response the 500 is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception exception, HttpServletResponse response) throws IOException {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getName();
        LOG.error("\n{}\nMessage               : {}\n{}\nRoot Exception stack trace:",
                RULE_STARS, message, RULE_DASHES, exception);
        RawBody.write(response, 500, message.getBytes(StandardCharsets.UTF_8));
    }
}
