package com.mulesoft.examples.web_service_consumer.client;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.mulesoft.tshirt_service.AuthenticationHeader;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Sends the {@code OrderTshirt} and {@code ListInventory} SOAP 1.1 requests of the t-shirt service through the
 * configured {@link WebServiceTemplate} (D-028). The contract is {@code wsdl/tshirt.wsdl}: service
 * {@code TshirtService}, port {@code TshirtServicePort}, binding {@code TshirtServiceSoapBinding}, document/literal.
 * The class replaces the two {@code ws:consumer} elements of
 * {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}, {@code operation="OrderTshirt"} [:25] and
 * {@code operation="ListInventory"} [:38], both on {@code ws:consumer-config} {@code Web_Service_Consumer} [:3].
 *
 * <p>The template comes from {@code config/TshirtWsConfig}: it sends to its default URI,
 * {@code tshirt.service-address}, with the configured timeouts and SOAP 1.1 messages. Each method:
 * <ul>
 *   <li>sets the {@code SOAPAction} of its operation in the WSDL binding;</li>
 *   <li>sends exactly one request to the default URI;</li>
 *   <li>returns the SOAP Body payload element of the response as the document element of a new namespace-aware DOM
 *       document, and does not read the response SOAP header;</li>
 *   <li>lets every exception of the template propagate unchanged:
 *       {@link org.springframework.ws.soap.client.SoapFaultClientException} for a SOAP fault, a {@code TshirtFault}
 *       included; {@link org.springframework.ws.client.WebServiceTransportException} for an HTTP error status
 *       without a SOAP fault; {@link org.springframework.ws.client.WebServiceIOException} for a connection, I/O or
 *       timeout failure.</li>
 * </ul>
 *
 * <p>The class uses Spring WS, Spring OXM, JAXP and the JAXB classes generated from {@code tshirt.wsdl}, and imports
 * no JAX-WS, CXF or Mule type (D-050). Its two fields are final and set once; every call builds its own DOM documents
 * and callback, and the class is safe for concurrent use.
 *
 * <p>Usage, as in {@code service/TshirtOrderService}:
 * <pre>{@code
 * Element order = client.orderTshirt(mapper.toOrderTshirt(json), mapper.authenticationHeader(apiKey));
 * // order: {http://mulesoft.org/tshirt-service}OrderTshirtResponse, holding orderId
 * Element inventory = client.listInventory();
 * // inventory: {http://mulesoft.org/tshirt-service}ListInventoryResponse, holding the inventory items
 * }</pre>
 */
@Component
public class TshirtServiceClient {

    /**
     * {@code soapAction} of operation {@code OrderTshirt} in binding {@code TshirtServiceSoapBinding}
     * [tshirt.wsdl:177].
     */
    public static final String ORDER_TSHIRT_ACTION = "http://mulesoft.org/tshirt-service/order-tshirt";

    /**
     * {@code soapAction} of operation {@code ListInventory} in binding {@code TshirtServiceSoapBinding}
     * [tshirt.wsdl:195].
     */
    public static final String LIST_INVENTORY_ACTION = "http://mulesoft.org/tshirt-service/list-inventory";

    /** Target namespace of the t-shirt service and of its request, response and header elements [tshirt.wsdl:3]. */
    public static final String NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** Message of the {@link IllegalStateException} thrown when a call yields no response message. */
    private static final String NO_RESPONSE = "No response from TshirtService";

    /** Sends every request; its default URI is {@code tshirt.service-address}. */
    private final WebServiceTemplate webServiceTemplate;

    /** Marshals the {@code AuthenticationHeader} SOAP header block of {@code OrderTshirt}. */
    private final Jaxb2Marshaller marshaller;

    /**
     * Creates the client over the {@code webServiceTemplate} and {@code jaxb2Marshaller} beans of
     * {@code config/TshirtWsConfig}.
     *
     * @param webServiceTemplate the SOAP 1.1 template whose default URI is {@code tshirt.service-address}
     * @param marshaller         the marshaller of the classes generated from {@code tshirt.wsdl}
     * @throws IllegalArgumentException when {@code webServiceTemplate} or {@code marshaller} is {@code null}
     */
    public TshirtServiceClient(WebServiceTemplate webServiceTemplate, Jaxb2Marshaller marshaller) {
        this.webServiceTemplate = requireArgument(webServiceTemplate, "webServiceTemplate");
        this.marshaller = requireArgument(marshaller, "marshaller");
    }

