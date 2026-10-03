package com.mulesoft.examples.proxying_a_soap_api.config;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Attributes of the {@code cxf:proxy-service} at {@code soap-api-proxy.xml:7}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:7]: the upstream WSDL location and the
 * service QName ({@code namespace}, {@code service}) and WSDL port name. Bound from the
 * {@code cxf.proxy-service} keys of {@code application.yml}. Read for {@code ?wsdl} serving (D-059).
 *
 * <p>Each component binds the key of the same name in kebab case: {@code wsdl-location},
 * {@code namespace}, {@code service} and {@code port}. The element's {@code payload="envelope"} and
 * {@code enableMuleSoapHeaders="false"} attributes are fixed behaviour of the envelope pass-through
 * (D-059) and have no key.
 *
 * <p>Consumers: {@code client/ShopServiceProxyClient} fetches the WSDL at {@link #wsdlLocation()};
 * {@code service/SoapProxyService} rewrites the {@code soap:address} of the port named by
 * {@link #port()} in the service named by {@link #namespace()} and {@link #service()}.
 *
 * <p>The compact constructor checks every component and keeps each value exactly as bound, with no
 * trimming and no default (D-368). {@code cxf.proxy-service.wsdl-location} parses as a
 * {@link URI} with the scheme {@code http} or {@code https} in any letter case, a host, and either
 * no port or a port in {@code 1..65535}; the URL is only parsed, and nothing is fetched.
 * {@code cxf.proxy-service.wsdl-location}, {@code cxf.proxy-service.namespace},
 * {@code cxf.proxy-service.service} and {@code cxf.proxy-service.port} are each present, not blank
 * and, stripped, not the placeholder {@code TODO} in any letter case; {@code port} is checked as a
 * WSDL port name, never as a TCP port. A failed check throws {@link IllegalArgumentException} whose
 * message starts with the key, and binding of {@code cxf.proxy-service} fails at startup.
 *
 * @param wsdlLocation URL of the upstream ShopService WSDL;
 *                     {@code http://www.predic8.com:8080/shop/ShopService?wsdl} in
 *                     {@code application.yml}
 * @param namespace    namespace URI of the proxied service QName, the WSDL
 *                     {@code targetNamespace}; {@code http://predic8.com/wsdl/shop/1/} in
 *                     {@code application.yml}
 * @param service      local name of the proxied service QName, the WSDL {@code wsdl:service}
 *                     name; {@code ShopService} in {@code application.yml}
 * @param port         WSDL port name ({@code wsdl:port} name) of the proxied service, not a TCP
 *                     port; {@code ShopServicePTPort} in {@code application.yml}
 */
@ConfigurationProperties("cxf.proxy-service")
public record ProxyServiceProperties(String wsdlLocation, String namespace, String service, String port) {

    private static final String WSDL_LOCATION_KEY = "cxf.proxy-service.wsdl-location";
    private static final String NAMESPACE_KEY = "cxf.proxy-service.namespace";
    private static final String SERVICE_KEY = "cxf.proxy-service.service";
    private static final String PORT_KEY = "cxf.proxy-service.port";
    private static final String PLACEHOLDER = "TODO";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Checks the four components as the class description states and keeps each value as bound.
     *
     * @param wsdlLocation value bound from {@code cxf.proxy-service.wsdl-location}
     * @param namespace    value bound from {@code cxf.proxy-service.namespace}
     * @param service      value bound from {@code cxf.proxy-service.service}
     * @param port         value bound from {@code cxf.proxy-service.port}, a WSDL port name
     * @throws IllegalArgumentException naming the key of the first component that fails its check
     */
    public ProxyServiceProperties {
        requireHttpUrl(WSDL_LOCATION_KEY, wsdlLocation);
        requireText(NAMESPACE_KEY, namespace);
        requireText(SERVICE_KEY, service);
        requireText(PORT_KEY, port);
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

    private static void requireHttpUrl(String key, String value) {
        requireText(key, value);
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    key + " must be a valid URL, was '" + value + "': " + e.getMessage(), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    key + " must be an absolute http or https URL, was '" + value + "'");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException(key + " must name a host, was '" + value + "'");
        }
        int urlPort = uri.getPort();
        if (urlPort != -1 && (urlPort < MIN_PORT || urlPort > MAX_PORT)) {
            throw new IllegalArgumentException(key + " must have no port or a port between " + MIN_PORT
                    + " and " + MAX_PORT + ", was " + urlPort + " in '" + value + "'");
        }
    }
}
