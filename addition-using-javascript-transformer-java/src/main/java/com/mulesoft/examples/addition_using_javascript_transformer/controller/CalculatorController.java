package com.mulesoft.examples.addition_using_javascript_transformer.controller;

import com.mulesoft.examples.addition_using_javascript_transformer.service.CalculatorService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Binds {@code POST /}, the {@code http:listener} of Mule flow {@code javascript-calculatorFlow1}
 * [addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:4-5], on the
 * listener port ({@code http.port}, default 8081, D-065) and writes the {@link CalculatorService}
 * reply with status 200 and no {@code Content-Type} header (D-066).
 *
 * <p>The path {@code /} is a literal mapping and the class reads no property (D-175). The mapping
 * declares no {@code consumes}, {@code produces}, {@code params} or {@code headers} condition: a
 * {@code POST /} with any request {@code Content-Type} or none, and with any {@code Accept}
 * header, reaches {@link #javascriptCalculatorFlow1(byte[], HttpServletResponse)}.
 *
 * <p>The class binds the request body and delegates; it holds no logic of the flow. An exception
 * thrown by the service, such as the {@link IllegalArgumentException} for the malformed body
 * {@code [1,}, propagates unchanged to the project's {@code exception.GlobalExceptionHandler},
 * which answers the default 500. Other methods on {@code /} and requests for other paths are
 * answered by {@code exception.GlobalExceptionHandler} and {@code config.ListenerMethodFilter},
 * not by this class.
 *
 * <p>Exchange of the original test
 * [addition-using-javascript-transformer/src/test/java/org/mule/examples/AdditionUsingJavascriptTransformerIT.java:29,50]:
 *
 * <pre>{@code
 * POST / HTTP/1.1
 * Content-Type: application/json
 *
 * { "a" : 1, "b": 2 }
 *
 * HTTP/1.1 200 OK
 * Content-Length: 12
 *
 * Sum is: 3.0.
 * }</pre>
 *
 * <p>The bean holds no request state and is safe for concurrent requests.
 */
@RestController
public class CalculatorController {

    /** Runs flow {@code javascript-calculatorFlow1} on the request body. */
    private final CalculatorService calculatorService;

    /**
     * Creates the controller over the service that runs the flow.
     *
     * @param calculatorService the service of flow {@code javascript-calculatorFlow1}
     */
    public CalculatorController(CalculatorService calculatorService) {
        this.calculatorService = calculatorService;
    }

    /**
     * Answers {@code POST /} with the reply of flow {@code javascript-calculatorFlow1}.
     *
     * <p>The request body bytes are passed unchanged to
     * {@link CalculatorService#javascriptCalculatorFlow1(byte[])}; a request without a body passes
     * {@code null}, and the outcome for {@code null} is the service's. The returned text is encoded
     * as UTF-8 and written by {@link RawBody#write(HttpServletResponse, int, byte[])} with status
     * 200, a {@code Content-Length} header and no {@code Content-Type} header (D-066, D-195). For
     * example the body {@code { "a": 3, "b": 4 }} is answered with {@code Sum is: 7.0.} and
     * {@code { "a" : 1, "b": 2 }} with {@code Sum is: 3.0.} and {@code Content-Length: 12}.
     *
     * <p>A {@code POST} with {@code Content-Type: application/x-www-form-urlencoded}, which curl
     * sends by default for {@code --data} and {@code --data-binary}, reaches the service as the
     * body Spring rebuilds from the request parameters, not as the raw bytes: the body
     * {@code { "a": 3, "b": 4 }} arrives as {@code %7B+%22a%22%3A+3%2C+%22b%22%3A+4+%7D=}, which
     * the JSON-only SC-01 reading rejects with the default 500 (D-169).
     *
     * <p>The method writes the whole response; Spring writes nothing further for the {@code void}
     * return.
     *
     * @param body the raw request body, or {@code null} when the request has none
     * @param response the servlet response the reply is written to
     * @throws IOException if the response body cannot be written
     */
    @PostMapping("/")
    public void javascriptCalculatorFlow1(@RequestBody(required = false) byte[] body,
                                          HttpServletResponse response) throws IOException {
        RawBody.write(response, HttpServletResponse.SC_OK,
                calculatorService.javascriptCalculatorFlow1(body).getBytes(StandardCharsets.UTF_8));
    }
}
