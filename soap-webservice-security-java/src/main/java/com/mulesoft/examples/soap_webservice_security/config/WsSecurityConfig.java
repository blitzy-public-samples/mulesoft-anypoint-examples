package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.xml.namespace.QName;
import javax.xml.transform.TransformerException;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.crypto.CryptoFactory;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.handler.RequestData;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.NoEndpointFoundException;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.EndpointInvocationChain;
import org.springframework.ws.server.EndpointMapping;
import org.springframework.ws.server.endpoint.interceptor.EndpointInterceptorAdapter;
import org.springframework.ws.server.endpoint.mapping.PayloadRootAnnotationMethodEndpointMapping;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.WsSecurityValidationException;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.soap.server.endpoint.interceptor.DelegatingSmartSoapEndpointInterceptor;
import org.springframework.ws.soap.soap11.Soap11Body;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.xml.transform.TransformerHelper;

/**
 * Spring Web Services server configuration of the six {@code Greeter} SOAP services and their WS-Security
 * policies, the counterpart of the {@code cxf:jaxws-service} and {@code cxf:ws-security} elements of the six
 * service flows and of the listener {@code HTTP_Listener_Configuration1} (D-030).
 *
 * <p>Registers:
 * <ul>
 *   <li>the {@link MessageDispatcherServlet} on {@code /<base-path>/*}, {@code /services/*} for the default
 *       {@code listener.http-listener-configuration1.base-path}; the Spring MVC {@code DispatcherServlet} keeps
 *       {@code /};</li>
 *   <li>the SOAP 1.1 message factory {@code messageFactory} and the {@link Jaxb2Marshaller} over the generated
 *       {@code com.mulesoft.mule.example.security} types;</li>
 *   <li>the fixed WSDL contract {@code wsdl/Greeter.wsdl} as bean {@code greeterWsdlDefinition} (D-028);</li>
 *   <li>the WSS4J {@link Crypto} {@code wsCrypto} over {@code wssecurity.properties} and {@code keystore.password},
 *       the password callbacks and the SAML 2 validator {@code samlCustomValidator} (D-012);</li>
 *   <li>one URI-matched {@link Wss4jSecurityInterceptor} per secured service path, with the validation actions of
 *       the original flow; {@code unsecure} has none:
 *       <table>
 *         <caption>Validation per service path</caption>
 *         <tr><th>Path</th><th>Actions</th><th>Callback, crypto, validator</th></tr>
 *         <tr><td>{@code username}</td><td>{@code UsernameToken Timestamp}</td>
 *             <td>{@code passwordCallback}</td></tr>
 *         <tr><td>{@code signed}</td><td>{@code UsernameToken Signature Timestamp}</td>
 *             <td>{@code passwordCallback}, signature crypto {@code wsCrypto}</td></tr>
 *         <tr><td>{@code encrypted}</td><td>{@code UsernameToken Timestamp Encrypt}</td>
 *             <td>{@code passwordCallback}, decryption crypto {@code wsCrypto}</td></tr>
 *         <tr><td>{@code saml}</td><td>{@code SAMLTokenUnsigned Timestamp}</td>
 *             <td>SAML 2 validator {@code samlCustomValidator}; subject confirmation not validated (D-558)</td></tr>
 *         <tr><td>{@code signedsaml}</td><td>{@code SAMLTokenUnsigned Signature}</td>
 *             <td>signature crypto {@code wsCrypto}, SAML 2 validator {@code samlCustomValidator}</td></tr>
 *       </table>
 *       {@code passwordCallback} receives only the WSS4J password callbacks (D-557);</li>
 *   <li>the endpoint mapping of encrypted {@code greet} payloads on {@code encrypted} (D-559).</li>
 * </ul>
 *
 * <p>{@link EnableWs} imports the Spring WS annotation infrastructure; its endpoint mappings pick up the five
 * {@link DelegatingSmartSoapEndpointInterceptor} beans, and each applies its interceptor only to requests whose
 * last URI path segment is its service path. A failed validation is answered with a SOAP 1.1 {@code wsse} fault
 * (D-030, D-560).
 */
@Configuration
@EnableWs
public class WsSecurityConfig implements WsConfigurer {

