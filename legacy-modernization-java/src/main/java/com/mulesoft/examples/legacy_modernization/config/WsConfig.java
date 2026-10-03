package com.mulesoft.examples.legacy_modernization.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;

import jakarta.xml.soap.SOAPEnvelope;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPHeader;

import org.glassfish.jaxb.runtime.marshaller.NamespacePrefixMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInvocationChain;
import org.springframework.ws.server.EndpointMapping;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.saaj.SaajSoapEnvelopeException;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.xml.transform.TransformerFactoryUtils;

/**
 * Spring Web Services configuration of the {@code IFulfillmentService} SOAP endpoint, the counterpart of the
 * {@code http:listener} {@code POST OrderFulfillment} and {@code cxf:jaxws-service} of flow
 * {@code Fulfillment_LegacySystemModernization} (D-028).
 *
 * <p>Registers:
 * <ul>
 *   <li>the {@link MessageDispatcherServlet} on exactly the listener path of
 *       {@code fulfillment-legacy-system-modernization.listener.path}, by default {@value #SERVICE_PATH}
 *       (D-139, D-327);
 *       the listener address comes from {@code server.port} and {@code server.address} in
 *       {@code application.yml};</li>
 *   <li>the fixed WSDL contract {@value #WSDL_LOCATION} as bean {@code IFulfillmentService};</li>
 *   <li>the SOAP 1.1 message factory {@code messageFactory}, whose response envelopes use the {@code soap} prefix
 *       and carry no {@code Header} (D-324);</li>
 *   <li>the {@link Jaxb2Marshaller} over the generated {@code org.ordermgmt} types, which writes the
 *       {@code http://ordermgmt.org/} namespace with the prefix {@code ns2}, and its
 *       {@link MarshallingPayloadMethodProcessor} as the first return value handler of the endpoint adapter
 *       (D-083, D-326);</li>
 *   <li>the lowest-precedence {@link UnexpectedWrapperEndpointMapping}, which turns a request whose payload root
 *       is not {@code {http://ordermgmt.org/}putShippingOrder} into an {@link UnexpectedWrapperElementException}
 *       for the project's endpoint exception resolver (D-325).</li>
 * </ul>
 *
 * <p>{@link EnableWs} imports the Spring WS annotation infrastructure (payload-root, SOAP-action and WS-Addressing
 * endpoint mappings, {@code DefaultMethodEndpointAdapter}, the annotation and simple SOAP exception resolvers).
 */
@Configuration
@EnableWs
public class WsConfig implements WsConfigurer {

    /**
     * Servlet path of the SOAP endpoint for the default listener path {@code OrderFulfillment} of
     * {@code fulfillment-legacy-system-modernization.listener.path}.
     */
    public static final String SERVICE_PATH = "/OrderFulfillment";

    /** Classpath location of the fixed WSDL contract (D-028). */
    public static final String WSDL_LOCATION = "wsdl/IFulfillmentService.wsdl";

    /** Target namespace of the {@code IFulfillmentService} contract. */
    private static final String ORDER_NAMESPACE_URI = "http://ordermgmt.org/";

    /** Local name of the only request wrapper element the contract declares. */
    private static final String EXPECTED_WRAPPER_LOCAL_NAME = "putShippingOrder";

    /** SOAP 1.1 envelope namespace. */
    private static final String SOAP11_ENVELOPE_NAMESPACE_URI = "http://schemas.xmlsoap.org/soap/envelope/";

    /** Prefix of the SOAP 1.1 envelope namespace in every response envelope. */
    private static final String SOAP_PREFIX = "soap";

    /** Prefix of the {@code http://ordermgmt.org/} namespace in marshalled response payloads. */
    private static final String ORDER_NAMESPACE_PREFIX = "ns2";

    /** Java package of the JAXB types generated from {@value #WSDL_LOCATION}. */
    private static final String JAXB_CONTEXT_PATH = "org.ordermgmt";

