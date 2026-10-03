package com.mulesoft.examples.proxying_a_soap_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Address of the upstream ShopService: host and port of the {@code http:request-config} at
 * {@code soap-api-proxy.xml:4}, path of the {@code http:request} at {@code soap-api-proxy.xml:10}.
 * Bound from {@code request.http-request-configuration}.
 *
 * <p>Each component binds the key of the same name under
 * {@code request.http-request-configuration} in {@code application.yml}, which holds the
 * original literals of {@code HTTP_Request_Configuration} and of the {@code http:request} that
 * references it [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:4,10]. The application's
 * configuration-properties scan registers this record as a bean. {@code ShopServiceClientConfig}
 * builds the base URL {@code http://<host>:<port>} from {@code host} and {@code port}, and
 * {@code client/ShopServiceProxyClient} sends the envelope pass-through of D-059 as an HTTP
 * {@code POST} to {@code path} under that base URL.
 *
 * @param host host of the upstream ShopService; {@code www.predic8.com} in
 *             {@code application.yml}
 * @param port port of the upstream ShopService; {@code 8080} in {@code application.yml}
 * @param path request path of the upstream ShopService, exactly as bound;
 *             {@code shop/ShopService}, with no leading slash, in {@code application.yml}
 */
@ConfigurationProperties("request.http-request-configuration")
public record ShopServiceRequestProperties(String host, int port, String path) {
}
