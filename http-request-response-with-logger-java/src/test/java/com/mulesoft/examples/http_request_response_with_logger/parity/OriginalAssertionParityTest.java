/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ported assertions of {@code org.mule.example.echo.HttpRequestResponseWithLoggerTest}; header kept per
 * D-048.
 *
 * <p>Sends an HTTP GET to {@code /message} on the running application and checks, in the original's
 * order, that the body is not null, is not empty and equals {@code "/" + MESSAGE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OriginalAssertionParityTest {

    /** Path segment sent and expected back after a leading {@code /}. */
    private static final String MESSAGE = "message";

    /** HTTP/1.1 client of this class; redirects are not followed. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Port the embedded server listens on. */
    @LocalServerPort
    int port;

    /**
     * GET {@code /message} with an empty body: the response body is not null, not empty, and equals
     * {@code /message}.
     *
     * @throws Exception if the request cannot be sent or the response cannot be read
     */
    @Test
    @DisplayName("org.mule.example.echo.HttpRequestResponseWithLoggerTest#httpGetToFlowUrlEchoesSentMessage")
    void httpGetToFlowUrlEchoesSentMessage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/" + MESSAGE))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();

        assertNotNull(body);
        assertFalse(body.isEmpty());
        assertEquals("/" + MESSAGE, body);
    }
}
