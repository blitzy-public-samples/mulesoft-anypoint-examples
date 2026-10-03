package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import javax.xml.namespace.QName;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.server.endpoint.adapter.DefaultMethodEndpointAdapter;
import org.springframework.ws.server.endpoint.mapping.PayloadRootAnnotationMethodEndpointMapping;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.xml.transform.TransformerFactoryUtils;

import com.mulesoft.examples.foreach_processing_and_choice_routing.exception.SoapFaultMappingExceptionResolver;

/**
 * Spring Web Services wiring of the mock credit agency and the five mock banks, the counterparts of the
 * {@code cxf:jaxws-service} elements of flows {@code TheCreditAgencyService} and {@code Bank1Flow} …
 * {@code Bank5Flow} (D-028).
 *
 * <p>Sources: {@code foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml}:
 * <ul>
 *   <li>:138-144, flow {@code TheCreditAgencyService}: listener 2 (18080, base path
 *       {@code /mule/TheCreditAgencyService}), {@code cxf:jaxws-service} over {@code DefaultCreditAgency} (:140);</li>
 *   <li>:146-195, flows {@code Bank1Flow} … {@code Bank5Flow}: listeners 3 … 7 (10080 … 50080, base paths
 *       {@code /mule/TheBank1} … {@code /mule/TheBank5}), {@code cxf:jaxws-service} over {@code Bank}
 *       (:148, :158, :169, :179, :189);</li>
 *   <li>:72 and :107, the {@code cxf:jaxws-client} calls of {@code getCreditProfile} and {@code getLoanQuote}.</li>
 * </ul>
 *
 * <p>Beans:
 * <ul>
 *   <li>{@code messageFactory}: the SOAP 1.1 {@link SaajSoapMessageFactory} that reads every request and writes
 *       every response of the dispatcher;</li>
 *   <li>{@code jaxb2Marshaller}: one {@link Jaxb2Marshaller} over the two XJC packages
 *       {@value #CREDIT_AGENCY_CONTEXT_PATH} and {@value #BANK_CONTEXT_PATH};</li>
 *   <li>{@code endpointMapping}: the {@link PayloadRootAnnotationMethodEndpointMapping} that maps the
 *       {@code @PayloadRoot} methods of the context's {@code @Endpoint} beans, {@code endpoint.CreditAgencyEndpoint}
 *       (root {@code getCreditProfile} in {@value #CREDIT_AGENCY_NAMESPACE}) and {@code endpoint.BankEndpoint}
 *       (root {@code getLoanQuote} in {@value #BANK_NAMESPACE}), with the port check of
 *       {@link PortNamespaceInterceptor} and the rejecting default endpoint of {@link UnknownPayloadRootEndpoint};</li>
 *   <li>{@code endpointAdapter}: a {@link DefaultMethodEndpointAdapter} with its default argument resolvers and
 *       return value handlers, which bind the XJC {@code JAXBElement} wrappers;</li>
 *   <li>{@code soapFaultResolver}: the only instance of the project's {@link SoapFaultMappingExceptionResolver}
 *       (D-223);</li>
 *   <li>{@code messageDispatcher}: the only {@link SoapMessageDispatcher} of the context, holding exactly the
 *       mapping, the adapter and the resolver above;</li>
 *   <li>{@code wsHandlerAdapter}: the {@link WebServiceMessageReceiverHandlerAdapter} that runs the dispatcher
 *       for a Spring MVC request over {@code messageFactory};</li>
 *   <li>{@code soapPortHandlerMapping}: the highest-precedence {@link HandlerMapping} that hands every request
 *       accepted on the port of listener 2 … 7 to the dispatcher and returns no handler on any other port;</li>
 *   <li>{@code creditAgencyWsdl} and {@code bankWsdl}: the fixed contracts {@value #CREDIT_AGENCY_WSDL} and
 *       {@value #BANK_WSDL} as {@link SimpleWsdl11Definition}s, served by {@code config.WsdlQueryFilter} and never
 *       generated at runtime (D-028).</li>
 * </ul>
 *
 * <p>Port and namespace acceptance (D-534):
 * <ul>
 *   <li>the port of listener 2 accepts a payload root in {@value #CREDIT_AGENCY_NAMESPACE} only;</li>
 *   <li>the ports of listeners 3 … 7 accept a payload root in {@value #BANK_NAMESPACE} only;</li>
 *   <li>a payload root outside the port's namespace, an absent payload root, and a root in the port's namespace
 *       whose local name no endpoint maps raise {@link IllegalArgumentException} with the message
 *       {@code Unexpected wrapper element <root> found.}, {@code <root>} in {@link QName#toString()} form
 *       ({@code {ns}local}, or {@code null} for an absent root). {@code soapFaultResolver} answers it with HTTP
 *       500 and a {@code soap:Server} fault whose {@code faultstring} is that message;</li>
 *   <li>the port of listener 1 ({@code server.port}, 11081 by default) never reaches the dispatcher.</li>
 * </ul>
 *
 * <p>The class declares no {@code @EnableWs}, extends no Spring WS configuration support class and registers no
 * {@code MessageDispatcherServlet} (D-534); the application class excludes {@code WebServicesAutoConfiguration}
 * (D-279).
 * Requests reach the dispatcher through Spring MVC's {@code DispatcherServlet}, after
 * {@code config.PortPathGuardFilter} and {@code config.WsdlQueryFilter} (D-011, D-028).
 */
