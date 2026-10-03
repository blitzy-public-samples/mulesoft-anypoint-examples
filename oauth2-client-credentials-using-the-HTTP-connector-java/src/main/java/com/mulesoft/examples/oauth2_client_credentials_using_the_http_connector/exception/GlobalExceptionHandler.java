package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller.RawBody;
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
 * Translates exceptions from the HTTP endpoints into responses without a Content-Type (D-066):
 * unmatched paths to 404 {@code No listener for endpoint: <path>}, every other exception to 500
 * with the exception message.
 *
 * <p>The advice applies to every controller of the application, which replaces the two flows on
 * the listener {@code HTTP_Listener_Configuration} ({@code 0.0.0.0:8081})
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:9,31-47].
 * It has exactly two handlers, one per original HTTP behaviour:
 * <ul>
 *   <li>{@link #notFound} receives {@link NoHandlerFoundException} and
 *       {@link NoResourceFoundException}, raised for a path no controller maps under
 *       {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 *       {@code spring.web.resources.add-mappings: false} (D-545);</li>
 *   <li>{@link #unexpected} receives every other exception, for example
 *       {@code ResponseValidatorException} for an upstream status of 400 or above (D-207), a
 *       transport failure of the Box or FIM call, or a failed token request (D-546).</li>
 * </ul>
 * Spring selects the handler whose declared exception type is closest to the thrown one, so the two
 * not-found types reach {@link #notFound} only.
 *
 * <p>Both handlers write through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets
 * the status and {@code Content-Length} and no {@code Content-Type} (D-066, D-199). No reason
 * phrase is set, so each status line carries Undertow's standard phrase (D-010). Example exchanges:
 * <pre>
 * GET /unknown                     -&gt; HTTP/1.1 404 Not Found
 *                                     Content-Length: 34
 *
 *                                     No listener for endpoint: /unknown
 *
 * upstream 404 on /oauth or /FIM/x -&gt; HTTP/1.1 500 Internal Server Error
 *                                     Content-Length: 36
 *
 *                                     Response code 404 mapped as failure.
 * </pre>
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a path no controller maps with 404 and the body
     * {@code No listener for endpoint: <request URI>}, without a Content-Type (D-545); listener
     * {@code HTTP_Listener_Configuration} for a path no flow listens on
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:9].
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for the unmatched path
     * @param request  the unmatched request; its {@link HttpServletRequest#getRequestURI()}, without
     *                 the query string, completes the body
     * @param response the response that receives the status and the UTF-8 body
     * @throws IOException declared by the handler signature and not raised by this body:
     *                     {@link RawBody#write(HttpServletResponse, int, byte[])} reports a write
     *                     failure as {@link java.io.UncheckedIOException} (D-199)
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        RawBody.write(response, 404,
                ("No listener for endpoint: " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers every other exception with 500 and the exception message as the body, or an empty body
     * for a {@code null} message, without a Content-Type, after logging the message and stack trace
     * at ERROR (D-546); default strategy of {@code http-client-credentialsFlow} and rollback strategy
     * of {@code httpclientcredentialsFlow}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:35-37,39-47].
     *
     * @param ex       the exception raised while the request was processed
     * @param response the response that receives the status and the UTF-8 body
     * @throws IOException declared by the handler signature and not raised by this body:
     *                     {@link RawBody#write(HttpServletResponse, int, byte[])} reports a write
     *                     failure as {@link java.io.UncheckedIOException} (D-199)
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error(String.valueOf(ex.getMessage()), ex);
        RawBody.write(response, 500,
                ex.getMessage() == null ? new byte[0] : ex.getMessage().getBytes(StandardCharsets.UTF_8));
    }
}
