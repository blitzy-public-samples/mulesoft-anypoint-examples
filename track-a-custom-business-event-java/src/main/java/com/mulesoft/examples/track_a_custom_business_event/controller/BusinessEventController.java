package com.mulesoft.examples.track_a_custom_business_event.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.track_a_custom_business_event.service.PriceDiscountService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP adapter of flow {@code custom-business-eventsFlow1}
 * [track-a-custom-business-event/src/main/app/custom-business-events.xml:4-25]: serves every HTTP
 * method on {@code /customBusinessEvents} and replies 200 with the discounted price, without a
 * {@code Content-Type} (D-066).
 *
 * <p>The listener {@code HTTP_Listener_Configuration} (:3) with {@code path="customBusinessEvents"}
 * and no {@code allowedMethods} (:5) becomes the class mapping {@code /customBusinessEvents}, with
 * no method, media-type, parameter or header condition (AAP 0.3.2):
 * <ul>
 *   <li>{@link #customBusinessEventsFlow1(byte[], HttpServletResponse)} receives GET, HEAD, POST,
 *       PUT, PATCH, DELETE, CONNECT, extension methods such as {@code PROPFIND}, and TRACE,
 *       dispatched under {@code spring.mvc.dispatch-trace-request: true} in
 *       {@code application.yml} (D-629); any or no {@code Content-Type} and {@code Accept}
 *       header is accepted;</li>
 *   <li>OPTIONS runs the same method through a private OPTIONS-only mapping and gets no
 *       {@code Allow} header (D-629). A CORS preflight, an OPTIONS request that carries both
 *       {@code Origin} and {@code Access-Control-Request-Method}, is answered by Spring MVC's CORS
 *       handling and does not run the flow.</li>
 * </ul>
 * Any other path has no mapping in this class. The listener port and address are the
 * {@code http.port} (default 8081, D-065) and {@code listener.http-listener-configuration.host}
 * keys of {@code application.yml}.
 *
 * <p>Each request is bound and delegated, nothing more (AAP 0.3.7): the raw body goes to
 * {@link PriceDiscountService#customBusinessEventsFlow1(byte[])}, which runs the JSON-to-map
 * transformer (:6), the {@code Price} business event (:7-11), the JRuby discount (:12-22), the
 * object-to-string transformer (:23) and the tracking transaction (:24). No exception is caught
 * here: malformed or empty JSON, the {@link IllegalStateException} for an unlisted item while no
 * discount is set (D-071) and every other exception of the service reach the project's
 * {@code GlobalExceptionHandler.unexpected}, which answers 500 (AAP 0.6.2).
 *
 * <p>Example exchange with the original test's {@code message.json}:
 *
 * <pre>
 * POST /customBusinessEvents HTTP/1.1
 * Content-Type: application/json
 *
 * {"email": "aaa@abc.sk", "item name": "shoes", "item units": 2, "item price per unit": 10, "membership": "free"}
 *
 * HTTP/1.1 200 OK
 * Content-Length: 3
 *
 * 8.5
 * </pre>
 */
@RestController
@RequestMapping("/customBusinessEvents")
public class BusinessEventController {

    /** The implementation of flow {@code custom-business-eventsFlow1} that each request is handed to. */
    private final PriceDiscountService priceDiscountService;

    /**
     * Creates the adapter over the service that implements the flow.
     *
     * @param priceDiscountService the implementation of {@code custom-business-eventsFlow1}
     */
    public BusinessEventController(PriceDiscountService priceDiscountService) {
        this.priceDiscountService = priceDiscountService;
    }

    /**
     * Runs flow {@code custom-business-eventsFlow1} for a request on {@code /customBusinessEvents}
     * and writes its reply.
     *
     * <p>The steps are:
     * <ol>
     *   <li>{@code body} is the raw request body as read by {@code ByteArrayHttpMessageConverter}
     *       for every media type, or {@code null} when the request has none; it is passed to
     *       {@link PriceDiscountService#customBusinessEventsFlow1(byte[])} unchanged;</li>
     *   <li>the service returns the discounted {@code item price per unit} as text, for example
     *       {@code 8.5} for {@code shoes} at {@code 10};</li>
     *   <li>the text is written as UTF-8 bytes with status 200, {@code Content-Length} and no
     *       {@code Content-Type} through {@link RawBody#write(HttpServletResponse, int, byte[])}
     *       (D-066).</li>
     * </ol>
     *
     * @param body     the raw request body, or {@code null} when the request has none
     * @param response the HTTP response the reply is written to
     * @throws IOException if the body is empty or not a JSON object, as raised by the service, or
     *                     if the reply cannot be written
     */
    @RequestMapping
    public void customBusinessEventsFlow1(@RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        String result = priceDiscountService.customBusinessEventsFlow1(body);
        RawBody.write(response, 200, result.getBytes(StandardCharsets.UTF_8));
    }

    // OPTIONS on /customBusinessEvents runs the flow through this OPTIONS-only mapping, beside the
    // unrestricted mapping of customBusinessEventsFlow1 (D-629).
    /**
     * Runs {@link #customBusinessEventsFlow1(byte[], HttpServletResponse)} for OPTIONS on
     * {@code /customBusinessEvents}; the client receives the flow's reply and no {@code Allow}
     * header (D-629).
     *
     * @param body     the raw request body, or {@code null} when the request has none
     * @param response the HTTP response the reply is written to
     * @throws IOException if the body is empty or not a JSON object, as raised by the service, or
     *                     if the reply cannot be written
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    private void customBusinessEventsFlow1OnOptions(@RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        customBusinessEventsFlow1(body, response);
    }
}
