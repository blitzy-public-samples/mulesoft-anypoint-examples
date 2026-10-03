package com.mulesoft.examples.rest_api_with_apikit.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.config.RamlModel;
import com.mulesoft.examples.rest_api_with_apikit.exception.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the interactive API console for {@code leagues.raml} under {@code /api/console} (D-009).
 */
@Controller
public class ApiConsoleController {

    /**
     * Console mapping prefix: the APIkit router listener's base path key followed by the console path
     * key, {@code /api/console} with the default keys (D-140, D-661).
     */
    private static final String CONSOLE =
            "${listener.http-connector.base-path}/${apikit.leagues-config.console-path}";

    private static final String INDEX_HTML = "static/console/index.html";

    private static final String CONSOLE_JS = "static/console/console.js";

    private static final String SCHEMAS = "schemas/";

    private static final String EXAMPLES = "examples/";

    private static final MediaType TEXT_HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private static final MediaType APPLICATION_JAVASCRIPT = MediaType.parseMediaType("application/javascript");

    private static final String APPLICATION_RAML_YAML = "application/raml+yaml";

    private static final Logger log = LoggerFactory.getLogger(ApiConsoleController.class);

    private final RamlModel ramlModel;

    private final ObjectMapper objectMapper;

    private final String consolePath;

    private final String basePath;

    /**
     * Creates the console controller.
     *
     * @param ramlModel    the console model and the contract files of {@code leagues.raml}
     * @param objectMapper the Boot-configured mapper that renders {@code api.json}
     * @param consolePath  {@code apikit.leagues-config.console-path}, {@code console}
     * @param basePath     {@code listener.http-connector.base-path}, {@code /api} (D-140, D-661)
     */
    public ApiConsoleController(RamlModel ramlModel, ObjectMapper objectMapper,
                                @Value("${apikit.leagues-config.console-path}") String consolePath,
                                @Value("${listener.http-connector.base-path}") String basePath) {
        this.ramlModel = ramlModel;
        this.objectMapper = objectMapper;
        this.consolePath = consolePath;
        this.basePath = basePath;
    }

    /**
     * {@code GET /api/console}: 302 with {@code Location: /api/console/}, no body and no {@code Content-Type} (D-066).
     *
     * @param response the servlet response the status and {@code Location} header are written to
     * @throws IOException if the response cannot be written
     */
    @GetMapping(CONSOLE)
    public void redirect(HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.LOCATION, basePath + "/" + consolePath + "/");
        RawBody.write(response, HttpServletResponse.SC_FOUND, new byte[0]);
    }

    /**
     * {@code GET /api/console/}: the console page {@code static/console/index.html} as {@code text/html;charset=UTF-8}.
     *
     * @return 200 with the page bytes
     */
    @GetMapping(CONSOLE + "/")
    public ResponseEntity<byte[]> index() {
        return ResponseEntity.ok().contentType(TEXT_HTML_UTF8).body(classpath(INDEX_HTML));
    }

    /**
     * {@code GET /api/console/console.js}: {@code static/console/console.js} as {@code application/javascript}.
     *
     * @return 200 with the script bytes
     */
    @GetMapping(CONSOLE + "/console.js")
    public ResponseEntity<byte[]> consoleScript() {
        return ResponseEntity.ok().contentType(APPLICATION_JAVASCRIPT).body(classpath(CONSOLE_JS));
    }

    /**
     * {@code GET /api/console/api.json}: {@link RamlModel#consoleModel()} as {@code application/json}.
     *
     * @return 200 with the JSON bytes of the console model
     * @throws JsonProcessingException if the console model cannot be serialised
     */
    @GetMapping(CONSOLE + "/api.json")
    public ResponseEntity<byte[]> apiJson() throws JsonProcessingException {
        byte[] body = objectMapper.writeValueAsBytes(ramlModel.consoleModel());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * {@code GET /api/console/{file}}: the contract file {@code file} ({@code leagues.raml}) unchanged (D-661).
     *
     * @param file the file name
     * @return 200 with the file bytes and the media type of its extension
     */
    @GetMapping(CONSOLE + "/{file}")
    public ResponseEntity<byte[]> file(@PathVariable("file") String file) {
        return contractFile(file, file);
    }

    /**
     * {@code GET /api/console/schemas/{file}}: the included schema {@code schemas/file}, unchanged (D-661).
     *
     * @param file the schema file name
     * @return 200 with the schema bytes as {@code application/json}
     */
    @GetMapping(CONSOLE + "/schemas/{file}")
    public ResponseEntity<byte[]> schema(@PathVariable("file") String file) {
        return contractFile(file, SCHEMAS + file);
    }

    /**
     * {@code GET /api/console/examples/{file}}: the included example {@code examples/file}, unchanged (D-661).
     *
     * @param file the example file name
     * @return 200 with the example bytes as {@code application/json}
     */
    @GetMapping(CONSOLE + "/examples/{file}")
    public ResponseEntity<byte[]> example(@PathVariable("file") String file) {
        return contractFile(file, EXAMPLES + file);
    }

    /**
     * Answers the contract file {@code key} of {@link RamlModel#file(String)} with the media type of
     * {@code name}: {@code .raml} {@code application/raml+yaml}, {@code .json} {@code application/json}.
     *
     * @param name the requested file name, one path segment
     * @param key  the {@code RamlModel} key: {@code name}, {@code schemas/name} or {@code examples/name}
     * @return 200 with the unchanged file bytes
     * @throws NotFoundException for an empty name, a name holding {@code ..}, {@code /} or {@code \},
     *     an unknown key and any other extension
     */
    private ResponseEntity<byte[]> contractFile(String name, String key) {
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            log.debug("Console file name rejected: {}", name);
            throw new NotFoundException("Unknown console file: " + key);
        }
        byte[] bytes = ramlModel.file(key)
                .orElseThrow(() -> new NotFoundException("Unknown console file: " + key));
        String type;
        if (name.endsWith(".raml")) {
            type = APPLICATION_RAML_YAML;
        } else if (name.endsWith(".json")) {
            type = MediaType.APPLICATION_JSON_VALUE;
        } else {
            log.debug("Console file extension not served: {}", key);
            throw new NotFoundException("Unknown console file: " + key);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).body(bytes);
    }

    /**
     * Reads a classpath resource on each call.
     *
     * @param location the classpath location
     * @return the resource bytes
     * @throws NotFoundException    if the resource does not exist
     * @throws UncheckedIOException if the resource exists but cannot be read
     */
    private byte[] classpath(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (FileNotFoundException e) {
            log.warn("Console asset missing from the classpath: {}", location);
            throw new NotFoundException("Unknown console asset: " + location);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read console asset " + location, e);
        }
    }
}
