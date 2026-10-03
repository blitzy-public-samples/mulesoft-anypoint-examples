package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayHttpStatusInterceptor;
import java.net.http.HttpClient;
import java.util.List;
import org.apache.wss4j.dom.WSConstants;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.webservices.WebServicesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.client.support.interceptor.ClientInterceptor;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.transport.http.ClientHttpRequestMessageSender;

/**
 * Spring WS client beans of the Workday Revenue_Management v35.0 SOAP service, replacing the Workday
 * connector global element {@code wd-connector:config} {@code Workday}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:5] that
 * {@code wd-connector:invoke type="Revenue_Management||Put_Customer"} of {@code add-customer-flow} uses
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:42] (D-018).
 *
 * <p>Defines five beans:
 * <ul>
 *   <li>{@link #jaxb2Marshaller()}: marshals and unmarshals the {@code com.workday.bsvc} classes that
 *       {@code jaxb2-maven-plugin} generates from {@code wsdl/Revenue_Management_v35.0.wsdl}, namespace
 *       {@code urn:com.workday/bsvc} (D-018, D-078);</li>
 *   <li>{@link #messageFactory()}: creates SOAP 1.1 messages;</li>
 *   <li>{@link #workdaySecurityInterceptor(WorkdayProperties)}: adds a WS-Security UsernameToken
 *       {@code <wday.user>@<wday.tenant>} with a {@code PasswordText} password to each request
 *       (D-018, D-030);</li>
 *   <li>{@link #workdayMessageSender()}: sends each request once over an HTTP/1.1 JDK {@link HttpClient}
 *       and raises HTTP 401 and 429 through {@link WorkdayHttpStatusInterceptor} (D-020, D-173);</li>
 *   <li>{@link #webServiceTemplate(Jaxb2Marshaller, SaajSoapMessageFactory, Wss4jSecurityInterceptor,
 *       ClientHttpRequestMessageSender, WorkdayProperties)}: the context's only {@link WebServiceTemplate},
 *       addressed to {@link WorkdayProperties#endpointUri()} and injected into
 *       {@code client.WorkdayRevenueClient} (D-018).</li>
 * </ul>
 *
 * <p>Excludes {@link WebServicesAutoConfiguration} from the whole context: no
 * {@code MessageDispatcherServlet} is registered at {@code /services/*}, and every request other than
 * {@code POST /} receives the answers of {@code exception.GlobalExceptionHandler} (D-508).
 * {@code WebServiceTemplateAutoConfiguration} stays active.
 *
 * <p>No bean opens a connection, resolves a host name or calls Workday while the context starts, and
 * the committed {@code TODO} values of the {@code wday.*} keys (D-012) start it. The first Workday call
 * is made by the first {@code POST /}. No bean repeats a request; the one re-issue after an
 * authentication failure belongs to {@code client.WorkdayRevenueClient.execute} (D-020). The class
 * imports no JAX-WS, CXF or Mule type (D-050).
 *
 * <p>Usage, as in {@code client.WorkdayRevenueClient}:
 * <pre>{@code
 * Object response = webServiceTemplate.marshalSendAndReceive(objectFactory.createPutCustomerRequest(request));
 * }</pre>
 * The call goes to the template's default URI with {@code SOAPAction: ""}.
 */
@Configuration(proxyBeanMethods = false)
// WebServicesAutoConfiguration is excluded for the whole context: no MessageDispatcherServlet at /services/* (D-508).
@ImportAutoConfiguration(exclude = WebServicesAutoConfiguration.class)
public class WorkdayWsConfig {

    /**
     * Creates the JAXB marshaller for the context path {@code com.workday.bsvc}, the package of the
     * classes generated from the Revenue_Management v35.0 WSDL (D-018, D-078). Every other setting keeps
     * its default: the JAXB context is created at startup, and neither schema validation nor MTOM is
     * enabled.
     *
     * @return the marshaller and unmarshaller of the Workday request and response elements
     */
    @Bean
    public Jaxb2Marshaller jaxb2Marshaller() {
        Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
        marshaller.setContextPath("com.workday.bsvc");
        return marshaller;
    }

    /**
     * Creates the SAAJ message factory for SOAP 1.1, the version of the document/literal
     * Revenue_Management v35.0 binding (D-018). Spring initializes the factory through
     * {@code afterPropertiesSet()}.
     *
     * @return the SOAP 1.1 message factory of the Workday template
     */
    @Bean
    public SaajSoapMessageFactory messageFactory() {
        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        return messageFactory;
    }

