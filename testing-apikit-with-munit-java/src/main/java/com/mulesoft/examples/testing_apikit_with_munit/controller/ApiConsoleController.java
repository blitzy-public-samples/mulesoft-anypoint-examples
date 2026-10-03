package com.mulesoft.examples.testing_apikit_with_munit.controller;

import com.mulesoft.examples.testing_apikit_with_munit.config.RamlModel;
import com.mulesoft.examples.testing_apikit_with_munit.exception.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the interactive API console of the APIkit router {@code api-config}
 * [testing-apikit-with-munit/src/main/app/api.xml:3 ({@code apikit:config consoleEnabled="true"
 * consolePath="console"})] under {@code <apikit.api-config.base-path>/<apikit.api-config.console-path>},
 * {@code /api/console} with the committed {@code application.yml} (D-009).
 *
 * <p>The controller maps exactly four {@code GET} routes:
 *
 * <table>
 *   <caption>Console routes</caption>
 *   <tr><th>Route</th><th>Method</th><th>Answer</th></tr>
 *   <tr><td>{@code /api/console}</td><td>{@link #redirect(HttpServletResponse)}</td>
 *       <td>302, {@code Location: /api/console/}, no body and no {@code Content-Type} (D-066)</td></tr>
 *   <tr><td>{@code /api/console/}</td><td>{@link #index()}</td>
 *       <td>200, {@code Content-Type: text/html;charset=UTF-8}, the bytes of
 *       {@code classpath:static/console/index.html}</td></tr>
 *   <tr><td>{@code /api/console/api.json}</td><td>{@link #apiJson()}</td>
 *       <td>200, {@code Content-Type: application/json}, the {@link RamlModel.ConsoleModel} of
 *       {@link RamlModel#consoleModel()} written by Jackson</td></tr>
 *   <tr><td>{@code /api/console/{file}}</td><td>{@link #file(String)}</td>
 *       <td>{@code console.js}: 200, {@code Content-Type: application/javascript}, the bytes of
 *       {@code classpath:static/console/console.js}; {@code api.raml}: 200,
 *       {@code Content-Type: application/raml+yaml}, the bytes of {@link RamlModel#ramlBytes()};
 *       both with {@code Content-Disposition: inline; filename="<file>"}; any other name:
 *       {@link NotFoundException}</td></tr>
 * </table>
 *
 * <p>The page {@code index.html} loads {@code console.js} and links {@code api.raml}, and
 * {@code console.js} fetches {@code api.json}; each name is relative to {@code /api/console/}. A
 * {@link NotFoundException} reaches {@code exception.GlobalExceptionHandler}, which answers 404 with
 * {@code Content-Type: application/json} and the body {@code { "message": "Resource not found" }}.
 * A deeper path such as {@code /api/console/a/b} matches no route and receives the same APIkit 404
 * answer through {@code spring.mvc.throw-exception-if-no-handler-found}. The controller holds no
 * state beyond its constructor arguments.
 *
 * <pre>{@code
 * GET /api/console              -> 302 Location: /api/console/
 * GET /api/console/             -> 200 text/html;charset=UTF-8       index.html
 * GET /api/console/api.json     -> 200 application/json              {"title":"Sample API","basePath":"/api",...}
 * GET /api/console/console.js   -> 200 application/javascript        console.js
 * GET /api/console/api.raml     -> 200 application/raml+yaml         api.raml
 * GET /api/console/nope         -> 404 application/json              { "message": "Resource not found" }
 * }</pre>
 */
@Controller
@RequestMapping("${apikit.api-config.base-path}/${apikit.api-config.console-path}")
public class ApiConsoleController {

    /** Classpath location of the console page. */
    private static final String INDEX_LOCATION = "static/console/index.html";

    /** Classpath location of the console script. */
    private static final String SCRIPT_LOCATION = "static/console/console.js";

    /** Media type of the served RAML file. */
    private static final MediaType RAML_MEDIA_TYPE = MediaType.parseMediaType("application/raml+yaml");

    /** Media type of the served console script. */
    private static final MediaType JAVASCRIPT_MEDIA_TYPE = MediaType.parseMediaType("application/javascript");

    /** Media type of the served console page, written as {@code text/html;charset=UTF-8}. */
    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");

    /** Name of the console script below the console path. */
    private static final String SCRIPT_NAME = "console.js";

    /** Name of the RAML file below the console path. */
    private static final String RAML_NAME = "api.raml";

    /** Source of the console model and of the RAML bytes. */
    private final RamlModel ramlModel;

    /** Listener base path of the router ({@code apikit.api-config.base-path}, {@code /api}). */
    private final String basePath;

    /** Console path below the base path ({@code apikit.api-config.console-path}, {@code console}). */
    private final String consolePath;

    /**
     * Creates the controller over the RAML model and the two path keys of the redirect target.
     *
     * @param ramlModel   the model of the copied RAML contract
     * @param basePath    listener base path of the router ({@code apikit.api-config.base-path})
     * @param consolePath console path below the base path ({@code apikit.api-config.console-path})
     */
    public ApiConsoleController(RamlModel ramlModel,
                                @Value("${apikit.api-config.base-path}") String basePath,
                                @Value("${apikit.api-config.console-path}") String consolePath) {
        this.ramlModel = ramlModel;
        this.basePath = basePath;
        this.consolePath = consolePath;
    }

    /**
     * Answers {@code GET /api/console} with 302 and the header
     * {@code Location: <base-path>/<console-path>/}, {@code /api/console/}, written as set, without
     * a body and without a {@code Content-Type} header, through {@link RawBody#write} (D-066, D-009).
     *
     * @param response the servlet response the redirect is written to
     * @throws IOException if the response cannot be written
     */
    @GetMapping("")
    public void redirect(HttpServletResponse response) throws IOException {
        response.setHeader("Location", basePath + "/" + consolePath + "/");
        RawBody.write(response, HttpServletResponse.SC_FOUND, new byte[0]);
    }

    /**
     * Answers {@code GET /api/console/} with 200, {@code Content-Type: text/html;charset=UTF-8} and
     * the bytes of {@code classpath:static/console/index.html}, unchanged (D-009).
     *
     * @return the console page response
     * @throws IOException if the page cannot be read from the classpath
     */
    @GetMapping("/")
    public ResponseEntity<byte[]> index() throws IOException {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(readClasspath(INDEX_LOCATION));
    }

    /**
     * Answers {@code GET /api/console/api.json} with 200 and the console model of the copied RAML,
     * written by Jackson as {@code application/json}: {@code title}, {@code basePath} and every
     * resource with its methods, parameters, request bodies and responses (D-009).
     *
     * @return the console model, the same immutable instance on every call
     */
    @GetMapping(path = "/api.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RamlModel.ConsoleModel apiJson() {
        return ramlModel.consoleModel();
    }

    /**
     * Answers {@code GET /api/console/{file}} for the two console files below the console path
     * (D-009):
     *
     * <ul>
     *   <li>{@code console.js}: 200, {@code Content-Type: application/javascript} and the bytes of
     *       {@code classpath:static/console/console.js}, unchanged;</li>
     *   <li>{@code api.raml}: 200, {@code Content-Type: application/raml+yaml} and the bytes of the
     *       copied RAML {@code classpath:api/api.raml}, unchanged, from {@link RamlModel#ramlBytes()};</li>
     *   <li>any other name: throws {@link NotFoundException}, answered with the APIkit 404.</li>
     * </ul>
     *
     * <p>Each 200 answer carries {@code Content-Disposition: inline; filename="<file>"}.
     *
     * @param file the last path segment below {@code /api/console/}
     * @return the file response
     * @throws IOException       if the console script cannot be read from the classpath
     * @throws NotFoundException if {@code file} names no console file
     */
    @GetMapping("/{file}")
    public ResponseEntity<byte[]> file(@PathVariable("file") String file) throws IOException {
        return switch (file) {
            case SCRIPT_NAME -> consoleFile(SCRIPT_NAME, JAVASCRIPT_MEDIA_TYPE, readClasspath(SCRIPT_LOCATION));
            case RAML_NAME -> consoleFile(RAML_NAME, RAML_MEDIA_TYPE, ramlModel.ramlBytes());
            default -> throw new NotFoundException("Console file not found: " + file);
        };
    }

    /**
     * Builds the 200 answer of one console file: the media type, the header
     * {@code Content-Disposition: inline; filename="<name>"} and the bytes, unchanged. With this
     * header set, Spring MVC adds no {@code Content-Disposition: inline;filename=f.txt} of its own.
     *
     * @param name      the served file name, {@code console.js} or {@code api.raml}
     * @param mediaType the media type of the file
     * @param body      the file bytes
     * @return the file response
     */
    private static ResponseEntity<byte[]> consoleFile(String name, MediaType mediaType, byte[] body) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .body(body);
    }

    /**
     * Reads every byte of a classpath resource.
     *
     * @param location classpath location, for example {@code static/console/index.html}
     * @return the resource bytes, unchanged
     * @throws IOException if the resource is missing or cannot be read
     */
    private byte[] readClasspath(String location) throws IOException {
        return new ClassPathResource(location).getContentAsByteArray();
    }
}
