package com.mulesoft.examples.implementing_a_choice_exception_strategy.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.ValidationService;

/**
 * Receives every HTTP method on {@code /} and delegates to {@link ValidationService} (flow
 * {@code choice-error-handlingFlow1} [choice-error-handling.xml:4-48]).
 *
 * <p>The listener "Recieve HTTP requests" [choice-error-handling.xml:5] has the path {@code /} and
 * no {@code allowedMethods}. {@link #choiceErrorHandlingFlow1(InputStream)} is mapped on {@code /}
 * with no method, media-type, parameter or header condition; a private OPTIONS-only mapping on
 * {@code /} runs the same method for OPTIONS requests (D-607). TRACE requests and CORS preflight
 * requests do not reach this class (D-607). No other path is mapped here.
 *
 * <p>Answers, none of which carries a {@code Content-Type} header (D-066):
 * <ul>
 *   <li>input that passes validation: {@code HTTP/1.1 200 OK}, {@code Content-Length: 29} and the
 *       body {@code Input data validation passed.} [:14];</li>
 *   <li>an {@code email} value the regex filter rejects [:13]: {@code HTTP/1.1 200 OK},
 *       {@code Content-Length: 0} and no body;</li>
 *   <li>every exception raised by {@link ValidationService#choiceErrorHandlingFlow1(byte[])} or by
 *       reading the body leaves this class unchanged and is answered by
 *       {@code exception.GlobalExceptionHandler}: 400 for each of the two branches of the
 *       {@code choice-exception-strategy} [:15-47], 500 for the default strategy.</li>
 * </ul>
 *
 * <p>The request body is read unchanged from the request stream for every method and
 * {@code Content-Type} (D-091). The controller parses, validates, filters and builds no text itself,
 * and holds no per-request state.
 */
@RestController
public class ValidationController {

    /** The service that runs the body of the flow [choice-error-handling.xml:8-14]. */
    private final ValidationService validationService;

    /**
     * Creates the controller.
     *
     * @param validationService the service each request body is passed to
     */
    public ValidationController(ValidationService validationService) {
        this.validationService = validationService;
    }

    /**
     * Reads the raw request body, returns 200 with the service text or an empty body; no
     * {@code Content-Type} is set (D-066, D-091, D-426).
     *
     * <p>Mapped on {@code /} with no method, media-type, parameter or header condition; OPTIONS
     * requests reach it through the OPTIONS-only mapping (D-607). The steps, in order:
     * <ol>
     *   <li>every byte of {@code body} is read, unchanged;</li>
     *   <li>the bytes are passed once to {@link ValidationService#choiceErrorHandlingFlow1(byte[])};</li>
     *   <li>a present result is returned with status 200 and its UTF-8 bytes
     *       ({@link RawBody#of(String)}); an empty result is returned with status 200 and a
     *       zero-length body ({@link RawBody#empty()}).</li>
     * </ol>
     *
     * <p>Example: {@code POST /} with the body
     * {@code {"email":"aaa@aaa.aa","item name":"aa","item units":10,"item price per unit":1,"membership":"free"}}
     * answers {@code HTTP/1.1 200 OK}, {@code Content-Length: 29} and
     * {@code Input data validation passed.}; the same body with {@code "email":"bad"} answers
     * {@code HTTP/1.1 200 OK} and {@code Content-Length: 0}.
     *
     * @param body the request body stream, as received
     * @return status 200 with the reply text, or status 200 with an empty body
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping("/")
    public ResponseEntity<RawBody> choiceErrorHandlingFlow1(InputStream body) throws IOException {
        byte[] bytes = body.readAllBytes();
        Optional<String> result = validationService.choiceErrorHandlingFlow1(bytes);
        if (result.isPresent()) {
            return ResponseEntity.ok(RawBody.of(result.get()));
        }
        return ResponseEntity.ok(RawBody.empty());
    }

    /**
     * Answers an OPTIONS request on {@code /} through {@link #choiceErrorHandlingFlow1(InputStream)},
     * with the same status and body as every other method and no {@code Content-Type} or
     * {@code Allow} header (D-066, D-607).
     *
     * @param body the request body stream, as received
     * @return the response of {@link #choiceErrorHandlingFlow1(InputStream)} for {@code body}
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    private ResponseEntity<RawBody> choiceErrorHandlingFlow1OnOptions(InputStream body) throws IOException {
        return choiceErrorHandlingFlow1(body);
    }
}
