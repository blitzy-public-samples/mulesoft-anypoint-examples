package com.mulesoft.examples.service_orchestration_and_choice_routing.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;

import javax.xml.transform.Source;

import com.mulesoft.examples.service_orchestration_and_choice_routing.config.AdditionalPortsConfig;
import com.mulesoft.se.samsung.ObjectFactory;
import com.mulesoft.se.samsung.OrderRequest;
import com.mulesoft.se.samsung.OrderResponse;
import com.mulesoft.se.samsung.Purchase;
import com.mulesoft.se.samsung.PurchaseResponse;
import jakarta.xml.bind.JAXBElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceMessageExtractor;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapMessageFactory;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;

/**
 * Sends the Samsung {@code purchase} SOAP request of {@code wsdl/samsung.wsdl} and returns the HTTP
 * status together with the {@code orderResponse} of the answer.
 *
 * <p>Implements sub-flow {@code samsungWebServiceClient} (fulfillment.xml:94-97), whose
 * {@code cxf:jaxws-client} (operation {@code purchase}, port {@code SamsungServicePort}) builds the
 * request and whose {@code http:request} on {@code HTTP_Request_Configuration} posts it to
 * {@code samsung/orders} on port 9090. The class replaces the original JAX-WS client façade of the
 * WSDL service with a Spring WS {@link WebServiceTemplate} over the JAXB types generated from
 * {@code samsung.wsdl} into {@code com.mulesoft.se.samsung} (D-028); no JAX-WS, CXF or Mule type
 * is used (D-050).
 *
 * <p>Each call is one HTTP {@code POST} with no retry:
 * <ul>
 *   <li><b>Destination.</b> {@code http://<host>:<port>/<path>}, where {@code <host>} is
 *       {@code http-request-configuration.host} ({@code 0.0.0.0}), {@code <port>} is
 *       {@link AdditionalPortsConfig.ListenerPorts#samsungSoapPort()}
 *       ({@code listener.http-listener-configuration1.port}, 9090) and {@code <path>} is
 *       {@code samsung-web-service-client.path} ({@code samsung/orders}), trimmed and prefixed with
 *       {@code /} when it has none (D-140). The destination is built for each call.</li>
 *   <li><b>Envelope.</b> A SOAP 1.1 message from the {@code messageFactory} bean: the {@code soap}
 *       prefix and no {@code soap:Header}. The Body holds {@code purchase} in namespace
 *       {@code http://samsung.se.mulesoft.com/}, marshalled by the {@code samsungMarshaller} bean.
 *       The {@code SOAPAction} header is {@code ""}, the {@code soapAction} of the WSDL binding.</li>
 *   <li><b>Timeouts.</b> Socket read timeout {@code http-request-configuration.response-timeout},
 *       10000 ms when the key is absent; connection timeout
 *       {@code http-request-configuration.connect-timeout}, 30000 ms when the key is absent
 *       (D-131, D-486).</li>
 * </ul>
 *
 * <p>The client returns a {@link PurchaseResult} for every 2xx response other than 202 and 204 whose
 * Body holds a {@code purchaseResponse}; it does not compare the status with 200 (D-486). Every other
 * outcome is an exception, none of which the client catches:
 * <ul>
 *   <li>{@link org.springframework.ws.soap.client.SoapFaultClientException} for an HTTP 500 response
 *       that carries a SOAP fault;</li>
 *   <li>{@link WebServiceTransportException} for a non-2xx response without a SOAP fault, for a 202
 *       or 204 response whatever its body, and for a 2xx response without a body, with an empty Body
 *       or with a Body payload other than {@code purchaseResponse} (D-486);</li>
 *   <li>{@link WebServiceIOException} when the connection, the exchange or the status read fails with
 *       an I/O error, a read timeout included;</li>
 *   <li>{@link org.springframework.oxm.XmlMappingException} when the request cannot be marshalled or
 *       the response payload cannot be unmarshalled with the {@code samsungMarshaller}.</li>
 * </ul>
 *
 * <p>The template is configured once in the constructor and the class holds no other mutable state;
 * it is safe for concurrent use.
 *
 * <pre>{@code
 * OrderRequest request = new OrderRequest();
 * request.setName("Galaxy");
 * request.setQuantity(1);
 * SamsungServiceClient.PurchaseResult result = samsungServiceClient.purchase(request);
 * result.statusCode();                 // 200
 * result.orderResponse().getResult();  // "ACCEPTED"
 * result.orderResponse().getPrice();   // "2550"
 * }</pre>
 */