    /** Glassfish JAXB marshaller property that takes a {@link NamespacePrefixMapper}. */
    private static final String NAMESPACE_PREFIX_MAPPER_PROPERTY = "org.glassfish.jaxb.namespacePrefixMapper";

    /** Name of the dispatcher servlet registration. */
    private static final String DISPATCHER_SERVLET_NAME = "messageDispatcherServlet";

    /** {@code application.yml} key of the listener path of flow {@code Fulfillment_LegacySystemModernization}. */
    private static final String LISTENER_PATH_PROPERTY = "fulfillment-legacy-system-modernization.listener.path";

    /** Original listener path, in Mule path syntax without the leading slash. */
    private static final String LISTENER_PATH_DEFAULT = "OrderFulfillment";

    /**
     * Registers the Spring WS {@link MessageDispatcherServlet}, loaded on startup, on exactly the servlet path of
     * {@code fulfillment-legacy-system-modernization.listener.path}: the key's value with a leading {@code /}
     * added when it has none, {@value #SERVICE_PATH} for the default {@code OrderFulfillment} (D-139, D-327).
     *
     * <p>A {@code POST} to that path is dispatched to the SOAP endpoint; any other method answers {@code 405}. Other
     * paths stay with the Spring MVC {@code DispatcherServlet}.
     *
     * @param applicationContext the application context that holds the endpoint, mappings and resolvers, and whose
     *                           environment supplies the listener path
     * @return the servlet registration named {@code messageDispatcherServlet}
     * @throws IllegalStateException when the listener path key is set to a blank value
     */
    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(
            ApplicationContext applicationContext) {
        // The servlet mapping is read from the D-139 listener path key (D-327); SERVICE_PATH maps its default.
        String listenerPath = applicationContext.getEnvironment()
                .getProperty(LISTENER_PATH_PROPERTY, LISTENER_PATH_DEFAULT);
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(applicationContext);
        ServletRegistrationBean<MessageDispatcherServlet> registration =
                new ServletRegistrationBean<>(servlet, servletPath(listenerPath));
        registration.setName(DISPATCHER_SERVLET_NAME);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * Converts a listener path in Mule path syntax into a servlet URL mapping: surrounding whitespace removed and a
     * leading {@code /} added when absent, for example {@code OrderFulfillment} to {@value #SERVICE_PATH}.
     *
     * @param listenerPath the configured listener path
     * @return the servlet URL mapping
     * @throws IllegalStateException when {@code listenerPath} is blank
     */
    private static String servletPath(String listenerPath) {
        String path = listenerPath.trim();
        if (path.isEmpty()) {
            throw new IllegalStateException(LISTENER_PATH_PROPERTY + " must not be blank");
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Serves the fixed contract {@value #WSDL_LOCATION} (D-028).
     *
     * @return the WSDL definition over the classpath resource, never generated at runtime
     */
    @Bean("IFulfillmentService")
    public SimpleWsdl11Definition iFulfillmentService() {
        return new SimpleWsdl11Definition(new ClassPathResource(WSDL_LOCATION));
    }

    /**
     * The SOAP 1.1 message factory the dispatcher servlet uses for requests and responses (D-324).
     *
     * @return the response-envelope message factory, registered under the name {@code messageFactory}
     */
    @Bean("messageFactory")
    public ResponseEnvelopeMessageFactory messageFactory() {
        return new ResponseEnvelopeMessageFactory();
    }

    /**
     * JAXB marshaller over the generated {@code org.ordermgmt} types; it writes the {@code http://ordermgmt.org/}
     * namespace with the prefix {@code ns2} through {@link OrderMgmtPrefixMapper} (D-083, D-326).
     *
     * @return the marshaller for the {@code org.ordermgmt} context path
     */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(JAXB_CONTEXT_PATH);
        marshaller.setMarshallerProperties(Map.of(NAMESPACE_PREFIX_MAPPER_PROPERTY, new OrderMgmtPrefixMapper()));
        return marshaller;
    }

    /**
     * Puts a {@link MarshallingPayloadMethodProcessor} over {@link #jaxb2Marshaller()} at the head of the endpoint
     * adapter's return value handlers, ahead of the default JAXB handlers the list holds on entry (D-083, D-326).
     * Endpoint methods returning an {@code org.ordermgmt} type or a {@code JAXBElement} of one are then marshalled
     * with the {@code ns2} prefix; request payloads stay with the default argument resolvers.
     *
     * @param returnValueHandlers the adapter's return value handlers, initially the Spring WS defaults
     */
    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        returnValueHandlers.add(0, new MarshallingPayloadMethodProcessor(jaxb2Marshaller()));
    }

    /**
     * The endpoint mapping consulted after every other mapping, for requests whose payload root matches no
     * endpoint (D-325).
     *
     * @return the unexpected-wrapper endpoint mapping
     */
    @Bean
    public UnexpectedWrapperEndpointMapping unexpectedWrapperEndpointMapping() {
        return new UnexpectedWrapperEndpointMapping();
    }

    /**
     * SOAP 1.1 SAAJ message factory whose newly created messages are written as
     * {@code <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>…</soap:Body>
     * </soap:Envelope>}, without a {@code Header} and without an XML declaration (D-324).
     *
     * <p>Spring WS creates response messages through {@link #createWebServiceMessage()}; request messages are read
     * through {@link #createWebServiceMessage(java.io.InputStream)}, which keeps the inbound envelope unchanged.
     */
    public static class ResponseEnvelopeMessageFactory extends SaajSoapMessageFactory {

        /**
         * Creates an empty SOAP 1.1 message, removes its empty {@code Header} and puts its {@code Envelope} and
         * {@code Body} in the {@code soap} prefix.
         *
         * @return the empty response message
         * @throws SaajSoapEnvelopeException when the SAAJ envelope cannot be read or changed
         */
        @Override
        public SaajSoapMessage createWebServiceMessage() {
            SaajSoapMessage message = super.createWebServiceMessage();
            try {
                SOAPEnvelope envelope = message.getSaajMessage().getSOAPPart().getEnvelope();
                SOAPHeader header = envelope.getHeader();
                if (header != null) {
                    header.detachNode();
                }
                String createdPrefix = envelope.getPrefix();
                if (createdPrefix != null && !createdPrefix.isEmpty() && !SOAP_PREFIX.equals(createdPrefix)) {
                    envelope.removeNamespaceDeclaration(createdPrefix);
                }
                envelope.addNamespaceDeclaration(SOAP_PREFIX, SOAP11_ENVELOPE_NAMESPACE_URI);
                envelope.setPrefix(SOAP_PREFIX);
                envelope.getBody().setPrefix(SOAP_PREFIX);
            }
            catch (SOAPException ex) {
                throw new SaajSoapEnvelopeException(ex);
            }
            return message;
        }
    }

    /**
     * Namespace prefix mapper of {@link #jaxb2Marshaller()}: the {@code http://ordermgmt.org/} namespace gets the
     * prefix {@code ns2}; every other namespace keeps the prefix JAXB suggests (D-083, D-326).
     */
    public static class OrderMgmtPrefixMapper extends NamespacePrefixMapper {

        /**
         * Returns {@code ns2} for {@code http://ordermgmt.org/} and {@code suggestion} for any other namespace.
         *
         * @param namespaceUri  the namespace URI JAXB is about to declare
         * @param suggestion    the prefix JAXB would use without a mapper
         * @param requirePrefix whether the namespace needs a non-empty prefix
         * @return the prefix to declare
         */
        @Override
        public String getPreferredPrefix(String namespaceUri, String suggestion, boolean requirePrefix) {
            if (ORDER_NAMESPACE_URI.equals(namespaceUri)) {
                return ORDER_NAMESPACE_PREFIX;
            }
            return suggestion;
        }
    }

    /**
     * Raised for a request whose payload root element is not {@code {http://ordermgmt.org/}putShippingOrder}
     * (D-325). Its message is the SOAP {@code faultstring}, for example
     * {@code Unexpected wrapper element {http://example.com/}foo found.   Expected {http://ordermgmt.org/}putShippingOrder.}
     */
    public static class UnexpectedWrapperElementException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception with the given fault message.
         *
         * @param message the fault message
         */
        public UnexpectedWrapperElementException(String message) {
            super(message);
        }
    }

    /**
     * Endpoint mapping with {@link Ordered#LOWEST_PRECEDENCE}, consulted by the {@code MessageDispatcher} after the
     * payload-root, SOAP-action and WS-Addressing mappings. It maps no request to an endpoint: a request with a
     * payload root element raises {@link UnexpectedWrapperElementException}, and a request without a payload is
     * left unmapped (D-325). Instances are stateless apart from the shared {@link TransformerFactory}.
     */
    public static class UnexpectedWrapperEndpointMapping implements EndpointMapping, Ordered {

        private static final Logger LOG = LoggerFactory.getLogger(UnexpectedWrapperEndpointMapping.class);

        private static final QName EXPECTED_WRAPPER = new QName(ORDER_NAMESPACE_URI, EXPECTED_WRAPPER_LOCAL_NAME);

        private final TransformerFactory transformerFactory = TransformerFactoryUtils.newInstance();

        /**
         * Reads the request payload root and raises {@link UnexpectedWrapperElementException} for it.
         *
         * <p>The message reads {@code Unexpected wrapper element <root> found.   Expected
         * {http://ordermgmt.org/}putShippingOrder.}, with three spaces before {@code Expected} and {@code <root>} in
         * {@link QName#toString()} form: {@code {namespace}local}, or {@code local} for an element without a
         * namespace.
         *
         * <p>With DEBUG enabled, the line {@code Request payload root <root> matches no endpoint} is logged first; it
         * renders the payload root with its control characters escaped as {@code escapeControls} describes (D-339),
         * while the exception message keeps the root's {@link QName#toString()} form.
         *
         * @param messageContext the message context of the request
         * @return {@code null} when the request has no payload or no payload root element
         * @throws UnexpectedWrapperElementException when the request has a payload root element
         * @throws javax.xml.transform.TransformerException when the payload cannot be read
         */
        @Override
        public EndpointInvocationChain getEndpoint(MessageContext messageContext) throws Exception {
            Source source = messageContext.getRequest().getPayloadSource();
            if (source == null) {
                return null;
            }
            QName root = PayloadRootUtils.getPayloadRootQName(source, this.transformerFactory);
            if (root == null) {
                return null;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Request payload root {} matches no endpoint", escapeControls(root.toString()));
            }
            throw new UnexpectedWrapperElementException(
                    "Unexpected wrapper element " + root + " found.   Expected " + EXPECTED_WRAPPER + ".");
        }

        /**
         * Returns {@code value} with each ISO control character (U+0000 to U+001F and U+007F to U+009F) and each
         * U+2028 line separator and U+2029 paragraph separator replaced by a six-character escape: a backslash, the
         * letter {@code u} and the character's code in four upper-case hexadecimal digits. A line feed is written as
         * backslash-u000A and a carriage return as backslash-u000D; every other character is kept unchanged (D-339).
         *
         * @param value the text to escape
         * @return the escaped text
         */
        private static String escapeControls(String value) {
            StringBuilder escaped = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
                    escaped.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                } else {
                    escaped.append(c);
                }
            }
            return escaped.toString();
        }

        /**
         * Returns {@link Ordered#LOWEST_PRECEDENCE}.
         *
         * @return {@link Integer#MAX_VALUE}
         */
        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
