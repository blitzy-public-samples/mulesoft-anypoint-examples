package com.mulesoft.examples.proxying_a_soap_api.exception;

import com.mulesoft.examples.proxying_a_soap_api.controller.RawBody;
import com.mulesoft.examples.proxying_a_soap_api.mapper.SoapFaultMapper;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;
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
 * Handles every exception raised while a request of the HTTP listener of flow {@code main}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:6] is served, and writes the two answers of that
 * listener through {@link #noListener} and {@link #unexpected} (D-460).
 *
 * <p>The advice has no {@code assignableTypes}, {@code basePackages} or {@code annotations} selector: it
 * applies to every handler of the application and to requests for which no handler exists. Spring MVC
 * selects the handler method whose declared exception type is closest to the raised one:
 *
 * <ul>
 *   <li>{@link NoHandlerFoundException} and {@link NoResourceFoundException}, raised for every path other
 *       than {@code /} under {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 *       {@code spring.web.resources.add-mappings: false} (D-109), reach {@link #noListener}: 404 with the
 *       reason phrase {@code Not Found} written through {@link ReasonPhrase} (D-010) and no
 *       {@code Content-Type} (D-066);</li>
 *   <li>every other exception reaches {@link #unexpected}, among them {@link ResponseValidatorException}
 *       for an upstream status outside 0–399 (D-259), {@link RequestSendException} for an upstream that
 *       cannot be reached, and the {@link IllegalStateException} of the {@code ?wsdl} address rewrite
 *       (D-251): 500 with the {@code soap:Server} fault of {@link SoapFaultMapper} (D-245).</li>
 * </ul>
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /other              -> HTTP/1.1 404 Not Found
 *                            (no Content-Type) No listener for endpoint: /other
 * POST / (upstream 500)   -> HTTP/1.1 500 Internal Server Error
 *                            Content-Type: text/xml; charset=UTF-8
 *                            <soap:Envelope ...><faultstring>Response code 500 mapped as failure. ...
 * }</pre>
 *
 * <p>Both answers are written through {@link RawBody#write(HttpServletResponse, int, byte[])}, which adds
 * no header of its own (D-262). The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the one ERROR entry written for each exception {@link #unexpected} handles. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Reason phrase of the unmatched-path 404 status line (D-010, D-253). */
    private static final String NOT_FOUND_REASON = "Not Found";

    /** Prefix of the unmatched-path 404 body; the request URI follows it (D-109). */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Exact {@code Content-Type} header value of the 500 fault, space before {@code charset} included (D-460). */
    private static final String FAULT_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** Builds the {@code soap:Server} fault envelope of the 500 answer (D-245). */
    private final SoapFaultMapper soapFaultMapper;

    /**
     * Creates the advice with the mapper that builds the 500 fault body.
     *
     * @param soapFaultMapper the fault envelope builder of flow {@code main}'s default branch
     */
    public GlobalExceptionHandler(SoapFaultMapper soapFaultMapper) {
        this.soapFaultMapper = soapFaultMapper;
    }

    /**
     * Answers a path other than {@code /} on the listener of {@code soap-api-proxy.xml:6} with 404
     * {@code Not Found} and the body {@code No listener for endpoint: <path>}, without Content-Type
     * (D-066, D-109).
     *
     * <p>The steps, in order: the status is set to 404; the reason phrase {@code Not Found} is written to
     * the Undertow exchange through {@link ReasonPhrase#set(String)} (D-010, D-253); the body is written
     * through {@link RawBody#write(HttpServletResponse, int, byte[])}, which leaves the status and the
     * phrase as set. {@code <path>} is {@link HttpServletRequest#getRequestURI()} as received, undecoded
     * and without the query string: {@code GET /other?x=1} answers {@code No listener for endpoint: /other}.
     * The body is UTF-8 encoded. Nothing is logged.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the path
     * @param request  the request whose path no handler maps
     * @param response the response that receives the 404 status, the reason phrase and the body
     * @throws IOException           if the body cannot be written to the response
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(404);
        ReasonPhrase.set(NOT_FOUND_REASON);
        RawBody.write(response, 404,
                (NO_LISTENER_PREFIX + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Default branch of flow {@code main} behind {@code cxf:proxy-service} ({@code soap-api-proxy.xml:7}):
     * logs at ERROR and answers 500 with a {@code soap:Server} fault carrying the exception message, as
     * {@code text/xml; charset=UTF-8} (D-023, D-245, D-460).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>one ERROR event {@code Exception thrown processing flow main: <message>} is logged with the
     *       exception and its stack trace;</li>
     *   <li>a response already committed is left as sent: no status, header or byte is added;</li>
     *   <li>the header {@code Content-Type} with the value {@code text/xml; charset=UTF-8} is put on the
     *       response headers of the current Undertow exchange through {@link ServletRequestContext}, and
     *       reaches the wire byte for byte as written: {@code Content-Type: text/xml; charset=UTF-8};</li>
     *   <li>status 500 and {@link SoapFaultMapper#serverFault(String)} of {@link Exception#getMessage()}
     *       are written through {@link RawBody#write(HttpServletResponse, int, byte[])}. A {@code null}
     *       message is passed unchanged and gives an empty {@code faultstring}.</li>
     * </ol>
     *
     * <p>No reason phrase is set: the status line carries Undertow's standard
     * {@code Internal Server Error}. The exception is not retried, rethrown or wrapped. A message holding a
     * character XML 1.0 does not allow makes {@code serverFault} raise {@link IllegalArgumentException}
     * (D-361), which this method does not catch.
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response that receives the 500 status, the header and the fault envelope
     * @throws IOException           if the body cannot be written to the response
     * @throws IllegalStateException if the response is not committed and no Undertow servlet request is
     *                               active on the calling thread
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("Exception thrown processing flow main: {}", ex.getMessage(), ex);
        if (response.isCommitted()) {
            // A committed response is left as sent: no status, header or byte is added (D-460).
            return;
        }
        // The header value goes onto the response headers of the Undertow exchange unchanged (D-460).
        ServletRequestContext.requireCurrent().getExchange().getResponseHeaders()
                .put(Headers.CONTENT_TYPE, FAULT_CONTENT_TYPE);
        RawBody.write(response, 500, soapFaultMapper.serverFault(ex.getMessage()));
    }
}
