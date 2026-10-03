package com.mulesoft.examples.http_oauth_provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of {@link ResourceService#protectedAuthcodeFlow()}, the resource map of flow
 * {@code protectedAuthcodeFlow} (http-oauth-provider.xml:47); counts toward the service coverage
 * floor (D-049).
 *
 * <p>Each test builds the service directly, with no Spring application context, for the
 * {@code http.listener.port} default 8082 (mule-app.properties:2) and for 18082, and asserts one
 * property of the returned map.
 */
public class ResourceServiceTest {

    /**
     * The map holds exactly the keys {@code name} and {@code uri}, iterated in that order.
     *
     * @param listenerPort the {@code http.listener.port} value passed to the constructor
     */
    @ParameterizedTest(name = "listener port {0}")
    @ValueSource(ints = {8082, 18082})
    @DisplayName("protectedAuthcodeFlow returns the keys name then uri")
    public void protectedAuthcodeFlowReturnsNameThenUri(int listenerPort) {
        Map<String, String> map = new ResourceService(listenerPort).protectedAuthcodeFlow();

        assertEquals(List.of("name", "uri"), new ArrayList<>(map.keySet()));
    }

    /**
     * The {@code name} entry is {@code payroll} for every listener port.
     *
     * @param listenerPort the {@code http.listener.port} value passed to the constructor
     */
    @ParameterizedTest(name = "listener port {0}")
    @ValueSource(ints = {8082, 18082})
    @DisplayName("protectedAuthcodeFlow returns name payroll")
    public void protectedAuthcodeFlowNameIsPayroll(int listenerPort) {
        Map<String, String> map = new ResourceService(listenerPort).protectedAuthcodeFlow();

        assertEquals("payroll", map.get("name"));
    }

    /**
     * The {@code uri} entry is {@code http://localhost:<listenerPort>/resources/payroll}.
     *
     * @param listenerPort the {@code http.listener.port} value passed to the constructor
     */
    @ParameterizedTest(name = "listener port {0}")
    @ValueSource(ints = {8082, 18082})
    @DisplayName("protectedAuthcodeFlow builds the payroll uri from the listener port")
    public void protectedAuthcodeFlowUriUsesListenerPort(int listenerPort) {
        Map<String, String> map = new ResourceService(listenerPort).protectedAuthcodeFlow();

        assertEquals("http://localhost:" + listenerPort + "/resources/payroll", map.get("uri"));
    }
}
