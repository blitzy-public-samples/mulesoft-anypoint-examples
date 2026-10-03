package com.mulesoft.examples.content_based_routing.controller;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.mulesoft.examples.content_based_routing.service.LanguageRoutingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter for flow {@code content-based-routingFlow} (content-based-routing.xml:3-5): any
 * method on {@code /}.
 *
 * <p>The listener {@code HTTP_Listener_Configuration} (:3) and its {@code http:listener path="/"}
 * without {@code allowedMethods} (:5) become the mapping of the exact path {@code /}:
 * <ul>
 *   <li>{@link #contentBasedRoutingFlow(HttpServletRequest, byte[], HttpServletResponse)} carries
 *       a mapping without a method restriction and receives every method that Spring MVC
 *       dispatches to such a mapping: GET, HEAD, POST, PUT, PATCH, DELETE, TRACE (dispatched under
 *       {@code spring.mvc.dispatch-trace-request: true} in {@code application.yml}) and extension
 *       methods such as {@code PROPFIND};</li>
 *   <li>OPTIONS on {@code /} reaches the same method through a second mapping limited to
 *       OPTIONS. A CORS preflight, an OPTIONS request that carries both {@code Origin} and
 *       {@code Access-Control-Request-Method}, is answered by Spring MVC's CORS handling before
 *       either method runs: 403 {@code Invalid CORS request} for an origin other than the
 *       server's own, and 200 with an {@code Allow} header and an empty body for the server's
 *       own origin.</li>
 * </ul>
 * Any other path, {@code /favicon.ico}, {@code /x} and {@code //} included, has no mapping here
 * and is answered by the project's {@code GlobalExceptionHandler.noListener}.
 *
 * <p>Each request is bound and delegated, nothing more: the raw body and the {@code language}
 * query parameter go to {@link LanguageRoutingService#contentBasedRoutingFlow(Object, String)},
 * which runs the favicon filter (:7), the choice (:9-19), the default sub-flow (:22-26) and both
 * loggers (:20, :23). The reply is written with status 200 and no {@code Content-Type} (D-066).
 * An exception from the query decoding or the service leaves this class unhandled and reaches the
 * project's {@code GlobalExceptionHandler.unexpected}.
 *
 * <pre>{@code
 * GET  /?language=Spanish             -> 200 Hola!
 * GET  /?language=French              -> 200 Bonjour!
 * GET  /                              -> 200 Hello!
 * GET  /?language=Spanish&language=French -> 200 Hola!
 * POST / with body /favicon.ico       -> 200 Hello!
 * }</pre>
 */
@RestController
public class GreetingController {

    /** The implementation of flow {@code content-based-routingFlow} that each request is handed to. */
    private final LanguageRoutingService languageRoutingService;

    /**
     * Creates the adapter over the service that implements the flow.
     *
     * @param languageRoutingService the implementation of {@code content-based-routingFlow}
     */
    public GreetingController(LanguageRoutingService languageRoutingService) {
        this.languageRoutingService = languageRoutingService;
    }

    /**
     * Runs flow {@code content-based-routingFlow} (content-based-routing.xml:4-21) for a request on
     * {@code /} and writes its reply.
     *
     * <p>The steps are:
     * <ol>
     *   <li>{@code body} is the raw request body as read by {@code ByteArrayHttpMessageConverter}
     *       for every media type, or {@code null} when the request has no body; it is passed to
     *       the service unchanged as the message payload;</li>
     *   <li>{@code language} is the first {@code language} pair of the raw query string,
     *       URL-decoded (:8), or {@code null} when there is none;</li>
     *   <li>the service returns the reply {@code Hola!}, {@code Bonjour!} or {@code Hello!}, or
     *       an empty result when its favicon filter (:7) rejects the payload;</li>
     *   <li>the reply is written with status 200 and no Content-Type (D-066); an empty reply
     *       yields an empty body with {@code Content-Length: 0}.</li>
     * </ol>
     *
     * @param request  the HTTP request; only its raw query string is read
     * @param body     the raw request body, or {@code null} when the request has none
     * @param response the HTTP response the reply is written to
     * @throws IOException              if the reply cannot be written
     * @throws IllegalArgumentException if a pair of the query string up to the first
     *                                  {@code language} pair holds a malformed percent escape,
     *                                  such as {@code %zz} or a trailing {@code %}
     */
    @RequestMapping(path = "/")
    public void contentBasedRoutingFlow(HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        Optional<String> reply =
                languageRoutingService.contentBasedRoutingFlow(body, language(request.getQueryString()));
        RawBody.write(response, 200, reply.map(r -> r.getBytes(StandardCharsets.UTF_8)).orElse(new byte[0]));
    }

    // OPTIONS on "/" reaches the flow through this OPTIONS-only mapping; the mapping on
    // contentBasedRoutingFlow keeps no method restriction for every other method.
    /**
     * Runs {@link #contentBasedRoutingFlow(HttpServletRequest, byte[], HttpServletResponse)} for
     * OPTIONS on {@code /}; the client receives the flow's reply and no {@code Allow} header.
     *
     * @param request  the HTTP request; only its raw query string is read
     * @param body     the raw request body, or {@code null} when the request has none
     * @param response the HTTP response the reply is written to
     * @throws IOException if the reply cannot be written
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    private void contentBasedRoutingFlowOnOptions(HttpServletRequest request,
            @RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        contentBasedRoutingFlow(request, body, response);
    }

    /**
     * Returns the first {@code language} query parameter, URL-decoded (content-based-routing.xml:8).
     *
     * <p>The raw query string is split on {@code &} and its pairs are read in order. In each pair
     * the name is the text before the first {@code =}, or the whole pair when it has none, and the
     * value is the text after that {@code =}. The name, and the value when the pair has an
     * {@code =}, are decoded with {@link URLDecoder#decode(String, java.nio.charset.Charset)} in
     * UTF-8, which turns {@code +} into a space and decodes each {@code %xx}. The first pair whose
     * decoded name equals {@code language}, case-sensitively, gives the result: its decoded value,
     * the empty string for {@code language=}, or {@code null} for a bare {@code language}. Pairs
     * after it are not read.
     *
     * <pre>{@code
     * language(null)                          -> null
     * language("language=Spanish")            -> "Spanish"
     * language("language=Fren%63h")           -> "French"
     * language("language=French&language=Spanish") -> "French"
     * language("language=")                   -> ""
     * language("language")                    -> null
     * language("Language=Spanish")            -> null
     * }</pre>
     *
     * @param queryString the raw query string of the request, or {@code null} when it has none
     * @return the decoded value of the first {@code language} pair, or {@code null} when the query
     *         string is {@code null}, holds no such pair, or that pair has no {@code =}
     * @throws IllegalArgumentException if a name or value read holds a malformed percent escape
     */
    private static String language(String queryString) {
        if (queryString == null) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int separator = pair.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            String value = separator < 0 ? null
                    : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if ("language".equals(name)) {
                return value;
            }
        }
        return null;
    }
}
