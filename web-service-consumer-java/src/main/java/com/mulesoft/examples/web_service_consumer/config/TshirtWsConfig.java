package com.mulesoft.examples.web_service_consumer.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

/**
 * Spring WS client configuration of the t-shirt SOAP service. Replaces the Web Service Consumer global element
 * {@code ws:consumer-config} {@code Web_Service_Consumer}
 * [web-service-consumer/src/main/app/tshirt-service-consumer.xml:3]: service {@code TshirtService}, port
 * {@code TshirtServicePort}, address {@code http://tshirt-service.cloudhub.io} and WSDL {@code tshirt.wsdl}.
 *
 * <p>Defines three beans:
 * <ul>
 *   <li>{@link #jaxb2Marshaller()}: marshals the classes generated from {@code tshirt.wsdl} (D-028);</li>
 *   <li>{@link #messageFactory()}: builds SOAP 1.1 envelopes;</li>
 *   <li>{@link #webServiceTemplate(SaajSoapMessageFactory, Jaxb2Marshaller, Properties)}: checks the
 *       {@code tshirt.*} keys (D-358) and sends each request to {@code tshirt.service-address} through one HTTP
 *       message sender with the configured timeouts, writing each request body at most once per call (D-357).</li>
 * </ul>
 *
 * <p>Registers {@link Properties}, which binds the {@code tshirt.*} keys of {@code application.yml}. The class uses
 * Spring WS and JAXB only and imports no JAX-WS, CXF or Mule type (D-050).
 *
 * <p>Usage, as in {@code client/TshirtServiceClient}:
 * <pre>{@code
 * webServiceTemplate.sendSourceAndReceiveToResult(payload, callback, result);
 * }</pre>
 * The call goes to the template's default URI, {@code tshirt.service-address}.
 */
@Configuration
@EnableConfigurationProperties(TshirtWsConfig.Properties.class)
public class TshirtWsConfig {

    /**
     * Package of the JAXB classes and the {@code ObjectFactory} generated from {@code tshirt.wsdl} (D-028). XJC
     * derives it from the WSDL target namespace {@code http://mulesoft.org/tshirt-service}; it is a generated
     * package of this project, not a package of the Mule runtime.
     */
    private static final String JAXB_CONTEXT_PATH = "org.mulesoft.tshirt_service";

    /**
     * Placeholder value of {@code tshirt.api-key} in the committed {@code application.yml} (D-012), matched after
     * trimming and ignoring case.
     */
    private static final String API_KEY_PLACEHOLDER = "TODO";

    /**
     * Binds the {@code tshirt.*} keys of {@code application.yml}: the attributes of {@code ws:consumer-config}
     * {@code Web_Service_Consumer} [web-service-consumer/src/main/app/tshirt-service-consumer.xml:3], the
     * {@code apiKey} of the {@code AuthenticationHeader} SOAP header
     * [web-service-consumer/src/main/app/tshirt-service-consumer.xml:17-23] and the two timeouts of each call to
     * the service. {@code application.yml} supplies every default.
     *
     * <p>{@link TshirtWsConfig#webServiceTemplate(SaajSoapMessageFactory, Jaxb2Marshaller, Properties)} requires a
     * configured API key, an absolute {@code http} or {@code https} service address with a host and positive
     * timeouts, and stops startup otherwise (D-358).
     *
     * @param serviceName     WSDL service name, key {@code tshirt.service-name}
     * @param portName        WSDL port name, key {@code tshirt.port-name}
     * @param serviceAddress  endpoint address of every request, key {@code tshirt.service-address}
     * @param apiKey          value of the {@code apiKey} element of the {@code AuthenticationHeader} SOAP header,
     *                        key {@code tshirt.api-key}; the committed {@code application.yml} holds a placeholder,
     *                        and the real value is set in {@code application-local.yml} (D-012)
     * @param connectTimeout  connection timeout of each call in milliseconds, key {@code tshirt.connect-timeout}
     * @param responseTimeout response read timeout of each call in milliseconds, key {@code tshirt.response-timeout}
     */
    @ConfigurationProperties(prefix = "tshirt")
    public record Properties(
            String serviceName,
            String portName,
            String serviceAddress,
            String apiKey,
            int connectTimeout,
            int responseTimeout) {
    }

