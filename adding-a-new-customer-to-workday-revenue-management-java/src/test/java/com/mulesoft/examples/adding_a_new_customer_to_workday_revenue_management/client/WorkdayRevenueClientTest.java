package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayProperties;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config.WorkdayWsConfig;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.workday.bsvc.CustomerWWSDataType;
import com.workday.bsvc.PutCustomerRequestType;
import com.workday.bsvc.PutCustomerResponseType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.namespace.QName;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.server.endpoint.support.PayloadRootUtils;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.security.wss4j2.Wss4jSecurityInterceptor;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.ws.test.client.ResponseCreators;
import org.springframework.ws.transport.http.ClientHttpRequestMessageSender;
import org.springframework.xml.transform.StringSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Asserts the D-020 failure-mode classification of {@link WorkdayRevenueClient#putCustomer(PutCustomerRequestType)}
 * and the WS-Security UsernameToken, endpoint and {@code SOAPAction} of the Workday call (D-018, D-030).
 *
 * <p>{@link WorkdayRevenueClient} replaces {@code wd-connector:invoke type="Revenue_Management||Put_Customer"}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:42] over
 * {@code wd-connector:config} {@code Workday}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:5]. Every client runs
 * over a {@link WebServiceTemplate} built by {@link WorkdayWsConfig} from the project's own marshaller, SOAP 1.1
 * message factory, UsernameToken interceptor and message sender, with the {@code wday.*} values of
 * {@code application-test.yml}. No Spring application context is started (D-651).
 *
 * <p>Two groups of tests:
 * <ul>
 *   <li>Seven tests send real HTTP requests to a JDK {@link HttpServer} on {@code 127.0.0.1} that answers each
 *       request with the next scripted response, and count the requests it receives:
 *       <ul>
 *         <li>HTTP 401, or a SOAP fault {@code SOAP-ENV:Client.authenticationError}, is re-issued once; a second
 *             HTTP 401 is thrown as {@link UpstreamAuthenticationException};</li>
 *         <li>HTTP 429 is thrown as {@link UpstreamRateLimitException} carrying the {@code Retry-After} value,
 *             after one request;</li>
 *         <li>a read timeout and a refused connection are thrown as {@link UpstreamUnavailableException}, the
 *             read timeout after exactly one request;</li>
 *         <li>a SOAP fault {@code SOAP-ENV:Client.validationError} is rethrown as the unchanged
 *             {@link SoapFaultClientException}, after one request.</li>
 *       </ul></li>
 *   <li>One test runs the template against a Spring WS {@link MockWebServiceServer} and asserts the
 *       {@code Put_Customer_Request} payload, the UsernameToken {@code test-user@test_tenant} with a
 *       {@code PasswordText} password, the empty {@code SOAPAction} and the v35.0 endpoint URI.</li>
 * </ul>
 * The request fixture mirrors the customer name {@code John} of the original MUnit input
 * [adding-a-new-customer-to-workday-revenue-management/src/test/munit/integration-test.xml:40-49].
 *
 * <p>The class and its eight test methods are its only public members, each a {@code TRACEABILITY.md} backward
 * row (D-133).
 */
public class WorkdayRevenueClientTest {

    /** Namespace of the SOAP 1.1 envelope. */
    private static final String SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** Namespace of the Workday Web Services elements and their qualified attributes. */
    private static final String BSVC_NS = "urn:com.workday/bsvc";

    /** Namespace of the OASIS WS-Security 1.0 {@code Security} header and {@code UsernameToken}. */
    private static final String WSSE_NS =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";

    /** Path of the Revenue_Management v35.0 endpoint of the tenant {@code test_tenant} (D-018). */
    private static final String ENDPOINT_PATH = "/ccx/service/test_tenant/Revenue_Management/v35.0";

    /** Customer name of the request fixture and {@code Descriptor} of the scripted success response. */
    private static final String CUSTOMER_NAME = "John";

