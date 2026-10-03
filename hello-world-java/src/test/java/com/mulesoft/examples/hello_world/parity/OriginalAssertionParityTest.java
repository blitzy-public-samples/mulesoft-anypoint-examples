/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ported assertions of the original {@code HelloWorldIT}, run against the application on a random port.
 *
 * <p>The {@code test} profile sets {@code http.port} to {@code 0}, the counterpart of the original's
 * {@code DynamicPort("http.port")}; {@link #port} receives the bound port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OriginalAssertionParityTest {

    @LocalServerPort
    int port;

    /**
     * Sends {@code GET /helloWorld} with no request body and asserts that a response arrives, that its body is
     * present and not empty, and that the body equals {@code Hello World}. The reply carries no
     * {@code Content-Type} (D-066); the body is read as a string.
     *
     * @throws Exception if the request cannot be sent
     */
    @Test
    void httpGetToFlowUrlEchoesSentMessage() throws Exception {
        // MuleClient.send(url, "", {http.method=GET}) counterpart: a TestRestTemplate GET with no request body.
        TestRestTemplate client = new TestRestTemplate();
        ResponseEntity<String> response = client.getForEntity("http://localhost:" + port + "/helloWorld", String.class);
        assertNotNull(response);
        String body = response.getBody();
        assertNotNull(body);
        assertFalse(body.isEmpty());
        assertEquals("Hello World", body);
    }
}
