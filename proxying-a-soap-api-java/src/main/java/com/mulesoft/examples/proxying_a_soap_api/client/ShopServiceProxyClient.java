package com.mulesoft.examples.proxying_a_soap_api.client;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.proxying_a_soap_api.config.ProxyServiceProperties;
import com.mulesoft.examples.proxying_a_soap_api.config.ShopServiceRequestProperties;
import com.mulesoft.examples.proxying_a_soap_api.exception.RequestSendException;
import com.mulesoft.examples.proxying_a_soap_api.exception.ResponseValidatorException;
import com.mulesoft.examples.proxying_a_soap_api.model.ProxyResponse;

/**
 * Sends SOAP envelopes unchanged to the upstream ShopService endpoint and fetches its WSDL. It
 * replaces the envelope-mode {@code cxf:proxy-client} and the {@code http:request}
 * {@code method="POST" path="shop/ShopService"} of flow {@code main}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:9-10], and the load of the WSDL named by the
 * {@code cxf:proxy-service} {@code wsdlLocation} [soap-api-proxy.xml:7]. Request and response bytes
 * pass through unchanged (D-059).
 *
 * <p>Both calls use the {@code shopServiceRestClient} bean of {@code config/ShopServiceClientConfig}:
 * base URL {@code http://<host>:<port>} from {@code request.http-request-configuration}
 * ({@code http://www.predic8.com:8080} in {@code application.yml}) on the JDK HTTP client (D-547).
 * Each call is exactly one attempt: no status handler is registered, nothing is retried and nothing
 * is sent a second time. A transport failure that {@link RestClient} raises as
 * {@link ResourceAccessException} (refused connection, connect timeout, read timeout or another I/O
 * error while sending the request or reading the response) is rethrown as
 * {@link RequestSendException} with that exception as its cause. Every other exception propagates
 * unchanged. The class holds no mutable state and is safe for concurrent use.
 *
 * <p>The forwarding choices of this class (fixed {@code POST} to the configured path, fixed-length
 * body, the five header names never copied, every upstream status returned) are D-663.
 *
 * <pre>{@code
 * HttpHeaders headers = new HttpHeaders();
 * headers.add("Content-Type", "text/xml");
 * headers.add("SOAPAction", "\"\"");
 * ProxyResponse answer = shopServiceProxyClient.post(headers, envelopeBytes);
 * answer.status();   // the upstream status, for example 200, or 500 for a SOAP fault
 * answer.body();     // the upstream envelope bytes as received
 *
 * byte[] wsdl = shopServiceProxyClient.getWsdl(); // ResponseValidatorException for status >= 400
 * }</pre>
 */
@Component
public class ShopServiceProxyClient {

    /**
     * Request header names, lower-case, that {@link #post(HttpHeaders, byte[])} never copies to the
     * outbound request; names are compared after {@code toLowerCase(Locale.ROOT)} (D-663).
     */
    private static final Set<String> SKIPPED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    /** Upstream ShopService client, bean {@code shopServiceRestClient} (D-547). */
    private final RestClient restClient;

    /** Host, port and path of the upstream {@code http:request} [soap-api-proxy.xml:4,10]. */
    private final ShopServiceRequestProperties requestProperties;

    /** Attributes of the {@code cxf:proxy-service}, the WSDL location among them [soap-api-proxy.xml:7]. */
    private final ProxyServiceProperties proxyServiceProperties;

    /**
     * Creates the client over the upstream ShopService {@link RestClient}.
     *
     * @param shopServiceRestClient  the bean {@code shopServiceRestClient} of
     *                               {@code config/ShopServiceClientConfig}
     * @param requestProperties      {@code request.http-request-configuration}; its {@code path} is
     *                               the path of every {@code POST}
     * @param proxyServiceProperties {@code cxf.proxy-service}; its {@code wsdlLocation} is the URL
     *                               {@link #getWsdl()} fetches
     * @throws NullPointerException when an argument is {@code null}
     */
    public ShopServiceProxyClient(@Qualifier("shopServiceRestClient") RestClient shopServiceRestClient,
                                  ShopServiceRequestProperties requestProperties,
                                  ProxyServiceProperties proxyServiceProperties) {
        this.restClient = Objects.requireNonNull(shopServiceRestClient, "shopServiceRestClient");
        this.requestProperties = Objects.requireNonNull(requestProperties, "requestProperties");
        this.proxyServiceProperties = Objects.requireNonNull(proxyServiceProperties, "proxyServiceProperties");
    }

