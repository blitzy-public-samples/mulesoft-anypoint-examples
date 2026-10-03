package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.service_orchestration_and_choice_routing.config.AdditionalPortsConfig;

/**
 * Posts SOAP envelope text to the {@code orderService} endpoint of this application and returns the
 * response text. Replaces the {@code http:request} elements of the AJAX flows {@code orderRequest} and
 * {@code orderProxy} [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:21,40] and
 * their shared {@code HTTP_Request_Configuration}
 * [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:4].
 *
 * <p>Destination, resolved on every call: {@code POST http://<host>:<port>/<path>} with
 * <ul>
 *   <li>{@code <host>}: {@code http-request-configuration.host}, default {@code 0.0.0.0} (D-454);</li>
 *   <li>{@code <port>}: {@link AdditionalPortsConfig.ListenerPorts#ordersSoapPort()}, bound from
 *       {@code listener.http-listener-configuration3.port} (default 1080), the Spring WS listener of
 *       {@code orderService} (D-028);</li>
 *   <li>{@code <path>}: {@code order-request.path} or {@code order-proxy.path}, both referencing
 *       {@code listener.http-listener-configuration3.path} (default {@code orders}, D-140).</li>
 * </ul>
 *
 * <p>Per method:
 * <table>
 *   <caption>Call sites and read timeouts</caption>
 *   <tr><th>Method</th><th>Flow</th><th>Path key</th><th>Read timeout key</th><th>Default</th></tr>
 *   <tr><td>{@link #postOrderRequest(String)}</td><td>{@code orderRequest}</td>
 *       <td>{@code order-request.path}</td><td>{@code order-request.response-timeout}</td>
 *       <td>10000000 ms</td></tr>
 *   <tr><td>{@link #postOrderProxy(String)}</td><td>{@code orderProxy}</td>
 *       <td>{@code order-proxy.path}</td><td>{@code http-request-configuration.response-timeout}</td>
 *       <td>10000 ms, the {@code @Value} fallback; the key is absent from {@code application.yml}</td></tr>
 * </table>
 *
 * <p>Request: the UTF-8 bytes of the envelope, unchanged (no parsing, re-serialisation or XML declaration
 * change), with the header {@code Content-Type: text/xml; charset=UTF-8} and no {@code SOAPAction} or
 * other custom header. Transport: one HTTP/1.1 JDK {@link HttpClient} shared by both calls, the JDK
 * default redirect policy {@link HttpClient.Redirect#NEVER}, no connect timeout, one attempt, no retry,
 * no interceptor and the default status handler (D-454).
 *
 * <p>Response: the body decoded with the charset of the response {@code Content-Type}, otherwise UTF-8;
 * an absent or empty body answers {@code ""}.
 *
 * <p>Failures propagate unchanged; this class catches no transport or status exception. A status of 400
 * or more raises {@link org.springframework.web.client.HttpClientErrorException} or
 * {@link org.springframework.web.client.HttpServerErrorException}, which carry the status, the response
 * headers and the response body ({@code getResponseBodyAsByteArray()}, {@code getResponseBodyAsString()});
 * an HTTP 500 SOAP fault keeps its fault envelope there. A connection failure or an elapsed read timeout
 * raises {@link org.springframework.web.client.ResourceAccessException}. A response {@code Content-Type}
 * that cannot be parsed, an unsupported charset included, raises
 * {@link org.springframework.http.InvalidMediaTypeException} from {@link RestClient}. The AJAX channel
 * controller turns each of these into its failure reply (D-077).
 *
 * <p>Imports no JAX-WS, CXF, Mule or Spring WS client type (D-050).
 *
 * <pre>{@code
 * String reply = orderSoapClient.postOrderProxy(soapRequestText);
 * // reply is the <soap:Envelope> text answered by OrderServiceEndpoint
 * }</pre>
 */
@Component
public class OrderSoapClient {

    /**
     * Value of the {@code Content-Type} request header of both calls, sent as this literal string, not as a
     * {@link MediaType} rendering such as {@code text/xml;charset=UTF-8} (D-454).
     */
    static final String REQUEST_CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** Scheme, host and port part of the request URI template; the path is appended. */
    private static final String URI_TEMPLATE_PREFIX = "http://{host}:{port}";

    /** Decoding charset of a response without a {@code Content-Type} charset. */
    private static final Charset DEFAULT_RESPONSE_CHARSET = StandardCharsets.UTF_8;