    /**
     * Sends one {@code OrderTshirt} request, the operation of {@code ws:consumer operation="OrderTshirt"}
     * [web-service-consumer/src/main/app/tshirt-service-consumer.xml:25], to the template's default URI.
     *
     * <p>The SOAP 1.1 request carries:
     * <ul>
     *   <li>the {@code SOAPAction} {@value #ORDER_TSHIRT_ACTION};</li>
     *   <li>one SOAP header block, {@code header} as the {@code Jaxb2Marshaller} writes it: the element
     *       {@code AuthenticationHeader} of namespace {@value #NAMESPACE} with its unqualified {@code apiKey} child,
     *       under the namespace prefix JAXB assigns;</li>
     *   <li>{@code payload} as the SOAP Body content, unchanged.</li>
     * </ul>
     * The response SOAP header, {@code APIUsageInformation} included, is not read.
     *
     * @param payload the {@code OrderTshirt} request body, as {@code mapper/TshirtMapper.toOrderTshirt} builds it
     * @param header  the {@code AuthenticationHeader} SOAP header, as {@code mapper/TshirtMapper.authenticationHeader}
     *                builds it
     * @return the response SOAP Body payload element, {@code OrderTshirtResponse} of namespace {@value #NAMESPACE},
     *         as the document element of a new namespace-aware DOM document; {@code null} when the response SOAP
     *         Body holds no element
     * @throws IllegalArgumentException when {@code payload} or {@code header} is {@code null}; no request is sent
     * @throws IllegalStateException    with the message {@code No response from TshirtService} when the HTTP response
     *                                  carries no SOAP message, such as a 202 or 204 response or an empty body; or
     *                                  when the JDK provides no namespace-aware {@code DocumentBuilder}
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the response is a SOAP fault, a
     *                                  {@code TshirtFault} included
     * @throws org.springframework.ws.client.WebServiceTransportException when the response has an HTTP error status
     *                                  and no SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection, the exchange or a timeout
     *                                  fails
     * @throws org.springframework.oxm.XmlMappingException when {@code header} cannot be marshalled
     */
    public Element orderTshirt(Source payload, AuthenticationHeader header) {
        requireArgument(payload, "payload");
        requireArgument(header, "header");
        DOMResult result = new DOMResult(newDocument());
        WebServiceMessageCallback callback = message -> {
            SoapMessage soapMessage = (SoapMessage) message;
            soapMessage.setSoapAction(ORDER_TSHIRT_ACTION);
            marshaller.marshal(header, soapMessage.getSoapHeader().getResult());
        };
        // One send per call on the default URI; template exceptions propagate unchanged.
        boolean received = webServiceTemplate.sendSourceAndReceiveToResult(payload, callback, result);
        return responseElement(received, result);
    }

    /**
     * Sends one {@code ListInventory} request, the operation of {@code ws:consumer operation="ListInventory"}
     * [web-service-consumer/src/main/app/tshirt-service-consumer.xml:38], to the template's default URI.
     *
     * <p>The SOAP 1.1 request carries the {@code SOAPAction} {@value #LIST_INVENTORY_ACTION}, no SOAP header block,
     * and as its SOAP Body content the empty element
     * {@code <ns0:ListInventory xmlns:ns0="http://mulesoft.org/tshirt-service"/>}, with no children and no text.
     * The response SOAP header is not read.
     *
     * @return the response SOAP Body payload element, {@code ListInventoryResponse} of namespace {@value #NAMESPACE},
     *         as the document element of a new namespace-aware DOM document; {@code null} when the response SOAP
     *         Body holds no element
     * @throws IllegalStateException with the message {@code No response from TshirtService} when the HTTP response
     *                               carries no SOAP message, such as a 202 or 204 response or an empty body; or when
     *                               the JDK provides no namespace-aware {@code DocumentBuilder}
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the response is a SOAP fault, a
     *                               {@code TshirtFault} included
     * @throws org.springframework.ws.client.WebServiceTransportException when the response has an HTTP error status
     *                               and no SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection, the exchange or a timeout fails
     */
    public Element listInventory() {
        Document request = newDocument();
        Element listInventory = request.createElementNS(NAMESPACE, "ns0:ListInventory");
        listInventory.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ns0", NAMESPACE);
        request.appendChild(listInventory);
        // SOAPAction only: the request carries no SOAP header block.
        WebServiceMessageCallback callback = message -> ((SoapMessage) message).setSoapAction(LIST_INVENTORY_ACTION);
        DOMResult result = new DOMResult(newDocument());
        // One send per call on the default URI; template exceptions propagate unchanged.
        boolean received = webServiceTemplate.sendSourceAndReceiveToResult(new DOMSource(request), callback, result);
        return responseElement(received, result);
    }

    /**
     * Reads the response payload element that a send wrote into {@code result}.
     *
     * @param received the value {@code sendSourceAndReceiveToResult} returned
     * @param result   the result holder passed to that send, over a document from {@link #newDocument()}
     * @return the document element of the result document; {@code null} when the response SOAP Body held no element
     * @throws IllegalStateException with the message {@code No response from TshirtService} when {@code received} is
     *                               {@code false}
     */
    private static Element responseElement(boolean received, DOMResult result) {
        if (!received) {
            throw new IllegalStateException(NO_RESPONSE);
        }
        return ((Document) result.getNode()).getDocumentElement();
    }

    /**
     * Creates an empty DOM document from a new namespace-aware {@link DocumentBuilderFactory}.
     *
     * @return a new document without any node
     * @throws IllegalStateException wrapping the {@link ParserConfigurationException} when the JDK provides no
     *                               namespace-aware {@code DocumentBuilder}
     */
    private static Document newDocument() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            return factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("No namespace-aware DocumentBuilder is available", e);
        }
    }

    /**
     * Returns {@code value} when it is not {@code null}.
     *
     * @param value the argument to check
     * @param name  the parameter name written in the exception message
     * @param <T>   the argument type
     * @return {@code value}
     * @throws IllegalArgumentException with the message {@code <name> must not be null} when {@code value} is
     *                                  {@code null}
     */
    private static <T> T requireArgument(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
