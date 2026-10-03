package com.mulesoft.examples.testing_apikit_with_munit.exception;

import com.mulesoft.examples.testing_apikit_with_munit.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers request failures with the APIkit mapping strategy {@code api-apiKitGlobalExceptionMapping}
 * [testing-apikit-with-munit/src/main/app/api.xml:10-36], the strategy that the router flow
 * {@code api-main} [api.xml:38-43] references at api.xml:42, plus the HTTP default rule (HTTP 500).
 * The class is project-local (D-004).
 *
 * <p>The advice has no {@code assignableTypes}, {@code basePackages} or {@code annotations} selector:
 * it applies to every handler, including a {@code null} handler. A request that no handler maps,
 * inside or outside {@code /api}, raises {@link NoHandlerFoundException} (with
 * {@code spring.mvc.throw-exception-if-no-handler-found=true} and
 * {@code spring.web.resources.add-mappings=false} in {@code application.yml}) and receives the APIkit
 * 404 answer of {@link #notFound(Exception)} (D-397).
 *
 * <p>The five APIkit branches, declared in api.xml order, each answer with one status, exactly the
 * header {@code Content-Type: application/json} (no charset parameter) and the UTF-8 bytes of the
 * literal body of the branch's {@code set-payload}, byte for byte (D-007):
 *
 * <table>
 *   <caption>APIkit branches</caption>
 *   <tr><th>Method</th><th>Status</th><th>Body</th><th>Source</th></tr>
 *   <tr><td>{@link #notFound(Exception)}</td><td>404</td>
 *       <td>{@code { "message": "Resource not found" }}</td><td>api.xml:11-15</td></tr>
 *   <tr><td>{@link #methodNotAllowed(HttpRequestMethodNotSupportedException)}</td><td>405</td>
 *       <td>{@code { "message": "Method not allowed" }}</td><td>api.xml:16-20</td></tr>
 *   <tr><td>{@link #unsupportedMediaType(HttpMediaTypeNotSupportedException)}</td><td>415</td>
 *       <td>{@code { "message": "Unsupported media type" }}</td><td>api.xml:21-25</td></tr>
 *   <tr><td>{@link #notAcceptable(HttpMediaTypeNotAcceptableException)}</td><td>406</td>
 *       <td>{@code { "message": "Not acceptable" }}</td><td>api.xml:26-30</td></tr>
 *   <tr><td>{@link #badRequest(Exception)}</td><td>400</td>
 *       <td>{@code { "message": "Bad request" }}</td><td>api.xml:31-35</td></tr>
 * </table>
 *
 * <p>No branch sets an {@code Allow} header, any other header or a custom reason phrase; the status
 * line carries Undertow's standard phrase for the status code (D-010). The {@code Content-Type} set on
 * each {@link ResponseEntity} is written as set: the {@code byte[]} body is written by
 * {@code ByteArrayHttpMessageConverter} without negotiation against the request's {@code Accept}
 * header, and a request with {@code Accept: text/plain} that raises
 * {@link HttpMediaTypeNotAcceptableException} receives status 406 with the JSON body (D-397).
 *
 * <p>Handler selection follows Spring's exception-depth order: each APIkit branch receives its listed
 * exception types and their subclasses, and {@link #unexpected(Exception, HttpServletResponse)}
 * receives every exception no branch lists. Usage, as seen by a client of the running application:
 *
 * <pre>{@code
 * PATCH /api/munit          -> 405 Content-Type: application/json  { "message": "Method not allowed" }
 * GET   /api/munit          -> 406 (with Accept: text/plain)        { "message": "Not acceptable" }
 * GET   /other              -> 404 Content-Type: application/json  { "message": "Resource not found" }
 * }</pre>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the handler: ERROR for the HTTP default rule, DEBUG for the APIkit branches. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Body of the 404 branch, the decoded {@code set-payload} value at api.xml:14 (D-007). */
    private static final String RESOURCE_NOT_FOUND_BODY = "{ \"message\": \"Resource not found\" }";

    /** Body of the 405 branch, the decoded {@code set-payload} value at api.xml:19 (D-007). */
    private static final String METHOD_NOT_ALLOWED_BODY = "{ \"message\": \"Method not allowed\" }";

    /** Body of the 415 branch, the decoded {@code set-payload} value at api.xml:24 (D-007). */
    private static final String UNSUPPORTED_MEDIA_TYPE_BODY = "{ \"message\": \"Unsupported media type\" }";

    /** Body of the 406 branch, the decoded {@code set-payload} value at api.xml:29 (D-007). */
    private static final String NOT_ACCEPTABLE_BODY = "{ \"message\": \"Not acceptable\" }";

    /** Body of the 400 branch, the decoded {@code set-payload} value at api.xml:34 (D-007). */
    private static final String BAD_REQUEST_BODY = "{ \"message\": \"Bad request\" }";

    /**
     * APIkit branch 1, {@code NotFoundException} [api.xml:11-15]: answers 404 with
     * {@code Content-Type: application/json} and the body {@code { "message": "Resource not found" }}.
     *
     * <p>Receives {@link NoHandlerFoundException} (a request no handler maps, for example
     * {@code /api/unknown} or {@code /other}), {@link NoResourceFoundException} and the project
     * {@link NotFoundException}.
     *
     * @param e the exception raised for the request
     * @return the 404 response with the literal JSON body
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, NotFoundException.class})
    public ResponseEntity<byte[]> notFound(Exception e) {
        return apikitResponse(HttpStatus.NOT_FOUND, RESOURCE_NOT_FOUND_BODY, e);
    }

    /**
     * APIkit branch 2, {@code MethodNotAllowedException} [api.xml:16-20]: answers 405 with
     * {@code Content-Type: application/json} and the body {@code { "message": "Method not allowed" }}.
     * No {@code Allow} header is sent.
     *
     * <p>Receives {@link HttpRequestMethodNotSupportedException}, for example {@code PATCH}, {@code HEAD}
     * or {@code OPTIONS} on {@code /api/munit} (D-397).
     *
     * @param e the exception raised for the request
     * @return the 405 response with the literal JSON body
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<byte[]> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return apikitResponse(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY, e);
    }

    /**
     * APIkit branch 3, {@code UnsupportedMediaTypeException} [api.xml:21-25]: answers 415 with
     * {@code Content-Type: application/json} and the body
     * {@code { "message": "Unsupported media type" }}.
     *
     * <p>Receives {@link HttpMediaTypeNotSupportedException}, raised for a request body whose media type
     * the RAML method does not declare.
     *
     * @param e the exception raised for the request
     * @return the 415 response with the literal JSON body
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<byte[]> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return apikitResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_BODY, e);
    }

    /**
     * APIkit branch 4, {@code NotAcceptableException} [api.xml:26-30]: answers 406 with
     * {@code Content-Type: application/json} and the body {@code { "message": "Not acceptable" }},
     * whatever media types the request's {@code Accept} header lists.
     *
     * <p>Receives {@link HttpMediaTypeNotAcceptableException}, raised for a request whose {@code Accept}
     * header excludes the media type the RAML method returns.
     *
     * @param e the exception raised for the request
     * @return the 406 response with the literal JSON body
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<byte[]> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return apikitResponse(HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE_BODY, e);
    }

    /**
     * APIkit branch 5, {@code BadRequestException} [api.xml:31-35]: answers 400 with
     * {@code Content-Type: application/json} and the body {@code { "message": "Bad request" }}.
     *
     * <p>Receives the project {@link BadRequestException} (a RAML contract violation),
     * {@link MissingServletRequestParameterException}, {@link HttpMessageNotReadableException} and
     * {@link MethodArgumentTypeMismatchException}.
     *
     * @param e the exception raised for the request
     * @return the 400 response with the literal JSON body
     */
    @ExceptionHandler({
        BadRequestException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<byte[]> badRequest(Exception e) {
        return apikitResponse(HttpStatus.BAD_REQUEST, BAD_REQUEST_BODY, e);
    }

    /**
     * HTTP default rule: answers 500 for every exception that no APIkit branch above receives.
     *
     * <p>Logs the exception at ERROR with its message and stack trace, then writes status 500 and the
     * UTF-8 bytes of {@link Exception#getMessage()} through {@link RawBody#write}, which sets no
     * {@code Content-Type} header (D-066). A {@code null} message writes an empty body.
     *
     * @param e        the exception raised for the request
     * @param response the servlet response the 500 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception e, HttpServletResponse response) throws IOException {
        LOG.error("Request failed with {}; answering HTTP 500", e.getClass().getName(), e);
        String message = e.getMessage();
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), body);
    }

    /**
     * Builds an APIkit branch response: the given status, {@code Content-Type: application/json} and the
     * UTF-8 bytes of {@code body}, freshly encoded for each response. Logs the exception class and the
     * status at DEBUG.
     *
     * @param status the HTTP status of the branch
     * @param body   the literal JSON body of the branch
     * @param e      the exception the branch receives
     * @return the response entity
     */
    private static ResponseEntity<byte[]> apikitResponse(HttpStatus status, String body, Exception e) {
        LOG.debug("{} answered with HTTP {}", e.getClass().getName(), status.value());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
