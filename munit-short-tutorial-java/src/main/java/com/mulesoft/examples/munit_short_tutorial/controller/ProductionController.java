package com.mulesoft.examples.munit_short_tutorial.controller;

import com.mulesoft.examples.munit_short_tutorial.service.ProductionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * HTTP adapter of flow {@code exampleFlow}
 * [munit-short-tutorial/src/main/app/production-code.xml:6-21]: serves {@code GET /}, the
 * {@code http:listener path="/" allowedMethods="GET"} of the flow (:7), and delegates to
 * {@link ProductionService}.
 *
 * <p>The listener port and host ({@code http.port}, default 8081, D-065, and
 * {@code listener.http-listener-configuration.host}) are bound in {@code application.yml}; the
 * class reads no property.
 *
 * <p>{@link #exampleFlow(HttpServletRequest, HttpServletResponse)} binds the {@code url_key}
 * request parameter (:8, D-456) and hands it to {@link ProductionService#exampleFlow(String)},
 * which runs flow {@code exampleFlow2}, its two sub-flows and the choice of {@code exampleFlow}
 * (:10-20). The reply is written with status 200 and no {@code Content-Type} header (D-066). The
 * class holds no routing or transformation of the flow.
 *
 * <p>Every method other than GET on {@code /} is answered 405 by the project's
 * {@code exception.GlobalExceptionHandler.methodNotAllowed} (D-062, D-455):
 * <ul>
 *   <li>POST, PUT, PATCH, DELETE, TRACE, CONNECT and extension methods match no mapping of this
 *       class, and Spring MVC's handler mapping raises
 *       {@link HttpRequestMethodNotSupportedException};</li>
 *   <li>HEAD and OPTIONS reach {@link #rejectNonGetOnRoot(HttpServletRequest)}, which raises the
 *       same exception.</li>
 * </ul>
 * An exception thrown by the service leaves this class unhandled and is answered by the project's
 * {@code exception.GlobalExceptionHandler.unexpected} (500).
 *
 * <pre>{@code
 * GET /?url_key=payload_1            -> 200 response_payload_1
 * GET /?url_key=payload_2            -> 200 response_payload_2
 * GET /?url_key=                     -> 200 response_payload_2
 * GET /                              -> 200 response_payload_2
 * GET /?url_key=x&url_key=payload_1  -> 200 response_payload_1
 * POST, PUT, PATCH, DELETE, HEAD, OPTIONS or TRACE /  -> 405
 * }</pre>
 *
 * <p>The bean holds no request state and is safe for concurrent requests.
 */
@Controller
public class ProductionController {

    /** The implementation of flow {@code exampleFlow} that each request is handed to. */
    private final ProductionService productionService;

    /**
     * Creates the adapter over the service that implements the flow.
     *
     * @param productionService the implementation of flow {@code exampleFlow}
     */
    public ProductionController(ProductionService productionService) {
        this.productionService = productionService;
    }

    /**
     * Answers {@code GET /} with the reply of flow {@code exampleFlow}.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the {@code url_key} value is the last value of the {@code url_key} request parameter:
     *       {@code payload_1} for {@code ?url_key=x&url_key=payload_1}, the empty string for
     *       {@code ?url_key=}, and {@code null} when the request has no {@code url_key}
     *       parameter (D-456);</li>
     *   <li>{@link ProductionService#exampleFlow(String)} returns {@code response_payload_1} or
     *       {@code response_payload_2} for that value;</li>
     *   <li>the reply is encoded as UTF-8 and written by
     *       {@link RawBody#write(HttpServletResponse, int, byte[])} with status 200, a
     *       {@code Content-Length} header and no {@code Content-Type} header (D-066).</li>
     * </ol>
     *
     * <p>The method writes the whole response; Spring MVC writes nothing further for the
     * {@code void} return.
     *
     * @param request  the HTTP request; only its {@code url_key} parameter values are read
     * @param response the HTTP response the reply is written to
     * @throws IOException if the reply cannot be written
     */
    @GetMapping("/")
    public void exampleFlow(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String[] values = request.getParameterValues("url_key");
        String urlKey = (values == null || values.length == 0) ? null : values[values.length - 1];
        String result = productionService.exampleFlow(urlKey);
        RawBody.write(response, 200, result.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rejects {@code HEAD /} and {@code OPTIONS /}, two of the methods outside
     * {@code allowedMethods="GET"} (:7).
     *
     * <p>The method throws {@link HttpRequestMethodNotSupportedException} with the request method
     * and {@code GET} as the only supported method; the project's
     * {@code exception.GlobalExceptionHandler.methodNotAllowed} answers it with 405 (D-062). For a
     * HEAD request this single-method HEAD mapping takes precedence over the GET mapping of
     * {@link #exampleFlow(HttpServletRequest, HttpServletResponse)}, and for an OPTIONS request the
     * explicit OPTIONS mapping takes precedence over Spring MVC's implicit OPTIONS handling. The
     * method sets no header and writes nothing to the response.
     *
     * @param request the HTTP request; only its method is read
     * @throws HttpRequestMethodNotSupportedException on every call
     */
    @RequestMapping(path = "/", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public void rejectNonGetOnRoot(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of("GET"));
    }
}