    /** The {@code Put_Customer_Response} payload whose {@code Customer_Reference} has the {@code Descriptor} John. */
    private static final String PUT_CUSTOMER_RESPONSE =
            "<bsvc:Put_Customer_Response xmlns:bsvc=\"" + BSVC_NS + "\" bsvc:version=\"v35.0\">"
                    + "<bsvc:Customer_Reference bsvc:Descriptor=\"" + CUSTOMER_NAME + "\">"
                    + "<bsvc:ID bsvc:type=\"Customer_Reference_ID\">" + CUSTOMER_NAME + "</bsvc:ID>"
                    + "</bsvc:Customer_Reference>"
                    + "</bsvc:Put_Customer_Response>";

    /** SOAP 1.1 envelope carrying {@link #PUT_CUSTOMER_RESPONSE}. */
    private static final String SUCCESS_ENVELOPE = envelope(PUT_CUSTOMER_RESPONSE);

    /** SOAP 1.1 envelope carrying the Workday authentication fault. */
    private static final String AUTHENTICATION_FAULT_ENVELOPE = envelope(
            "<SOAP-ENV:Fault>"
                    + "<faultcode>SOAP-ENV:Client.authenticationError</faultcode>"
                    + "<faultstring>invalid username or password</faultstring>"
                    + "</SOAP-ENV:Fault>");

    /** SOAP 1.1 envelope carrying a Workday {@code Validation_Fault}. */
    private static final String VALIDATION_FAULT_ENVELOPE = envelope(
            "<SOAP-ENV:Fault>"
                    + "<faultcode>SOAP-ENV:Client.validationError</faultcode>"
                    + "<faultstring>Validation error occurred. Invalid ID value</faultstring>"
                    + "<detail>"
                    + "<wd:Validation_Fault xmlns:wd=\"" + BSVC_NS + "\">"
                    + "<wd:Validation_Error><wd:Message>Invalid ID value</wd:Message></wd:Validation_Error>"
                    + "</wd:Validation_Fault>"
                    + "</detail>"
                    + "</SOAP-ENV:Fault>");

    /** Read timeout of the sender of {@link #putCustomerMakesExactlyOneAttemptOnReadTimeout()}. */
    private static final Duration SHORT_READ_TIMEOUT = Duration.ofSeconds(3);

    /** Delay of the scripted response of {@link #putCustomerMakesExactlyOneAttemptOnReadTimeout()}, in ms. */
    private static final long SLOW_RESPONSE_MILLIS = 10000;

    /** The configuration whose bean methods build every template of this class. */
    private static final WorkdayWsConfig CONFIG = new WorkdayWsConfig();

    /** The {@code wday.*} values of {@code application-test.yml}. */
    private static final WorkdayProperties PROPERTIES =
            new WorkdayProperties("localhost", "test_tenant", "test-user", "test-password");

    /** Marshaller of the generated {@code com.workday.bsvc} classes, initialised once for the class. */
    private static Jaxb2Marshaller marshaller;

    /** SOAP 1.1 message factory, initialised once for the class. */
    private static SaajSoapMessageFactory messageFactory;

    /** UsernameToken interceptor for {@link #PROPERTIES}, initialised once for the class. */
    private static Wss4jSecurityInterceptor securityInterceptor;

    /** Number of requests the local server has received in the current test. */
    private final AtomicInteger requests = new AtomicInteger();

    /** Scripted responses of the local server, one consumed per request, in order. */
    private final ConcurrentLinkedQueue<HttpHandler> responders = new ConcurrentLinkedQueue<>();

    /** Executor of the local server's exchanges. */
    private ExecutorService executor;

    /** The local server of the current test, bound to an ephemeral port of {@code 127.0.0.1}. */
    private HttpServer server;

    /**
     * Builds and initialises the marshaller, the message factory and the UsernameToken interceptor shared by every
     * test.
     *
     * @throws Exception when a bean fails to initialise
     */
    @BeforeAll
    static void createSharedBeans() throws Exception {
        marshaller = init(CONFIG.jaxb2Marshaller());
        messageFactory = init(CONFIG.messageFactory());
        securityInterceptor = init(CONFIG.workdaySecurityInterceptor(PROPERTIES));
    }

