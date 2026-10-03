package com.mulesoft.examples.proxying_a_soap_api.config;

import java.net.URI;
import java.net.URISyntaxException;

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
 * <p>The compact constructor checks every component and keeps each value exactly as bound, with no
 * trimming and no default (D-368). {@code request.http-request-configuration.host} and
 * {@code request.http-request-configuration.path} are each present, not blank and, stripped, not the
 * placeholder {@code TODO} in any letter case. The host is also exactly the host that {@link URI}
 * parses from {@code http://<host>/}: a host name or IP literal with no user information, port, path,
 * query or fragment. {@code request.http-request-configuration.port} lies in {@code 1..65535}; a
 * missing port key binds {@code 0} and fails this check. The path is accepted with or without a
 * leading slash. Nothing is resolved or connected to. A failed check throws
 * {@link IllegalArgumentException} whose message starts with the key, and binding of
 * {@code request.http-request-configuration} fails at startup.
 *
 * @param host host of the upstream ShopService; {@code www.predic8.com} in
 *             {@code application.yml}
 * @param port port of the upstream ShopService; {@code 8080} in {@code application.yml}
 * @param path request path of the upstream ShopService, exactly as bound;
 *             {@code shop/ShopService}, with no leading slash, in {@code application.yml}
 */
@ConfigurationProperties("request.http-request-configuration")
public record ShopServiceRequestProperties(String host, int port, String path) {

    private static final String HOST_KEY = "request.http-request-configuration.host";
    private static final String PORT_KEY = "request.http-request-configuration.port";
    private static final String PATH_KEY = "request.http-request-configuration.path";
    private static final String PLACEHOLDER = "TODO";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Checks the three components as the class description states and keeps each value as bound.
     *
     * @param host value bound from {@code request.http-request-configuration.host}
     * @param port value bound from {@code request.http-request-configuration.port}, {@code 0} when
     *             the key is missing
     * @param path value bound from {@code request.http-request-configuration.path}
     * @throws IllegalArgumentException naming the key of the first component that fails its check
     */
    public ShopServiceRequestProperties {
        requireHost(HOST_KEY, host);
        requirePort(PORT_KEY, port);
        requireText(PATH_KEY, path);
    }

    private static void requireText(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        if (value.strip().equalsIgnoreCase(PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    key + " must not be the placeholder " + PLACEHOLDER + ", was '" + value + "'");
        }
    }

    private static void requireHost(String key, String value) {
        requireText(key, value);
        URI uri;
        try {
            uri = new URI("http://" + value + "/");
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    key + " must be a host name or IP literal, was '" + value + "': " + e.getMessage(), e);
        }
        if (!value.equals(uri.getHost())) {
            throw new IllegalArgumentException(key + " must be a host name or IP literal with no user"
                    + " information, port, path, query or fragment, was '" + value + "'");
        }
    }

    private static void requirePort(String key, int value) {
        if (value < MIN_PORT || value > MAX_PORT) {
            throw new IllegalArgumentException(key + " must be between " + MIN_PORT + " and " + MAX_PORT
                    + ", was " + value + (value == 0 ? " (0 is bound when the key is missing)" : ""));
        }
    }
}
