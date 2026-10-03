package com.mulesoft.examples.addition_using_javascript_transformer.exception;

import com.mulesoft.examples.addition_using_javascript_transformer.controller.RawBody;
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
 * Translates exceptions raised while serving HTTP requests into the answers of the Mule HTTP listener
 * {@code HTTP_Listener_Configuration} of flow {@code javascript-calculatorFlow1}, which accepts only {@code POST}
 * on {@code /} [addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:3-5].
 *
 * <ul>
 *   <li>A request the listener does not match answers 405 for another method on {@code /} and 404 for any other
 *       path, with an empty body ({@link #listenerNoMatch}, D-428).</li>
 *   <li>Any other exception answers 500 with its message as the body, the Mule default strategy for an HTTP
 *       listener ({@link #unexpected}, D-429).</li>
 * </ul>
 *
 * <p>Neither answer carries a {@code Content-Type} header (D-066): both are written through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets the status, {@code Content-Length} and the
 * body bytes only. No custom reason phrase is set, and the status lines carry Undertow's standard phrases
 * {@code 404 Not Found}, {@code 405 Method Not Allowed} and {@code 500 Internal Server Error} (D-010).
 *
 * <p>The advice has no selector: it applies to every handler of the application and to requests for which no
 * handler exists. With {@code spring.mvc.throw-exception-if-no-handler-found: true} and
 * {@code spring.web.resources.add-mappings: false}, an unmapped path raises a {@link NoHandlerFoundException} or a
 * {@link NoResourceFoundException}, and a method other than {@code POST} on {@code /} raises an
 * {@link HttpRequestMethodNotSupportedException}. Spring MVC selects the handler whose declared exception type is
 * closest to the raised one: those three types reach {@link #listenerNoMatch}, and every other exception reaches
 * {@link #unexpected}. OPTIONS, TRACE and extension methods are answered before the dispatcher by
 * {@code config.ListenerMethodFilter} and never reach this class (D-428).
 *
 * <p>The class holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Writes the DEBUG line of {@link #listenerNoMatch} and the ERROR event of {@link #unexpected}. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers requests the listener does not match: 405 for another method on {@code /}, 404 for any other path;
     * empty body, no Content-Type or Allow header (D-428).
     *
     * <p>The answers, by request:
     * <ul>
     *   <li>{@code GET /}, {@code HEAD /}, {@code PUT /}, {@code DELETE /} and {@code PATCH /} raise
     *       {@link HttpRequestMethodNotSupportedException} and answer {@code 405 Method Not Allowed};</li>
     *   <li>{@code GET}, {@code HEAD}, {@code POST}, {@code PUT}, {@code DELETE} or {@code PATCH} on another path,
     *       for example {@code POST /x}, {@code GET /x} or {@code GET /error}, raises {@link NoHandlerFoundException}
     *       or {@link NoResourceFoundException} and answers {@code 404 Not Found}.</li>
     * </ul>
     *
     * <p>The response carries {@code Content-Length: 0} and no other header set here: no {@code Content-Type}
     * (D-066), no {@code Allow} and no custom reason phrase (D-010). The exception's headers and supported methods
     * are not read. The answer is logged at DEBUG only.
     *
     * @param ex       the {@link HttpRequestMethodNotSupportedException}, {@link NoHandlerFoundException} or
     *                 {@link NoResourceFoundException} raised for the request
     * @param response the response the 405 or 404 is written to
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler({
        HttpRequestMethodNotSupportedException.class,
        NoHandlerFoundException.class,
        NoResourceFoundException.class
    })
    public void listenerNoMatch(Exception ex, HttpServletResponse response) throws IOException {
        int status = (ex instanceof HttpRequestMethodNotSupportedException) ? 405 : 404;
        LOG.debug("No listener match: {} -> {}", ex.getMessage(), status);
        RawBody.write(response, status, new byte[0]);
    }

    /**
     * Mule default strategy for the HTTP listener (D-429): logs the exception at ERROR and answers 500 with its
     * message, no Content-Type. Covers the {@link IllegalArgumentException} raised by {@code CalculatorService} for
     * input outside the JSON-only SC-01 reading (D-169), such as {@code [1,}, an empty body or
     * {@code {"a":"x"}} (scenario {@code addition-using-javascript-transformer_malformed-list}).
     *
     * <p>The ERROR event carries the message and the exception with its stack trace. The body is the UTF-8 encoding
     * of {@link Exception#getMessage()}, or of the exception's class name when the message is {@code null}, written
     * with {@code Content-Length} and no {@code Content-Type} (D-066) under Undertow's standard phrase
     * {@code 500 Internal Server Error} (D-010). A response already committed is left as sent and only the log
     * event is written. The exception is not retried, rethrown or wrapped (D-429).
     *
     * @param ex       the exception raised while the request was handled
     * @param response the response the 500 is written to
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Exception while processing javascript-calculatorFlow1: {}", ex.getMessage(), ex);
        String text = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
        if (!response.isCommitted()) {
            RawBody.write(response, 500, text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
