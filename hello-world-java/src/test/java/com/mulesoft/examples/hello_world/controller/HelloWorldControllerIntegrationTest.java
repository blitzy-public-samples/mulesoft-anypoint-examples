/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Endpoint integration test of {@code HelloWorldFlow1} [hello-world/src/main/app/HelloWorld.xml:4-7], the
 * {@code /helloWorld} listener answered by {@link HelloWorldController}, on the embedded Undertow server that the
 * {@code test} profile starts on a random port ({@code http.port: 0}).
 *
 * <p>Every request travels over a real HTTP/1.1 connection to {@code http://localhost:<port>}. The class checks:
 * <ul>
 *   <li>{@link #hello()}, the scenario of {@code HelloWorldFlow1} named by its {@code @DisplayName}:
 *       {@code GET /helloWorld} answers 200 with the body {@code Hello World} and no {@code Content-Type} header
 *       (D-066);</li>
 *   <li>GET, POST, PUT, PATCH, DELETE, OPTIONS and the non-standard {@code FOO} each answer 200 with the body
 *       {@code Hello World}, no {@code Content-Type} and no {@code Allow} header;</li>
 *   <li>HEAD answers 200 with an empty body;</li>
 *   <li>an unmatched path answers the listener's 404: the raw status line carries the reason phrase
 *       {@code No listener for endpoint: <path>} (D-010), the body is {@code Resource not found.} and no
 *       {@code Content-Type} header is sent. This is the endpoint's only error case (D-062).</li>
 * </ul>
 *
 * <p>The expected values are literal strings taken from the original flow and listener configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HelloWorldControllerIntegrationTest {

    /** HTTP/1.1 client shared by every request of the class; it holds no per-test state. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /** Upper bound, in milliseconds, for each HTTP exchange and each raw socket read. */
    private static final int TIMEOUT_MILLIS = 10000;

    /** The random port the embedded Undertow listener is bound to. */
    @LocalServerPort
    int port;

    /**
     * The scenario of {@code HelloWorldFlow1}: {@code GET /helloWorld} answers status 200, the body
     * {@code Hello World} and no {@code Content-Type} header (D-066).
     *
     * @throws Exception if the request cannot be sent or its response cannot be read
     */
    @Test
    @DisplayName("hello-world_hello")
    void hello() throws Exception {
        HttpResponse<String> response = send("GET", "/helloWorld", BodyPublishers.noBody());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Hello World");
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
    }

    /**
     * Each listed method on {@code /helloWorld} answers status 200, the body {@code Hello World}, no
     * {@code Content-Type} header (D-066) and no {@code Allow} header. POST, PUT and PATCH send the text body
     * {@code ping}; GET, DELETE, OPTIONS and the non-standard {@code FOO} send no body.
     *
     * @param method the HTTP method of the request
     * @throws Exception if the request cannot be sent or its response cannot be read
     */
    @ParameterizedTest(name = "{0} /helloWorld returns Hello World")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "FOO"})
    void anyMethodReachesHelloWorldFlow(String method) throws Exception {
        BodyPublisher body;
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            body = BodyPublishers.ofString("ping");
        } else {
            body = BodyPublishers.noBody();
        }

        HttpResponse<String> response = send(method, "/helloWorld", body);

        assertThat(response.statusCode()).as("status of %s /helloWorld", method).isEqualTo(200);
        assertThat(response.body()).as("body of %s /helloWorld", method).isEqualTo("Hello World");
        assertThat(response.headers().firstValue("Content-Type"))
                .as("Content-Type of %s /helloWorld", method)
                .isEmpty();
        assertThat(response.headers().firstValue("Allow"))
                .as("Allow of %s /helloWorld", method)
                .isEmpty();
    }

    /**
     * {@code HEAD /helloWorld} answers status 200 with an empty body.
     *
     * @throws Exception if the request cannot be sent or its response cannot be read
     */
    @Test
    void headReachesHelloWorldFlow() throws Exception {
        HttpResponse<String> response = send("HEAD", "/helloWorld", BodyPublishers.noBody());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }

    /**
     * {@code GET /unknownPath} answers the listener's 404, read byte for byte from a raw socket: the status line is
     * exactly {@code HTTP/1.1 404 No listener for endpoint: /unknownPath} (D-010), no header line is a
     * {@code Content-Type} header (D-066) and the body is exactly {@code Resource not found.}. This is the only error
     * case of {@code /helloWorld}'s listener (D-062).
     *
     * @throws Exception if the socket cannot be opened, written or read
     */
    @Test
    @DisplayName("unmatched path returns the listener 404")
    void unmatchedPathReturnsListener404() throws Exception {
        String raw;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            String request = "GET /unknownPath HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            byte[] bytes = socket.getInputStream().readAllBytes();
            raw = new String(bytes, StandardCharsets.ISO_8859_1);
        }

        int headEnd = raw.indexOf("\r\n\r\n");
        assertThat(headEnd).as("end of the response head in:%n%s", raw).isGreaterThanOrEqualTo(0);
        String[] headLines = raw.substring(0, headEnd).split("\r\n");
        List<String> headerLines = Arrays.asList(headLines).subList(1, headLines.length);
        String body = raw.substring(headEnd + "\r\n\r\n".length());

        assertThat(headLines[0]).isEqualTo("HTTP/1.1 404 No listener for endpoint: /unknownPath");
        assertThat(headerLines)
                .as("header lines of the 404 response")
                .noneMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("content-type:"));
        assertThat(body).isEqualTo("Resource not found.");
    }

    /**
     * Sends one request to the embedded listener and reads the response body as a string.
     *
     * @param method the HTTP method, standard or not
     * @param path   the request path, starting with {@code /}
     * @param body   the request body publisher
     * @return the response, with its body decoded as a string
     * @throws IOException          if the request cannot be sent or the response cannot be read
     * @throws InterruptedException if the calling thread is interrupted while waiting for the response
     */
    private HttpResponse<String> send(String method, String path, BodyPublisher body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofMillis(TIMEOUT_MILLIS))
                .method(method, body)
                .build();
        return CLIENT.send(request, BodyHandlers.ofString());
    }
}