@Component
public class SamsungServiceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamsungServiceClient.class);

    /** Key of the outbound host of {@code HTTP_Request_Configuration} (mule-config.xml:4). */
    static final String HOST_KEY = "http-request-configuration.host";

    /** Key of the request path of sub-flow {@code samsungWebServiceClient} (fulfillment.xml:96, D-140). */
    static final String PATH_KEY = "samsung-web-service-client.path";

    /** Key of the response timeout, in milliseconds, of a request without {@code responseTimeout} (D-486). */
    static final String RESPONSE_TIMEOUT_KEY = "http-request-configuration.response-timeout";

    /** Key of the connection timeout, in milliseconds, of {@code HTTP_Request_Configuration} (D-486). */
    static final String CONNECT_TIMEOUT_KEY = "http-request-configuration.connect-timeout";

    /** {@code soapAction} of operation {@code purchase} in the {@code samsung.wsdl} binding. */
    static final String SOAP_ACTION = "";

    /** Qualified name of the expected response payload, as written in exception messages. */
    private static final String PURCHASE_RESPONSE_NAME = "{http://samsung.se.mulesoft.com/}purchaseResponse";

    /**
     * Result of one accepted exchange: the HTTP status of a 2xx response and the
     * {@code orderResponse} of its {@code purchaseResponse}.
     *
     * @param statusCode    the HTTP status code of the response, in {@code 200..299} and neither 202
     *                      nor 204
     * @param orderResponse the {@code orderResponse} child of {@code purchaseResponse} ({@code id},
     *                      {@code result}, {@code price}); {@code null} when the
     *                      {@code purchaseResponse} has no {@code orderResponse} child, which the
     *                      WSDL allows ({@code minOccurs="0"})
     */
    public record PurchaseResult(int statusCode, OrderResponse orderResponse) {
    }

    private final ObjectFactory objectFactory = new ObjectFactory();

    private final Jaxb2Marshaller samsungMarshaller;

    private final AdditionalPortsConfig.ListenerPorts listenerPorts;

    private final String host;

    private final String path;

    private final WebServiceTemplate webServiceTemplate;

    /**
     * Creates the client and its {@link WebServiceTemplate}.
     *
     * <p>The template creates request messages with {@code messageFactory}, marshals and unmarshals
     * with {@code samsungMarshaller}, and sends through one {@link HttpUrlConnectionMessageSender}
     * whose read timeout is {@code responseTimeoutMillis} and whose connection timeout is
     * {@code connectTimeoutMillis}. It keeps the Spring WS defaults otherwise:
     * the connection is checked for an error status and for a SOAP fault, faults are resolved by the
     * default {@code SoapFaultMessageResolver}, and no interceptor and no default URI are set.
     *
     * @param samsungMarshaller     the {@code samsungMarshaller} bean, bound to
     *                              {@code com.mulesoft.se.samsung}
     * @param messageFactory        the {@code messageFactory} bean, a SOAP 1.1 message factory
     * @param listenerPorts         the listener ports; {@code samsungSoapPort()} is the destination port
     * @param host                  {@code http-request-configuration.host}, the destination host
     * @param path                  {@code samsung-web-service-client.path}, the destination path
     * @param responseTimeoutMillis {@code http-request-configuration.response-timeout}, the socket
     *                              read timeout in milliseconds, 10000 when the key is absent
     * @param connectTimeoutMillis  {@code http-request-configuration.connect-timeout}, the connection
     *                              timeout in milliseconds, 30000 when the key is absent
     * @throws NullPointerException     when {@code samsungMarshaller}, {@code messageFactory},
     *                                  {@code listenerPorts}, {@code host} or {@code path} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code messageFactory} does not create SOAP messages
     * @throws IllegalStateException    when {@code host} or {@code path} is blank, when
     *                                  {@code responseTimeoutMillis} or {@code connectTimeoutMillis}
     *                                  is not positive, or when host, port and path form no valid
     *                                  URI; the message names the key
     */
    public SamsungServiceClient(
            @Qualifier("samsungMarshaller") Jaxb2Marshaller samsungMarshaller,
            @Qualifier("messageFactory") WebServiceMessageFactory messageFactory,
            AdditionalPortsConfig.ListenerPorts listenerPorts,
            @Value("${http-request-configuration.host}") String host,
            @Value("${samsung-web-service-client.path}") String path,
            @Value("${http-request-configuration.response-timeout:10000}") long responseTimeoutMillis,
            @Value("${http-request-configuration.connect-timeout:30000}") long connectTimeoutMillis) {
        Objects.requireNonNull(samsungMarshaller, "samsungMarshaller");
        Objects.requireNonNull(messageFactory, "messageFactory");
        Objects.requireNonNull(listenerPorts, "listenerPorts");
        Objects.requireNonNull(host, HOST_KEY);
        Objects.requireNonNull(path, PATH_KEY);
        if (!(messageFactory instanceof SoapMessageFactory)) {
            throw new IllegalArgumentException("messageFactory must create SOAP messages, was "
                    + messageFactory.getClass().getName());
        }
        if (host.isBlank()) {
            throw new IllegalStateException(HOST_KEY + " must not be blank");
        }
        if (path.isBlank()) {
            throw new IllegalStateException(PATH_KEY + " must not be blank");
        }
        if (responseTimeoutMillis <= 0) {
            throw new IllegalStateException(RESPONSE_TIMEOUT_KEY + " must be positive, was " + responseTimeoutMillis);
        }
        if (connectTimeoutMillis <= 0) {
            throw new IllegalStateException(CONNECT_TIMEOUT_KEY + " must be positive, was " + connectTimeoutMillis);
        }
        this.samsungMarshaller = samsungMarshaller;
        this.listenerPorts = listenerPorts;
        this.host = host.trim();
        String trimmedPath = path.trim();
        this.path = trimmedPath.startsWith("/") ? trimmedPath : "/" + trimmedPath;
        String uri = serviceUri();
        LOGGER.info("Samsung purchase client posts to {} with read timeout {} ms and connection timeout {} ms",
                uri, responseTimeoutMillis, connectTimeoutMillis);

        HttpUrlConnectionMessageSender messageSender = new HttpUrlConnectionMessageSender();
        messageSender.setReadTimeout(Duration.ofMillis(responseTimeoutMillis));
        messageSender.setConnectionTimeout(Duration.ofMillis(connectTimeoutMillis));

        WebServiceTemplate template = new WebServiceTemplate(messageFactory);
        template.setMarshaller(samsungMarshaller);
        template.setUnmarshaller(samsungMarshaller);
        template.setMessageSender(messageSender);
        template.afterPropertiesSet();
        this.webServiceTemplate = template;
    }

    /**
     * Implements sub-flow {@code samsungWebServiceClient}: sends one {@code purchase} request that
     * wraps {@code orderRequest} and returns the HTTP status with the {@code orderResponse} of the
     * {@code purchaseResponse}.
     *
     * <p>The request Body is {@code <ns:purchase xmlns:ns="http://samsung.se.mulesoft.com/">} with
     * {@code orderRequest} as its {@code orderRequest} child ({@code name}, {@code quantity}); a
     * {@code null} {@code orderRequest} sends {@code purchase} without that child. The header
     * {@code SOAPAction} is {@code ""}. The returned status is the status of a response the
     * template accepted, in {@code 200..299} and neither 202 nor 204.
     *
     * @param orderRequest the order request built by DW-30 ({@code name}, {@code quantity}); may be
     *                     {@code null}
     * @return the HTTP status and the {@code orderResponse} of the {@code purchaseResponse}; never
     *         {@code null}
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the service answers
     *         with a SOAP fault
     * @throws WebServiceTransportException when the service answers with a non-2xx status without a
     *         SOAP fault, with status 202 or 204, or without a {@code purchaseResponse} payload; the
     *         message names the destination URI
     * @throws WebServiceIOException when the connection, the exchange or the status read fails with
     *         an I/O error, a read timeout included
     * @throws org.springframework.oxm.XmlMappingException when the request or the response cannot be
     *         mapped by the {@code samsungMarshaller}
     */
    public PurchaseResult purchase(OrderRequest orderRequest) {
        Purchase purchase = objectFactory.createPurchase();
        purchase.setOrderRequest(orderRequest);
        JAXBElement<Purchase> request = objectFactory.createPurchase(purchase);
        String uri = serviceUri();

        WebServiceMessageCallback requestCallback = message -> {
            samsungMarshaller.marshal(request, message.getPayloadResult());
            ((SoapMessage) message).setSoapAction(SOAP_ACTION);
        };
        WebServiceMessageExtractor<PurchaseResult> responseExtractor = message -> extractPurchaseResult(message, uri);

        PurchaseResult result = webServiceTemplate.sendAndReceive(uri, requestCallback, responseExtractor);
        if (result == null) {
            throw new WebServiceTransportException("No " + PURCHASE_RESPONSE_NAME + " in the response from " + uri);
        }
        LOGGER.debug("Samsung purchase to {} answered HTTP {}", uri, result.statusCode());
        return result;
    }

    /**
     * Reads the HTTP status of the current exchange and the {@code purchaseResponse} of
     * {@code message}.
     *
     * @param message the response message accepted by the template
     * @param uri     the destination URI, written in exception messages
     * @return the status and the {@code orderResponse}, or {@code null} when the Body is empty
     * @throws WebServiceTransportException when the Body payload is not a {@code purchaseResponse}
     * @throws WebServiceIOException        when the status cannot be read
     */
    private PurchaseResult extractPurchaseResult(WebServiceMessage message, String uri) {
        int statusCode = responseStatusCode(uri);
        Source payload = message.getPayloadSource();
        if (payload == null) {
            return null;
        }
        Object unmarshalled = samsungMarshaller.unmarshal(payload);
        if (unmarshalled instanceof JAXBElement<?> element && element.getValue() instanceof PurchaseResponse response) {
            return new PurchaseResult(statusCode, response.getOrderResponse());
        }
        throw new WebServiceTransportException("Unexpected response payload " + describe(unmarshalled)
                + " from " + uri + ", expected " + PURCHASE_RESPONSE_NAME);
    }

    /**
     * Reads the response status from the {@link HttpUrlConnection} of the current exchange.
     *
     * @param uri the destination URI, written in exception messages
     * @return the HTTP status code of the response
     * @throws WebServiceTransportException when the current exchange has no {@link HttpUrlConnection}
     * @throws WebServiceIOException        when {@code HttpURLConnection.getResponseCode()} fails
     */
    private int responseStatusCode(String uri) {
        TransportContext transportContext = TransportContextHolder.getTransportContext();
        WebServiceConnection connection = transportContext == null ? null : transportContext.getConnection();
        if (!(connection instanceof HttpUrlConnection httpConnection)) {
            throw new WebServiceTransportException("No HTTP connection to " + uri
                    + " to read the response status from");
        }
        try {
            return httpConnection.getConnection().getResponseCode();
        } catch (IOException ex) {
            throw new WebServiceIOException("Could not read the HTTP status of the response from " + uri
                    + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Names an unmarshalled payload for an exception message.
     *
     * @param unmarshalled the object the {@code samsungMarshaller} returned
     * @return the element name of a {@link JAXBElement}, otherwise the class name, or
     *         {@code "null"} for {@code null}
     */
    private static String describe(Object unmarshalled) {
        if (unmarshalled instanceof JAXBElement<?> element) {
            return String.valueOf(element.getName());
        }
        return unmarshalled == null ? "null" : unmarshalled.getClass().getName();
    }

    /**
     * Builds the destination URI {@code http://<host>:<port><path>} from the configured host, the
     * current {@link AdditionalPortsConfig.ListenerPorts#samsungSoapPort()} and the configured path;
     * an IPv6 host gets square brackets.
     *
     * @return the destination URI as text
     * @throws IllegalStateException when host, port and path form no valid URI; the message names
     *                               {@link #HOST_KEY} and {@link #PATH_KEY}
     */
    private String serviceUri() {
        int port = listenerPorts.samsungSoapPort();
        try {
            return new URI("http", null, host, port, path, null, null).toString();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(HOST_KEY + " and " + PATH_KEY + " form no valid URI with port " + port
                    + ": " + ex.getMessage(), ex);
        }
    }
}
