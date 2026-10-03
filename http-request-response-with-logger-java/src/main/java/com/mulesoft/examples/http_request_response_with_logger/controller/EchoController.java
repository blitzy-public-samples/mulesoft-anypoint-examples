package com.mulesoft.examples.http_request_response_with_logger.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Echoes the request path of every HTTP request, the behaviour of the original flow {@code EchoFlow}.
 *
 * <p>Every HTTP method on every path is answered (D-108) with:
 * <ul>
 *   <li>status 200;</li>
 *   <li>the request path as the body, as received and without the query string, encoded in UTF-8
 *       (for example {@code GET /echo} gives {@code /echo} and {@code PUT /a/b?x=1} gives
 *       {@code /a/b});</li>
 *   <li>a {@code Content-Length} header and no {@code Content-Type} header (D-066).</li>
 * </ul>
 *
 * <p>Each request logs {@code About to echo <path>} at INFO. The request body, headers and parameters
 * are never read, and no service is called.
 */
@RestController
public class EchoController {

    /** Logger of the {@code About to echo <path>} INFO events, named after this class. */
    private static final Logger LOGGER = LoggerFactory.getLogger(EchoController.class);

    /**
     * Reads the request URI, logs {@code About to echo <path>} at INFO and writes the path through
     * {@link RawBody#write(HttpServletResponse, int, byte[])} with status 200, its UTF-8 bytes as the
     * body and no {@code Content-Type} header (D-066).
     *
     * <p>The mapping {@code /**} declares no method, media type, parameter or header condition.
     *
     * @param request  the current request, whose URI is the echoed path
     * @param response the response the path is written to
     * @throws IOException if the response body cannot be written
     */
    @RequestMapping("/**")
    public void echo(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String payload = request.getRequestURI();
        LOGGER.info("About to echo {}", payload);
        RawBody.write(response, 200, payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers an OPTIONS request on any path through
     * {@link #echo(HttpServletRequest, HttpServletResponse)}: status 200, the request path as the body,
     * no {@code Content-Type} header and no {@code Allow} header (D-432).
     *
     * @param request  the current OPTIONS request, whose URI is the echoed path
     * @param response the response the path is written to
     * @throws IOException if the response body cannot be written
     */
    @RequestMapping(path = "/**", method = RequestMethod.OPTIONS)
    public void echoOptions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        echo(request, response);
    }
}
