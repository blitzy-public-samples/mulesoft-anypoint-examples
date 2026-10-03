package com.mulesoft.examples.proxying_a_soap_api.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures the {@link RestClient} that replaces the {@code http:request-config} at
 * {@code soap-api-proxy.xml:4} [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:4].
 *
 * <p>The single bean, {@code shopServiceRestClient}, addresses the upstream ShopService at the
 * {@code host} and {@code port} of {@link ShopServiceRequestProperties}
 * ({@code request.http-request-configuration.*}; {@code http://www.predic8.com:8080} with the
 * committed {@code application.yml}). {@code client/ShopServiceProxyClient} injects it for the
 * envelope {@code POST} to {@code shop/ShopService} [soap-api-proxy.xml:10] and for the
 * {@code ?wsdl} fetch [soap-api-proxy.xml:7] (D-059).
 */
@Configuration
public class ShopServiceClientConfig {

    /**
     * Returns a {@link RestClient} with base URL {@code http://<host>:<port>} from
     * {@link ShopServiceRequestProperties}. Uses the JDK {@link HttpClient} over HTTP/1.1 with a
     * 30000 ms connect timeout and a 10000 ms read timeout, follows redirects
     * ({@link HttpClient.Redirect#NORMAL}), and adds no headers, interceptors, status handlers or
     * retry. Used for the envelope pass-through of D-059. See D-547.
     *
     * <p>The request and response bytes pass through unchanged: the client adds no
     * {@code Accept-Encoding} header and decodes no response body. The 10000 ms read timeout runs from
     * sending the request, connecting included, to the arrival of the response status line and
     * headers: a call whose connection or response headers take longer ends at 10000 ms after its one
     * attempt, and {@link RestClient} raises the I/O failure as
     * {@link org.springframework.web.client.ResourceAccessException}. The response body is read
     * without a time limit.
     *
     * @param properties host and port of the upstream ShopService, bound from
     *                   {@code request.http-request-configuration} and registered as a bean by
     *                   the application's configuration-properties scan
     * @return the upstream ShopService client, bean {@code shopServiceRestClient}
     */
    @Bean
    public RestClient shopServiceRestClient(ShopServiceRequestProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(30000))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(10000));
        return RestClient.builder()
                .baseUrl("http://" + properties.host() + ":" + properties.port())
                .requestFactory(factory)
                .build();
    }
}
