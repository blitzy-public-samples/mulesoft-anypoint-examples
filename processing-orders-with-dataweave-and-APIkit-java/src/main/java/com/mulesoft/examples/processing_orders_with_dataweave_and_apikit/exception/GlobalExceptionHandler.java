package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller.RawBody;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Translates exceptions into the HTTP responses of the APIkit mapping strategy
 * {@code currency-apiKitGlobalExceptionMapping} referenced by the router flow {@code currency-main}:
 * literal JSON bodies for 404, 405, 415, 406 and 400 (D-007), and a 500 with the exception message and
 * no {@code Content-Type} for anything else (D-066).
 *
 * <p>The five mapping branches and their answers, each with {@code Content-Type: application/json}
 * exactly and the body bytes below in UTF-8:
 * <ul>
 *   <li>{@link #notFound}: 404 {@code { "message": "Resource not found" }} for a path under
 *       {@code /api}; any other path answers 404 {@code No listener for endpoint: <path>} with no
 *       {@code Content-Type} (D-157);</li>
 *   <li>{@link #methodNotAllowed}: 405 {@code { "message": "Method not allowed" }};</li>
 *   <li>{@link #unsupportedMediaType}: 415 {@code { "message": "Unsupported media type" }};</li>
 *   <li>{@link #notAcceptable}: 406 {@code { "message": "Not acceptable" }};</li>
 *   <li>{@link #badRequest}: 400 {@code { "message": "Bad request" }}.</li>
 * </ul>
 *
 * <p>{@link #unexpected} answers every other exception with 500, the exception message as the body
 * and no {@code Content-Type}, and logs it at ERROR. Every handler resets the response before it
 * writes, adds no {@code Allow} header and leaves an already committed response unchanged. The
 * advice applies to every handler, including requests that no handler maps.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NOT_FOUND_BODY = "{ \"message\": \"Resource not found\" }";

    private static final String METHOD_NOT_ALLOWED_BODY = "{ \"message\": \"Method not allowed\" }";

    private static final String UNSUPPORTED_MEDIA_TYPE_BODY = "{ \"message\": \"Unsupported media type\" }";

    private static final String NOT_ACCEPTABLE_BODY = "{ \"message\": \"Not acceptable\" }";

    private static final String BAD_REQUEST_BODY = "{ \"message\": \"Bad request\" }";

    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    private static final String JSON_CONTENT_TYPE = "application/json";

    // Literal prefix of the currency-main listener path /api/*, the same prefix the controller and
    // validator mappings use; listener.currency-http-listener-config.base-path is not read here (D-140).
    private static final String API_BASE_PATH = "/api";

    /**
     * Answers 404 with {@code { "message": "Resource not found" }} for a path under {@code /api}, and
     * 404 with {@code No listener for endpoint: <path>} for any other path.
     *
     * @param ex       the unmatched-path or missing-resource exception
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, NotFoundException.class})
    public void notFound(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (isApiPath(request)) {
            writeJson(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_BODY);
        } else {
            noListener(request, response);
        }
    }

    /**
     * Answers 405 with {@code { "message": "Method not allowed" }}.
     *
     * @param ex       the unsupported-method exception
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        writeJson(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY);
    }

    /**
     * Answers 415 with {@code { "message": "Unsupported media type" }}.
     *
     * @param ex       the unsupported request media type exception
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class})
    public void unsupportedMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        writeJson(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_BODY);
    }

    /**
     * Answers 406 with {@code { "message": "Not acceptable" }}, whatever media types the request accepts.
     *
     * @param ex       the not-acceptable exception
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({HttpMediaTypeNotAcceptableException.class})
    public void notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        writeJson(response, HttpServletResponse.SC_NOT_ACCEPTABLE, NOT_ACCEPTABLE_BODY);
    }

    /**
     * Answers 400 with {@code { "message": "Bad request" }}.
     *
     * @param ex       the contract violation, missing parameter, unreadable body or parameter type mismatch
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({BadRequestException.class, MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public void badRequest(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        writeJson(response, HttpServletResponse.SC_BAD_REQUEST, BAD_REQUEST_BODY);
    }

    /**
     * Answers 500 with the exception message as the body, an empty body for a {@code null} message, and
     * no {@code Content-Type} (D-066); logs the exception at ERROR.
     *
     * @param ex       any exception no other handler of this class takes
     * @param request  the request that raised it
     * @param response the response written
     * @throws IOException if the response body cannot be written
     */
    @ExceptionHandler({Exception.class})
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        String msg = ex.getMessage();
        byte[] bytes = msg == null ? new byte[0] : msg.getBytes(StandardCharsets.UTF_8);
        if (alreadyCommitted(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR)) {
            return;
        }
        response.reset();
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, bytes);
    }

    // Answers 404 "No listener for endpoint: <path>" for paths outside /api (D-157).
    void noListener(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] bytes = (NO_LISTENER_PREFIX + request.getRequestURI()).getBytes(StandardCharsets.UTF_8);
        if (alreadyCommitted(response, HttpServletResponse.SC_NOT_FOUND)) {
            return;
        }
        response.reset();
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, bytes);
    }

    /**
     * Returns whether the request path, without the context path, is {@code /api} or lies under
     * {@code /api/}.
     *
     * @param request the request whose URI is tested
     * @return {@code true} for {@code /api} and every path under {@code /api/}
     */
    private static boolean isApiPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals(API_BASE_PATH) || path.startsWith(API_BASE_PATH + "/");
    }

    /**
     * Resets {@code response} and writes {@code status}, {@code Content-Type: application/json} with no
     * charset parameter, {@code Content-Length} and the UTF-8 bytes of {@code body} through the output
     * stream; a committed response is left unchanged.
     *
     * @param response the response written
     * @param status   the HTTP status code
     * @param body     the literal JSON body
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        if (alreadyCommitted(response, status)) {
            return;
        }
        response.reset();
        response.setStatus(status);
        response.setContentType(JSON_CONTENT_TYPE);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    /**
     * Returns {@code true} and logs at ERROR when {@code response} is already committed, in which case
     * {@code status} is not written.
     *
     * @param response the response tested
     * @param status   the status named in the log line
     * @return {@code true} when the response is committed
     */
    private static boolean alreadyCommitted(HttpServletResponse response, int status) {
        if (response.isCommitted()) {
            LOG.error("Response already committed; error status {} not written", status);
            return true;
        }
        return false;
    }
}
