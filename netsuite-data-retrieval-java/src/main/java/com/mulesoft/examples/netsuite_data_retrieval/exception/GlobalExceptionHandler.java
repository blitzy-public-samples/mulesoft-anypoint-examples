package com.mulesoft.examples.netsuite_data_retrieval.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.netsuite_data_retrieval.controller.RawBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 * Answers request failures with the APIkit mapping strategy
 * {@code netsuite-api-apiKitGlobalExceptionMapping}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:107-133], which the router flow
 * {@code netsuite-api-main} [netsuite-api.xml:20-24] references at netsuite-api.xml:23, plus the
 * NetSuite connector failure modes (D-020) and the HTTP default rule (HTTP 500). The class is
 * project-local (D-004).
 *
 * <p>The advice has no {@code basePackages}, {@code assignableTypes} or {@code annotations}
 * selector: it applies to every controller of the project, {@code ApiConsoleController} included,
 * and to a request that no handler maps.
 *
 * <p>Every handler returns {@code void} and writes status, headers and body itself through
 * {@link RawBody}; Spring writes nothing for the request and negotiates no media type against the
 * request's {@code Accept} header (D-189).
 *
 * <table>
 *   <caption>Handlers, statuses and bodies</caption>
 *   <tr><th>Method</th><th>Status</th><th>{@code Content-Type}</th><th>Body</th><th>Source</th></tr>
 *   <tr><td>{@link #notFound}</td><td>404</td><td>{@code application/json}</td>
 *       <td>{@code { "message": "Resource not found" }}</td><td>netsuite-api.xml:108-112</td></tr>
 *   <tr><td>{@link #methodNotAllowed}</td><td>405</td><td>{@code application/json}</td>
 *       <td>{@code { "message": "Method not allowed" }}</td><td>netsuite-api.xml:113-117</td></tr>
 *   <tr><td>{@link #unsupportedMediaType}</td><td>415</td><td>{@code application/json}</td>
 *       <td>{@code { "message": "Unsupported media type" }}</td><td>netsuite-api.xml:118-122</td></tr>
 *   <tr><td>{@link #notAcceptable}</td><td>406</td><td>{@code application/json}</td>
 *       <td>{@code { "message": "Not acceptable" }}</td><td>netsuite-api.xml:123-127</td></tr>
 *   <tr><td>{@link #badRequest}</td><td>400</td><td>{@code application/json}</td>
 *       <td>{@code { "message": "Bad request" }}</td><td>netsuite-api.xml:128-132</td></tr>
 *   <tr><td>{@link #upstreamAuthentication}</td><td>502</td><td>{@code application/json}</td>
 *       <td>{@link ErrorResponse}</td><td>D-020</td></tr>
 *   <tr><td>{@link #upstreamRateLimit}</td><td>429</td><td>{@code application/json}</td>
 *       <td>{@link ErrorResponse}, plus the vendor {@code Retry-After} when present</td><td>D-020</td></tr>
 *   <tr><td>{@link #upstreamUnavailable}</td><td>503</td><td>{@code application/json}</td>
 *       <td>{@link ErrorResponse}</td><td>D-020</td></tr>
 *   <tr><td>{@link #unexpected}</td><td>500</td><td>none</td>
 *       <td>the exception message, UTF-8</td><td>HTTP default rule (D-007, D-066)</td></tr>
 * </table>
 *
 * <p>The APIkit bodies are the decoded {@code set-payload} literals of the mapping, written byte
 * for byte as their UTF-8 encoding (D-007). No APIkit branch writes an {@code Allow} header, any
 * other header or a custom reason phrase, and no APIkit branch logs. The status line carries
 * Undertow's standard phrase for each status code (D-010).
 *
 * <p>Spring selects the handler whose listed exception type is closest to the raised exception;
 * {@link #unexpected} receives only the exceptions no other handler lists. A NetSuite failure the
 * client reports as a {@code WebClientResponseException}, a NetSuite 404 among them, receives the
 * HTTP default 500.
 *
 * <p>Usage, as seen by a client of the running application:
 *
 * <pre>{@code
 * GET  /api/nope                              -> 404 { "message": "Resource not found" }
 * GET  /api/items?operator=FOO                -> 400 { "message": "Bad request" }
 * POST /api/customers                         -> 405 { "message": "Method not allowed" }
 * GET  /api/customers  (Accept: text/plain)   -> 406 { "message": "Not acceptable" }
 * GET  /api/customers  (NetSuite unreachable) -> 503 {"message":"NetSuite unavailable: queryIds customer","upstreamSystem":"NetSuite"}
 * }</pre>
 *
 * <p>The handler holds no mutable state and is safe for concurrent requests.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the handler: ERROR for the HTTP default rule; no other handler logs. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** {@code upstreamSystem} member of every D-020 {@link ErrorResponse} body. */
    static final String UPSTREAM_SYSTEM = "NetSuite";

    /** Body of the 404 branch: the decoded {@code set-payload} value at netsuite-api.xml:111. */
    private static final String NOT_FOUND_BODY = "{ \"message\": \"Resource not found\" }";

    /** Body of the 405 branch: the decoded {@code set-payload} value at netsuite-api.xml:116. */
    private static final String METHOD_NOT_ALLOWED_BODY = "{ \"message\": \"Method not allowed\" }";

    /** Body of the 415 branch: the decoded {@code set-payload} value at netsuite-api.xml:121. */
    private static final String UNSUPPORTED_MEDIA_TYPE_BODY = "{ \"message\": \"Unsupported media type\" }";

    /** Body of the 406 branch: the decoded {@code set-payload} value at netsuite-api.xml:126. */
    private static final String NOT_ACCEPTABLE_BODY = "{ \"message\": \"Not acceptable\" }";

    /** Body of the 400 branch: the decoded {@code set-payload} value at netsuite-api.xml:131. */
    private static final String BAD_REQUEST_BODY = "{ \"message\": \"Bad request\" }";

    /** Serializes the D-020 {@link ErrorResponse} bodies. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the handler over Spring Boot's auto-configured Jackson mapper.
     *
     * @param objectMapper the mapper that writes the {@link ErrorResponse} bodies
     * @throws NullPointerException if {@code objectMapper} is {@code null}
     */
    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * APIkit branch 1, {@code org.mule.module.apikit.exception.NotFoundException}
     * [netsuite-api.xml:108-112]: answers 404 with {@code Content-Type: application/json} and the
     * body {@code { "message": "Resource not found" }} [netsuite-api.xml:111].
     *
     * <p>Receives {@link NoHandlerFoundException} (a request no handler maps, inside or outside
     * {@code /api}, for example {@code GET /api/nope} or {@code GET /nope}),
     * {@link NoResourceFoundException} and the project {@link NotFoundException}.
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 404 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, NotFoundException.class})
    public void notFound(Exception ex, HttpServletResponse response) throws IOException {
        writeApikitBody(response, HttpStatus.NOT_FOUND.value(), NOT_FOUND_BODY);
    }

    /**
     * APIkit branch 2, {@code org.mule.module.apikit.exception.MethodNotAllowedException}
     * [netsuite-api.xml:113-117]: answers 405 with {@code Content-Type: application/json} and the
     * body {@code { "message": "Method not allowed" }} [netsuite-api.xml:116]. No {@code Allow}
     * header is written.
     *
     * <p>Receives {@link HttpRequestMethodNotSupportedException}, for example {@code POST /api/customers}.
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 405 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        writeApikitBody(response, HttpStatus.METHOD_NOT_ALLOWED.value(), METHOD_NOT_ALLOWED_BODY);
    }

    /**
     * APIkit branch 3, {@code org.mule.module.apikit.exception.UnsupportedMediaTypeException}
     * [netsuite-api.xml:118-122]: answers 415 with {@code Content-Type: application/json} and the
     * body {@code { "message": "Unsupported media type" }} [netsuite-api.xml:121].
     *
     * <p>Receives {@link HttpMediaTypeNotSupportedException}. No request to the NetSuite API raises
     * it over HTTP; the README lists the missing 415 error case (D-062).
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 415 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public void unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletResponse response)
            throws IOException {
        writeApikitBody(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), UNSUPPORTED_MEDIA_TYPE_BODY);
    }

    /**
     * APIkit branch 4, {@code org.mule.module.apikit.exception.NotAcceptableException}
     * [netsuite-api.xml:123-127]: answers 406 with {@code Content-Type: application/json} and the
     * body {@code { "message": "Not acceptable" }} [netsuite-api.xml:126], whatever media types the
     * request's {@code Accept} header lists.
     *
     * <p>Receives {@link HttpMediaTypeNotAcceptableException}, for example {@code GET /api/customers}
     * with {@code Accept: text/plain}.
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 406 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletResponse response)
            throws IOException {
        writeApikitBody(response, HttpStatus.NOT_ACCEPTABLE.value(), NOT_ACCEPTABLE_BODY);
    }

    /**
     * APIkit branch 5, {@code org.mule.module.apikit.exception.BadRequestException}
     * [netsuite-api.xml:128-132]: answers 400 with {@code Content-Type: application/json} and the
     * body {@code { "message": "Bad request" }} [netsuite-api.xml:131]. The exception message is not
     * written.
     *
     * <p>Receives the project {@link BadRequestException} (a RAML contract violation or a refused
     * query value, for example {@code GET /api/items?operator=FOO} or {@code quantity=abc}),
     * {@link MissingServletRequestParameterException}, {@link MethodArgumentTypeMismatchException}
     * and {@link HttpMessageNotReadableException}.
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 400 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({
        BadRequestException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class
    })
    public void badRequest(Exception ex, HttpServletResponse response) throws IOException {
        writeApikitBody(response, HttpStatus.BAD_REQUEST.value(), BAD_REQUEST_BODY);
    }

    /**
     * D-020 authentication failure: answers 502 with {@code Content-Type: application/json} and the
     * {@link ErrorResponse} JSON of the exception message and {@code upstreamSystem}
     * {@code NetSuite}, for example
     * {@code {"message":"NetSuite authentication failed: queryIds customer","upstreamSystem":"NetSuite"}}
     * (D-007).
     *
     * <p>Receives {@link UpstreamAuthenticationException}, raised by the NetSuite client after one
     * re-authentication and retry.
     *
     * @param ex       the exception raised by the NetSuite client
     * @param response the servlet response the 502 answer is written to
     * @throws IOException if the body cannot be serialized or the response body cannot be written
     */
    @ExceptionHandler(UpstreamAuthenticationException.class)
    public void upstreamAuthentication(UpstreamAuthenticationException ex, HttpServletResponse response)
            throws IOException {
        writeUpstreamBody(response, HttpStatus.BAD_GATEWAY.value(), ex);
    }

    /**
     * D-020 rate limit: answers 429 with {@code Content-Type: application/json} and the
     * {@link ErrorResponse} JSON of the exception message and {@code upstreamSystem}
     * {@code NetSuite} (D-007).
     *
     * <p>When {@link UpstreamRateLimitException#getRetryAfter()} is not {@code null}, the response
     * carries the header {@code Retry-After} with that value, unchanged; otherwise the response has
     * no {@code Retry-After} header.
     *
     * @param ex       the exception raised by the NetSuite client for a NetSuite HTTP 429
     * @param response the servlet response the 429 answer is written to
     * @throws IOException if the body cannot be serialized or the response body cannot be written
     */
    @ExceptionHandler(UpstreamRateLimitException.class)
    public void upstreamRateLimit(UpstreamRateLimitException ex, HttpServletResponse response) throws IOException {
        String retryAfter = ex.getRetryAfter();
        if (retryAfter != null) {
            response.setHeader("Retry-After", retryAfter);
        }
        writeUpstreamBody(response, HttpStatus.TOO_MANY_REQUESTS.value(), ex);
    }

    /**
     * D-020 timeout or connectivity failure: answers 503 with {@code Content-Type: application/json}
     * and the {@link ErrorResponse} JSON of the exception message and {@code upstreamSystem}
     * {@code NetSuite}, for example
     * {@code {"message":"NetSuite unavailable: queryIds customer","upstreamSystem":"NetSuite"}}
     * (D-007).
     *
     * @param ex       the exception raised by the NetSuite client after its single attempt
     * @param response the servlet response the 503 answer is written to
     * @throws IOException if the body cannot be serialized or the response body cannot be written
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    public void upstreamUnavailable(UpstreamUnavailableException ex, HttpServletResponse response)
            throws IOException {
        writeUpstreamBody(response, HttpStatus.SERVICE_UNAVAILABLE.value(), ex);
    }

    /**
     * HTTP default rule (D-007): answers 500 for every exception that no other handler of this class
     * receives, a {@code WebClientResponseException} from the NetSuite client included.
     *
     * <p>Logs the exception at ERROR with its class name and stack trace, then writes status 500 and
     * the UTF-8 bytes of {@link Exception#getMessage()} through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sends no {@code Content-Type}
     * header (D-066). A {@code null} message writes an empty body.
     *
     * @param ex       the exception raised for the request
     * @param response the servlet response the 500 answer is written to
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Request failed with {}; answering HTTP 500", ex.getClass().getName(), ex);
        String message = ex.getMessage();
        byte[] body = message == null ? null : message.getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), body);
    }

    /**
     * Writes an APIkit branch answer: {@code status}, {@code Content-Type: application/json} and the
     * UTF-8 bytes of {@code body}, encoded for each response.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status of the branch
     * @param body     the literal JSON body of the branch
     * @throws IOException if the response body cannot be written
     */
    private static void writeApikitBody(HttpServletResponse response, int status, String body) throws IOException {
        RawBody.writeJson(response, status, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a D-020 answer: {@code status}, {@code Content-Type: application/json} and the Jackson
     * serialization of {@code ErrorResponse(ex.getMessage(), "NetSuite")}. A {@code null} message is
     * omitted from the body (D-007).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status of the failure mode
     * @param ex       the upstream exception raised by the NetSuite client
     * @throws IOException if the body cannot be serialized or the response body cannot be written
     */
    private void writeUpstreamBody(HttpServletResponse response, int status, RuntimeException ex) throws IOException {
        byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse(ex.getMessage(), UPSTREAM_SYSTEM));
        RawBody.writeJson(response, status, body);
    }
}
