/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates the exceptions raised while serving the three person listeners
 * {@code GET /person}, {@code POST /person} and {@code GET /person/{personId}}
 * [http-restful-resource.xml:11,16,28] into HTTP responses, one handler method per original branch.
 *
 * <p>The advice has no selector and applies to every request the {@code DispatcherServlet} serves,
 * including requests for which no handler is found. Its handlers:
 *
 * <ul>
 *   <li>{@link #notFound(FilterUnacceptedException)}: the rollback branch of the
 *       {@code choice-exception-strategy} of {@code retrievePersonFlow}
 *       [http-restful-resource.xml:37-42], answered with 404, the reason phrase {@code Not Found}
 *       and an empty body;</li>
 *   <li>{@link #noListener(Exception, HttpServletResponse)}: a path no listener serves, answered
 *       with 404 and an empty body;</li>
 *   <li>{@link #methodNotAllowed(HttpRequestMethodNotSupportedException, HttpServletResponse)}: a
 *       method outside a listener's {@code allowedMethods}, answered with 405 and an empty body,
 *       without an {@code Allow} header;</li>
 *   <li>{@link #unexpected(Exception, HttpServletResponse)}: every other exception, the runtime's
 *       default strategy, answered with 500 and the exception message after an ERROR log
 *       entry.</li>
 * </ul>
 *
 * <p>Spring MVC selects the handler whose declared exception type is closest to the raised
 * exception, so {@link FilterUnacceptedException}, {@link NoHandlerFoundException},
 * {@link NoResourceFoundException} and {@link HttpRequestMethodNotSupportedException} never reach
 * {@code unexpected}. No response carries a {@code Content-Type} header (D-066). Bodies follow
 * D-007; the empty 404 and 405 bodies and the 500 body stand until the Tier 2A fixtures pin them
 * (D-023, D-435). Only {@code notFound} sets a reason phrase (D-010); the other statuses carry
 * Undertow's standard phrases. This project carries its own copy of the class (D-004).
 *
 * <p>Example exchanges on an empty person store:
 *
 * <pre>{@code
 * GET /person/7    -> HTTP/1.1 404 Not Found              empty body              (notFound)
 * GET /nothing     -> HTTP/1.1 404 Not Found              empty body              (noListener)
 * GET /error       -> HTTP/1.1 404 Not Found              empty body              (noListener)
 * PUT /person      -> HTTP/1.1 405 Method Not Allowed     empty body              (methodNotAllowed)
 * TRACE /person    -> HTTP/1.1 405 Method Not Allowed     empty body              (methodNotAllowed)
 * GET /person/abc  -> HTTP/1.1 500 Internal Server Error  For input string: "abc" (unexpected)
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Receives the ERROR entries written by {@link #notFound} and {@link #unexpected}. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Status code of the rollback branch: the value it assigns to {@code errorStatusCode}
     * [http-restful-resource.xml:39], written by the {@code http:error-response-builder}
     * [http-restful-resource.xml:29].
     */
    static final int ERROR_STATUS_CODE = 404;

    /**
     * Reason phrase of the rollback branch: the value it assigns to {@code errorReasonPhrase}
     * [http-restful-resource.xml:40], written on the status line through
     * {@link ReasonPhrase#set(String)} (D-010).
     */
    static final String ERROR_REASON_PHRASE = "Not Found";

    /**
     * Answers 404 {@code Not Found} with an empty body when the requested person is not stored.
     *
     * <p>Replaces the {@code rollback-exception-strategy} whose condition is
     * {@code exception.causedBy(org.mule.api.routing.filter.FilterUnacceptedException)}
     * [http-restful-resource.xml:38-41]. The exception is logged at ERROR with its stack trace,
     * {@link #ERROR_REASON_PHRASE} is set on the Undertow exchange through
     * {@link ReasonPhrase#set(String)} (D-010), and the response carries status
     * {@link #ERROR_STATUS_CODE}, {@code Content-Length: 0}, no body and no {@code Content-Type}
     * (D-007, D-066). The status line reads {@code HTTP/1.1 404 Not Found}.
     *
     * <p>Runs only inside a request served by embedded Undertow: {@link ReasonPhrase#set(String)}
     * throws {@link IllegalStateException} on a thread without an Undertow servlet request
     * (D-213).
     *
     * @param ex the exception {@code PersonService.retrievePersonFlow} raises for an id the store
     *     does not contain
     * @return the bodyless 404 response
     */
    @ExceptionHandler(FilterUnacceptedException.class)
    public ResponseEntity<Void> notFound(FilterUnacceptedException ex) {
        LOG.error("Exception caught by the rollback exception strategy of flow retrievePersonFlow: {}",
                ex.getMessage(), ex);
        ReasonPhrase.set(ERROR_REASON_PHRASE);
        return ResponseEntity.status(ERROR_STATUS_CODE).build();
    }

    /**
     * Answers a path that no listener serves with 404, an empty body and Undertow's standard phrase
     * {@code Not Found}.
     *
     * <p>{@link NoHandlerFoundException} and {@link NoResourceFoundException} are raised for such a
     * path, {@code /error} included, with {@code spring.mvc.throw-exception-if-no-handler-found:
     * true}, {@code spring.web.resources.add-mappings: false} and {@code ErrorMvcAutoConfiguration}
     * excluded (D-436), for every method. The response carries {@code Content-Length: 0} and no
     * {@code Content-Type} (D-066). Nothing is logged.
     *
     * <p>The empty body is written through {@link RawBody#write(HttpServletResponse, int, byte[])},
     * which commits the response before the method returns: a {@code TRACE} or {@code OPTIONS}
     * request receives the same 404, with no echoed request and no {@code Allow} header
     * (D-435).
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for
     *     the request
     * @param response the response that receives the 404 status
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(Exception ex, HttpServletResponse response) throws IOException {
        // Writes and commits the 404 here instead of returning a ResponseEntity: no TRACE echo and no
        // OPTIONS Allow header follows it (D-435).
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, new byte[0]);
    }

    /**
     * Answers a method outside a listener's {@code allowedMethods} with 405, an empty body and
     * Undertow's standard phrase {@code Method Not Allowed}.
     *
     * <p>{@code GET /person} [http-restful-resource.xml:11] and {@code GET /person/{personId}}
     * [http-restful-resource.xml:28] allow {@code GET}; {@code POST /person}
     * [http-restful-resource.xml:16] allows {@code POST}. Every other method that Spring MVC
     * dispatches on these paths, for example {@code PUT /person}, {@code DELETE /person/1} or
     * {@code TRACE /person} (D-436), reaches this handler. The response carries
     * {@code Content-Length: 0}, no {@code Content-Type} and no {@code Allow} header (D-066).
     * Nothing is logged.
     *
     * <p>The empty body is written through {@link RawBody#write(HttpServletResponse, int, byte[])},
     * which commits the response before the method returns: a {@code TRACE} request receives the
     * 405 with no echoed request (D-435).
     *
     * @param ex the exception Spring MVC raises when a path matches and its method does not
     * @param response the response that receives the 405 status
     * @throws IOException if the response cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        // Writes and commits the 405 here instead of returning a ResponseEntity: no TRACE echo follows
        // it (D-435).
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, new byte[0]);
    }

    /**
     * Logs any other exception at ERROR and answers 500 with its message as the body, without a
     * {@code Content-Type} (D-066).
     *
     * <p>Replaces the runtime's default strategy, which a {@code choice-exception-strategy} without
     * a matching branch falls through to: a non-numeric {@code personId} in
     * {@code Integer.valueOf} [http-restful-resource.xml:31] raising {@link NumberFormatException},
     * a request body the person mapping cannot read, and any other failure while a person request
     * is served. The log entry carries the exception and its stack trace. The body is
     * {@link Exception#getMessage()} encoded as UTF-8, or empty when the message is {@code null},
     * written through {@link RawBody#write(HttpServletResponse, int, byte[])} with
     * {@code Content-Length} and Undertow's standard phrase {@code Internal Server Error}; the
     * Tier 2A fixture of the scenario pins the body (D-023, D-435).
     *
     * <p>When the response is already committed, for example after a write to a closed client
     * connection failed, the ERROR entry is the only effect and nothing further is written.
     *
     * @param ex the exception raised while the request was handled
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Exception caught by the default exception strategy: {}", ex.getMessage(), ex);
        if (response.isCommitted()) {
            return;
        }
        String message = ex.getMessage();
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
    }
}