    /** Java package of the JAXB types generated from {@value #WSDL_LOCATION}. */
    private static final String JAXB_CONTEXT_PATH = "com.mulesoft.mule.example.security";

    /** Classpath location of the fixed WSDL contract (D-028). */
    private static final String WSDL_LOCATION = "wsdl/Greeter.wsdl";

    /** Classpath location of the WSS4J crypto properties copied from the original. */
    private static final String CRYPTO_PROPERTIES_LOCATION = "wssecurity.properties";

    /** Merlin property that receives the value of {@code keystore.password} (D-012). */
    private static final String KEYSTORE_PASSWORD_PROPERTY = "org.apache.ws.security.crypto.merlin.keystore.password";

    /** Name of the dispatcher servlet registration. */
    private static final String DISPATCHER_SERVLET_NAME = "messageDispatcherServlet";

    // The interceptors match these literal listener paths; the D-139 per-flow path keys are not read (D-561).

    /** Service path of {@code UsernameTokenServiceFlow}. */
    private static final String USERNAME_PATH = "username";

    /** Service path of {@code UsernameTokenSignedServiceFlow}. */
    private static final String SIGNED_PATH = "signed";

    /** Service path of {@code UsernameTokenEncryptedServiceFlow}. */
    private static final String ENCRYPTED_PATH = "encrypted";

    /** Service path of {@code SamlTokenServiceFlow}. */
    private static final String SAML_PATH = "saml";

    /** Service path of {@code SignedSamlTokenServiceFlow}. */
    private static final String SIGNED_SAML_PATH = "signedsaml";

    /** Request element of the {@code greet} operation. */
    private static final QName GREET_REQUEST = new QName("http://security.example.mule.mulesoft.com/", "greet");

    /** Validation actions of {@code UsernameTokenServiceFlow}. */
    private static final String USERNAME_ACTIONS = "UsernameToken Timestamp";

    /** Validation actions of {@code UsernameTokenSignedServiceFlow}. */
    private static final String SIGNED_ACTIONS = "UsernameToken Signature Timestamp";

    /** Validation actions of {@code UsernameTokenEncryptedServiceFlow}. */
    private static final String ENCRYPTED_ACTIONS = "UsernameToken Timestamp Encrypt";

    /** Validation actions of {@code SamlTokenServiceFlow}. */
    private static final String SAML_ACTIONS = "SAMLTokenUnsigned Timestamp";

    /** Validation actions of {@code SignedSamlTokenServiceFlow}. */
    private static final String SIGNED_SAML_ACTIONS = "SAMLTokenUnsigned Signature";

    /**
     * Registers the Spring WS {@link MessageDispatcherServlet}, loaded on startup, on {@code /<basePath>/*}. WSDL
     * locations are not transformed.
     *
     * @param applicationContext the application context that holds the endpoint, mappings and interceptors
     * @param basePath           the value of {@code listener.http-listener-configuration1.base-path}, by default
     *                           {@code services}
     * @return the servlet registration named {@code messageDispatcherServlet}
     */
    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(
            ApplicationContext applicationContext,
            @Value("${listener.http-listener-configuration1.base-path}") String basePath) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(applicationContext);
        ServletRegistrationBean<MessageDispatcherServlet> registration =
                new ServletRegistrationBean<>(servlet, "/" + basePath + "/*");
        registration.setName(DISPATCHER_SERVLET_NAME);
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * SAAJ SOAP 1.1 message factory, looked up by the {@link MessageDispatcherServlet} under the name
     * {@code messageFactory}.
     *
     * @return the SOAP 1.1 message factory
     */
    @Bean(name = "messageFactory")
    public SaajSoapMessageFactory messageFactory() {
        return new SaajSoapMessageFactory();
    }

