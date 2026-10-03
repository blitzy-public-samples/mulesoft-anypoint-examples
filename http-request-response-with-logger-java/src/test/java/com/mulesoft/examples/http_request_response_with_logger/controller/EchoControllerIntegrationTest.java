package com.mulesoft.examples.http_request_response_with_logger.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulesoft.examples.http_request_response_with_logger.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tier 1 endpoint tests of {@link EchoController}, the target of Mule flow {@code EchoFlow}
 * [http-request-response-with-logger/src/main/app/echo.xml:4-8], over HTTP/1.1 against the embedded
 * Undertow server on a random port.
 *
 * <p>The tests check:
 * <ul>
 *   <li>the scenario {@code http-request-response-with-logger_echo-path}: {@code GET /echo} answers
 *       200 with body {@code /echo} and no {@code Content-Type} header (D-066), and the
 *       {@link EchoController} logger writes exactly one INFO event, {@code About to echo /echo};</li>
 *   <li>GET, POST, PUT, PATCH, DELETE, OPTIONS and TRACE on several paths, {@code /} and
 *       {@code /error} included, each answering 200 with the request path, without its query string,
 *       as the body and no {@code Content-Type} header (D-066);</li>
 *   <li>HEAD, answering 200 with an empty body and no {@code Content-Type} header;</li>
 *   <li>the HTTP default strategy, {@link GlobalExceptionHandler#unexpected}: status 500, the exception
 *       message as the body, no {@code Content-Type} and one ERROR event.</li>
 * </ul>
 *
 * <p>No error case other than the default strategy is tested (D-062).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EchoControllerIntegrationTest {

    /** The single HTTP/1.1 client of this class; it follows no redirect. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Port of the embedded Undertow server. */
    @LocalServerPort
    int port;

    /** Receives the events of the {@link EchoController} logger during one test. */
    private ListAppender<ILoggingEvent> appender;

    /** Starts a new list appender and attaches it to the {@link EchoController} logger. */
    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        logbackLogger(EchoController.class).addAppender(appender);
    }

    /** Detaches the list appender from the {@link EchoController} logger, then stops it. */
    @AfterEach
    void detachAppender() {
        logbackLogger(EchoController.class).detachAppender(appender);
        appender.stop();
    }

    /**
     * Scenario {@code echo-path}: {@code GET /echo} answers 200 with body {@code /echo} and no
     * {@code Content-Type}, and the {@link EchoController} logger writes the single INFO event
     * {@code About to echo /echo} [echo.xml:5-7].
     */
    @Test
    @DisplayName("http-request-response-with-logger_echo-path")
    void echoPath() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(port, "/echo")).GET().build());

        assertEcho(response, "/echo");
        List<ILoggingEvent> events = events(appender);
        assertEquals(1, events.size(), () -> "events of the EchoController logger: " + events);
        assertEquals(Level.INFO, events.get(0).getLevel());
        assertEquals("About to echo /echo", events.get(0).getFormattedMessage());
    }

    /** {@code GET /moon} answers 200 with body {@code /moon} and no {@code Content-Type}. */
    @Test
    @DisplayName("GET /moon echoes /moon")
    void echoesMoon() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/moon")).GET().build()), "/moon");
    }

    /** {@code GET /} answers 200 with body {@code /} and no {@code Content-Type}. */
    @Test
    @DisplayName("GET / echoes /")
    void echoesRoot() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/")).GET().build()), "/");
    }

    /** {@code GET /echo?name=x} answers 200 with body {@code /echo}, the path without its query string. */
    @Test
    @DisplayName("GET /echo?name=x echoes /echo without the query string")
    void echoOmitsQueryString() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/echo?name=x")).GET().build()), "/echo");
    }

    /** {@code GET /a/b/c} answers 200 with body {@code /a/b/c} and no {@code Content-Type}. */
    @Test
    @DisplayName("GET /a/b/c echoes /a/b/c")
    void echoesNestedPath() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/a/b/c")).GET().build()), "/a/b/c");
    }

    /** {@code POST /post-path} with a JSON body answers 200 with body {@code /post-path}. */
    @Test
    @DisplayName("POST /post-path echoes /post-path")
    void echoesPost() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, "/post-path"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"k\":\"v\"}"))
                .build();

        assertEcho(send(request), "/post-path");
    }

    /** {@code PUT /put-path} with a text body answers 200 with body {@code /put-path}. */
    @Test
    @DisplayName("PUT /put-path echoes /put-path")
    void echoesPut() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, "/put-path"))
                .PUT(HttpRequest.BodyPublishers.ofString("x"))
                .build();

        assertEcho(send(request), "/put-path");
    }

    /** {@code PATCH /patch-path} with a text body answers 200 with body {@code /patch-path}. */
    @Test
    @DisplayName("PATCH /patch-path echoes /patch-path")
    void echoesPatch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, "/patch-path"))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("x"))
                .build();

        assertEcho(send(request), "/patch-path");
    }

    /** {@code DELETE /delete-path} answers 200 with body {@code /delete-path}. */
    @Test
    @DisplayName("DELETE /delete-path echoes /delete-path")
    void echoesDelete() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/delete-path")).DELETE().build()), "/delete-path");
    }

    /** {@code OPTIONS /options-path} answers 200 with body {@code /options-path} and no {@code Content-Type}. */
    @Test
    @DisplayName("OPTIONS /options-path echoes /options-path")
    void echoesOptions() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, "/options-path"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        assertEcho(send(request), "/options-path");
    }

    /** {@code HEAD /head-path} answers 200 with an empty body and no {@code Content-Type}. */
    @Test
    @DisplayName("HEAD /head-path answers 200 with no body")
    void answersHead() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(port, "/head-path"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        assertEcho(send(request), "");
    }

    /** {@code GET /error} answers 200 with body {@code /error}, like any other path. */
    @Test
    @DisplayName("GET /error echoes /error")
    void echoesErrorPath() throws Exception {
        assertEcho(send(HttpRequest.newBuilder(uri(port, "/error")).GET().build()), "/error");
    }

    /**
     * {@code TRACE /trace-path}, sent over a raw socket, answers {@code HTTP/1.1 200} with body
     * {@code /trace-path} and no {@code Content-Type} header line.
     */
    @Test
    @DisplayName("TRACE /trace-path echoes /trace-path")
    void echoesTrace() throws IOException {
        String raw = exchangeRaw(port,
                "TRACE /trace-path HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n");

        int separator = raw.indexOf("\r\n\r\n");
        assertTrue(separator >= 0, () -> "end of the header block in: " + raw);
        String[] headLines = raw.substring(0, separator).split("\r\n");
        String body = raw.substring(separator + 4);
        assertTrue(headLines[0].startsWith("HTTP/1.1 200"), () -> "status line: " + headLines[0]);
        // No Content-Type header line (D-066)
        assertTrue(Arrays.stream(headLines)
                        .noneMatch(line -> line.toLowerCase(Locale.ROOT).startsWith("content-type:")),
                () -> "header block: " + Arrays.toString(headLines));
        assertEquals("/trace-path", body);
    }

    /**
     * HTTP default strategy: {@link GlobalExceptionHandler#unexpected} answers an uncommitted response
     * with status 500, the exception message {@code boom} as the UTF-8 body and no {@code Content-Type},
     * and its logger writes one ERROR event {@code boom} carrying the exception.
     */
    @Test
    @DisplayName("Default strategy answers 500 with the exception message and no Content-Type")
    void defaultStrategyAnswers500() throws Exception {
        ListAppender<ILoggingEvent> handlerAppender = new ListAppender<>();
        handlerAppender.start();
        Logger handlerLogger = logbackLogger(GlobalExceptionHandler.class);
        handlerLogger.addAppender(handlerAppender);
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            new GlobalExceptionHandler().unexpected(new IllegalStateException("boom"), response);
        } finally {
            handlerLogger.detachAppender(handlerAppender);
            handlerAppender.stop();
        }

        assertEquals(500, response.getStatus());
        assertEquals("boom", response.getContentAsString(StandardCharsets.UTF_8));
        // No Content-Type (D-066)
        assertNull(response.getContentType());
        List<ILoggingEvent> events = events(handlerAppender);
        assertEquals(1, events.size(), () -> "events of the GlobalExceptionHandler logger: " + events);
        assertEquals(Level.ERROR, events.get(0).getLevel());
        assertEquals("boom", events.get(0).getFormattedMessage());
        assertEquals(IllegalStateException.class.getName(), events.get(0).getThrowableProxy().getClassName());
    }

    /**
     * Sends {@code request} with the class client and reads the body as UTF-8.
     *
     * @param request the request to send
     * @return the response with its body as a string
     * @throws IOException          if the exchange fails
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Builds {@code http://localhost:<port><pathAndQuery>}.
     *
     * @param port         the server port
     * @param pathAndQuery the path, with an optional query string
     * @return the request URI
     */
    private static URI uri(int port, String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    /**
     * Asserts status 200, the exact body {@code expectedBody} and no {@code Content-Type} header.
     *
     * @param response     the response to check
     * @param expectedBody the expected body
     */
    private static void assertEcho(HttpResponse<String> response, String expectedBody) {
        assertEquals(200, response.statusCode(), () -> "status of " + response.request().method() + " "
                + response.uri());
        assertEquals(expectedBody, response.body());
        // No Content-Type header (D-066)
        assertTrue(response.headers().firstValue("Content-Type").isEmpty(),
                () -> "headers: " + response.headers().map());
    }

    /**
     * Writes {@code request} as ISO-8859-1 bytes to a new socket on {@code localhost:<port>} and reads
     * the reply to end of stream, with a read timeout of 10 seconds.
     *
     * @param port    the server port
     * @param request the complete request, request line and header block included
     * @return the reply decoded as ISO-8859-1
     * @throws IOException if the connection, the write or the read fails
     */
    private static String exchangeRaw(int port, String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Returns the Logback logger named after {@code type}.
     *
     * @param type the class whose logger is returned
     * @return the Logback logger of {@code type}
     */
    private static Logger logbackLogger(Class<?> type) {
        return (Logger) LoggerFactory.getLogger(type);
    }

    /**
     * Returns a copy of the events {@code listAppender} holds, read under the appender's lock.
     *
     * @param listAppender the appender to read
     * @return the events in the order they were appended
     */
    private static List<ILoggingEvent> events(ListAppender<ILoggingEvent> listAppender) {
        synchronized (listAppender) {
            return List.copyOf(listAppender.list);
        }
    }
}

