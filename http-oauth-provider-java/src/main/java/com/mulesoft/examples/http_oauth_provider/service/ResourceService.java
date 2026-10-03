package com.mulesoft.examples.http_oauth_provider.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Body of the flow {@code protectedAuthcodeFlow}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:44-49] after its listener and token
 * validation (D-041): returns the protected resource description of the flow, {@code name}
 * {@code payroll} and {@code uri} {@code http://localhost:<http.listener.port>/resources/payroll},
 * in that order (http-oauth-provider.xml:47).
 *
 * <p>The port is the {@code http.listener.port} key of {@code application.yml}. The host is the
 * literal {@code localhost}.
 *
 * <p>Usage:
 * <pre>{@code
 * Map<String, String> resource = new ResourceService(8082).protectedAuthcodeFlow();
 * // {name=payroll, uri=http://localhost:8082/resources/payroll}
 * }</pre>
 *
 * <p>The class holds no mutable state; one instance serves concurrent requests.
 */
@Service
public class ResourceService {

    private final int listenerPort;

    /**
     * Creates the service.
     *
     * @param listenerPort port of the resource listener, from {@code http.listener.port}
     */
    public ResourceService(@Value("${http.listener.port}") int listenerPort) {
        this.listenerPort = listenerPort;
    }

    /**
     * Returns the protected resource description of flow {@code protectedAuthcodeFlow}:
     * {@code name} {@code payroll} and {@code uri}
     * {@code http://localhost:<http.listener.port>/resources/payroll}, in that order
     * (http-oauth-provider.xml:47, D-041).
     *
     * <p>Each call returns a new mutable map whose iteration order is {@code name}, then
     * {@code uri}.
     *
     * @return the map {@code {name=payroll, uri=http://localhost:<listenerPort>/resources/payroll}}
     */
    public Map<String, String> protectedAuthcodeFlow() {
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("name", "payroll");
        resource.put("uri", "http://localhost:" + listenerPort + "/resources/payroll");
        return resource;
    }
}