    /**
     * Sends one {@code POST} of {@code body} to the upstream ShopService and returns its answer,
     * whatever the status [soap-api-proxy.xml:9-10] (D-059, D-663).
     *
     * <p>What is sent:
     * <ul>
     *   <li>method {@code POST}, always, to {@code /<path>} under the base URL, where {@code <path>} is
     *       {@code request.http-request-configuration.path} ({@code shop/ShopService}) with exactly one
     *       leading slash, and no query string;</li>
     *   <li>every header of {@code headers} in iteration order, each of its values in order, except the
     *       names {@code Connection}, {@code Content-Length}, {@code Expect}, {@code Host} and
     *       {@code Upgrade} in any letter case. No {@code Content-Type}, {@code Accept} or
     *       {@code Accept-Encoding} is added; a caller's {@code Content-Type} is sent unchanged. The JDK
     *       HTTP client writes {@code Host}, {@code Content-Length} and, when {@code headers} holds
     *       none, its own {@code User-Agent};</li>
     *   <li>the bytes of {@code body} unchanged, with a fixed {@code Content-Length} equal to
     *       {@code body.length}; an empty array is sent as {@code Content-Length: 0} with no body
     *       bytes.</li>
     * </ul>
     *
     * <p>What is returned: a {@link ProxyResponse} with the upstream status code (any status, a
     * {@code 500} SOAP fault included; no status raises an exception), a new {@link HttpHeaders}
     * holding every response header with all its values in the order the response reports them, and
     * the response body bytes exactly as read, neither decompressed nor decoded, or an empty array
     * when the response has no body.
     *
     * @param headers request headers to forward, as filtered by sub-flow {@code copy-headers}
     *                [soap-api-proxy.xml:13-21]; not modified
     * @param body    the SOAP envelope bytes to send; not modified
     * @return the upstream status, headers and body
     * @throws RequestSendException when the request cannot be sent or the response cannot be read:
     *                              refused connection, connect or read timeout, or another I/O error
     * @throws NullPointerException when {@code headers} or {@code body} is {@code null}
     */
    public ProxyResponse post(HttpHeaders headers, byte[] body) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        // Always POST to the configured path with no query; the caller's method and query are not
        // forwarded (D-663).
        return execute(() -> restClient.post()
                .uri(requestPath())
                .headers(outbound -> copyRequestHeaders(headers, outbound))
                // Fixed-length body: Content-Length is body.length, set after the copied headers (D-663).
                .contentLength(body.length)
                .body((StreamingHttpOutputMessage.Body) out -> out.write(body))
                .exchange((request, response) -> toProxyResponse(response)));
    }

    /**
     * Fetches the upstream WSDL with one {@code GET} of the absolute URL
     * {@code cxf.proxy-service.wsdl-location} ({@code http://www.predic8.com:8080/shop/ShopService?wsdl}),
     * its query included and the base URL not applied, and returns the response body bytes unchanged
     * [soap-api-proxy.xml:7] (D-059). No request header is set beyond those the JDK HTTP client writes.
     *
     * @return the WSDL bytes exactly as read; an empty array when the response has no body
     * @throws ResponseValidatorException carrying the upstream status when that status is {@code 400}
     *                                    or above (D-259)
     * @throws RequestSendException       when the request cannot be sent or the response cannot be
     *                                    read: refused connection, connect or read timeout, or another
     *                                    I/O error
     */
    public byte[] getWsdl() {
        ProxyResponse response = execute(() -> restClient.get()
                .uri(URI.create(proxyServiceProperties.wsdlLocation()))
                .exchange((request, clientResponse) -> toProxyResponse(clientResponse)));
        if (response.status() >= 400) {
            throw new ResponseValidatorException(response.status());
        }
        return response.body();
    }

    /**
     * Runs one upstream call and rethrows a {@link ResourceAccessException} as
     * {@link RequestSendException}; every other exception propagates unchanged. The call runs once.
     *
     * @param call the complete {@link RestClient} call chain
     * @param <T>  the result type of the call
     * @return the result of the call
     */
    private static <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            throw new RequestSendException(e);
        }
    }

    /**
     * Returns {@code request.http-request-configuration.path} with exactly one leading slash.
     *
     * @return the request path, for example {@code /shop/ShopService}
     */
    private String requestPath() {
        // The bound path may carry leading slashes (D-368); it is sent with exactly one (D-663).
        String path = requestProperties.path();
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return "/" + path.substring(start);
    }

    /**
     * Adds every value of every header of {@code source} to {@code target}, names and values in
     * iteration order, except the names in {@link #SKIPPED_HEADERS}.
     *
     * @param source the headers handed to {@link #post(HttpHeaders, byte[])}
     * @param target the headers of the outbound request
     */
    private static void copyRequestHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if (!SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    target.add(name, value);
                }
            }
        });
    }

    /**
     * Reads the status, a copy of every response header with all its values, and the body bytes of
     * {@code response}.
     *
     * @param response the upstream response
     * @return the response as a {@link ProxyResponse}
     * @throws IOException when the status or the body cannot be read
     */
    private static ProxyResponse toProxyResponse(ClientHttpResponse response) throws IOException {
        HttpHeaders copy = new HttpHeaders();
        response.getHeaders().forEach(copy::addAll);
        return new ProxyResponse(response.getStatusCode().value(), copy, response.getBody().readAllBytes());
    }
}
