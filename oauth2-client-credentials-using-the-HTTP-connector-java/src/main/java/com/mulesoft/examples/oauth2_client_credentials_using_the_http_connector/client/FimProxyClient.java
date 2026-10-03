package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception.ResponseValidatorException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Forwards the method, raw request URI and, for methods other than GET, HEAD and OPTIONS, the body and its
 * {@code Content-Type} to the FIM base URL {@code fim.base-url}, and returns the response body and its
 * {@code Content-Type} unchanged. Replaces the request configuration {@code HTTP_Request_Configuration1}
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:29] and the
 * {@code http:request} of {@code http-client-credentialsFlow}
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:39-47]
 * (D-316, D-541).
 *
 * <p>The HTTP stack is built once at construction and is used by every call (D-541):
 * <ul>
 *   <li>a JDK {@link HttpClient} that speaks HTTP/1.1 only, follows redirects with
 *       {@link HttpClient.Redirect#NORMAL}, has a fixed connect timeout of 10000 ms and uses the default
 *       JVM trust (no custom {@code SSLContext});</li>
 *   <li>a {@link JdkClientHttpRequestFactory} whose read timeout is {@code fim.response-timeout}
 *       milliseconds (default 10000);</li>
 *   <li>a {@link RestClient} on that factory with no base URL, no default header and no interceptor.</li>
 * </ul>
 *
 * <p>Each call sends exactly one request. The request carries no inbound header and no {@code client_id}
 * parameter. Besides the JDK client's own {@code Host}, {@code User-Agent} and {@code Content-Length}
 * headers ({@code Content-Length: 0} when no body is sent), its only header is {@code Content-Type}, set
 * when a body is sent and a type is given. Failures propagate unchanged and are not caught, wrapped or
 * retried:
 * <ul>
 *   <li>a final status &ge; 400 raises {@link ResponseValidatorException} with message
 *       {@code Response code <n> mapped as failure.} (D-207);</li>
 *   <li>a connect or read timeout, a TLS failure or another I/O failure raises
 *       {@link org.springframework.web.client.ResourceAccessException};</li>
 *   <li>an illegal target URI, or a method the JDK client rejects (for example {@code CONNECT}), raises
 *       {@link IllegalArgumentException}.</li>
 * </ul>
 *
 * <p>Instances are thread-safe. The original example has no tests (D-052).
 *
 * <p>Example:
 *
 * <pre>{@code
 * FimResponse response = fimProxyClient.forward("POST", "/FIM/a%20b?x=1",
 *         "a=1".getBytes(StandardCharsets.US_ASCII), "application/x-www-form-urlencoded");
 * byte[] upstreamBody = response.body();        // response bytes as FIM sent them
 * String upstreamType = response.contentType(); // FIM's raw Content-Type, or null
 * }</pre>
 */
@Component
public class FimProxyClient {

    /** Connect timeout of the JDK {@link HttpClient}, in milliseconds. */
    private static final long CONNECT_TIMEOUT_MILLIS = 10000L;

    /** Methods sent without a body and without a {@code Content-Type}. */
    private static final Set<HttpMethod> NO_BODY_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD,
            HttpMethod.OPTIONS);

    /** Scheme, host and port that prefix every request URI; concatenated with it unchanged. */
    private final String fimBaseUrl;

    /** Client over the JDK HTTP/1.1 stack; it has no base URL, default header or interceptor. */
    private final RestClient restClient;

    /**
     * Response of one forwarded call.
     *
     * <p>{@code equals} and {@code hashCode} compare {@code body} by array reference; compare bodies with
     * {@link java.util.Arrays#equals(byte[], byte[])}.
     *
     * @param body        response bytes as FIM sent them; empty (never {@code null}) for HEAD, 204 or a
     *                    response with no body
     * @param contentType raw {@code Content-Type} header of the response; {@code null} when FIM sends none
     */
    public record FimResponse(byte[] body, String contentType) {
    }

    /**
     * Builds the HTTP stack described on the class. The two keys are bound with {@code @Value} on this
     * constructor; this client has no properties record (D-541).
     *
     * @param fimBaseUrl            value of {@code fim.base-url}: an absolute URI with a scheme and a host,
     *                              to which each request URI is appended unchanged
     * @param responseTimeoutMillis value of {@code fim.response-timeout} (default 10000), the read timeout
     *                              in milliseconds
     * @throws NullPointerException     if {@code fimBaseUrl} is {@code null}
     * @throws IllegalArgumentException if {@code fimBaseUrl} is not an absolute URI with a host, or
     *                                  {@code responseTimeoutMillis} is not positive
     */
    public FimProxyClient(@Value("${fim.base-url}") String fimBaseUrl,
            @Value("${fim.response-timeout:10000}") long responseTimeoutMillis) {
        Objects.requireNonNull(fimBaseUrl, "fimBaseUrl");
        URI base = URI.create(fimBaseUrl);
        if (!base.isAbsolute() || base.getHost() == null) {
            throw new IllegalArgumentException(
                    "fim.base-url must be an absolute URI with a host: " + fimBaseUrl);
        }
        if (responseTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "fim.response-timeout must be a positive number of milliseconds: " + responseTimeoutMillis);
        }
        this.fimBaseUrl = fimBaseUrl;

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(responseTimeoutMillis));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Sends {@code method} to {@code URI.create(fimBaseUrl + requestUri)} and returns the response body and
     * its {@code Content-Type}. The path and query of {@code requestUri} are sent as given: nothing is
     * expanded, decoded or re-encoded.
     *
     * <p>When {@code body} is not {@code null} and the method is not GET, HEAD or OPTIONS, the request sets
     * {@code Content-Type} to {@code contentType} as given (only when it is not {@code null}), sets
     * {@code Content-Length} to {@code body.length} and writes the bytes of {@code body}. Otherwise the
     * request has no body and no {@code Content-Type}.
     *
     * @param method      HTTP method name as received, for example {@code GET}; resolved with
     *                    {@link HttpMethod#valueOf(String)}, which is case-sensitive
     * @param requestUri  raw request URI: the path plus {@code ?query} when present, for example
     *                    {@code /FIM/a%20b?x=1&y=%2F}
     * @param body        raw request bytes; may be {@code null}
     * @param contentType raw {@code Content-Type} of the inbound request; may be {@code null}
     * @return the response bytes (empty when there are none) and the raw response {@code Content-Type}
     *         ({@code null} when absent)
     * @throws ResponseValidatorException if the final response status is 400 or higher
     * @throws org.springframework.web.client.ResourceAccessException on a connect or read timeout, a TLS
     *         failure or another I/O failure
     * @throws IllegalArgumentException if {@code fimBaseUrl + requestUri} is not a legal URI, or the JDK
     *         client rejects the method
     * @throws NullPointerException if {@code method} or {@code requestUri} is {@code null}
     */
    public FimResponse forward(String method, String requestUri, byte[] body, String contentType) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");
        HttpMethod httpMethod = HttpMethod.valueOf(method);
        URI target = URI.create(fimBaseUrl + requestUri);

        RestClient.RequestBodySpec request = restClient.method(httpMethod).uri(target);
        if (body != null && !NO_BODY_METHODS.contains(httpMethod)) {
            request.headers(headers -> {
                if (contentType != null) {
                    headers.set(HttpHeaders.CONTENT_TYPE, contentType);
                }
                headers.setContentLength(body.length);
            });
            request.body(out -> out.write(body));
        }

        return request.exchange((clientRequest, response) -> {
            int status = response.getStatusCode().value();
            if (status >= 400) {
                throw new ResponseValidatorException(status);
            }
            byte[] bytes = response.getBody().readAllBytes();
            String responseContentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            return new FimResponse(bytes, responseContentType);
        });
    }
}
