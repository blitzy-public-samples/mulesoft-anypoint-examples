package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config.HttpRequestConfigurationProperties;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model.CurrencyRates;

import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends {@code GET <basePath><path>} to this application's own HTTP port and binds the body to
 * {@link CurrencyRates} (D-054). Replaces the request configuration {@code HTTP_Request_Configuration}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:3-5] and the
 * {@code http:request GET /currencies} inside the {@code flowVars.currencies} enricher of
 * {@code OrderFlow} [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:9-11].
 *
 * <p>The request URI is {@code http://<host>:<port><basePath><path>}, with the values of
 * {@link HttpRequestConfigurationProperties} and the port of the running web server, read on each call.
 * {@code basePath} and {@code path} are joined with exactly one {@code /} (D-140, D-471). With the
 * {@code application.yml} defaults the URI is {@code http://localhost:<port>/api/currencies}.
 *
 * <p>The HTTP stack is built once at construction and is used by every call (D-471):
 * <ul>
 *   <li>a JDK {@link HttpClient} that speaks HTTP/1.1, follows no redirect and has
 *       {@code responseTimeout} as its connect timeout;</li>
 *   <li>a {@link JdkClientHttpRequestFactory} that has {@code responseTimeout} as its read timeout;</li>
 *   <li>a client-local {@link ObjectMapper} with {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS},
 *       registered on this client only, ahead of the other message converters. The application
 *       {@link ObjectMapper} bean is not changed.</li>
 * </ul>
 *
 * <p>Each call sends one request with no body, sets no header other than {@code Accept: application/json},
 * and makes no second attempt. Failures propagate unchanged: a non-2xx status raises
 * {@link org.springframework.web.client.RestClientResponseException}, and a connection, timeout or other
 * I/O failure raises {@link org.springframework.web.client.ResourceAccessException}.
 *
 * <p>Instances are thread-safe.
 *
 * <p>Example:
 *
 * <pre>{@code
 * CurrencyRates rates = currencyClient.getCurrencies();
 * BigDecimal eur = rates.usd().get(0).ratio(); // 0.92 with the committed currency.json
 * }</pre>
 */
@Component
public class CurrencyClient {

    /** Separator inserted between {@code basePath} and {@code path}; runs of {@code /} collapse to one. */
    private static final String PATH_SEPARATOR = "/";

    /** Scheme of the request URI. */
    private static final String SCHEME = "http";

    /**
     * Client over the JDK HTTP/1.1 stack with the client-local JSON converter. It has no base URL: each
     * call composes the absolute URI from host, server port, base path and path (D-054, D-471).
     */
    private final RestClient restClient;

    /** Host, base path, path and response timeout of the request. */
    private final HttpRequestConfigurationProperties properties;

    /** Context whose web server supplies the port on each call. */
    private final ApplicationContext applicationContext;

    /**
     * Builds the HTTP stack from {@code properties} on a clone of {@code restClientBuilder}; the given
     * builder is left unchanged. The web server and its port are not read here.
     *
     * @param restClientBuilder  builder whose clone receives the request factory and message converters
     * @param properties         host, base path, path and response timeout of the request
     * @param applicationContext context whose {@link WebServerApplicationContext#getWebServer() web server}
     *                           supplies the port on each call
     * @throws NullPointerException if any argument is {@code null}
     */
    public CurrencyClient(RestClient.Builder restClientBuilder, HttpRequestConfigurationProperties properties,
            ApplicationContext applicationContext) {
        Objects.requireNonNull(restClientBuilder, "restClientBuilder");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.responseTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.responseTimeout());

        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.restClient = restClientBuilder.clone()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(0, new MappingJackson2HttpMessageConverter(mapper));
                })
                .build();
    }

    /**
     * Sends {@code GET http://<host>:<port><basePath><path>} with {@code Accept: application/json} to the
     * running web server of this application and returns the response body bound to
     * {@link CurrencyRates}, unchanged: the rates keep the order of the body (EUR, ARS, GBP with the
     * committed {@code currency.json}) and each {@code ratio} keeps the scale it has in the body (D-054).
     *
     * @return the rates of the response body; {@code null} when the response has no body
     * @throws IllegalStateException if the application context is not a
     *                               {@link WebServerApplicationContext}, has no web server, or its web
     *                               server has no assigned port
     * @throws org.springframework.web.client.RestClientResponseException if the response status is not
     *                               2xx
     * @throws org.springframework.web.client.ResourceAccessException if the connection fails, the
     *                               response timeout elapses or another I/O error occurs
     * @throws org.springframework.web.client.RestClientException if the body cannot be read as
     *                               {@link CurrencyRates}
     */
    public CurrencyRates getCurrencies() {
        // base-path, one '/', then path: both read from request.http-request-configuration (D-140, D-471).
        URI uri = UriComponentsBuilder.newInstance()
                .scheme(SCHEME)
                .host(properties.host())
                .port(serverPort())
                .path(properties.basePath())
                .path(PATH_SEPARATOR)
                .path(properties.path())
                .build()
                .toUri();
        return restClient.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(CurrencyRates.class);
    }

    /**
     * Returns the port of the running web server of {@link #applicationContext}.
     *
     * @return the assigned port, greater than zero
     * @throws IllegalStateException if the context is not a {@link WebServerApplicationContext}, has no
     *                               web server, or its web server has no assigned port
     */
    private int serverPort() {
        if (!(applicationContext instanceof WebServerApplicationContext webServerContext)) {
            throw new IllegalStateException("No running web server: the currencies endpoint cannot be called; "
                    + "the application context is not a WebServerApplicationContext");
        }
        WebServer webServer = webServerContext.getWebServer();
        if (webServer == null) {
            throw new IllegalStateException("No web server in the application context: "
                    + "the currencies endpoint cannot be called");
        }
        int port = webServer.getPort();
        if (port <= 0) {
            throw new IllegalStateException("No assigned web server port (port " + port + "): "
                    + "the currencies endpoint cannot be called");
        }
        return port;
    }
}