@Configuration(proxyBeanMethods = false)
public class WsConfig {

    /** Target namespace of {@code CreditAgencyService.wsdl} and of the {@code getCreditProfile} wrappers. */
    public static final String CREDIT_AGENCY_NAMESPACE = "http://creditagency.loanbroker.example.mule.org/";

    /** Target namespace of {@code BankService.wsdl} and of the {@code getLoanQuote} wrappers. */
    public static final String BANK_NAMESPACE = "http://bank.loanbroker.example.mule.org/";

    /** Java package that XJC generates from {@value #CREDIT_AGENCY_WSDL}. */
    private static final String CREDIT_AGENCY_CONTEXT_PATH = "org.mule.example.loanbroker.creditagency";

    /** Java package that XJC generates from {@value #BANK_WSDL}. */
    private static final String BANK_CONTEXT_PATH = "org.mule.example.loanbroker.bank";

    /** Classpath location of the credit agency contract. */
    private static final String CREDIT_AGENCY_WSDL = "wsdl/CreditAgencyService.wsdl";

    /** Classpath location of the contract shared by the five banks. */
    private static final String BANK_WSDL = "wsdl/BankService.wsdl";

    /** Opening text of the message of every rejected payload root. */
    private static final String UNEXPECTED_WRAPPER_PREFIX = "Unexpected wrapper element ";

    /** Closing text of the message of every rejected payload root. */
    private static final String UNEXPECTED_WRAPPER_SUFFIX = " found.";

    private static final Logger LOGGER = LoggerFactory.getLogger(WsConfig.class);

    /**
     * Returns the SOAP 1.1 SAAJ message factory of the SOAP endpoints. Spring initialises it through
     * {@link SaajSoapMessageFactory#afterPropertiesSet()}.
     *
     * @return a SOAP 1.1 message factory
     */
    @Bean
    public SaajSoapMessageFactory messageFactory() {
        SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
        factory.setSoapVersion(SoapVersion.SOAP_11);
        return factory;
    }

