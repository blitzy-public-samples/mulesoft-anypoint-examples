package com.mulesoft.examples.testing_apikit_with_munit.controller;

import com.mulesoft.examples.testing_apikit_with_munit.service.MunitResourceService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers the four methods of the RAML resource {@code /munit} under the base path
 * {@code apikit.api-config.base-path} ({@code /api} by default). This one controller serves the
 * one top-level resource of the API (D-061).
 *
 * <p>Each method reads no path variable, query parameter, header or body, accepts a request of any
 * {@code Content-Type} or none, delegates to {@link MunitResourceService} and answers with the
 * status the RAML declares, the header {@code Content-Type: application/json} with no
 * {@code charset} parameter (D-066), and the UTF-8 bytes of the service text, unquoted (D-380):
 *
 * <pre>{@code
 * GET    /api/munit   200   GET RESPONSE
 * POST   /api/munit   201   POST RESPONSE
 * PUT    /api/munit   201   PUT RESPONSE
 * DELETE /api/munit   200   DELETE RESPONSE
 * }</pre>
 *
 * <p>Every mapping produces {@code application/json} only, and no {@code HEAD} or {@code OPTIONS}
 * mapping is declared. A request whose {@code Accept} header excludes {@code application/json}
 * raises {@code HttpMediaTypeNotAcceptableException} in Spring MVC. Any other method on
 * {@code /api/munit} raises {@code HttpRequestMethodNotSupportedException}: Spring MVC raises it
 * for an unmapped method such as {@code PATCH}, and {@code config.RamlRequestValidator} for
 * {@code HEAD} and {@code OPTIONS}. The project's {@code exception.GlobalExceptionHandler} answers
 * both exceptions with the APIkit bodies.
 */
@RestController
@RequestMapping("${apikit.api-config.base-path}")
public class MunitController {

    /** Supplies the response text of each {@code /munit} method. */
    private final MunitResourceService service;

    /**
     * Creates the controller over the service that supplies the response texts.
     *
     * @param service the {@code /munit} resource service
     */
    public MunitController(MunitResourceService service) {
        this.service = service;
    }

    /**
     * Answers {@code GET /api/munit} with 200 and the text of {@link MunitResourceService#getMunit()}
     * as {@code application/json} (D-066).
     *
     * @return status 200, {@code Content-Type: application/json} and the body {@code GET RESPONSE}
     */
    @GetMapping(path = "/munit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> getMunit() {
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.getMunit().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers {@code POST /api/munit} with 201 and the text of
     * {@link MunitResourceService#postMunit()} as {@code application/json} (D-066). The request
     * body is not read.
     *
     * @return status 201, {@code Content-Type: application/json} and the body {@code POST RESPONSE}
     */
    @PostMapping(path = "/munit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> postMunit() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.postMunit().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers {@code PUT /api/munit} with 201 and the text of {@link MunitResourceService#putMunit()}
     * as {@code application/json} (D-066). The request body is not read.
     *
     * @return status 201, {@code Content-Type: application/json} and the body {@code PUT RESPONSE}
     */
    @PutMapping(path = "/munit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> putMunit() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.putMunit().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers {@code DELETE /api/munit} with 200 and the text of
     * {@link MunitResourceService#deleteMunit()} as {@code application/json} (D-066).
     *
     * @return status 200, {@code Content-Type: application/json} and the body
     *         {@code DELETE RESPONSE}
     */
    @DeleteMapping(path = "/munit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> deleteMunit() {
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.deleteMunit().getBytes(StandardCharsets.UTF_8));
    }
}
