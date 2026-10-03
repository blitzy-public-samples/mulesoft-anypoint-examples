package com.mulesoft.examples.http_multipart_request.exception;

import com.mulesoft.examples.http_multipart_request.controller.RawBody;
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
 * Maps exceptions from every request of this application to raw responses without a
 * {@code Content-Type} (D-066).
 *
 * <p>The flows {@code httpRenderFlow} ({@code GET /uploadFile})
 * [http-multipart-request/src/main/app/http-multipart-request.xml:6-11] and
 * {@code httpMultipartRequestFlow} ({@code POST /uploadFile})
 * [http-multipart-request/src/main/app/http-multipart-request.xml:13-29] declare no exception
 * strategy. The advice has no selector: it applies to every handler of the application and to
 * requests that no handler matches. It has one handler method per outcome (D-585):
 * <ul>
 *   <li>{@link #notFound} answers {@link NoHandlerFoundException} and
 *       {@link NoResourceFoundException}, raised for every path other than {@code /uploadFile},
 *       {@code /error} included, with 404 and an empty body (D-162);</li>
 *   <li>{@link #methodNotAllowed} answers {@link HttpRequestMethodNotSupportedException}, raised for
 *       {@code PUT}, {@code DELETE}, {@code PATCH}, {@code TRACE}, {@code HEAD} and {@code OPTIONS}
 *       on {@code /uploadFile}, with 405, an empty body and no {@code Allow} header (D-162);</li>
 *   <li>{@link #unexpected} answers every other exception with 500 and the exception message as
 *       the body, and logs it at ERROR.</li>
 * </ul>
 * Spring MVC selects the handler whose declared exception type is closest to the raised one: the
 * three routing exception types reach {@link #notFound} or {@link #methodNotAllowed}, and every
 * other exception reaches {@link #unexpected}.
 *
 * <p>Every answer is written through {@link RawBody#write(HttpServletResponse, int, byte[])}, which
 * sets the status, {@code Content-Length} and the body bytes and commits the response (D-439). No
 * reason phrase is set, and the status lines carry Undertow's standard phrases (D-010). Example
 * exchanges:
 * <pre>
 * GET /other                      -&gt; HTTP/1.1 404 Not Found
 *                                    Content-Length: 0
 *
 * PUT /uploadFile                 -&gt; HTTP/1.1 405 Method Not Allowed
 *                                    Content-Length: 0
 *
 * POST /uploadFile, failure "boom" -&gt; HTTP/1.1 500 Internal Server Error
 *                                    Content-Length: 4
 *
 *                                    boom
 * </pre>
 *
 * <p>This project carries its own copy of the class (D-004). The class holds no mutable state and
 * is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Writes the ERROR event of {@link #unexpected}. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers 404 with an empty body.
     *
     * <p>The response carries {@code Content-Length: 0} and no {@code Content-Type} (D-066, D-162).
     * The exception is not read and nothing is logged.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised
     *                 for the unmatched path
     * @param response the response the 404 is written to
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletResponse response) throws IOException {
        RawBody.write(response, 404, new byte[0]);
    }

    /**
     * Answers 405 with an empty body and no {@code Allow} header.
     *
     * <p>The response carries {@code Content-Length: 0} and no {@code Content-Type} (D-066, D-162).
     * The supported methods of the exception are not read and nothing is logged.
     *
     * @param ex       the {@link HttpRequestMethodNotSupportedException} raised for a method other
     *                 than {@code GET} or {@code POST} on {@code /uploadFile}
     * @param response the response the 405 is written to
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        RawBody.write(response, 405, new byte[0]);
    }

    /**
     * Logs the exception at ERROR and answers 500 with the exception message as the body, unless
     * the response is already committed.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>one ERROR event is written whose text is {@code String.valueOf(ex.getMessage())}, the
     *       text {@code null} for an exception without a message, and which carries the exception
     *       with its stack trace;</li>
     *   <li>a response that is already committed is left as sent, and nothing else happens;</li>
     *   <li>otherwise 500 is written with the UTF-8 bytes of
     *       {@code String.valueOf(ex.getMessage())} as the body and no {@code Content-Type}
     *       (D-066).</li>
     * </ol>
     *
     * <p>Among the exceptions answered here are the {@link NullPointerException} of a request
     * without part {@code file} or without its {@code Content-Disposition} header, the
     * {@link StringIndexOutOfBoundsException} of a file name that cannot be extracted (D-376), an
     * {@link IOException} or {@link java.io.UncheckedIOException} of the file write under
     * {@code file.outbound-endpoint.path}, the {@link jakarta.servlet.ServletException} of a
     * non-multipart {@code POST} and the {@link org.springframework.web.multipart.MultipartException}
     * of a multipart body that cannot be parsed (D-585). The exception is not retried, rethrown or
     * wrapped.
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response the 500 is written to
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error(String.valueOf(ex.getMessage()), ex);
        if (response.isCommitted()) {
            return;
        }
        RawBody.write(response, 500, String.valueOf(ex.getMessage()).getBytes(StandardCharsets.UTF_8));
    }
}