    /**
     * Returns one JAXB marshaller over the credit agency and bank types that XJC generates from the two WSDLs.
     * The two packages declare their {@code customer} and {@code creditProfile} types in different namespaces,
     * and one context holds both. Spring creates the JAXB context through
     * {@link Jaxb2Marshaller#afterPropertiesSet()} while the application context starts.
     *
     * @return a marshaller with the context paths {@value #CREDIT_AGENCY_CONTEXT_PATH} and
     *         {@value #BANK_CONTEXT_PATH}
     */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPaths(CREDIT_AGENCY_CONTEXT_PATH, BANK_CONTEXT_PATH);
        return marshaller;
    }

    /**
     * Returns the payload-root mapping of the SOAP endpoints. As a bean of the application context it maps the
     * {@code @PayloadRoot} methods of every {@code @Endpoint} bean of that context. Every mapped request, and every
     * request answered by the default endpoint, first passes {@link PortNamespaceInterceptor}. A request whose
     * payload root no method maps reaches {@link UnknownPayloadRootEndpoint#reject(MessageContext)}, never Spring
     * WS's {@code NoEndpointFoundException}.
     *
     * @param listeners the seven listener addresses of {@code application.yml}
     * @return the endpoint mapping with the port check as its only interceptor and the rejecting default endpoint
     */
    @Bean
    public PayloadRootAnnotationMethodEndpointMapping endpointMapping(ListenerProperties listeners) {
        TransformerFactory transformerFactory = TransformerFactoryUtils.newInstance();
        PayloadRootAnnotationMethodEndpointMapping mapping = new PayloadRootAnnotationMethodEndpointMapping();
        mapping.setInterceptors(new EndpointInterceptor[] {
                new PortNamespaceInterceptor(listeners, transformerFactory)});
        mapping.setDefaultEndpoint(UnknownPayloadRootEndpoint.methodEndpoint(transformerFactory));
        return mapping;
    }

    /**
     * Returns the method endpoint adapter of the SOAP endpoints with its default argument resolvers and return value
     * handlers, which Spring initialises through {@link DefaultMethodEndpointAdapter#afterPropertiesSet()}. Its JAXB
     * processors bind the {@code JAXBElement<GetCreditProfile>} and {@code JAXBElement<GetLoanQuote>} payloads, and
     * its {@link MessageContext} resolver serves {@link UnknownPayloadRootEndpoint#reject(MessageContext)}.
     *
     * @return an adapter with the default strategies
     */
    @Bean
    public DefaultMethodEndpointAdapter endpointAdapter() {
        return new DefaultMethodEndpointAdapter();
    }

    /**
     * Returns the only instance of the project's endpoint exception resolver, which answers every exception with a
     * {@code soap:Server} fault whose {@code faultstring} is the exception message (D-223).
     *
     * @return a new resolver
     */
    @Bean
    public SoapFaultMappingExceptionResolver soapFaultResolver() {
        return new SoapFaultMappingExceptionResolver();
    }

    /**
     * Returns the only SOAP message dispatcher of the context. It holds exactly one endpoint mapping, one endpoint
     * adapter and one exception resolver; with the three lists set it loads none of Spring WS's default strategies.
     *
     * @param endpointMapping   the payload-root mapping of {@link #endpointMapping(ListenerProperties)}
     * @param endpointAdapter   the adapter of {@link #endpointAdapter()}
     * @param soapFaultResolver the resolver of {@link #soapFaultResolver()}
     * @return the dispatcher
     */
    @Bean
    public SoapMessageDispatcher messageDispatcher(PayloadRootAnnotationMethodEndpointMapping endpointMapping,
            DefaultMethodEndpointAdapter endpointAdapter, SoapFaultMappingExceptionResolver soapFaultResolver) {
        SoapMessageDispatcher dispatcher = new SoapMessageDispatcher();
        dispatcher.setEndpointMappings(List.of(endpointMapping));
        dispatcher.setEndpointAdapters(List.of(endpointAdapter));
        dispatcher.setEndpointExceptionResolvers(List.of(soapFaultResolver));
        return dispatcher;
    }

    /**
     * Returns the Spring MVC handler adapter that runs a {@link SoapMessageDispatcher} handler: a {@code POST} is
     * read and answered through {@code messageFactory}, a response holding a fault gets HTTP 500, and any other
     * method gets HTTP 405. The {@code DispatcherServlet} detects it beside Boot's own handler adapters.
     *
     * @param messageFactory the SOAP 1.1 factory of {@link #messageFactory()}
     * @return the handler adapter
     */
    @Bean
    public WebServiceMessageReceiverHandlerAdapter wsHandlerAdapter(SaajSoapMessageFactory messageFactory) {
        WebServiceMessageReceiverHandlerAdapter adapter = new WebServiceMessageReceiverHandlerAdapter();
        adapter.setMessageFactory(messageFactory);
        return adapter;
    }

    /**
     * Returns the highest-precedence Spring MVC handler mapping of the SOAP ports: a request accepted on the port
     * of listener 2 … 7 is handled by {@code messageDispatcher}, and a request on any other port is left to the
     * next mapping. The mapping reads no path and no query.
     *
     * @param listeners         the seven listener addresses of {@code application.yml}
     * @param messageDispatcher the dispatcher of
     *                          {@link #messageDispatcher(PayloadRootAnnotationMethodEndpointMapping,
     *                          DefaultMethodEndpointAdapter, SoapFaultMappingExceptionResolver)}
     * @return a {@link SoapPortHandlerMapping}
     */
    @Bean
    public HandlerMapping soapPortHandlerMapping(ListenerProperties listeners,
            SoapMessageDispatcher messageDispatcher) {
        return new SoapPortHandlerMapping(listeners, messageDispatcher);
    }

    /**
     * Returns the fixed credit agency contract {@value #CREDIT_AGENCY_WSDL} (D-028).
     *
     * @return the WSDL definition over the classpath resource
     */
    @Bean
    public SimpleWsdl11Definition creditAgencyWsdl() {
        return new SimpleWsdl11Definition(new ClassPathResource(CREDIT_AGENCY_WSDL));
    }

    /**
     * Returns the fixed bank contract {@value #BANK_WSDL}, shared by the five banks (D-028).
     *
     * @return the WSDL definition over the classpath resource
     */
    @Bean
    public SimpleWsdl11Definition bankWsdl() {
        return new SimpleWsdl11Definition(new ClassPathResource(BANK_WSDL));
    }

    /**
     * Returns the qualified name of the request's payload root element, the first child element of the SOAP
     * {@code Body}.
     *
     * @param messageContext     the message context of the SOAP request
     * @param transformerFactory factory used when the payload source is not a DOM source
     * @return the payload root name, or {@code null} when the {@code Body} holds no element
     * @throws TransformerException when the payload source cannot be read
     */
    private static QName payloadRoot(MessageContext messageContext, TransformerFactory transformerFactory)
            throws TransformerException {
        return PayloadRootUtils.getPayloadRootQName(messageContext.getRequest().getPayloadSource(),
                transformerFactory);
    }

    /**
     * Returns the exception raised for a rejected payload root.
     *
     * @param root the payload root name, or {@code null} when the {@code Body} holds no element
     * @return an exception with the message {@code Unexpected wrapper element <root> found.}, {@code <root>} in
     *         {@link QName#toString()} form or {@code null}
     */
    private static IllegalArgumentException unexpectedWrapper(QName root) {
        return new IllegalArgumentException(UNEXPECTED_WRAPPER_PREFIX + root + UNEXPECTED_WRAPPER_SUFFIX);
    }

    /**
     * Spring MVC handler mapping of the SOAP ports (D-011, D-028, D-534). A request whose local port is the port of
     * listener 2 … 7 ({@link ListenerProperties#additionalOnPort(int)} present) gets a handler chain holding only
     * the SOAP message dispatcher, with no handler interceptor. A request on any other port, listener 1 included,
     * gets {@code null}, and the {@code DispatcherServlet} consults the next mapping. Its order is
     * {@link Ordered#HIGHEST_PRECEDENCE}, ahead of {@code RequestMappingHandlerMapping}.
     *
     * <p>The mapping holds no mutable state and is safe for concurrent requests.
     */
    static final class SoapPortHandlerMapping implements HandlerMapping, Ordered {

        private final ListenerProperties listeners;

        private final SoapMessageDispatcher messageDispatcher;

        /**
         * Creates the mapping.
         *
         * @param listeners         the seven listener addresses
         * @param messageDispatcher the dispatcher that handles every request on the ports of listeners 2 … 7
         */
        SoapPortHandlerMapping(ListenerProperties listeners, SoapMessageDispatcher messageDispatcher) {
            this.listeners = listeners;
            this.messageDispatcher = messageDispatcher;
        }

        /**
         * Returns the dispatcher chain for a request accepted on the port of listener 2 … 7.
         *
         * @param request the current request
         * @return a chain whose handler is the dispatcher, or {@code null} for a request on any other port
         * @throws IllegalStateException when an entry among listeners 2 … 7 is not bound
         */
        @Override
        public HandlerExecutionChain getHandler(HttpServletRequest request) {
            if (listeners.additionalOnPort(request.getLocalPort()).isPresent()) {
                return new HandlerExecutionChain(messageDispatcher);
            }
            return null;
        }

        /**
         * Returns {@link Ordered#HIGHEST_PRECEDENCE}.
         *
         * @return the order of this mapping among the {@code DispatcherServlet}'s handler mappings
         */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    /**
     * Endpoint interceptor that checks the payload root namespace against the local port of the request (D-028,
     * D-534).
     *
     * <p>The namespace accepted on a port is {@value WsConfig#CREDIT_AGENCY_NAMESPACE} when the listener bound on
     * that port is {@link ListenerProperties#creditAgency()} (listener 2), and {@value WsConfig#BANK_NAMESPACE} when
     * it is one of {@link ListenerProperties#banks()} (listeners 3 … 7). A payload root in the accepted namespace
     * continues to the endpoint. Any other payload root, an absent one included, and any request on a port no
     * listener 2 … 7 owns raise {@link IllegalArgumentException} with the message
     * {@code Unexpected wrapper element <root> found.}; the message dispatcher hands it to the exception resolver,
     * which answers HTTP 500 with a {@code soap:Server} fault. A request that carries no HTTP servlet connection
     * raises {@link IllegalStateException}.
     *
     * <p>The interceptor holds no mutable state; the shared transformer factory is used only for a payload source
     * that is not a DOM source, which SAAJ messages never produce.
     */
    static final class PortNamespaceInterceptor implements EndpointInterceptor {

        private final ListenerProperties listeners;

        private final TransformerFactory transformerFactory;

        /**
         * Creates the interceptor.
         *
         * @param listeners          the seven listener addresses
         * @param transformerFactory factory used to read a payload source that is not a DOM source
         */
        PortNamespaceInterceptor(ListenerProperties listeners, TransformerFactory transformerFactory) {
            this.listeners = listeners;
            this.transformerFactory = transformerFactory;
        }

        /**
         * Lets a request continue when its payload root is in the namespace of the listener on its local port.
         *
         * @param messageContext the message context of the SOAP request
         * @param endpoint       the mapped endpoint or the rejecting default endpoint; not read
         * @return {@code true} for a payload root in the accepted namespace
         * @throws IllegalArgumentException with the message {@code Unexpected wrapper element <root> found.} for any
         *                                  other payload root, an absent one included
         * @throws IllegalStateException    when the request carries no HTTP servlet connection, or an entry among
         *                                  listeners 2 … 7 is not bound
         * @throws TransformerException     when the payload source cannot be read
         */
        @Override
        public boolean handleRequest(MessageContext messageContext, Object endpoint) throws TransformerException {
            int localPort = localPort();
            QName root = payloadRoot(messageContext, transformerFactory);
            Optional<String> accepted = acceptedNamespace(localPort);
            if (root != null && accepted.isPresent() && accepted.get().equals(root.getNamespaceURI())) {
                return true;
            }
            LOGGER.debug("Rejected the SOAP payload root of a request on local port {}", localPort);
            throw unexpectedWrapper(root);
        }

        /**
         * Returns {@code true}: a response continues unchanged.
         *
         * @param messageContext the message context holding the response
         * @param endpoint       the endpoint that produced the response; not read
         * @return {@code true}
         */
        @Override
        public boolean handleResponse(MessageContext messageContext, Object endpoint) {
            return true;
        }

        /**
         * Returns {@code true}: a fault response continues unchanged.
         *
         * @param messageContext the message context holding the fault response
         * @param endpoint       the endpoint whose exception produced the fault; not read
         * @return {@code true}
         */
        @Override
        public boolean handleFault(MessageContext messageContext, Object endpoint) {
            return true;
        }

        /**
         * Does nothing: the interceptor holds no per-request state.
         *
         * @param messageContext the message context of the completed request; not read
         * @param endpoint       the endpoint of the completed request; not read
         * @param ex             the exception raised by the endpoint, or {@code null}; not read
         */
        @Override
        public void afterCompletion(MessageContext messageContext, Object endpoint, Exception ex) {
            // Completion releases nothing: the interceptor keeps no per-request state.
        }

        /**
         * Returns the namespace accepted on {@code localPort}.
         *
         * @param localPort local port that accepted the request
         * @return {@value WsConfig#CREDIT_AGENCY_NAMESPACE} for the port of listener 2,
         *         {@value WsConfig#BANK_NAMESPACE} for the port of listener 3 … 7, and empty for any other port
         */
        private Optional<String> acceptedNamespace(int localPort) {
            Optional<ListenerProperties.Listener> owner = listeners.additionalOnPort(localPort);
            if (owner.isEmpty()) {
                return Optional.empty();
            }
            if (owner.get().equals(listeners.creditAgency())) {
                return Optional.of(CREDIT_AGENCY_NAMESPACE);
            }
            if (listeners.banks().contains(owner.get())) {
                return Optional.of(BANK_NAMESPACE);
            }
            return Optional.empty();
        }

        /**
         * Returns the local port of the HTTP request that carries the SOAP message.
         *
         * @return the port that accepted the request
         * @throws IllegalStateException when the current transport context holds no HTTP servlet connection
         */
        private static int localPort() {
            TransportContext transportContext = TransportContextHolder.getTransportContext();
            WebServiceConnection connection = (transportContext != null) ? transportContext.getConnection() : null;
            if (!(connection instanceof HttpServletConnection httpConnection)) {
                throw new IllegalStateException("The SOAP request carries no HTTP servlet connection");
            }
            return httpConnection.getHttpServletRequest().getLocalPort();
        }
    }

    /**
     * Default endpoint of {@code endpointMapping}, reached by a payload root that passed
     * {@link PortNamespaceInterceptor} but that no {@code @PayloadRoot} method maps: a root in the port's namespace
     * with an unknown local name, such as {@code {http://bank.loanbroker.example.mule.org/}unknown} on a bank port.
     */
    private static final class UnknownPayloadRootEndpoint {

        /** Name of the endpoint method. */
        private static final String REJECT_METHOD = "reject";

        private final TransformerFactory transformerFactory;

        /**
         * Creates the endpoint.
         *
         * @param transformerFactory factory used to read a payload source that is not a DOM source
         */
        private UnknownPayloadRootEndpoint(TransformerFactory transformerFactory) {
            this.transformerFactory = transformerFactory;
        }

        /**
         * Returns a method endpoint over {@link #reject(MessageContext)} of a new instance, which
         * {@link DefaultMethodEndpointAdapter} invokes with the {@link MessageContext} of the request.
         *
         * @param transformerFactory factory used to read a payload source that is not a DOM source
         * @return the method endpoint
         */
        static MethodEndpoint methodEndpoint(TransformerFactory transformerFactory) {
            Method reject = ReflectionUtils.findMethod(UnknownPayloadRootEndpoint.class, REJECT_METHOD,
                    MessageContext.class);
            return new MethodEndpoint(new UnknownPayloadRootEndpoint(transformerFactory), reject);
        }

        /**
         * Rejects the request.
         *
         * @param messageContext the message context of the SOAP request
         * @throws IllegalArgumentException always, with the message {@code Unexpected wrapper element <root> found.},
         *                                  {@code <root>} computed as in {@link PortNamespaceInterceptor}
         * @throws TransformerException     when the payload source cannot be read
         */
        public void reject(MessageContext messageContext) throws TransformerException {
            throw unexpectedWrapper(payloadRoot(messageContext, transformerFactory));
        }
    }
}