    /**
     * Starts the local server on an ephemeral port of the loopback address with one context {@code /} served by
     * {@link #dispatch(HttpExchange)}.
     *
     * @throws IOException when the server cannot be bound
     */
    @BeforeEach
    void startServer() throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
    }

    /** Stops the local server at once and interrupts any exchange still running. */
    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    /**
     * Asserts an HTTP 401 answer is followed by exactly one re-issued request, and the success response of that
     * request is returned with the {@code Descriptor} John (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @DisplayName("Retries once after HTTP 401 and returns the customer")
    public void putCustomerRetriesOnceAfterHttp401AndSucceeds() throws Exception {
        responders.add(bare(401, Map.of()));
        responders.add(soap(200, SUCCESS_ENVELOPE));
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), server.getAddress().getPort());

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest());

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        assertThat(requests.get()).as("requests received").isEqualTo(2);
    }

    /**
     * Asserts two HTTP 401 answers in a row end the call with {@link UpstreamAuthenticationException} for the
     * upstream system {@code Workday} after exactly two requests (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @DisplayName("Throws UpstreamAuthenticationException after a second HTTP 401")
    public void putCustomerThrowsUpstreamAuthenticationAfterSecondHttp401() throws Exception {
        responders.add(bare(401, Map.of()));
        responders.add(bare(401, Map.of()));
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), server.getAddress().getPort());
        PutCustomerRequestType request = putCustomerRequest();

        UpstreamAuthenticationException thrown =
                assertThrows(UpstreamAuthenticationException.class, () -> client.putCustomer(request));

        assertThat(thrown.getUpstreamSystem()).isEqualTo("Workday");
        assertThat(requests.get()).as("requests received").isEqualTo(2);
    }

    /**
     * Asserts an HTTP 500 SOAP fault {@code SOAP-ENV:Client.authenticationError} with the fault string
     * {@code invalid username or password} is followed by exactly one re-issued request, and the success response
     * of that request is returned with the {@code Descriptor} John (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @DisplayName("Retries once after a Workday authentication fault and returns the customer")
    public void putCustomerRetriesOnceAfterAuthenticationFaultAndSucceeds() throws Exception {
        responders.add(soap(500, AUTHENTICATION_FAULT_ENVELOPE));
        responders.add(soap(200, SUCCESS_ENVELOPE));
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), server.getAddress().getPort());

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest());

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        assertThat(requests.get()).as("requests received").isEqualTo(2);
    }

    /**
     * Asserts an HTTP 429 answer with {@code Retry-After: 7} ends the call with {@link UpstreamRateLimitException}
     * carrying the value {@code 7} for the upstream system {@code Workday}, after exactly one request (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @DisplayName("Throws UpstreamRateLimitException carrying Retry-After on HTTP 429")
    public void putCustomerThrowsUpstreamRateLimitWithRetryAfterOnHttp429() throws Exception {
        responders.add(bare(429, Map.of("Retry-After", "7")));
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), server.getAddress().getPort());
        PutCustomerRequestType request = putCustomerRequest();

        UpstreamRateLimitException thrown =
                assertThrows(UpstreamRateLimitException.class, () -> client.putCustomer(request));

        assertThat(thrown.getRetryAfter()).isEqualTo("7");
        assertThat(thrown.getUpstreamSystem()).isEqualTo("Workday");
        assertThat(requests.get()).as("requests received").isEqualTo(1);
    }

    /**
     * Asserts a response delayed 10 seconds, slower than the sender's 3 s read timeout, ends the call with
     * {@link UpstreamUnavailableException} for the upstream system {@code Workday} after exactly one request, and
     * the test completes within 15 seconds (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @Timeout(15)
    @DisplayName("Makes exactly one attempt on a read timeout and throws UpstreamUnavailableException")
    public void putCustomerMakesExactlyOneAttemptOnReadTimeout() throws Exception {
        responders.add(sleep(SLOW_RESPONSE_MILLIS));
        WorkdayRevenueClient client = newClient(readTimeoutSender(SHORT_READ_TIMEOUT), server.getAddress().getPort());
        PutCustomerRequestType request = putCustomerRequest();

        UpstreamUnavailableException thrown =
                assertThrows(UpstreamUnavailableException.class, () -> client.putCustomer(request));

        assertThat(requests.get()).as("requests received").isEqualTo(1);
        assertThat(thrown.getUpstreamSystem()).isEqualTo("Workday");
    }

    /**
     * Asserts a request to a closed port of {@code 127.0.0.1} ends the call with
     * {@link UpstreamUnavailableException} for the upstream system {@code Workday} (D-020).
     *
     * @throws Exception when the client cannot be built or no free port is found
     */
    @Test
    @DisplayName("Throws UpstreamUnavailableException when the connection is refused")
    public void putCustomerThrowsUpstreamUnavailableOnConnectionRefused() throws Exception {
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), closedPort());
        PutCustomerRequestType request = putCustomerRequest();

        UpstreamUnavailableException thrown =
                assertThrows(UpstreamUnavailableException.class, () -> client.putCustomer(request));

        assertThat(thrown.getUpstreamSystem()).isEqualTo("Workday");
    }

    /**
     * Asserts an HTTP 500 SOAP fault {@code SOAP-ENV:Client.validationError} reaches the caller as the unchanged
     * {@link SoapFaultClientException}, with its fault code and fault string, after exactly one request (D-020).
     *
     * @throws Exception when the client cannot be built
     */
    @Test
    @DisplayName("Rethrows a Workday validation fault unchanged")
    public void putCustomerRethrowsValidationFaultUnchanged() throws Exception {
        responders.add(soap(500, VALIDATION_FAULT_ENVELOPE));
        WorkdayRevenueClient client = newClient(CONFIG.workdayMessageSender(), server.getAddress().getPort());
        PutCustomerRequestType request = putCustomerRequest();

        SoapFaultClientException thrown =
                assertThrows(SoapFaultClientException.class, () -> client.putCustomer(request));

        assertThat(thrown).isExactlyInstanceOf(SoapFaultClientException.class);
        assertThat(thrown.getFaultCode().getNamespaceURI()).isEqualTo(SOAP_ENV_NS);
        assertThat(thrown.getFaultCode().getLocalPart()).isEqualTo("Client.validationError");
        assertThat(thrown.getFaultStringOrReason()).isEqualTo("Validation error occurred. Invalid ID value");
        assertThat(requests.get()).as("requests received").isEqualTo(1);
    }

    /**
     * Asserts the request {@link WorkdayRevenueClient#putCustomer(PutCustomerRequestType)} sends through the
     * template {@link WorkdayWsConfig} builds, as a {@link MockWebServiceServer} receives it:
     * <ul>
     *   <li>the target is the v35.0 endpoint {@code https://localhost/ccx/service/test_tenant/Revenue_Management/v35.0}
     *       built from {@code wday.hostname} and {@code wday.tenant} (D-018);</li>
     *   <li>the payload root is {@code {urn:com.workday/bsvc}Put_Customer_Request};</li>
     *   <li>the {@code wsse:Security} header holds exactly one {@code UsernameToken} with the user name
     *       {@code test-user@test_tenant} (D-018) and the password {@code test-password} of type
     *       {@code #PasswordText} (D-030);</li>
     *   <li>the {@code SOAPAction} is empty, {@code ""} or {@code "\"\""}.</li>
     * </ul>
     * The scripted {@code Put_Customer_Response} is returned with the {@code Descriptor} John, and the mock server
     * verifies its single expected request.
     *
     * @throws Exception when the template cannot be built
     */
    @Test
    @DisplayName("Sends a PasswordText UsernameToken user@tenant and an empty SOAPAction")
    public void putCustomerSendsUsernameTokenAndEmptySoapAction() throws Exception {
        WebServiceTemplate template = newTemplate(CONFIG.workdayMessageSender());
        MockWebServiceServer mock = MockWebServiceServer.createServer(template);
        mock.expect((uri, message) -> assertThat(uri.toString())
                        .as("endpoint URI")
                        .isEqualTo("https://localhost" + ENDPOINT_PATH))
                .andExpect((uri, message) -> assertThat(payloadRoot(message))
                        .as("payload root")
                        .isEqualTo(new QName(BSVC_NS, "Put_Customer_Request")))
                .andExpect((uri, message) -> assertUsernameToken(((SaajSoapMessage) message).getDocument()))
                .andExpect((uri, message) -> assertThat(((SoapMessage) message).getSoapAction())
                        .as("SOAPAction")
                        .isIn("", "\"\""))
                .andRespond(ResponseCreators.withPayload(new StringSource(PUT_CUSTOMER_RESPONSE)));
        WorkdayRevenueClient client = new WorkdayRevenueClient(template);

        PutCustomerResponseType response = client.putCustomer(putCustomerRequest());

        assertThat(response.getCustomerReference().getDescriptor()).isEqualTo(CUSTOMER_NAME);
        mock.verify();
    }

    /**
     * Answers one request of the local server: counts it, reads its body, and applies the next scripted responder.
     * With no responder left it answers HTTP 500 with a plain-text body. The exchange is closed afterwards.
     *
     * @param exchange the request and response of one HTTP exchange
     * @throws IOException when the request cannot be read or the response cannot be written
     */
    private void dispatch(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try {
            exchange.getRequestBody().readAllBytes();
            HttpHandler responder = responders.poll();
            if (responder == null) {
                byte[] body = ("No scripted response for request " + requests.get())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            responder.handle(exchange);
        } finally {
            exchange.close();
        }
    }

    /**
     * Returns a responder that answers with {@code status}, {@code Content-Type: text/xml; charset=utf-8} and the
     * UTF-8 bytes of {@code envelope}.
     *
     * @param status   the HTTP status, 500 for a SOAP fault
     * @param envelope the SOAP 1.1 envelope text
     * @return the responder
     */
    private static HttpHandler soap(int status, String envelope) {
        byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
        return exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        };
    }

    /**
     * Returns a responder that answers with {@code status}, the given headers and no body; no
     * {@code WWW-Authenticate} header is added.
     *
     * @param status  the HTTP status
     * @param headers the response headers, by name
     * @return the responder
     */
    private static HttpHandler bare(int status, Map<String, String> headers) {
        return exchange -> {
            headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.sendResponseHeaders(status, -1);
        };
    }

    /**
     * Returns a responder that waits {@code millis} milliseconds and then answers with {@link #SUCCESS_ENVELOPE}.
     * An interrupted wait ends the exchange without a response.
     *
     * @param millis the wait before the response, in milliseconds
     * @return the responder
     */
    private static HttpHandler sleep(long millis) {
        HttpHandler success = soap(200, SUCCESS_ENVELOPE);
        return exchange -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            success.handle(exchange);
        };
    }

    /**
     * Returns a {@link WorkdayRevenueClient} over a new template from {@link #newTemplate} whose default URI is the
     * Revenue_Management v35.0 path on {@code http://127.0.0.1:<port>}.
     *
     * @param sender the message sender of the template
     * @param port   the local port the requests go to
     * @return the client under test
     * @throws Exception when the template fails to initialise
     */
    private static WorkdayRevenueClient newClient(ClientHttpRequestMessageSender sender, int port) throws Exception {
        WebServiceTemplate template = newTemplate(sender);
        template.setDefaultUri("http://127.0.0.1:" + port + ENDPOINT_PATH);
        return new WorkdayRevenueClient(template);
    }

    /**
     * Returns the initialised template that {@link WorkdayWsConfig#webServiceTemplate} builds from the shared
     * marshaller, message factory and UsernameToken interceptor, {@code sender} and {@link #PROPERTIES}.
     *
     * @param sender the message sender of the template
     * @return the template, with the default URI {@code https://localhost/ccx/service/test_tenant/Revenue_Management/v35.0}
     * @throws Exception when the sender or the template fails to initialise
     */
    private static WebServiceTemplate newTemplate(ClientHttpRequestMessageSender sender) throws Exception {
        return init(CONFIG.webServiceTemplate(marshaller, messageFactory, securityInterceptor, init(sender),
                PROPERTIES));
    }

    /**
     * Returns a sender equal to {@link WorkdayWsConfig#workdayMessageSender()}, an HTTP/1.1 JDK {@link HttpClient}
     * that follows no redirect and has no authenticator under one {@link WorkdayHttpStatusInterceptor}, except
     * for the read timeout of its {@link JdkClientHttpRequestFactory}.
     *
     * @param readTimeout the read timeout of the request factory
     * @return the sender
     */
    private static ClientHttpRequestMessageSender readTimeoutSender(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return new ClientHttpRequestMessageSender(
                new InterceptingClientHttpRequestFactory(factory, List.of(new WorkdayHttpStatusInterceptor())));
    }

    /**
     * Returns a {@code Put_Customer_Request} whose {@code Customer_Data/Customer_Name} is John.
     *
     * @return the request fixture
     */
    private static PutCustomerRequestType putCustomerRequest() {
        CustomerWWSDataType customerData = new CustomerWWSDataType();
        customerData.setCustomerName(CUSTOMER_NAME);
        PutCustomerRequestType request = new PutCustomerRequestType();
        request.setCustomerData(customerData);
        return request;
    }

    /**
     * Returns a port of the loopback address with no listener: a server socket is bound to an ephemeral port and
     * closed again.
     *
     * @return the closed port
     * @throws IOException when no server socket can be bound
     */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * Returns the qualified name of the payload root element of {@code message}.
     *
     * @param message the request message
     * @return the payload root name
     * @throws IOException when the payload cannot be read
     */
    private static QName payloadRoot(WebServiceMessage message) throws IOException {
        try {
            return PayloadRootUtils.getPayloadRootQName(message.getPayloadSource(), TransformerFactory.newInstance());
        } catch (TransformerException ex) {
            throw new IOException("Payload of the request cannot be read", ex);
        }
    }

    /**
     * Asserts {@code document} holds exactly one WS-Security {@code UsernameToken}, inside a {@code wsse:Security}
     * header, with the user name {@code test-user@test_tenant} (D-018) and a {@code Password} of type
     * {@code ...#PasswordText} whose text is {@code test-password} (D-030).
     *
     * @param document the SOAP envelope of the request as sent
     */
    private static void assertUsernameToken(Document document) {
        NodeList tokens = document.getElementsByTagNameNS(WSSE_NS, "UsernameToken");
        assertThat(tokens.getLength()).as("UsernameToken elements").isEqualTo(1);
        Element token = (Element) tokens.item(0);
        Node security = token.getParentNode();
        assertThat(security.getNamespaceURI()).as("UsernameToken parent namespace").isEqualTo(WSSE_NS);
        assertThat(security.getLocalName()).as("UsernameToken parent").isEqualTo("Security");
        NodeList usernames = token.getElementsByTagNameNS(WSSE_NS, "Username");
        assertThat(usernames.getLength()).as("Username elements").isEqualTo(1);
        assertThat(usernames.item(0).getTextContent()).as("Username").isEqualTo("test-user@test_tenant");
        NodeList passwords = token.getElementsByTagNameNS(WSSE_NS, "Password");
        assertThat(passwords.getLength()).as("Password elements").isEqualTo(1);
        Element password = (Element) passwords.item(0);
        assertThat(password.getAttribute("Type")).as("Password Type").endsWith("#PasswordText");
        assertThat(password.getTextContent()).as("Password").isEqualTo("test-password");
    }

    /**
     * Calls {@link InitializingBean#afterPropertiesSet()} on {@code bean} when it is an {@link InitializingBean}.
     *
     * @param bean the bean
     * @param <T>  the bean type
     * @return {@code bean}
     * @throws Exception when the bean fails to initialise
     */
    private static <T> T init(T bean) throws Exception {
        if (bean instanceof InitializingBean initializingBean) {
            initializingBean.afterPropertiesSet();
        }
        return bean;
    }

    /**
     * Returns a SOAP 1.1 envelope, prefix {@code SOAP-ENV}, whose body holds {@code body}.
     *
     * @param body the body content
     * @return the envelope text with an XML declaration
     */
    private static String envelope(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_ENV_NS + "\">"
                + "<SOAP-ENV:Body>" + body + "</SOAP-ENV:Body>"
                + "</SOAP-ENV:Envelope>";
    }
}
