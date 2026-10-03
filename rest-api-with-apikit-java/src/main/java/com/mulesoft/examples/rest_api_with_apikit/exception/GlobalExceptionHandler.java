package com.mulesoft.examples.rest_api_with_apikit.exception;

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
 * Answers every failed request of the Leagues API the way the APIkit mapping strategy
 * {@code leagues-apiKitGlobalExceptionMapping} of flow {@code main} did, with the literal bodies of
 * its five mappings (D-007).
 *
 * <p>Each mapped branch answers with {@code Content-Type: application/json} (no charset parameter),
 * the UTF-8 bytes of the original literal body and no other header:
 * <ul>
 *   <li>404 {@code { "message": "Resource not found" }}: an unknown team or match
 *       ({@link NotFoundException}) and every request no handler matches
 *       ({@link NoHandlerFoundException}, {@link NoResourceFoundException}), inside or outside
 *       {@code /api/*} (D-378);</li>
 *   <li>405 {@code { "message": "Method not allowed" }}: a verb the RAML does not declare for the
 *       resource ({@link HttpRequestMethodNotSupportedException});</li>
 *   <li>415 {@code { "message": "Unsupported media type" }}: a request {@code Content-Type} the RAML
 *       does not declare ({@link HttpMediaTypeNotSupportedException});</li>
 *   <li>406 {@code { "message": "Not acceptable" }}: an {@code Accept} header no declared response
 *       type satisfies ({@link HttpMediaTypeNotAcceptableException}); the JSON body is sent whatever
 *       the {@code Accept} header names;</li>
 *   <li>400 {@code { "message": "Bad request" }}: a request that violates the RAML contract
 *       ({@link BadRequestException}, {@link MissingServletRequestParameterException},
 *       {@link HttpMessageNotReadableException}, {@link MethodArgumentTypeMismatchException}).</li>
 * </ul>
 *
 * <p>Two further branches complete the strategy: {@link ConflictException} answers 409 with no body
 * and no {@code Content-Type} (D-008), and any other exception answers 500 with
 * {@code Content-Type: text/plain}, the exception message as the body and one ERROR log entry
 * (D-007, D-379).
 *
 * <p>The message of a mapped exception never reaches a response body. No reason phrase is set; the
 * status line carries Undertow's standard phrase for each status. The advice applies to every
 * controller of the application.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the 500 branch; the mapped branches log nothing. */
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Body of the APIkit {@code NotFoundException} mapping. */
    private static final String NOT_FOUND_BODY = "{ \"message\": \"Resource not found\" }";

    /** Body of the APIkit {@code MethodNotAllowedException} mapping. */
    private static final String METHOD_NOT_ALLOWED_BODY = "{ \"message\": \"Method not allowed\" }";

    /** Body of the APIkit {@code UnsupportedMediaTypeException} mapping. */
    private static final String UNSUPPORTED_MEDIA_TYPE_BODY = "{ \"message\": \"Unsupported media type\" }";

    /** Body of the APIkit {@code NotAcceptableException} mapping. */
    private static final String NOT_ACCEPTABLE_BODY = "{ \"message\": \"Not acceptable\" }";

    /** Body of the APIkit {@code BadRequestException} mapping. */
    private static final String BAD_REQUEST_BODY = "{ \"message\": \"Bad request\" }";

    /**
     * Answers 404 with {@code { "message": "Resource not found" }} ({@code application/json}), as the
     * APIkit {@code NotFoundException} mapping did, for an unknown team or match and for every
     * request no handler matches, inside or outside {@code /api/*} (D-378).
     *
     * @return status 404 with the literal JSON body
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class, NotFoundException.class})
    public ResponseEntity<byte[]> notFound() {
        return json(HttpStatus.NOT_FOUND, NOT_FOUND_BODY);
    }

    /**
     * Answers 405 with {@code { "message": "Method not allowed" }} ({@code application/json}), as the
     * APIkit {@code MethodNotAllowedException} mapping did. No {@code Allow} header is sent.
     *
     * @return status 405 with the literal JSON body
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<byte[]> methodNotAllowed() {
        return json(HttpStatus.METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_BODY);
    }

    /**
     * Answers 415 with {@code { "message": "Unsupported media type" }} ({@code application/json}), as
     * the APIkit {@code UnsupportedMediaTypeException} mapping did.
     *
     * @return status 415 with the literal JSON body
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<byte[]> unsupportedMediaType() {
        return json(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_BODY);
    }

    /**
     * Answers 406 with {@code { "message": "Not acceptable" }} ({@code application/json}), as the
     * APIkit {@code NotAcceptableException} mapping did. The body and its {@code Content-Type} are
     * sent whatever media types the request's {@code Accept} header names.
     *
     * @return status 406 with the literal JSON body
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<byte[]> notAcceptable() {
        return json(HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE_BODY);
    }

    /**
     * Answers 400 with {@code { "message": "Bad request" }} ({@code application/json}), as the APIkit
     * {@code BadRequestException} mapping did, for a RAML contract violation, a missing required
     * query parameter, an unreadable request body and a parameter of the wrong type.
     *
     * @return status 400 with the literal JSON body
     */
    @ExceptionHandler({
        BadRequestException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<byte[]> badRequest() {
        return json(HttpStatus.BAD_REQUEST, BAD_REQUEST_BODY);
    }

    /**
     * Answers 409 with no body and no {@code Content-Type} (D-008), for a team id that already exists
     * and for a score set on a match that has not been played yet.
     *
     * @return status 409 without a body
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Void> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /**
     * Answers 500 for any exception no other branch maps (D-007, D-379): {@code Content-Type: text/plain}
     * and, as the UTF-8 body, the exception message, or the exception class name when the message is
     * {@code null}. Logs the exception, stack trace included, once at ERROR.
     *
     * @param ex the unmapped exception
     * @return status 500 with the exception message as a plain-text body
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unexpected(Exception ex) {
        LOGGER.error("Unhandled exception", ex);
        String text = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds a response with {@code status}, {@code Content-Type: application/json} and the UTF-8
     * bytes of {@code body} unchanged.
     *
     * @param status the response status
     * @param body the literal JSON body
     * @return the response entity
     */
    private static ResponseEntity<byte[]> json(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