    /** Logs each call's URI, status and response size at DEBUG; never the envelope. */
    private static final Logger log = LoggerFactory.getLogger(OrderSoapClient.class);

    /** Ports of the extra listeners; {@link AdditionalPortsConfig.ListenerPorts#ordersSoapPort()} is read per call. */
    private final AdditionalPortsConfig.ListenerPorts listenerPorts;

    /** Host of {@code HTTP_Request_Configuration}; the original {@code 0.0.0.0} by default, not a fixed {@code localhost} (D-454). */
    private final String host;

    /** Path of the {@code orderRequest} call with one leading {@code /}, from {@code order-request.path} (D-140). */
    private final String orderRequestPath;

    /** Path of the {@code orderProxy} call with one leading {@code /}, from {@code order-proxy.path} (D-140). */
    private final String orderProxyPath;

    /** Client of {@link #postOrderRequest(String)}: read timeout {@code order-request.response-timeout}. */
    private final RestClient orderRequestClient;

    /** Client of {@link #postOrderProxy(String)}: read timeout {@code http-request-configuration.response-timeout}. */
    private final RestClient orderProxyClient;

    /**
     * Creates the client and its two {@link RestClient}s over one HTTP/1.1 JDK {@link HttpClient}.
     *
     * @param restClientBuilder            Boot's {@link RestClient.Builder}; each client is built from a
     *                                     clone of it with its own {@link JdkClientHttpRequestFactory}
     * @param listenerPorts                ports of the extra listeners; supplies the {@code orderService}
     *                                     port on every call
     * @param host                         {@code http-request-configuration.host}
     * @param orderRequestPath             {@code order-request.path}; a missing leading {@code /} is added
     * @param orderProxyPath               {@code order-proxy.path}; a missing leading {@code /} is added
     * @param orderRequestTimeoutMillis    {@code order-request.response-timeout}, read timeout of
     *                                     {@link #postOrderRequest(String)} in milliseconds
     * @param defaultResponseTimeoutMillis {@code http-request-configuration.response-timeout}, read timeout
     *                                     of {@link #postOrderProxy(String)} in milliseconds, 10000 when
     *                                     the key is unset (D-454)
     * @throws NullPointerException     if a builder, ports or path argument is {@code null}
     * @throws IllegalArgumentException if {@code host} is blank or a timeout is not greater than 0
     */
    public OrderSoapClient(RestClient.Builder restClientBuilder,
                           AdditionalPortsConfig.ListenerPorts listenerPorts,
                           @Value("${http-request-configuration.host}") String host,
                           @Value("${order-request.path}") String orderRequestPath,
                           @Value("${order-proxy.path}") String orderProxyPath,
                           @Value("${order-request.response-timeout}") long orderRequestTimeoutMillis,
                           @Value("${http-request-configuration.response-timeout:10000}") long defaultResponseTimeoutMillis) {
        Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.listenerPorts = Objects.requireNonNull(listenerPorts, "listenerPorts");
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException("http-request-configuration.host must not be blank");
        }
        this.host = host.trim();
        this.orderRequestPath = leadingSlash(orderRequestPath, "order-request.path");
        this.orderProxyPath = leadingSlash(orderProxyPath, "order-proxy.path");
        Duration orderRequestTimeout = positiveMillis(orderRequestTimeoutMillis, "order-request.response-timeout");
        Duration defaultResponseTimeout = positiveMillis(defaultResponseTimeoutMillis,
                "http-request-configuration.response-timeout");

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.orderRequestClient = restClient(restClientBuilder, httpClient, orderRequestTimeout);
        this.orderProxyClient = restClient(restClientBuilder, httpClient, defaultResponseTimeout);
    }

    /**
     * Posts the envelope of the {@code orderRequest} flow
     * [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:21] to
     * {@code http://<host>:<ordersSoapPort>/<order-request.path>} with the read timeout
     * {@code order-request.response-timeout} (default 10000000 ms).
     *
     * @param envelope the SOAP envelope text, sent as its UTF-8 bytes unchanged
     * @return the response body decoded with the response charset, otherwise UTF-8; {@code ""} when empty
     * @throws NullPointerException                                       if {@code envelope} is {@code null}
     * @throws org.springframework.web.client.HttpClientErrorException    if the endpoint answers 4xx
     * @throws org.springframework.web.client.HttpServerErrorException    if the endpoint answers 5xx, for
     *                                                                    example 500 with a SOAP fault
     * @throws org.springframework.web.client.ResourceAccessException     if the connection fails or the
     *                                                                    read timeout elapses
     * @throws org.springframework.http.InvalidMediaTypeException         if the response
     *                                                                    {@code Content-Type} cannot be
     *                                                                    parsed
     */
    public String postOrderRequest(String envelope) {
        return post(orderRequestClient, orderRequestPath, envelope);
    }

    /**
     * Posts the envelope of the {@code orderProxy} flow
     * [service-orchestration-and-choice-routing/src/main/app/mule-config.xml:40] to
     * {@code http://<host>:<ordersSoapPort>/<order-proxy.path>} with the read timeout
     * {@code http-request-configuration.response-timeout} (default 10000 ms, D-454).
     *
     * @param envelope the SOAP request text of the page's textarea, sent as its UTF-8 bytes unchanged
     * @return the response body decoded with the response charset, otherwise UTF-8; {@code ""} when empty
     * @throws NullPointerException                                       if {@code envelope} is {@code null}
     * @throws org.springframework.web.client.HttpClientErrorException    if the endpoint answers 4xx
     * @throws org.springframework.web.client.HttpServerErrorException    if the endpoint answers 5xx, for
     *                                                                    example 500 with a SOAP fault
     * @throws org.springframework.web.client.ResourceAccessException     if the connection fails or the
     *                                                                    read timeout elapses
     * @throws org.springframework.http.InvalidMediaTypeException         if the response
     *                                                                    {@code Content-Type} cannot be
     *                                                                    parsed
     */
    public String postOrderProxy(String envelope) {
        return post(orderProxyClient, orderProxyPath, envelope);
    }

    /**
     * Sends one {@code POST} of the envelope bytes with {@link #REQUEST_CONTENT_TYPE} and decodes the
     * response body. Status and transport exceptions propagate unchanged.
     *
     * @param client   the {@link RestClient} carrying the read timeout of the call site
     * @param path     the request path with one leading {@code /}
     * @param envelope the SOAP envelope text
     * @return the decoded response body; {@code ""} when absent or empty
     */
    private String post(RestClient client, String path, String envelope) {
        Objects.requireNonNull(envelope, "envelope");
        int port = listenerPorts.ordersSoapPort();
        ResponseEntity<byte[]> response = client.post()
                .uri(URI_TEMPLATE_PREFIX + path, host, port)
                .header(HttpHeaders.CONTENT_TYPE, REQUEST_CONTENT_TYPE)
                .body(envelope.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toEntity(byte[].class);
        byte[] body = response.getBody();
        if (log.isDebugEnabled()) {
            log.debug("POST http://{}:{}{} answered {} with {} bytes", host, port, path,
                    response.getStatusCode().value(), body == null ? 0 : body.length);
        }
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, responseCharset(response.getHeaders()));
    }

    /**
     * Charset parameter of the response {@code Content-Type}; UTF-8 when the header is absent or carries
     * no charset. {@link RestClient} has already parsed the header while reading the body.
     *
     * @param headers the response headers
     * @return the decoding charset of the response body
     */
    private static Charset responseCharset(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        if (contentType == null || contentType.getCharset() == null) {
            return DEFAULT_RESPONSE_CHARSET;
        }
        return contentType.getCharset();
    }

    /**
     * A {@link RestClient} cloned from {@code builder} with a JDK request factory and the given read timeout.
     *
     * @param builder     the builder to clone; left unchanged
     * @param httpClient  the shared HTTP/1.1 JDK client
     * @param readTimeout the read timeout of every request of the returned client
     * @return the built client
     */
    private static RestClient restClient(RestClient.Builder builder, HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone()
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * {@code path}, trimmed, with a leading {@code /} added when it has none.
     *
     * @param path the configured path
     * @param key  the property key of {@code path}, used as the exception message
     * @return the path starting with {@code /}
     * @throws NullPointerException if {@code path} is {@code null}
     */
    private static String leadingSlash(String path, String key) {
        Objects.requireNonNull(path, key);
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * {@code millis} as a {@link Duration}, rejecting values not greater than 0.
     *
     * @param millis the configured timeout in milliseconds
     * @param key    the property key of {@code millis}, named in the exception message
     * @return the timeout as a {@link Duration}
     * @throws IllegalArgumentException if {@code millis} is not greater than 0
     */
    private static Duration positiveMillis(long millis, String key) {
        if (millis <= 0) {
            throw new IllegalArgumentException(key + " must be greater than 0 ms, was " + millis);
        }
        return Duration.ofMillis(millis);
    }
}
