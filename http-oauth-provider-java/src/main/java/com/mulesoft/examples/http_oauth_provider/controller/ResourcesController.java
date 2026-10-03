package com.mulesoft.examples.http_oauth_provider.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_oauth_provider.service.ResourceService;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves {@code /resources} on the listener port ({@code http.listener.port}, D-011) for the Mule
 * flow {@code protectedAuthcodeFlow} [http-oauth-provider/src/main/app/http-oauth-provider.xml:44-49];
 * the token and scope checks run in {@code ResourceServerConfig} (D-041).
 *
 * <p>Source elements and their Java counterparts:
 * <ul>
 *   <li>{@code http:listener path="/resources"} on {@code HTTP_Listener_Configuration}
 *       ({@code localhost:${http.listener.port}}, :43, :45), declared without
 *       {@code allowedMethods}: the mapping of {@code /resources} on this class.
 *       {@link #protectedAuthcodeFlow()} carries a mapping with no method, media-type, parameter
 *       or header condition and answers GET, HEAD, POST, PUT, PATCH and DELETE; OPTIONS reaches
 *       the same method through an OPTIONS-only mapping (D-498). {@code PortPathGuardFilter} answers 404
 *       for {@code /resources} on {@code http.provider.port} (D-011).</li>
 *   <li>{@code oauth2-provider:validate scopes="READ_RESOURCE"} (:46): {@code ResourceServerConfig}
 *       answers 401 for a missing or unknown bearer token and 403 for a token without
 *       {@code READ_RESOURCE} before this class runs (D-041).</li>
 *   <li>{@code set-payload} of the resource map (:47): {@link ResourceService#protectedAuthcodeFlow()}.</li>
 *   <li>{@code json:object-to-json-transformer} (:48): the map serialized by the application's
 *       {@link ObjectMapper} and sent as {@code application/json}.</li>
 * </ul>
 *
 * <p>The request body, query parameters and headers other than the bearer token are not read.
 * The {@code Accept} header does not change the answer. A serialization failure propagates to the
 * project's {@code GlobalExceptionHandler.unexpected}, the default-strategy 500 of HTTP ingress.
 *
 * <p>Example exchange with the default {@code http.listener.port} 8082:
 *
 * <pre>
 * GET /resources HTTP/1.1
 * Authorization: Bearer &lt;access_token with READ_RESOURCE&gt;
 *
 * HTTP/1.1 200 OK
 * Content-Type: application/json
 * Content-Length: 66
 *
 * {"name":"payroll","uri":"http://localhost:8082/resources/payroll"}
 * </pre>
 *
 * <p>The class holds no mutable state; one instance serves concurrent requests.
 */
@RestController
@RequestMapping("/resources")
public class ResourcesController {

    /** The implementation of flow {@code protectedAuthcodeFlow} that builds the resource map. */
    private final ResourceService resourceService;

    /** The application's JSON serializer, Spring Boot's auto-configured {@link ObjectMapper}. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the adapter over the flow's service and the application's JSON serializer.
     *
     * @param resourceService the implementation of flow {@code protectedAuthcodeFlow}
     * @param objectMapper    the serializer of the resource map
     */
    public ResourcesController(ResourceService resourceService, ObjectMapper objectMapper) {
        this.resourceService = resourceService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the protected resource map from {@link ResourceService} as {@code application/json}
     * for any HTTP method (http-oauth-provider.xml:47-48).
     *
     * <p>The body is the compact UTF-8 JSON of
     * {@link ResourceService#protectedAuthcodeFlow()} with its keys in map order, {@code name}
     * then {@code uri}, for example
     * {@code {"name":"payroll","uri":"http://localhost:8082/resources/payroll"}}. The status is
     * 200 and {@code Content-Type} is exactly {@code application/json}, whatever the request's
     * {@code Accept} header holds.
     *
     * @return status 200, {@code Content-Type: application/json} and the serialized resource map
     * @throws JsonProcessingException if the resource map cannot be serialized
     */
    @RequestMapping
    public ResponseEntity<byte[]> protectedAuthcodeFlow() throws JsonProcessingException {
        Map<String, String> payload = resourceService.protectedAuthcodeFlow();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsBytes(payload));
    }

    // OPTIONS on /resources reaches protectedAuthcodeFlow through this OPTIONS-only mapping (D-498);
    // the mapping of protectedAuthcodeFlow keeps no method restriction for every other method.
    /**
     * Runs {@link #protectedAuthcodeFlow()} for OPTIONS on {@code /resources}: the client receives
     * the same status, {@code Content-Type} and body, and no {@code Allow} header (D-498).
     *
     * @return status 200, {@code Content-Type: application/json} and the serialized resource map
     * @throws JsonProcessingException if the resource map cannot be serialized
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    private ResponseEntity<byte[]> protectedAuthcodeFlowOnOptions() throws JsonProcessingException {
        return protectedAuthcodeFlow();
    }
}
