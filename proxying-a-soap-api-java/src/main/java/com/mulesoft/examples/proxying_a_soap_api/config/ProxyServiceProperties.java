package com.mulesoft.examples.proxying_a_soap_api.config;

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
}
