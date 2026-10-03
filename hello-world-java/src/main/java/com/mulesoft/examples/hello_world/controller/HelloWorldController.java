/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Serves {@code /helloWorld}, the listener path of {@code HelloWorldFlow1}, for every HTTP method other
 * than TRACE with status 200, the body {@code Hello World} and no {@code Content-Type} (D-066).
 *
 * <p>{@link #helloWorld(HttpServletResponse)} answers GET, HEAD, POST, PUT, PATCH, DELETE and non-standard
 * methods such as {@code FOO}; {@link #helloWorldOptions(HttpServletResponse)} answers OPTIONS. TRACE has no
 * mapping in this class and receives the servlet's default TRACE response. Both methods write the body through
 * {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets {@code Content-Length} and commits the
 * response. The listener port and address are the {@code http.port} and
 * {@code listener.http-listener-configuration.host} keys of {@code application.yml} (D-065).
 *
 * <p>Example exchange:
 *
 * <pre>
 * GET /helloWorld HTTP/1.1
 *
 * HTTP/1.1 200 OK
 * Content-Length: 11
 *
 * Hello World
 * </pre>
 */
@Controller
public class HelloWorldController {

    /** The response body of {@code /helloWorld}: the {@code set-payload} value of {@code HelloWorldFlow1}. */
    static final String HELLO_WORLD = "Hello World";

    /**
     * Answers a request on {@code /helloWorld} with any method other than OPTIONS and TRACE: status 200, the
     * UTF-8 bytes of {@code Hello World}, {@code Content-Length: 11} and no {@code Content-Type} (D-066).
     * A HEAD request receives the same status and headers with no body.
     *
     * @param response the servlet response the body is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping("/helloWorld")
    public void helloWorld(HttpServletResponse response) throws IOException {
        RawBody.write(response, 200, HELLO_WORLD.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers OPTIONS on {@code /helloWorld} with the same status, body and headers as
     * {@link #helloWorld(HttpServletResponse)}: status 200, {@code Hello World}, {@code Content-Length: 11},
     * no {@code Content-Type} (D-066) and no {@code Allow} header.
     *
     * @param response the servlet response the body is written to
     * @throws IOException if the response cannot be written
     */
    @RequestMapping(path = "/helloWorld", method = RequestMethod.OPTIONS)
    public void helloWorldOptions(HttpServletResponse response) throws IOException {
        RawBody.write(response, 200, HELLO_WORLD.getBytes(StandardCharsets.UTF_8));
    }
}