    /**
     * Creates the marshaller of the classes generated from {@code tshirt.wsdl} into package
     * {@value #JAXB_CONTEXT_PATH} (D-028): the {@code OrderTshirt}, {@code ListInventory} and
     * {@code TrackOrder} requests and responses, {@code AuthenticationHeader}, {@code APIUsageInformation} and
     * {@code TshirtFault}. The container builds its JAXB context when it initialises the bean.
     *
     * @return the marshaller and unmarshaller of the TshirtService payload and header types
     */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(JAXB_CONTEXT_PATH);
        return marshaller;
    }

    /**
     * Creates the SAAJ message factory for SOAP 1.1, the version of the {@code TshirtServiceSoapBinding} binding of
     * {@code tshirt.wsdl}. The container creates the underlying SAAJ factory when it initialises the bean.
     *
     * @return the SOAP 1.1 message factory of the TshirtService requests
     */
    @Bean
    public SaajSoapMessageFactory messageFactory() {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        return messageFactory;
    }

    /**
     * Creates the SOAP 1.1 {@link WebServiceTemplate} for TshirtService.
     *
     * <ul>
     *   <li>Messages are built by {@code messageFactory}.</li>
     *   <li>Payloads are marshalled and unmarshalled by {@code jaxb2Marshaller}.</li>
     *   <li>The default URI is {@code tshirt.service-address}.</li>
     *   <li>The only message sender is one {@link HttpUrlConnectionMessageSender} with the connection timeout
     *       {@code tshirt.connect-timeout} and the read timeout {@code tshirt.response-timeout}, in milliseconds.</li>
     *   <li>The sender puts each {@link HttpURLConnection} in chunked streaming mode with the default chunk length
     *       ({@link HttpURLConnection#setChunkedStreamingMode(int)} with {@code 0}): the request body is sent with
     *       {@code Transfer-Encoding: chunked} and no {@code Content-Length} (D-357).</li>
     * </ul>
     *
     * <p>The template has no interceptors; the {@code AuthenticationHeader} SOAP header is added per call by
     * {@code client/TshirtServiceClient}. It keeps the default SOAP fault resolver, which throws
     * {@code SoapFaultClientException} for a SOAP fault. In streaming mode {@link HttpURLConnection} sends the request
     * body at most once per call: a failed connection, body write or response read ends the call with
     * {@code WebServiceIOException} and is not repeated; only a failed header write, before any body byte, is repeated
     * once on a new connection. No authentication challenge or redirect is followed: a {@code 401}, {@code 407} or
     * {@code 3xx} response ends the call with {@code WebServiceTransportException} carrying its status (D-357).
     *
     * @param messageFactory  the SOAP 1.1 message factory from {@link #messageFactory()}
     * @param jaxb2Marshaller the marshaller from {@link #jaxb2Marshaller()}
     * @param properties      the bound {@code tshirt.*} keys
     * @return the template that sends the TshirtService requests
     * @throws IllegalStateException before the template is created, when {@code tshirt.api-key} is missing, blank or,
     *                               trimmed and ignoring case, {@code TODO}; {@code tshirt.service-address} is
     *                               missing, blank or not an absolute, hierarchical {@code http} or {@code https} URI
     *                               with a host; or {@code tshirt.connect-timeout} or {@code tshirt.response-timeout}
     *                               is not a positive number of milliseconds. The message names every invalid key
     *                               without its value (D-358)
     */
    @Bean
    public WebServiceTemplate webServiceTemplate(
            SaajSoapMessageFactory messageFactory, Jaxb2Marshaller jaxb2Marshaller, Properties properties) {
        validate(properties);
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(jaxb2Marshaller);
        template.setUnmarshaller(jaxb2Marshaller);
        template.setDefaultUri(properties.serviceAddress());

        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender() {
            @Override
            protected void prepareConnection(HttpURLConnection connection) throws IOException {
                super.prepareConnection(connection);
                connection.setChunkedStreamingMode(0);
            }
        };
        sender.setConnectionTimeout(Duration.ofMillis(properties.connectTimeout()));
        sender.setReadTimeout(Duration.ofMillis(properties.responseTimeout()));
        template.setMessageSender(sender);
        return template;
    }

    /**
     * Checks the {@code tshirt.*} keys that {@link #webServiceTemplate(SaajSoapMessageFactory, Jaxb2Marshaller,
     * Properties)} applies (D-358). Each invalid key adds its name and condition to one message:
     * <ul>
     *   <li>{@code tshirt.api-key}: missing, blank or, trimmed and ignoring case, {@value #API_KEY_PLACEHOLDER};</li>
     *   <li>{@code tshirt.service-address}: missing, blank or not an absolute, hierarchical {@code http} or
     *       {@code https} URI with a host;</li>
     *   <li>{@code tshirt.connect-timeout} and {@code tshirt.response-timeout}: zero or negative milliseconds.</li>
     * </ul>
     * The message contains no configured value.
     *
     * @param properties the bound {@code tshirt.*} keys
     * @throws IllegalStateException when at least one key is invalid
     */
    private static void validate(Properties properties) {
        List<String> invalidKeys = new ArrayList<>();
        if (!isConfiguredApiKey(properties.apiKey())) {
            invalidKeys.add("tshirt.api-key (missing, blank or the " + API_KEY_PLACEHOLDER + " placeholder)");
        }
        if (!isHttpAddress(properties.serviceAddress())) {
            invalidKeys.add("tshirt.service-address (not an absolute http or https URI with a host)");
        }
        if (properties.connectTimeout() <= 0) {
            invalidKeys.add("tshirt.connect-timeout (not a positive number of milliseconds)");
        }
        if (properties.responseTimeout() <= 0) {
            invalidKeys.add("tshirt.response-timeout (not a positive number of milliseconds)");
        }
        if (!invalidKeys.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid t-shirt service configuration: " + String.join("; ", invalidKeys));
        }
    }

    /**
     * Tells whether an API key is set: not {@code null}, not blank and, trimmed and ignoring case, not
     * {@value #API_KEY_PLACEHOLDER}.
     *
     * @param apiKey the bound {@code tshirt.api-key}, possibly {@code null}
     * @return {@code true} when the key is set
     */
    private static boolean isConfiguredApiKey(String apiKey) {
        return apiKey != null && !apiKey.isBlank() && !API_KEY_PLACEHOLDER.equalsIgnoreCase(apiKey.trim());
    }

    /**
     * Tells whether an address parses as an absolute, hierarchical {@link URI} whose scheme is {@code http} or
     * {@code https}, ignoring case, and which has a host.
     *
     * @param address the bound {@code tshirt.service-address}, possibly {@code null}
     * @return {@code true} when the address is such a URI
     */
    private static boolean isHttpAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        return uri.isAbsolute()
                && !uri.isOpaque()
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && uri.getHost() != null;
    }
}