    /**
     * Creates the WS-Security interceptor that adds a UsernameToken {@code <wday.user>@<wday.tenant>}
     * with a {@code PasswordText} password to each outgoing request and validates no response
     * (D-018, D-030). Nonce, created, timestamp and {@code mustUnderstand} keep their defaults, and
     * UsernameToken is the only securement action.
     *
     * <p>Example: {@code wday.user=integration} and {@code wday.tenant=acme} send
     * {@code <wsse:Username>integration@acme</wsse:Username>}.
     *
     * @param properties the {@code wday.*} keys; supplies {@link WorkdayProperties#username()} and
     *                   {@link WorkdayProperties#password()}
     * @return the UsernameToken interceptor of the Workday template
     */
    @Bean
    public Wss4jSecurityInterceptor workdaySecurityInterceptor(WorkdayProperties properties) {
        Wss4jSecurityInterceptor interceptor = new Wss4jSecurityInterceptor();
        interceptor.setSecurementActions("UsernameToken");
        interceptor.setSecurementUsername(properties.username());
        interceptor.setSecurementPassword(properties.password());
        interceptor.setSecurementPasswordType(WSConstants.PW_TEXT);
        interceptor.setValidationActions("NoSecurity");
        interceptor.setValidateResponse(false);
        return interceptor;
    }

    /**
     * Creates the message sender: an HTTP/1.1 JDK {@link HttpClient} that follows no redirect and has
     * no authenticator, proxy, connect timeout or executor, under a {@link JdkClientHttpRequestFactory}
     * with its default read timeout, wrapped in an {@link InterceptingClientHttpRequestFactory} holding
     * one {@link WorkdayHttpStatusInterceptor} (D-020, D-173). HTTP 401 and 429 responses are raised by
     * that interceptor; every request is sent once and never repeated by the sender.
     *
     * @return the {@link ClientHttpRequestMessageSender} of the Workday template
     */
    @Bean
    public ClientHttpRequestMessageSender workdayMessageSender() {
        // Option B sender of D-173: no Authenticator, proxy, timeout, executor or retry on the HttpClient.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        ClientHttpRequestFactory requestFactory = new InterceptingClientHttpRequestFactory(
                new JdkClientHttpRequestFactory(httpClient), List.of(new WorkdayHttpStatusInterceptor()));
        return new ClientHttpRequestMessageSender(requestFactory);
    }

    /**
     * Creates the context's only {@link WebServiceTemplate}: SOAP 1.1 messages from
     * {@code messageFactory}, {@code jaxb2Marshaller} as marshaller and unmarshaller,
     * {@code workdaySecurityInterceptor} as its one client interceptor, {@code workdayMessageSender} as
     * its one message sender, and {@link WorkdayProperties#endpointUri()}
     * ({@code https://<wday.hostname>/ccx/service/<wday.tenant>/Revenue_Management/v35.0}) as its
     * default URI (D-018). Fault handling keeps the defaults: a SOAP fault raises
     * {@code SoapFaultClientException}, and the connection is checked for faults and errors (D-020).
     *
     * @param jaxb2Marshaller            the marshaller of the {@code com.workday.bsvc} classes
     * @param messageFactory             the SOAP 1.1 message factory
     * @param workdaySecurityInterceptor the UsernameToken interceptor
     * @param workdayMessageSender       the HTTP message sender
     * @param properties                 the {@code wday.*} keys; supplies the default URI
     * @return the template that {@code client.WorkdayRevenueClient} calls
     */
    @Bean
    public WebServiceTemplate webServiceTemplate(Jaxb2Marshaller jaxb2Marshaller,
            SaajSoapMessageFactory messageFactory,
            Wss4jSecurityInterceptor workdaySecurityInterceptor,
            ClientHttpRequestMessageSender workdayMessageSender,
            WorkdayProperties properties) {
        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(jaxb2Marshaller);
        template.setUnmarshaller(jaxb2Marshaller);
        template.setInterceptors(new ClientInterceptor[] {workdaySecurityInterceptor});
        template.setMessageSender(workdayMessageSender);
        template.setDefaultUri(properties.endpointUri());
        return template;
    }
}
