package com.mulesoft.examples.web_service_consumer.config;

import java.time.Duration;

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
 *   <li>{@link #webServiceTemplate(SaajSoapMessageFactory, Jaxb2Marshaller, Properties)}: sends each request to
 *       {@code tshirt.service-address} through one HTTP message sender with the configured timeouts.</li>
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
     * Binds the {@code tshirt.*} keys of {@code application.yml}: the attributes of {@code ws:consumer-config}
     * {@code Web_Service_Consumer} [web-service-consumer/src/main/app/tshirt-service-consumer.xml:3], the
     * {@code apiKey} of the {@code AuthenticationHeader} SOAP header
     * [web-service-consumer/src/main/app/tshirt-service-consumer.xml:17-23] and the two timeouts of each call to
     * the service. {@code application.yml} supplies every default.
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
     * </ul>
     *
     * <p>The template has no interceptors; the {@code AuthenticationHeader} SOAP header is added per call by
     * {@code client/TshirtServiceClient}. It keeps the default SOAP fault resolver, which throws
     * {@code SoapFaultClientException} for a SOAP fault, and makes exactly one HTTP attempt per call.
     *
     * @param messageFactory  the SOAP 1.1 message factory from {@link #messageFactory()}
     * @param jaxb2Marshaller the marshaller from {@link #jaxb2Marshaller()}
     * @param properties      the bound {@code tshirt.*} keys
     * @return the template that sends the TshirtService requests
     */
    @Bean
    public WebServiceTemplate webServiceTemplate(
            SaajSoapMessageFactory messageFactory, Jaxb2Marshaller jaxb2Marshaller, Properties properties) {
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(jaxb2Marshaller);
        template.setUnmarshaller(jaxb2Marshaller);
        template.setDefaultUri(properties.serviceAddress());

        HttpUrlConnectionMessageSender sender = new HttpUrlConnectionMessageSender();
        sender.setConnectionTimeout(Duration.ofMillis(properties.connectTimeout()));
        sender.setReadTimeout(Duration.ofMillis(properties.responseTimeout()));
        template.setMessageSender(sender);
        return template;
    }
}
