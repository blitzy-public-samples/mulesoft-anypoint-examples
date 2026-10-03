/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Ports the assertions of
 * {@code org.mule.examples.AdditionUsingJavascriptTransformerIT#httpGetToFlowUrlSentMessage} to the
 * Spring Boot application, with their original comparison semantics. It is the Tier 1 fallback
 * evidence of D-023, and the project's parity label stays
 * {@code PARITY: UNVERIFIED — Mule runtime unavailable} per D-073. The file header is copied from
 * the original test (D-048).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class OriginalAssertionParityTest {

    private static final String MESSAGE = "{ \"a\" : 1, \"b\": 2 }";

    @Autowired
    private TestRestTemplate restTemplate;

    /** Posts the original message to {@code /} and asserts the reply {@code Sum is: 3.0.}. */
    @Test
    public void httpGetToFlowUrlSentMessage() {
        ResponseEntity<byte[]> result =
                restTemplate.exchange("/", HttpMethod.POST, new HttpEntity<>(MESSAGE), byte[].class);
        assertNotNull(result);
        assertNotNull(result.getBody());
        String payload = new String(result.getBody(), StandardCharsets.UTF_8);
        assertFalse(payload.isEmpty());
        assertEquals("Sum is: 3.0.", payload);
    }
}
