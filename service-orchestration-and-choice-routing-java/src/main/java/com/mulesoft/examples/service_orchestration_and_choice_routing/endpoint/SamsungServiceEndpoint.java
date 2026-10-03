package com.mulesoft.examples.service_orchestration_and_choice_routing.endpoint;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.SamsungServiceImpl;
import com.mulesoft.se.samsung.ObjectFactory;
import com.mulesoft.se.samsung.OrderResponse;
import com.mulesoft.se.samsung.Purchase;
import com.mulesoft.se.samsung.PurchaseResponse;
import jakarta.xml.bind.JAXBElement;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * Spring WS endpoint of the Samsung SOAP service: answers operation {@code purchase} of
 * {@code wsdl/samsung.wsdl} (service {@code SamsungServiceService}, port {@code SamsungServicePort},
 * binding {@code SamsungServiceServiceSoapBinding}, document/literal, {@code soapAction=""}).
 *
 * <p>Implements the inbound side of flow {@code samsungService}
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:150-155}):
 * <ul>
 *   <li>{@code http:listener allowedMethods="POST" path="samsung/orders"} on
 *       {@code HTTP_Listener_Configuration1} ({@code listener.http-listener-configuration1.port},
 *       9090), which {@code config.PortPathGuardFilter} keeps on that port (D-011);</li>
 *   <li>{@code cxf:jaxws-service serviceClass="com.mulesoft.se.samsung.SamsungService"}, replaced by
 *       this class. The service endpoint interface {@code com.mulesoft.se.samsung.SamsungService} is
 *       not carried (D-037); {@code wsdl/samsung.wsdl} is the service contract, served unchanged at
 *       {@code /samsung/orders?wsdl} (D-028);</li>
 *   <li>{@code logger level="INFO" message="aaaa"} and
 *       {@code component class="com.mulesoft.se.samsung.SamsungServiceImpl"}, both performed by
 *       {@link SamsungServiceImpl#samsungService(com.mulesoft.se.samsung.OrderRequest)}.</li>
 * </ul>
 *
 * <p>Wire format: SOAP Body payloads in namespace {@value #NAMESPACE_URI} with unqualified
 * children; the namespace prefixes below are illustrative.
 * <pre>{@code
 * request   <sam:purchase xmlns:sam="http://samsung.se.mulesoft.com/">
 *             <orderRequest><name>s-1</name><quantity>1</quantity></orderRequest>
 *           </sam:purchase>
 * response  <sam:purchaseResponse xmlns:sam="http://samsung.se.mulesoft.com/">
 *             <orderResponse><id>1</id><result>ACCEPTED</result><price>2550</price></orderResponse>
 *           </sam:purchaseResponse>
 * }</pre>
 *
 * <p>The endpoint binds and delegates only: it reads the {@code orderRequest} child of the request,
 * passes it unchanged to {@link SamsungServiceImpl#samsungService(com.mulesoft.se.samsung.OrderRequest)},
 * including a {@code null} child, and wraps the returned order response in {@code purchaseResponse}.
 * It neither validates, logs nor catches; an exception raised by the service reaches
 * {@code exception.SoapFaultMappingExceptionResolver}, which answers a {@code soap:Server} fault with
 * the exception message as {@code faultstring}. {@link Purchase}, {@link PurchaseResponse},
 * {@link OrderResponse} and {@link ObjectFactory} are the JAXB types generated from
 * {@code samsung.wsdl} into {@code com.mulesoft.se.samsung}; no JAX-WS, CXF or Mule type is used
 * (D-050). The message dispatcher, the {@code samsungMarshaller} and the SOAP message factory are
 * configured by {@code config.WsConfig}.
 *
 * <p>The endpoint holds no mutable state and is safe for concurrent use.
 */
@Endpoint
public class SamsungServiceEndpoint {

    /** Target namespace of {@code samsung.wsdl} and of its {@code purchase} and {@code purchaseResponse} elements. */
    public static final String NAMESPACE_URI = "http://samsung.se.mulesoft.com/";

    /** Answers the purchase: logs {@code aaaa} and builds the order response. */
    private final SamsungServiceImpl samsungServiceImpl;

    /** Creates the {@code purchaseResponse} payload and its element {@code {NAMESPACE_URI}purchaseResponse}. */
    private final ObjectFactory objectFactory = new ObjectFactory();

    /**
     * Creates the endpoint over the Samsung purchase service.
     *
     * @param samsungServiceImpl the service implementing flow {@code samsungService}
     */
    public SamsungServiceEndpoint(SamsungServiceImpl samsungServiceImpl) {
        this.samsungServiceImpl = samsungServiceImpl;
    }

    /**
     * Binds the {@code purchase} request and returns the Samsung order response wrapped in
     * {@code purchaseResponse}.
     *
     * <p>Mapped to every SOAP request whose Body payload root is
     * {@code {http://samsung.se.mulesoft.com/}purchase}. The {@code orderRequest} child is passed to
     * {@link SamsungServiceImpl#samsungService(com.mulesoft.se.samsung.OrderRequest)} as it was
     * unmarshalled, {@code null} when the request carries none. The response element is
     * {@code {http://samsung.se.mulesoft.com/}purchaseResponse} holding the returned
     * {@code orderResponse}, for example {@code id 1}, {@code result ACCEPTED} and {@code price 2550}
     * for quantity 1.
     *
     * @param request the unmarshalled {@code purchase} element
     * @return the {@code purchaseResponse} element holding the service's order response
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "purchase")
    @ResponsePayload
    public JAXBElement<PurchaseResponse> purchase(@RequestPayload JAXBElement<Purchase> request) {
        Purchase purchase = request.getValue();
        OrderResponse orderResponse = samsungServiceImpl.samsungService(purchase.getOrderRequest());
        PurchaseResponse response = objectFactory.createPurchaseResponse();
        response.setOrderResponse(orderResponse);
        return objectFactory.createPurchaseResponse(response);
    }
}
