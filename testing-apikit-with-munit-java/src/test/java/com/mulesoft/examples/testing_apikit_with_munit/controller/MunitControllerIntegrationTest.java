package com.mulesoft.examples.testing_apikit_with_munit.controller;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-stack HTTP tests of the {@code /api/munit} endpoints of {@link MunitController} and of the
 * APIkit 404, 405 and 406 bodies, sent to the application on a random port with the {@code test}
 * profile.
 *
 * <p>The four MUnit tests of {@code testing-apikit-with-munit/src/test/munit/api-test-suite.xml} are
 * ported with their status and payload assertions, the payloads compared as unquoted UTF-8 bytes
 * (D-057, D-380). The error bodies are the literal mappings of
 * {@code testing-apikit-with-munit/src/main/app/api.xml:10-36}, compared as exact UTF-8 bytes. Each
 * {@code Content-Type} asserted is exactly {@code application/json}, with no parameter (D-066), and
 * no 405 response carries an {@code Allow} header (D-397).
 *
 * <p>Every request is sent over HTTP/1.1 with no body and no {@code Content-Type} header; only the
 * 406 request sets an {@code Accept} header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MunitControllerIntegrationTest {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @LocalServerPort
    int port;

    /** MUnit test {@code api-test-get} [api-test-suite.xml:15-21]: 200 and {@code GET RESPONSE}. */
    @Test
    @DisplayName("testing-apikit-with-munit_get-munit-200")
    void getMunit200() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/api/munit", null);

        assertJsonResponse(200, "GET RESPONSE", response);
    }

    /** MUnit test {@code api-test-post} [api-test-suite.xml:23-29]: 201 and {@code POST RESPONSE}. */
    @Test
    @DisplayName("testing-apikit-with-munit_post-munit-201")
    void postMunit201() throws Exception {
        HttpResponse<byte[]> response = send("POST", "/api/munit", null);

        assertJsonResponse(201, "POST RESPONSE", response);
    }

    /** MUnit test {@code api-test-put} [api-test-suite.xml:31-37]: 201 and {@code PUT RESPONSE}. */
    @Test
    @DisplayName("testing-apikit-with-munit_put-munit-201")
    void putMunit201() throws Exception {
        HttpResponse<byte[]> response = send("PUT", "/api/munit", null);

        assertJsonResponse(201, "PUT RESPONSE", response);
    }

    /** MUnit test {@code api-test-delete} [api-test-suite.xml:39-45]: 200 and {@code DELETE RESPONSE}. */
    @Test
    @DisplayName("testing-apikit-with-munit_delete-munit-200")
    void deleteMunit200() throws Exception {
        HttpResponse<byte[]> response = send("DELETE", "/api/munit", null);

        assertJsonResponse(200, "DELETE RESPONSE", response);
    }

    /** APIkit mapping 404 [api.xml:11-15]: {@code GET /api/unknown} answers 404 and its literal body. */
    @Test
    @DisplayName("testing-apikit-with-munit_unknown-path-404")
    void unknownPath404() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/api/unknown", null);

        assertJsonResponse(404, "{ \"message\": \"Resource not found\" }", response);
    }

    /**
     * APIkit mapping 405 [api.xml:16-20]: {@code PATCH}, {@code HEAD} and {@code OPTIONS} on
     * {@code /api/munit} answer 405 without an {@code Allow} header; {@code PATCH} and {@code OPTIONS}
     * answer {@code { "message": "Method not allowed" }} (D-397).
     */
    @Test
    @DisplayName("testing-apikit-with-munit_wrong-method-405")
    void wrongMethod405() throws Exception {
        byte[] methodNotAllowed = utf8("{ \"message\": \"Method not allowed\" }");
        HttpResponse<byte[]> patch = send("PATCH", "/api/munit", null);
        HttpResponse<byte[]> head = send("HEAD", "/api/munit", null);
        HttpResponse<byte[]> options = send("OPTIONS", "/api/munit", null);

        assertAll(
                () -> assertEquals(405, patch.statusCode(), "PATCH status"),
                () -> assertEquals(Optional.of("application/json"), patch.headers().firstValue("Content-Type"),
                        "PATCH Content-Type"),
                () -> assertArrayEquals(methodNotAllowed, patch.body(), "PATCH body"),
                () -> assertTrue(patch.headers().firstValue("Allow").isEmpty(), "PATCH Allow header"),
                () -> assertEquals(405, head.statusCode(), "HEAD status"),
                () -> assertTrue(head.headers().firstValue("Allow").isEmpty(), "HEAD Allow header"),
                () -> assertEquals(405, options.statusCode(), "OPTIONS status"),
                () -> assertEquals(Optional.of("application/json"), options.headers().firstValue("Content-Type"),
                        "OPTIONS Content-Type"),
                () -> assertArrayEquals(methodNotAllowed, options.body(), "OPTIONS body"),
                () -> assertTrue(options.headers().firstValue("Allow").isEmpty(), "OPTIONS Allow header"));
    }

    /** APIkit mapping 406 [api.xml:26-30]: {@code GET /api/munit} with {@code Accept: text/plain} answers 406. */
    @Test
    @DisplayName("testing-apikit-with-munit_not-acceptable-406")
    void notAcceptable406() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/api/munit", "text/plain");

        assertJsonResponse(406, "{ \"message\": \"Not acceptable\" }", response);
    }

    private HttpResponse<byte[]> send(String method, String path, String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            request.header("Accept", accept);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertJsonResponse(int status, String body, HttpResponse<byte[]> response) {
        assertAll(
                () -> assertEquals(status, response.statusCode(), "status"),
                () -> assertEquals(Optional.of("application/json"), response.headers().firstValue("Content-Type"),
                        "Content-Type"),
                () -> assertArrayEquals(utf8(body), response.body(), "body"));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
