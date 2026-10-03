package com.mulesoft.examples.testing_apikit_with_munit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link MunitResourceService}, with no Spring application context: each method
 * returns the constant payload of its {@code /munit} resource flow (D-057).
 *
 * <p>The expected values are the {@code set-payload} literals of the flows
 * {@code get:/munit:api-config}, {@code post:/munit:api-config}, {@code put:/munit:api-config}
 * and {@code delete:/munit:api-config}, without quote characters.
 */
final class MunitResourceServiceTest {

    /** The service under test, instantiated directly. */
    private final MunitResourceService service = new MunitResourceService();

    /** {@link MunitResourceService#getMunit()} returns {@code GET RESPONSE}. */
    @Test
    @DisplayName("getMunit returns GET RESPONSE")
    void getMunitReturnsGetResponse() {
        assertEquals("GET RESPONSE", service.getMunit());
    }

    /** {@link MunitResourceService#postMunit()} returns {@code POST RESPONSE}. */
    @Test
    @DisplayName("postMunit returns POST RESPONSE")
    void postMunitReturnsPostResponse() {
        assertEquals("POST RESPONSE", service.postMunit());
    }

    /** {@link MunitResourceService#putMunit()} returns {@code PUT RESPONSE}. */
    @Test
    @DisplayName("putMunit returns PUT RESPONSE")
    void putMunitReturnsPutResponse() {
        assertEquals("PUT RESPONSE", service.putMunit());
    }

    /** {@link MunitResourceService#deleteMunit()} returns {@code DELETE RESPONSE}. */
    @Test
    @DisplayName("deleteMunit returns DELETE RESPONSE")
    void deleteMunitReturnsDeleteResponse() {
        assertEquals("DELETE RESPONSE", service.deleteMunit());
    }
}
