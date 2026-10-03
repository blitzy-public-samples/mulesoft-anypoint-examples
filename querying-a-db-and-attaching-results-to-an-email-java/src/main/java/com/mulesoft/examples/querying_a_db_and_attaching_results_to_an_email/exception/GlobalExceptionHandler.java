package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.exception;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller.RawBody;
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
 * Translates exceptions raised while handling HTTP requests into the responses of the original listener flow
 * {@code attachmentsFlow1}.
 *
 * <p>The advice applies to every request the {@code DispatcherServlet} serves, including requests for which no
 * handler is found, and answers through two handler methods:
 *
 * <ul>
 *   <li>{@link #notFound(Exception, HttpServletRequest, HttpServletResponse)}: a path no listener serves, answered
 *       with 404;</li>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every other exception, answered with 500 after an
 *       ERROR log entry.</li>
 * </ul>
 *
 * <p>Spring selects the handler whose declared exception type is closest to the raised exception, so a
 * {@link NoHandlerFoundException} or {@link NoResourceFoundException} reaches {@code notFound} and any other
 * exception reaches {@code unexpected}. Both write the body through {@link RawBody#write(HttpServletResponse, int,
 * byte[])}, which sets the status and {@code Content-Length} and sends no {@code Content-Type} (D-066). Undertow
 * writes the standard reason phrases {@code Not Found} and {@code Internal Server Error} on the status line
 * (D-010). This project carries its own copy of the class (D-004).
 *
 * <p>Example exchanges:
 *
 * <pre>{@code
 * GET /unknown        -> HTTP/1.1 404 Not Found              body: No listener for endpoint: /unknown
 * POST / (bad XML)    -> HTTP/1.1 500 Internal Server Error  body: <the exception message>
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entry written for each exception {@link #unexpected} answers. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers an unmatched path with 404 and the body {@code No listener for endpoint: <path>}, with no
     * Content-Type (D-066).
     *
     * <p>{@code <path>} is {@link HttpServletRequest#getRequestURI()}: the request path without the query string.
     * The body is encoded as UTF-8. Nothing is logged.
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for the request
     * @param request the request whose path no listener serves
     * @param response the response that receives the 404 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response) throws IOException {
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
                ("No listener for endpoint: " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Logs any other exception at ERROR and answers 500 with its message as the body, with no Content-Type (D-066).
     *
     * <p>The log entry carries the exception and its stack trace. The body is {@link Exception#getMessage()} encoded
     * as UTF-8, or empty when the message is {@code null}. The method answers every failure of the flow alike: an
     * XML payload that cannot be parsed, an employee name without a second word, a database failure, a mail delivery
     * failure and a value the CSV mapping cannot format. No error behaviour is added beyond the original's
     * (D-062).
     *
     * @param ex the exception raised while the request was handled
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        log.error("Exception caught while processing flow attachmentsFlow1", ex);
        String message = ex.getMessage();
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                (message == null ? "" : message).getBytes(StandardCharsets.UTF_8));
    }
}