    /**
     * JAXB marshaller over the types generated from {@value #WSDL_LOCATION}, shared by the endpoint and the
     * client templates.
     *
     * @return the marshaller with context path {@value #JAXB_CONTEXT_PATH}
     */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath(JAXB_CONTEXT_PATH);
        return marshaller;
    }

    /**
     * The fixed {@code Greeter} WSDL contract {@value #WSDL_LOCATION} (D-028).
     *
     * @return the WSDL definition named {@code greeterWsdlDefinition}
     */
    @Bean
    public SimpleWsdl11Definition greeterWsdlDefinition() {
        return new SimpleWsdl11Definition(new ClassPathResource(WSDL_LOCATION));
    }

    /**
     * WSS4J Merlin crypto over the keystore named in {@value #CRYPTO_PROPERTIES_LOCATION}, with the keystore
     * password taken from {@code keystore.password} (D-012). The properties file is read from the classpath and the
     * password is added in memory only.
     *
     * @param keystorePassword the value of {@code keystore.password}
     * @return the crypto used for signature verification and decryption
     * @throws IllegalStateException when the properties file cannot be read or the keystore cannot be loaded, for
     *                               example with the committed placeholder password (D-012)
     */
    @Bean
    public Crypto wsCrypto(@Value("${keystore.password}") String keystorePassword) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(CRYPTO_PROPERTIES_LOCATION).getInputStream()) {
            properties.load(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + CRYPTO_PROPERTIES_LOCATION + " from the classpath", ex);
        }
        properties.setProperty(KEYSTORE_PASSWORD_PROPERTY, keystorePassword);
        try {
            return CryptoFactory.getInstance(properties);
        } catch (WSSecurityException ex) {
            throw new IllegalStateException(
                    "Cannot load the WS-Security crypto of " + CRYPTO_PROPERTIES_LOCATION, ex);
        }
    }

    /**
     * Password callback of the users {@code joe} and {@code stan} (D-012).
     *
     * @param joePassword  the value of {@code wssecurity.users.joe}
     * @param stanPassword the value of {@code wssecurity.users.stan}
     * @return the password callback
     */
    @Bean
    public PasswordCallback passwordCallback(@Value("${wssecurity.users.joe}") String joePassword,
                                             @Value("${wssecurity.users.stan}") String stanPassword) {
        return new PasswordCallback(joePassword, stanPassword);
    }

    /**
     * Password callback that returns {@code wssecurity.wrong-password} for {@code joe} (D-012).
     *
     * @param wrongPassword the value of {@code wssecurity.wrong-password}
     * @return the wrong-password callback
     */
    @Bean
    public WrongPasswordCallback wrongPasswordCallback(@Value("${wssecurity.wrong-password}") String wrongPassword) {
        return new WrongPasswordCallback(wrongPassword);
    }

    /**
     * SAML 2 assertion validator of the {@code saml} and {@code signedsaml} services, the Spring bean
     * {@code samlCustomValidator} of the original.
     *
     * @return the SAML validator
     */
    @Bean
    public SAMLCustomValidator samlCustomValidator() {
        return new SAMLCustomValidator();
    }

    /**
     * WS-Security of {@code UsernameTokenServiceFlow}: requests to {@code username} must carry a UsernameToken
     * accepted by {@code passwordCallback} and a Timestamp.
     *
     * @param passwordCallback the password callback of {@code joe} and {@code stan}
     * @return the interceptor applied to {@code username} requests only
     */
    @Bean
    public DelegatingSmartSoapEndpointInterceptor usernameSecurityInterceptor(PasswordCallback passwordCallback) {
        ServerSecurityInterceptor interceptor = new ServerSecurityInterceptor(true);
        interceptor.setValidationActions(USERNAME_ACTIONS);
        interceptor.setValidationCallbackHandler(new PasswordCallbackOnly(passwordCallback));
        return pathMatching(USERNAME_PATH, interceptor);
    }

    /**
     * WS-Security of {@code UsernameTokenSignedServiceFlow}: requests to {@code signed} must carry a UsernameToken
     * accepted by {@code passwordCallback}, a signature verified with {@code wsCrypto} and a Timestamp.
     *
     * @param passwordCallback the password callback of {@code joe} and {@code stan}
     * @param wsCrypto         the crypto that verifies the signature
     * @return the interceptor applied to {@code signed} requests only
     */
    @Bean
    public DelegatingSmartSoapEndpointInterceptor signedSecurityInterceptor(PasswordCallback passwordCallback,
                                                                            Crypto wsCrypto) {
        ServerSecurityInterceptor interceptor = new ServerSecurityInterceptor(true);
        interceptor.setValidationActions(SIGNED_ACTIONS);
        interceptor.setValidationCallbackHandler(new PasswordCallbackOnly(passwordCallback));
        interceptor.setValidationSignatureCrypto(wsCrypto);
        return pathMatching(SIGNED_PATH, interceptor);
    }

    /**
     * WS-Security of {@code UsernameTokenEncryptedServiceFlow}: requests to {@code encrypted} must carry a
     * UsernameToken accepted by {@code passwordCallback}, a Timestamp and content encrypted for the key of
     * {@code wsCrypto}.
     *
     * @param passwordCallback the password callback of {@code joe} and {@code stan}, also asked for the private
     *                         key password
     * @param wsCrypto         the crypto that decrypts the request
     * @return the interceptor applied to {@code encrypted} requests only
     */
    @Bean
    public DelegatingSmartSoapEndpointInterceptor encryptedSecurityInterceptor(PasswordCallback passwordCallback,
                                                                               Crypto wsCrypto) {
        ServerSecurityInterceptor interceptor = new ServerSecurityInterceptor(true);
        interceptor.setValidationActions(ENCRYPTED_ACTIONS);
        interceptor.setValidationCallbackHandler(new PasswordCallbackOnly(passwordCallback));
        interceptor.setValidationDecryptionCrypto(wsCrypto);
        return pathMatching(ENCRYPTED_PATH, interceptor);
    }

    /**
     * WS-Security of {@code SamlTokenServiceFlow}: requests to {@code saml} must carry an unsigned SAML 2 assertion
     * accepted by {@code samlCustomValidator} and a Timestamp. WSS4J does not validate the subject confirmation of
     * the assertion, sender-vouches without a message signature (D-558).
     *
     * @param samlCustomValidator the SAML 2 assertion validator
     * @return the interceptor applied to {@code saml} requests only
     */
    @Bean
    public DelegatingSmartSoapEndpointInterceptor samlSecurityInterceptor(SAMLCustomValidator samlCustomValidator) {
        ServerSecurityInterceptor interceptor = new ServerSecurityInterceptor(false);
        interceptor.setValidationActions(SAML_ACTIONS);
        interceptor.setWssConfig(samlValidatingConfig(samlCustomValidator));
        return pathMatching(SAML_PATH, interceptor);
    }

    /**
     * WS-Security of {@code SignedSamlTokenServiceFlow}: requests to {@code signedsaml} must carry a SAML 2
     * assertion accepted by {@code samlCustomValidator} and a signature verified with {@code wsCrypto}.
     *
     * @param samlCustomValidator the SAML 2 assertion validator
     * @param wsCrypto            the crypto that verifies the signature
     * @return the interceptor applied to {@code signedsaml} requests only
     */
    @Bean
    public DelegatingSmartSoapEndpointInterceptor signedSamlSecurityInterceptor(
            SAMLCustomValidator samlCustomValidator, Crypto wsCrypto) {
        ServerSecurityInterceptor interceptor = new ServerSecurityInterceptor(true);
        interceptor.setValidationActions(SIGNED_SAML_ACTIONS);
        interceptor.setValidationSignatureCrypto(wsCrypto);
        interceptor.setWssConfig(samlValidatingConfig(samlCustomValidator));
        return pathMatching(SIGNED_SAML_PATH, interceptor);
    }

    /**
     * Endpoint mapping, after the {@link EnableWs} mappings, of {@code encrypted} requests whose payload is an
     * {@code xenc:EncryptedData} element: maps them to the endpoint method of {@code greet}, the only operation of
     * {@value #WSDL_LOCATION}, with the URI-matched interceptors followed by a check that the decrypted payload is a
     * {@code greet} element (D-559).
     *
     * @return the endpoint mapping of encrypted {@code greet} requests
     */
    @Bean
    public EndpointMapping encryptedPayloadEndpointMapping() {
        EncryptedPayloadEndpointMapping mapping = new EncryptedPayloadEndpointMapping();
        mapping.setOrder(Ordered.LOWEST_PRECEDENCE);
        return mapping;
    }

    /**
     * Builds a WSS4J configuration whose validator of SAML 2 assertions is {@code samlCustomValidator}.
     *
     * @param samlCustomValidator the SAML 2 assertion validator
     * @return a new WSS4J configuration with the default validators and {@code samlCustomValidator}
     */
    private static WSSConfig samlValidatingConfig(SAMLCustomValidator samlCustomValidator) {
        WSSConfig wssConfig = WSSConfig.getNewInstance();
        wssConfig.setValidator(WSConstants.SAML2_TOKEN, samlCustomValidator);
        return wssConfig;
    }

    /**
     * Initialises {@code interceptor} and wraps it into a {@link PathMatchingInterceptor} for {@code servicePath}.
     *
     * @param servicePath the last URI path segment of the requests the interceptor applies to
     * @param interceptor the configured server interceptor
     * @return the URI-matched interceptor
     * @throws IllegalStateException when the interceptor configuration is incomplete
     */
    private static DelegatingSmartSoapEndpointInterceptor pathMatching(String servicePath,
                                                                       ServerSecurityInterceptor interceptor) {
        interceptor.setSecureResponse(false);
        try {
            interceptor.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid WS-Security configuration for service path " + servicePath, ex);
        }
        return new PathMatchingInterceptor(servicePath, interceptor);
    }

    /**
     * Returns the last URI path segment of the current request, or {@code null} when there is no transport
     * connection or its URI cannot be parsed.
     *
     * @return the service path of the current request, or {@code null}
     */
    private static String currentServicePath() {
        TransportContext transportContext = TransportContextHolder.getTransportContext();
        if (transportContext == null) {
            return null;
        }
        WebServiceConnection connection = transportContext.getConnection();
        if (connection == null) {
            return null;
        }
        try {
            URI uri = connection.getUri();
            return lastPathSegment(uri.getPath());
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /**
     * Returns the last segment of a URI path: the text after the last {@code /}, with one trailing {@code /}
     * removed first; the empty string for a {@code null} path.
     *
     * @param path the URI path, possibly {@code null}
     * @return the last path segment
     */
    private static String lastPathSegment(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /**
     * Returns the qualified name of the root element of the request payload.
     *
     * @param messageContext the message context of the request
     * @return the payload root name, or {@code null} for an empty payload
     * @throws TransformerException when the payload cannot be read
     */
    private static QName payloadRoot(MessageContext messageContext) throws TransformerException {
        return PayloadRootUtils.getPayloadRootQName(messageContext.getRequest().getPayloadSource(),
                new TransformerHelper());
    }

    /**
     * Applies its delegate only to SOAP requests whose last URI path segment equals its service path; requests
     * without a transport connection, or with an unparsable URI, are not intercepted. {@code understands} is
     * answered by the delegate, which accepts the {@code wsse:Security} header (D-030).
     */
    private static final class PathMatchingInterceptor extends DelegatingSmartSoapEndpointInterceptor {

        /** Last URI path segment of the requests this interceptor applies to. */
        private final String servicePath;

        /**
         * Creates the interceptor.
         *
         * @param servicePath the last URI path segment of the requests to intercept
         * @param delegate    the interceptor applied to those requests
         */
        private PathMatchingInterceptor(String servicePath, EndpointInterceptor delegate) {
            super(delegate);
            this.servicePath = servicePath;
        }

        /**
         * Returns whether the current request's last URI path segment equals the service path.
         *
         * @param messageContext the message context of the request
         * @param endpoint       the mapped endpoint
         * @return {@code true} only for requests to the service path
         */
        @Override
        public boolean shouldIntercept(MessageContext messageContext, Object endpoint) {
            return servicePath.equals(currentServicePath());
        }
    }

    /**
     * Payload-root endpoint mapping that resolves only {@code encrypted} requests whose payload root is
     * {@code xenc:EncryptedData}, under the key {@code greet}; its invocation chains end with a
     * {@link DecryptedGreetCheck} (D-559).
     */
    private static final class EncryptedPayloadEndpointMapping extends PayloadRootAnnotationMethodEndpointMapping {

        /** Root element of an encrypted SOAP body content. */
        private static final QName ENCRYPTED_DATA = new QName(WSConstants.ENC_NS, WSConstants.ENC_DATA_LN);

        /** Check appended to every invocation chain of this mapping. */
        private static final EndpointInterceptor DECRYPTED_GREET_CHECK = new DecryptedGreetCheck();

        /**
         * Returns {@code greet} for an {@code xenc:EncryptedData} payload on the {@code encrypted} service path,
         * {@code null} for every other request.
         *
         * @param messageContext the message context of the request
         * @return the lookup key, or {@code null}
         * @throws TransformerException when the payload cannot be read
         */
        @Override
        protected QName getLookupKeyForMessage(MessageContext messageContext) throws TransformerException {
            if (!ENCRYPTED_PATH.equals(currentServicePath())) {
                return null;
            }
            return ENCRYPTED_DATA.equals(payloadRoot(messageContext)) ? GREET_REQUEST : null;
        }

        /**
         * Creates the invocation chain of {@code endpoint} with {@code interceptors} followed by the
         * {@link DecryptedGreetCheck}.
         *
         * @param messageContext the message context of the request
         * @param endpoint       the mapped endpoint
         * @param interceptors   the interceptors selected for the request
         * @return the invocation chain
         */
        @Override
        protected EndpointInvocationChain createEndpointInvocationChain(MessageContext messageContext,
                                                                        Object endpoint,
                                                                        EndpointInterceptor[] interceptors) {
            EndpointInterceptor[] chain = Arrays.copyOf(interceptors, interceptors.length + 1);
            chain[interceptors.length] = DECRYPTED_GREET_CHECK;
            return new EndpointInvocationChain(endpoint, chain);
        }
    }

    /**
     * Ends the processing of a request mapped by {@link EncryptedPayloadEndpointMapping} with
     * {@link NoEndpointFoundException}, answered with 404, when its payload root after WS-Security validation is not
     * {@code greet} (D-559).
     */
    private static final class DecryptedGreetCheck extends EndpointInterceptorAdapter {

        /**
         * Returns {@code true} when the request payload root is {@code greet}.
         *
         * @param messageContext the message context of the request
         * @param endpoint       the mapped endpoint
         * @return {@code true}
         * @throws NoEndpointFoundException when the payload root is not {@code greet}
         * @throws TransformerException     when the payload cannot be read
         */
        @Override
        public boolean handleRequest(MessageContext messageContext, Object endpoint) throws TransformerException {
            if (!GREET_REQUEST.equals(payloadRoot(messageContext))) {
                throw new NoEndpointFoundException(messageContext.getRequest());
            }
            return true;
        }
    }

    /**
     * Callback handler that passes {@link WSPasswordCallback}s to its delegate and answers every other callback,
     * such as the principal and cleanup callbacks of {@link Wss4jSecurityInterceptor}, with
     * {@link UnsupportedCallbackException} (D-557).
     */
    private static final class PasswordCallbackOnly implements CallbackHandler {

        /** Handler of the password callbacks. */
        private final CallbackHandler delegate;

        /**
         * Creates the handler.
         *
         * @param delegate the handler of the password callbacks
         */
        private PasswordCallbackOnly(CallbackHandler delegate) {
            this.delegate = delegate;
        }

        /**
         * Passes {@code callbacks} to the delegate when every element is a {@link WSPasswordCallback}.
         *
         * @param callbacks the callbacks to handle
         * @throws IOException                  when the delegate fails with it
         * @throws UnsupportedCallbackException for the first callback that is not a {@link WSPasswordCallback}, or
         *                                      when the delegate fails with it
         */
        @Override
        public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (!(callback instanceof WSPasswordCallback)) {
                    throw new UnsupportedCallbackException(callback, "Unrecognized Callback");
                }
            }
            delegate.handle(callbacks);
        }
    }

    /**
     * Server-side {@link Wss4jSecurityInterceptor} that answers a failed validation with a SOAP 1.1 fault carrying
     * the WS-Security fault code and message of the failure (D-030, D-560).
     *
     * <p>The fault code is {@code wsse:<local part>} of the {@link WSSecurityException#getFaultCode()} of the
     * failure's {@link WSSecurityException} cause, or {@code wsse:InvalidSecurity} when there is no such cause or
     * it has no fault code. The fault string is {@value #FAILED_AUTHENTICATION_FAULT_STRING} for the
     * {@link WSSecurityException.ErrorCode#FAILED_AUTHENTICATION} error code, and
     * {@value #INVALID_SECURITY_FAULT_STRING} for every other failure.
     */
    private static final class ServerSecurityInterceptor extends Wss4jSecurityInterceptor {

        /** Namespace of the WS-Security fault codes. */
        private static final String WSSE_NAMESPACE_URI =
                "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

        /** Prefix of the WS-Security fault codes. */
        private static final String WSSE_PREFIX = "wsse";

        /** Fault code local part used when the failure carries no WS-Security fault code. */
        private static final String INVALID_SECURITY_LOCAL_PART = "InvalidSecurity";

        /** Fault string of a token that could not be authenticated. */
        private static final String FAILED_AUTHENTICATION_FAULT_STRING =
                "The security token could not be authenticated or authorized";

        /** Fault string of every other validation failure. */
        private static final String INVALID_SECURITY_FAULT_STRING =
                "An error was discovered processing the <wsse:Security> header";

        /** Number of causes of a validation failure searched for a {@link WSSecurityException}. */
        private static final int MAX_CAUSE_DEPTH = 16;

        /**
         * Writes the WS-Security SOAP 1.1 fault of {@code ex} into the response of {@code messageContext}.
         *
         * @param ex             the validation failure
         * @param messageContext the message context whose response receives the fault
         * @return {@code false}, ending the request processing
         */
        /** Whether WSS4J validates the subject confirmation of received SAML assertions. */
        private final boolean validateSamlSubjectConfirmation;

        /**
         * Creates the interceptor.
         *
         * @param validateSamlSubjectConfirmation whether WSS4J validates the subject confirmation of received SAML
         *                                        assertions
         */
        private ServerSecurityInterceptor(boolean validateSamlSubjectConfirmation) {
            this.validateSamlSubjectConfirmation = validateSamlSubjectConfirmation;
        }

        /**
         * Returns the request data of the superclass with SAML subject confirmation validation set as configured.
         *
         * @param messageContext the message context of the request
         * @return the WSS4J request data of the validation
         */
        @Override
        protected RequestData initializeValidationRequestData(MessageContext messageContext) {
            RequestData requestData = super.initializeValidationRequestData(messageContext);
            requestData.setValidateSamlSubjectConfirmation(validateSamlSubjectConfirmation);
            return requestData;
        }

        @Override
        protected boolean handleValidationException(WsSecurityValidationException ex, MessageContext messageContext) {
            WSSecurityException cause = wsSecurityCause(ex);
            QName faultCode = new QName(WSSE_NAMESPACE_URI, faultCodeLocalPart(cause), WSSE_PREFIX);
            String faultString = faultString(cause);
            if (logger.isWarnEnabled()) {
                logger.warn("Could not validate request: " + ex.getMessage() + "; answering fault "
                        + WSSE_PREFIX + ":" + faultCode.getLocalPart());
            }
            Soap11Body body = (Soap11Body) ((SoapMessage) messageContext.getResponse()).getSoapBody();
            body.addFault(faultCode, faultString, Locale.ENGLISH);
            return false;
        }

        /**
         * Returns the first {@link WSSecurityException} among the first {@value #MAX_CAUSE_DEPTH} causes of
         * {@code ex}.
         *
         * @param ex the validation failure
         * @return the WSS4J exception, or {@code null} when the chain holds none
         */
        private static WSSecurityException wsSecurityCause(Throwable ex) {
            Throwable current = ex.getCause();
            for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
                if (current instanceof WSSecurityException wsSecurityException) {
                    return wsSecurityException;
                }
                current = current.getCause();
            }
            return null;
        }

        /**
         * Returns the fault code local part of {@code cause}, or {@value #INVALID_SECURITY_LOCAL_PART}.
         *
         * @param cause the WSS4J exception, possibly {@code null}
         * @return the fault code local part
         */
        private static String faultCodeLocalPart(WSSecurityException cause) {
            if (cause == null || cause.getFaultCode() == null) {
                return INVALID_SECURITY_LOCAL_PART;
            }
            return cause.getFaultCode().getLocalPart();
        }

        /**
         * Returns the fault string of {@code cause}.
         *
         * @param cause the WSS4J exception, possibly {@code null}
         * @return {@value #FAILED_AUTHENTICATION_FAULT_STRING} for a failed authentication,
         *         {@value #INVALID_SECURITY_FAULT_STRING} otherwise
         */
        private static String faultString(WSSecurityException cause) {
            if (cause != null && cause.getErrorCode() == WSSecurityException.ErrorCode.FAILED_AUTHENTICATION) {
                return FAILED_AUTHENTICATION_FAULT_STRING;
            }
            return INVALID_SECURITY_FAULT_STRING;
        }
    }
}
